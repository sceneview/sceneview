#!/usr/bin/env node

/**
 * CLI entrypoint for the `sceneview-mcp` npm package.
 *
 * Two transports, one server (`./server.ts`):
 *
 *   npx sceneview-mcp          stdio — Claude Desktop, Claude Code, Cursor,
 *                              Windsurf and every other local MCP client.
 *   npx sceneview-mcp --http   Streamable HTTP on `/mcp` — the remote surface
 *                              for ChatGPT / Codex and the OpenAI API `mcp`
 *                              tool (`./http.ts`, free tier only).
 *
 * This file used to be a 1 200+ line monolith that defined every tool, its
 * handler, and the stdio transport all at once. The tool definitions and
 * handler logic live in `./tools/`, the `Server` wiring (resources, tool
 * list/call, Pro proxy dispatch via `./proxy.ts`) in `./server.ts`, so this
 * file only picks a transport.
 *
 * IMPORTANT: the stdio runtime behaviour must stay identical to v4.0.0 for
 * existing npm consumers. Do not reorder checks, do not change content
 * strings, do not touch disclaimers, do not print the banner differently.
 */

import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { PACKAGE_VERSION } from "./generated/version.js";
import { DEFAULT_PRICING_URL, isProxyConfigured } from "./proxy.js";
import { createSceneViewServer } from "./server.js";

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

if (process.argv.includes("--http")) {
  // Remote surface. Loaded lazily so the stdio path never pays for
  // `node:http` + the Streamable HTTP transport it does not use.
  const { startHttpServer } = await import("./http.js");
  await startHttpServer();
} else {
  logStartupBanner();
  const server = createSceneViewServer({ surface: "stdio" });
  const transport = new StdioServerTransport();
  await server.connect(transport);
}
