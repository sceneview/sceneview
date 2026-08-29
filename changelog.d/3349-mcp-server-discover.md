<!-- category: Fixed -->
<!-- RELEASE NOTE (maintainer-only):
     `@hasmcp/mcp-spec-test` 0.1.1 reported `sceneview-mcp` as "not conformant —
     6 requirements violated" against MCP 2026-07-28, with 22 further cases
     unverified, because `server/discover` answered `-32601 Method not found`.
     The issue's premise that the server *advertises* 2026-07-28 turned out to
     be wrong — nothing in `mcp/` ever named that revision; the report's
     "supported 2026-07-28, 2025-11-25" header is the suite's own vendored
     schema window. Bumping the SDK was not an option either:
     `@modelcontextprotocol/sdk` 1.30.0, the newest release, tops out at
     2025-11-25 and ships no `server/discover` at all. So the handler is
     hand-rolled and advertises exactly what the SDK can serve, read from
     `SUPPORTED_PROTOCOL_VERSIONS` rather than hardcoded. `mcp/` stays on its
     own version track (4.0.16 -> 4.1.0); publishing to npm remains manual. -->
- **`sceneview-mcp` answers `server/discover` (MCP 2026-07-28) instead of `-32601 Method not found`.** A 2026-07-28-aware client now learns in one handshake-free round trip which revisions the server actually serves, plus its identity, capabilities and cache hints (`ttlMs`, `cacheScope`), rather than being left to guess after a "method not found". Against `@hasmcp/mcp-spec-test` 0.1.1 the 2026-07-28 run goes from 8 passed / 6 failed / 22 not verified to 14 passed / 0 failed, and 2025-11-25 stays at 0 failures.
