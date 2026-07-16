/**
 * generate_3d_model — Tripo BYOK text/image→GLB generation tool.
 *
 * `search_models` finds assets that already exist; this tool closes the other
 * half of the agentic asset loop: when no existing model fits, the assistant
 * generates a brand-new GLB from a text prompt or a source image using the
 * Tripo AI API (api.tripo3d.ai), then loads it with
 * `rememberModelInstance(modelLoader, ...)` and places it in AR.
 *
 * The tool is BYOK — users bring their own `TRIPO_API_KEY` (create one at
 * platform.tripo3d.ai/api-keys). We never ship or proxy a key, so there is no
 * server-side key custody, no cost to us, and no rate-limit sharing across
 * users — exactly the `SKETCHFAB_API_KEY` pattern used by `search_models`.
 *
 * Quality tiers (July 2026 Tripo model catalog):
 *   - "fast" (default) → P1 low-poly (`P1-20260311`) — AR-ready meshes,
 *     ~25–30 s, roughly $0.10–0.25 of Tripo credits per generation.
 *   - "hd" → H3.1 (`v3.1-20260211`) with quad topology + detailed geometry
 *     and textures — up to ~100 s, roughly $0.41 of Tripo credits.
 *
 * API contract (grounded against docs.tripo3d.ai, July 2026):
 *   1. POST https://api.tripo3d.ai/v2/openapi/task
 *      Authorization: Bearer <key>
 *      { "type": "text_to_model", "prompt": "...", "model_version": "..." }
 *      or
 *      { "type": "image_to_model", "file": { "type": "jpg|png|...", "url": "..." }, ... }
 *      → { "code": 0, "data": { "task_id": "..." } }
 *   2. GET https://api.tripo3d.ai/v2/openapi/task/{task_id} — poll until the
 *      status is finalized. Status enum: queued | running (ongoing) and
 *      success | failed | banned | expired | cancelled | unknown (finalized).
 *      On success, `data.output` carries the model URLs (`pbr_model`,
 *      `model`, `base_model`) plus `rendered_image`. Download URLs expire
 *      after ~5 minutes — the caller must download the GLB immediately.
 *
 * All network errors, missing keys, task failures, and poll timeouts are
 * translated to a structured `GenerateModelError` so the MCP handler can
 * render a clear message without crashing the server.
 */

// ─── Configuration ──────────────────────────────────────────────────────────

const TRIPO_TASK_ENDPOINT = "https://api.tripo3d.ai/v2/openapi/task";
const API_KEYS_URL = "https://platform.tripo3d.ai/api-keys";
const MAX_PROMPT_LENGTH = 1024; // Tripo's documented prompt limit.

/** How often to re-poll the task while it is queued/running. */
const DEFAULT_POLL_INTERVAL_MS = 2_000;

/**
 * Bounded polling caps. "fast" (P1) typically finishes in ~25–30 s; "hd"
 * (H3.1) can take ~100 s, so it gets a 4-minute ceiling. Beyond the cap the
 * tool returns a `timeout` error instead of hanging the MCP call forever.
 */
const QUALITY_TIERS = {
  fast: {
    modelVersion: "P1-20260311",
    label: "fast (Tripo P1 — low-poly, AR-ready)",
    timeoutMs: 120_000,
  },
  hd: {
    modelVersion: "v3.1-20260211",
    label: "hd (Tripo H3.1 — quad topology, detailed geometry & textures)",
    timeoutMs: 240_000,
  },
} as const;

const LICENSE_NOTE =
  'Generated with your own Tripo API key — usage rights follow your Tripo plan\'s terms (https://www.tripo3d.ai/api). No third-party author attribution is required, but crediting "Made with Tripo AI" is appreciated.';

const ATTRIBUTION = "Tripo AI (https://www.tripo3d.ai)";

// ─── Public types ───────────────────────────────────────────────────────────

export type GenerateQuality = keyof typeof QUALITY_TIERS;

export interface GenerateModelOptions {
  /** Text prompt for text→3D. Exactly one of `prompt` / `imageUrl` is required. */
  prompt?: string;
  /** Public HTTPS URL of a source image (JPEG/PNG, max 20 MB) for image→3D. */
  imageUrl?: string;
  /**
   * Quality tier. `"fast"` (default) = Tripo P1 low-poly — cheap and
   * AR-ready. `"hd"` = Tripo H3.1 quad topology with detailed geometry and
   * textures — slower and pricier.
   */
  quality?: GenerateQuality;
  /** Test/tuning hook — poll cadence. Not exposed in the MCP schema. */
  pollIntervalMs?: number;
  /** Test/tuning hook — overall deadline. Not exposed in the MCP schema. */
  timeoutMs?: number;
}

export interface GeneratedModel {
  taskId: string;
  /** Direct GLB download URL. Expires ~5 minutes after generation. */
  modelUrl: string;
  /** Rendered preview image URL, when Tripo provides one. */
  previewImageUrl: string;
  quality: GenerateQuality;
  modelVersion: string;
  mode: "text" | "image";
  /** Echo of the prompt or image URL that produced the model. */
  input: string;
  /** Tripo credits consumed, when reported by the API. */
  creditsConsumed: number | null;
  license: string;
  attribution: string;
}

export interface GenerateModelSuccess {
  ok: true;
  model: GeneratedModel;
}

export type GenerateErrorCode =
  | "missing_key"
  | "unauthorized"
  | "rate_limited"
  | "network"
  | "bad_response"
  | "invalid_input"
  | "task_failed"
  | "timeout";

export interface GenerateModelError {
  ok: false;
  code: GenerateErrorCode;
  message: string;
}

export type GenerateModelResult = GenerateModelSuccess | GenerateModelError;

// ─── Tripo API response shapes (only the fields we actually read) ───────────

interface TripoEnvelope<T> {
  code?: number;
  message?: string;
  suggestion?: string;
  data?: T;
}

interface TripoSubmitData {
  task_id?: string;
}

interface TripoTaskOutput {
  model?: string;
  base_model?: string;
  pbr_model?: string;
  rendered_image?: string;
}

interface TripoTaskData {
  task_id?: string;
  status?: string;
  progress?: number;
  output?: TripoTaskOutput;
  consumed_credit?: number;
}

/** Finalized-but-not-successful Tripo task statuses. */
const FAILED_STATUSES = new Set(["failed", "banned", "expired", "cancelled", "unknown"]);

// ─── Helpers ────────────────────────────────────────────────────────────────

function missingKeyError(): GenerateModelError {
  return {
    ok: false,
    code: "missing_key",
    message: [
      "generate_3d_model needs a Tripo API key (BYOK — generations are billed to YOUR Tripo account, nothing is charged by SceneView).",
      "",
      `1. Create an API key at ${API_KEYS_URL} (new accounts get free trial credits)`,
      "2. Set the TRIPO_API_KEY environment variable in your MCP client config:",
      "",
      "   Claude Desktop / Cursor / Windsurf:",
      "   {",
      '     "mcpServers": {',
      '       "sceneview": {',
      '         "command": "npx",',
      '         "args": ["-y", "sceneview-mcp"],',
      '         "env": { "TRIPO_API_KEY": "YOUR_KEY_HERE" }',
      "       }",
      "     }",
      "   }",
    ].join("\n"),
  };
}

/**
 * Derive the Tripo `file.type` hint from the image URL extension. Tripo
 * documents the field as advisory ("currently not validated") — JPEG and PNG
 * are the officially supported input formats.
 */
function imageTypeFromUrl(url: string): string {
  const path = url.split(/[?#]/)[0]?.toLowerCase() ?? "";
  if (path.endsWith(".png")) return "png";
  if (path.endsWith(".webp")) return "webp";
  if (path.endsWith(".jpeg")) return "jpeg";
  return "jpg";
}

function isHttpUrl(value: string): boolean {
  return /^https?:\/\//i.test(value.trim());
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Build the task-submission body for the requested mode + quality tier.
 * Exported for tests only.
 */
export function buildTaskBody(options: {
  prompt?: string;
  imageUrl?: string;
  quality: GenerateQuality;
}): Record<string, unknown> {
  const tier = QUALITY_TIERS[options.quality];
  const body: Record<string, unknown> = {
    model_version: tier.modelVersion,
    texture: true,
    pbr: true,
  };
  if (options.quality === "hd") {
    // H3.1 add-ons: quad-mesh topology + detailed geometry & textures.
    body.quad = true;
    body.geometry_quality = "detailed";
    body.texture_quality = "detailed";
  }
  if (options.prompt !== undefined) {
    body.type = "text_to_model";
    body.prompt = options.prompt;
  } else {
    body.type = "image_to_model";
    body.file = {
      type: imageTypeFromUrl(options.imageUrl ?? ""),
      url: options.imageUrl,
    };
  }
  return body;
}

/** Map a non-OK Tripo HTTP response to a structured error. */
function httpError(status: number, statusText: string): GenerateModelError {
  if (status === 401 || status === 403) {
    return {
      ok: false,
      code: "unauthorized",
      message: [
        `Tripo rejected the API key (HTTP ${status}).`,
        `Double-check the key at ${API_KEYS_URL}, or create a new one — keys look like "tsk_...".`,
      ].join(" "),
    };
  }
  if (status === 429) {
    return {
      ok: false,
      code: "rate_limited",
      message:
        "Tripo rate limit reached (HTTP 429). Wait a minute and retry, or check your plan's concurrency limits at https://platform.tripo3d.ai.",
    };
  }
  return {
    ok: false,
    code: "bad_response",
    message: `Tripo returned HTTP ${status} ${statusText || ""}`.trim(),
  };
}

// ─── Public API ─────────────────────────────────────────────────────────────

/**
 * Generate a 3D model (GLB) from a text prompt or a source image via the
 * Tripo API: submit a task, then poll until it finalizes or the bounded
 * deadline expires.
 *
 * Reads `TRIPO_API_KEY` from the environment. All error paths return a
 * `GenerateModelError` rather than throwing, so the MCP dispatcher can render
 * a friendly message without wrapping the call in a try/catch.
 */
export async function generateModel(options: GenerateModelOptions): Promise<GenerateModelResult> {
  // ── Input validation (no network before this passes) ──────────────────────
  const prompt = typeof options?.prompt === "string" ? options.prompt.trim() : undefined;
  const imageUrl = typeof options?.imageUrl === "string" ? options.imageUrl.trim() : undefined;
  const hasPrompt = prompt !== undefined && prompt.length > 0;
  const hasImage = imageUrl !== undefined && imageUrl.length > 0;

  if (!hasPrompt && !hasImage) {
    return {
      ok: false,
      code: "invalid_input",
      message:
        "Provide exactly one of `prompt` (text→3D) or `imageUrl` (image→3D). Both are currently empty.",
    };
  }
  if (hasPrompt && hasImage) {
    return {
      ok: false,
      code: "invalid_input",
      message:
        "Provide exactly one of `prompt` or `imageUrl`, not both. Use `prompt` for text→3D or `imageUrl` for image→3D.",
    };
  }
  if (hasPrompt && prompt.length > MAX_PROMPT_LENGTH) {
    return {
      ok: false,
      code: "invalid_input",
      message: `\`prompt\` is too long (${prompt.length} chars). Tripo accepts at most ${MAX_PROMPT_LENGTH} characters.`,
    };
  }
  if (hasImage && !isHttpUrl(imageUrl)) {
    return {
      ok: false,
      code: "invalid_input",
      message: "`imageUrl` must be a public http(s) URL to a JPEG or PNG image (max 20 MB).",
    };
  }

  const quality: GenerateQuality = options.quality === "hd" ? "hd" : "fast"; // default + unknown values → fast
  const tier = QUALITY_TIERS[quality];

  const apiKey = process.env.TRIPO_API_KEY;
  if (!apiKey || apiKey.trim().length === 0) {
    return missingKeyError();
  }

  const headers = {
    Authorization: `Bearer ${apiKey}`,
    "Content-Type": "application/json",
    Accept: "application/json",
  };

  // ── 1. Submit the generation task ──────────────────────────────────────────
  const body = buildTaskBody({
    prompt: hasPrompt ? prompt : undefined,
    imageUrl: hasImage ? imageUrl : undefined,
    quality,
  });

  let submitResponse: Response;
  try {
    submitResponse = await fetch(TRIPO_TASK_ENDPOINT, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
    });
  } catch (err) {
    const cause = err instanceof Error ? err.message : String(err);
    return {
      ok: false,
      code: "network",
      message: `Could not reach Tripo (${cause}). Check your internet connection and try again.`,
    };
  }

  if (!submitResponse.ok) {
    return httpError(submitResponse.status, submitResponse.statusText);
  }

  let submitPayload: TripoEnvelope<TripoSubmitData>;
  try {
    submitPayload = (await submitResponse.json()) as TripoEnvelope<TripoSubmitData>;
  } catch (err) {
    const cause = err instanceof Error ? err.message : String(err);
    return {
      ok: false,
      code: "bad_response",
      message: `Tripo returned invalid JSON: ${cause}`,
    };
  }

  if (typeof submitPayload.code === "number" && submitPayload.code !== 0) {
    const detail = [submitPayload.message, submitPayload.suggestion].filter(Boolean).join(" — ");
    return {
      ok: false,
      code: "bad_response",
      message: `Tripo rejected the task (code ${submitPayload.code})${detail ? `: ${detail}` : "."}`,
    };
  }

  const taskId = submitPayload.data?.task_id;
  if (!taskId || typeof taskId !== "string") {
    return {
      ok: false,
      code: "bad_response",
      message: "Tripo accepted the request but returned no task_id.",
    };
  }

  // ── 2. Poll until the task finalizes (bounded) ─────────────────────────────
  const pollIntervalMs = options.pollIntervalMs ?? DEFAULT_POLL_INTERVAL_MS;
  const timeoutMs = options.timeoutMs ?? tier.timeoutMs;
  const deadline = Date.now() + timeoutMs;
  let lastStatus = "queued";
  let lastProgress = 0;

  for (;;) {
    let pollResponse: Response;
    try {
      pollResponse = await fetch(`${TRIPO_TASK_ENDPOINT}/${taskId}`, { headers });
    } catch (err) {
      const cause = err instanceof Error ? err.message : String(err);
      return {
        ok: false,
        code: "network",
        message: `Lost connection to Tripo while polling task ${taskId} (${cause}). The generation may still complete — retry later or check https://platform.tripo3d.ai.`,
      };
    }

    if (pollResponse.status === 401 || pollResponse.status === 403) {
      return httpError(pollResponse.status, pollResponse.statusText);
    }

    // Transient poll hiccups (429 burst, 5xx, malformed JSON) are tolerated:
    // keep polling until the bounded deadline instead of failing the task.
    if (pollResponse.ok) {
      let pollPayload: TripoEnvelope<TripoTaskData> | undefined;
      try {
        pollPayload = (await pollResponse.json()) as TripoEnvelope<TripoTaskData>;
      } catch {
        pollPayload = undefined;
      }

      const task = pollPayload?.data;
      const status = task?.status;
      if (typeof status === "string") {
        lastStatus = status;
        if (typeof task?.progress === "number") lastProgress = task.progress;

        if (status === "success") {
          const output = task?.output ?? {};
          const modelUrl = output.pbr_model || output.model || output.base_model || "";
          if (!modelUrl) {
            return {
              ok: false,
              code: "bad_response",
              message: `Tripo task ${taskId} succeeded but returned no model URL.`,
            };
          }
          return {
            ok: true,
            model: {
              taskId,
              modelUrl,
              previewImageUrl: output.rendered_image ?? "",
              quality,
              modelVersion: tier.modelVersion,
              mode: hasPrompt ? "text" : "image",
              input: hasPrompt ? prompt : (imageUrl as string),
              creditsConsumed:
                typeof task?.consumed_credit === "number" ? task.consumed_credit : null,
              license: LICENSE_NOTE,
              attribution: ATTRIBUTION,
            },
          };
        }

        if (FAILED_STATUSES.has(status)) {
          return {
            ok: false,
            code: "task_failed",
            message: [
              `Tripo task ${taskId} finalized with status "${status}".`,
              status === "failed"
                ? "Try rephrasing the prompt (or a clearer source image) and generate again."
                : "Check the task on https://platform.tripo3d.ai for details.",
            ].join(" "),
          };
        }
        // queued / running → keep polling.
      }
    }

    if (Date.now() >= deadline) {
      return {
        ok: false,
        code: "timeout",
        message: [
          `Tripo task ${taskId} did not finish within ${Math.round(timeoutMs / 1000)}s`,
          `(last status: "${lastStatus}", progress ${lastProgress}%).`,
          "The generation may still complete on Tripo's side — credits may be consumed.",
          "Retry, or check the task on https://platform.tripo3d.ai.",
        ].join(" "),
      };
    }

    await sleep(pollIntervalMs);
  }
}

/**
 * Render a `GenerateModelResult` as the markdown text block the MCP
 * dispatcher returns to the client. Kept here (not in handler.ts) so unit
 * tests can verify formatting without touching the dispatch layer.
 */
export function formatGenerateResult(result: GenerateModelResult): string {
  if (!result.ok) {
    return result.message;
  }

  const m = result.model;
  const sourceLabel = m.mode === "text" ? "Prompt" : "Source image";
  const lines: string[] = [
    `## Generated 3D model (${QUALITY_TIERS[m.quality].label})`,
    "",
    `- **GLB download:** ${m.modelUrl}`,
    `- **⚠️ URL expiry:** the download link expires ~5 minutes after generation — download the file NOW and self-host it (e.g. copy it into your app's \`assets/models/\`).`,
    ...(m.previewImageUrl ? [`- **Preview:** ${m.previewImageUrl}`] : []),
    `- **${sourceLabel}:** ${m.input}`,
    `- **Model version:** ${m.modelVersion}`,
    `- **Task ID:** \`${m.taskId}\``,
    ...(m.creditsConsumed !== null ? [`- **Tripo credits consumed:** ${m.creditsConsumed}`] : []),
    `- **License:** ${m.license}`,
    `- **Generator:** ${m.attribution}`,
    "",
    "### Load it in SceneView",
    "",
    "```kotlin",
    "// After downloading the GLB into your app's assets:",
    'val model = rememberModelInstance(modelLoader, "models/generated.glb")',
    "```",
    "",
    'Place it in AR with `AnchorNode` + `ModelNode` — see the llms.txt recipe "Generate a 3D model with AI (Tripo) and place it in AR".',
  ];
  return lines.join("\n");
}
