/**
 * Pure tool library for this vertical MCP package.
 *
 * Exports `TOOL_DEFINITIONS` and `dispatchTool` so this package can be
 * consumed both by its own stdio entrypoint (`./index.ts`) and by the
 * hosted `mcp-gateway` which multiplexes all verticals behind a single
 * HTTP endpoint. Runtime output is identical to the original monolith.
 */


import {
  generateCharacterViewer,
  CHARACTER_STYLES,
  ANIMATION_STATES,
  type CharacterStyle,
  type AnimationState,
} from "./character-viewer.js";
import {
  generateLevelEditor,
  LEVEL_THEMES,
  GEOMETRY_TYPES,
  type LevelTheme,
  type GeometryType,
} from "./level-editor.js";
import {
  generatePhysicsGame,
  PHYSICS_PRESETS,
  GRAVITY_MODES,
  type PhysicsPreset,
  type GravityMode,
} from "./physics-game.js";
import {
  generateParticleEffects,
  PARTICLE_EFFECTS,
  BLEND_MODES,
  type ParticleEffect,
  type BlendMode,
} from "./particle-effects.js";
import {
  generateInventory3D,
  ITEM_CATEGORIES,
  INVENTORY_LAYOUTS,
  type ItemCategory,
  type InventoryLayout,
} from "./inventory-3d.js";
import {
  listGameModels,
  formatModelList,
  GAME_MODEL_CATEGORIES,
  type GameModelCategory,
} from "./game-models.js";
import {
  validateGameCode,
  formatValidationReport,
} from "./validator.js";

// ─── Legal disclaimer ─────────────────────────────────────────────────────────

const DISCLAIMER =
  "\n\n---\n*Generated code suggestion for gaming/entertainment 3D visualization. Review and test before production use. See [LICENSE](https://github.com/sceneview/sceneview/blob/main/mcp-gaming/LICENSE).*";

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
    name: "get_character_viewer",
    description:
      "Returns a complete, compilable Kotlin composable for a 3D character viewer with animation state management using SceneView. Supports humanoid, cartoon, chibi, robot, creature, animal, fantasy, and sci-fi character styles. Includes switchable animation states (idle, walk, run, jump, attack, die, dance, wave), three-point lighting, auto-rotation option, and AR mode for placing characters in the real world.",
    inputSchema: {
      type: "object",
      properties: {
        style: {
          type: "string",
          enum: CHARACTER_STYLES,
          description: `Character style:\n${CHARACTER_STYLES.map((s) => `- "${s}"`).join("\n")}`,
        },
        animations: {
          type: "array",
          items: {
            type: "string",
            enum: ANIMATION_STATES,
          },
          description: `Animation states to include (default: ["idle", "walk", "run"]):\n${ANIMATION_STATES.map((a) => `- "${a}"`).join("\n")}`,
        },
        autoRotate: {
          type: "boolean",
          description: "Enable auto-rotation of the character model (default: false).",
        },
        showControls: {
          type: "boolean",
          description: "Show animation control buttons (default: true).",
        },
        ar: {
          type: "boolean",
          description: "Generate AR version using ARSceneView (default: false). Requires arsceneview dependency.",
        },
      },
      required: ["style"],
    },
  },
  {
    name: "get_level_editor",
    description:
      "Returns a complete, compilable Kotlin composable for a procedural level editor using SceneView. Uses built-in geometry nodes (CubeNode, SphereNode, CylinderNode) for real-time level construction — no external models needed. Supports 10 themes (dungeon, forest, space, underwater, desert, city, castle, ice, lava, sky) with theme-appropriate lighting. Features editable block placement, geometry palette, and grid display.",
    inputSchema: {
      type: "object",
      properties: {
        theme: {
          type: "string",
          enum: LEVEL_THEMES,
          description: `Level theme:\n${LEVEL_THEMES.map((t) => `- "${t}"`).join("\n")}`,
        },
        geometries: {
          type: "array",
          items: {
            type: "string",
            enum: GEOMETRY_TYPES,
          },
          description: `Geometry types to include (default: ["cube", "sphere", "cylinder", "plane"]):\n${GEOMETRY_TYPES.map((g) => `- "${g}"`).join("\n")}`,
        },
        gridSize: {
          type: "number",
          description: "Grid size for level generation (default: 10).",
        },
        showGrid: {
          type: "boolean",
          description: "Show grid floor plane (default: true).",
        },
        editable: {
          type: "boolean",
          description: "Allow block placement/removal (default: true).",
        },
        ar: {
          type: "boolean",
          description: "Generate AR version (default: false).",
        },
      },
      required: ["theme"],
    },
  },
  {
    name: "get_physics_game",
    description:
      "Returns a complete, compilable Kotlin composable for a physics simulation game using SceneView. Includes full Euler integration physics with gravity, bounciness, collision detection, and response. Presets: bouncing-balls, bowling, billiards, marble-run, tower-collapse, pong-3d, pinball, cannon. Customizable gravity (earth, moon, mars, jupiter, zero-g, reverse). Optional trajectory prediction. Includes pause, reset, and score tracking.",
    inputSchema: {
      type: "object",
      properties: {
        preset: {
          type: "string",
          enum: PHYSICS_PRESETS,
          description: `Physics game preset:\n${PHYSICS_PRESETS.map((p) => `- "${p}"`).join("\n")}`,
        },
        gravity: {
          type: "string",
          enum: GRAVITY_MODES,
          description: `Gravity mode (default: "earth"):\n${GRAVITY_MODES.map((g) => `- "${g}"`).join("\n")}`,
        },
        bounciness: {
          type: "number",
          description: "Coefficient of restitution, 0.0 to 1.0 (default: 0.7).",
        },
        showTrajectory: {
          type: "boolean",
          description: "Show trajectory prediction line (default: false).",
        },
        ar: {
          type: "boolean",
          description: "Generate AR version (default: false).",
        },
      },
      required: ["preset"],
    },
  },
  {
    name: "get_particle_effects",
    description:
      "Returns a complete, compilable Kotlin composable for visual particle effects using SceneView. Effects: fire, smoke, sparkles, rain, snow, explosion, magic, confetti, bubbles, fireflies. Each particle is rendered as a SphereNode with per-frame position, size, and lifetime updates. Includes emitter shape, velocity, gravity, blend mode, and loop settings. Play/pause and restart controls included.",
    inputSchema: {
      type: "object",
      properties: {
        effect: {
          type: "string",
          enum: PARTICLE_EFFECTS,
          description: `Particle effect:\n${PARTICLE_EFFECTS.map((e) => `- "${e}"`).join("\n")}`,
        },
        particleCount: {
          type: "number",
          description: "Number of particles (default: 100). Higher counts impact performance.",
        },
        blendMode: {
          type: "string",
          enum: BLEND_MODES,
          description: `Blend mode (default: effect-specific):\n${BLEND_MODES.map((b) => `- "${b}"`).join("\n")}`,
        },
        loop: {
          type: "boolean",
          description: "Loop the effect by respawning dead particles (default: true).",
        },
        ar: {
          type: "boolean",
          description: "Generate AR version (default: false).",
        },
      },
      required: ["effect"],
    },
  },
  {
    name: "get_inventory_3d",
    description:
      "Returns a complete, compilable Kotlin composable for a 3D game item inventory with interactive preview using SceneView. Layout options: grid, carousel, list, radial. Selecting an item shows a 3D model preview with auto-rotation. Includes category filter tabs, item stats panel, quantity display, and rarity system. Sample items included (weapons, armor, potions, gems, etc.).",
    inputSchema: {
      type: "object",
      properties: {
        layout: {
          type: "string",
          enum: INVENTORY_LAYOUTS,
          description: `Inventory layout:\n${INVENTORY_LAYOUTS.map((l) => `- "${l}"`).join("\n")}`,
        },
        categories: {
          type: "array",
          items: {
            type: "string",
            enum: ITEM_CATEGORIES,
          },
          description: `Item categories to include (default: ["weapon", "armor", "potion", "gem"]):\n${ITEM_CATEGORIES.map((c) => `- "${c}"`).join("\n")}`,
        },
        columns: {
          type: "number",
          description: "Number of grid columns (default: 4).",
        },
        showStats: {
          type: "boolean",
          description: "Show item stats panel when selected (default: true).",
        },
        autoRotate: {
          type: "boolean",
          description: "Auto-rotate 3D preview (default: true).",
        },
        ar: {
          type: "boolean",
          description: "Generate AR version for viewing items at real scale (default: false).",
        },
      },
      required: ["layout"],
    },
  },
  {
    name: "list_game_models",
    description:
      "Lists free, openly-licensed 3D game models suitable for SceneView apps. Sources include Khronos glTF Sample Models (CC BY/CC0), Kenney.nl (CC0 Public Domain), Quaternius (CC0), and Sketchfab (CC BY). Categories: character, weapon, vehicle, environment, prop, creature, building, item, effect, ui. Each entry includes source URL, format, license, and SceneView compatibility notes.",
    inputSchema: {
      type: "object",
      properties: {
        category: {
          type: "string",
          enum: GAME_MODEL_CATEGORIES,
          description: `Filter by category:\n${GAME_MODEL_CATEGORIES.map((c) => `- "${c}"`).join("\n")}`,
        },
        tag: {
          type: "string",
          description: 'Filter by tag (e.g., "animated", "low-poly", "fantasy", "sci-fi"). Omit to list all.',
        },
      },
      required: [],
    },
  },
  {
    name: "validate_game_code",
    description:
      "Validates a Kotlin SceneView snippet for common gaming-app mistakes. Checks threading violations (Filament JNI on background thread), null-safety for model loading, LightNode trailing-lambda bug, deprecated 2.x APIs, physics timestep issues, particle performance, animation safety, and Random seeding. Always call this before presenting generated gaming SceneView code.",
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
    // ── get_character_viewer ────────────────────────────────────────────────
    case "get_character_viewer": {
      const args = rawArgs ?? {};
      const style = args.style as CharacterStyle;
      if (!style || !CHARACTER_STYLES.includes(style)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid style "${style}". Valid: ${CHARACTER_STYLES.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generateCharacterViewer({
        style,
        animations: (args.animations as AnimationState[]) ?? ["idle", "walk", "run"],
        autoRotate: args.autoRotate as boolean ?? false,
        showControls: args.showControls as boolean ?? true,
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} Character Viewer — ${style}`,
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

    // ── get_level_editor ───────────────────────────────────────────────────
    case "get_level_editor": {
      const args = rawArgs ?? {};
      const theme = args.theme as LevelTheme;
      if (!theme || !LEVEL_THEMES.includes(theme)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid theme "${theme}". Valid: ${LEVEL_THEMES.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generateLevelEditor({
        theme,
        geometries: (args.geometries as GeometryType[]) ?? ["cube", "sphere", "cylinder", "plane"],
        gridSize: (args.gridSize as number) ?? 10,
        showGrid: args.showGrid as boolean ?? true,
        editable: args.editable as boolean ?? true,
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} Level Editor — ${theme}`,
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

    // ── get_physics_game ───────────────────────────────────────────────────
    case "get_physics_game": {
      const args = rawArgs ?? {};
      const preset = args.preset as PhysicsPreset;
      if (!preset || !PHYSICS_PRESETS.includes(preset)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid preset "${preset}". Valid: ${PHYSICS_PRESETS.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generatePhysicsGame({
        preset,
        gravity: (args.gravity as GravityMode) ?? "earth",
        bounciness: (args.bounciness as number) ?? 0.7,
        showTrajectory: args.showTrajectory as boolean ?? false,
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} Physics Game — ${preset}`,
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

    // ── get_particle_effects ───────────────────────────────────────────────
    case "get_particle_effects": {
      const args = rawArgs ?? {};
      const effect = args.effect as ParticleEffect;
      if (!effect || !PARTICLE_EFFECTS.includes(effect)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid effect "${effect}". Valid: ${PARTICLE_EFFECTS.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generateParticleEffects({
        effect,
        particleCount: (args.particleCount as number) ?? 100,
        blendMode: args.blendMode as BlendMode | undefined,
        loop: args.loop as boolean ?? true,
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} Particle Effect — ${effect}`,
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

    // ── get_inventory_3d ───────────────────────────────────────────────────
    case "get_inventory_3d": {
      const args = rawArgs ?? {};
      const layout = args.layout as InventoryLayout;
      if (!layout || !INVENTORY_LAYOUTS.includes(layout)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid layout "${layout}". Valid: ${INVENTORY_LAYOUTS.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generateInventory3D({
        layout,
        categories: (args.categories as ItemCategory[]) ?? ["weapon", "armor", "potion", "gem"],
        columns: (args.columns as number) ?? 4,
        showStats: args.showStats as boolean ?? true,
        autoRotate: args.autoRotate as boolean ?? true,
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} Inventory — ${layout}`,
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

    // ── list_game_models ───────────────────────────────────────────────────
    case "list_game_models": {
      const args = rawArgs ?? {};
      const category = args.category as GameModelCategory | undefined;
      const tag = args.tag as string | undefined;

      const models = listGameModels(category, tag);
      const text = formatModelList(models);

      return {
        content: withDisclaimer([{ type: "text", text }]),
      };
    }

    // ── validate_game_code ─────────────────────────────────────────────────
    case "validate_game_code": {
      const code = rawArgs?.code as string;
      if (!code) {
        return {
          content: [{ type: "text", text: "No code provided for validation." }],
          isError: true,
        };
      }

      const result = validateGameCode(code);
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
            text: `Unknown tool: "${toolName}". Available: get_character_viewer, get_level_editor, get_physics_game, get_particle_effects, get_inventory_3d, list_game_models, validate_game_code`,
          },
        ],
        isError: true,
      };
  }
}
