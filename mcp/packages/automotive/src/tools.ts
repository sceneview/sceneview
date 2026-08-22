/**
 * Pure tool library for this vertical MCP package.
 *
 * Exports `TOOL_DEFINITIONS` and `dispatchTool` so this package can be
 * consumed both by its own stdio entrypoint (`./index.ts`) and by the
 * hosted `mcp-gateway` which multiplexes all verticals behind a single
 * HTTP endpoint. Runtime output is identical to the original monolith.
 */


import {
  generateCarConfigurator,
  CAR_BODY_STYLES,
  CAMERA_PRESETS,
  COLOR_CATEGORIES,
  type CarBodyStyle,
  type CameraPreset,
} from "./car-configurator.js";
import {
  generateHudOverlay,
  HUD_ELEMENTS,
  HUD_STYLES,
  type HudElement,
  type HudStyle,
} from "./hud-overlay.js";
import {
  generateDashboard3d,
  GAUGE_TYPES,
  DASHBOARD_THEMES,
  type GaugeType,
  type DashboardTheme,
} from "./dashboard-3d.js";
import {
  generateArShowroom,
  SHOWROOM_LOCATIONS,
  SHOWROOM_FEATURES,
  type ShowroomLocation,
  type ShowroomFeature,
} from "./ar-showroom.js";
import {
  generatePartsCatalog,
  PART_CATEGORIES,
  CATALOG_FEATURES,
  type PartCategory,
  type CatalogFeature,
} from "./parts-catalog.js";
import {
  listCarModels,
  formatCarModelList,
  CAR_MODEL_CATEGORIES,
  type CarModelCategory,
} from "./car-models.js";
import {
  validateAutomotiveCode,
  formatValidationReport,
} from "./validator.js";
import {
  generateEvChargingStationViewer,
  CHARGING_CONNECTORS,
  STATION_LAYOUTS,
  type ChargingConnector,
  type StationLayout,
} from "./ev-charging-station-viewer.js";
import {
  generateCarPaintShader,
  PAINT_FINISHES,
  type PaintFinish,
} from "./car-paint-shader.js";

// ─── Legal disclaimer ─────────────────────────────────────────────────────────

const DISCLAIMER =
  "\n\n---\n*Generated code suggestion for automotive 3D visualization. Review before production use. See [LICENSE](https://github.com/sceneview/sceneview/blob/main/mcp-automotive/LICENSE).*";

function withDisclaimer<T extends { type: string; text: string }>(content: T[]): T[] {
  if (content.length === 0) return content;
  const last = content[content.length - 1];
  return [
    ...content.slice(0, -1),
    { ...last, text: last.text + DISCLAIMER },
  ];
}

// ─── Server ───────────────────────────────────────────────────────────────────

export interface ToolTextContent {
  type: "text";
  text: string;
}

export interface ToolResult {
  content: ToolTextContent[];
  structuredContent?: Record<string, unknown>;
  isError?: boolean;
}

export interface ToolAnnotations {
  readOnlyHint: boolean;
  openWorldHint: boolean;
  destructiveHint: boolean;
  title?: string;
}

export interface ToolDefinition {
  name: string;
  description: string;
  inputSchema: {
    type: "object";
    properties?: Record<string, unknown>;
    required?: string[];
    additionalProperties?: boolean;
  };
  outputSchema?: { type: "object"; properties?: Record<string, unknown>; required?: string[]; additionalProperties?: boolean };
  /** MCP behaviour hints — see mcp/src/tools/types.ts for the full rationale. */
  annotations?: ToolAnnotations;
}

export interface DispatchContext {
  userId?: string;
  apiKeyId?: string;
  tier?: "free" | "pro" | "team";
  extras?: Record<string, unknown>;
}

export const TOOL_DEFINITIONS: ToolDefinition[] = [
  {
    name: "get_car_configurator",
    description:
      "Returns a complete, compilable Kotlin composable for a 3D car configurator using SceneView. Supports 10 body styles (sedan, SUV, coupe, sports, electric, etc.). Features include color picker with 8 paint options, material variants (metallic, matte, pearlescent, gloss), camera presets (exterior, interior, detail views), auto-rotation turntable, and AR mode for placing the car in your driveway. Uses KHR_materials_variants for paint switching and three-point studio lighting.",
    inputSchema: {
      type: "object",
      properties: {
        bodyStyle: {
          type: "string",
          enum: CAR_BODY_STYLES,
          description: `Car body style:\n${CAR_BODY_STYLES.map((s) => `- "${s}"`).join("\n")}`,
        },
        colorPicker: {
          type: "boolean",
          description: "Include color picker with 8 paint options (default: true).",
        },
        materialVariants: {
          type: "boolean",
          description: "Include material variant selector — metallic, matte, pearlescent, gloss (default: true).",
        },
        cameraPresets: {
          type: "array",
          items: {
            type: "string",
            enum: CAMERA_PRESETS,
          },
          description: `Camera presets to include (default: exterior-front, exterior-three-quarter, interior-driver):\n${CAMERA_PRESETS.map((p) => `- "${p}"`).join("\n")}`,
        },
        turntable: {
          type: "boolean",
          description: "Enable auto-rotation turntable (default: true).",
        },
        ar: {
          type: "boolean",
          description: "Generate AR version using ARSceneView — place car in real world (default: false). Requires arsceneview dependency.",
        },
      },
      required: ["bodyStyle"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_hud_overlay",
    description:
      "Returns a complete, compilable Kotlin composable for a heads-up display overlay using SceneView ViewNode. HUD elements include speedometer, navigation arrows, alerts, fuel gauge, temperature, gear indicator, turn signals, and lane assist. Choose from 6 styles (minimal, sport, luxury, combat, eco, retro). Supports night mode (green-on-black), metric/imperial units, and AR mode for projecting HUD elements in real space.",
    inputSchema: {
      type: "object",
      properties: {
        elements: {
          type: "array",
          items: {
            type: "string",
            enum: HUD_ELEMENTS,
          },
          description: `HUD elements to display:\n${HUD_ELEMENTS.map((e) => `- "${e}"`).join("\n")}`,
        },
        style: {
          type: "string",
          enum: HUD_STYLES,
          description: `HUD visual style (default: "minimal"):\n${HUD_STYLES.map((s) => `- "${s}"`).join("\n")}`,
        },
        nightMode: {
          type: "boolean",
          description: "Enable night mode — green-on-black color scheme (default: false).",
        },
        units: {
          type: "string",
          enum: ["metric", "imperial"],
          description: 'Unit system — "metric" (km/h, °C) or "imperial" (mph, °F). Default: "metric".',
        },
        ar: {
          type: "boolean",
          description: "Generate AR version with HUD projected in real space (default: false).",
        },
      },
      required: ["elements"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_dashboard_3d",
    description:
      "Returns a complete, compilable Kotlin composable for a 3D instrument cluster using SceneView. Gauge types: speedometer, tachometer, fuel, temperature, oil-pressure, battery, boost, odometer. Themes: classic, digital, sport, luxury, electric, retro. The dashboard housing is a 3D model while gauge faces use ViewNode for crisp animated rendering. Includes spring-damped needle animations, red zone indicators, and interactive slider controls.",
    inputSchema: {
      type: "object",
      properties: {
        gauges: {
          type: "array",
          items: {
            type: "string",
            enum: GAUGE_TYPES,
          },
          description: `Gauges to include:\n${GAUGE_TYPES.map((g) => `- "${g}"`).join("\n")}`,
        },
        theme: {
          type: "string",
          enum: DASHBOARD_THEMES,
          description: `Dashboard theme (default: "classic"):\n${DASHBOARD_THEMES.map((t) => `- "${t}"`).join("\n")}`,
        },
        animated: {
          type: "boolean",
          description: "Enable spring-damped gauge animations (default: true).",
        },
        interactive: {
          type: "boolean",
          description: "Include interactive slider controls for demo input (default: true).",
        },
        ar: {
          type: "boolean",
          description: "Generate AR version (default: false).",
        },
      },
      required: ["gauges"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_ar_showroom",
    description:
      "Returns a complete, compilable Kotlin composable for an AR car showroom using SceneView ARScene. Place a full-size car in your driveway, parking lot, garage, showroom floor, or street. Features include walk-around, open doors animation, color swap, real-world measurements, side-by-side comparison, photo capture, and night lighting effects. Real 1:1 scale placement with ground-plane shadows.",
    inputSchema: {
      type: "object",
      properties: {
        location: {
          type: "string",
          enum: SHOWROOM_LOCATIONS,
          description: `Placement context:\n${SHOWROOM_LOCATIONS.map((l) => `- "${l}"`).join("\n")}`,
        },
        features: {
          type: "array",
          items: {
            type: "string",
            enum: SHOWROOM_FEATURES,
          },
          description: `Showroom features (default: walk-around, color-swap):\n${SHOWROOM_FEATURES.map((f) => `- "${f}"`).join("\n")}`,
        },
        realScale: {
          type: "boolean",
          description: "Place car at real 1:1 scale (~4.5m) or table-top (~2m). Default: true (real scale).",
        },
        shadows: {
          type: "boolean",
          description: "Enable ground-plane shadow lighting (default: true).",
        },
      },
      required: ["location"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_parts_catalog",
    description:
      "Returns a complete, compilable Kotlin composable for a 3D parts catalog explorer using SceneView. Categories: engine, transmission, suspension, brakes, exhaust, interior, body, electrical, wheels, cooling. Features include exploded-view slider, part selection highlighting, detail zoom, cross-section plane, assembly animation, part info cards, and search. Each category comes with realistic part data (names, part numbers, pricing). AR mode for hands-on part examination.",
    inputSchema: {
      type: "object",
      properties: {
        category: {
          type: "string",
          enum: PART_CATEGORIES,
          description: `Part category:\n${PART_CATEGORIES.map((c) => `- "${c}"`).join("\n")}`,
        },
        features: {
          type: "array",
          items: {
            type: "string",
            enum: CATALOG_FEATURES,
          },
          description: `Catalog features (default: exploded-view, part-selection, detail-zoom):\n${CATALOG_FEATURES.map((f) => `- "${f}"`).join("\n")}`,
        },
        partNumbers: {
          type: "boolean",
          description: "Show manufacturer part numbers (default: true).",
        },
        pricing: {
          type: "boolean",
          description: "Show part pricing (default: false).",
        },
        ar: {
          type: "boolean",
          description: "Generate AR version for hands-on examination (default: false).",
        },
      },
      required: ["category"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_ev_charging_station_viewer",
    description:
      "Returns a complete, compilable Kotlin composable that renders a 3D EV charging station model with an overlay UI showing live charge level, available bays, and estimated time to full. Supports 5 connector types (CCS, CHAdeMO, Type 2, Tesla, J1772) and 4 station layouts (single, dual, bank, canopy). The overlay is a Material 3 card bound to live state that your app can wire to a real BLE/backend charging session. Optional AR mode uses ARScene so the station can be placed in the real world.",
    inputSchema: {
      type: "object",
      properties: {
        connector: {
          type: "string",
          enum: CHARGING_CONNECTORS,
          description: `EV charging connector standard (default: "ccs"):\n${CHARGING_CONNECTORS.map((c) => `- "${c}"`).join("\n")}`,
        },
        layout: {
          type: "string",
          enum: STATION_LAYOUTS,
          description: `Station layout (default: "single"):\n${STATION_LAYOUTS.map((l) => `- "${l}"`).join("\n")}`,
        },
        overlay: {
          type: "boolean",
          description: "Render the Material 3 status overlay card (default: true).",
        },
        ar: {
          type: "boolean",
          description: "Generate an AR version using ARSceneView — place the station in the real world (default: false). Requires arsceneview dependency.",
        },
      },
      required: [],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_car_paint_shader",
    description:
      "Returns a realistic Filament .mat car-paint material definition (clearcoat + optional metallic flakes) plus a Kotlin SceneView snippet showing how to compile it to .filamat and apply it to a loaded car model's material instances. Parameters are physically based: baseColor, metallic, roughness, clearcoat, clearcoatRoughness. Supports 4 finish presets (solid, metallic, pearlescent, matte) that override the PBR knobs with realistic defaults. Use this for car configurators where hand-writing a correct clearcoat material is otherwise tedious.",
    inputSchema: {
      type: "object",
      properties: {
        baseColorHex: {
          type: "string",
          description: 'Hex base color, e.g. "#CC0000" for racing red. Default: "#CC0000".',
        },
        metallic: {
          type: "number",
          description: "Metallic parameter (0.0 = dielectric, 1.0 = full metal). Default: 0.9. Forced to 0 for matte finish.",
        },
        roughness: {
          type: "number",
          description: "Base coat roughness (0.0 = mirror, 1.0 = chalk). Default: 0.35.",
        },
        clearcoat: {
          type: "number",
          description: "Clearcoat lacquer intensity (0.0..1.0). Default: 1.0. Forced to 0 for matte finish.",
        },
        clearcoatRoughness: {
          type: "number",
          description: "Clearcoat smoothness — lower = glossier. Default: 0.05.",
        },
        finish: {
          type: "string",
          enum: PAINT_FINISHES,
          description: `Finish preset that overrides PBR knobs with realistic defaults (default: "metallic"):\n${PAINT_FINISHES.map((f) => `- "${f}"`).join("\n")}`,
        },
      },
      required: [],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "list_car_models",
    description:
      "Lists free, openly-licensed 3D car models suitable for SceneView apps. Includes the Khronos ToyCar (CC0, GLB ready), Sketchfab free cars (sedans, SUVs, sports, classics, EVs), engine assemblies, brake calipers, suspension kits, interior cockpits, steering wheels, and dashboard panels. Categories: complete-car, concept, classic, parts, interior, wheels, engine, test-model. Each entry includes source URL, format, license, and SceneView compatibility notes.",
    inputSchema: {
      type: "object",
      properties: {
        category: {
          type: "string",
          enum: CAR_MODEL_CATEGORIES,
          description: `Filter by category:\n${CAR_MODEL_CATEGORIES.map((c) => `- "${c}"`).join("\n")}`,
        },
        tag: {
          type: "string",
          description: 'Filter by tag (e.g., "pbr", "engine", "low-poly", "sedan"). Omit to list all.',
        },
      },
      required: [],
    },
    outputSchema: { type: "object", properties: { models: { type: "array", items: { type: "object", properties: { id: { type: "string" }, name: { type: "string" }, category: { type: "string" }, source: { type: "string" }, sourceUrl: { type: "string" }, format: { type: "string" }, license: { type: "string" }, description: { type: "string" }, tags: { type: "array", items: { type: "string" } } }, required: ["id", "name", "category", "source", "sourceUrl", "format", "license", "description", "tags"], additionalProperties: false } } }, required: ["models"], additionalProperties: false },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "validate_automotive_code",
    description:
      "Validates a Kotlin SceneView snippet for common automotive-app mistakes. Checks threading violations (Filament JNI on background thread), null-safety for model loading, LightNode trailing-lambda bug, deprecated 2.x APIs, automotive-specific issues (unrealistic scale, unsupported formats like FBX/.blend, turntable performance), and missing imports. Always call this before presenting generated automotive SceneView code.",
    inputSchema: {
      type: "object",
      properties: {
        code: {
          type: "string",
          description: "The Kotlin source code to validate.",
        },
      },
      required: ["code"],
    },
    outputSchema: { type: "object", properties: { valid: { type: "boolean" }, issues: { type: "array", items: { type: "object", properties: { severity: { type: "string" }, message: { type: "string" }, line: { type: "number" } }, required: ["severity", "message"], additionalProperties: false } }, issueCount: { type: "object", properties: { errors: { type: "number" }, warnings: { type: "number" }, info: { type: "number" } }, required: ["errors", "warnings", "info"], additionalProperties: false } }, required: ["valid", "issues", "issueCount"], additionalProperties: false },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
];

/** Returns a read-only copy of the vertical's tool definitions. */
export function getAllTools(): ToolDefinition[] {
  return [...TOOL_DEFINITIONS];
}

/** Looks up a tool definition by name. */
export function getToolDefinition(name: string): ToolDefinition | undefined {
  return TOOL_DEFINITIONS.find((t) => t.name === name);
}

/**
 * Runs a tool from this vertical package.
 *
 * This function is pure business logic: it does not authenticate, rate
 * limit, or record billing. Those concerns live in the caller (stdio
 * server or gateway).
 */
export async function dispatchTool(
  toolName: string,
  rawArgs: Record<string, unknown> | undefined,
  _ctx: DispatchContext = {},
): Promise<ToolResult> {
  switch (toolName) {
    // ── get_car_configurator ──────────────────────────────────────────────
    case "get_car_configurator": {
      const args = rawArgs ?? {};
      const bodyStyle = args.bodyStyle as CarBodyStyle;
      if (!bodyStyle || !CAR_BODY_STYLES.includes(bodyStyle)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid bodyStyle "${bodyStyle}". Valid: ${CAR_BODY_STYLES.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generateCarConfigurator({
        bodyStyle,
        colorPicker: args.colorPicker as boolean ?? true,
        materialVariants: args.materialVariants as boolean ?? true,
        cameraPresets: (args.cameraPresets as CameraPreset[]) ?? ["exterior-front", "exterior-three-quarter", "interior-driver"],
        turntable: args.turntable as boolean ?? true,
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} Car Configurator — ${bodyStyle}`,
              ``,
              `**Gradle dependency:**`,
              "```kotlin",
              args.ar
                ? `implementation("io.github.sceneview:arsceneview:4.0.9")`
                : `implementation("io.github.sceneview:sceneview:4.0.9")`,
              "```",
              ``,
              `**Kotlin (Jetpack Compose):**`,
              "```kotlin",
              code,
              "```",
            ].join("\n"),
          },
        ]),
      };
    }

    // ── get_hud_overlay ───────────────────────────────────────────────────
    case "get_hud_overlay": {
      const args = rawArgs ?? {};
      const elements = args.elements as HudElement[];
      if (!elements || !Array.isArray(elements) || elements.length === 0) {
        return {
          content: [
            {
              type: "text",
              text: `At least one HUD element is required. Valid: ${HUD_ELEMENTS.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generateHudOverlay({
        elements,
        style: (args.style as HudStyle) ?? "minimal",
        nightMode: args.nightMode as boolean ?? false,
        units: (args.units as "metric" | "imperial") ?? "metric",
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} HUD Overlay — ${elements.join(", ")}`,
              ``,
              `**Gradle dependency:**`,
              "```kotlin",
              args.ar
                ? `implementation("io.github.sceneview:arsceneview:4.0.9")`
                : `implementation("io.github.sceneview:sceneview:4.0.9")`,
              "```",
              ``,
              `**Kotlin (Jetpack Compose):**`,
              "```kotlin",
              code,
              "```",
            ].join("\n"),
          },
        ]),
      };
    }

    // ── get_dashboard_3d ──────────────────────────────────────────────────
    case "get_dashboard_3d": {
      const args = rawArgs ?? {};
      const gauges = args.gauges as GaugeType[];
      if (!gauges || !Array.isArray(gauges) || gauges.length === 0) {
        return {
          content: [
            {
              type: "text",
              text: `At least one gauge type is required. Valid: ${GAUGE_TYPES.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generateDashboard3d({
        gauges,
        theme: (args.theme as DashboardTheme) ?? "classic",
        animated: args.animated as boolean ?? true,
        interactive: args.interactive as boolean ?? true,
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} Dashboard — ${gauges.join(", ")}`,
              ``,
              `**Gradle dependency:**`,
              "```kotlin",
              args.ar
                ? `implementation("io.github.sceneview:arsceneview:4.0.9")`
                : `implementation("io.github.sceneview:sceneview:4.0.9")`,
              "```",
              ``,
              `**Kotlin (Jetpack Compose):**`,
              "```kotlin",
              code,
              "```",
            ].join("\n"),
          },
        ]),
      };
    }

    // ── get_ar_showroom ───────────────────────────────────────────────────
    case "get_ar_showroom": {
      const args = rawArgs ?? {};
      const location = args.location as ShowroomLocation;
      if (!location || !SHOWROOM_LOCATIONS.includes(location)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid location "${location}". Valid: ${SHOWROOM_LOCATIONS.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generateArShowroom({
        location,
        features: (args.features as ShowroomFeature[]) ?? ["walk-around", "color-swap"],
        realScale: args.realScale as boolean ?? true,
        shadows: args.shadows as boolean ?? true,
      });

      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## AR Showroom — ${location}`,
              ``,
              `**Gradle dependency:**`,
              "```kotlin",
              `implementation("io.github.sceneview:arsceneview:4.0.9")`,
              "```",
              ``,
              `**Kotlin (Jetpack Compose):**`,
              "```kotlin",
              code,
              "```",
            ].join("\n"),
          },
        ]),
      };
    }

    // ── get_parts_catalog ─────────────────────────────────────────────────
    case "get_parts_catalog": {
      const args = rawArgs ?? {};
      const category = args.category as PartCategory;
      if (!category || !PART_CATEGORIES.includes(category)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid category "${category}". Valid: ${PART_CATEGORIES.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generatePartsCatalog({
        category,
        features: (args.features as CatalogFeature[]) ?? ["exploded-view", "part-selection", "detail-zoom"],
        partNumbers: args.partNumbers as boolean ?? true,
        pricing: args.pricing as boolean ?? false,
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} Parts Catalog — ${category}`,
              ``,
              `**Gradle dependency:**`,
              "```kotlin",
              args.ar
                ? `implementation("io.github.sceneview:arsceneview:4.0.9")`
                : `implementation("io.github.sceneview:sceneview:4.0.9")`,
              "```",
              ``,
              `**Kotlin (Jetpack Compose):**`,
              "```kotlin",
              code,
              "```",
            ].join("\n"),
          },
        ]),
      };
    }

    // ── get_ev_charging_station_viewer ────────────────────────────────────
    case "get_ev_charging_station_viewer": {
      const args = rawArgs ?? {};
      const connector = (args.connector as ChargingConnector | undefined) ?? "ccs";
      const layout = (args.layout as StationLayout | undefined) ?? "single";

      if (!CHARGING_CONNECTORS.includes(connector)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid connector "${connector}". Valid: ${CHARGING_CONNECTORS.join(", ")}`,
            },
          ],
          isError: true,
        };
      }
      if (!STATION_LAYOUTS.includes(layout)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid layout "${layout}". Valid: ${STATION_LAYOUTS.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generateEvChargingStationViewer({
        connector,
        layout,
        overlay: args.overlay as boolean ?? true,
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} EV Charging Station Viewer — ${layout} ${connector.toUpperCase()}`,
              ``,
              `**Gradle dependency:**`,
              "```kotlin",
              args.ar
                ? `implementation("io.github.sceneview:arsceneview:4.0.0-rc.1")`
                : `implementation("io.github.sceneview:sceneview:4.0.0-rc.1")`,
              "```",
              ``,
              `**Kotlin (Jetpack Compose):**`,
              "```kotlin",
              code,
              "```",
            ].join("\n"),
          },
        ]),
      };
    }

    // ── get_car_paint_shader ──────────────────────────────────────────────
    case "get_car_paint_shader": {
      const args = rawArgs ?? {};
      const finish = (args.finish as PaintFinish | undefined) ?? "metallic";
      if (!PAINT_FINISHES.includes(finish)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid finish "${finish}". Valid: ${PAINT_FINISHES.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const template = generateCarPaintShader({
        baseColorHex: args.baseColorHex as string | undefined,
        metallic: args.metallic as number | undefined,
        roughness: args.roughness as number | undefined,
        clearcoat: args.clearcoat as number | undefined,
        clearcoatRoughness: args.clearcoatRoughness as number | undefined,
        finish,
      });

      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## Car Paint Shader — ${finish}`,
              ``,
              template.description,
              ``,
              `**Dependencies:**`,
              ...template.dependencies.map((d) => `- ${d}`),
              ``,
              `**Filament material definition (\`car_paint.mat\`):**`,
              "```",
              template.materialDefinition,
              "```",
              ``,
              `Compile with: \`matc -p mobile -a opengl -o car_paint.filamat car_paint.mat\``,
              `Place the compiled \`.filamat\` in \`src/main/assets/materials/car_paint.filamat\`.`,
              ``,
              `**Kotlin usage (Jetpack Compose):**`,
              "```kotlin",
              template.kotlinUsage,
              "```",
            ].join("\n"),
          },
        ]),
      };
    }

    // ── list_car_models ───────────────────────────────────────────────────
    case "list_car_models": {
      const args = rawArgs ?? {};
      const category = args.category as CarModelCategory | undefined;
      const tag = args.tag as string | undefined;

      const models = listCarModels(category, tag);
      const text = formatCarModelList(models);

      return {
        content: withDisclaimer([{ type: "text", text }]),
        structuredContent: { models },
      };
    }

    // ── validate_automotive_code ──────────────────────────────────────────
    case "validate_automotive_code": {
      const code = rawArgs?.code as string;
      if (!code) {
        return {
          content: [{ type: "text", text: "No code provided for validation." }],
          isError: true,
        };
      }

      const result = validateAutomotiveCode(code);
      const report = formatValidationReport(result);

      return {
        content: [{ type: "text", text: report }],
        structuredContent: { ...result },
      };
    }

    default:
      return {
        content: [
          {
            type: "text",
            text: `Unknown tool: "${toolName}". Available: get_car_configurator, get_hud_overlay, get_dashboard_3d, get_ar_showroom, get_parts_catalog, get_ev_charging_station_viewer, get_car_paint_shader, list_car_models, validate_automotive_code`,
          },
        ],
        isError: true,
      };
  }
}
