#!/usr/bin/env node

/**
 * stdio entrypoint for the `sceneview-mcp` npm package.
 *
 * This file used to be a 1 200+ line monolith that defined every tool,
 * its handler, and the stdio transport all at once. The tool definitions
 * and handler logic now live in `./tools/`, so this file is a thin
 * adapter that wires the library into the MCP stdio server plus the
 * two MCP resources (`sceneview://api`, `sceneview://known-issues`) and
 * the proxy dispatch for Pro-tier tools (`./proxy.ts`).
 *
 * IMPORTANT: the runtime behaviour must stay identical to v4.0.0 for
 * existing npm consumers. Do not reorder checks, do not change content
 * strings, do not touch disclaimers.
 */

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
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
import { LATEST_SCENEVIEW_RELEASE, PACKAGE_VERSION } from "./generated/version.js";
import { fetchKnownIssues } from "./issues.js";
import { DEFAULT_PRICING_URL, dispatchProxyToolCall, isProxyConfigured } from "./proxy.js";
import { recordClientInit, recordToolCall } from "./telemetry.js";
import { getToolTier, isProTool } from "./tiers.js";
import { API_DOCS, dispatchTool, TOOL_DEFINITIONS } from "./tools/index.js";

// ─── v4 lite-mode startup banner ─────────────────────────────────────────────
//
// MCP servers must keep stdout clean for JSON-RPC, so we log to stderr.
// Claude Desktop surfaces this in the server's "Logs" panel. The banner
// tells the user which mode they're in (hosted vs free) and where to
// upgrade, without blocking the transport handshake.

function logStartupBanner(): void {
  if (process.env.SCENEVIEW_MCP_QUIET === "1") return;
  const proxied = isProxyConfigured();
  const mode = proxied ? "HOSTED (Pro tools → gateway)" : "LITE (free tools only)";
  const lines = [
    `[sceneview-mcp] v${PACKAGE_VERSION} — ${mode}`,
    proxied
      ? `[sceneview-mcp] Pro tool calls will be forwarded to the hosted gateway.`
      : `[sceneview-mcp] All setup, migration & docs tools are free. Pro packages (Automotive/Gaming/Healthcare/Interior) at ${DEFAULT_PRICING_URL}`,
  ];
  for (const line of lines) process.stderr.write(`${line}\n`);
}

logStartupBanner();

// `SERVER_INFO` / `SERVER_CAPABILITIES` live in `./discover.ts` so the
// handshake and `server/discover` answer the identity/capability question the
// same way, from one source.
const server = new Server({ ...SERVER_INFO }, { capabilities: { ...SERVER_CAPABILITIES } });

// ─── server/discover (MCP 2026-07-28) ────────────────────────────────────────
//
// Handshake-free discovery: answered before `initialize`, with no session and
// no negotiated version. The SDK routes on the method literal and imposes no
// pre-initialization gate, so registering the handler is enough. See
// `./discover.ts` for why we answer a 2026-07-28 method while serving
// 2025-11-25 (issue #3349).
server.setRequestHandler(DiscoverRequestSchema, async () => buildDiscoverResult());

// ─── Telemetry (anonymous, opt-out via SCENEVIEW_TELEMETRY=0) ────────────────
//
// Fire once when the client finishes the handshake. See `telemetry.ts` and
// `PRIVACY.md` for what's collected and how to opt out.
server.oninitialized = () => {
  recordClientInit(server.getClientVersion());
};

// ─── Resources ───────────────────────────────────────────────────────────────

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

    default:
      throw new Error(`Unknown resource: ${request.params.uri}`);
  }
});

// ─── Tools ───────────────────────────────────────────────────────────────────

server.setRequestHandler(ListToolsRequestSchema, async () => {
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

  // ── v4 lite-mode routing ─────────────────────────────────────────────────
  //
  // Free tools execute locally, same as 3.6.x. Pro tools are forwarded to
  // the hosted gateway at sceneview-mcp.mcp-tools-lab.workers.dev/mcp —
  // that's where auth, metering, and Stripe live. If no API key is set,
  // `dispatchProxyToolCall` returns a friendly stub that points at the
  // pricing page (handles the upsell itself, no separate denied-response
  // step needed).
  if (isProTool(toolName)) {
    const result = await dispatchProxyToolCall(toolName, args);
    return result as unknown as {
      content: Array<{ type: "text"; text: string }>;
      isError?: boolean;
    };
  }

  // The dispatcher returns the narrower SceneView `ToolResult` shape, which
  // structurally matches the MCP SDK's `CallToolResult` but TS can't prove
  // it (the SDK's zod-derived type has additional optional members).
  const result = await dispatchTool(toolName, args);
  return result as unknown as {
    content: Array<{ type: "text"; text: string }>;
    isError?: boolean;
  };
});

const transport = new StdioServerTransport();
await server.connect(transport);
