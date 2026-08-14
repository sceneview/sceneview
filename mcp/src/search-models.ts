/**
 * search_models — Sketchfab BYOK search tool.
 *
 * Finding real, free-to-use 3D models is the single biggest pain point when
 * an AI assistant tries to write SceneView code: generated snippets constantly
 * reference `"models/robot.glb"` that doesn't exist. This tool closes the gap
 * by querying Sketchfab's public search API and returning a small list of
 * results the assistant can paste into `ModelNode.load(...)`, embed as a live
 * preview, or show as a thumbnail.
 *
 * The tool is BYOK — users bring their own `SKETCHFAB_API_KEY` (free account,
 * takes ~30 seconds at sketchfab.com/register). We never ship or proxy a key,
 * so there's no cost to us and no rate-limit sharing across users.
 *
 * All network errors, missing keys, and Sketchfab API errors are translated
 * to a structured `SearchError` so the MCP handler can render a clear message
 * without crashing the server.
 */

// ─── Configuration ──────────────────────────────────────────────────────────

const SKETCHFAB_SEARCH_ENDPOINT = "https://api.sketchfab.com/v3/search";
const DEFAULT_MAX_RESULTS = 6;
const MIN_MAX_RESULTS = 1;
const MAX_MAX_RESULTS = 10;
const SIGNUP_URL = "https://sketchfab.com/register";

// ─── Public types ───────────────────────────────────────────────────────────

export interface SearchModelsOptions {
  /** Free-text search query. Required. */
  query: string;
  /** Optional Sketchfab category slug (e.g. `"cars-vehicles"`, `"animals-pets"`). */
  category?: string;
  /**
   * Restrict results to downloadable models. Defaults to `true` so generated
   * code can actually load the asset; set to `false` to widen the search.
   */
  downloadable?: boolean;
  /** Cap the number of returned results. Clamped to [1, 10]. Default: 6. */
  maxResults?: number;
}

export interface ModelResult {
  uid: string;
  name: string;
  author: string;
  viewerUrl: string;
  thumbnailUrl: string;
  license: string;
  downloadable: boolean;
  triangleCount: number;
  embedUrl: string;
}

export interface SearchModelsSuccess {
  ok: true;
  results: ModelResult[];
  query: string;
  totalReturned: number;
}

export type SearchErrorCode =
  | "missing_key"
  | "unauthorized"
  | "rate_limited"
  | "network"
  | "bad_response"
  | "invalid_input";

export interface SearchModelsError {
  ok: false;
  code: SearchErrorCode;
  message: string;
}

export type SearchModelsResult = SearchModelsSuccess | SearchModelsError;

// ─── Sketchfab API response shapes (only the fields we actually read) ──────

interface SketchfabUser {
  username?: string;
  displayName?: string;
}

interface SketchfabThumbnailImage {
  url?: string;
  width?: number;
  height?: number;
}

interface SketchfabThumbnails {
  images?: SketchfabThumbnailImage[];
}

interface SketchfabLicense {
  label?: string;
  slug?: string;
}

interface SketchfabModel {
  uid?: string;
  name?: string;
  viewerUrl?: string;
  embedUrl?: string;
  isDownloadable?: boolean;
  faceCount?: number;
  vertexCount?: number;
  user?: SketchfabUser;
  thumbnails?: SketchfabThumbnails;
  license?: SketchfabLicense;
}

interface SketchfabSearchResponse {
  results?: SketchfabModel[];
}

// ─── Helpers ────────────────────────────────────────────────────────────────

function clampMaxResults(value: number | undefined): number {
  if (typeof value !== "number" || Number.isNaN(value)) return DEFAULT_MAX_RESULTS;
  const floored = Math.floor(value);
  if (floored < MIN_MAX_RESULTS) return MIN_MAX_RESULTS;
  if (floored > MAX_MAX_RESULTS) return MAX_MAX_RESULTS;
  return floored;
}

/** Pick the largest available thumbnail so Claude-rendered cards look crisp. */
function pickThumbnail(model: SketchfabModel): string {
  const images = model.thumbnails?.images ?? [];
  if (images.length === 0) return "";
  const sorted = [...images].sort(
    (a, b) => (b.width ?? 0) * (b.height ?? 0) - (a.width ?? 0) * (a.height ?? 0)
  );
  return sorted[0]?.url ?? "";
}

function normalizeModel(model: SketchfabModel): ModelResult {
  return {
    uid: model.uid ?? "",
    name: model.name ?? "Untitled",
    author: model.user?.displayName ?? model.user?.username ?? "Unknown",
    viewerUrl: model.viewerUrl ?? "",
    thumbnailUrl: pickThumbnail(model),
    license: model.license?.label ?? model.license?.slug ?? "Unknown",
    downloadable: Boolean(model.isDownloadable),
    triangleCount: typeof model.faceCount === "number" ? model.faceCount : 0,
    embedUrl: model.embedUrl ?? "",
  };
}

function buildSearchUrl(options: {
  query: string;
  category?: string;
  downloadable: boolean;
  count: number;
}): string {
  const params = new URLSearchParams();
  params.set("type", "models");
  params.set("q", options.query);
  params.set("count", String(options.count));
  params.set("sort_by", "-likeCount");
  if (options.downloadable) {
    params.set("downloadable", "true");
  }
  if (options.category && options.category.trim().length > 0) {
    params.set("categories", options.category.trim());
  }
  return `${SKETCHFAB_SEARCH_ENDPOINT}?${params.toString()}`;
}

function missingKeyError(): SearchModelsError {
  return {
    ok: false,
    code: "missing_key",
    message: [
      "search_models needs a free Sketchfab API key (BYOK — nothing is charged by SceneView).",
      "",
      `1. Create a free account at ${SIGNUP_URL}`,
      "2. Open https://sketchfab.com/settings/password and copy your API token",
      "3. Set the SKETCHFAB_API_KEY environment variable in your MCP client config:",
      "",
      "   Claude Desktop / Cursor / Windsurf:",
      "   {",
      '     "mcpServers": {',
      '       "sceneview": {',
      '         "command": "npx",',
      '         "args": ["-y", "sceneview-mcp"],',
      '         "env": { "SKETCHFAB_API_KEY": "YOUR_TOKEN_HERE" }',
      "       }",
      "     }",
      "   }",
    ].join("\n"),
  };
}

// ─── Public API ─────────────────────────────────────────────────────────────

/**
 * Search Sketchfab for 3D models matching `query`.
 *
 * Reads `SKETCHFAB_API_KEY` from the environment. All error paths return a
 * `SearchModelsError` rather than throwing, so the MCP dispatcher can render
 * a friendly message without wrapping the call in a try/catch.
 */
export async function searchModels(options: SearchModelsOptions): Promise<SearchModelsResult> {
  if (!options || typeof options.query !== "string" || options.query.trim().length === 0) {
    return {
      ok: false,
      code: "invalid_input",
      message: "Missing required parameter: `query` must be a non-empty string.",
    };
  }

  const apiKey = process.env.SKETCHFAB_API_KEY;
  if (!apiKey || apiKey.trim().length === 0) {
    return missingKeyError();
  }

  const count = clampMaxResults(options.maxResults);
  const downloadable = options.downloadable !== false; // default: true
  const url = buildSearchUrl({
    query: options.query.trim(),
    category: options.category,
    downloadable,
    count,
  });

  let response: Response;
  try {
    response = await fetch(url, {
      headers: {
        Authorization: `Token ${apiKey}`,
        Accept: "application/json",
      },
    });
  } catch (err) {
    const cause = err instanceof Error ? err.message : String(err);
    return {
      ok: false,
      code: "network",
      message: `Could not reach Sketchfab (${cause}). Check your internet connection and try again.`,
    };
  }

  if (response.status === 401 || response.status === 403) {
    return {
      ok: false,
      code: "unauthorized",
      message: [
        "Sketchfab rejected the API key (HTTP " + response.status + ").",
        `Double-check the token at https://sketchfab.com/settings/password, or create a free account at ${SIGNUP_URL}.`,
      ].join(" "),
    };
  }

  if (response.status === 429) {
    return {
      ok: false,
      code: "rate_limited",
      message:
        "Sketchfab rate limit reached (HTTP 429). The free tier allows a few hundred requests per hour — wait a minute and retry, or upgrade your Sketchfab plan.",
    };
  }

  if (!response.ok) {
    return {
      ok: false,
      code: "bad_response",
      message: `Sketchfab returned HTTP ${response.status} ${response.statusText || ""}`.trim(),
    };
  }

  let payload: SketchfabSearchResponse;
  try {
    payload = (await response.json()) as SketchfabSearchResponse;
  } catch (err) {
    const cause = err instanceof Error ? err.message : String(err);
    return {
      ok: false,
      code: "bad_response",
      message: `Sketchfab returned invalid JSON: ${cause}`,
    };
  }

  const rawResults = Array.isArray(payload.results) ? payload.results : [];
  const results = rawResults.slice(0, count).map(normalizeModel);

  return {
    ok: true,
    results,
    query: options.query.trim(),
    totalReturned: results.length,
  };
}

/**
 * Render a `SearchModelsResult` as the markdown text block the MCP dispatcher
 * returns to the client. Kept here (not in handler.ts) so unit tests can
 * verify formatting without touching the dispatch layer.
 */
export function formatSearchResults(result: SearchModelsResult): string {
  if (!result.ok) {
    return result.message;
  }

  if (result.results.length === 0) {
    return [
      `## No Sketchfab models found for "${result.query}"`,
      "",
      "Try a broader query, drop the `category` filter, or set `downloadable: false` to include view-only models.",
    ].join("\n");
  }

  const lines: string[] = [
    `## Sketchfab results for "${result.query}" (${result.totalReturned})`,
    "",
  ];

  for (const model of result.results) {
    const triangles =
      model.triangleCount > 0
        ? `${model.triangleCount.toLocaleString()} triangles`
        : "triangle count unknown";
    const downloadable = model.downloadable ? "downloadable" : "view-only";

    lines.push(
      `### ${model.name}`,
      `- **Author:** ${model.author}`,
      `- **License:** ${model.license}`,
      `- **Geometry:** ${triangles} (${downloadable})`,
      `- **Viewer:** ${model.viewerUrl}`,
      `- **Embed URL:** ${model.embedUrl}`,
      `- **Thumbnail:** ${model.thumbnailUrl}`,
      `- **UID:** \`${model.uid}\``,
      ""
    );
  }

  lines.push(
    "---",
    'Tip: use the viewer URL to verify the model, then download a GLB from Sketchfab and load it with `rememberModelInstance(modelLoader, "models/your-file.glb")`.'
  );
  return lines.join("\n");
}
