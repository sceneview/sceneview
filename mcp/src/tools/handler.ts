/**
 * Pure tool dispatcher for the SceneView MCP server.
 *
 * This module contains the full tool-handling logic extracted from
 * `mcp/src/index.ts`. It has no dependency on the MCP stdio transport — it
 * just takes a tool name + argument bag and returns a `ToolResult`. This
 * lets the same logic be consumed by:
 *
 *   1. The stdio MCP server in `../index.ts` (legacy npm package path).
 *   2. The HTTP gateway in `mcp-gateway/src/mcp/registry.ts` (hosted path).
 *
 * Zero runtime behaviour change from 4.0.0: every tool returns exactly the
 * same content it did when the logic lived inside the stdio server's
 * `CallToolRequestSchema` handler.
 *
 * Filesystem note: we embed `llms.txt` via `../generated/llms-txt.ts` so
 * this module is safe to run in environments without a filesystem
 * (Cloudflare Workers). The generator script at
 * `mcp/scripts/generate-llms-txt.js` keeps that file in sync with the
 * canonical `llms.txt` at the repo root.
 */

import { getSample, SAMPLE_IDS, SAMPLES } from "../samples.js";
import { validateCode, formatValidationReport } from "../validator.js";
import { MIGRATION_GUIDE } from "../migration.js";
import { parseNodeSections, findNodeSection, listNodeTypes } from "../node-reference.js";
import {
  PLATFORM_ROADMAP,
  BEST_PRACTICES,
  AR_SETUP_GUIDE,
  TROUBLESHOOTING_GUIDE,
} from "../guides.js";
import {
  buildPreviewUrl,
  validatePreviewInput,
  formatPreviewResponse,
} from "../preview.js";
import {
  validateArtifactInput,
  generateArtifact,
  formatArtifactResponse,
  type ArtifactType,
} from "../artifact.js";
import {
  getPlatformSetup,
  type Platform,
  type SetupType,
} from "../platform-setup.js";
import { migrateCode, formatMigrationResult } from "../migrate-code.js";
import {
  getDebugGuide,
  autoDetectIssue,
  DEBUG_CATEGORIES,
  type DebugCategory,
} from "../debug-issue.js";
import { generateScene, formatGeneratedScene } from "../generate-scene.js";
import {
  ANIMATION_GUIDE,
  GESTURE_GUIDE,
  PERFORMANCE_TIPS,
} from "../advanced-guides.js";
import {
  MATERIAL_GUIDE,
  COLLISION_GUIDE,
  MODEL_OPTIMIZATION_GUIDE,
  WEB_RENDERING_GUIDE,
} from "../extra-guides.js";
import { searchModels, formatSearchResults } from "../search-models.js";
import { generateModel, formatGenerateResult, type GenerateQuality } from "../generate-model.js";
import { analyzeProject, formatAnalysisReport } from "../analyze-project.js";
import {
  searchAndroidDocs,
  fetchAndroidDoc,
  formatAndroidDocsSearch,
  formatAndroidDocsFetch,
} from "../android-docs.js";
import { LLMS_TXT } from "../generated/llms-txt.js";

import type { DispatchContext, ToolResult, ToolTextContent } from "./types.js";
import { LATEST_SCENEVIEW_RELEASE } from "../generated/version.js";

// ─── Legal disclaimer (identical to index.ts 4.0.0) ─────────────────────

const DISCLAIMER =
  "\n\n---\n*Generated code suggestion. Review before use in production. See [TERMS.md](https://github.com/sceneview/sceneview/blob/main/mcp/TERMS.md).*";

// ─── Sponsor CTA (shown every N tool calls, opt-out via env var) ────────────
//
// Non-intrusive monetization: append a one-line sponsor call-to-action after
// every `SPONSOR_CTA_INTERVAL` tool calls. Disabled by setting
// `SCENEVIEW_SPONSOR_CTA=0`. The counter is module-scoped (per MCP process
// lifetime), not persisted.

const SPONSOR_CTA_INTERVAL = 10;

const SPONSOR_CTA =
  "\n\n💙 *Building SceneView is a one-dev labor of love. If it saved you time, consider [supporting on GitHub Sponsors](https://github.com/sponsors/sceneview).*";

let toolCallCount = 0;

/** Test-only: reset the module-scoped counter between test cases. */
export function __resetSponsorCounter(): void {
  toolCallCount = 0;
}

function shouldShowSponsorCta(): boolean {
  if (process.env.SCENEVIEW_SPONSOR_CTA === "0") return false;
  toolCallCount += 1;
  return toolCallCount % SPONSOR_CTA_INTERVAL === 0;
}

function withDisclaimer<T extends ToolTextContent>(content: T[]): T[] {
  if (content.length === 0) return content;
  const last = content[content.length - 1];
  const suffix = DISCLAIMER + (shouldShowSponsorCta() ? SPONSOR_CTA : "");
  return [
    ...content.slice(0, -1),
    { ...last, text: last.text + suffix },
  ];
}

// ─── llms.txt-derived state ──────────────────────────────────────────────────

/** Raw API docs markdown — exported for the `sceneview://api` resource. */
export const API_DOCS = LLMS_TXT;

/** Parsed node-reference sections, shared across `get_node_reference` calls. */
const NODE_SECTIONS = parseNodeSections(API_DOCS);

// ─── Public dispatch entrypoint ──────────────────────────────────────────────

/**
 * Run a SceneView MCP tool and return its MCP-formatted result.
 *
 * The caller is responsible for:
 *   - enforcing tier/pro access (the stdio server does this; the gateway
 *     does the same before reaching this function),
 *   - rate limiting,
 *   - recording billing usage.
 *
 * This function performs NO auth and NO billing — it is pure business logic.
 *
 * @param toolName  MCP tool name, e.g. `"get_sample"`.
 * @param args      Arguments bag from the MCP client. `undefined` and
 *                  `null` are both tolerated.
 * @param _ctx      Optional context (user, tier, request id). Currently
 *                  unused by any handler, reserved for future tool wiring.
 */
export async function dispatchTool(
  toolName: string,
  args: Record<string, unknown> | undefined,
  _ctx: DispatchContext = {},
): Promise<ToolResult> {
  switch (toolName) {
    // ── get_sample ────────────────────────────────────────────────────────────
    case "get_sample": {
      const scenario = args?.scenario as string;
      const sample = getSample(scenario);
      if (!sample) {
        return {
          content: [
            {
              type: "text",
              text: `Unknown scenario "${scenario}". Call \`list_samples\` to see available options.`,
            },
          ],
          isError: true,
        };
      }
      const isIos = sample.language === "swift";
      const depBlock = isIos
        ? [
            `**SPM dependency:**`,
            `\`\`\`swift`,
            `.package(url: "${sample.spmDependency ?? sample.dependency}", from: "${LATEST_SCENEVIEW_RELEASE}")`,
            `\`\`\``,
          ]
        : [
            `**Gradle dependency:**`,
            `\`\`\`kotlin`,
            `implementation("${sample.dependency}")`,
            `\`\`\``,
          ];
      const codeLang = isIos ? "swift" : "kotlin";
      const codeLabel = isIos ? "**Swift (SwiftUI):**" : "**Kotlin (Jetpack Compose):**";

      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${sample.title}`,
              ``,
              `**Tags:** ${sample.tags.join(", ")}`,
              ``,
              ...depBlock,
              ``,
              codeLabel,
              `\`\`\`${codeLang}`,
              sample.code,
              `\`\`\``,
              ``,
              `**Prompt that generates this:**`,
              `> ${sample.prompt}`,
            ].join("\n"),
          },
        ]),
      };
    }

    // ── list_samples ──────────────────────────────────────────────────────────
    case "list_samples": {
      const filterTag = args?.tag as string | undefined;
      const entries = Object.values(SAMPLES).filter(
        (s) => !filterTag || s.tags.includes(filterTag as any)
      );

      if (entries.length === 0) {
        return {
          content: [
            {
              type: "text",
              text: `No samples found with tag "${filterTag}". Available tags: 3d, ar, model, geometry, animation, camera, environment, anchor, plane-detection, image-tracking, cloud-anchor, point-cloud, placement, gestures, physics, sky, fog, lines, text, reflection, post-processing, ios, swift, video, lighting`,
            },
          ],
        };
      }

      const header = filterTag
        ? `## SceneView samples tagged \`${filterTag}\` (${entries.length})\n`
        : `## All SceneView samples (${entries.length})\n`;

      const rows = entries
        .map(
          (s) => {
            const depLabel = s.language === "swift" ? "*SPM:*" : "*Dependency:*";
            return `### \`${s.id}\`\n**${s.title}**${s.language === "swift" ? " (Swift/iOS)" : ""}\n${s.description}\n*Tags:* ${s.tags.join(", ")}\n${depLabel} \`${s.dependency}\`\n\nCall \`get_sample("${s.id}")\` for the full code.`;
          }
        )
        .join("\n\n---\n\n");

      return { content: withDisclaimer([{ type: "text", text: header + rows }]) };
    }

    // ── get_setup ─────────────────────────────────────────────────────────────
    case "get_setup": {
      const type = args?.type as "3d" | "ar";
      if (type === "3d") {
        return {
          content: withDisclaimer([
            {
              type: "text",
              text: [
                `## SceneView — 3D setup`,
                ``,
                `### build.gradle.kts`,
                `\`\`\`kotlin`,
                `dependencies {`,
                `    implementation("io.github.sceneview:sceneview:${LATEST_SCENEVIEW_RELEASE}")`,
                `}`,
                `\`\`\``,
                ``,
                `No manifest changes required for 3D-only scenes.`,
              ].join("\n"),
            },
          ]),
        };
      }
      if (type === "ar") {
        return {
          content: withDisclaimer([
            {
              type: "text",
              text: [
                `## SceneView — AR setup`,
                ``,
                `### build.gradle.kts`,
                `\`\`\`kotlin`,
                `dependencies {`,
                `    implementation("io.github.sceneview:arsceneview:${LATEST_SCENEVIEW_RELEASE}")`,
                `}`,
                `\`\`\``,
                ``,
                `### AndroidManifest.xml`,
                `\`\`\`xml`,
                `<uses-permission android:name="android.permission.CAMERA" />`,
                `<uses-feature android:name="android.hardware.camera.ar" android:required="true" />`,
                `<application>`,
                `    <meta-data android:name="com.google.ar.core" android:value="required" />`,
                `</application>`,
                `\`\`\``,
              ].join("\n"),
            },
          ]),
        };
      }
      return {
        content: [{ type: "text", text: `Unknown type "${type}". Use "3d" or "ar".` }],
        isError: true,
      };
    }

    // ── validate_code ─────────────────────────────────────────────────────────
    case "validate_code": {
      const code = args?.code as string;
      if (!code || typeof code !== "string") {
        return {
          content: [{ type: "text", text: "Missing required parameter: `code`" }],
          isError: true,
        };
      }
      const issues = validateCode(code);
      const report = formatValidationReport(issues);
      return { content: withDisclaimer([{ type: "text", text: report }]) };
    }

    // ── get_migration_guide ───────────────────────────────────────────────────
    case "get_migration_guide": {
      return { content: withDisclaimer([{ type: "text", text: MIGRATION_GUIDE }]) };
    }

    // ── get_node_reference ────────────────────────────────────────────────────
    case "get_node_reference": {
      const nodeType = args?.nodeType as string;
      if (!nodeType || typeof nodeType !== "string") {
        return {
          content: [{ type: "text", text: "Missing required parameter: `nodeType`" }],
          isError: true,
        };
      }

      const section = findNodeSection(NODE_SECTIONS, nodeType);

      if (!section) {
        const available = listNodeTypes(NODE_SECTIONS).join(", ");
        return {
          content: [
            {
              type: "text",
              text: [
                `No reference found for node type \`${nodeType}\`.`,
                ``,
                `**Available node types:**`,
                available,
              ].join("\n"),
            },
          ],
        };
      }

      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## \`${section.name}\` — API Reference`,
              ``,
              section.content,
            ].join("\n"),
          },
        ]),
      };
    }

    // ── get_platform_roadmap ────────────────────────────────────────────────
    case "get_platform_roadmap": {
      return { content: withDisclaimer([{ type: "text", text: PLATFORM_ROADMAP }]) };
    }

    // ── get_best_practices ───────────────────────────────────────────────────
    case "get_best_practices": {
      const category = (args?.category as string) || "all";
      const text = BEST_PRACTICES[category] ?? BEST_PRACTICES["all"];
      return { content: withDisclaimer([{ type: "text", text }]) };
    }

    // ── get_ar_setup ─────────────────────────────────────────────────────────
    case "get_ar_setup": {
      return { content: withDisclaimer([{ type: "text", text: AR_SETUP_GUIDE }]) };
    }

    // ── get_troubleshooting ──────────────────────────────────────────────────
    case "get_troubleshooting": {
      return { content: withDisclaimer([{ type: "text", text: TROUBLESHOOTING_GUIDE }]) };
    }

    // ── get_ios_setup ─────────────────────────────────────────────────────────
    case "get_ios_setup": {
      const iosType = args?.type as "3d" | "ar";
      if (iosType === "3d") {
        return {
          content: withDisclaimer([
            {
              type: "text",
              text: [
                `## SceneViewSwift — iOS/macOS/visionOS 3D Setup`,
                ``,
                `### 1. Add SPM Dependency`,
                ``,
                `In Xcode: **File → Add Package Dependencies** → paste:`,
                `\`\`\``,
                `https://github.com/sceneview/sceneview`,
                `\`\`\``,
                `Set version rule to **"from: ${LATEST_SCENEVIEW_RELEASE}"**.`,
                ``,
                `Or in Package.swift:`,
                `\`\`\`swift`,
                `// swift-tools-version: 5.10`,
                `import PackageDescription`,
                ``,
                `let package = Package(`,
                `    name: "MyApp",`,
                `    platforms: [.iOS(.v17), .macOS(.v14), .visionOS(.v1)],`,
                `    dependencies: [`,
                `        .package(url: "https://github.com/sceneview/sceneview", from: "${LATEST_SCENEVIEW_RELEASE}")`,
                `    ],`,
                `    targets: [`,
                `        .executableTarget(`,
                `            name: "MyApp",`,
                `            dependencies: [`,
                `                .product(name: "SceneViewSwift", package: "sceneview")`,
                `            ]`,
                `        )`,
                `    ]`,
                `)`,
                `\`\`\``,
                ``,
                `### 2. Minimum Platform Versions`,
                ``,
                `| Platform | Minimum Version |`,
                `|----------|-----------------|`,
                `| iOS      | 17.0            |`,
                `| macOS    | 14.0            |`,
                `| visionOS | 1.0             |`,
                ``,
                `### 3. Basic SwiftUI Integration`,
                ``,
                `\`\`\`swift`,
                `import SwiftUI`,
                `import SceneViewSwift`,
                `import RealityKit`,
                ``,
                `struct ContentView: View {`,
                `    @State private var model: ModelNode?`,
                ``,
                `    var body: some View {`,
                `        SceneView { root in`,
                `            if let model {`,
                `                root.addChild(model.entity)`,
                `            }`,
                `        }`,
                `        .cameraControls(.orbit)`,
                `        .task {`,
                `            model = try? await ModelNode.load("models/car.usdz")`,
                `            model?.scaleToUnits(1.0)`,
                `        }`,
                `    }`,
                `}`,
                `\`\`\``,
                ``,
                `### 4. Model Formats`,
                ``,
                `| Format | Support |`,
                `|--------|---------|`,
                `| USDZ   | Native — recommended for iOS |`,
                `| .reality | Native — RealityKit format |`,
                `| glTF/GLB | Planned via GLTFKit2 |`,
                ``,
                `No manifest or permission changes needed for 3D-only scenes.`,
              ].join("\n"),
            },
          ]),
        };
      }
      if (iosType === "ar") {
        return {
          content: withDisclaimer([
            {
              type: "text",
              text: [
                `## SceneViewSwift — iOS AR Setup`,
                ``,
                `### 1. Add SPM Dependency`,
                ``,
                `\`\`\`swift`,
                `.package(url: "https://github.com/sceneview/sceneview", from: "${LATEST_SCENEVIEW_RELEASE}")`,
                `\`\`\``,
                ``,
                `### 2. Minimum Platform`,
                ``,
                `AR requires **iOS 17.0+** (ARKit + RealityKit). macOS and visionOS use different AR APIs.`,
                ``,
                `### 3. Info.plist — Camera Permission`,
                ``,
                `Add to your Info.plist (required for AR camera access):`,
                `\`\`\`xml`,
                `<key>NSCameraUsageDescription</key>`,
                `<string>This app uses the camera for augmented reality.</string>`,
                `\`\`\``,
                ``,
                `### 4. Basic AR Integration`,
                ``,
                `\`\`\`swift`,
                `import SwiftUI`,
                `import SceneViewSwift`,
                `import RealityKit`,
                ``,
                `struct ARContentView: View {`,
                `    @State private var model: ModelNode?`,
                ``,
                `    var body: some View {`,
                `        ARSceneView(`,
                `            planeDetection: .horizontal,`,
                `            showCoachingOverlay: true,`,
                `            onTapOnPlane: { position, arView in`,
                `                guard let model else { return }`,
                `                let anchor = AnchorNode.world(position: position)`,
                `                let clone = model.entity.clone(recursive: true)`,
                `                clone.scale = .init(repeating: 0.3)`,
                `                anchor.add(clone)`,
                `                arView.scene.addAnchor(anchor.entity)`,
                `            }`,
                `        )`,
                `        .edgesIgnoringSafeArea(.all)`,
                `        .task {`,
                `            model = try? await ModelNode.load("models/robot.usdz")`,
                `        }`,
                `    }`,
                `}`,
                `\`\`\``,
                ``,
                `### 5. AR Configuration Options`,
                ``,
                `| Parameter | Options | Default |`,
                `|-----------|---------|---------|`,
                `| \`planeDetection\` | \`.none\`, \`.horizontal\`, \`.vertical\`, \`.both\` | \`.horizontal\` |`,
                `| \`showPlaneOverlay\` | \`true\` / \`false\` | \`true\` |`,
                `| \`showCoachingOverlay\` | \`true\` / \`false\` | \`true\` |`,
                `| \`imageTrackingDatabase\` | \`Set<ARReferenceImage>\` | \`nil\` |`,
                ``,
                `### 6. Image Tracking`,
                ``,
                `\`\`\`swift`,
                `let images = AugmentedImageNode.createImageDatabase([`,
                `    AugmentedImageNode.ReferenceImage(`,
                `        name: "poster",`,
                `        image: UIImage(named: "poster_ref")!,`,
                `        physicalWidth: 0.3  // meters`,
                `    )`,
                `])`,
                ``,
                `ARSceneView(`,
                `    imageTrackingDatabase: images,`,
                `    onImageDetected: { name, anchor, arView in`,
                `        let cube = GeometryNode.cube(size: 0.1, color: .blue)`,
                `        anchor.add(cube.entity)`,
                `        arView.scene.addAnchor(anchor.entity)`,
                `    }`,
                `)`,
                `\`\`\``,
              ].join("\n"),
            },
          ]),
        };
      }
      return {
        content: [{ type: "text", text: `Unknown type "${iosType}". Use "3d" or "ar".` }],
        isError: true,
      };
    }

    // ── get_web_setup ────────────────────────────────────────────────────────
    case "get_web_setup": {
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## SceneView Web — Browser 3D Setup`,
              ``,
              `SceneView Web uses **Filament.js** — the same rendering engine as Android, compiled to WebAssembly (WebGL2).`,
              ``,
              `### 1. Install`,
              ``,
              `\`\`\`bash`,
              `npm install @sceneview/sceneview-web`,
              `\`\`\``,
              ``,
              `Or in a Kotlin/JS Gradle project:`,
              `\`\`\`kotlin`,
              `// build.gradle.kts`,
              `kotlin {`,
              `    js(IR) { browser(); binaries.executable() }`,
              `    sourceSets {`,
              `        jsMain.dependencies {`,
              `            implementation("@sceneview/sceneview-web")`,
              `            // or: implementation(project(":sceneview-web"))`,
              `        }`,
              `    }`,
              `}`,
              `\`\`\``,
              ``,
              `### 2. HTML`,
              ``,
              `\`\`\`html`,
              `<canvas id="scene-canvas" style="width:100%;height:100vh"></canvas>`,
              `<script src="your-app.js"></script>`,
              `\`\`\``,
              ``,
              `### 3. Kotlin/JS Code`,
              ``,
              `\`\`\`kotlin`,
              `import io.github.sceneview.web.SceneView`,
              `import kotlinx.browser.document`,
              `import org.w3c.dom.HTMLCanvasElement`,
              ``,
              `fun main() {`,
              `    val canvas = document.getElementById("scene-canvas") as HTMLCanvasElement`,
              `    canvas.width = canvas.clientWidth`,
              `    canvas.height = canvas.clientHeight`,
              ``,
              `    SceneView.create(`,
              `        canvas = canvas,`,
              `        configure = {`,
              `            camera {`,
              `                eye(0.0, 1.5, 5.0)`,
              `                target(0.0, 0.0, 0.0)`,
              `                fov(45.0)`,
              `            }`,
              `            light {`,
              `                directional()`,
              `                intensity(100_000.0)`,
              `            }`,
              `            model("models/DamagedHelmet.glb")`,
              `            autoRotate()`,
              `        },`,
              `        onReady = { sceneView ->`,
              `            sceneView.startRendering()`,
              `        }`,
              `    )`,
              `}`,
              `\`\`\``,
              ``,
              `### 4. Features`,
              ``,
              `- Same Filament PBR renderer as Android (WASM)`,
              `- glTF 2.0 / GLB model loading`,
              `- IBL environment lighting (KTX)`,
              `- Orbit camera with mouse/touch/pinch controls`,
              `- Auto-rotation`,
              `- Directional, point, and spot lights`,
              ``,
              `### 5. Limitations`,
              ``,
              `- No AR (requires native sensors)`,
              `- WebGL2 required (~95% of browsers)`,
              `- glTF/GLB format only (same as Android)`,
            ].join("\n"),
          },
        ]),
      };
    }

    // ── render_3d_preview ──────────────────────────────────────────────────
    case "render_3d_preview": {
      const modelUrl = args?.modelUrl as string | undefined;
      const codeSnippet = args?.codeSnippet as string | undefined;
      const autoRotate = args?.autoRotate as boolean | undefined;
      const ar = args?.ar as boolean | undefined;
      const title = args?.title as string | undefined;

      const validationError = validatePreviewInput(modelUrl, codeSnippet);
      if (validationError) {
        return {
          content: [{ type: "text", text: `Error: ${validationError}` }],
          isError: true,
        };
      }

      const result = buildPreviewUrl({ modelUrl, codeSnippet, autoRotate, ar, title });
      const text = formatPreviewResponse(result);

      return { content: withDisclaimer([{ type: "text", text }]) };
    }

    // ── create_3d_artifact ───────────────────────────────────────────────────
    case "create_3d_artifact": {
      const artifactInput = {
        type: args?.type as ArtifactType,
        modelUrl: args?.modelUrl as string | undefined,
        title: args?.title as string | undefined,
        data: args?.data as any[] | undefined,
        options: args?.options as any | undefined,
        hotspots: args?.hotspots as any[] | undefined,
        shapes: args?.shapes as any[] | undefined,
      };

      const validationError = validateArtifactInput(artifactInput);
      if (validationError) {
        return {
          content: [{ type: "text", text: `Error: ${validationError}` }],
          isError: true,
        };
      }

      const result = generateArtifact(artifactInput);
      const text = formatArtifactResponse(result);
      return { content: withDisclaimer([{ type: "text", text }]) };
    }

    // ── get_platform_setup ─────────────────────────────────────────────────
    case "get_platform_setup": {
      const platform = args?.platform as Platform;
      const setupType = args?.type as SetupType;
      if (!platform || !setupType) {
        return {
          content: [{ type: "text", text: "Missing required parameters: `platform` and `type`." }],
          isError: true,
        };
      }
      const guide = getPlatformSetup(platform, setupType);
      return { content: withDisclaimer([{ type: "text", text: guide }]) };
    }

    // ── migrate_code ─────────────────────────────────────────────────────────
    case "migrate_code": {
      const code = args?.code as string;
      if (!code || typeof code !== "string") {
        return {
          content: [{ type: "text", text: "Missing required parameter: `code`." }],
          isError: true,
        };
      }
      const migrationResult = migrateCode(code);
      const migrationReport = formatMigrationResult(migrationResult);
      return { content: withDisclaimer([{ type: "text", text: migrationReport }]) };
    }

    // ── debug_issue ──────────────────────────────────────────────────────────
    case "debug_issue": {
      let category = args?.category as DebugCategory | undefined;
      const desc = args?.description as string | undefined;

      if (!category && desc) {
        category = autoDetectIssue(desc) ?? undefined;
      }
      if (!category) {
        return {
          content: [{
            type: "text",
            text: `Please provide a \`category\` or \`description\`.\n\nAvailable categories: ${DEBUG_CATEGORIES.join(", ")}`,
          }],
          isError: true,
        };
      }

      const debugGuide = getDebugGuide(category);
      const prefix = desc && autoDetectIssue(desc) === category
        ? `> Auto-detected category: **${category}** from your description.\n\n`
        : "";
      return { content: withDisclaimer([{ type: "text", text: prefix + debugGuide }]) };
    }

    // ── generate_scene ───────────────────────────────────────────────────────
    case "generate_scene": {
      const sceneDesc = args?.description as string;
      if (!sceneDesc || typeof sceneDesc !== "string") {
        return {
          content: [{ type: "text", text: "Missing required parameter: `description`." }],
          isError: true,
        };
      }
      const sceneResult = generateScene(sceneDesc);
      const sceneReport = formatGeneratedScene(sceneResult);
      return { content: withDisclaimer([{ type: "text", text: sceneReport }]) };
    }

    // ── list_platforms ────────────────────────────────────────────────────────
    case "list_platforms": {
      // All `version:` and `dependency:` fields use `LATEST_SCENEVIEW_RELEASE`
      // explicitly so each platform row renders the actually-current SDK
      // version. Pre-fix these were double-quoted strings with a literal
      // `${LATEST_SCENEVIEW_RELEASE}` substring that never interpolated —
      // users following list_platforms output saw the unresolved template
      // text. Backticks throughout = real interpolation. See #941 follow-up.
      const platforms = [
        { platform: "Android", renderer: "Filament", framework: "Jetpack Compose", status: "Stable", version: LATEST_SCENEVIEW_RELEASE, dependency: `io.github.sceneview:sceneview:${LATEST_SCENEVIEW_RELEASE}`, features: ["3D", "AR (ARCore)", "Model loading (GLB/glTF)", "Geometry nodes", "Physics", "Gestures"] },
        { platform: "Android TV", renderer: "Filament", framework: "Compose TV", status: "Alpha", version: LATEST_SCENEVIEW_RELEASE, dependency: `io.github.sceneview:sceneview:${LATEST_SCENEVIEW_RELEASE}`, features: ["3D", "D-pad controls", "Auto-rotation", "Model loading"] },
        { platform: "Android XR", renderer: "Jetpack XR SceneCore", framework: "Compose XR", status: "Planned", version: "-", dependency: "-", features: ["Spatial computing", "Hand tracking", "Passthrough"] },
        { platform: "iOS", renderer: "RealityKit", framework: "SwiftUI", status: "Alpha", version: LATEST_SCENEVIEW_RELEASE, dependency: "SceneViewSwift (SPM)", features: ["3D", "AR (ARKit)", "16 node types", "USDZ models"] },
        { platform: "macOS", renderer: "RealityKit", framework: "SwiftUI", status: "Alpha", version: LATEST_SCENEVIEW_RELEASE, dependency: "SceneViewSwift (SPM)", features: ["3D", "Orbit camera", "USDZ models"] },
        { platform: "visionOS", renderer: "RealityKit", framework: "SwiftUI", status: "Alpha", version: LATEST_SCENEVIEW_RELEASE, dependency: "SceneViewSwift (SPM)", features: ["3D", "Immersive spaces", "Hand tracking (planned)"] },
        { platform: "Web", renderer: "Filament.js (WASM)", framework: "Kotlin/JS", status: "Alpha", version: LATEST_SCENEVIEW_RELEASE, dependency: "sceneview-web (npm)", features: ["3D", "WebXR AR/VR", "GLB models", "WebGL2"] },
        { platform: "Desktop", renderer: "Software / Filament JNI", framework: "Compose Desktop", status: "Alpha", version: LATEST_SCENEVIEW_RELEASE, dependency: "sceneview-desktop (local)", features: ["3D", "Software renderer", "Wireframe"] },
        { platform: "Flutter", renderer: "Filament / RealityKit", framework: "PlatformView", status: "Alpha", version: LATEST_SCENEVIEW_RELEASE, dependency: "flutter pub: sceneview_flutter (git ref)", features: ["3D", "AR", "Android + iOS bridge"] },
      ];

      const lines = [
        "## SceneView Supported Platforms\n",
        "| Platform | Renderer | Framework | Status | Version |",
        "|----------|----------|-----------|--------|---------|",
        ...platforms.map(p => `| ${p.platform} | ${p.renderer} | ${p.framework} | ${p.status} | ${p.version} |`),
        "",
        "### Platform Details\n",
        ...platforms.map(p => [
          `**${p.platform}** (${p.status})`,
          `- Renderer: ${p.renderer}`,
          `- Framework: ${p.framework}`,
          `- Dependency: \`${p.dependency}\``,
          `- Features: ${p.features.join(", ")}`,
          "",
        ].join("\n")),
        "### Architecture",
        "",
        "SceneView uses **native renderers per platform**: Filament on Android/Web/Desktop, RealityKit on Apple (iOS/macOS/visionOS).",
        "KMP `sceneview-core` shares logic (math, collision, geometry, animations) across all platforms.",
      ];

      return { content: withDisclaimer([{ type: "text", text: lines.join("\n") }]) };
    }

    // ── get_animation_guide ─────────────────────────────────────────────────
    case "get_animation_guide": {
      return { content: withDisclaimer([{ type: "text", text: ANIMATION_GUIDE }]) };
    }

    // ── get_gesture_guide ────────────────────────────────────────────────────
    case "get_gesture_guide": {
      return { content: withDisclaimer([{ type: "text", text: GESTURE_GUIDE }]) };
    }

    // ── get_performance_tips ─────────────────────────────────────────────────
    case "get_performance_tips": {
      return { content: withDisclaimer([{ type: "text", text: PERFORMANCE_TIPS }]) };
    }

    // ── get_material_guide ───────────────────────────────────────────────────
    case "get_material_guide": {
      return { content: withDisclaimer([{ type: "text", text: MATERIAL_GUIDE }]) };
    }

    // ── get_collision_guide ──────────────────────────────────────────────────
    case "get_collision_guide": {
      return { content: withDisclaimer([{ type: "text", text: COLLISION_GUIDE }]) };
    }

    // ── get_model_optimization_guide ─────────────────────────────────────────
    case "get_model_optimization_guide": {
      return { content: withDisclaimer([{ type: "text", text: MODEL_OPTIMIZATION_GUIDE }]) };
    }

    // ── get_web_rendering_guide ──────────────────────────────────────────────
    case "get_web_rendering_guide": {
      return { content: withDisclaimer([{ type: "text", text: WEB_RENDERING_GUIDE }]) };
    }

    // ── search_models ────────────────────────────────────────────────────────
    case "search_models": {
      const query = args?.query as string | undefined;
      if (!query || typeof query !== "string" || query.trim().length === 0) {
        return {
          content: [{ type: "text", text: "Missing required parameter: `query` must be a non-empty string." }],
          isError: true,
        };
      }
      const searchResult = await searchModels({
        query,
        category: args?.category as string | undefined,
        downloadable: args?.downloadable as boolean | undefined,
        maxResults: args?.maxResults as number | undefined,
      });
      const searchText = formatSearchResults(searchResult);
      return {
        content: withDisclaimer([{ type: "text", text: searchText }]),
        isError: searchResult.ok ? undefined : true,
      };
    }

    // ── generate_3d_model ────────────────────────────────────────────────────
    case "generate_3d_model": {
      const genResult = await generateModel({
        prompt: args?.prompt as string | undefined,
        imageUrl: args?.imageUrl as string | undefined,
        quality: args?.quality as GenerateQuality | undefined,
      });
      const genText = formatGenerateResult(genResult);
      return {
        content: withDisclaimer([{ type: "text", text: genText }]),
        isError: genResult.ok ? undefined : true,
      };
    }

    // ── analyze_project ───────────────────────────────────────────────────────
    case "analyze_project": {
      const rawPath = args?.path;
      const analyzePath = typeof rawPath === "string" && rawPath.length > 0 ? rawPath : undefined;
      try {
        const analysis = await analyzeProject({ path: analyzePath });
        const report = formatAnalysisReport(analysis);
        return { content: withDisclaimer([{ type: "text", text: report }]) };
      } catch (err) {
        return {
          content: [
            {
              type: "text",
              text: `analyze_project failed: ${(err as Error).message}`,
            },
          ],
          isError: true,
        };
      }
    }

    // ── search_android_docs ──────────────────────────────────────────────────
    case "search_android_docs": {
      const query = args?.query as string | undefined;
      if (!query || typeof query !== "string" || query.trim().length === 0) {
        return {
          content: [{ type: "text", text: "Missing required parameter: `query` must be a non-empty string." }],
          isError: true,
        };
      }
      const docsResult = await searchAndroidDocs(query);
      const text = formatAndroidDocsSearch(query, docsResult);
      return {
        content: withDisclaimer([{ type: "text", text }]),
        isError: docsResult.ok ? undefined : true,
      };
    }

    // ── fetch_android_doc ────────────────────────────────────────────────────
    case "fetch_android_doc": {
      const uri = args?.uri as string | undefined;
      if (!uri || typeof uri !== "string" || uri.trim().length === 0) {
        return {
          content: [{ type: "text", text: "Missing required parameter: `uri` must be a non-empty `kb://...` string." }],
          isError: true,
        };
      }
      const docResult = await fetchAndroidDoc(uri);
      const text = formatAndroidDocsFetch(uri, docResult);
      return {
        content: withDisclaimer([{ type: "text", text }]),
        isError: docResult.ok ? undefined : true,
      };
    }

    default:
      return {
        content: [{ type: "text", text: `Unknown tool: ${toolName}` }],
        isError: true,
      };
  }
}
