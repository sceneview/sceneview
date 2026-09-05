/**
 * Multiplexed MCP tool registry for the SceneView hosted gateway.
 *
 * This module takes the six upstream tool libraries (sceneview-mcp plus
 * the five verticals) and exposes them as a single flat registry:
 *
 *   - `getAllTools()` returns the concatenated list of tool definitions.
 *   - `dispatch(toolName, args, ctx)` routes a call to the owning library.
 *
 * Name collisions between packages are detected at import time and throw
 * a descriptive error, which surfaces immediately during Worker startup or
 * vitest collection — well before a user can hit a duplicated endpoint.
 *
 * Imports use relative paths into the sibling `mcp/` source tree rather
 * than an npm workspace because the monorepo publishes each package
 * independently. The `.js` extensions on the imports are mandatory in
 * Node16-style ESM resolution and they are rewritten by TypeScript's
 * bundler-moduleResolution to the matching `.ts` source files at build
 * time and by Hono's bundled Worker at runtime.
 */

import * as SceneViewTools from "../../../mcp/src/tools/index.js";
import * as AutomotiveTools from "../../../mcp/packages/automotive/src/tools.js";
import * as GamingTools from "../../../mcp/packages/gaming/src/tools.js";
import * as HealthcareTools from "../../../mcp/packages/healthcare/src/tools.js";
import * as InteriorTools from "../../../mcp/packages/interior/src/tools.js";
import * as RerunTools from "../../../mcp/packages/rerun/src/tools.js";

import type {
  DispatchContext,
  ToolDefinition,
  ToolLibrary,
  ToolResult,
} from "./types.js";

// ── Raw library list ────────────────────────────────────────────────────────

const LIBRARIES: ToolLibrary[] = [
  {
    id: "sceneview",
    label: "sceneview-mcp",
    definitions: SceneViewTools.TOOL_DEFINITIONS,
    dispatch: (name, args, ctx) => SceneViewTools.dispatchTool(name, args, ctx),
  },
  {
    id: "automotive",
    label: "automotive-3d-mcp",
    definitions: AutomotiveTools.TOOL_DEFINITIONS,
    dispatch: (name, args, ctx) => AutomotiveTools.dispatchTool(name, args, ctx),
  },
  {
    id: "gaming",
    label: "gaming-3d-mcp",
    definitions: GamingTools.TOOL_DEFINITIONS,
    dispatch: (name, args, ctx) => GamingTools.dispatchTool(name, args, ctx),
  },
  {
    id: "healthcare",
    label: "healthcare-3d-mcp",
    definitions: HealthcareTools.TOOL_DEFINITIONS,
    dispatch: (name, args, ctx) => HealthcareTools.dispatchTool(name, args, ctx),
  },
  {
    id: "interior",
    label: "interior-design-3d-mcp",
    definitions: InteriorTools.TOOL_DEFINITIONS,
    dispatch: (name, args, ctx) => InteriorTools.dispatchTool(name, args, ctx),
  },
  {
    id: "rerun",
    label: "rerun-3d-mcp",
    definitions: RerunTools.TOOL_DEFINITIONS,
    dispatch: (name, args, ctx) => RerunTools.dispatchTool(name, args, ctx),
  },
  // The MCP Apps widget tool (`view_3d_model`) is part of `sceneview-mcp`
  // above: the upstream package ships both the declaration and the widget
  // HTML (`mcp/src/widgets.ts`), so there is no gateway-native library any
  // more. See `./widget-tools.ts`.
];

// ── Build a name → library lookup and fail fast on collisions ──────────────

/**
 * Map of tool name → owning library, built eagerly at import time.
 *
 * Using a plain Map guarantees O(1) dispatch and lets us report collisions
 * across packages with a precise error message.
 */
const OWNERS: Map<string, ToolLibrary> = (() => {
  const owners = new Map<string, ToolLibrary>();
  const collisions: Array<{ name: string; a: string; b: string }> = [];

  for (const lib of LIBRARIES) {
    for (const def of lib.definitions) {
      const existing = owners.get(def.name);
      if (existing) {
        collisions.push({ name: def.name, a: existing.label, b: lib.label });
        continue;
      }
      owners.set(def.name, lib);
    }
  }

  if (collisions.length > 0) {
    const details = collisions
      .map((c) => `  - "${c.name}" is defined by both ${c.a} and ${c.b}`)
      .join("\n");
    throw new Error(
      `SceneView MCP registry: tool name collision detected between packages.\n${details}\n` +
        `Rename the offending tool in one of the packages before redeploying the gateway.`,
    );
  }

  return owners;
})();

// ── Public API ──────────────────────────────────────────────────────────────

/**
 * Returns the merged list of tool definitions from all five libraries,
 * as a fresh array (safe to mutate).
 */
export function getAllTools(): ToolDefinition[] {
  const out: ToolDefinition[] = [];
  for (const lib of LIBRARIES) {
    for (const def of lib.definitions) out.push(def);
  }
  return out;
}

/** Lightweight read-only view of the merged registry. */
export function getRegistrySummary(): {
  libraries: Array<{ id: string; label: string; toolCount: number }>;
  totalTools: number;
} {
  const libraries = LIBRARIES.map((lib) => ({
    id: lib.id,
    label: lib.label,
    toolCount: lib.definitions.length,
  }));
  return {
    libraries,
    totalTools: libraries.reduce((n, l) => n + l.toolCount, 0),
  };
}

/** Returns the tool definition for a given name, or `undefined`. */
export function getToolDefinition(name: string): ToolDefinition | undefined {
  const owner = OWNERS.get(name);
  if (!owner) return undefined;
  return owner.definitions.find((d) => d.name === name);
}

/**
 * Routes a tool call to the library that owns that tool name.
 *
 * Returns a JSON-RPC-ish error result for unknown tool names. The gateway
 * callers (MCP transport layer, step 5) are responsible for mapping this
 * onto the MCP wire-level error shape.
 */
export async function dispatch(
  toolName: string,
  args: Record<string, unknown> | undefined,
  ctx: DispatchContext = {},
): Promise<ToolResult> {
  const owner = OWNERS.get(toolName);
  if (!owner) {
    return {
      content: [{ type: "text", text: `Unknown tool: ${toolName}` }],
      isError: true,
    };
  }
  return owner.dispatch(toolName, args, ctx);
}

/** Exposed for tests and diagnostics. */
export const __internals = { LIBRARIES, OWNERS };
