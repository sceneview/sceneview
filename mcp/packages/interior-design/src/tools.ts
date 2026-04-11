/**
 * Pure tool library for this vertical MCP package.
 *
 * Exports `TOOL_DEFINITIONS` and `dispatchTool` so this package can be
 * consumed both by its own stdio entrypoint (`./index.ts`) and by the
 * hosted `mcp-gateway` which multiplexes all verticals behind a single
 * HTTP endpoint. Runtime output is identical to the original monolith.
 */


import {
  generateRoomPlanner,
  ROOM_TYPES,
  WALL_STYLES,
  FLOOR_STYLES,
  type RoomType,
  type WallStyle,
  type FloorStyle,
} from "./room-planner.js";
import {
  generateFurniturePlacement,
  FURNITURE_CATEGORIES,
  FURNITURE_SIZES,
  type FurnitureCategory,
  type FurnitureSize,
} from "./furniture-placement.js";
import {
  generateMaterialSwitcher,
  MATERIAL_SURFACES,
  PAINT_FINISHES,
  type MaterialSurface,
  type PaintFinish,
} from "./material-switcher.js";
import {
  generateLightingDesign,
  LIGHT_TYPES,
  COLOR_TEMPERATURES,
  type LightType,
  type ColorTemperature,
} from "./lighting-design.js";
import {
  generateRoomTour,
  TOUR_STYLES,
  TOUR_SPEEDS,
  type TourStyle,
  type TourSpeed,
} from "./room-tour.js";
import {
  listFurnitureModels,
  formatFurnitureModelList,
  FURNITURE_MODEL_CATEGORIES,
  type FurnitureModelCategory,
} from "./furniture-models.js";
import {
  validateInteriorCode,
  formatValidationReport,
} from "./validator.js";

// ─── Legal disclaimer ─────────────────────────────────────────────────────────

const DISCLAIMER =
  "\n\n---\n*Generated code suggestion for interior design 3D visualization. For inspiration only — verify dimensions and materials before purchasing. See [LICENSE](https://github.com/sceneview/sceneview/blob/main/mcp-interior/LICENSE).*";

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
  isError?: boolean;
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
}

export interface DispatchContext {
  userId?: string;
  apiKeyId?: string;
  tier?: "free" | "pro" | "team";
  extras?: Record<string, unknown>;
}

export const TOOL_DEFINITIONS: ToolDefinition[] = [
  {
    name: "get_room_planner",
    description:
      "Returns a complete, compilable Kotlin composable for a 3D room layout planner using SceneView. Procedurally generates walls, floor, ceiling with configurable dimensions. Supports living room, bedroom, kitchen, bathroom, dining room, office, studio, hallway, garage, and open-plan layouts. Options include wall style (standard, brick, concrete, wood-panel, glass), floor style (hardwood, tile, carpet, marble, concrete, laminate, vinyl), window/door count, and AR mode for placing a miniature room layout on a real surface.",
    inputSchema: {
      type: "object",
      properties: {
        roomType: {
          type: "string",
          enum: ROOM_TYPES,
          description: `Room type:\n${ROOM_TYPES.map((r) => `- "${r}"`).join("\n")}`,
        },
        widthMeters: {
          type: "number",
          description: "Room width in meters (default: 4.0).",
        },
        lengthMeters: {
          type: "number",
          description: "Room length in meters (default: 5.0).",
        },
        heightMeters: {
          type: "number",
          description: "Room height in meters (default: 2.7).",
        },
        wallStyle: {
          type: "string",
          enum: WALL_STYLES,
          description: `Wall material style (default: "standard"):\n${WALL_STYLES.map((w) => `- "${w}"`).join("\n")}`,
        },
        floorStyle: {
          type: "string",
          enum: FLOOR_STYLES,
          description: `Floor material style (default: "hardwood"):\n${FLOOR_STYLES.map((f) => `- "${f}"`).join("\n")}`,
        },
        windows: {
          type: "number",
          description: "Number of windows (default: 2).",
        },
        doors: {
          type: "number",
          description: "Number of doors (default: 1).",
        },
        ar: {
          type: "boolean",
          description: "Generate AR version using ARSceneView (default: false). Places a 1:10 miniature room on a real surface.",
        },
      },
      required: ["roomType"],
    },
  },
  {
    name: "get_furniture_placement",
    description:
      "Returns a complete, compilable Kotlin composable for AR furniture placement using SceneView. Place furniture in your real room — tap a surface to position, rotate and scale. Supports sofa, chair, table, bed, desk, shelf, wardrobe, cabinet, lamp, rug, plant, and mirror. Options include size presets (small/medium/large/custom), custom color hex, rotation and scaling controls. Also generates 3D-only preview mode.",
    inputSchema: {
      type: "object",
      properties: {
        category: {
          type: "string",
          enum: FURNITURE_CATEGORIES,
          description: `Furniture category:\n${FURNITURE_CATEGORIES.map((c) => `- "${c}"`).join("\n")}`,
        },
        size: {
          type: "string",
          enum: FURNITURE_SIZES,
          description: `Size preset (default: "medium"):\n${FURNITURE_SIZES.map((s) => `- "${s}"`).join("\n")}`,
        },
        colorHex: {
          type: "string",
          description: 'Optional color hex (e.g., "#4A90D9") for material customization.',
        },
        modelPath: {
          type: "string",
          description: 'Custom model path (default: "models/furniture/{category}.glb").',
        },
        rotatable: {
          type: "boolean",
          description: "Include rotation slider control (default: true).",
        },
        scalable: {
          type: "boolean",
          description: "Include scale slider control (default: true).",
        },
        ar: {
          type: "boolean",
          description: "Generate AR version (default: true). Set false for 3D preview only.",
        },
      },
      required: ["category"],
    },
  },
  {
    name: "get_material_switcher",
    description:
      "Returns a complete, compilable Kotlin composable for switching wall paint, floor material, fabric, and other surfaces in 3D using SceneView. Live-preview colors and textures on a room model with real-time PBR material updates. Surfaces: wall-paint, floor, ceiling, backsplash, countertop, fabric, curtain, wallpaper. Options include paint finish (matte/satin/semi-gloss/gloss/eggshell), color palette, custom texture paths, before/after comparison slider, and AR mode.",
    inputSchema: {
      type: "object",
      properties: {
        surface: {
          type: "string",
          enum: MATERIAL_SURFACES,
          description: `Surface to customize:\n${MATERIAL_SURFACES.map((s) => `- "${s}"`).join("\n")}`,
        },
        finish: {
          type: "string",
          enum: PAINT_FINISHES,
          description: `Paint/material finish (default: "matte"):\n${PAINT_FINISHES.map((f) => `- "${f}"`).join("\n")}`,
        },
        colors: {
          type: "array",
          items: { type: "string" },
          description: 'Color hex codes to offer (default: surface-specific palette). Example: ["#FFFFFF", "#87CEEB"].',
        },
        texturePaths: {
          type: "array",
          items: { type: "string" },
          description: 'Custom texture file paths in assets (e.g., ["textures/oak.ktx", "textures/marble.ktx"]).',
        },
        beforeAfter: {
          type: "boolean",
          description: "Include before/after comparison slider (default: false).",
        },
        ar: {
          type: "boolean",
          description: "Generate AR version for in-situ preview (default: false).",
        },
      },
      required: ["surface"],
    },
  },
  {
    name: "get_lighting_design",
    description:
      "Returns a complete, compilable Kotlin composable for interior lighting design using SceneView. Configure multiple light types in a 3D room scene — ambient, spot, accent, pendant, recessed, track, sconce, floor lamp, table lamp, chandelier, strip LED, and natural light. Options include color temperature (warm-white ~2700K to daylight ~6500K), per-light dimming sliders, shadow casting, and AR mode to preview lighting in your real room.",
    inputSchema: {
      type: "object",
      properties: {
        lights: {
          type: "array",
          items: {
            type: "string",
            enum: LIGHT_TYPES,
          },
          description: `Light types to include:\n${LIGHT_TYPES.map((l) => `- "${l}"`).join("\n")}`,
        },
        colorTemperature: {
          type: "string",
          enum: COLOR_TEMPERATURES,
          description: `Color temperature (default: "warm-white"):\n${COLOR_TEMPERATURES.map((c) => `- "${c}"`).join("\n")}`,
        },
        dimmable: {
          type: "boolean",
          description: "Include per-light dimming sliders (default: true).",
        },
        roomModel: {
          type: "string",
          description: 'Room model path (default: "models/room/interior_scene.glb").',
        },
        showShadows: {
          type: "boolean",
          description: "Enable shadow casting (default: true).",
        },
        ar: {
          type: "boolean",
          description: "Generate AR version (default: false).",
        },
      },
      required: ["lights"],
    },
  },
  {
    name: "get_room_tour",
    description:
      "Returns a complete, compilable Kotlin composable for an animated camera walkthrough of a 3D room using SceneView. Tour styles: orbit (circle around room), walkthrough (first-person path), flyover (elevated bird's eye), dolly (forward/backward), panoramic (360 from center). Options include speed (slow/normal/fast), number of waypoints, loop/one-shot, pause-on-interaction, and AR mode to view a miniature tour on a real surface.",
    inputSchema: {
      type: "object",
      properties: {
        tourStyle: {
          type: "string",
          enum: TOUR_STYLES,
          description: `Tour animation style:\n${TOUR_STYLES.map((t) => `- "${t}"`).join("\n")}`,
        },
        speed: {
          type: "string",
          enum: TOUR_SPEEDS,
          description: `Tour speed (default: "normal"):\n${TOUR_SPEEDS.map((s) => `- "${s}"`).join("\n")}`,
        },
        roomModel: {
          type: "string",
          description: 'Room model path (default: "models/room/interior_scene.glb").',
        },
        waypoints: {
          type: "number",
          description: "Number of camera waypoints (default: 4).",
        },
        loop: {
          type: "boolean",
          description: "Loop tour continuously (default: true).",
        },
        pauseOnInteraction: {
          type: "boolean",
          description: "Pause tour when user touches the screen (default: true).",
        },
        ar: {
          type: "boolean",
          description: "Generate AR version (default: false).",
        },
      },
      required: ["tourStyle"],
    },
  },
  {
    name: "list_furniture_models",
    description:
      "Lists free, openly-licensed 3D furniture models suitable for SceneView apps. Sources include Poly Haven (CC0), Khronos glTF Sample Assets (Apache 2.0 / CC BY 4.0), and Sketchfab (CC BY 4.0). Categories: seating, table, bed, storage, lighting, decor, kitchen, bathroom, outdoor, office. Each entry includes source URL, format, license, and description.",
    inputSchema: {
      type: "object",
      properties: {
        category: {
          type: "string",
          enum: FURNITURE_MODEL_CATEGORIES,
          description: `Filter by category:\n${FURNITURE_MODEL_CATEGORIES.map((c) => `- "${c}"`).join("\n")}`,
        },
        tag: {
          type: "string",
          description: 'Filter by tag (e.g., "sofa", "modern", "kitchen", "lighting"). Omit to list all.',
        },
      },
      required: [],
    },
  },
  {
    name: "validate_interior_code",
    description:
      "Validates a Kotlin SceneView snippet for common interior-design app mistakes. Checks threading violations (Filament JNI on background thread), null-safety for model loading, LightNode trailing-lambda bug, deprecated 2.x APIs, unsupported format warnings (FBX, OBJ, 3DS), texture size warnings, and multiple model instance performance. Always call this before presenting generated interior SceneView code.",
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
    // ── get_room_planner ──────────────────────────────────────────────────
    case "get_room_planner": {
      const args = rawArgs ?? {};
      const roomType = args.roomType as RoomType;
      if (!roomType || !ROOM_TYPES.includes(roomType)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid roomType "${roomType}". Valid: ${ROOM_TYPES.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generateRoomPlanner({
        roomType,
        widthMeters: args.widthMeters as number | undefined,
        lengthMeters: args.lengthMeters as number | undefined,
        heightMeters: args.heightMeters as number | undefined,
        wallStyle: args.wallStyle as WallStyle | undefined,
        floorStyle: args.floorStyle as FloorStyle | undefined,
        windows: args.windows as number | undefined,
        doors: args.doors as number | undefined,
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} Room Planner — ${roomType}`,
              ``,
              `**Gradle dependency:**`,
              "```kotlin",
              args.ar
                ? `implementation("io.github.sceneview:arsceneview:3.6.0")`
                : `implementation("io.github.sceneview:sceneview:3.6.0")`,
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

    // ── get_furniture_placement ────────────────────────────────────────────
    case "get_furniture_placement": {
      const args = rawArgs ?? {};
      const category = args.category as FurnitureCategory;
      if (!category || !FURNITURE_CATEGORIES.includes(category)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid category "${category}". Valid: ${FURNITURE_CATEGORIES.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generateFurniturePlacement({
        category,
        size: args.size as FurnitureSize | undefined,
        colorHex: args.colorHex as string | undefined,
        modelPath: args.modelPath as string | undefined,
        rotatable: args.rotatable as boolean ?? true,
        scalable: args.scalable as boolean ?? true,
        ar: args.ar as boolean ?? true,
      });

      const mode = (args.ar as boolean ?? true) ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} Furniture Placement — ${category}`,
              ``,
              `**Gradle dependency:**`,
              "```kotlin",
              (args.ar as boolean ?? true)
                ? `implementation("io.github.sceneview:arsceneview:3.6.0")`
                : `implementation("io.github.sceneview:sceneview:3.6.0")`,
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

    // ── get_material_switcher ──────────────────────────────────────────────
    case "get_material_switcher": {
      const args = rawArgs ?? {};
      const surface = args.surface as MaterialSurface;
      if (!surface || !MATERIAL_SURFACES.includes(surface)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid surface "${surface}". Valid: ${MATERIAL_SURFACES.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generateMaterialSwitcher({
        surface,
        finish: args.finish as PaintFinish | undefined,
        colors: args.colors as string[] | undefined,
        texturePaths: args.texturePaths as string[] | undefined,
        beforeAfter: args.beforeAfter as boolean ?? false,
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} Material Switcher — ${surface}`,
              ``,
              `**Gradle dependency:**`,
              "```kotlin",
              args.ar
                ? `implementation("io.github.sceneview:arsceneview:3.6.0")`
                : `implementation("io.github.sceneview:sceneview:3.6.0")`,
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

    // ── get_lighting_design ───────────────────────────────────────────────
    case "get_lighting_design": {
      const args = rawArgs ?? {};
      const lights = args.lights as LightType[];
      if (!lights || !Array.isArray(lights) || lights.length === 0) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid lights array. Provide at least one: ${LIGHT_TYPES.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      for (const light of lights) {
        if (!LIGHT_TYPES.includes(light)) {
          return {
            content: [
              {
                type: "text",
                text: `Invalid light type "${light}". Valid: ${LIGHT_TYPES.join(", ")}`,
              },
            ],
            isError: true,
          };
        }
      }

      const code = generateLightingDesign({
        lights,
        colorTemperature: args.colorTemperature as ColorTemperature | undefined,
        dimmable: args.dimmable as boolean ?? true,
        roomModel: args.roomModel as string | undefined,
        showShadows: args.showShadows as boolean ?? true,
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} Lighting Design — ${lights.join(", ")}`,
              ``,
              `**Gradle dependency:**`,
              "```kotlin",
              args.ar
                ? `implementation("io.github.sceneview:arsceneview:3.6.0")`
                : `implementation("io.github.sceneview:sceneview:3.6.0")`,
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

    // ── get_room_tour ─────────────────────────────────────────────────────
    case "get_room_tour": {
      const args = rawArgs ?? {};
      const tourStyle = args.tourStyle as TourStyle;
      if (!tourStyle || !TOUR_STYLES.includes(tourStyle)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid tourStyle "${tourStyle}". Valid: ${TOUR_STYLES.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generateRoomTour({
        tourStyle,
        speed: args.speed as TourSpeed | undefined,
        roomModel: args.roomModel as string | undefined,
        waypoints: args.waypoints as number | undefined,
        loop: args.loop as boolean ?? true,
        pauseOnInteraction: args.pauseOnInteraction as boolean ?? true,
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} Room Tour — ${tourStyle}`,
              ``,
              `**Gradle dependency:**`,
              "```kotlin",
              args.ar
                ? `implementation("io.github.sceneview:arsceneview:3.6.0")`
                : `implementation("io.github.sceneview:sceneview:3.6.0")`,
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

    // ── list_furniture_models ──────────────────────────────────────────────
    case "list_furniture_models": {
      const args = rawArgs ?? {};
      const category = args.category as FurnitureModelCategory | undefined;
      const tag = args.tag as string | undefined;

      const models = listFurnitureModels(category, tag);
      const text = formatFurnitureModelList(models);

      return {
        content: withDisclaimer([{ type: "text", text }]),
      };
    }

    // ── validate_interior_code ─────────────────────────────────────────────
    case "validate_interior_code": {
      const code = rawArgs?.code as string;
      if (!code) {
        return {
          content: [{ type: "text", text: "No code provided for validation." }],
          isError: true,
        };
      }

      const result = validateInteriorCode(code);
      const report = formatValidationReport(result);

      return {
        content: [{ type: "text", text: report }],
      };
    }

    default:
      return {
        content: [
          {
            type: "text",
            text: `Unknown tool: "${toolName}". Available: get_room_planner, get_furniture_placement, get_material_switcher, get_lighting_design, get_room_tour, list_furniture_models, validate_interior_code`,
          },
        ],
        isError: true,
      };
  }
}
