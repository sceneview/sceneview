/**
 * Widget-bearing tools: which tool renders which MCP Apps resource.
 *
 * `view_3d_model` used to be defined HERE, as a gateway-native library, on
 * the theory that the stdio package could not serve widget HTML. Since the
 * upstream package ships the widget itself (`mcp/src/widgets.ts`) and
 * declares the tool in `mcp/src/tools/definitions.ts` — with
 * `_meta.ui.resourceUri` on the declaration and on the result — the gateway
 * consumes that one definition through the regular `sceneview-mcp` library
 * in `registry.ts`. A second copy here would collide on the tool name and
 * drift from the upstream contract.
 *
 * What remains is the lookup the transport uses to attach (or re-affirm)
 * the widget pointer, derived from the declarations rather than typed by
 * hand so the two can never disagree.
 */

import { TOOL_DEFINITIONS } from "../../../mcp/src/tools/index.js";

/** Tool name → widget resource URI, read from each declaration's `_meta.ui`. */
export const WIDGET_TOOL_RESOURCE: Record<string, string> = Object.fromEntries(
  TOOL_DEFINITIONS.flatMap((def) => {
    const ui = (def._meta as { ui?: { resourceUri?: unknown } } | undefined)?.ui;
    return typeof ui?.resourceUri === "string" ? [[def.name, ui.resourceUri]] : [];
  }),
);

/** Returns the resource URI a tool should expose, or `null` if none. */
export function widgetResourceFor(toolName: string): string | null {
  return WIDGET_TOOL_RESOURCE[toolName] ?? null;
}
