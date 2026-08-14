import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  buildTaskBody,
  formatGenerateResult,
  type GenerateModelError,
  type GenerateModelSuccess,
  generateModel,
} from "./generate-model.js";

// ─── Test helpers ───────────────────────────────────────────────────────────

const API_KEY_ENV = "TRIPO_API_KEY";
const TASK_ENDPOINT = "https://api.tripo3d.ai/v2/openapi/task";

/** Zero-delay polling so tests never wait on real timers. */
const FAST_POLL = { pollIntervalMs: 0 };

function tripoResponse(
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

const SUBMIT_OK = { code: 0, data: { task_id: "task-123" } };

const POLL_RUNNING = {
  code: 0,
  data: { task_id: "task-123", status: "running", progress: 42 },
};

const POLL_SUCCESS = {
  code: 0,
  data: {
    task_id: "task-123",
    status: "success",
    progress: 100,
    consumed_credit: 40,
    output: {
      model: "https://cdn.tripo3d.ai/models/task-123.glb",
      pbr_model: "https://cdn.tripo3d.ai/models/task-123-pbr.glb",
      rendered_image: "https://cdn.tripo3d.ai/renders/task-123.png",
    },
  },
};

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

// ─── Happy path: text → 3D ──────────────────────────────────────────────────

describe("generateModel — happy path (text→3D, fast)", () => {
  it("submits a P1 text_to_model task, polls to success, and returns the GLB URL", async () => {
    process.env[API_KEY_ENV] = "tsk_fake_key";
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(tripoResponse(SUBMIT_OK))
      .mockResolvedValueOnce(tripoResponse(POLL_RUNNING))
      .mockResolvedValueOnce(tripoResponse(POLL_SUCCESS));
    vi.stubGlobal("fetch", fetchMock);

    const result = await generateModel({ prompt: "a low-poly wooden chair", ...FAST_POLL });

    expect(result.ok).toBe(true);
    const model = (result as GenerateModelSuccess).model;
    expect(model.taskId).toBe("task-123");
    // Prefers pbr_model over model.
    expect(model.modelUrl).toBe("https://cdn.tripo3d.ai/models/task-123-pbr.glb");
    expect(model.previewImageUrl).toBe("https://cdn.tripo3d.ai/renders/task-123.png");
    expect(model.quality).toBe("fast");
    expect(model.modelVersion).toBe("P1-20260311");
    expect(model.mode).toBe("text");
    expect(model.input).toBe("a low-poly wooden chair");
    expect(model.creditsConsumed).toBe(40);
    expect(model.license).toContain("Tripo");

    // Submit call: POST to the task endpoint with the Bearer key.
    expect(fetchMock).toHaveBeenCalledTimes(3);
    const [submitUrl, submitInit] = fetchMock.mock.calls[0]!;
    expect(String(submitUrl)).toBe(TASK_ENDPOINT);
    expect((submitInit as RequestInit).method).toBe("POST");
    expect((submitInit as RequestInit).headers).toMatchObject({
      Authorization: "Bearer tsk_fake_key",
    });
    const submitBody = JSON.parse((submitInit as RequestInit).body as string);
    expect(submitBody).toMatchObject({
      type: "text_to_model",
      prompt: "a low-poly wooden chair",
      model_version: "P1-20260311",
      texture: true,
      pbr: true,
    });
    // fast tier does NOT request the hd add-ons.
    expect(submitBody.quad).toBeUndefined();
    expect(submitBody.geometry_quality).toBeUndefined();

    // Poll calls: GET {endpoint}/{task_id}.
    expect(String(fetchMock.mock.calls[1]![0])).toBe(`${TASK_ENDPOINT}/task-123`);
    expect(String(fetchMock.mock.calls[2]![0])).toBe(`${TASK_ENDPOINT}/task-123`);
  });

  it("falls back to output.model when pbr_model is absent", async () => {
    process.env[API_KEY_ENV] = "tsk_fake_key";
    const success = {
      code: 0,
      data: {
        status: "success",
        output: { model: "https://cdn.tripo3d.ai/models/only.glb" },
      },
    };
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(tripoResponse(SUBMIT_OK))
        .mockResolvedValueOnce(tripoResponse(success))
    );

    const result = await generateModel({ prompt: "a mug", ...FAST_POLL });
    expect(result.ok).toBe(true);
    expect((result as GenerateModelSuccess).model.modelUrl).toBe(
      "https://cdn.tripo3d.ai/models/only.glb"
    );
    expect((result as GenerateModelSuccess).model.creditsConsumed).toBeNull();
  });
});

// ─── Happy path: quality = hd ────────────────────────────────────────────────

describe("generateModel — quality=hd (H3.1)", () => {
  it("submits an H3.1 task with quad topology and detailed geometry/texture", async () => {
    process.env[API_KEY_ENV] = "tsk_fake_key";
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(tripoResponse(SUBMIT_OK))
      .mockResolvedValueOnce(tripoResponse(POLL_SUCCESS));
    vi.stubGlobal("fetch", fetchMock);

    const result = await generateModel({
      prompt: "a detailed bronze statue",
      quality: "hd",
      ...FAST_POLL,
    });

    expect(result.ok).toBe(true);
    expect((result as GenerateModelSuccess).model.quality).toBe("hd");
    expect((result as GenerateModelSuccess).model.modelVersion).toBe("v3.1-20260211");

    const submitBody = JSON.parse((fetchMock.mock.calls[0]![1] as RequestInit).body as string);
    expect(submitBody).toMatchObject({
      type: "text_to_model",
      model_version: "v3.1-20260211",
      quad: true,
      geometry_quality: "detailed",
      texture_quality: "detailed",
    });
  });
});

// ─── Happy path: image → 3D ─────────────────────────────────────────────────

describe("generateModel — image→3D", () => {
  it("submits an image_to_model task with the file url + type hint", async () => {
    process.env[API_KEY_ENV] = "tsk_fake_key";
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(tripoResponse(SUBMIT_OK))
      .mockResolvedValueOnce(tripoResponse(POLL_SUCCESS));
    vi.stubGlobal("fetch", fetchMock);

    const result = await generateModel({
      imageUrl: "https://example.com/photos/chair.png?size=large",
      ...FAST_POLL,
    });

    expect(result.ok).toBe(true);
    expect((result as GenerateModelSuccess).model.mode).toBe("image");
    expect((result as GenerateModelSuccess).model.input).toBe(
      "https://example.com/photos/chair.png?size=large"
    );

    const submitBody = JSON.parse((fetchMock.mock.calls[0]![1] as RequestInit).body as string);
    expect(submitBody).toMatchObject({
      type: "image_to_model",
      file: { type: "png", url: "https://example.com/photos/chair.png?size=large" },
    });
    expect(submitBody.prompt).toBeUndefined();
  });
});

// ─── buildTaskBody ──────────────────────────────────────────────────────────

describe("buildTaskBody — image type hints", () => {
  it.each([
    ["https://x.com/a.png", "png"],
    ["https://x.com/a.webp", "webp"],
    ["https://x.com/a.jpeg", "jpeg"],
    ["https://x.com/a.jpg", "jpg"],
    ["https://x.com/a", "jpg"],
  ])("derives %s → %s", (url, expected) => {
    const body = buildTaskBody({ imageUrl: url, quality: "fast" });
    expect((body.file as { type: string }).type).toBe(expected);
  });
});

// ─── Missing key ────────────────────────────────────────────────────────────

describe("generateModel — missing API key", () => {
  it("returns actionable BYOK instructions when TRIPO_API_KEY is not set", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const result = await generateModel({ prompt: "a robot", ...FAST_POLL });

    expect(result.ok).toBe(false);
    const error = result as GenerateModelError;
    expect(error.code).toBe("missing_key");
    expect(error.message).toContain("TRIPO_API_KEY");
    expect(error.message).toContain("platform.tripo3d.ai/api-keys");
    expect(error.message).toContain("mcpServers");
    // No network call should be made.
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("returns missing_key when the env var is blank", async () => {
    process.env[API_KEY_ENV] = "   ";
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const result = await generateModel({ prompt: "a robot", ...FAST_POLL });
    expect((result as GenerateModelError).code).toBe("missing_key");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

// ─── Input validation ───────────────────────────────────────────────────────

describe("generateModel — input validation", () => {
  it("rejects a call with neither prompt nor imageUrl, before any network", async () => {
    process.env[API_KEY_ENV] = "tsk_fake_key";
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const result = await generateModel({ ...FAST_POLL });
    expect(result.ok).toBe(false);
    expect((result as GenerateModelError).code).toBe("invalid_input");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejects a call with BOTH prompt and imageUrl", async () => {
    process.env[API_KEY_ENV] = "tsk_fake_key";
    vi.stubGlobal("fetch", vi.fn());

    const result = await generateModel({
      prompt: "a chair",
      imageUrl: "https://example.com/chair.jpg",
      ...FAST_POLL,
    });
    expect((result as GenerateModelError).code).toBe("invalid_input");
    expect((result as GenerateModelError).message).toContain("not both");
  });

  it("rejects prompts above Tripo's 1024-character limit", async () => {
    process.env[API_KEY_ENV] = "tsk_fake_key";
    vi.stubGlobal("fetch", vi.fn());

    const result = await generateModel({ prompt: "x".repeat(1025), ...FAST_POLL });
    expect((result as GenerateModelError).code).toBe("invalid_input");
    expect((result as GenerateModelError).message).toContain("1024");
  });

  it("rejects a non-http imageUrl", async () => {
    process.env[API_KEY_ENV] = "tsk_fake_key";
    vi.stubGlobal("fetch", vi.fn());

    const result = await generateModel({ imageUrl: "file:///tmp/cat.png", ...FAST_POLL });
    expect((result as GenerateModelError).code).toBe("invalid_input");
  });
});

// ─── Submit-time API errors ─────────────────────────────────────────────────

describe("generateModel — submit errors", () => {
  it("maps HTTP 401/403 to `unauthorized`", async () => {
    process.env[API_KEY_ENV] = "tsk_bad_key";
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          tripoResponse({}, { ok: false, status: 401, statusText: "Unauthorized" })
        )
    );

    const result = await generateModel({ prompt: "a robot", ...FAST_POLL });
    expect((result as GenerateModelError).code).toBe("unauthorized");
    expect((result as GenerateModelError).message).toMatch(/401/);
  });

  it("maps HTTP 429 to `rate_limited`", async () => {
    process.env[API_KEY_ENV] = "tsk_fake_key";
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          tripoResponse({}, { ok: false, status: 429, statusText: "Too Many Requests" })
        )
    );

    const result = await generateModel({ prompt: "a robot", ...FAST_POLL });
    expect((result as GenerateModelError).code).toBe("rate_limited");
    expect((result as GenerateModelError).message).toMatch(/rate limit/i);
  });

  it("maps fetch rejections to `network`", async () => {
    process.env[API_KEY_ENV] = "tsk_fake_key";
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("ECONNREFUSED")));

    const result = await generateModel({ prompt: "a robot", ...FAST_POLL });
    expect((result as GenerateModelError).code).toBe("network");
    expect((result as GenerateModelError).message).toContain("ECONNREFUSED");
  });

  it("surfaces a non-zero Tripo envelope code with its message and suggestion", async () => {
    process.env[API_KEY_ENV] = "tsk_fake_key";
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          tripoResponse({ code: 2010, message: "insufficient credits", suggestion: "top up" })
        )
    );

    const result = await generateModel({ prompt: "a robot", ...FAST_POLL });
    const err = result as GenerateModelError;
    expect(err.code).toBe("bad_response");
    expect(err.message).toContain("2010");
    expect(err.message).toContain("insufficient credits");
    expect(err.message).toContain("top up");
  });

  it("maps a missing task_id to `bad_response`", async () => {
    process.env[API_KEY_ENV] = "tsk_fake_key";
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(tripoResponse({ code: 0, data: {} })));

    const result = await generateModel({ prompt: "a robot", ...FAST_POLL });
    expect((result as GenerateModelError).code).toBe("bad_response");
    expect((result as GenerateModelError).message).toContain("task_id");
  });

  it("maps submit JSON parse failures to `bad_response`", async () => {
    process.env[API_KEY_ENV] = "tsk_fake_key";
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => {
          throw new Error("Unexpected token");
        },
      } as unknown as Response)
    );

    const result = await generateModel({ prompt: "a robot", ...FAST_POLL });
    expect((result as GenerateModelError).code).toBe("bad_response");
  });
});

// ─── Task failure ───────────────────────────────────────────────────────────

describe("generateModel — task failure", () => {
  it.each(["failed", "banned", "expired", "cancelled", "unknown"])(
    "surfaces finalized status %s as `task_failed`",
    async (status) => {
      process.env[API_KEY_ENV] = "tsk_fake_key";
      vi.stubGlobal(
        "fetch",
        vi
          .fn()
          .mockResolvedValueOnce(tripoResponse(SUBMIT_OK))
          .mockResolvedValueOnce(tripoResponse({ code: 0, data: { status } }))
      );

      const result = await generateModel({ prompt: "a robot", ...FAST_POLL });
      const err = result as GenerateModelError;
      expect(err.code).toBe("task_failed");
      expect(err.message).toContain("task-123");
      expect(err.message).toContain(status);
    }
  );

  it("returns bad_response when a successful task carries no model URL", async () => {
    process.env[API_KEY_ENV] = "tsk_fake_key";
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(tripoResponse(SUBMIT_OK))
        .mockResolvedValueOnce(tripoResponse({ code: 0, data: { status: "success", output: {} } }))
    );

    const result = await generateModel({ prompt: "a robot", ...FAST_POLL });
    expect((result as GenerateModelError).code).toBe("bad_response");
    expect((result as GenerateModelError).message).toContain("no model URL");
  });
});

// ─── Poll timeout ───────────────────────────────────────────────────────────

describe("generateModel — poll timeout", () => {
  it("returns `timeout` when the task never finalizes within the deadline", async () => {
    process.env[API_KEY_ENV] = "tsk_fake_key";
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(tripoResponse(SUBMIT_OK))
        .mockResolvedValue(tripoResponse(POLL_RUNNING))
    );

    const result = await generateModel({
      prompt: "a robot",
      pollIntervalMs: 0,
      timeoutMs: 0, // deadline hits after the first poll
    });

    const err = result as GenerateModelError;
    expect(err.code).toBe("timeout");
    expect(err.message).toContain("task-123");
    expect(err.message).toContain('"running"');
    expect(err.message).toContain("42%");
    expect(err.message).toMatch(/credits may be consumed/i);
  });

  it("tolerates transient poll failures (5xx) and still succeeds within the deadline", async () => {
    process.env[API_KEY_ENV] = "tsk_fake_key";
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(tripoResponse(SUBMIT_OK))
        .mockResolvedValueOnce(
          tripoResponse({}, { ok: false, status: 502, statusText: "Bad Gateway" })
        )
        .mockResolvedValueOnce(tripoResponse(POLL_SUCCESS))
    );

    const result = await generateModel({ prompt: "a robot", pollIntervalMs: 0, timeoutMs: 5_000 });
    expect(result.ok).toBe(true);
  });

  it("maps an unauthorized poll response to `unauthorized` (key revoked mid-task)", async () => {
    process.env[API_KEY_ENV] = "tsk_fake_key";
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(tripoResponse(SUBMIT_OK))
        .mockResolvedValueOnce(
          tripoResponse({}, { ok: false, status: 403, statusText: "Forbidden" })
        )
    );

    const result = await generateModel({ prompt: "a robot", ...FAST_POLL });
    expect((result as GenerateModelError).code).toBe("unauthorized");
  });
});

// ─── formatGenerateResult ───────────────────────────────────────────────────

describe("formatGenerateResult", () => {
  it("renders a markdown block with the GLB URL, expiry warning, and next steps", () => {
    const text = formatGenerateResult({
      ok: true,
      model: {
        taskId: "task-123",
        modelUrl: "https://cdn.tripo3d.ai/models/task-123-pbr.glb",
        previewImageUrl: "https://cdn.tripo3d.ai/renders/task-123.png",
        quality: "fast",
        modelVersion: "P1-20260311",
        mode: "text",
        input: "a low-poly wooden chair",
        creditsConsumed: 40,
        license: "Generated with your own Tripo API key.",
        attribution: "Tripo AI (https://www.tripo3d.ai)",
      },
    });

    expect(text).toContain("https://cdn.tripo3d.ai/models/task-123-pbr.glb");
    expect(text).toContain("expires ~5 minutes");
    expect(text).toContain("a low-poly wooden chair");
    expect(text).toContain("P1-20260311");
    expect(text).toContain("task-123");
    expect(text).toContain("rememberModelInstance");
    expect(text).toContain("Tripo AI");
  });

  it("omits the preview and credits lines when absent", () => {
    const text = formatGenerateResult({
      ok: true,
      model: {
        taskId: "t",
        modelUrl: "https://cdn.tripo3d.ai/m.glb",
        previewImageUrl: "",
        quality: "hd",
        modelVersion: "v3.1-20260211",
        mode: "image",
        input: "https://example.com/chair.jpg",
        creditsConsumed: null,
        license: "x",
        attribution: "y",
      },
    });
    expect(text).not.toContain("**Preview:**");
    expect(text).not.toContain("credits consumed");
    expect(text).toContain("Source image");
  });

  it("passes through error messages untouched", () => {
    const err: GenerateModelError = {
      ok: false,
      code: "task_failed",
      message: "Tripo task t finalized with status failed.",
    };
    expect(formatGenerateResult(err)).toBe("Tripo task t finalized with status failed.");
  });
});
