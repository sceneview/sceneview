// ─── Tool surfaces ────────────────────────────────────────────────────────────
//
// Every SceneView MCP tool is free. There is no paid tier, no API key, no
// gateway: the "Pro" tier was retired in August 2026 and `/pricing` has
// answered 404 ever since, so the upsell that pointed at it was removed.
//
// The hosted worker itself is still up, and this comment used to claim
// otherwise — it said `/mcp` had answered 404 since August too, which was
// wrong and made `mcp/README.md`'s hosted-endpoint paragraph look stale.
// Measured 2026-09-22: `GET /health` → 200 `{"status":"ok","version":"4.1.0"}`,
// `POST /mcp` → a valid `initialize` for `sceneview-mcp` 4.1.0 serving 29
// tools, `GET /mcp` → 405 (the endpoint is POST-only, which is what a bare
// browser hit sees). Only `/pricing` is gone. Re-probe with
// `curl -sS -m 10 -o /dev/null -w '%{http_code}' https://mcp.sceneview.dev/health`
// before editing this paragraph again.
//
// What remains is a surface question, not a price one. Four generation tools
// need per-user credentials and external infrastructure that a shared,
// anonymous HTTP endpoint has no way to hold, so they run in the local stdio
// package only. Everything else is served by both surfaces.

/**
 * Tools the anonymous remote surface (`--http`, `worker.ts`) does not serve.
 *
 * Not a paywall: these call out to per-user third-party accounts (World Labs,
 * the preview host) or return heavy generated artifacts, and a shared endpoint
 * has no caller to bill them to. `generate_world` in particular spends up to
 * ~3000 World Labs credits per call from whatever key the process holds and
 * relays caller-supplied media URLs, so it must never run behind an anonymous
 * endpoint. Run `npx sceneview-mcp` locally to use them.
 */
const LOCAL_ONLY_TOOLS: readonly string[] = [
  "render_3d_preview",
  "create_3d_artifact",
  "generate_scene",
  "generate_world",
] as const;

/** Returns true if the tool is served by the local stdio package only. */
export function isLocalOnlyTool(toolName: string): boolean {
  return LOCAL_ONLY_TOOLS.includes(toolName);
}

/** Returns the names of the tools the remote surface does not serve. */
export function getLocalOnlyToolNames(): string[] {
  return [...LOCAL_ONLY_TOOLS];
}
