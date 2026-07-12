<!-- category: Docs -->
- Fixed stale Pro-tool count in `mcp/mcpize.yaml`: the manifest claimed "35 Pro tools" in two places, but `PRO_TOOLS` in `mcp/src/tiers.ts` has 27 entries (counted programmatically by importing the module). Also dropped "multi-platform setup" from the Pro description — setup guides moved to Free in MCP 4.0.5 (follow-up to the "26 free tools" sibling drift fixed in #2675).
