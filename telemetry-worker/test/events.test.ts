import { describe, it, expect, beforeEach } from "vitest";
import { app } from "../src/index.js";
import { createMockD1 } from "./helpers/mock-d1.js";
import { createMockKV } from "./helpers/mock-kv.js";

function makeEnv() {
  return {
    DB: createMockD1() as unknown as D1Database,
    RL_KV: createMockKV() as unknown as KVNamespace,
    ENVIRONMENT: "test",
  };
}

function validPayload(overrides: Record<string, unknown> = {}) {
  return {
    timestamp: "2026-04-12T10:00:00.000Z",
    event: "tool",
    client: "claude-desktop",
    clientVersion: "1.2.3",
    mcpVersion: "4.0.0-rc.1",
    tier: "free",
    tool: "get_node_info",
    ...overrides,
  };
}

function post(
  path: string,
  body: unknown,
  env: ReturnType<typeof makeEnv>,
  headers: Record<string, string> = {},
) {
  return app.request(
    path,
    {
      method: "POST",
      headers: { "content-type": "application/json", ...headers },
      body: JSON.stringify(body),
    },
    env,
  );
}

describe("POST /v1/events", () => {
  let env: ReturnType<typeof makeEnv>;

  beforeEach(() => {
    env = makeEnv();
  });

  it("accepts a valid tool event", async () => {
    const res = await post("/v1/events", validPayload(), env);
    expect(res.status).toBe(202);

    const json = await res.json();
    expect(json).toEqual({ ok: true });

    // Verify D1 insert
    const db = env.DB as unknown as ReturnType<typeof createMockD1>;
    expect(db._statements).toHaveLength(1);
    expect(db._statements[0].params).toEqual([
      "2026-04-12T10:00:00.000Z",
      "tool",
      "claude-desktop",
      "1.2.3",
      "4.0.0-rc.1",
      "free",
      "get_node_info",
      null, // install_id — not set in validPayload() → bound as null
      null, // bot_likelihood — not set in validPayload() → bound as null
    ]);
  });

  it("accepts a valid init event (no tool field)", async () => {
    const res = await post(
      "/v1/events",
      validPayload({ event: "init", tool: undefined }),
      env,
    );
    expect(res.status).toBe(202);

    const db = env.DB as unknown as ReturnType<typeof createMockD1>;
    expect(db._statements[0].params[6]).toBeNull(); // tool = null
  });

  it("rejects invalid JSON", async () => {
    const res = await app.request("/v1/events", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "not json",
    }, env);
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ error: "invalid_json" });
  });

  it("rejects unknown event type", async () => {
    const res = await post("/v1/events", validPayload({ event: "crash" }), env);
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ error: "invalid_payload" });
  });

  it("rejects unknown tier", async () => {
    const res = await post("/v1/events", validPayload({ tier: "enterprise" }), env);
    expect(res.status).toBe(400);
  });

  it("rejects oversized strings", async () => {
    const res = await post(
      "/v1/events",
      validPayload({ client: "x".repeat(200) }),
      env,
    );
    expect(res.status).toBe(400);
  });

  it("rejects missing required fields", async () => {
    const { client, ...incomplete } = validPayload();
    const res = await post("/v1/events", incomplete, env);
    expect(res.status).toBe(400);
  });

  it("returns 202 with stored:false on D1 failure", async () => {
    const db = env.DB as unknown as ReturnType<typeof createMockD1>;
    db._shouldFail = true;

    const res = await post("/v1/events", validPayload(), env);
    expect(res.status).toBe(202);
    expect(await res.json()).toEqual({ ok: true, stored: false });
  });
});

describe("POST /v1/batch", () => {
  let env: ReturnType<typeof makeEnv>;

  beforeEach(() => {
    env = makeEnv();
  });

  it("accepts a batch of valid events", async () => {
    const batch = [
      validPayload({ tool: "get_node_info" }),
      validPayload({ tool: "search_models", event: "tool" }),
      validPayload({ event: "init", tool: undefined }),
    ];

    const res = await post("/v1/batch", batch, env);
    expect(res.status).toBe(202);
    expect(await res.json()).toEqual({ ok: true, accepted: 3 });
  });

  it("filters out invalid events in batch", async () => {
    const batch = [
      validPayload(),
      { garbage: true }, // invalid
      validPayload({ tool: "other_tool" }),
    ];

    const res = await post("/v1/batch", batch, env);
    expect(res.status).toBe(202);
    expect(await res.json()).toEqual({ ok: true, accepted: 2 });
  });

  it("rejects empty batch", async () => {
    const res = await post("/v1/batch", [], env);
    expect(res.status).toBe(400);
  });

  it("rejects oversized batch (>50)", async () => {
    const batch = Array.from({ length: 51 }, () => validPayload());
    const res = await post("/v1/batch", batch, env);
    expect(res.status).toBe(400);
  });

  it("rejects batch with all invalid events", async () => {
    const batch = [{ garbage: true }, { also: "garbage" }];
    const res = await post("/v1/batch", batch, env);
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ error: "no_valid_events" });
  });
});

describe("Rate limit headers", () => {
  it("POST /v1/events response includes X-RateLimit-Limit: 30 header", async () => {
    const env = makeEnv();
    const res = await post("/v1/events", validPayload(), env);
    expect(res.status).toBe(202);
    expect(res.headers.get("x-ratelimit-limit")).toBe("30");
  });

  it("POST /v1/events response includes X-RateLimit-Remaining header (number >= 0)", async () => {
    const env = makeEnv();
    const res = await post("/v1/events", validPayload(), env);
    expect(res.status).toBe(202);
    const remaining = res.headers.get("x-ratelimit-remaining");
    expect(remaining).not.toBeNull();
    expect(Number(remaining)).toBeGreaterThanOrEqual(0);
  });

  it("429 response includes Retry-After: 60 header", async () => {
    const env = makeEnv();
    // Exhaust the 30-request window
    for (let i = 0; i < 30; i++) {
      await post("/v1/events", validPayload(), env);
    }
    const res = await post("/v1/events", validPayload(), env);
    expect(res.status).toBe(429);
    expect(res.headers.get("retry-after")).toBe("60");
  });

  it("POST /v1/batch response also includes X-RateLimit-Limit header", async () => {
    const env = makeEnv();
    const batch = [validPayload(), validPayload()];
    const res = await post("/v1/batch", batch, env);
    expect(res.status).toBe(202);
    expect(res.headers.get("x-ratelimit-limit")).toBe("30");
  });
});

describe("Rate limiting", () => {
  it("returns 429 after exceeding limit", async () => {
    const env = makeEnv();

    // Simulate 30 prior requests in the KV bucket
    const kv = env.RL_KV as unknown as ReturnType<typeof createMockKV>;
    // We need to fill any matching key — the rate limiter uses hashed IP + minute bucket.
    // Easier: just send 31 requests.
    for (let i = 0; i < 30; i++) {
      const res = await post("/v1/events", validPayload(), env);
      expect(res.status).toBe(202);
    }

    // 31st should be rate-limited
    const res = await post("/v1/events", validPayload(), env);
    expect(res.status).toBe(429);
    expect(await res.json()).toEqual({ error: "rate_limited" });
  });
});

describe("GET /health", () => {
  it("returns 200 with service info", async () => {
    const env = makeEnv();
    const res = await app.request("/health", { method: "GET" }, env);
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({
      ok: true,
      service: "sceneview-telemetry",
      version: "1.0.0",
    });
  });
});

describe("GET /v1/stats", () => {
  it("returns stats structure", async () => {
    const env = makeEnv();
    const db = env.DB as unknown as ReturnType<typeof createMockD1>;
    // Mock the expected row shapes
    db._rows = [{ total: 42, inits: 10, tools: 32, unique_clients: 3 }];

    const res = await app.request("/v1/stats", { method: "GET" }, env);
    expect(res.status).toBe(200);
    const json = await res.json();
    expect(json.period).toBe("24h");
    expect(json.totals).toBeDefined();
  });
});

describe("GET /v1/stats — auth", () => {
  it("returns 200 when no STATS_TOKEN is configured (open access in dev)", async () => {
    const env = makeEnv(); // no STATS_TOKEN
    const res = await app.request("/v1/stats", { method: "GET" }, env);
    expect(res.status).toBe(200);
  });

  it("returns 200 with correct bearer token", async () => {
    const env = { ...makeEnv(), STATS_TOKEN: "secret-token" };
    const res = await app.request(
      "/v1/stats",
      {
        method: "GET",
        headers: { Authorization: "Bearer secret-token" },
      },
      env,
    );
    expect(res.status).toBe(200);
  });

  it("returns 401 with wrong bearer token", async () => {
    const env = { ...makeEnv(), STATS_TOKEN: "secret-token" };
    const res = await app.request(
      "/v1/stats",
      {
        method: "GET",
        headers: { Authorization: "Bearer wrong-token" },
      },
      env,
    );
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ error: "unauthorized" });
  });

  it("returns 401 with missing Authorization header when STATS_TOKEN is set", async () => {
    const env = { ...makeEnv(), STATS_TOKEN: "secret-token" };
    const res = await app.request("/v1/stats", { method: "GET" }, env);
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ error: "unauthorized" });
  });
});

describe("Payload size limits", () => {
  let env: ReturnType<typeof makeEnv>;

  beforeEach(() => {
    env = makeEnv();
  });

  it("rejects POST /v1/events with content-length > 65536 (413)", async () => {
    const res = await post("/v1/events", validPayload(), env, {
      "content-length": "65537",
    });
    expect(res.status).toBe(413);
    expect(await res.json()).toEqual({ error: "payload_too_large" });
  });

  it("rejects POST /v1/batch with content-length > 1048576 (413)", async () => {
    const res = await post("/v1/batch", [validPayload()], env, {
      "content-length": "1048577",
    });
    expect(res.status).toBe(413);
    expect(await res.json()).toEqual({ error: "payload_too_large" });
  });

  it("accepts POST /v1/events with normal-sized payload (202)", async () => {
    const body = JSON.stringify(validPayload());
    const res = await post("/v1/events", validPayload(), env, {
      "content-length": String(body.length),
    });
    expect(res.status).toBe(202);
    expect(await res.json()).toEqual({ ok: true });
  });
});

describe("CORS", () => {
  it("responds to OPTIONS preflight", async () => {
    const env = makeEnv();
    const res = await app.request("/v1/events", {
      method: "OPTIONS",
      headers: {
        Origin: "http://localhost:3000",
        "Access-Control-Request-Method": "POST",
      },
    }, env);
    expect(res.status).toBe(204);
    expect(res.headers.get("access-control-allow-origin")).toBe("*");
  });
});

describe("404 catch-all", () => {
  it("returns 404 for unknown routes", async () => {
    const env = makeEnv();
    const res = await app.request("/unknown", { method: "GET" }, env);
    expect(res.status).toBe(404);
  });
});

describe("GET /v1/timeseries", () => {
  function getTimeseries(
    params: Record<string, string>,
    env: ReturnType<typeof makeEnv> & { STATS_TOKEN?: string },
    headers: Record<string, string> = {},
  ) {
    const qs = new URLSearchParams(params).toString();
    const url = qs ? `/v1/timeseries?${qs}` : "/v1/timeseries";
    return app.request(url, { method: "GET", headers }, env);
  }

  it("returns 200 with default params (days=30, metric=events)", async () => {
    const env = makeEnv();
    const db = env.DB as unknown as ReturnType<typeof createMockD1>;
    db._rows = [{ time: "2026-03-13", value: 5 }];

    const res = await getTimeseries({}, env);
    expect(res.status).toBe(200);

    const json = await res.json() as { metric: string; days: number; data: unknown[] };
    expect(json.metric).toBe("events");
    expect(json.days).toBe(30);
    expect(Array.isArray(json.data)).toBe(true);
  });

  it("returns data for metric=tools", async () => {
    const env = makeEnv();
    const db = env.DB as unknown as ReturnType<typeof createMockD1>;
    db._rows = [{ time: "2026-03-13", label: "get_node_info", value: 10 }];

    const res = await getTimeseries({ metric: "tools" }, env);
    expect(res.status).toBe(200);

    const json = await res.json() as { metric: string; days: number; data: unknown[] };
    expect(json.metric).toBe("tools");
    expect(json.days).toBe(30);
    expect(Array.isArray(json.data)).toBe(true);
  });

  it("returns data for metric=versions", async () => {
    const env = makeEnv();
    const db = env.DB as unknown as ReturnType<typeof createMockD1>;
    db._rows = [{ time: "2026-03-13", label: "4.0.0-rc.1", value: 8 }];

    const res = await getTimeseries({ metric: "versions" }, env);
    expect(res.status).toBe(200);

    const json = await res.json() as { metric: string; days: number; data: unknown[] };
    expect(json.metric).toBe("versions");
    expect(Array.isArray(json.data)).toBe(true);
  });

  it("returns data for metric=clients", async () => {
    const env = makeEnv();
    const db = env.DB as unknown as ReturnType<typeof createMockD1>;
    db._rows = [{ time: "2026-03-13", label: "claude-desktop", value: 3 }];

    const res = await getTimeseries({ metric: "clients" }, env);
    expect(res.status).toBe(200);

    const json = await res.json() as { metric: string; days: number; data: unknown[] };
    expect(json.metric).toBe("clients");
    expect(Array.isArray(json.data)).toBe(true);
  });

  it("rejects invalid metric value → 400", async () => {
    const env = makeEnv();
    const res = await getTimeseries({ metric: "invalid_metric" }, env);
    expect(res.status).toBe(400);

    const json = await res.json() as { error: string; valid: string[] };
    expect(json.error).toBe("invalid_metric");
    expect(Array.isArray(json.valid)).toBe(true);
    expect(json.valid).toContain("events");
  });

  it("clamps days to max 90", async () => {
    const env = makeEnv();
    const db = env.DB as unknown as ReturnType<typeof createMockD1>;
    db._rows = [];

    const res = await getTimeseries({ days: "999" }, env);
    expect(res.status).toBe(200);

    const json = await res.json() as { metric: string; days: number; data: unknown[] };
    expect(json.days).toBe(90);
  });

  it("returns 401 when STATS_TOKEN is set but wrong bearer provided", async () => {
    const env = { ...makeEnv(), STATS_TOKEN: "secret-timeseries" };
    const res = await getTimeseries(
      {},
      env,
      { Authorization: "Bearer wrong-token" },
    );
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ error: "unauthorized" });
  });

  it("returns 200 when STATS_TOKEN is set and correct bearer provided", async () => {
    const env = { ...makeEnv(), STATS_TOKEN: "secret-timeseries" };
    const db = env.DB as unknown as ReturnType<typeof createMockD1>;
    db._rows = [];

    const res = await getTimeseries(
      {},
      env,
      { Authorization: "Bearer secret-timeseries" },
    );
    expect(res.status).toBe(200);

    const json = await res.json() as { metric: string; days: number; data: unknown[] };
    expect(json.metric).toBe("events");
    expect(json.days).toBe(30);
  });
});

// ── GET /v1/export ───────────────────────────────────────────────────────────

function getExport(
  params: Record<string, string>,
  env: ReturnType<typeof makeEnv>,
  headers: Record<string, string> = {},
) {
  const qs = new URLSearchParams(params).toString();
  const url = `/v1/export${qs ? `?${qs}` : ""}`;
  return app.request(url, { method: "GET", headers }, env);
}

describe("GET /v1/export", () => {
  let env: ReturnType<typeof makeEnv>;

  beforeEach(() => {
    env = makeEnv();
  });

  it("returns CSV with correct content-type", async () => {
    const res = await getExport({}, env);
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toContain("text/csv");
  });

  it("returns Content-Disposition attachment header", async () => {
    const res = await getExport({}, env);
    const disposition = res.headers.get("content-disposition") ?? "";
    expect(disposition).toContain("attachment");
    expect(disposition).toContain(".csv");
  });

  it("returns CSV header row", async () => {
    const res = await getExport({}, env);
    const text = await res.text();
    expect(text).toContain("timestamp,event,client,client_ver,mcp_ver,tier,tool");
  });

  it("rejects invalid event_type", async () => {
    const res = await getExport({ event_type: "crash" }, env);
    expect(res.status).toBe(400);
  });

  it("accepts valid event_type=tool", async () => {
    const res = await getExport({ event_type: "tool" }, env);
    expect(res.status).toBe(200);
  });

  it("clamps days to max 30", async () => {
    const res = await getExport({ days: "999" }, env);
    expect(res.status).toBe(200);
    // Verify via filename in Content-Disposition
    const disposition = res.headers.get("content-disposition") ?? "";
    expect(disposition).toContain("30d");
  });

  it("returns 401 with wrong STATS_TOKEN", async () => {
    const authEnv = { ...makeEnv(), STATS_TOKEN: "secret" } as ReturnType<typeof makeEnv>;
    const res = await getExport({}, authEnv, { Authorization: "Bearer wrong" });
    expect(res.status).toBe(401);
  });

  it("returns 200 with correct STATS_TOKEN", async () => {
    const authEnv = { ...makeEnv(), STATS_TOKEN: "secret" } as ReturnType<typeof makeEnv>;
    const res = await getExport({}, authEnv, { Authorization: "Bearer secret" });
    expect(res.status).toBe(200);
  });
});
