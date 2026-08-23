/**
 * Pure tool library for this vertical MCP package.
 *
 * Exports `TOOL_DEFINITIONS` and `dispatchTool` so this package can be
 * consumed both by its own stdio entrypoint (`./index.ts`) and by the
 * hosted `mcp-gateway` which multiplexes all verticals behind a single
 * HTTP endpoint. Runtime output is identical to the original monolith.
 */


import {
  generateAnatomyViewer,
  ANATOMY_SYSTEMS,
  ANATOMY_REGIONS,
  type AnatomySystem,
  type AnatomyRegion,
} from "./anatomy-viewer.js";
import {
  generateMoleculeViewer,
  MOLECULE_TYPES,
  MOLECULE_REPRESENTATIONS,
  type MoleculeType,
  type MoleculeRepresentation,
} from "./molecule-viewer.js";
import {
  generateMedicalImaging,
  IMAGING_MODALITIES,
  RENDERING_MODES,
  type ImagingModality,
  type RenderingMode,
} from "./medical-imaging.js";
import {
  generateSurgicalPlanning,
  SURGERY_TYPES,
  PLANNING_FEATURES,
  type SurgeryType,
  type PlanningFeature,
} from "./surgical-planning.js";
import {
  generateDentalViewer,
  DENTAL_VIEW_TYPES,
  DENTAL_FEATURES,
  type DentalViewType,
  type DentalFeature,
} from "./dental-viewer.js";
import {
  listMedicalModels,
  formatModelList,
  MEDICAL_MODEL_CATEGORIES,
  type MedicalModelCategory,
} from "./medical-models.js";
import {
  validateMedicalCode,
  formatValidationReport,
} from "./validator.js";

// ─── Legal disclaimer ─────────────────────────────────────────────────────────

const DISCLAIMER =
  "\n\n---\n*Generated code suggestion for medical/healthcare 3D visualization. Not a medical device — review before clinical use. See [LICENSE](https://github.com/sceneview/sceneview/blob/main/mcp-healthcare/LICENSE).*";

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
    name: "get_anatomy_viewer",
    description:
      "Returns a complete, compilable Kotlin composable for a 3D anatomy viewer using SceneView. Supports skeleton, muscular, circulatory, nervous, respiratory, digestive, and other body systems. Choose a body region (head, torso, upper-limb, lower-limb, spine, pelvis, hand, foot, full). Options include transparency for layered views, exploded view for educational purposes, anatomical labels, and AR mode for placing anatomy models in the real world.",
    inputSchema: {
      type: "object",
      properties: {
        system: {
          type: "string",
          enum: ANATOMY_SYSTEMS,
          description: `The body system to display:\n${ANATOMY_SYSTEMS.map((s) => `- "${s}"`).join("\n")}`,
        },
        region: {
          type: "string",
          enum: ANATOMY_REGIONS,
          description: `Body region to focus on (default: "full"):\n${ANATOMY_REGIONS.map((r) => `- "${r}"`).join("\n")}`,
        },
        transparent: {
          type: "boolean",
          description: "Enable transparency slider for layered anatomy viewing (default: false).",
        },
        exploded: {
          type: "boolean",
          description: "Enable exploded view slider to separate anatomical components (default: false).",
        },
        labels: {
          type: "boolean",
          description: "Show anatomical labels overlay (default: true).",
        },
        ar: {
          type: "boolean",
          description: "Generate AR version using ARSceneView instead of SceneView (default: false). Requires arsceneview dependency.",
        },
      },
      required: ["system"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_molecule_viewer",
    description:
      "Returns a complete, compilable Kotlin composable for a 3D molecular structure viewer using SceneView. Supports proteins, DNA, RNA, small molecules, antibodies, viruses, and enzymes. Choose representation (ball-and-stick, space-filling, ribbon, wireframe, surface) and color scheme (element/CPK, chain, secondary-structure, hydrophobicity). Can auto-rotate and supports AR mode. Includes workflow for converting PDB structures to GLB.",
    inputSchema: {
      type: "object",
      properties: {
        moleculeType: {
          type: "string",
          enum: MOLECULE_TYPES,
          description: `The type of molecule:\n${MOLECULE_TYPES.map((t) => `- "${t}"`).join("\n")}`,
        },
        representation: {
          type: "string",
          enum: MOLECULE_REPRESENTATIONS,
          description: `3D representation style (default: "ball-and-stick"):\n${MOLECULE_REPRESENTATIONS.map((r) => `- "${r}"`).join("\n")}`,
        },
        pdbId: {
          type: "string",
          description: 'Optional PDB ID for a specific structure (e.g., "1HHO" for hemoglobin). Model file will be named after the PDB ID.',
        },
        colorScheme: {
          type: "string",
          enum: ["element", "chain", "secondary-structure", "hydrophobicity", "custom"],
          description: 'Color scheme for the molecule (default: "element"/CPK).',
        },
        showHydrogens: {
          type: "boolean",
          description: "Show hydrogen atoms (default: false). Increases vertex count significantly.",
        },
        animate: {
          type: "boolean",
          description: "Enable auto-rotation animation (default: false).",
        },
        ar: {
          type: "boolean",
          description: "Generate AR version (default: false).",
        },
      },
      required: ["moleculeType"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_medical_imaging",
    description:
      "Returns a complete, compilable Kotlin composable for 3D visualization of medical imaging data (CT, MRI, PET, ultrasound, X-ray) using SceneView. Includes the full DICOM-to-3D pipeline documentation: parsing DICOM with dcm4che, generating 3D meshes with marching cubes, and rendering in SceneView. Supports windowing controls (window center/width in HU), segmentation overlays (tumor, vessel highlighting), and AR mode for examining reconstructions in real space.",
    inputSchema: {
      type: "object",
      properties: {
        modality: {
          type: "string",
          enum: IMAGING_MODALITIES,
          description: `Imaging modality:\n${IMAGING_MODALITIES.map((m) => `- "${m}"`).join("\n")}`,
        },
        renderingMode: {
          type: "string",
          enum: RENDERING_MODES,
          description: `3D rendering mode (default: "surface"):\n${RENDERING_MODES.map((r) => `- "${r}"`).join("\n")}`,
        },
        bodyRegion: {
          type: "string",
          description: 'Body region of the scan (e.g., "chest", "head", "abdomen", "pelvis"). Default: "chest".',
        },
        windowing: {
          type: "boolean",
          description: "Include HU windowing controls for CT/MRI (default: true).",
        },
        segmentation: {
          type: "boolean",
          description: "Include segmentation overlay layer (default: false).",
        },
        ar: {
          type: "boolean",
          description: "Generate AR version (default: false).",
        },
      },
      required: ["modality"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_surgical_planning",
    description:
      "Returns a complete, compilable Kotlin composable for surgical planning 3D visualization using SceneView. Supports orthopedic, cardiac, neurosurgery, maxillofacial, spinal, laparoscopic, and ophthalmic surgery types. Features include measurement tools, annotation, cross-section plane, implant placement preview, surgical trajectory planning, and pre-operative comparison. AR mode lets surgeons examine patient anatomy at real scale.",
    inputSchema: {
      type: "object",
      properties: {
        surgeryType: {
          type: "string",
          enum: SURGERY_TYPES,
          description: `Type of surgery:\n${SURGERY_TYPES.map((t) => `- "${t}"`).join("\n")}`,
        },
        features: {
          type: "array",
          items: {
            type: "string",
            enum: PLANNING_FEATURES,
          },
          description: `Planning features to include (default: ["measurement", "annotation"]):\n${PLANNING_FEATURES.map((f) => `- "${f}"`).join("\n")}`,
        },
        implantModel: {
          type: "boolean",
          description: "Include implant/prosthesis overlay with toggle (default: false).",
        },
        preOpComparison: {
          type: "boolean",
          description: "Include pre-operative comparison overlay (default: false).",
        },
        ar: {
          type: "boolean",
          description: "Generate AR version (default: false).",
        },
      },
      required: ["surgeryType"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "get_dental_viewer",
    description:
      "Returns a complete, compilable Kotlin composable for dental 3D scanning visualization using SceneView. View types include full-arch, single-tooth, implant, orthodontic, crown-bridge, intraoral-scan, and CBCT reconstruction. Features include measurement, margin line drawing, occlusion analysis, before/after comparison, shade matching, and orthodontic treatment stage timeline. Supports root and nerve overlays for implant planning. Includes intraoral scanner export workflow (iTero, 3Shape, Medit).",
    inputSchema: {
      type: "object",
      properties: {
        viewType: {
          type: "string",
          enum: DENTAL_VIEW_TYPES,
          description: `Dental view type:\n${DENTAL_VIEW_TYPES.map((t) => `- "${t}"`).join("\n")}`,
        },
        features: {
          type: "array",
          items: {
            type: "string",
            enum: DENTAL_FEATURES,
          },
          description: `Dental features to include (default: ["measurement"]):\n${DENTAL_FEATURES.map((f) => `- "${f}"`).join("\n")}`,
        },
        arch: {
          type: "string",
          enum: ["upper", "lower", "both"],
          description: 'Which dental arch to display (default: "both").',
        },
        showRoots: {
          type: "boolean",
          description: "Include tooth root overlay for implant planning (default: false).",
        },
        showNerves: {
          type: "boolean",
          description: "Include nerve pathway overlay — inferior alveolar, mental nerve (default: false).",
        },
        ar: {
          type: "boolean",
          description: "Generate AR version for patient consultation (default: false).",
        },
      },
      required: ["viewType"],
    },
    annotations: {
      readOnlyHint: true,
      openWorldHint: false,
      destructiveHint: false,
    },
  },
  {
    name: "list_medical_models",
    description:
      "Lists free, openly-licensed 3D medical models suitable for SceneView apps. Sources include BodyParts3D (CC BY-SA), NIH 3D Print Exchange (Public Domain), and Sketchfab (CC BY). Categories: anatomy, skeleton, organ, muscle, dental, molecule, cell, surgical, imaging, prosthetic. Each entry includes source URL, format, license, and conversion notes for SceneView compatibility.",
    inputSchema: {
      type: "object",
      properties: {
        category: {
          type: "string",
          enum: MEDICAL_MODEL_CATEGORIES,
          description: `Filter by category:\n${MEDICAL_MODEL_CATEGORIES.map((c) => `- "${c}"`).join("\n")}`,
        },
        tag: {
          type: "string",
          description: 'Filter by tag (e.g., "heart", "dental", "protein", "skeleton"). Omit to list all.',
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
    name: "validate_medical_code",
    description:
      "Validates a Kotlin SceneView snippet for common medical-app mistakes. Checks threading violations (Filament JNI on background thread), null-safety for model loading, LightNode trailing-lambda bug, deprecated 2.x APIs, DICOM library requirements, STL/OBJ format warnings, and transparency rendering order. Always call this before presenting generated medical SceneView code.",
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
    // ── get_anatomy_viewer ──────────────────────────────────────────────────
    case "get_anatomy_viewer": {
      const args = rawArgs ?? {};
      const system = args.system as AnatomySystem;
      if (!system || !ANATOMY_SYSTEMS.includes(system)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid system "${system}". Valid: ${ANATOMY_SYSTEMS.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generateAnatomyViewer({
        system,
        region: (args.region as AnatomyRegion) ?? "full",
        transparent: args.transparent as boolean ?? false,
        exploded: args.exploded as boolean ?? false,
        labels: args.labels as boolean ?? true,
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} Anatomy Viewer — ${system} (${(args.region as string) ?? "full"})`,
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

    // ── get_molecule_viewer ─────────────────────────────────────────────────
    case "get_molecule_viewer": {
      const args = rawArgs ?? {};
      const moleculeType = args.moleculeType as MoleculeType;
      if (!moleculeType || !MOLECULE_TYPES.includes(moleculeType)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid moleculeType "${moleculeType}". Valid: ${MOLECULE_TYPES.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generateMoleculeViewer({
        moleculeType,
        representation: (args.representation as MoleculeRepresentation) ?? "ball-and-stick",
        pdbId: args.pdbId as string | undefined,
        colorScheme: (args.colorScheme as any) ?? "element",
        showHydrogens: args.showHydrogens as boolean ?? false,
        animate: args.animate as boolean ?? false,
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} Molecule Viewer — ${moleculeType}${args.pdbId ? ` (PDB: ${args.pdbId})` : ""}`,
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

    // ── get_medical_imaging ─────────────────────────────────────────────────
    case "get_medical_imaging": {
      const args = rawArgs ?? {};
      const modality = args.modality as ImagingModality;
      if (!modality || !IMAGING_MODALITIES.includes(modality)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid modality "${modality}". Valid: ${IMAGING_MODALITIES.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generateMedicalImaging({
        modality,
        renderingMode: (args.renderingMode as RenderingMode) ?? "surface",
        bodyRegion: (args.bodyRegion as string) ?? "chest",
        windowing: args.windowing as boolean ?? true,
        segmentation: args.segmentation as boolean ?? false,
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} Medical Imaging — ${modality.toUpperCase()} (${(args.bodyRegion as string) ?? "chest"})`,
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

    // ── get_surgical_planning ───────────────────────────────────────────────
    case "get_surgical_planning": {
      const args = rawArgs ?? {};
      const surgeryType = args.surgeryType as SurgeryType;
      if (!surgeryType || !SURGERY_TYPES.includes(surgeryType)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid surgeryType "${surgeryType}". Valid: ${SURGERY_TYPES.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generateSurgicalPlanning({
        surgeryType,
        features: (args.features as PlanningFeature[]) ?? ["measurement", "annotation"],
        implantModel: args.implantModel as boolean ?? false,
        preOpComparison: args.preOpComparison as boolean ?? false,
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} Surgical Planning — ${surgeryType}`,
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

    // ── get_dental_viewer ───────────────────────────────────────────────────
    case "get_dental_viewer": {
      const args = rawArgs ?? {};
      const viewType = args.viewType as DentalViewType;
      if (!viewType || !DENTAL_VIEW_TYPES.includes(viewType)) {
        return {
          content: [
            {
              type: "text",
              text: `Invalid viewType "${viewType}". Valid: ${DENTAL_VIEW_TYPES.join(", ")}`,
            },
          ],
          isError: true,
        };
      }

      const code = generateDentalViewer({
        viewType,
        features: (args.features as DentalFeature[]) ?? ["measurement"],
        arch: (args.arch as "upper" | "lower" | "both") ?? "both",
        showRoots: args.showRoots as boolean ?? false,
        showNerves: args.showNerves as boolean ?? false,
        ar: args.ar as boolean ?? false,
      });

      const mode = args.ar ? "AR" : "3D";
      return {
        content: withDisclaimer([
          {
            type: "text",
            text: [
              `## ${mode} Dental Viewer — ${viewType} (${(args.arch as string) ?? "both"})`,
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

    // ── list_medical_models ─────────────────────────────────────────────────
    case "list_medical_models": {
      const args = rawArgs ?? {};
      const category = args.category as MedicalModelCategory | undefined;
      const tag = args.tag as string | undefined;

      const models = listMedicalModels(category, tag);
      const text = formatModelList(models);

      return {
        content: withDisclaimer([{ type: "text", text }]),
        structuredContent: { models },
      };
    }

    // ── validate_medical_code ───────────────────────────────────────────────
    case "validate_medical_code": {
      const code = rawArgs?.code as string;
      if (!code) {
        return {
          content: [{ type: "text", text: "No code provided for validation." }],
          isError: true,
        };
      }

      const result = validateMedicalCode(code);
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
            text: `Unknown tool: "${toolName}". Available: get_anatomy_viewer, get_molecule_viewer, get_medical_imaging, get_surgical_planning, get_dental_viewer, list_medical_models, validate_medical_code`,
          },
        ],
        isError: true,
      };
  }
}
