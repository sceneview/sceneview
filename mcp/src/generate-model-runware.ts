/**
 * TRELLIS.2 on Runware — the low-cost provider behind `generate_3d_model`.
 *
 * TRELLIS.2 (Microsoft, MIT-licensed weights) is image→3D only, so text→3D
 * runs in two Runware tasks:
 *   1. `imageInference` with FLUX.1 [schnell] (`runware:100@1`) renders a
 *      single, centred object on a plain background from the prompt;
 *   2. `3dInference` with `microsoft:trellis-2@4b` turns that image (or the
 *      caller's own image) into a textured GLB.
 * Runware bills by compute time — a TRELLIS.2 run costs from ~$0.008 (512³)
 * to a few US cents (Runware model page, November 2026). The reported `cost`
 * is surfaced to the user.
 *
 * API contract (runware.ai/docs/models-api, November 2026):
 *   POST https://api.runware.ai/v1, `Authorization: Bearer <key>`, body = an
 *   array of task objects. Responses are `{ data: [...] }` or
 *   `{ errors: [...] }`. An async task is polled with
 *   `{ taskType: "getResponse", taskUUID }`, whose rows carry
 *   `status: processing | success | error`.
 *
 * BYOK like the Tripo path: users bring their own `RUNWARE_API_KEY`
 * (my.runware.ai/keys). SceneView never ships, proxies or pays for a key.
 */

import { randomUUID } from "node:crypto";
import type { GenerateModelError, GenerateModelResult, GenerateQuality } from "./generate-model.js";

export const RUNWARE_ENDPOINT = "https://api.runware.ai/v1";
export const RUNWARE_KEYS_URL = "https://my.runware.ai/keys";
export const TRELLIS_MODEL = "microsoft:trellis-2@4b";
export const CONCEPT_IMAGE_MODEL = "runware:100@1"; // FLUX.1 [schnell]

/** Prompt suffix that gives TRELLIS.2 a clean, single-object input image. */
const CONCEPT_SUFFIX =
  "single object, centered, full view, plain white background, soft studio lighting, no text";

export const TRELLIS_TIERS = {
  fast: { resolution: 1024, label: "fast (TRELLIS.2 1024³ on Runware)", timeoutMs: 180_000 },
  hd: { resolution: 1536, label: "hd (TRELLIS.2 1536³ on Runware)", timeoutMs: 300_000 },
} as const;

export const TRELLIS_LICENSE =
  "Generated with your own Runware API key — TRELLIS.2 weights are MIT-licensed (Microsoft) and usage follows Runware's terms (https://runware.ai/terms). No author attribution is required.";

export const TRELLIS_ATTRIBUTION = "TRELLIS.2 (Microsoft) via Runware (https://runware.ai)";

const DEFAULT_POLL_INTERVAL_MS = 2_000;

interface RunwareRow {
  taskType?: string;
  taskUUID?: string;
  status?: string;
  progress?: number;
  imageURL?: string;
  outputs?: { files?: Array<{ uuid?: string; url?: string }> };
  cost?: number;
}

interface RunwareError {
  code?: string;
  message?: string;
  taskUUID?: string;
}

interface RunwareEnvelope {
  data?: RunwareRow[];
  errors?: RunwareError[];
  error?: RunwareError | string;
}

type Post = { ok: true; body: RunwareEnvelope } | { ok: false; error: GenerateModelError };

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function fail(code: GenerateModelError["code"], message: string): GenerateModelError {
  return { ok: false, code, message };
}

/** Build the TRELLIS.2 `3dInference` task. Exported for tests. */
export function buildTrellisTask(
  taskUUID: string,
  imageUrl: string,
  quality: GenerateQuality
): Record<string, unknown> {
  return {
    taskType: "3dInference",
    taskUUID,
    model: TRELLIS_MODEL,
    deliveryMethod: "async",
    includeCost: true,
    outputFormat: "GLB",
    settings: { resolution: TRELLIS_TIERS[quality].resolution, textureSize: 2048, remesh: true },
    inputs: { image: imageUrl },
  };
}

/** Build the FLUX.1 [schnell] concept-image task for text→3D. Exported for tests. */
export function buildConceptTask(taskUUID: string, prompt: string): Record<string, unknown> {
  return {
    taskType: "imageInference",
    taskUUID,
    model: CONCEPT_IMAGE_MODEL,
    positivePrompt: `${prompt}, ${CONCEPT_SUFFIX}`,
    width: 1024,
    height: 1024,
    numberResults: 1,
    includeCost: true,
  };
}

function errorText(envelope: RunwareEnvelope): string | null {
  const first = envelope.errors?.[0];
  if (first) return [first.code, first.message].filter(Boolean).join(": ") || "unknown error";
  if (typeof envelope.error === "string") return envelope.error;
  if (envelope.error) {
    return (
      [envelope.error.code, envelope.error.message].filter(Boolean).join(": ") || "unknown error"
    );
  }
  return null;
}

async function post(apiKey: string, tasks: unknown[]): Promise<Post> {
  let response: Response;
  try {
    response = await fetch(RUNWARE_ENDPOINT, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
        Accept: "application/json",
      },
      body: JSON.stringify(tasks),
    });
  } catch (err) {
    const cause = err instanceof Error ? err.message : String(err);
    return {
      ok: false,
      error: fail(
        "network",
        `Could not reach Runware (${cause}). Check your internet connection and try again.`
      ),
    };
  }
  if (response.status === 401 || response.status === 403) {
    return {
      ok: false,
      error: fail(
        "unauthorized",
        `Runware rejected the API key (HTTP ${response.status}). Double-check it at ${RUNWARE_KEYS_URL}, or create a new one.`
      ),
    };
  }
  if (response.status === 429) {
    return {
      ok: false,
      error: fail(
        "rate_limited",
        "Runware rate limit reached (HTTP 429). Wait a minute and retry."
      ),
    };
  }
  let body: RunwareEnvelope;
  try {
    body = (await response.json()) as RunwareEnvelope;
  } catch (err) {
    const cause = err instanceof Error ? err.message : String(err);
    return { ok: false, error: fail("bad_response", `Runware returned invalid JSON: ${cause}`) };
  }
  if (!response.ok && !body.data) {
    const detail = errorText(body);
    return {
      ok: false,
      error: fail(
        "bad_response",
        `Runware returned HTTP ${response.status}${detail ? `: ${detail}` : ""}`
      ),
    };
  }
  return { ok: true, body };
}

/**
 * Generate a GLB with TRELLIS.2 on Runware. Inputs are already validated by
 * `generateModel`; exactly one of `prompt` / `imageUrl` is set.
 */
export async function generateWithTrellis(options: {
  apiKey: string;
  prompt?: string;
  imageUrl?: string;
  quality: GenerateQuality;
  pollIntervalMs?: number;
  timeoutMs?: number;
  newUuid?: () => string;
}): Promise<GenerateModelResult> {
  const newUuid = options.newUuid ?? randomUUID;
  const tier = TRELLIS_TIERS[options.quality];
  let costUsd = 0;
  let sawCost = false;

  // ── 1. Text→3D: render a concept image first ──────────────────────────────
  let sourceImage = options.imageUrl ?? "";
  if (options.prompt !== undefined) {
    const imageTask = buildConceptTask(newUuid(), options.prompt);
    const imageCall = await post(options.apiKey, [imageTask]);
    if (!imageCall.ok) return imageCall.error;
    const detail = errorText(imageCall.body);
    const row = imageCall.body.data?.find((r) => typeof r.imageURL === "string");
    if (!row?.imageURL) {
      return fail(
        "task_failed",
        `Runware could not render the concept image${detail ? ` (${detail})` : ""}. Try rephrasing the prompt.`
      );
    }
    sourceImage = row.imageURL;
    if (typeof row.cost === "number") {
      costUsd += row.cost;
      sawCost = true;
    }
  }

  // ── 2. Submit the TRELLIS.2 task (async) ──────────────────────────────────
  const taskUUID = newUuid();
  const submit = await post(options.apiKey, [
    buildTrellisTask(taskUUID, sourceImage, options.quality),
  ]);
  if (!submit.ok) return submit.error;
  const submitError = errorText(submit.body);
  if (submitError && !submit.body.data?.length) {
    return fail("bad_response", `Runware rejected the 3D task: ${submitError}`);
  }

  // ── 3. Poll getResponse until success / error / deadline ──────────────────
  const pollIntervalMs = options.pollIntervalMs ?? DEFAULT_POLL_INTERVAL_MS;
  const timeoutMs = options.timeoutMs ?? tier.timeoutMs;
  const deadline = Date.now() + timeoutMs;
  let lastProgress = 0;

  for (;;) {
    const poll = await post(options.apiKey, [{ taskType: "getResponse", taskUUID }]);
    if (!poll.ok) {
      // A key that stops working is final; other hiccups are retried until the deadline.
      if (poll.error.code === "unauthorized") return poll.error;
    } else {
      const failed = poll.body.errors?.find((e) => !e.taskUUID || e.taskUUID === taskUUID);
      if (failed) {
        return fail(
          "task_failed",
          `Runware task ${taskUUID} failed${failed.message ? `: ${failed.message}` : ""}. Try a clearer prompt or source image and generate again.`
        );
      }
      const row = poll.body.data?.find((r) => r.taskUUID === taskUUID) ?? poll.body.data?.[0];
      if (typeof row?.progress === "number") lastProgress = row.progress;
      const modelUrl = row?.outputs?.files?.find((f) => typeof f.url === "string")?.url;
      if (row && (row.status === "success" || modelUrl)) {
        if (!modelUrl) {
          return fail(
            "bad_response",
            `Runware task ${taskUUID} succeeded but returned no GLB URL.`
          );
        }
        if (typeof row.cost === "number") {
          costUsd += row.cost;
          sawCost = true;
        }
        return {
          ok: true,
          model: {
            taskId: taskUUID,
            modelUrl,
            previewImageUrl: options.prompt !== undefined ? sourceImage : "",
            quality: options.quality,
            modelVersion: TRELLIS_MODEL,
            mode: options.prompt !== undefined ? "text" : "image",
            input: options.prompt ?? sourceImage,
            creditsConsumed: null,
            costUsd: sawCost ? Math.round(costUsd * 10_000) / 10_000 : null,
            provider: "trellis",
            license: TRELLIS_LICENSE,
            attribution: TRELLIS_ATTRIBUTION,
          },
        };
      }
    }

    if (Date.now() >= deadline) {
      return fail(
        "timeout",
        `Runware task ${taskUUID} did not finish within ${Math.round(timeoutMs / 1000)}s (progress ${lastProgress}%). The generation may still complete and be billed — retry later.`
      );
    }
    await sleep(pollIntervalMs);
  }
}
