import { beforeEach, describe, expect, it, vi } from "vitest";
import worker, { decoration, type Env } from "../src/index";

const mock = vi.hoisted(() => ({ stream: vi.fn(), constructor: vi.fn() }));
vi.mock("@anthropic-ai/sdk", () => ({
  default: class {
    messages = { stream: mock.stream };
    constructor(options: unknown) { mock.constructor(options); }
  },
}));

const env: Env = {
  ANTHROPIC_API_KEY: "<set with wrangler secret put>",
  ASK_CLIENT_TAG: "sceneview-ask-ar-v1",
  ASK_RATE_LIMITER: { limit: vi.fn(async () => ({ success: true })) },
};

function request(body: unknown = { question: "How far?", context: { distanceAheadMeters: 2.4 } }) {
  return new Request("https://proxy.example/ask", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Ask-Client": env.ASK_CLIENT_TAG,
      "CF-Connecting-IP": "192.0.2.1",
    },
    body: JSON.stringify(body),
  });
}

function model(deltas = ["About *2.5", " metres*.\nEasy!"], stopReason = "end_turn") {
  return {
    async *[Symbol.asyncIterator]() {
      yield { type: "message_start" };
      yield { type: "content_block_delta", delta: { type: "thinking_delta", thinking: "hidden" } };
      for (const text of deltas) {
        yield { type: "content_block_delta", delta: { type: "text_delta", text } };
      }
    },
    finalMessage: vi.fn(async () => ({ stop_reason: stopReason })),
    abort: vi.fn(),
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(env.ASK_RATE_LIMITER.limit).mockResolvedValue({ success: true });
  mock.stream.mockReturnValue(model());
});

describe("Ask SSE", () => {
  it("forwards only text deltas with JSON escaping and a terminal decoration", async () => {
    const response = await worker.fetch(request(), env);
    expect(response.headers.get("Content-Type")).toContain("text/event-stream");
    expect(response.headers.has("Access-Control-Allow-Origin")).toBe(false);
    expect(await response.text()).toBe(
      'data: {"t":"About *2.5"}\n\n' +
      'data: {"t":" metres*.\\nEasy!"}\n\n' +
      'data: {"done":true,"doodle":"circle","emphasis":"2.5 metres"}\n\n',
    );
    expect(mock.stream).toHaveBeenCalledWith(expect.objectContaining({
      model: "claude-haiku-5-5", max_tokens: 200,
      output_config: { effort: "low" }, thinking: { type: "disabled" },
    }), expect.objectContaining({ signal: expect.any(AbortSignal) }));
    expect(mock.constructor).toHaveBeenCalledWith(expect.objectContaining({ logLevel: "off", maxRetries: 0 }));
  });

  it("delivers the first delta before the model finishes", async () => {
    const stream = model();
    mock.stream.mockReturnValue(stream);
    const response = await worker.fetch(request(), env);
    const reader = response.body!.getReader();
    expect(new TextDecoder().decode((await reader.read()).value)).toContain('"t":"About *2.5"');
    expect(stream.finalMessage).not.toHaveBeenCalled();
    await reader.cancel();
    expect(stream.abort).toHaveBeenCalled();
  });

  it.each(["refusal", "max_tokens"])("reports %s without a done event", async (reason) => {
    mock.stream.mockReturnValue(model(["Sorry"], reason));
    const text = await (await worker.fetch(request(), env)).text();
    expect(text).toContain('data: {"error":');
    expect(text).not.toContain('"done"');
  });

  it("sanitizes SDK failures", async () => {
    mock.stream.mockImplementation(() => { throw new Error("private request details"); });
    expect(await (await worker.fetch(request(), env)).text())
      .toBe('data: {"error":"Live answer unavailable"}\n\n');
  });

  it.each([
    { question: "x".repeat(201) }, { question: " " },
    { question: "Hello", context: { distanceAheadMeters: "2" } },
    { question: "Hello", context: { distanceAheadMeters: -1 } },
  ])("validates inputs before contacting the SDK", async (body) => {
    expect((await worker.fetch(request(body), env)).status).toBe(400);
    expect(mock.stream).not.toHaveBeenCalled();
  });

  it("limits per IP and rejects untagged or browser requests", async () => {
    vi.mocked(env.ASK_RATE_LIMITER.limit).mockResolvedValue({ success: false });
    expect((await worker.fetch(request(), env)).status).toBe(429);
    expect(env.ASK_RATE_LIMITER.limit).toHaveBeenCalledWith({ key: "192.0.2.1" });
    const untagged = request();
    untagged.headers.delete("X-Ask-Client");
    expect((await worker.fetch(untagged, env)).status).toBe(403);
    const browser = request();
    browser.headers.set("Origin", "https://example.com");
    expect((await worker.fetch(browser, env)).status).toBe(403);
    expect(mock.stream).not.toHaveBeenCalled();
  });

  it("rejects oversized actual bodies", async () => {
    expect((await worker.fetch(request({ question: "Hi", padding: "x".repeat(4096) }), env)).status)
      .toBe(400);
  });

  it("chooses deterministic doodles", () => {
    expect(decoration("Try *pasta*. ")).toEqual({ doodle: "underline", emphasis: "pasta" });
    expect(decoration("Hello there!").doodle).toBe("arrow");
    expect(decoration("Try 2 metres.").doodle).toBe("circle");
  });
});
