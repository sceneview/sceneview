import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  buildGenerateBody,
  formatGenerateWorldResult,
  type GenerateWorldError,
  type GenerateWorldSuccess,
  generateWorld,
} from "./generate-world.js";

// ─── Test helpers ───────────────────────────────────────────────────────────

const API_KEY_ENV = "WORLDLABS_API_KEY";
const GENERATE_ENDPOINT = "https://api.worldlabs.ai/marble/v1/worlds:generate";
const OPERATIONS_ENDPOINT = "https://api.worldlabs.ai/marble/v1/operations";

/** Zero-delay polling so tests never wait on real timers. */
const FAST_POLL = { pollIntervalMs: 0 };

function wlResponse(
  body: unknown,
  init: Partial<{ status: number; statusText: string; ok: boolean }> = {}
) {
  return {
    ok: init.ok ?? true,
    status: init.status ?? 200,
    statusText: init.statusText ?? "OK",
    json: async () => body,
  } as unknown as Response;
}

const SUBMIT_OK = { operation_id: "op-123", done: false };

const POLL_RUNNING = {
  operation_id: "op-123",
  done: false,
  metadata: { progress: { status: "IN_PROGRESS", description: "Generating splats" } },
};

const WORLD = {
  id: "world-abc",
  display_name: "A sunlit Kyoto tea house",
  generation_input: { model: "marble-1.0-draft" },
  assets: {
    caption: "A wooden tea house with tatami floors and paper screens.",
    thumbnail_url: "https://cdn.worldlabs.ai/w/world-abc/thumb.jpg",
    splats: {
      spz_urls: {
        "100k": "https://cdn.worldlabs.ai/w/world-abc/100k.spz",
        "500k": "https://cdn.worldlabs.ai/w/world-abc/500k.spz",
        full_res: "https://cdn.worldlabs.ai/w/world-abc/full.spz",
      },
    },
    mesh: { collider_mesh_url: "https://cdn.worldlabs.ai/w/world-abc/collider.glb" },
    imagery: { pano_url: "https://cdn.worldlabs.ai/w/world-abc/pano.png" },
  },
};

const POLL_DONE = { operation_id: "op-123", done: true, response: WORLD };

// ─── Environment management ─────────────────────────────────────────────────

let originalKey: string | undefined;

beforeEach(() => {
  originalKey = process.env[API_KEY_ENV];
  delete process.env[API_KEY_ENV];
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  if (originalKey === undefined) {
    delete process.env[API_KEY_ENV];
  } else {
    process.env[API_KEY_ENV] = originalKey;
  }
});

// ─── Happy path: text → world ───────────────────────────────────────────────

describe("generateWorld — happy path (text→world, draft)", () => {
  it("submits a marble-1.0-draft text prompt, polls to done, and returns every asset URL", async () => {
    process.env[API_KEY_ENV] = "wl_fake_key";
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(wlResponse(SUBMIT_OK))
      .mockResolvedValueOnce(wlResponse(POLL_RUNNING))
      .mockResolvedValueOnce(wlResponse(POLL_DONE));
    vi.stubGlobal("fetch", fetchMock);

    const result = await generateWorld({ prompt: "A sunlit Kyoto tea house", ...FAST_POLL });

    expect(result.ok).toBe(true);
    const success = result as GenerateWorldSuccess;
    expect(success.world.operationId).toBe("op-123");
    expect(success.world.worldId).toBe("world-abc");
    expect(success.world.model).toBe("marble-1.0-draft");
    expect(success.world.quality).toBe("draft");
    expect(success.world.mode).toBe("text");
    expect(success.world.splatUrls.k100).toBe("https://cdn.worldlabs.ai/w/world-abc/100k.spz");
    expect(success.world.splatUrls.k500).toBe("https://cdn.worldlabs.ai/w/world-abc/500k.spz");
    expect(success.world.splatUrls.full).toBe("https://cdn.worldlabs.ai/w/world-abc/full.spz");
    expect(success.world.colliderMeshUrl).toBe("https://cdn.worldlabs.ai/w/world-abc/collider.glb");
    expect(success.world.panoramaUrl).toBe("https://cdn.worldlabs.ai/w/world-abc/pano.png");
    expect(success.world.caption).toContain("tea house");
    expect(success.world.estimatedCredits).toBe("150–250");

    // Submit: POST to worlds:generate with the World Labs header, never Authorization.
    const [submitUrl, submitInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(submitUrl).toBe(GENERATE_ENDPOINT);
    expect(submitInit.method).toBe("POST");
    const headers = submitInit.headers as Record<string, string>;
    expect(headers["WLT-Api-Key"]).toBe("wl_fake_key");
    expect(headers.Authorization).toBeUndefined();
    const body = JSON.parse(submitInit.body as string);
    expect(body.model).toBe("marble-1.0-draft");
    expect(body.world_prompt).toEqual({ type: "text", text_prompt: "A sunlit Kyoto tea house" });
    expect(body.display_name).toBe("A sunlit Kyoto tea house");

    // Poll: GET operations/{id} with the same header.
    const [pollUrl, pollInit] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(pollUrl).toBe(`${OPERATIONS_ENDPOINT}/op-123`);
    expect((pollInit.headers as Record<string, string>)["WLT-Api-Key"]).toBe("wl_fake_key");
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("accepts a Google-style `name: operations/<id>` operation envelope", async () => {
    process.env[API_KEY_ENV] = "wl_fake_key";
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(wlResponse({ name: "operations/op-999", done: false }))
      .mockResolvedValueOnce(
        wlResponse({ name: "operations/op-999", done: true, response: WORLD })
      );
    vi.stubGlobal("fetch", fetchMock);

    const result = await generateWorld({ prompt: "a lighthouse", ...FAST_POLL });

    expect(result.ok).toBe(true);
    expect((result as GenerateWorldSuccess).world.operationId).toBe("op-999");
    expect(fetchMock.mock.calls[1]?.[0]).toBe(`${OPERATIONS_ENDPOINT}/op-999`);
  });

  it("skips polling when the submit response is already done", async () => {
    process.env[API_KEY_ENV] = "wl_fake_key";
    const fetchMock = vi.fn().mockResolvedValueOnce(wlResponse(POLL_DONE));
    vi.stubGlobal("fetch", fetchMock);

    const result = await generateWorld({ prompt: "a cave", ...FAST_POLL });

    expect(result.ok).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

// ─── Quality tiers ──────────────────────────────────────────────────────────

describe("generateWorld — quality tiers", () => {
  it.each([
    ["standard", "marble-1.1", "1500"],
    ["large", "marble-1.1-plus", "1500–3000"],
  ] as const)("quality=%s submits %s", async (quality, model, credits) => {
    process.env[API_KEY_ENV] = "wl_fake_key";
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(wlResponse(SUBMIT_OK))
      .mockResolvedValueOnce(
        wlResponse({
          ...POLL_DONE,
          response: { ...WORLD, generation_input: { model } },
        })
      );
    vi.stubGlobal("fetch", fetchMock);

    const result = await generateWorld({ prompt: "a market", quality, ...FAST_POLL });

    const body = JSON.parse((fetchMock.mock.calls[0] as [string, RequestInit])[1].body as string);
    expect(body.model).toBe(model);
    expect(result.ok).toBe(true);
    expect((result as GenerateWorldSuccess).world.estimatedCredits).toBe(credits);
  });

  it("falls back to draft for an unknown quality value", () => {
    const body = buildGenerateBody({ prompt: "x", quality: "draft" });
    expect(body.model).toBe("marble-1.0-draft");
  });
});

// ─── Media prompts ──────────────────────────────────────────────────────────

describe("generateWorld — image→world and video→world", () => {
  it("submits an image prompt with a guiding text prompt and the panorama flag", async () => {
    process.env[API_KEY_ENV] = "wl_fake_key";
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(wlResponse(SUBMIT_OK))
      .mockResolvedValueOnce(wlResponse(POLL_DONE));
    vi.stubGlobal("fetch", fetchMock);

    const result = await generateWorld({
      imageUrl: "https://example.com/pano.jpg",
      isPanorama: true,
      prompt: "make it dusk",
      seed: 7.9,
      ...FAST_POLL,
    });

    expect(result.ok).toBe(true);
    expect((result as GenerateWorldSuccess).world.mode).toBe("image");
    expect((result as GenerateWorldSuccess).world.input).toBe("https://example.com/pano.jpg");
    const body = JSON.parse((fetchMock.mock.calls[0] as [string, RequestInit])[1].body as string);
    expect(body.world_prompt).toEqual({
      type: "image",
      text_prompt: "make it dusk",
      image_prompt: { source: "uri", uri: "https://example.com/pano.jpg", is_pano: true },
    });
    expect(body.seed).toBe(7);
  });

  it("submits a video prompt by public URL", async () => {
    process.env[API_KEY_ENV] = "wl_fake_key";
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(wlResponse(SUBMIT_OK))
      .mockResolvedValueOnce(wlResponse(POLL_DONE));
    vi.stubGlobal("fetch", fetchMock);

    const result = await generateWorld({
      videoUrl: "https://example.com/room.mp4",
      ...FAST_POLL,
    });

    expect(result.ok).toBe(true);
    expect((result as GenerateWorldSuccess).world.mode).toBe("video");
    const body = JSON.parse((fetchMock.mock.calls[0] as [string, RequestInit])[1].body as string);
    expect(body.world_prompt).toEqual({
      type: "video",
      video_prompt: { source: "uri", uri: "https://example.com/room.mp4" },
    });
    expect(body.display_name).toBe("SceneView world (from video)");
  });
});

describe("buildGenerateBody", () => {
  it("truncates a long prompt into the display name and keeps the full text_prompt", () => {
    const prompt = "A ".repeat(80).trim();
    const body = buildGenerateBody({ prompt, quality: "draft" });
    expect((body.display_name as string).length).toBeLessThanOrEqual(60);
    expect((body.world_prompt as { text_prompt: string }).text_prompt).toBe(prompt);
  });

  it("omits seed when it is not a finite number", () => {
    const body = buildGenerateBody({ prompt: "x", quality: "draft", seed: Number.NaN });
    expect(body.seed).toBeUndefined();
  });
});

// ─── Resume an operation ────────────────────────────────────────────────────

describe("generateWorld — resume with operationId", () => {
  it("polls without submitting and reports the resumed mode", async () => {
    process.env[API_KEY_ENV] = "wl_fake_key";
    const fetchMock = vi.fn().mockResolvedValueOnce(
      wlResponse({
        ...POLL_DONE,
        response: { ...WORLD, generation_input: { model: "marble-1.1" } },
      })
    );
    vi.stubGlobal("fetch", fetchMock);

    const result = await generateWorld({ operationId: "op-123", ...FAST_POLL });

    expect(result.ok).toBe(true);
    const world = (result as GenerateWorldSuccess).world;
    expect(world.mode).toBe("resumed");
    expect(world.quality).toBeNull();
    expect(world.estimatedCredits).toBeNull();
    expect(world.model).toBe("marble-1.1");
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0]?.[0]).toBe(`${OPERATIONS_ENDPOINT}/op-123`);
  });

  it("rejects operationId combined with a new prompt, before any network", async () => {
    process.env[API_KEY_ENV] = "wl_fake_key";
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const result = (await generateWorld({
      operationId: "op-123",
      prompt: "x",
    })) as GenerateWorldError;

    expect(result.ok).toBe(false);
    expect(result.code).toBe("invalid_input");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("maps an unknown operation (404) to bad_response", async () => {
    process.env[API_KEY_ENV] = "wl_fake_key";
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(wlResponse({}, { ok: false, status: 404, statusText: "Not Found" }))
    );

    const result = (await generateWorld({
      operationId: "op-nope",
      ...FAST_POLL,
    })) as GenerateWorldError;

    expect(result.code).toBe("bad_response");
    expect(result.message).toContain("op-nope");
  });
});

// ─── Missing key ────────────────────────────────────────────────────────────

describe("generateWorld — missing API key", () => {
  it("returns actionable BYOK instructions when WORLDLABS_API_KEY is not set", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const result = (await generateWorld({ prompt: "a forest" })) as GenerateWorldError;

    expect(result.ok).toBe(false);
    expect(result.code).toBe("missing_key");
    expect(result.message).toContain("WORLDLABS_API_KEY");
    expect(result.message).toContain("platform.worldlabs.ai");
    expect(result.message).toContain("separate from the Marble app");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("returns missing_key when the env var is blank", async () => {
    process.env[API_KEY_ENV] = "   ";
    vi.stubGlobal("fetch", vi.fn());

    const result = (await generateWorld({ prompt: "a forest" })) as GenerateWorldError;

    expect(result.code).toBe("missing_key");
  });
});

// ─── Input validation ───────────────────────────────────────────────────────

describe("generateWorld — input validation", () => {
  beforeEach(() => {
    process.env[API_KEY_ENV] = "wl_fake_key";
  });

  it("rejects a call with no prompt, image, video or operation, before any network", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const result = (await generateWorld({})) as GenerateWorldError;

    expect(result.code).toBe("invalid_input");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejects imageUrl together with videoUrl", async () => {
    const result = (await generateWorld({
      imageUrl: "https://example.com/a.jpg",
      videoUrl: "https://example.com/a.mp4",
    })) as GenerateWorldError;

    expect(result.code).toBe("invalid_input");
    expect(result.message).toContain("not both");
  });

  it("rejects prompts above the length cap", async () => {
    const result = (await generateWorld({ prompt: "x".repeat(2001) })) as GenerateWorldError;

    expect(result.code).toBe("invalid_input");
    expect(result.message).toContain("too long");
  });

  it.each([
    ["http://example.com/a.jpg", "imageUrl"],
    ["file:///tmp/a.jpg", "imageUrl"],
    ["/tmp/a.jpg", "imageUrl"],
  ])("rejects the non-https image URL %s", async (url) => {
    const result = (await generateWorld({ imageUrl: url })) as GenerateWorldError;

    expect(result.code).toBe("invalid_input");
    expect(result.message).toContain("https://");
  });

  it("rejects a non-https video URL", async () => {
    const result = (await generateWorld({
      videoUrl: "http://example.com/a.mp4",
    })) as GenerateWorldError;

    expect(result.code).toBe("invalid_input");
    expect(result.message).toContain("30 seconds");
  });

  it("rejects isPanorama without an imageUrl", async () => {
    const result = (await generateWorld({ prompt: "x", isPanorama: true })) as GenerateWorldError;

    expect(result.code).toBe("invalid_input");
    expect(result.message).toContain("isPanorama");
  });
});

// ─── Submit errors ──────────────────────────────────────────────────────────

describe("generateWorld — submit errors", () => {
  beforeEach(() => {
    process.env[API_KEY_ENV] = "wl_fake_key";
  });

  it("maps HTTP 401/403 to `unauthorized` without echoing the key", async () => {
    for (const status of [401, 403]) {
      vi.stubGlobal(
        "fetch",
        vi
          .fn()
          .mockResolvedValueOnce(
            wlResponse(
              { error: { message: "invalid key" } },
              { ok: false, status, statusText: "Nope" }
            )
          )
      );
      const result = (await generateWorld({ prompt: "x" })) as GenerateWorldError;
      expect(result.code).toBe("unauthorized");
      expect(result.message).toContain(String(status));
      expect(result.message).toContain("invalid key");
      expect(result.message).not.toContain("wl_fake_key");
    }
  });

  it("maps HTTP 402 to `payment_required` with the separate-credits hint", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(
          wlResponse(
            { detail: "Insufficient credits" },
            { ok: false, status: 402, statusText: "Payment Required" }
          )
        )
    );

    const result = (await generateWorld({ prompt: "x", quality: "large" })) as GenerateWorldError;

    expect(result.code).toBe("payment_required");
    expect(result.message).toContain("Insufficient credits");
    expect(result.message).toContain("SEPARATE");
    expect(result.message).toContain("draft");
  });

  it("maps HTTP 429 to `rate_limited`", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(wlResponse({}, { ok: false, status: 429, statusText: "Too Many" }))
    );

    const result = (await generateWorld({ prompt: "x" })) as GenerateWorldError;

    expect(result.code).toBe("rate_limited");
  });

  it("maps other HTTP failures to `bad_response`", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(
          wlResponse(
            { error: "model not found" },
            { ok: false, status: 400, statusText: "Bad Request" }
          )
        )
    );

    const result = (await generateWorld({ prompt: "x" })) as GenerateWorldError;

    expect(result.code).toBe("bad_response");
    expect(result.message).toContain("400");
    expect(result.message).toContain("model not found");
  });

  it("maps fetch rejections to `network` and says nothing was billed", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValueOnce(new Error("ECONNRESET")));

    const result = (await generateWorld({ prompt: "x" })) as GenerateWorldError;

    expect(result.code).toBe("network");
    expect(result.message).toContain("ECONNRESET");
    expect(result.message).toContain("nothing was billed");
  });

  it("maps a missing operation id to `bad_response`", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(wlResponse({ done: false })));

    const result = (await generateWorld({ prompt: "x" })) as GenerateWorldError;

    expect(result.code).toBe("bad_response");
    expect(result.message).toContain("no operation id");
  });

  it("maps submit JSON parse failures to `bad_response`", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce({
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => {
          throw new Error("Unexpected token <");
        },
      } as unknown as Response)
    );

    const result = (await generateWorld({ prompt: "x" })) as GenerateWorldError;

    expect(result.code).toBe("bad_response");
    expect(result.message).toContain("invalid JSON");
  });
});

// ─── Operation outcomes ─────────────────────────────────────────────────────

describe("generateWorld — operation failure and odd payloads", () => {
  beforeEach(() => {
    process.env[API_KEY_ENV] = "wl_fake_key";
  });

  it("returns task_failed with the LRO error message and the operation id", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(wlResponse(SUBMIT_OK))
        .mockResolvedValueOnce(
          wlResponse({
            operation_id: "op-123",
            done: true,
            error: { code: 3, message: "video too long" },
          })
        )
    );

    const result = (await generateWorld({
      videoUrl: "https://example.com/long.mp4",
      ...FAST_POLL,
    })) as GenerateWorldError;

    expect(result.code).toBe("task_failed");
    expect(result.message).toContain("video too long");
    expect(result.message).toContain("30 s");
    expect(result.operationId).toBe("op-123");
  });

  it("returns bad_response when a done operation carries no world", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(wlResponse(SUBMIT_OK))
        .mockResolvedValueOnce(wlResponse({ operation_id: "op-123", done: true }))
    );

    const result = (await generateWorld({ prompt: "x", ...FAST_POLL })) as GenerateWorldError;

    expect(result.code).toBe("bad_response");
    expect(result.message).toContain("without a world");
  });

  it("returns bad_response when the world has no downloadable assets", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(wlResponse(SUBMIT_OK))
        .mockResolvedValueOnce(
          wlResponse({ operation_id: "op-123", done: true, response: { id: "w", assets: {} } })
        )
    );

    const result = (await generateWorld({ prompt: "x", ...FAST_POLL })) as GenerateWorldError;

    expect(result.code).toBe("bad_response");
    expect(result.message).toContain("no downloadable assets");
  });

  it("returns a partial asset set when only the mesh and panorama exist", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(wlResponse(SUBMIT_OK))
        .mockResolvedValueOnce(
          wlResponse({
            operation_id: "op-123",
            done: true,
            response: {
              id: "w",
              assets: {
                mesh: { collider_mesh_url: "https://cdn/collider.glb" },
                imagery: { pano_url: "https://cdn/pano.png" },
              },
            },
          })
        )
    );

    const result = await generateWorld({ prompt: "x", ...FAST_POLL });

    expect(result.ok).toBe(true);
    const world = (result as GenerateWorldSuccess).world;
    expect(world.splatUrls).toEqual({ k100: null, k500: null, full: null });
    expect(world.colliderMeshUrl).toBe("https://cdn/collider.glb");
  });
});

// ─── Polling ────────────────────────────────────────────────────────────────

describe("generateWorld — polling", () => {
  beforeEach(() => {
    process.env[API_KEY_ENV] = "wl_fake_key";
  });

  it("returns `timeout` with the operation id and resume hint when the deadline passes", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(wlResponse(SUBMIT_OK))
        .mockResolvedValue(wlResponse(POLL_RUNNING))
    );

    const result = (await generateWorld({
      prompt: "x",
      pollIntervalMs: 0,
      timeoutMs: 0,
    })) as GenerateWorldError;

    expect(result.code).toBe("timeout");
    expect(result.operationId).toBe("op-123");
    expect(result.message).toContain('operationId: "op-123"');
    expect(result.message).toContain("IN_PROGRESS");
    expect(result.message).toContain("Generating splats");
  });

  it("tolerates transient poll failures (5xx, bad JSON) and still succeeds", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(wlResponse(SUBMIT_OK))
        .mockResolvedValueOnce(
          wlResponse({}, { ok: false, status: 503, statusText: "Unavailable" })
        )
        .mockResolvedValueOnce({
          ok: true,
          status: 200,
          statusText: "OK",
          json: async () => {
            throw new Error("truncated");
          },
        } as unknown as Response)
        .mockResolvedValueOnce(wlResponse(POLL_DONE))
    );

    const result = await generateWorld({ prompt: "x", ...FAST_POLL });

    expect(result.ok).toBe(true);
  });

  it("maps an unauthorized poll response to `unauthorized` (key revoked mid-job)", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(wlResponse(SUBMIT_OK))
        .mockResolvedValueOnce(
          wlResponse({}, { ok: false, status: 401, statusText: "Unauthorized" })
        )
    );

    const result = (await generateWorld({ prompt: "x", ...FAST_POLL })) as GenerateWorldError;

    expect(result.code).toBe("unauthorized");
    expect(result.operationId).toBe("op-123");
  });

  it("maps a poll-time 402 to `payment_required`", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(wlResponse(SUBMIT_OK))
        .mockResolvedValueOnce(
          wlResponse({}, { ok: false, status: 402, statusText: "Payment Required" })
        )
    );

    const result = (await generateWorld({ prompt: "x", ...FAST_POLL })) as GenerateWorldError;

    expect(result.code).toBe("payment_required");
  });

  it("maps a lost connection while polling to `network` with a resume hint", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(wlResponse(SUBMIT_OK))
        .mockRejectedValueOnce(new Error("socket hang up"))
    );

    const result = (await generateWorld({ prompt: "x", ...FAST_POLL })) as GenerateWorldError;

    expect(result.code).toBe("network");
    expect(result.operationId).toBe("op-123");
    expect(result.message).toContain('operationId: "op-123"');
  });
});

// ─── Formatting ─────────────────────────────────────────────────────────────

describe("formatGenerateWorldResult", () => {
  it("renders every asset, the coordinate note, and Android + web snippets", async () => {
    process.env[API_KEY_ENV] = "wl_fake_key";
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(wlResponse(SUBMIT_OK))
        .mockResolvedValueOnce(wlResponse(POLL_DONE))
    );
    const result = await generateWorld({ prompt: "A sunlit Kyoto tea house", ...FAST_POLL });

    const text = formatGenerateWorldResult(result);

    expect(text).toContain("## Generated 3D world");
    expect(text).toContain("https://cdn.worldlabs.ai/w/world-abc/100k.spz");
    expect(text).toContain("https://cdn.worldlabs.ai/w/world-abc/collider.glb");
    expect(text).toContain("https://cdn.worldlabs.ai/w/world-abc/pano.png");
    expect(text).toContain("Rotation(x = 180f)");
    expect(text).toContain("SplatParser.parse");
    expect(text).toContain('sv.addSplatNode("https://cdn.worldlabs.ai/w/world-abc/100k.spz")');
    expect(text).toContain("self-host");
    expect(text).toContain("World Labs Marble");
    expect(text).toContain("`op-123`");
    expect(text).not.toContain("wl_fake_key");
  });

  it("omits asset lines that are absent", () => {
    const text = formatGenerateWorldResult({
      ok: true,
      world: {
        operationId: "op-1",
        worldId: null,
        displayName: "",
        model: "marble-1.1",
        quality: null,
        mode: "resumed",
        input: "op-1",
        caption: null,
        splatUrls: { k100: null, k500: null, full: null },
        colliderMeshUrl: "https://cdn/collider.glb",
        panoramaUrl: null,
        thumbnailUrl: null,
        estimatedCredits: null,
        license: "L",
        attribution: "A",
      },
    });

    expect(text).toContain("Resumed operation");
    expect(text).toContain("https://cdn/collider.glb");
    expect(text).not.toContain("Splats, 100k");
    expect(text).not.toContain("panorama (");
    expect(text).not.toContain("credits (price list)");
  });

  it("passes through error messages untouched", () => {
    const text = formatGenerateWorldResult({
      ok: false,
      code: "timeout",
      message: "took too long",
    });
    expect(text).toBe("took too long");
  });
});
