// ─── Tool tier system ─────────────────────────────────────────────────────────
//
// Defines free vs pro tool access for SceneView MCP.

export type Tier = "free" | "pro";

// ─── Free tools ──────────────────────────────────────────────────────────────
//
// 4.0.5: setup guides + cross-platform docs moved from Pro to Free. Rationale:
// these are the entry-point tools any developer hits when integrating
// SceneView. Gating them behind a paywall was killing adoption (see
// `.claude/plans/strategic-pivot-april-2026.md`). Pro is now reserved for
// the 4 vertical packages (Automotive / Gaming / Healthcare / Interior) and
// the 3 generation tools (preview, artifact, scene generation).

const FREE_TOOLS: readonly string[] = [
  // Gateway-only widget tool. It is defined in
  // `mcp-gateway/src/mcp/widget-tools.ts`, NOT in this package's
  // TOOL_DEFINITIONS — the stdio server never lists or serves it. The entry
  // here is load-bearing anyway: the gateway's tier gate
  // (`mcp-gateway/src/mcp/access.ts`) resolves tiers through this map and
  // unknown tools default to "pro", so removing this line would silently
  // paywall the anonymous ChatGPT 3D-viewer widget.
  "view_3d_model",
  "list_samples",
  "get_sample",
  "get_setup",
  "get_node_reference",
  "list_platforms",
  "validate_code",
  "get_troubleshooting",
  "debug_issue",
  "get_best_practices",
  "get_animation_guide",
  "get_gesture_guide",
  "get_performance_tips",
  "get_material_guide",
  "get_collision_guide",
  "get_platform_roadmap",
  "search_models",
  "generate_3d_model",
  "analyze_project",
  "search_android_docs",
  "fetch_android_doc",

  // Setup guides (moved from Pro in 4.0.5)
  "get_ios_setup",
  "get_web_setup",
  "get_ar_setup",
  "get_platform_setup",

  // Migration tools (moved from Pro in 4.0.5)
  "migrate_code",
  "get_migration_guide",

  // Static docs guides (moved from Pro in 4.0.5)
  "get_model_optimization_guide",
  "get_web_rendering_guide",
] as const;

// ─── Pro tools ────────────────────────────────────────────────────────────────

const PRO_TOOLS: readonly string[] = [
  // Generation tools (heavier, may involve external infra)
  "render_3d_preview",
  "create_3d_artifact",
  "generate_scene",

  // Automotive package
  "get_car_configurator",
  "get_dashboard_3d",
  "get_parts_catalog",
  "get_hud_overlay",
  "get_ar_showroom",
  "list_car_models",
  "get_ev_charging_station_viewer",
  "get_car_paint_shader",
  "validate_automotive_code",

  // Gaming package
  "get_physics_game",
  "get_particle_effects",
  "get_level_editor",
  "get_inventory_3d",
  "get_character_viewer",
  "list_game_models",
  "validate_game_code",

  // Healthcare package
  "get_surgical_planning",
  "get_dental_viewer",
  "get_medical_imaging",
  "get_anatomy_viewer",
  "get_molecule_viewer",
  "list_medical_models",
  "validate_medical_code",

  // Interior package
  "get_room_planner",
  "get_lighting_design",
  "get_room_tour",
  "get_material_switcher",
  "get_furniture_placement",
  "list_furniture_models",
  "validate_interior_code",

  // Rerun package (Pro per its README — "All 5 rerun tools are Pro tier").
  // Until #2697 these five (and the six package additions above) rode the
  // unknown-tool default-to-pro fallback; behaviour is unchanged, the map
  // is just explicit now so a forgotten mapping is distinguishable from a
  // deliberate Pro tool.
  "setup_rerun_project",
  "generate_ar_logger",
  "generate_python_sidecar",
  "embed_web_viewer",
  "explain_concept",
] as const;

// ─── Tier map ─────────────────────────────────────────────────────────────────

export const TOOL_TIERS: Record<string, Tier> = Object.fromEntries([
  ...FREE_TOOLS.map((name) => [name, "free" as Tier]),
  ...PRO_TOOLS.map((name) => [name, "pro" as Tier]),
]);

// ─── Helper functions ─────────────────────────────────────────────────────────

/** Returns true if the tool requires a Pro subscription. Unknown tools default to pro. */
export function isProTool(toolName: string): boolean {
  return getToolTier(toolName) === "pro";
}

/** Returns the tier for a tool. Defaults to "pro" for unknown tools. */
export function getToolTier(toolName: string): Tier {
  return TOOL_TIERS[toolName] ?? "pro";
}

/** Returns all pro tool names. */
export function getProToolNames(): string[] {
  return [...PRO_TOOLS];
}

/** Returns all free tool names. */
export function getFreeToolNames(): string[] {
  return [...FREE_TOOLS];
}

// ─── Upgrade message ──────────────────────────────────────────────────────────

export const PRO_UPGRADE_MESSAGE = `## \u{1F512} Pro Feature

This tool is part of a specialized package (Automotive / Gaming / Healthcare / Interior / Rerun) or a heavier generation tool. SceneView MCP Pro unlocks them.

**Upgrade for \u20AC19/month** to unlock:
- 5 vertical packages (Automotive, Gaming, Healthcare, Interior, Rerun — 35 specialized tools)
- 3D preview, artifact, and scene-generation helpers

All setup, migration, and reference guides remain free.

\u2192 Subscribe at https://sceneview-mcp.mcp-tools-lab.workers.dev/pricing
\u2192 Then set your API key: \`SCENEVIEW_API_KEY=your_key\``;
