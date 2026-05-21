import { Hono, type Context } from "hono";
import { cors } from "hono/cors";
import { getCookie, setCookie } from "hono/cookie";
import type { Env } from "./env.js";
import {
  adminAttemptLimited,
  isRateLimited,
  issueQuotaExceeded,
} from "./rate-limit.js";
import { transcribe } from "./transcribe.js";
import { buildIssue, type Category, type FeedbackContext } from "./issue.js";
import { createIssue } from "./github.js";
import {
  getFeedback,
  insertFeedback,
  markError,
  markIssued,
} from "./db.js";
import { renderNotFound, renderViewer } from "./viewer.js";
import { purgeExpiredMedia } from "./retention.js";

const app = new Hono<{ Bindings: Env }>();

/** Upload ceiling — a short screen recording is well under this. */
const MAX_UPLOAD = 30 * 1024 * 1024; // 30 MB
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Wrap a request body stream in a counting `TransformStream` that aborts once
 * `limit` bytes have flowed through it. This caps how much can be buffered into
 * the 128 MB isolate by `formData()` even when `Content-Length` is missing or
 * forged — the stream is torn down before the body is fully read into memory.
 */
function limitedBody(
  body: ReadableStream<Uint8Array>,
  limit: number,
): ReadableStream<Uint8Array> {
  let seen = 0;
  return body.pipeThrough(
    new TransformStream<Uint8Array, Uint8Array>({
      transform(chunk, controller) {
        seen += chunk.byteLength;
        if (seen > limit) {
          controller.error(new Error("upload_too_large"));
          return;
        }
        controller.enqueue(chunk);
      },
    }),
  );
}

/** Pick a File-valued multipart field; null if absent or a plain string. */
function fileField(form: FormData, name: string): File | null {
  const entry: unknown = form.get(name);
  if (
    entry !== null &&
    typeof entry === "object" &&
    typeof (entry as { arrayBuffer?: unknown }).arrayBuffer === "function"
  ) {
    return entry as File;
  }
  return null;
}

/** Name of the HttpOnly cookie carrying the admin session token. */
const ADMIN_COOKIE = "fb_admin";

/** Constant-time string compare via SHA-256 digests (fixed length, no early-out). */
async function constantTimeEqual(a: string, b: string): Promise<boolean> {
  const enc = new TextEncoder();
  const [da, db] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(a)),
    crypto.subtle.digest("SHA-256", enc.encode(b)),
  ]);
  const va = new Uint8Array(da);
  const vb = new Uint8Array(db);
  let diff = 0;
  for (let i = 0; i < va.length; i++) diff |= va[i] ^ vb[i];
  return diff === 0;
}

/** True when the request carries a valid admin session cookie. */
async function isAdmin(c: Context<{ Bindings: Env }>): Promise<boolean> {
  const token = c.env.ADMIN_TOKEN;
  if (!token) return false;
  const cookie = getCookie(c, ADMIN_COOKIE);
  return !!cookie && (await constantTimeEqual(cookie, token));
}

// ── CORS — the demo apps post from a native HTTP client ─────────────────────
app.use(
  "/v1/*",
  cors({
    origin: "*",
    allowMethods: ["POST", "OPTIONS"],
    allowHeaders: ["content-type"],
    maxAge: 86400,
  }),
);

// ── Health check ─────────────────────────────────────────────────────────────
app.get("/health", (c) =>
  c.json({ ok: true, service: "sceneview-feedback", version: "1.0.0" }),
);

// ── POST /v1/feedback — receive a feedback submission ────────────────────────
app.post("/v1/feedback", async (c) => {
  // Reject oversized uploads BEFORE buffering the body into the isolate.
  // `Content-Length` is client-controlled and may be absent or forged, so a
  // present numeric value over the cap is rejected outright; a missing or
  // non-numeric value is also rejected (a well-behaved multipart upload always
  // sends one). The streaming guard below is the real backstop for the rest.
  // An empty-string value is rejected explicitly — `Number("")` is `0`, so an
  // empty header would otherwise slip through as a zero-length body.
  const clHeader = c.req.header("content-length");
  const cl = Number(clHeader);
  if (
    clHeader === undefined ||
    clHeader.trim() === "" ||
    !Number.isFinite(cl) ||
    cl < 0
  ) {
    return c.json({ error: "length_required" }, 411);
  }
  if (cl > MAX_UPLOAD) return c.json({ error: "payload_too_large" }, 413);

  // Rate limit by IP (hashed — never stored raw).
  const ip = c.req.header("cf-connecting-ip") ?? "unknown";
  const rl = await isRateLimited(ip, c.env.RL_KV);
  if (rl.limited) {
    return c.json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
  }

  // Hard ceiling: wrap the raw body in a counting stream that aborts once
  // MAX_UPLOAD bytes have flowed, so a forged Content-Length cannot OOM the
  // isolate by streaming an unbounded body into `formData()`.
  let form: FormData;
  try {
    const raw = c.req.raw.body;
    const req = raw
      ? new Request(c.req.raw.url, {
          method: c.req.raw.method,
          headers: c.req.raw.headers,
          body: limitedBody(raw, MAX_UPLOAD),
          // Streaming request bodies require an explicit half-duplex hint.
          duplex: "half",
        } as RequestInit & { duplex: "half" })
      : c.req.raw;
    form = await req.formData();
  } catch {
    // A multipart parse error and a tripped size guard are indistinguishable
    // here; both mean the body could not be safely consumed.
    return c.json({ error: "invalid_or_oversized_multipart" }, 413);
  }

  const category = String(form.get("category") ?? "");
  if (category !== "bug" && category !== "idea") {
    return c.json({ error: "invalid_category" }, 400);
  }
  const text = String(form.get("text") ?? "").slice(0, 4000);

  // Optional JSON context — bounded, malformed input is ignored, not fatal.
  let context: FeedbackContext = {};
  const rawContext = form.get("context");
  if (typeof rawContext === "string" && rawContext.length <= 8192) {
    try {
      const parsed: unknown = JSON.parse(rawContext);
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        context = parsed as FeedbackContext;
      }
    } catch {
      // malformed context — proceed without it
    }
  }

  const video = fileField(form, "video");
  const audio = fileField(form, "audio");

  // Enforce the real upload size on the File objects — `content-length`
  // (checked above) is optional and client-controlled, so it is not authoritative.
  if ((video?.size ?? 0) + (audio?.size ?? 0) > MAX_UPLOAD) {
    return c.json({ error: "payload_too_large" }, 413);
  }
  // Reject empty submissions — no text, no audio and no video would only
  // produce a blank GitHub issue.
  if (!text && !video && !audio) {
    return c.json({ error: "empty_feedback" }, 400);
  }

  const id = crypto.randomUUID();
  let videoKey: string | null = null;
  let audioKey: string | null = null;
  let audioBuf: ArrayBuffer | null = null;

  // Store media privately in R2.
  try {
    if (video && video.size > 0) {
      videoKey = `feedback/${id}/screen.mp4`;
      await c.env.MEDIA.put(videoKey, await video.arrayBuffer(), {
        httpMetadata: { contentType: video.type || "video/mp4" },
      });
    }
    if (audio && audio.size > 0) {
      audioBuf = await audio.arrayBuffer();
      audioKey = `feedback/${id}/audio.m4a`;
      await c.env.MEDIA.put(audioKey, audioBuf, {
        httpMetadata: { contentType: audio.type || "audio/mp4" },
      });
    }
  } catch (e) {
    console.error("R2 upload failed", e);
    return c.json({ error: "storage_failed" }, 502);
  }

  // Transcribe the audio (best-effort — never throws).
  // Surface the Whisper-detected language in the context so a maintainer can
  // tell which language the transcript is in (it is auto-detected — the user
  // may speak in any language).
  let transcript = "";
  if (audioBuf) {
    const transcription = await transcribe(c.env, audioBuf);
    transcript = transcription.text;
    if (transcription.language && context.transcriptLanguage === undefined) {
      context.transcriptLanguage = transcription.language;
    }
  }

  // Persist the record.
  try {
    await insertFeedback(c.env, {
      id,
      category,
      transcript: transcript || null,
      context_json: JSON.stringify(context),
      video_key: videoKey,
      audio_key: audioKey,
    });
  } catch (e) {
    console.error("D1 insert failed", e);
    // The media is already in R2 but no row references it — retention sweeps
    // by D1 row, so without this cleanup the objects would be un-purgeable
    // forever. Best-effort delete both keys before returning.
    await Promise.allSettled([
      videoKey ? c.env.MEDIA.delete(videoKey) : Promise.resolve(),
      audioKey ? c.env.MEDIA.delete(audioKey) : Promise.resolve(),
    ]);
    return c.json({ error: "storage_failed" }, 502);
  }

  // Build + create the GitHub issue.
  const viewerUrl = new URL(`/feedback/${id}`, c.req.url).toString();
  const issue = buildIssue({
    id,
    category: category as Category,
    transcript,
    text,
    context,
    viewerUrl,
  });

  // Repo-wide backstop: if issue creation is being flooded this hour, keep the
  // feedback (media + transcript are already stored) but skip opening the issue.
  // The `reason` field lets a caller distinguish a deliberate throttle
  // (`quota`) from a GitHub-side failure (`github_error`) — both still 202.
  if (await issueQuotaExceeded(c.env.RL_KV)) {
    console.error("global issue quota exceeded — feedback stored, issue skipped");
    await markError(c.env, id).catch(() => {});
    return c.json({ ok: true, id, issue: null, reason: "quota" }, 202);
  }

  try {
    const created = await createIssue(c.env, issue);
    await markIssued(c.env, id, created.number, created.url);
    return c.json({ ok: true, id, issue: created.number, url: created.url }, 201);
  } catch (e) {
    // Media + transcript are saved — the maintainer can still recover this
    // from the viewer even though the issue was not opened.
    console.error("GitHub issue creation failed", e);
    await markError(c.env, id).catch(() => {});
    return c.json({ ok: true, id, issue: null, reason: "github_error" }, 202);
  }
});

// ── GET /feedback/:id — the viewer page ──────────────────────────────────────
app.get("/feedback/:id", async (c) => {
  const id = c.req.param("id");
  if (!UUID_RE.test(id)) return c.html(renderNotFound(), 404);

  const row = await getFeedback(c.env, id);
  if (!row) return c.html(renderNotFound(), 404);

  // Admin unlock: a valid `?token=` sets an HttpOnly cookie and redirects to a
  // clean URL, so the token never lingers in browser history, logs or Referer.
  const queryToken = c.req.query("token");
  if (queryToken !== undefined) {
    // Feedback UUIDs appear publicly on the GitHub issues, so the viewer URL is
    // guessable — rate-limit token attempts per IP to blunt a brute-force.
    const ip = c.req.header("cf-connecting-ip") ?? "unknown";
    if (await adminAttemptLimited(ip, c.env.RL_KV)) {
      return c.json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
    }
    if (
      c.env.ADMIN_TOKEN &&
      (await constantTimeEqual(queryToken, c.env.ADMIN_TOKEN))
    ) {
      setCookie(c, ADMIN_COOKIE, c.env.ADMIN_TOKEN, {
        httpOnly: true,
        secure: true,
        sameSite: "Strict",
        path: "/feedback",
        maxAge: 28800,
      });
    } else {
      // Audit trail — a failed admin-token attempt against a guessable URL.
      console.warn(`failed admin-token attempt for feedback ${id}`);
    }
    return c.redirect(`/feedback/${id}`, 302);
  }

  return c.html(renderViewer(row, await isAdmin(c)));
});

// ── GET /feedback/:id/media/:kind — admin-gated media stream ─────────────────
app.get("/feedback/:id/media/:kind", async (c) => {
  if (!(await isAdmin(c))) {
    return c.json({ error: "unauthorized" }, 401);
  }
  const id = c.req.param("id");
  const kind = c.req.param("kind");
  if (!UUID_RE.test(id) || (kind !== "video" && kind !== "audio")) {
    return c.json({ error: "not_found" }, 404);
  }

  const row = await getFeedback(c.env, id);
  if (!row) return c.json({ error: "not_found" }, 404);

  const key = kind === "video" ? row.video_key : row.audio_key;
  if (!key) return c.json({ error: "not_found" }, 404);

  const obj = await c.env.MEDIA.get(key);
  if (!obj) return c.json({ error: "not_found" }, 404);

  return new Response(obj.body, {
    headers: {
      "content-type":
        obj.httpMetadata?.contentType ??
        (kind === "video" ? "video/mp4" : "audio/mp4"),
      "cache-control": "private, no-store",
    },
  });
});

// ── Landing page ─────────────────────────────────────────────────────────────
app.get("/", (c) =>
  c.html(`<!DOCTYPE html>
<html lang="en"><head><meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<title>SceneView Feedback</title>
<style>body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
background:#0d1117;color:#e6edf3;min-height:100vh;display:flex;align-items:center;
justify-content:center;padding:2rem;text-align:center}div{max-width:420px}
h1{font-size:1.3rem;margin-bottom:.75rem}p{color:#8b949e;font-size:.9rem;line-height:1.6}</style>
</head><body><div><h1>SceneView Feedback</h1>
<p>Receives in-app feedback from the SceneView demo apps and opens pre-filled
GitHub issues. Screen recordings and audio are kept private.</p></div></body></html>`),
);

// ── Catch-all ────────────────────────────────────────────────────────────────
app.all("*", (c) => c.json({ error: "not_found" }, 404));

export { app };

export default {
  fetch: app.fetch,
  async scheduled(_event: ScheduledEvent, env: Env, ctx: ExecutionContext) {
    ctx.waitUntil(
      purgeExpiredMedia(env)
        .then((n) => console.log(`retention cron: purged ${n} record(s)`))
        .catch((e) => console.error("retention cron failed", e)),
    );
  },
};
