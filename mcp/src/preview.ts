// ─── 3D Preview Link Generator ───────────────────────────────────────────────
//
// Generates embeddable preview URLs for sceneview.github.io/preview.
// Two modes:
//   1. Model URL → direct model-viewer embed link
//   2. Code snippet → embed link with default model + code panel

const PREVIEW_BASE = "https://sceneview.github.io/preview";

const DEFAULT_MODEL_URL =
  "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/DamagedHelmet/glTF-Binary/DamagedHelmet.glb";

export interface PreviewOptions {
  /** Public HTTPS URL to a .glb or .gltf model */
  modelUrl?: string;
  /** SceneView code snippet (Kotlin/Swift) to display alongside the preview */
  codeSnippet?: string;
  /** Auto-rotate the model (default: true) */
  autoRotate?: boolean;
  /** Enable AR mode on supported devices (default: true) */
  ar?: boolean;
  /** Title shown above the preview */
  title?: string;
}

export interface PreviewResult {
  previewUrl: string;
  modelUrl: string;
  hasCode: boolean;
  title: string;
}

/**
 * Build a sceneview.github.io/preview URL from the given options.
 *
 * - If `modelUrl` is supplied it is embedded directly.
 * - If only `codeSnippet` is supplied a default model is used and the code is
 *   passed via the `code` query parameter so the preview page can show it in a
 *   companion panel.
 * - If both are supplied the model URL takes priority and the code is included.
 */
export function buildPreviewUrl(options: PreviewOptions): PreviewResult {
  const model = options.modelUrl || DEFAULT_MODEL_URL;
  const hasCode = Boolean(options.codeSnippet?.trim());
  const title =
    options.title || (hasCode && !options.modelUrl ? "SceneView Code Preview" : "3D Model Preview");

  const params = new URLSearchParams();
  params.set("model", model);

  if (options.autoRotate === false) params.set("rotate", "false");
  if (options.ar === false) params.set("ar", "false");
  if (options.title) params.set("title", options.title);
  if (hasCode) params.set("code", options.codeSnippet!.trim());

  const previewUrl = `${PREVIEW_BASE}?${params.toString()}`;

  return { previewUrl, modelUrl: model, hasCode, title };
}

/**
 * Validate preview input — at least one of modelUrl or codeSnippet must be provided.
 */
export function validatePreviewInput(modelUrl?: string, codeSnippet?: string): string | null {
  if (!modelUrl && !codeSnippet) {
    return "At least one of `modelUrl` or `codeSnippet` is required.";
  }

  if (modelUrl) {
    if (!modelUrl.startsWith("https://") && !modelUrl.startsWith("http://")) {
      return "modelUrl must be an HTTP(S) URL.";
    }
    if (!/\.(glb|gltf)(\?|$)/i.test(modelUrl)) {
      return "modelUrl should point to a .glb or .gltf file.";
    }
  }

  return null;
}

/**
 * Format the preview result as a rich text response for the MCP tool.
 */
export function formatPreviewResponse(result: PreviewResult): string {
  const lines: string[] = [
    `## ${result.title}`,
    ``,
    `**[Click to view the 3D model interactively \u2192](${result.previewUrl})**`,
    ``,
    `The link opens an interactive 3D viewer where you can:`,
    `- Drag to orbit, scroll to zoom`,
    `- "View in AR" on mobile devices (ARCore/ARKit)`,
    `- Share the link with anyone`,
    ``,
    `**Preview URL:** ${result.previewUrl}`,
    ``,
    `**Model:** ${result.modelUrl}`,
  ];

  if (result.hasCode) {
    lines.push(``);
    lines.push(`*The code snippet is included in the preview page as a companion panel.*`);
  }

  lines.push(``);
  lines.push(`---`);
  lines.push(`*Powered by SceneView \u2014 3D & AR for every platform*`);

  return lines.join("\n");
}
