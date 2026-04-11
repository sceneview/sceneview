#!/usr/bin/env node
/**
 * stdio entrypoint for this vertical MCP package.
 *
 * All tool metadata and handlers now live in `./tools.ts`. This file is
 * a thin adapter that wires them into the MCP stdio server. Runtime
 * behaviour is identical to the pre-refactor monolith.
 */
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CallToolRequestSchema, ListToolsRequestSchema, } from "@modelcontextprotocol/sdk/types.js";
import { TOOL_DEFINITIONS, dispatchTool } from "./tools.js";
const server = new Server({ name: "game-dev-mcp", version: "1.0.0" }, { capabilities: { tools: {} } });
// ─── Tools ────────────────────────────────────────────────────────────────────
server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: TOOL_DEFINITIONS,
}));
server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const result = await dispatchTool(request.params.name, request.params.arguments);
    return result;
});
// ─── Start ────────────────────────────────────────────────────────────────────
async function main() {
    const transport = new StdioServerTransport();
    await server.connect(transport);
}
main().catch((err) => {
    console.error("Fatal:", err);
    process.exit(1);
});
