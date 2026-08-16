/**
 * Tool counts advertised by the public dashboard, derived from the registry.
 *
 * Every number the landing / pricing / docs pages print about the tool
 * surface comes from here, and every value here is computed from the same
 * sources the gateway actually serves — never written down. The pages used
 * to hardcode them, and all five drifted at once: the landing's three stat
 * cards understated the free, Pro and package counts alike, /docs opened on
 * a stale free count, and /pricing told self-hosters that every tool the
 * stdio package declares runs without a signup — three of them are
 * Pro-gated and refuse to run without a key. A pricing page that overstates
 * what a free install does is the one kind of stale number that costs money
 * rather than credibility.
 *
 * The historical values are deliberately not spelled out here: the claim
 * gate (`tools/check-mcp-tool-claims.js`) reads this file and cannot tell a
 * quoted past mistake from a present claim — correctly, since a comment
 * naming a wrong count is exactly how a wrong count gets copied back out.
 * They are in the changelog fragment instead.
 *
 * The hosted gateway and the stdio npm package do NOT serve the same free
 * surface, and conflating them is what produced the 28-vs-29 disagreement
 * between two adjacent paragraphs of /docs. `view_3d_model` is a
 * gateway-native widget tool (see `mcp/widget-tools.ts`): it is free, but
 * it only exists in the hosted transport. Hence two distinct constants
 * rather than one, each named for the surface it describes.
 */

import * as SceneViewTools from "../../../mcp/src/tools/index.js";
import { getToolTier } from "../../../mcp/src/tiers.js";
import { getAllTools, getRegistrySummary } from "../mcp/registry.js";

/** Registry ids that are not one of the specialised vertical packages. */
const NON_VERTICAL_LIBRARIES = new Set(["sceneview", "widgets"]);

const mountedNames = getAllTools().map((t) => t.name);
const stdioNames = SceneViewTools.TOOL_DEFINITIONS.map((t) => t.name);

/** Tools the hosted gateway serves to an anonymous client (no API key). */
export const HOSTED_FREE_TOOL_COUNT = mountedNames.filter(
  (n) => getToolTier(n) === "free",
).length;

/** Tools the hosted gateway gates behind a Pro subscription. */
export const PRO_TOOL_COUNT = mountedNames.filter(
  (n) => getToolTier(n) === "pro",
).length;

/** Every tool the gateway mounts, both tiers. */
export const TOTAL_TOOL_COUNT = mountedNames.length;

/**
 * Tools a local `npx -y sceneview-mcp` install runs with no key at all.
 *
 * Lower than {@link HOSTED_FREE_TOOL_COUNT} by the gateway-only widget
 * tools, and lower than {@link STDIO_TOTAL_TOOL_COUNT} by the Pro tools the
 * package declares but refuses to run unattributed.
 */
export const STDIO_FREE_TOOL_COUNT = stdioNames.filter(
  (n) => getToolTier(n) === "free",
).length;

/** Every tool the stdio package declares, including the Pro-gated ones. */
export const STDIO_TOTAL_TOOL_COUNT = stdioNames.length;

const verticals = getRegistrySummary().libraries.filter(
  (lib) => !NON_VERTICAL_LIBRARIES.has(lib.id),
);

/** Number of specialised vertical packages (Automotive, Gaming, …). */
export const VERTICAL_PACKAGE_COUNT = verticals.length;

/** Tools contributed by the vertical packages, i.e. Pro minus the generation helpers. */
export const VERTICAL_TOOL_COUNT = verticals.reduce(
  (n, lib) => n + lib.toolCount,
  0,
);

/** Pro tools that are not part of a vertical package (3D preview, artifact, scene generation). */
export const GENERATION_TOOL_COUNT = PRO_TOOL_COUNT - VERTICAL_TOOL_COUNT;
