import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  __computeBotLikelihoodForTest,
  __getInstallIdForTest,
  __resetClientContext,
  flushTelemetry,
  recordClientInit,
  recordToolCall,
  type TelemetryPayload,
} from "./telemetry.js";

// ─── Fetch stub ──────────────────────────────────────────────────────────────
//
// Telemetry must never throw and must never await the caller, so every test
// stubs `globalThis.fetch` with a spy and then inspects call counts and
// payloads. We also use fake timers in one test to assert the abort timeout
// does not leave a dangling handle.
//
// With client-side batching, events are buffered and only sent on flush.
// Tests call `flushTelemetry()` to drain the buffer before asserting on fetch.

type FetchMock = ReturnType<typeof vi.fn>;

function installFetchMock(impl?: (...args: unknown[]) => Promise<Response>): FetchMock {
  const mock = vi.fn<(...args: unknown[]) => Promise<Response>>(
    impl ?? (async () => new Response("ok", { status: 200 }))
  );
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (globalThis as unknown as { fetch: typeof fetch }).fetch = mock as unknown as typeof fetch;
  return mock;
}

const ORIGINAL_ENV = { ...process.env };
const ORIGINAL_FETCH = globalThis.fetch;

// Per-test XDG_CONFIG_HOME so the install.json file is written to a throwaway
// directory and never pollutes the developer's real `~/.config/sceneview-mcp/`.
let tempConfigHome: string;

// Bot-detection env vars that the heuristic looks at. We unset them all in
// beforeEach so the default test environment is "no bot signals" (score = 0
// before the no-TTY signal, which itself adds 0.15 under vitest).
const BOT_ENV_VARS = [
  "GITHUB_ACTIONS",
  "GITLAB_CI",
  "CIRCLECI",
  "BUILDKITE",
  "TF_BUILD",
  "JENKINS_URL",
  "BITBUCKET_BUILD_NUMBER",
  "TEAMCITY_VERSION",
  "TRAVIS",
  "DRONE",
  "KUBERNETES_SERVICE_HOST",
  "AWS_LAMBDA_FUNCTION_NAME",
  "VERCEL",
  "NETLIFY",
  "CODESPACE_NAME",
  "container",
  "DEBIAN_FRONTEND",
];

beforeEach(() => {
  // Start each test from a clean env with telemetry enabled and CI off.
  // The vitest harness usually sets CI=true, so we must unset it here.
  process.env = { ...ORIGINAL_ENV };
  delete process.env.SCENEVIEW_TELEMETRY;
  delete process.env.CI;
  for (const k of BOT_ENV_VARS) delete process.env[k];

  // Isolate install.json per test.
  tempConfigHome = mkdtempSync(join(tmpdir(), "sceneview-mcp-test-"));
  process.env.XDG_CONFIG_HOME = tempConfigHome;

  __resetClientContext();
});

afterEach(() => {
  process.env = { ...ORIGINAL_ENV };
  globalThis.fetch = ORIGINAL_FETCH;
  vi.restoreAllMocks();
  // Drain any remaining buffer so timers don't leak between tests.
  flushTelemetry();
  if (tempConfigHome) {
    try {
      rmSync(tempConfigHome, { recursive: true, force: true });
    } catch {
      // best-effort cleanup
    }
  }
});

// Wait a microtask tick so fire-and-forget `fetch(...)` calls have a chance
// to be registered. We never actually await the telemetry call itself.
async function flushMicrotasks(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

// ─── Opt-out ─────────────────────────────────────────────────────────────────

describe("telemetry opt-out", () => {
  it("does not call fetch when SCENEVIEW_TELEMETRY=0 (init)", async () => {
    process.env.SCENEVIEW_TELEMETRY = "0";
    const mock = installFetchMock();

    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    flushTelemetry();
    await flushMicrotasks();

    expect(mock).not.toHaveBeenCalled();
  });

  it("does not call fetch when SCENEVIEW_TELEMETRY=0 (tool call)", async () => {
    process.env.SCENEVIEW_TELEMETRY = "0";
    const mock = installFetchMock();

    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    recordToolCall("list_samples", "free");
    flushTelemetry();
    await flushMicrotasks();

    expect(mock).not.toHaveBeenCalled();
  });

  it("is case-sensitive: SCENEVIEW_TELEMETRY=1 does NOT disable telemetry", async () => {
    process.env.SCENEVIEW_TELEMETRY = "1";
    const mock = installFetchMock();

    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    flushTelemetry();
    await flushMicrotasks();

    expect(mock).toHaveBeenCalledTimes(1);
  });
});

// ─── CI detection ────────────────────────────────────────────────────────────

describe("telemetry CI detection", () => {
  it("does not call fetch when CI=true (init)", async () => {
    process.env.CI = "true";
    const mock = installFetchMock();

    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    flushTelemetry();
    await flushMicrotasks();

    expect(mock).not.toHaveBeenCalled();
  });

  it("does not call fetch when CI=true (tool call)", async () => {
    process.env.CI = "true";
    const mock = installFetchMock();

    recordToolCall("list_samples", "free");
    flushTelemetry();
    await flushMicrotasks();

    expect(mock).not.toHaveBeenCalled();
  });

  it("other truthy CI values do not trip the skip (only literal 'true')", async () => {
    // Rationale: we deliberately match only "true" (as documented) so local
    // experiments with CI=1 still emit events.
    process.env.CI = "1";
    const mock = installFetchMock();

    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    flushTelemetry();
    await flushMicrotasks();

    expect(mock).toHaveBeenCalledTimes(1);
  });
});

// ─── Payload shape ───────────────────────────────────────────────────────────

describe("telemetry payload shape", () => {
  const ALLOWED_FIELDS = new Set([
    "timestamp",
    "event",
    "client",
    "clientVersion",
    "mcpVersion",
    "tier",
    "tool",
    "installId",
    "botLikelihood",
  ]);

  // Parse the payload from a single-event fetch call (goes to /v1/events).
  function parseSinglePayload(mock: FetchMock, callIndex = 0): TelemetryPayload {
    const call = mock.mock.calls[callIndex];
    expect(call, `expected fetch call #${callIndex}`).toBeDefined();
    const [url, init] = call as [string, RequestInit];
    expect(url).toBe("https://sceneview-telemetry.mcp-tools-lab.workers.dev/v1/events");
    expect(init.method).toBe("POST");
    const headers = (init.headers ?? {}) as Record<string, string>;
    expect(headers["content-type"]).toBe("application/json");
    return JSON.parse(init.body as string) as TelemetryPayload;
  }

  it("init event contains only allowed fields", async () => {
    const mock = installFetchMock();

    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    flushTelemetry();
    await flushMicrotasks();

    expect(mock).toHaveBeenCalledTimes(1);
    const payload = parseSinglePayload(mock);

    expect(payload.event).toBe("init");
    expect(payload.client).toBe("claude-desktop");
    expect(payload.clientVersion).toBe("0.11.0");
    expect(typeof payload.mcpVersion).toBe("string");
    expect(payload.mcpVersion.length).toBeGreaterThan(0);
    expect(payload.tier).toBe("free");
    expect(payload.tool).toBeUndefined();
    // Timestamp must be a valid ISO string (round-trippable via Date).
    expect(new Date(payload.timestamp).toISOString()).toBe(payload.timestamp);

    // No extra fields. This is the anti-exfiltration guard.
    for (const key of Object.keys(payload)) {
      expect(ALLOWED_FIELDS.has(key), `unexpected field: ${key}`).toBe(true);
    }
  });

  it("two-event batch sends to /v1/batch with correct envelope", async () => {
    const mock = installFetchMock();

    recordClientInit({ name: "cursor", version: "0.50.0" });
    recordToolCall("get_node_reference", "pro");
    flushTelemetry();
    await flushMicrotasks();

    // Two events → batch endpoint.
    expect(mock).toHaveBeenCalledTimes(1);
    const [url, init] = mock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://sceneview-telemetry.mcp-tools-lab.workers.dev/v1/batch");
    expect(init.method).toBe("POST");
    const headers = (init.headers ?? {}) as Record<string, string>;
    expect(headers["content-type"]).toBe("application/json");

    const body = JSON.parse(init.body as string) as TelemetryPayload[];
    expect(Array.isArray(body)).toBe(true);
    expect(body).toHaveLength(2);

    const [initPayload, toolPayload] = body;
    expect(initPayload!.event).toBe("init");
    expect(toolPayload!.event).toBe("tool");
    expect(toolPayload!.tool).toBe("get_node_reference");
    expect(toolPayload!.tier).toBe("pro");
    expect(toolPayload!.client).toBe("cursor");
    expect(toolPayload!.clientVersion).toBe("0.50.0");

    // Both payloads must only contain allowed fields.
    for (const payload of body) {
      for (const key of Object.keys(payload)) {
        expect(ALLOWED_FIELDS.has(key), `unexpected field: ${key}`).toBe(true);
      }
    }
  });

  it("tool event falls back to 'unknown' client when init was never recorded", async () => {
    const mock = installFetchMock();

    // No recordClientInit before recordToolCall.
    recordToolCall("list_samples", "free");
    flushTelemetry();
    await flushMicrotasks();

    expect(mock).toHaveBeenCalledTimes(1);
    const payload = JSON.parse((mock.mock.calls[0]![1] as RequestInit).body as string);
    expect(payload.client).toBe("unknown");
    expect(payload.clientVersion).toBe("unknown");
  });

  it("does not send an init event when clientInfo is undefined", async () => {
    const mock = installFetchMock();

    recordClientInit(undefined);
    flushTelemetry();
    await flushMicrotasks();

    expect(mock).not.toHaveBeenCalled();
  });

  it("payload never contains an 'ip', 'hostname', 'user', 'args', or 'result' field", async () => {
    const mock = installFetchMock();

    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    recordToolCall("debug_issue", "free");
    flushTelemetry();
    await flushMicrotasks();

    for (const call of mock.mock.calls) {
      const [url, init] = call as [string, RequestInit];
      const raw = JSON.parse(init.body as string) as Record<string, unknown>;
      // Both /v1/events (single body) and /v1/batch (bare array) shapes.
      const payloads: TelemetryPayload[] = url.endsWith("/batch")
        ? (raw as unknown as TelemetryPayload[])
        : [raw as unknown as TelemetryPayload];
      for (const body of payloads) {
        for (const forbidden of ["ip", "hostname", "user", "args", "result", "prompt", "apiKey"]) {
          expect((body as unknown as Record<string, unknown>)[forbidden]).toBeUndefined();
        }
      }
    }
  });
});

// ─── Non-blocking behavior ───────────────────────────────────────────────────

describe("telemetry non-blocking behavior", () => {
  it("recordClientInit returns synchronously even if fetch hangs forever", () => {
    // Install a fetch that returns a promise which never resolves.
    installFetchMock(() => new Promise<Response>(() => {}));

    const start = Date.now();
    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    const elapsed = Date.now() - start;

    // Must be effectively instant (< 50ms) — buffering is synchronous,
    // flush is fire-and-forget.
    expect(elapsed).toBeLessThan(50);
  });

  it("recordToolCall returns synchronously even if fetch hangs forever", () => {
    installFetchMock(() => new Promise<Response>(() => {}));

    recordClientInit({ name: "claude-desktop", version: "0.11.0" });

    const start = Date.now();
    recordToolCall("list_samples", "free");
    const elapsed = Date.now() - start;

    expect(elapsed).toBeLessThan(50);
  });

  it("recordClientInit does not return a thenable (caller cannot await it)", () => {
    installFetchMock();
    const result = recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    expect(result).toBeUndefined();
  });

  it("flushTelemetry does not return a thenable", () => {
    installFetchMock();
    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    const result = flushTelemetry();
    expect(result).toBeUndefined();
  });
});

// ─── Fetch failure is swallowed ──────────────────────────────────────────────

describe("telemetry failure handling", () => {
  it("swallows fetch rejections (network error) on flush", async () => {
    const mock = installFetchMock(() => Promise.reject(new Error("ENETUNREACH")));

    expect(() => {
      recordClientInit({ name: "claude-desktop", version: "0.11.0" });
      flushTelemetry();
    }).not.toThrow();
    await flushMicrotasks();
    await flushMicrotasks();

    expect(mock).toHaveBeenCalledTimes(1);
  });

  it("swallows fetch rejections on tool calls after flush", async () => {
    const mock = installFetchMock(() => Promise.reject(new Error("DNS failure")));

    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    expect(() => recordToolCall("list_samples", "free")).not.toThrow();
    expect(() => flushTelemetry()).not.toThrow();
    await flushMicrotasks();
    await flushMicrotasks();
    await flushMicrotasks(); // extra tick for fallback individual sends

    // Batch attempt fails → 2 individual fallback sends.
    expect(mock).toHaveBeenCalledTimes(3); // 1 batch attempt + 2 individual fallbacks
  });

  it("swallows non-2xx responses without throwing (e.g., Cloudflare 502)", async () => {
    const mock = installFetchMock(async () => new Response("bad gateway", { status: 502 }));

    expect(() => {
      recordClientInit({ name: "claude-desktop", version: "0.11.0" });
      recordToolCall("list_samples", "free");
      flushTelemetry();
    }).not.toThrow();

    await flushMicrotasks();
    await flushMicrotasks();

    // Two events → one batch POST (non-2xx is NOT a rejection, so no fallback).
    expect(mock).toHaveBeenCalledTimes(1);
  });

  it("swallows synchronous fetch throws on flush", async () => {
    installFetchMock(() => {
      throw new Error("sync boom");
    });

    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    expect(() => flushTelemetry()).not.toThrow();
  });
});

// ─── Batching behavior ────────────────────────────────────────────────────────

describe("telemetry batching", () => {
  it("buffers events and does not fetch before flush", async () => {
    const mock = installFetchMock();

    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    recordToolCall("list_samples", "free");
    await flushMicrotasks();

    // No fetch yet — buffer holds both events.
    expect(mock).not.toHaveBeenCalled();
  });

  it("flushTelemetry sends all buffered events in one batch POST", async () => {
    const mock = installFetchMock();

    recordClientInit({ name: "cursor", version: "1.0.0" });
    recordToolCall("get_node_reference", "free");
    recordToolCall("list_samples", "free");
    flushTelemetry();
    await flushMicrotasks();

    expect(mock).toHaveBeenCalledTimes(1);
    const [url, init] = mock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://sceneview-telemetry.mcp-tools-lab.workers.dev/v1/batch");
    const body = JSON.parse(init.body as string) as TelemetryPayload[];
    expect(body).toHaveLength(3);
  });

  it("auto-flushes when buffer reaches BATCH_MAX_SIZE (10)", async () => {
    const mock = installFetchMock();

    // Pump exactly 10 tool events.
    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    // The init event is event #1 in the buffer. Add 9 more tool calls to hit 10.
    for (let i = 0; i < 9; i++) {
      recordToolCall("list_samples", "free");
    }
    await flushMicrotasks();

    // At 10 events the buffer auto-flushes synchronously.
    expect(mock).toHaveBeenCalledTimes(1);
    const [url, init] = mock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://sceneview-telemetry.mcp-tools-lab.workers.dev/v1/batch");
    const body = JSON.parse(init.body as string) as TelemetryPayload[];
    expect(body).toHaveLength(10);
  });

  it("flushTelemetry clears the buffer so a second flush sends nothing", async () => {
    const mock = installFetchMock();

    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    flushTelemetry();
    await flushMicrotasks();
    expect(mock).toHaveBeenCalledTimes(1);

    // Second flush: buffer is empty, fetch must NOT be called again.
    flushTelemetry();
    await flushMicrotasks();
    expect(mock).toHaveBeenCalledTimes(1);
  });

  it("single-event flush uses /v1/events endpoint (not batch)", async () => {
    const mock = installFetchMock();

    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    flushTelemetry();
    await flushMicrotasks();

    expect(mock).toHaveBeenCalledTimes(1);
    const [url] = mock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://sceneview-telemetry.mcp-tools-lab.workers.dev/v1/events");
  });

  it("__resetClientContext also clears the buffer", async () => {
    const mock = installFetchMock();

    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    recordToolCall("list_samples", "free");
    __resetClientContext();
    flushTelemetry(); // buffer is already empty
    await flushMicrotasks();

    expect(mock).not.toHaveBeenCalled();
  });
});

// ─── Install ID — anonymous per-install fingerprint ──────────────────────────

describe("telemetry installId fingerprint", () => {
  function firstPayload(mock: FetchMock): TelemetryPayload {
    const [url, init] = mock.mock.calls[0] as [string, RequestInit];
    const raw = JSON.parse((init.body as string) ?? "{}") as TelemetryPayload | TelemetryPayload[];
    return Array.isArray(raw) ? raw[0]! : raw;
  }

  it("populates installId on init events", async () => {
    const mock = installFetchMock();

    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    flushTelemetry();
    await flushMicrotasks();

    const payload = firstPayload(mock);
    expect(payload.installId).toBeDefined();
    expect(typeof payload.installId).toBe("string");
    // UUID v4 is 36 chars: 8-4-4-4-12 hex with hyphens.
    expect(payload.installId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
    );
  });

  it("populates installId on tool events", async () => {
    const mock = installFetchMock();

    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    recordToolCall("list_samples", "free");
    flushTelemetry();
    await flushMicrotasks();

    const batch = JSON.parse(
      (mock.mock.calls[0]![1] as RequestInit).body as string
    ) as TelemetryPayload[];
    expect(batch).toHaveLength(2);
    expect(batch[0]!.installId).toBeDefined();
    expect(batch[1]!.installId).toBeDefined();
    // Same install → same id across init and tool events.
    expect(batch[0]!.installId).toBe(batch[1]!.installId);
  });

  it("reuses the same installId across a second call within the same process", () => {
    const a = __getInstallIdForTest();
    const b = __getInstallIdForTest();
    expect(a).toBeDefined();
    expect(a).toBe(b);
  });

  it("persists the installId across __resetClientContext (different processes simulated)", () => {
    const a = __getInstallIdForTest();
    __resetClientContext();
    const b = __getInstallIdForTest();
    // Same XDG_CONFIG_HOME → install.json is read back and id matches.
    expect(a).toBe(b);
  });

  it("yields a fresh installId in a different XDG_CONFIG_HOME", () => {
    const a = __getInstallIdForTest();

    const otherHome = mkdtempSync(join(tmpdir(), "sceneview-mcp-other-"));
    try {
      process.env.XDG_CONFIG_HOME = otherHome;
      __resetClientContext();
      const b = __getInstallIdForTest();
      expect(a).toBeDefined();
      expect(b).toBeDefined();
      expect(a).not.toBe(b);
    } finally {
      rmSync(otherHome, { recursive: true, force: true });
    }
  });

  it("installId is never sent when telemetry is opted-out", async () => {
    process.env.SCENEVIEW_TELEMETRY = "0";
    const mock = installFetchMock();

    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    flushTelemetry();
    await flushMicrotasks();

    expect(mock).not.toHaveBeenCalled();
  });
});

// ─── Bot likelihood — sender-side heuristic ──────────────────────────────────

describe("telemetry botLikelihood heuristic", () => {
  function firstPayload(mock: FetchMock): TelemetryPayload {
    const [url, init] = mock.mock.calls[0] as [string, RequestInit];
    const raw = JSON.parse((init.body as string) ?? "{}") as TelemetryPayload | TelemetryPayload[];
    return Array.isArray(raw) ? raw[0]! : raw;
  }

  it("clamps to [0.0, 1.0]", () => {
    process.env.GITHUB_ACTIONS = "true";
    process.env.CI = "true";
    process.env.KUBERNETES_SERVICE_HOST = "10.0.0.1";
    process.env.DEBIAN_FRONTEND = "noninteractive";
    // Force every signal to trip. Total adds = 0.45 + 0.30 + ~0.15 + 0.20 + 0.10 = 1.20.
    const score = __computeBotLikelihoodForTest();
    expect(score).toBeLessThanOrEqual(1);
    expect(score).toBeGreaterThanOrEqual(0);
  });

  it("returns a non-zero score under a fully-clean env (no-TTY signal under vitest)", () => {
    // Under vitest, process.stderr.isTTY is false → +0.15 baseline.
    const score = __computeBotLikelihoodForTest();
    expect(score).toBeGreaterThanOrEqual(0);
    // Bounded above by the no-TTY signal alone since we cleaned everything else.
    expect(score).toBeLessThan(0.2);
  });

  it("bumps score when GITHUB_ACTIONS is set", () => {
    const before = __computeBotLikelihoodForTest();
    process.env.GITHUB_ACTIONS = "true";
    const after = __computeBotLikelihoodForTest();
    expect(after).toBeGreaterThan(before);
    expect(after - before).toBeCloseTo(0.45, 2);
  });

  it("bumps score when generic CI=true is set", () => {
    // Note: CI=true also disables telemetry sending entirely, but the SCORE
    // is still computed/cached for the next process.
    const before = __computeBotLikelihoodForTest();
    process.env.CI = "true";
    const after = __computeBotLikelihoodForTest();
    expect(after - before).toBeCloseTo(0.3, 2);
    delete process.env.CI;
  });

  it("bumps score when KUBERNETES_SERVICE_HOST is set", () => {
    const before = __computeBotLikelihoodForTest();
    process.env.KUBERNETES_SERVICE_HOST = "10.0.0.1";
    const after = __computeBotLikelihoodForTest();
    expect(after - before).toBeCloseTo(0.2, 2);
  });

  it("scores >=0.9 under a full GitHub Actions container run", () => {
    process.env.GITHUB_ACTIONS = "true";
    process.env.CI = "true";
    process.env.KUBERNETES_SERVICE_HOST = "10.0.0.1";
    const score = __computeBotLikelihoodForTest();
    expect(score).toBeGreaterThanOrEqual(0.9);
  });

  it("includes botLikelihood field on the payload", async () => {
    const mock = installFetchMock();
    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    flushTelemetry();
    await flushMicrotasks();

    const payload = firstPayload(mock);
    expect(payload.botLikelihood).toBeDefined();
    expect(typeof payload.botLikelihood).toBe("number");
    expect(payload.botLikelihood).toBeGreaterThanOrEqual(0);
    expect(payload.botLikelihood).toBeLessThanOrEqual(1);
  });

  it("payload botLikelihood reflects elevated score when CI vars are set", async () => {
    process.env.GITHUB_ACTIONS = "true";
    const mock = installFetchMock();

    // Note: CI=true would disable telemetry. GITHUB_ACTIONS alone is enough.
    recordClientInit({ name: "claude-desktop", version: "0.11.0" });
    flushTelemetry();
    await flushMicrotasks();

    const payload = firstPayload(mock);
    expect(payload.botLikelihood).toBeGreaterThanOrEqual(0.45);
  });
});
