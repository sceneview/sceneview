/**
 * The SceneView MCP `Server`, built once and shared by both transports.
 *
 * `index.ts` (stdio, the `npx sceneview-mcp` path) and `http.ts` (Streamable
 * HTTP, the `--http` path for ChatGPT / Codex / the OpenAI API `mcp` tool)
 * used to be the same 200 lines of handler registration, so they now call
 * `createSceneViewServer()` and only differ by the `surface` option:
 *
 *   - `"stdio"`  — lists and runs every tool, in-process. Every tool is free
 *                  and nothing is forwarded anywhere.
 *   - `"remote"` — the anonymous public surface. Lists everything except the
 *                  local-only tools of `surfaces.ts` (the ones needing your
 *                  own third-party credentials) and refuses those names at
 *                  call time with a clear `isError` text.
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
import { isLocalOnlyTool } from "./surfaces.js";
import { recordClientInit, recordToolCall } from "./telemetry.js";
import { API_DOCS, dispatchTool, TOOL_DEFINITIONS } from "./tools/index.js";
import type { ToolDefinition } from "./tools/types.js";
import {
  listWidgetResources,
  readUiExtension,
  readWidgetResource,
  serveWidgetsTo,
} from "./widgets.js";

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
  _meta?: Record<string, unknown>;
};

/** Tool declarations the remote surface publishes: everything but the local-only tools. */
export function remoteToolDefinitions(): ToolDefinition[] {
  return TOOL_DEFINITIONS.filter((tool) => !isLocalOnlyTool(tool.name));
}

/** The refusal a remote caller gets for a local-only tool name. Exported for tests. */
export function remoteUnavailableRefusal(toolName: string): string {
  return (
    `\`${toolName}\` is not available on this remote server: it needs your own ` +
    `third-party credentials, which a shared anonymous endpoint cannot hold. ` +
    `Run the package locally (\`npx sceneview-mcp\`) to use it. It is free.`
  );
}

/**
 * Strips the MCP Apps `_meta.ui` pointer off a definition or a result.
 *
 * Everything else in `_meta` survives, the `openai/*` keys included: those are
 * a different vendor's mechanism, and a client that speaks the extension is
 * not the host that reads them.
 */
function withoutWidgetPointer<T extends { _meta?: Record<string, unknown> }>(value: T): T {
  if (!value._meta || !("ui" in value._meta)) return value;
  const meta = { ...value._meta };
  delete meta.ui;
  return { ...value, _meta: meta };
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

  /**
   * Whether this session gets MCP Apps widget pointers.
   *
   * The client's declared mime types arrive in the `initialize` handshake and
   * the SDK keeps them on the server, so both tool handlers can ask the same
   * question the HTTP gateway asks — otherwise the two transports drift and
   * the stdio server hands a widget to a host that declared it cannot render
   * one (#3485). Silence stays permissive: see `serveWidgetsTo`.
   */
  const widgetsEnabled = () => serveWidgetsTo(readUiExtension(server.getClientCapabilities()));

  server.setRequestHandler(ListToolsRequestSchema, async () => {
    const withWidgets = widgetsEnabled();
    const negotiate = <T extends { _meta?: Record<string, unknown> }>(tool: T): T =>
      withWidgets ? tool : withoutWidgetPointer(tool);

    // Remote surface: everything the shared endpoint can actually run.
    // Listing a tool it has no credentials for would advertise a dead end.
    if (remote) return { tools: remoteToolDefinitions().map(negotiate) };

    // Local stdio: the whole tool list, unmodified. Every tool is free.
    return { tools: TOOL_DEFINITIONS.map(negotiate) };
  });

  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    // Same decision as the declaration above, so a host that discovers
    // widgets from RESULTS rather than declarations cannot be handed a
    // pointer the session negotiated away (#3485). The tool still runs and
    // still answers with its text content: degradation, not failure.
    const negotiateResult = (result: SdkCallToolResult): SdkCallToolResult =>
      widgetsEnabled() ? result : withoutWidgetPointer(result);

    const toolName = request.params.name;
    const args = request.params.arguments as Record<string, unknown> | undefined;

    // Record anonymous telemetry (fire-and-forget, non-blocking, opt-out via
    // SCENEVIEW_TELEMETRY=0). See `telemetry.ts` and `PRIVACY.md`.
    recordToolCall(toolName);

    // Remote surface: a local-only tool name is refused with an explanation.
    // Unknown names fall through to `dispatchTool`, whose "Unknown tool"
    // answer is the honest one for a typo. Locally, nothing is refused —
    // every tool runs in-process.
    if (remote && isLocalOnlyTool(toolName)) {
      return {
        content: [{ type: "text", text: remoteUnavailableRefusal(toolName) }],
        isError: true,
      };
    }

    // The dispatcher returns the narrower SceneView `ToolResult` shape, which
    // structurally matches the MCP SDK's `CallToolResult` but TS can't prove
    // it (the SDK's zod-derived type has additional optional members).
    const result = await dispatchTool(toolName, args);
    return negotiateResult(result as unknown as SdkCallToolResult);
  });

  return server;
}
