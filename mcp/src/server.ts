/**
 * The SceneView MCP `Server`, built once and shared by both transports.
 *
 * `index.ts` (stdio, the `npx sceneview-mcp` path) and `http.ts` (Streamable
 * HTTP, the `--http` path for ChatGPT / Codex / the OpenAI API `mcp` tool)
 * used to be the same 200 lines of handler registration, so they now call
 * `createSceneViewServer()` and only differ by the `surface` option:
 *
 *   - `"stdio"`  — lists every tool (Pro ones prefixed `[PRO]` unless an API
 *                  key is set) and forwards Pro calls to the hosted gateway.
 *                  This is the v4.0.0 behaviour and MUST stay byte-identical
 *                  for existing npm consumers: same content strings, same
 *                  order of checks.
 *   - `"remote"` — the anonymous public surface. Lists ONLY the free tier and
 *                  refuses Pro tool names at call time with a clear `isError`
 *                  text. There is no key on a shared endpoint, so there is
 *                  nothing to unlock and nothing to forward.
 *
 * Resources (`sceneview://api`, `sceneview://known-issues`, the two
 * `examples://` patterns and the `ui://widget/3d-viewer.html` MCP Apps
 * widget) are identical on both surfaces.
 */

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import {
  CallToolRequestSchema,
  ListResourcesRequestSchema,
  ListToolsRequestSchema,
  ReadResourceRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import {
  buildDiscoverResult,
  DiscoverRequestSchema,
  SERVER_CAPABILITIES,
  SERVER_INFO,
} from "./discover.js";
import { DEMO_WITH_SETTINGS_EXAMPLE, SKETCHFAB_STREAMING_EXAMPLE } from "./examples.js";
import { LATEST_SCENEVIEW_RELEASE } from "./generated/version.js";
import { fetchKnownIssues } from "./issues.js";
import { dispatchProxyToolCall, isProxyConfigured } from "./proxy.js";
import { recordClientInit, recordToolCall } from "./telemetry.js";
import { getToolTier, isProTool, TOOL_TIERS } from "./tiers.js";
import { API_DOCS, dispatchTool, TOOL_DEFINITIONS } from "./tools/index.js";
import type { ToolDefinition } from "./tools/types.js";
import { listWidgetResources, readWidgetResource } from "./widgets.js";

/** Which transport the server is being built for. See the file header. */
export type ServerSurface = "stdio" | "remote";

export interface SceneViewServerOptions {
  /** Defaults to `"stdio"`, the historical (and only pre-4.2) behaviour. */
  surface?: ServerSurface;
}

/**
 * The SDK types a request handler's result as the zod-derived
 * `CallToolResult`, which SceneView's narrower `ToolResult` structurally
 * matches but TS cannot prove. One cast, in one place.
 */
type SdkCallToolResult = {
  content: Array<{ type: "text"; text: string }>;
  isError?: boolean;
};

/** Tool declarations the remote surface publishes: the free tier, nothing else. */
export function remoteToolDefinitions(): ToolDefinition[] {
  return TOOL_DEFINITIONS.filter((tool) => getToolTier(tool.name) === "free");
}

/** The refusal a remote caller gets for a Pro tool name. Exported for tests. */
export function remoteProToolRefusal(toolName: string): string {
  return (
    `\`${toolName}\` is a Pro tool and is not available on this remote server, ` +
    `which exposes only the free SceneView tools. To use Pro tools, run the ` +
    `package locally (\`npx sceneview-mcp\`) with a \`SCENEVIEW_API_KEY\`.`
  );
}

export function createSceneViewServer(options: SceneViewServerOptions = {}): Server {
  const surface = options.surface ?? "stdio";
  const remote = surface === "remote";

  // `SERVER_INFO` / `SERVER_CAPABILITIES` live in `./discover.ts` so the
  // handshake and `server/discover` answer the identity/capability question the
  // same way, from one source.
  const server = new Server({ ...SERVER_INFO }, { capabilities: { ...SERVER_CAPABILITIES } });

  // ─── server/discover (MCP 2026-07-28) ──────────────────────────────────────
  //
  // Handshake-free discovery: answered before `initialize`, with no session and
  // no negotiated version. The SDK routes on the method literal and imposes no
  // pre-initialization gate, so registering the handler is enough. See
  // `./discover.ts` for why we answer a 2026-07-28 method while serving
  // 2025-11-25 (issue #3349).
  server.setRequestHandler(DiscoverRequestSchema, async () => buildDiscoverResult());

  // ─── Telemetry (anonymous, opt-out via SCENEVIEW_TELEMETRY=0) ──────────────
  //
  // Fire once when the client finishes the handshake. See `telemetry.ts` and
  // `PRIVACY.md` for what's collected and how to opt out.
  server.oninitialized = () => {
    recordClientInit(server.getClientVersion());
  };

  // ─── Resources ─────────────────────────────────────────────────────────────

  server.setRequestHandler(ListResourcesRequestSchema, async () => ({
    resources: [
      {
        uri: "sceneview://api",
        name: "SceneView API Reference",
        description: `Complete SceneView ${LATEST_SCENEVIEW_RELEASE} API — SceneView, ARSceneView, SceneScope DSL, ARSceneScope DSL, node types, resource loading, camera, gestures, math types, threading rules, and common patterns. Read this before writing any SceneView code.`,
        mimeType: "text/markdown",
      },
      {
        uri: "sceneview://known-issues",
        name: "SceneView Open GitHub Issues",
        description:
          "Live list of open issues from the SceneView GitHub repository. Check this before reporting a bug or when something isn't working — there may already be a known workaround.",
        mimeType: "text/markdown",
      },
      {
        uri: "examples://demo-with-settings",
        name: "Example — DemoScaffold v2 (full-screen scene + ModalBottomSheet)",
        description:
          "Pattern for full-screen 3D / AR scene + Material 3 ModalBottomSheet controls. The DemoScaffold v2 contract used by every demo in samples/android-demo (issue #1154, PR #1169). Read this before adding a new demo with settings.",
        mimeType: "text/markdown",
      },
      {
        uri: "examples://sketchfab-streaming",
        name: "Example — Stream Sketchfab CC-BY models into a SceneView demo",
        description:
          "Pattern for streaming CC-BY licensed glTF models from Sketchfab on demand instead of bundling 30 MB of GLBs in the APK. Uses SketchfabAssetResolver + SampleAssets registry + per-slug bundled fallback (Stage 2 of umbrella issue #1152). Read this before adding a streamed demo.",
        mimeType: "text/markdown",
      },
      // MCP Apps widgets (`ui://…`), appended after the historical four so
      // existing consumers see the same list prefix they always did.
      ...listWidgetResources(),
    ],
  }));

  server.setRequestHandler(ReadResourceRequestSchema, async (request) => {
    switch (request.params.uri) {
      case "sceneview://api":
        return {
          contents: [{ uri: "sceneview://api", mimeType: "text/markdown", text: API_DOCS }],
        };

      case "sceneview://known-issues": {
        const issues = await fetchKnownIssues();
        return {
          contents: [{ uri: "sceneview://known-issues", mimeType: "text/markdown", text: issues }],
        };
      }

      case "examples://demo-with-settings":
        return {
          contents: [
            {
              uri: "examples://demo-with-settings",
              mimeType: "text/markdown",
              text: DEMO_WITH_SETTINGS_EXAMPLE,
            },
          ],
        };

      case "examples://sketchfab-streaming":
        return {
          contents: [
            {
              uri: "examples://sketchfab-streaming",
              mimeType: "text/markdown",
              text: SKETCHFAB_STREAMING_EXAMPLE,
            },
          ],
        };

      default: {
        const widget = readWidgetResource(request.params.uri);
        if (widget) return { contents: [widget] };
        throw new Error(`Unknown resource: ${request.params.uri}`);
      }
    }
  });

  // ─── Tools ─────────────────────────────────────────────────────────────────

  server.setRequestHandler(ListToolsRequestSchema, async () => {
    // Remote surface: the free tier only. There is no key on a shared
    // endpoint, so a `[PRO]` prefix would advertise tools nobody can call.
    if (remote) return { tools: remoteToolDefinitions() };

    // v4 lite mode: we trust the gateway to enforce Pro access at call time,
    // so listing is purely cosmetic here. If no API key is set we still prefix
    // Pro tool descriptions with "[PRO]" so the AI knows an upgrade is needed
    // and surfaces the upsell in its responses; with a key we expose the full
    // list unmodified.
    const unlocked = isProxyConfigured();
    const tools = TOOL_DEFINITIONS.map((tool) => {
      if (unlocked || !isProTool(tool.name)) return tool;
      return { ...tool, description: `[PRO] ${tool.description}` };
    });
    return { tools };
  });

  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const toolName = request.params.name;
    const args = request.params.arguments as Record<string, unknown> | undefined;

    // Record anonymous telemetry (fire-and-forget, non-blocking, opt-out via
    // SCENEVIEW_TELEMETRY=0). See `telemetry.ts` and `PRIVACY.md`.
    recordToolCall(toolName, getToolTier(toolName));

    // ── v4 lite-mode routing ───────────────────────────────────────────────
    //
    // Free tools execute locally, same as 3.6.x. Pro tools are forwarded to
    // the hosted gateway at sceneview-mcp.mcp-tools-lab.workers.dev/mcp —
    // that's where auth, metering, and Stripe live. If no API key is set,
    // `dispatchProxyToolCall` returns a friendly stub that points at the
    // pricing page (handles the upsell itself, no separate denied-response
    // step needed).
    //
    // Remote surface: a KNOWN Pro tool name is refused outright. Unknown
    // names fall through to `dispatchTool`, whose "Unknown tool" answer is
    // the honest one (unknown tools default to "pro" in the tier map, and
    // "that's a Pro tool" would be a lie about a typo).
    if (isProTool(toolName)) {
      if (remote) {
        if (toolName in TOOL_TIERS) {
          return {
            content: [{ type: "text", text: remoteProToolRefusal(toolName) }],
            isError: true,
          };
        }
      } else {
        const result = await dispatchProxyToolCall(toolName, args);
        return result as unknown as SdkCallToolResult;
      }
    }

    // The dispatcher returns the narrower SceneView `ToolResult` shape, which
    // structurally matches the MCP SDK's `CallToolResult` but TS can't prove
    // it (the SDK's zod-derived type has additional optional members).
    const result = await dispatchTool(toolName, args);
    return result as unknown as SdkCallToolResult;
  });

  return server;
}
