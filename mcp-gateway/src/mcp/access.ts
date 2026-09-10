/**
 * Per-tool tier gate for the multiplexed registry.
 *
 * The free/pro map lives in `./tiers.ts`, next to this gate: the
 * sceneview-mcp npm package dropped its paid tier and serves every tool
 * for free, so the gateway owns the only tier map left.
 *
 * The gate is intentionally permissive on the `pro` tier entry: any
 * authenticated user on `pro` or `team` can call every tool. Unknown
 * tools fall back to `pro` (conservative default) and are therefore
 * blocked on the free tier.
 */

import type { DispatchContext } from "./types.js";
import { getToolTier } from "./tiers.js";

/**
 * Returns true if the given dispatch context is allowed to call the
 * tool. This is the signature that `handleMcpRequest` passes in:
 * `(toolName, ctx: DispatchContext | undefined)`.
 */
export function canCallTool(
  toolName: string,
  ctx: DispatchContext | undefined,
): boolean {
  const required = getToolTier(toolName);
  if (required === "free") return true;
  const tier = ctx?.tier;
  // required === "pro" — pro and team users pass, free is blocked.
  return tier === "pro" || tier === "team";
}

/** Re-exports the upstream tier resolver for callers that want the raw value. */
export { getToolTier };
