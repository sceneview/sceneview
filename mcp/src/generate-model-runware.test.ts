import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  formatGenerateResult,
  type GenerateModelError,
  type GenerateModelSuccess,
  generateModel,
} from "./generate-model.js";
import {
  buildConceptTask,
  buildTrellisTask,
  generateWithTrellis,
  RUNWARE_ENDPOINT,
  TRELLIS_MODEL,
} from "./generate-model-runware.js";

function rw(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: "",
    json: async () => body,
  } as unknown as Response;
}

const CONCEPT_OK = {
  data: [
    {
      taskType: "imageInference",
      taskUUID: "uuid-1",
      imageURL: "https://im.runware.ai/image/concept.jpg",
      cost: 0.0013,
    },
  ],
};
const SUBMIT_OK = { data: [{ taskType: "3dInference", taskUUID: "uuid-2" }] };
const POLL_RUNNING = { data: [{ taskUUID: "uuid-2", status: "processing", progress: 40 }] };
const POLL_SUCCESS = {
  data: [
    {
      taskType: "3dInference",
      taskUUID: "uuid-2",
      status: "success",
      cost: 0.0187,
      outputs: { files: [{ uuid: "f", url: "https://im.runware.ai/3d/model.glb" }] },
    },
  ],
};

function uuids() {
  let n = 0;
  return () => `uuid-${++n}`;
}

function bodyOf(call: unknown[]): Array<Record<string, unknown>> {
  return JSON.parse((call[1] as RequestInit).body as string);
}

let tripo: string | undefined;
let runware: string | undefined;
beforeEach(() => {
  tripo = process.env.TRIPO_API_KEY;
  runware = process.env.RUNWARE_API_KEY;
  delete process.env.TRIPO_API_KEY;
  delete process.env.RUNWARE_API_KEY;
});
afterEach(() => {
  vi.unstubAllGlobals();
  if (tripo === undefined) delete process.env.TRIPO_API_KEY;
  else process.env.TRIPO_API_KEY = tripo;
  if (runware === undefined) delete process.env.RUNWARE_API_KEY;
  else process.env.RUNWARE_API_KEY = runware;
});

describe("task builders", () => {
  it("builds an async TRELLIS.2 task with the tier resolution", () => {
    const task = buildTrellisTask("u", "https://x/i.png", "hd");
    expect(task).toMatchObject({
      taskType: "3dInference",
      taskUUID: "u",
      model: TRELLIS_MODEL,
      deliveryMethod: "async",
      includeCost: true,
      outputFormat: "GLB",
      inputs: { image: "https://x/i.png" },
    });
    expect((task.settings as { resolution: number }).resolution).toBe(1536);
    expect((buildTrellisTask("u", "i", "fast").settings as { resolution: number }).resolution).toBe(
      1024
    );
  });

  it("builds a clean single-object concept prompt", () => {
    const task = buildConceptTask("u", "a red vintage armchair");
    expect(task.taskType).toBe("imageInference");
    expect(task.positivePrompt).toMatch(/^a red vintage armchair, single object, centered/);
  });
});

describe("generateWithTrellis", () => {
  it("text→3D: concept image, submit, poll until success, sums the cost", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(rw(CONCEPT_OK))
      .mockResolvedValueOnce(rw(SUBMIT_OK))
      .mockResolvedValueOnce(rw(POLL_RUNNING))
      .mockResolvedValueOnce(rw(POLL_SUCCESS));
    vi.stubGlobal("fetch", fetchMock);

    const result = await generateWithTrellis({
      apiKey: "k",
      prompt: "a red vintage armchair",
      quality: "fast",
      pollIntervalMs: 0,
      newUuid: uuids(),
    });

    expect(result.ok).toBe(true);
    const m = (result as GenerateModelSuccess).model;
    expect(m.modelUrl).toBe("https://im.runware.ai/3d/model.glb");
    expect(m.previewImageUrl).toBe("https://im.runware.ai/image/concept.jpg");
    expect(m.provider).toBe("trellis");
    expect(m.mode).toBe("text");
    expect(m.costUsd).toBe(0.02);
    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(fetchMock.mock.calls[0][0]).toBe(RUNWARE_ENDPOINT);
    const headers = (fetchMock.mock.calls[0][1] as RequestInit).headers as Record<string, string>;
    expect(headers.Authorization).toBe("Bearer k");
    expect(bodyOf(fetchMock.mock.calls[1])[0].inputs).toEqual({
      image: "https://im.runware.ai/image/concept.jpg",
    });
    expect(bodyOf(fetchMock.mock.calls[2])[0]).toEqual({
      taskType: "getResponse",
      taskUUID: "uuid-2",
    });
  });

  it("image→3D skips the concept image", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(rw({ data: [{ taskUUID: "uuid-1" }] }))
      .mockResolvedValueOnce(rw({ data: [{ ...POLL_SUCCESS.data[0], taskUUID: "uuid-1" }] }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await generateWithTrellis({
      apiKey: "k",
      imageUrl: "https://example.com/chair.jpg",
      quality: "hd",
      pollIntervalMs: 0,
      newUuid: uuids(),
    });

    expect(result.ok).toBe(true);
    const m = (result as GenerateModelSuccess).model;
    expect(m.mode).toBe("image");
    expect(m.previewImageUrl).toBe("");
    expect(m.costUsd).toBe(0.0187);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(bodyOf(fetchMock.mock.calls[0])[0].taskType).toBe("3dInference");
  });

  it("maps a task error to task_failed", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(rw(SUBMIT_OK))
        .mockResolvedValueOnce(
          rw({ errors: [{ code: "inferenceFailed", message: "bad input", taskUUID: "uuid-1" }] })
        )
    );
    const result = await generateWithTrellis({
      apiKey: "k",
      imageUrl: "https://example.com/c.jpg",
      quality: "fast",
      pollIntervalMs: 0,
      newUuid: uuids(),
    });
    expect((result as GenerateModelError).code).toBe("task_failed");
    expect((result as GenerateModelError).message).toContain("bad input");
  });

  it("maps HTTP 401 to unauthorized", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(rw({ errors: [] }, 401)));
    const result = await generateWithTrellis({
      apiKey: "k",
      imageUrl: "https://example.com/c.jpg",
      quality: "fast",
      pollIntervalMs: 0,
    });
    expect((result as GenerateModelError).code).toBe("unauthorized");
  });

  it("times out when the task never finishes", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce(rw(SUBMIT_OK)).mockResolvedValue(rw(POLL_RUNNING))
    );
    const result = await generateWithTrellis({
      apiKey: "k",
      imageUrl: "https://example.com/c.jpg",
      quality: "fast",
      pollIntervalMs: 0,
      timeoutMs: 0,
      newUuid: () => "uuid-2",
    });
    expect((result as GenerateModelError).code).toBe("timeout");
    expect((result as GenerateModelError).message).toContain("40%");
  });
});

describe("generateModel — provider routing", () => {
  it("picks TRELLIS.2 when only RUNWARE_API_KEY is set", async () => {
    process.env.RUNWARE_API_KEY = "rk";
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(rw(CONCEPT_OK))
      .mockResolvedValueOnce(rw(SUBMIT_OK))
      .mockResolvedValue(rw({ data: [{ ...POLL_SUCCESS.data[0], taskUUID: undefined }] }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await generateModel({ prompt: "a robot", pollIntervalMs: 0 });
    expect(result.ok).toBe(true);
    expect((result as GenerateModelSuccess).model.provider).toBe("trellis");
    expect(fetchMock.mock.calls[0][0]).toBe(RUNWARE_ENDPOINT);
  });

  it("keeps Tripo as the default when TRIPO_API_KEY is set", async () => {
    process.env.TRIPO_API_KEY = "tk";
    process.env.RUNWARE_API_KEY = "rk";
    const fetchMock = vi.fn().mockResolvedValue(rw({ code: 1, message: "stop" }, 500));
    vi.stubGlobal("fetch", fetchMock);
    await generateModel({ prompt: "a robot", pollIntervalMs: 0 });
    expect(String(fetchMock.mock.calls[0][0])).toContain("tripo3d.ai");
  });

  it("returns Runware BYOK instructions for provider=trellis without a key", async () => {
    process.env.TRIPO_API_KEY = "tk";
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const result = await generateModel({ prompt: "a robot", provider: "trellis" });
    const error = result as GenerateModelError;
    expect(error.code).toBe("missing_key");
    expect(error.message).toContain("RUNWARE_API_KEY");
    expect(error.message).toContain("my.runware.ai/keys");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("formatGenerateResult — TRELLIS.2", () => {
  it("shows the tier, concept image and cost", () => {
    const text = formatGenerateResult({
      ok: true,
      model: {
        taskId: "uuid-2",
        modelUrl: "https://im.runware.ai/3d/model.glb",
        previewImageUrl: "https://im.runware.ai/image/concept.jpg",
        quality: "fast",
        modelVersion: TRELLIS_MODEL,
        mode: "text",
        input: "a robot",
        creditsConsumed: null,
        costUsd: 0.02,
        provider: "trellis",
        license: "l",
        attribution: "a",
      },
    });
    expect(text).toContain("TRELLIS.2 1024³ on Runware");
    expect(text).toContain("**Concept image:** https://im.runware.ai/image/concept.jpg");
    expect(text).toContain("US$0.02");
    expect(text).not.toContain("Tripo credits");
  });
});
