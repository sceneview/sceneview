/**
 * Tool definitions (schemas + descriptions) for the SceneView MCP server.
 *
 * This file intentionally contains NO handler code — only the static MCP
 * tool metadata. The runtime handlers live in `handler.ts`. Splitting them
 * lets the gateway list tools without pulling the (larger) handler tree and
 * keeps the stdio server's `ListToolsRequestSchema` response identical to
 * what it was before the refactor.
 */

import { SAMPLE_IDS, SAMPLES } from "../samples.js";
import { PLATFORM_IDS } from "../platform-setup.js";
import { DEBUG_CATEGORIES } from "../debug-issue.js";
import type { ToolDefinition } from "./types.js";

export const TOOL_DEFINITIONS: ToolDefinition[] = [
  {
    name: "get_sample",
    description:
      "Returns a complete, compilable Kotlin sample for a given SceneView scenario. Use this to get a working starting point before customising. Call `list_samples` first if you are unsure which scenario fits.",
    inputSchema: {
      type: "object",
      properties: {
        scenario: {
          type: "string",
          enum: SAMPLE_IDS,
          description: `The scenario to fetch:\n${SAMPLE_IDS.map((id) => `- "${id}": ${SAMPLES[id].description}`).join("\n")}`,
        },
      },
      required: ["scenario"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "list_samples",
    description:
      "Lists all available SceneView code samples with their IDs, descriptions, and tags. Use this to find the right sample before calling `get_sample`, or to show the user what SceneView can do.",
    inputSchema: {
      type: "object",
      properties: {
        tag: {
          type: "string",
          description:
            "Optional tag to filter by (e.g. \"ar\", \"3d\", \"ios\", \"swift\", \"anchor\", \"geometry\", \"animation\", \"video\", \"lighting\"). Omit to list all samples.",
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
    name: "get_setup",
    description:
      "Returns the Gradle dependency and AndroidManifest snippet required to use SceneView in an Android project.",
    inputSchema: {
      type: "object",
      properties: {
        type: {
          type: "string",
          enum: ["3d", "ar"],
          description: '"3d" for 3D-only scenes. "ar" for augmented reality (includes 3D).',
        },
      },
      required: ["type"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "validate_code",
    description:
      "Checks a Kotlin or Swift SceneView snippet for common mistakes. For Kotlin: threading violations, wrong destroy order, missing null-checks, LightNode trailing-lambda bug, deprecated 2.x APIs. For Swift: missing @MainActor, async/await patterns, missing imports, RealityKit mistakes. Language is auto-detected. Always call this before presenting generated SceneView code to the user.",
    inputSchema: {
      type: "object",
      properties: {
        code: {
          type: "string",
          description: "The Kotlin or Swift source code to validate (composable function, SwiftUI view, class, or file). Language is auto-detected.",
        },
      },
      required: ["code"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_migration_guide",
    description:
      "Returns the full SceneView 2.x → 3.0 migration guide. Use this when a user reports code that worked in 2.x but breaks in 3.0, or when helping someone upgrade.",
    inputSchema: {
      type: "object",
      properties: {},
      required: [],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_node_reference",
    description:
      "Returns the full API reference for a specific SceneView node type or composable — parameters, types, and a usage example — parsed directly from the official llms.txt. Use this when you need the exact signature or options for a node (e.g. ModelNode, LightNode, ARScene). If the requested type is not found, the response lists all available types.",
    inputSchema: {
      type: "object",
      properties: {
        nodeType: {
          type: "string",
          description:
            'The node type or composable name to look up, e.g. "ModelNode", "LightNode", "ARScene", "AnchorNode". Case-insensitive.',
        },
      },
      required: ["nodeType"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_platform_roadmap",
    description:
      "Returns the SceneView multi-platform roadmap — current Android support status, planned iOS/KMP/web targets, and timeline. Use this when the user asks about cross-platform support, iOS, Kotlin Multiplatform, or future plans.",
    inputSchema: {
      type: "object",
      properties: {},
      required: [],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_best_practices",
    description:
      "Returns SceneView performance and architecture best practices — memory management, model optimization, threading rules, Compose integration patterns, and common anti-patterns. Use this when the user asks about performance, optimization, best practices, or architecture.",
    inputSchema: {
      type: "object",
      properties: {
        category: {
          type: "string",
          enum: ["all", "performance", "architecture", "memory", "threading"],
          description:
            'Category to filter by. "all" returns everything. Defaults to "all" if omitted.',
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
    name: "get_ar_setup",
    description:
      "Returns detailed AR setup instructions — AndroidManifest permissions and features, Gradle dependencies, ARCore session configuration options (depth, light estimation, instant placement, plane detection, image tracking, cloud anchors), and a complete working AR starter template. More detailed than `get_setup` for AR-specific configuration.",
    inputSchema: {
      type: "object",
      properties: {},
      required: [],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_troubleshooting",
    description:
      "Returns the SceneView troubleshooting guide — common crashes (SIGABRT, model not showing), build failures, AR issues (drift, overexposure, image detection), and performance problems. Use this when a user reports something not working, a crash, or unexpected behavior.",
    inputSchema: {
      type: "object",
      properties: {},
      required: [],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_ios_setup",
    description:
      "Returns the complete iOS setup guide for SceneViewSwift — SPM dependency, Package.swift example, minimum platform versions, Info.plist entries for AR (camera permission), and basic SwiftUI integration code. Use this when a user wants to set up SceneView for iOS, macOS, or visionOS.",
    inputSchema: {
      type: "object",
      properties: {
        type: {
          type: "string",
          enum: ["3d", "ar"],
          description: '"3d" for 3D-only scenes. "ar" for augmented reality (requires iOS, not macOS/visionOS via this path).',
        },
      },
      required: ["type"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_web_setup",
    description:
      "Returns the complete Web setup guide for SceneView Web — npm install, Kotlin/JS Gradle config, HTML canvas setup, and basic Filament.js integration code. SceneView Web uses the same Filament rendering engine as Android, compiled to WebAssembly. Use this when a user wants to set up SceneView for browsers.",
    inputSchema: {
      type: "object",
      properties: {},
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "render_3d_preview",
    description:
      "Generates an interactive 3D preview link. Accepts a model URL, a SceneView code snippet, or both. Returns a URL to sceneview.github.io/preview that renders the model in the browser with orbit controls, AR support, and sharing. For model URLs: embeds a model-viewer link directly. For code snippets: shows the 3D preview with the code in a companion panel. Use this when you want to show a 3D model to the user — paste the link in your response and they can click to see it live.",
    inputSchema: {
      type: "object",
      properties: {
        modelUrl: {
          type: "string",
          description: "Public URL to a .glb or .gltf model file. Must be HTTPS and CORS-enabled. If omitted, a default model is used.",
        },
        codeSnippet: {
          type: "string",
          description: "SceneView code snippet (Kotlin or Swift) to display alongside the 3D preview in a companion panel. Useful when showing generated code together with a live preview.",
        },
        autoRotate: {
          type: "boolean",
          description: "Auto-rotate the model (default: true).",
        },
        ar: {
          type: "boolean",
          description: "Enable AR mode on supported devices (default: true).",
        },
        title: {
          type: "string",
          description: "Custom title shown above the preview.",
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
    name: "create_3d_artifact",
    description:
      'Generates a complete, self-contained HTML page with interactive 3D content that Claude can render as an artifact. Returns valid HTML using model-viewer (Google\'s web component for 3D). Use this when the user asks to "show", "preview", "visualize" 3D models, create 3D charts/dashboards, or view products in 360°. The HTML works standalone in any browser, supports AR on mobile, and includes orbit controls. Types: "model-viewer" for 3D model viewing, "chart-3d" for 3D data visualization (bar charts with perspective), "scene" for rich 3D scenes with lighting, "product-360" for product turntables with hotspot annotations.',
    inputSchema: {
      type: "object",
      properties: {
        type: {
          type: "string",
          enum: ["model-viewer", "chart-3d", "scene", "product-360", "geometry"],
          description:
            '"model-viewer": interactive 3D model viewer with orbit + AR. "chart-3d": 3D bar chart for data visualization. "scene": rich 3D scene with lighting. "product-360": product turntable with hotspot annotations. "geometry": procedural 3D shapes (cubes, spheres, cylinders, planes, lines) — Claude can DRAW in 3D! Use this when the user asks to draw, build, or visualize 3D shapes.',
        },
        modelUrl: {
          type: "string",
          description:
            "Public HTTPS URL to a .glb or .gltf model. If omitted, a default model is used. Not needed for chart-3d type.",
        },
        title: {
          type: "string",
          description: "Title displayed on the artifact. Defaults to a sensible name per type.",
        },
        data: {
          type: "array",
          items: {
            type: "object",
            properties: {
              label: { type: "string", description: "Data point label (e.g. 'Q1 2025')" },
              value: { type: "number", description: "Numeric value" },
              color: { type: "string", description: "Optional hex color (e.g. '#4285F4'). Auto-assigned if omitted." },
            },
            required: ["label", "value"],
          },
          description:
            'Data array for chart-3d type. Each item has {label, value, color?}. Required for "chart-3d", ignored for other types.',
        },
        options: {
          type: "object",
          properties: {
            autoRotate: { type: "boolean", description: "Auto-rotate the model (default: true)" },
            ar: { type: "boolean", description: "Enable AR on mobile devices (default: true)" },
            backgroundColor: { type: "string", description: "Background color as hex (default: '#1a1a2e')" },
            cameraOrbit: { type: "string", description: "Camera orbit string (default: '0deg 75deg 105%')" },
          },
          description: "Visual options for the 3D artifact.",
        },
        hotspots: {
          type: "array",
          items: {
            type: "object",
            properties: {
              position: { type: "string", description: 'Hotspot 3D position, e.g. "0.5 1.2 0.3"' },
              normal: { type: "string", description: 'Hotspot surface normal, e.g. "0 1 0"' },
              label: { type: "string", description: "Hotspot label" },
              description: { type: "string", description: "Hotspot description" },
            },
            required: ["position", "normal", "label"],
          },
          description: "Annotation hotspots for product-360 type. Each has position, normal, label, and optional description.",
        },
        shapes: {
          type: "array",
          items: {
            type: "object",
            properties: {
              type: {
                type: "string",
                enum: ["cube", "sphere", "cylinder", "plane", "line"],
                description: 'Shape type: "cube", "sphere", "cylinder", "plane" (flat surface), or "line" (thin cylinder connecting points).',
              },
              position: {
                type: "array",
                items: { type: "number" },
                description: "Position [x, y, z] in world space. Y is up. Default: [0, 0, 0].",
              },
              scale: {
                type: "array",
                items: { type: "number" },
                description: "Scale [x, y, z]. For cube: edge sizes. For sphere: diameters. For line: [length, thickness, thickness]. Default: [1, 1, 1].",
              },
              color: {
                type: "array",
                items: { type: "number" },
                description: "Color [r, g, b] in 0-1 range. E.g. [1, 0, 0] for red, [0.2, 0.5, 1] for sky blue. Default: [0.8, 0.8, 0.8].",
              },
              metallic: {
                type: "number",
                description: "Metallic factor 0-1. 0 = plastic/matte, 1 = metal. Default: 0.",
              },
              roughness: {
                type: "number",
                description: "Roughness factor 0-1. 0 = mirror/glossy, 1 = rough/matte. Default: 0.5.",
              },
            },
            required: ["type"],
          },
          description:
            'Array of procedural 3D shapes for "geometry" type. Each shape has type, position, scale, color, metallic, roughness. Required for "geometry" type. Example: [{type:"cube",position:[0,0.5,0],scale:[1,1,1],color:[1,0,0]},{type:"sphere",position:[0,1.8,0],scale:[0.6,0.6,0.6],color:[0,0,1]}]',
        },
      },
      required: ["type"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_platform_setup",
    description:
      "Returns the complete setup guide for any SceneView-supported platform: Android, iOS, Web, Flutter, React Native, Desktop, or Android TV. Includes dependencies, manifest/permissions, minimum SDK, and a working starter template. Replaces `get_setup`, `get_ios_setup`, and `get_web_setup` with a single unified tool. Use this when a user wants to set up SceneView on any platform.",
    inputSchema: {
      type: "object",
      properties: {
        platform: {
          type: "string",
          enum: PLATFORM_IDS,
          description: `Target platform:\n${PLATFORM_IDS.map((p) => `- "${p}"`).join("\n")}`,
        },
        type: {
          type: "string",
          enum: ["3d", "ar"],
          description: '"3d" for 3D-only scenes. "ar" for augmented reality. Some platforms only support 3D.',
        },
      },
      required: ["platform", "type"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "migrate_code",
    description:
      "Automatically migrates SceneView 2.x Kotlin code to 3.x. Applies known renames (SceneView→Scene, ArSceneView→ARScene), replaces deprecated APIs (loadModelAsync→rememberModelInstance, Engine.create→rememberEngine), fixes LightNode trailing-lambda bug, removes Sceneform imports, and more. Returns the migrated code with a detailed changelog. Use this when a user has 2.x code that needs updating, or when you detect 2.x patterns in their code.",
    inputSchema: {
      type: "object",
      properties: {
        code: {
          type: "string",
          description: "The SceneView 2.x Kotlin code to migrate. Can be a snippet, a function, or a full file.",
        },
      },
      required: ["code"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "debug_issue",
    description:
      'Returns a targeted debugging guide for a specific SceneView issue. Categories: "model-not-showing" (invisible models), "ar-not-working" (AR camera/planes), "crash" (SIGABRT/native), "performance" (low FPS/memory), "build-error" (Gradle/dependency), "black-screen" (no rendering), "lighting" (dark/bright/shadows), "gestures" (touch/drag), "ios" (Swift/RealityKit). You can provide a category directly, or describe the problem and it will be auto-detected. Use this when a user reports something not working.',
    inputSchema: {
      type: "object",
      properties: {
        category: {
          type: "string",
          enum: DEBUG_CATEGORIES,
          description: `The issue category. If omitted, provide \`description\` for auto-detection.\n${DEBUG_CATEGORIES.map((c) => `- "${c}"`).join("\n")}`,
        },
        description: {
          type: "string",
          description: 'Free-text description of the issue (e.g., "my model is not showing", "app crashes on destroy"). Used for auto-detection when category is omitted.',
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
    name: "generate_scene",
    description:
      'Generates a complete, compilable SceneView{} or ARSceneView{} Kotlin composable from a natural language description. Parses 40+ object types (furniture, vehicles, animals, food, buildings, nature), quantities ("two chairs", "3 spheres"), environment (indoor/outdoor/dark), and mode (3D or AR). Returns working Kotlin code with proper engine setup, model loading, lighting, and ground plane. Use this when a user says "build me a scene with..." or describes a 3D scene they want to create.',
    inputSchema: {
      type: "object",
      properties: {
        description: {
          type: "string",
          description: 'Natural language description of the desired 3D scene. Examples: "a room with a table and two chairs", "AR scene with a robot on the floor", "outdoor scene with three trees and a car", "dark room with a sphere and a cube", "a dog and a cat in a garden", "house with a fence and flowers".',
        },
      },
      required: ["description"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "list_platforms",
    description:
      "Returns all platforms supported by SceneView with their renderer, framework, status, and version. Use this to answer questions about what platforms SceneView supports, or to show cross-platform capabilities.",
    inputSchema: {
      type: "object",
      properties: {},
      required: [],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_animation_guide",
    description:
      "Returns a comprehensive guide for animating 3D models in SceneView — playing embedded glTF animations, Spring physics animations (KMP core), Compose property animations (animateFloatAsState, InfiniteTransition), SmoothTransform for smooth following, and AR animated models. Includes compilable Kotlin code samples. Use this when a user asks about animation, motion, springs, smooth movement, or how to play model animations.",
    inputSchema: {
      type: "object",
      properties: {},
      required: [],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_gesture_guide",
    description:
      "Returns a comprehensive guide for adding gestures to 3D objects in SceneView — isEditable for one-line pinch-to-scale/drag-to-rotate/tap-to-select, custom onTouchEvent handlers, AR tap-to-place, drag-to-rotate with sensitivity, pinch-to-scale with limits, multi-model selection, and HitResultNode surface cursor. Includes compilable Kotlin code samples. Use this when a user asks about touch, gestures, interaction, drag, pinch, tap, or editing 3D objects.",
    inputSchema: {
      type: "object",
      properties: {},
      required: [],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_performance_tips",
    description:
      "Returns a comprehensive performance optimization guide for SceneView — polygon budgets per device tier, LOD, texture compression (KTX2/Basis Universal), mesh compression (Draco/Meshopt), engine reuse, per-frame allocation avoidance, frustum culling, instancing, lighting optimization, post-processing costs, and profiling with Systrace and Android GPU Inspector. Includes code samples and CLI commands. Use this when a user asks about performance, optimization, FPS, memory, profiling, or slow rendering.",
    inputSchema: {
      type: "object",
      properties: {},
      required: [],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_material_guide",
    description:
      "Returns a comprehensive guide for PBR materials in SceneView — baseColor, metallic, roughness, reflectance, emissive, clearCoat, normal maps. Includes recipes for common materials (glass, chrome, gold, rubber, car paint), code samples for modifying materials on ModelNode, texture setup, and environment lighting requirements. Use this when a user asks about materials, textures, colors, shaders, appearance, or why their model looks wrong (flat, dark, too shiny).",
    inputSchema: {
      type: "object",
      properties: {},
      required: [],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_collision_guide",
    description:
      "Returns a comprehensive guide for collision detection, hit testing, and physics in SceneView — node tapping (onTouchEvent), AR surface hit testing (frame.hitTest), ray-box/ray-sphere intersection (KMP core), bounding boxes, and basic rigid body physics. Use this when a user asks about tapping 3D objects, collision detection, physics simulation, ray casting, or hit testing.",
    inputSchema: {
      type: "object",
      properties: {},
      required: [],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_model_optimization_guide",
    description:
      "Returns a complete guide for optimizing 3D models for SceneView — polygon budgets per device tier, file size targets, Draco/Meshopt mesh compression, KTX2 texture compression, the recommended optimization pipeline (gltf-transform CLI), texture sizing rules, LOD strategies, and quick wins. Use this when a user asks about model optimization, file size, load times, polygon count, texture compression, or preparing models for mobile.",
    inputSchema: {
      type: "object",
      properties: {},
      required: [],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_web_rendering_guide",
    description:
      "Returns a comprehensive guide for SceneView Web (Filament.js WASM) — architecture, quick start (sceneview.js npm or Kotlin/JS), IBL environment lighting (critical for PBR quality), rendering quality settings (SSAO, bloom, TAA), camera exposure tuning, Filament.js vs model-viewer comparison, and web performance tips. Use this when a user asks about web 3D rendering, Filament.js, browser viewing, WebGL, or wants to display 3D models in a web page.",
    inputSchema: {
      type: "object",
      properties: {},
      required: [],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "search_models",
    description:
      "Searches Sketchfab for free 3D models matching a natural-language query and returns a shortlist with names, authors, licenses, thumbnails, triangle counts, and viewer/embed URLs. Use this BEFORE generating SceneView code when the user asks for a specific asset (\"a red sports car\", \"a low-poly tree\", \"a sci-fi robot\") — pick the best result, then load it with `rememberModelInstance(modelLoader, \"models/your-file.glb\")` or embed its viewer URL. Requires a free `SKETCHFAB_API_KEY` environment variable (BYOK — nothing is charged by SceneView). If the key is missing, the tool returns instructions for getting one at sketchfab.com/register.",
    inputSchema: {
      type: "object",
      properties: {
        query: {
          type: "string",
          description: "Free-text search query, e.g. \"red sports car\", \"low-poly pine tree\", \"sci-fi robot\".",
        },
        category: {
          type: "string",
          description: "Optional Sketchfab category slug to narrow results (e.g. \"cars-vehicles\", \"animals-pets\", \"architecture\", \"furniture-home\", \"weapons-military\").",
        },
        downloadable: {
          type: "boolean",
          description: "Restrict to downloadable models so you can actually load the asset. Default: true. Set to false to include view-only models.",
        },
        maxResults: {
          type: "number",
          description: "Maximum number of results to return. Clamped to [1, 10]. Default: 6.",
        },
      },
      required: ["query"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: true,
      destructiveHint: false,
    },
  },
  {
    name: "analyze_project",
    description:
      "Scans a local SceneView project on the user's machine and returns a structured analysis: detected project type (Android, iOS, Web), extracted SceneView dependency version, whether it is outdated vs the latest known release, and any known anti-patterns found by reading source files (threading violations, LightNode trailing-lambda bug, deprecated 2.x APIs, Sceneform imports). Safe: scans at most 30 source files and 500 KB total, never writes to disk. Use this when a user asks 'is my project up to date?', 'what's wrong with my SceneView code?', or when you want a fast sanity check of a project before generating code for it.",
    inputSchema: {
      type: "object",
      properties: {
        path: {
          type: "string",
          description:
            "Absolute or relative path to the project root to scan. Defaults to the current working directory of the MCP server.",
        },
      },
      required: [],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: true,
      destructiveHint: false,
    },
  },
  {
    name: "search_android_docs",
    description:
      "Searches Google's stock Android documentation knowledge base (~4 800 entries: Jetpack Compose, Camera2, ARCore SDK, Kotlin APIs, platform guides) and returns matching entries with their `kb://...` URIs. Use this to cross-reference stock Android APIs with SceneView code — e.g. \"how does LazyColumn paging work\", \"Camera2 capture session\", \"ARCore Config options\". Then call `fetch_android_doc` with a returned URI to read the full entry. Requires Google's `android` CLI on the MCP host's PATH (an optional runtime dependency — sceneview-mcp works without it); if the CLI is absent the tool returns clear install instructions instead of crashing.",
    inputSchema: {
      type: "object",
      properties: {
        query: {
          type: "string",
          description: "Free-text search query, e.g. \"LazyColumn paging\", \"Camera2 capture session\", \"ARCore anchors\".",
        },
      },
      required: ["query"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: true,
      destructiveHint: false,
    },
  },
  {
    name: "fetch_android_doc",
    description:
      "Fetches the full text of a single stock Android documentation entry by its knowledge-base URI (`kb://...`), as returned by `search_android_docs`. Use this after `search_android_docs` to read a complete guide or API reference. Requires Google's `android` CLI on the MCP host's PATH (an optional runtime dependency); if the CLI is absent the tool returns clear install instructions instead of crashing.",
    inputSchema: {
      type: "object",
      properties: {
        uri: {
          type: "string",
          description: "The knowledge-base URI to fetch, e.g. \"kb://compose/lists/lazy-column\". A bare path with no scheme is tolerated and normalised to kb://.",
        },
      },
      required: ["uri"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: true,
      destructiveHint: false,
    },
  },
];
