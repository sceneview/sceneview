import { describe, it, expect, beforeAll, afterEach } from "vitest";
import { app } from "../src/index.js";
import type { Env } from "../src/env.js";
import { purgeExpiredMedia } from "../src/retention.js";
import { createMockKV } from "./helpers/mock-kv.js";
import { createMockD1, type MockD1 } from "./helpers/mock-d1.js";
import { createMockR2, type MockR2 } from "./helpers/mock-r2.js";

// A real RSA key is needed so the GitHub App JWT can actually be signed.
let PRIVATE_KEY_PEM = "";

beforeAll(async () => {
  const pair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  const pkcs8 = await crypto.subtle.exportKey("pkcs8", pair.privateKey);
  const b64 = Buffer.from(pkcs8).toString("base64");
  PRIVATE_KEY_PEM = `-----BEGIN PRIVATE KEY-----\n${b64
    .match(/.{1,64}/g)!
    .join("\n")}\n-----END PRIVATE KEY-----\n`;
});

interface TestEnv {
  DB: MockD1;
  RL_KV: ReturnType<typeof createMockKV>;
  MEDIA: MockR2;
  AI: { run: (model: string, inputs: unknown) => Promise<unknown> };
  ENVIRONMENT: string;
  GITHUB_REPO: string;
  GITHUB_APP_ID: string;
  GITHUB_INSTALLATION_ID: string;
  GITHUB_PRIVATE_KEY: string;
  ADMIN_TOKEN: string;
}

function makeEnv(): TestEnv {
  return {
    DB: createMockD1(),
    RL_KV: createMockKV(),
    MEDIA: createMockR2(),
    AI: {
      run: async () => ({
        text: "the spinner never stops",
        transcription_info: { language: "en" },
      }),
    },
    ENVIRONMENT: "test",
    GITHUB_REPO: "sceneview/sceneview",
    GITHUB_APP_ID: "12345",
    GITHUB_INSTALLATION_ID: "67890",
    GITHUB_PRIVATE_KEY: PRIVATE_KEY_PEM,
    ADMIN_TOKEN: "admin-secret",
  };
}

const asEnv = (e: TestEnv) => e as unknown as Env;

const originalFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = originalFetch;
});

function stubGitHub(opts: { issueFails?: boolean } = {}) {
  globalThis.fetch = (async (input: unknown, init?: { method?: string }) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    if (url.endsWith("/access_tokens") && method === "POST") {
      return new Response(JSON.stringify({ token: "ghs_test" }), {
        status: 201,
      });
    }
    if (url.includes("/labels/user-feedback")) {
      return new Response("", { status: 404 });
    }
    if (url.endsWith("/labels") && method === "POST") {
      return new Response(JSON.stringify({ id: 1 }), { status: 201 });
    }
    if (url.endsWith("/issues") && method === "POST") {
      return opts.issueFails
        ? new Response("server error", { status: 500 })
        : new Response(
            JSON.stringify({
              number: 1234,
              html_url:
                "https://github.com/sceneview/sceneview/issues/1234",
            }),
            { status: 201 },
          );
    }
    return new Response("unexpected", { status: 599 });
  }) as typeof fetch;
}

function feedbackForm(category = "bug"): FormData {
  const fd = new FormData();
  fd.set("category", category);
  fd.set(
    "context",
    JSON.stringify({
      appVersion: "4.11.1",
      device: "Pixel 7a",
      demoId: "lighting",
    }),
  );
  fd.set(
    "audio",
    new File([new Uint8Array([1, 2, 3, 4])], "audio.m4a", {
      type: "audio/mp4",
    }),
  );
  return fd;
}

/**
 * POST a multipart form to /v1/feedback with a correct `Content-Length`
 * header — a real native HTTP client always sends one for a buffered body,
 * and the worker now requires it (411 Length Required otherwise).
 * `headerOverride` lets a test forge/omit the header to exercise the guard.
 */
async function postFeedback(
  fd: FormData,
  env: Env,
  headerOverride?: Record<string, string | undefined>,
): Promise<Response> {
  // Serialise the body so we can measure its real length, then replay it.
  const probe = new Request("https://w/v1/feedback", { method: "POST", body: fd });
  const ct = probe.headers.get("content-type") ?? "multipart/form-data";
  const bytes = new Uint8Array(await probe.arrayBuffer());
  const headers: Record<string, string> = {
    "content-type": ct,
    "content-length": String(bytes.byteLength),
  };
  if (headerOverride) {
    for (const [k, v] of Object.entries(headerOverride)) {
      if (v === undefined) delete headers[k];
      else headers[k] = v;
    }
  }
  return app.request(
    "/v1/feedback",
    { method: "POST", body: bytes, headers },
    env,
  );
}

function feedbackRow(id: string): Record<string, unknown> {
  return {
    id,
    created_at: "2026-05-21 10:00:00",
    category: "bug",
    status: "issued",
    transcript: "the spinner never stops",
    context_json: JSON.stringify({ device: "Pixel 7a", demoId: "lighting" }),
    video_key: null,
    audio_key: `feedback/${id}/audio.m4a`,
    github_issue: 1234,
    github_url: "https://github.com/sceneview/sceneview/issues/1234",
    media_purged: 0,
  };
}

describe("feedback worker", () => {
  it("GET /health is ok", async () => {
    const res = await app.request("/health", {}, asEnv(makeEnv()));
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({
      ok: true,
      service: "sceneview-feedback",
    });
  });

  it("rejects an invalid category", async () => {
    const fd = new FormData();
    fd.set("category", "nonsense");
    fd.set("text", "something");
    const res = await postFeedback(fd, asEnv(makeEnv()));
    expect(res.status).toBe(400);
  });

  it("creates a GitHub issue on the happy path", async () => {
    stubGitHub();
    const env = makeEnv();
    const res = await postFeedback(feedbackForm("bug"), asEnv(env));
    expect(res.status).toBe(201);
    const json = (await res.json()) as { ok: boolean; issue: number };
    expect(json.ok).toBe(true);
    expect(json.issue).toBe(1234);

    const sqls = env.DB._statements.map((s) => s.sql).join(" | ");
    expect(sqls).toContain("INSERT INTO feedback");
    expect(sqls).toContain("status = 'issued'");
    expect(
      [...env.MEDIA._store.keys()].some((k) => k.endsWith("/audio.m4a")),
    ).toBe(true);
  });

  it("returns 202 and records an error when issue creation fails", async () => {
    stubGitHub({ issueFails: true });
    const env = makeEnv();
    const res = await postFeedback(feedbackForm("bug"), asEnv(env));
    expect(res.status).toBe(202);
    const json = (await res.json()) as { ok: boolean; issue: number | null };
    expect(json.ok).toBe(true);
    expect(json.issue).toBeNull();
    expect(env.DB._statements.map((s) => s.sql).join(" | ")).toContain(
      "status = 'error'",
    );
  });

  it("rate-limits after 5 submissions from the same IP", async () => {
    stubGitHub();
    const env = makeEnv();
    const statuses: number[] = [];
    for (let i = 0; i < 6; i++) {
      const res = await postFeedback(feedbackForm("idea"), asEnv(env));
      statuses.push(res.status);
    }
    expect(statuses.filter((s) => s === 429).length).toBe(1);
    expect(statuses[5]).toBe(429);
  });

  it("fails open (still succeeds) when the rate-limit KV is exhausted", async () => {
    stubGitHub();
    const env = makeEnv();
    // Reproduce Cloudflare KV's daily write-quota exhaustion: every put()
    // throws. The rate limiter and the issue-quota backstop must both fail
    // open so a KV-infrastructure failure never 500s the whole endpoint.
    env.RL_KV.put = async () => {
      throw new Error("KV put() limit exceeded for the day.");
    };
    const res = await postFeedback(feedbackForm("bug"), asEnv(env));
    expect(res.status).toBe(201);
    expect(((await res.json()) as { ok: boolean }).ok).toBe(true);
  });

  it("viewer returns 404 for an unknown id", async () => {
    const res = await app.request(
      `/feedback/${crypto.randomUUID()}`,
      {},
      asEnv(makeEnv()),
    );
    expect(res.status).toBe(404);
  });

  it("viewer hides media behind the admin gate without a token", async () => {
    const env = makeEnv();
    const id = crypto.randomUUID();
    env.DB._rows = [feedbackRow(id)];
    const res = await app.request(`/feedback/${id}`, {}, asEnv(env));
    expect(res.status).toBe(200);
    const html = await res.text();
    expect(html).toContain("the spinner never stops");
    expect(html).not.toContain("<audio");
    expect(html).toContain('name="token"');
  });

  it("a valid ?token= sets an admin cookie and redirects to a clean URL", async () => {
    const env = makeEnv();
    const id = crypto.randomUUID();
    env.DB._rows = [feedbackRow(id)];
    const res = await app.request(
      `/feedback/${id}?token=admin-secret`,
      {},
      asEnv(env),
    );
    expect(res.status).toBe(302);
    expect(res.headers.get("location")).toBe(`/feedback/${id}`);
    expect(res.headers.get("set-cookie") ?? "").toContain("fb_admin=");
  });

  it("viewer embeds the player when the request carries the admin cookie", async () => {
    const env = makeEnv();
    const id = crypto.randomUUID();
    env.DB._rows = [feedbackRow(id)];
    const res = await app.request(
      `/feedback/${id}`,
      { headers: { Cookie: "fb_admin=admin-secret" } },
      asEnv(env),
    );
    const html = await res.text();
    expect(html).toContain("<audio");
    expect(html).not.toContain("?token=");
  });

  it("media endpoint rejects requests without the admin cookie", async () => {
    const env = makeEnv();
    const id = crypto.randomUUID();
    env.DB._rows = [feedbackRow(id)];
    const res = await app.request(
      `/feedback/${id}/media/audio`,
      {},
      asEnv(env),
    );
    expect(res.status).toBe(401);
  });

  it("media endpoint streams the file with a valid admin cookie", async () => {
    const env = makeEnv();
    const id = crypto.randomUUID();
    const row = feedbackRow(id);
    env.DB._rows = [row];
    await env.MEDIA.put(
      row.audio_key as string,
      new Uint8Array([9, 9, 9]).buffer,
      { httpMetadata: { contentType: "audio/mp4" } },
    );
    const res = await app.request(
      `/feedback/${id}/media/audio`,
      { headers: { Cookie: "fb_admin=admin-secret" } },
      asEnv(env),
    );
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toContain("audio");
  });

  it("rejects an empty submission (no text, no audio, no video)", async () => {
    const fd = new FormData();
    fd.set("category", "idea");
    fd.set("context", "{}");
    const res = await postFeedback(fd, asEnv(makeEnv()));
    expect(res.status).toBe(400);
  });

  it("rejects an over-sized upload by actual file size", async () => {
    const fd = new FormData();
    fd.set("category", "bug");
    fd.set("context", "{}");
    // 31 MB — over the 30 MB ceiling. The honest Content-Length is over the
    // cap, so the pre-buffer guard rejects it with 413.
    fd.set(
      "video",
      new File([new Uint8Array(31 * 1024 * 1024)], "screen.mp4", {
        type: "video/mp4",
      }),
    );
    const res = await postFeedback(fd, asEnv(makeEnv()));
    expect(res.status).toBe(413);
  });

  it("rejects a request with a missing Content-Length header (411)", async () => {
    // A real native HTTP client always sends Content-Length for a buffered
    // body; a missing one is a malformed/abusive request and is rejected
    // BEFORE the body is buffered into the isolate.
    const res = await postFeedback(feedbackForm("bug"), asEnv(makeEnv()), {
      "content-length": undefined,
    });
    expect(res.status).toBe(411);
  });

  it("rejects a non-numeric Content-Length header (411)", async () => {
    const res = await postFeedback(feedbackForm("bug"), asEnv(makeEnv()), {
      "content-length": "not-a-number",
    });
    expect(res.status).toBe(411);
  });

  it("rejects a forged Content-Length over the cap before buffering (413)", async () => {
    // A small body but a forged huge Content-Length — rejected up-front.
    const res = await postFeedback(feedbackForm("bug"), asEnv(makeEnv()), {
      "content-length": String(99 * 1024 * 1024),
    });
    expect(res.status).toBe(413);
  });

  it("aborts the stream when the body exceeds the cap despite a small Content-Length", async () => {
    // Forge a tiny Content-Length but stream a 31 MB body: the counting
    // TransformStream must trip and the request is rejected, not OOM'd.
    const fd = new FormData();
    fd.set("category", "bug");
    fd.set("context", "{}");
    fd.set(
      "video",
      new File([new Uint8Array(31 * 1024 * 1024)], "screen.mp4", {
        type: "video/mp4",
      }),
    );
    const res = await postFeedback(fd, asEnv(makeEnv()), {
      "content-length": "1024",
    });
    expect(res.status).toBe(413);
  });

  it("skips issue creation when the global hourly quota is exhausted", async () => {
    stubGitHub();
    const env = makeEnv();
    // Pre-fill the current hour's global issue-quota bucket to the cap.
    const bucket = Math.floor(Date.now() / 3_600_000).toString(36);
    env.RL_KV._store.set(`issuequota:${bucket}`, "30");
    const res = await postFeedback(feedbackForm("bug"), asEnv(env));
    expect(res.status).toBe(202);
    const json = (await res.json()) as { ok: boolean; issue: number | null };
    expect(json.ok).toBe(true);
    expect(json.issue).toBeNull();
    expect(env.DB._statements.map((s) => s.sql).join(" | ")).toContain(
      "status = 'error'",
    );
  });

  it("deletes orphaned R2 media when the D1 insert fails", async () => {
    const env = makeEnv();
    // The media is stored in R2, then the D1 insert throws — the media must
    // be cleaned up so it is not stranded un-purgeable forever.
    env.DB._shouldFail = true;
    const res = await postFeedback(feedbackForm("bug"), asEnv(env));
    expect(res.status).toBe(502);
    expect(env.MEDIA._store.size).toBe(0);
  });

  it("rate-limits brute-force admin-token attempts on the viewer", async () => {
    const env = makeEnv();
    const id = crypto.randomUUID();
    env.DB._rows = [feedbackRow(id)];
    const statuses: number[] = [];
    // 11 wrong-token attempts — the 11th must be rate-limited (cap is 10/min).
    for (let i = 0; i < 11; i++) {
      const res = await app.request(
        `/feedback/${id}?token=wrong-${i}`,
        {},
        asEnv(env),
      );
      statuses.push(res.status);
    }
    expect(statuses.slice(0, 10).every((s) => s === 302)).toBe(true);
    expect(statuses[10]).toBe(429);
  });

  it("retention purges expired media and keeps the record", async () => {
    const env = makeEnv();
    env.DB._rows = [
      {
        id: "old-1",
        video_key: "feedback/old-1/screen.mp4",
        audio_key: "feedback/old-1/audio.m4a",
      },
    ];
    await env.MEDIA.put(
      "feedback/old-1/screen.mp4",
      new Uint8Array([1]).buffer,
    );
    await env.MEDIA.put(
      "feedback/old-1/audio.m4a",
      new Uint8Array([2]).buffer,
    );
    const purged = await purgeExpiredMedia(asEnv(env));
    expect(purged).toBe(1);
    expect(env.MEDIA._store.size).toBe(0);
    expect(env.DB._statements.map((s) => s.sql).join(" | ")).toContain(
      "media_purged = 1",
    );
  });
});
