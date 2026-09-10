// ─── Tool surfaces ────────────────────────────────────────────────────────────
//
// Every SceneView MCP tool is free. There is no paid tier, no API key, no
// gateway: the hosted worker that once served a "Pro" tier was deleted in
// August 2026 and both of its URLs (`/mcp` and `/pricing`) have answered 404
// ever since, so the upsell that pointed at them was removed.
//
// What remains is a surface question, not a price one. Three generation tools
// need per-user credentials and external infrastructure that a shared,
// anonymous HTTP endpoint has no way to hold, so they run in the local stdio
// package only. Everything else is served by both surfaces.

/**
 * Tools the anonymous remote surface (`--http`, `worker.ts`) does not serve.
 *
 * Not a paywall: these three call out to per-user third-party accounts
 * (Tripo, the preview host) or return heavy generated artifacts, and a shared
 * endpoint has no caller to bill them to. Run `npx sceneview-mcp` locally to
 * use them.
 */
const LOCAL_ONLY_TOOLS: readonly string[] = [
  "render_3d_preview",
  "create_3d_artifact",
  "generate_scene",
] as const;

/** Returns true if the tool is served by the local stdio package only. */
export function isLocalOnlyTool(toolName: string): boolean {
  return LOCAL_ONLY_TOOLS.includes(toolName);
}

/** Returns the names of the tools the remote surface does not serve. */
export function getLocalOnlyToolNames(): string[] {
  return [...LOCAL_ONLY_TOOLS];
}
