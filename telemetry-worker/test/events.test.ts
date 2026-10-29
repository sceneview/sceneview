import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import type { MockInstance } from "vitest";
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

// Read routes fail closed: they need STATS_TOKEN configured and a matching
// bearer header. Tests that exercise read-route behaviour use these.
const TEST_TOKEN = "test-stats-token";
const AUTH = { Authorization: `Bearer ${TEST_TOKEN}` };

function makeReadEnv() {
  return { ...makeEnv(), STATS_TOKEN: TEST_TOKEN };
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
    const env = makeReadEnv();
    const db = env.DB as unknown as ReturnType<typeof createMockD1>;
    // Mock the expected row shapes
    db._rows = [{ total: 42, inits: 10, tools: 32, unique_clients: 3 }];

    const res = await app.request("/v1/stats", { method: "GET", headers: AUTH }, env);
    expect(res.status).toBe(200);
    const json = await res.json();
    expect(json.period).toBe("24h");
    expect(json.totals).toBeDefined();
  });
});

describe("GET /v1/stats — auth", () => {
  it("returns 401 when no STATS_TOKEN is configured (fail closed)", async () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    const env = makeEnv(); // no STATS_TOKEN
    const res = await app.request("/v1/stats", { method: "GET" }, env);
    consoleError.mockRestore();
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ error: "unauthorized" });
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

describe("GET /v1/funnel", () => {
  it("returns the usage funnel without a subscription stage", async () => {
    const env = makeReadEnv();
    const db = env.DB as unknown as ReturnType<typeof createMockD1>;
    db._rows = [{ installed: 4, activated: 3, engaged: 2, power_users: 1 }];

    const res = await app.request("/v1/funnel", { method: "GET", headers: AUTH }, env);
    expect(res.status).toBe(200);

    const json = await res.json() as { stages: Array<{ stage: string }> };
    expect(json.stages.map(({ stage }) => stage)).toEqual([
      "installed",
      "activated",
      "engaged",
      "power_users",
    ]);
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
    headers: Record<string, string> = AUTH,
  ) {
    const qs = new URLSearchParams(params).toString();
    const url = qs ? `/v1/timeseries?${qs}` : "/v1/timeseries";
    return app.request(url, { method: "GET", headers }, env);
  }

  it("returns 200 with default params (days=30, metric=events)", async () => {
    const env = makeReadEnv();
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
    const env = makeReadEnv();
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
    const env = makeReadEnv();
    const db = env.DB as unknown as ReturnType<typeof createMockD1>;
    db._rows = [{ time: "2026-03-13", label: "4.0.0-rc.1", value: 8 }];

    const res = await getTimeseries({ metric: "versions" }, env);
    expect(res.status).toBe(200);

    const json = await res.json() as { metric: string; days: number; data: unknown[] };
    expect(json.metric).toBe("versions");
    expect(Array.isArray(json.data)).toBe(true);
  });

  it("returns data for metric=clients", async () => {
    const env = makeReadEnv();
    const db = env.DB as unknown as ReturnType<typeof createMockD1>;
    db._rows = [{ time: "2026-03-13", label: "claude-desktop", value: 3 }];

    const res = await getTimeseries({ metric: "clients" }, env);
    expect(res.status).toBe(200);

    const json = await res.json() as { metric: string; days: number; data: unknown[] };
    expect(json.metric).toBe("clients");
    expect(Array.isArray(json.data)).toBe(true);
  });

  it("rejects invalid metric value → 400", async () => {
    const env = makeReadEnv();
    const res = await getTimeseries({ metric: "invalid_metric" }, env);
    expect(res.status).toBe(400);

    const json = await res.json() as { error: string; valid: string[] };
    expect(json.error).toBe("invalid_metric");
    expect(Array.isArray(json.valid)).toBe(true);
    expect(json.valid).toContain("events");
  });

  it("clamps days to max 90", async () => {
    const env = makeReadEnv();
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
  headers: Record<string, string> = AUTH,
) {
  const qs = new URLSearchParams(params).toString();
  const url = `/v1/export${qs ? `?${qs}` : ""}`;
  return app.request(url, { method: "GET", headers }, env);
}

describe("GET /v1/export", () => {
  let env: ReturnType<typeof makeEnv>;

  beforeEach(() => {
    env = makeReadEnv();
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
    expect(text.split("\n")[0]).toBe("timestamp,event,client,client_ver,mcp_ver,tier,tool,bot_likelihood");
  });

  describe("install_id (#4757)", () => {
    const INSTALL_A = "3f2a9c1e-7b4d-4e8a-9f60-1c2d3e4f5a6b";
    const INSTALL_B = "a1b2c3d4-e5f6-4789-8abc-def012345678";

    function seed(db: ReturnType<typeof createMockD1>) {
      // The mock returns these rows whatever the SELECT asks for, so this also
      // checks that the CSV writer itself drops install_id.
      db._rows = [
        { timestamp: "2026-10-20T10:00:00.000Z", event: "init", client: "claude-desktop", client_ver: "1.2.3", mcp_ver: "4.0.0", tier: "free", tool: null, install_id: INSTALL_A, bot_likelihood: 0.1 },
        { timestamp: "2026-10-20T10:00:01.000Z", event: "tool", client: "claude-desktop", client_ver: "1.2.3", mcp_ver: "4.0.0", tier: "free", tool: "get_node_info", install_id: INSTALL_A, bot_likelihood: null },
        { timestamp: "2026-10-20T10:00:02.000Z", event: "tool", client: "cursor", client_ver: "0.9", mcp_ver: "4.0.0", tier: "pro", tool: "create_scene", install_id: INSTALL_B, bot_likelihood: 0.9 },
        { timestamp: "2026-10-20T10:00:03.000Z", event: "tool", client: "cursor", client_ver: "0.9", mcp_ver: "4.0.0", tier: "pro", tool: "create_scene", install_id: null, bot_likelihood: null },
      ];
    }

    it("does not select install_id from D1", async () => {
      const db = env.DB as unknown as ReturnType<typeof createMockD1>;
      const queries: Record<string, string>[] = [{}, { event_type: "tool" }];
      for (const params of queries) {
        db._statements = [];
        const res = await getExport(params, env);
        expect(res.status).toBe(200);
        expect(db._statements).toHaveLength(1);
        expect(db._statements[0].sql).not.toContain("install_id");
      }
    });

    it("writes no install_id column and no install ID value", async () => {
      const db = env.DB as unknown as ReturnType<typeof createMockD1>;
      seed(db);
      const res = await getExport({}, env);
      const text = await res.text();
      expect(text).not.toContain("install_id");
      expect(text).not.toContain(INSTALL_A);
      expect(text).not.toContain(INSTALL_B);
    });

    it("keeps one row per event with as many fields as the header", async () => {
      const db = env.DB as unknown as ReturnType<typeof createMockD1>;
      seed(db);
      const res = await getExport({}, env);
      const [header, ...rows] = (await res.text()).split("\n");
      const columns = header.split(",").length;
      expect(rows).toHaveLength(4);
      for (const row of rows) expect(row.split(",")).toHaveLength(columns);
      expect(rows[0]).toBe("2026-10-20T10:00:00.000Z,init,claude-desktop,1.2.3,4.0.0,free,,0.1");
      expect(rows[1]).toBe("2026-10-20T10:00:01.000Z,tool,claude-desktop,1.2.3,4.0.0,free,get_node_info,");
    });
  });

  describe("spreadsheet formula injection", () => {
    async function exportRow(fields: { client: string; tool: string; client_ver?: string; mcp_ver?: string }) {
      const db = env.DB as unknown as ReturnType<typeof createMockD1>;
      db._rows = [{
        timestamp: "2026-10-20T10:00:00.000Z",
        event: "tool",
        client_ver: "1.0",
        mcp_ver: "4.0.0",
        tier: "free",
        bot_likelihood: null,
        ...fields,
      }];
      const res = await getExport({}, env);
      return (await res.text()).split("\n").slice(1).join("\n");
    }

    it.each([
      ["=", "=1+1", "'=1+1"],
      ["+", "+1+1", "'+1+1"],
      ["-", "-1+1", "'-1+1"],
      ["@", "@SUM(A1)", "'@SUM(A1)"],
      ["a tab", "\t=1+1", "'\t=1+1"],
    ])("prefixes a field that starts with %s with an apostrophe", async (_label, value, expected) => {
      const row = await exportRow({ client: value, tool: value });
      expect(row).toBe(`2026-10-20T10:00:00.000Z,tool,${expected},1.0,4.0.0,free,${expected},`);
    });

    it("prefixes a field that starts with a carriage return, then quotes it", async () => {
      const row = await exportRow({ client: "\r=1+1", tool: "ok" });
      expect(row).toBe(`2026-10-20T10:00:00.000Z,tool,"'\r=1+1",1.0,4.0.0,free,ok,`);
    });

    it("prefixes before the CSV escaping, so a quoted formula stays inert", async () => {
      const row = await exportRow({ client: '=HYPERLINK("http://example.com","x")', tool: "ok" });
      expect(row).toBe(
        `2026-10-20T10:00:00.000Z,tool,"'=HYPERLINK(""http://example.com"",""x"")",1.0,4.0.0,free,ok,`,
      );
    });

    it("prefixes the version strings too, since they also come from ingestion", async () => {
      const row = await exportRow({ client: "c", tool: "t", client_ver: "=1", mcp_ver: "@2" });
      expect(row).toBe("2026-10-20T10:00:00.000Z,tool,c,'=1,'@2,free,t,");
    });

    it("leaves a field alone when the character is not the first one", async () => {
      const row = await exportRow({ client: "claude-desktop", tool: "a=b+c" });
      expect(row).toBe("2026-10-20T10:00:00.000Z,tool,claude-desktop,1.0,4.0.0,free,a=b+c,");
    });
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

// ── Read routes — fail-closed auth and CORS scoping ──────────────────────────

const READ_ROUTES = [
  "/v1/stats",
  "/v1/timeseries",
  "/v1/top-clients",
  "/v1/funnel",
  "/v1/export",
];

// A fresh copy of the worker module, as a new isolate would load it, so its
// once-per-isolate state (the missing-secret log) starts unset.
async function freshApp(): Promise<typeof app> {
  vi.resetModules();
  return (await import("../src/index.js")).app;
}

// The response a caller gets for a refused read request, as status, body and
// the headers that could tell two refusals apart.
async function refusal(res: Response) {
  return {
    status: res.status,
    body: await res.text(),
    wwwAuthenticate: res.headers.get("www-authenticate"),
    contentType: res.headers.get("content-type"),
  };
}

describe.each(READ_ROUTES)("GET %s — auth (fail closed)", (route) => {
  let consoleError: MockInstance<typeof console.error>;

  beforeEach(() => {
    consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
  });

  afterEach(() => {
    consoleError.mockRestore();
  });

  it("returns 401 when STATS_TOKEN is not configured, even with a bearer", async () => {
    const env = makeEnv();
    const db = env.DB as unknown as ReturnType<typeof createMockD1>;
    const res = await app.request(route, { method: "GET", headers: AUTH }, env);
    expect(res.status).toBe(401);
    expect(res.headers.get("www-authenticate")).toBe("Bearer");
    expect(await res.json()).toEqual({ error: "unauthorized" });
    expect(db._statements).toHaveLength(0); // no query ran
  });

  it("returns 401 when STATS_TOKEN is configured as an empty string", async () => {
    const env = { ...makeEnv(), STATS_TOKEN: "" };
    const res = await app.request(route, { method: "GET", headers: { Authorization: "Bearer " } }, env);
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ error: "unauthorized" });
  });

  it.each([
    ["no Authorization header", {}],
    ["a bearer", AUTH],
    ["a lowercase bearer", { Authorization: `bearer ${TEST_TOKEN}` }],
    ["another scheme", { Authorization: `Basic ${TEST_TOKEN}` }],
  ])(
    "does not reveal that STATS_TOKEN is missing (%s): same refusal as a wrong token",
    async (_label, headers: Record<string, string>) => {
      const unset = await app.request(route, { method: "GET", headers }, makeEnv());
      const wrong = await app.request(
        route,
        { method: "GET", headers: { Authorization: "Bearer wrong-token" } },
        makeReadEnv(),
      );
      expect(await refusal(unset)).toEqual(await refusal(wrong));
      expect(unset.status).toBe(401);
    },
  );

  it("logs the missing STATS_TOKEN once per isolate, never the request's credentials", async () => {
    const isolate = await freshApp();
    const attempts: Record<string, string>[] = [AUTH, {}, { Authorization: "Basic x" }, AUTH];
    for (const headers of attempts) {
      const res = await isolate.request(route, { method: "GET", headers }, makeEnv());
      expect(res.status).toBe(401);
    }
    expect(consoleError).toHaveBeenCalledTimes(1);
    const logged = consoleError.mock.calls[0].join(" ");
    expect(logged).toContain("STATS_TOKEN is not configured");
    expect(logged).not.toContain(TEST_TOKEN);
  });

  it("logs nothing when STATS_TOKEN is configured and the token is wrong", async () => {
    const isolate = await freshApp();
    const res = await isolate.request(
      route,
      { method: "GET", headers: { Authorization: "Bearer wrong-token" } },
      makeReadEnv(),
    );
    expect(res.status).toBe(401);
    expect(consoleError).not.toHaveBeenCalled();
  });

  it.each([
    ["lowercase", `bearer ${TEST_TOKEN}`],
    ["uppercase", `BEARER ${TEST_TOKEN}`],
    ["mixed case", `bEaReR ${TEST_TOKEN}`],
    ["several spaces after", `Bearer   ${TEST_TOKEN}`],
  ])("accepts the Bearer scheme in %s (RFC 7235 §2.1)", async (_label, header) => {
    const env = makeReadEnv();
    const res = await app.request(route, { method: "GET", headers: { Authorization: header } }, env);
    expect(res.status).toBe(200);
  });

  it.each([
    ["a lowercase scheme with a wrong token", "bearer wrong-token"],
    ["the scheme glued to the token", `Bearer${TEST_TOKEN}`],
    ["the scheme alone", "Bearer"],
    ["a scheme that only starts with bearer", `Bearerx ${TEST_TOKEN}`],
  ])("returns 401 with %s", async (_label, header) => {
    const env = makeReadEnv();
    const res = await app.request(route, { method: "GET", headers: { Authorization: header } }, env);
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ error: "unauthorized" });
  });

  it("returns 401 without an Authorization header", async () => {
    const env = makeReadEnv();
    const db = env.DB as unknown as ReturnType<typeof createMockD1>;
    const res = await app.request(route, { method: "GET" }, env);
    expect(res.status).toBe(401);
    expect(res.headers.get("www-authenticate")).toBe("Bearer");
    expect(await res.json()).toEqual({ error: "unauthorized" });
    expect(db._statements).toHaveLength(0);
  });

  it.each([
    ["a wrong token", "Bearer wrong-token"],
    ["a prefix of the token", `Bearer ${TEST_TOKEN.slice(0, -1)}`],
    ["the token plus a suffix", `Bearer ${TEST_TOKEN}x`],
    ["the bare token without the scheme", TEST_TOKEN],
    ["another scheme", `Basic ${TEST_TOKEN}`],
  ])("returns 401 with %s", async (_label, header) => {
    const env = makeReadEnv();
    const res = await app.request(route, { method: "GET", headers: { Authorization: header } }, env);
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ error: "unauthorized" });
  });

  it("returns 200 with the correct bearer token", async () => {
    const env = makeReadEnv();
    const res = await app.request(route, { method: "GET", headers: AUTH }, env);
    expect(res.status).toBe(200);
  });

  it("sends no CORS headers on a successful cross-origin GET", async () => {
    const env = makeReadEnv();
    const res = await app.request(
      route,
      { method: "GET", headers: { ...AUTH, Origin: "https://example.com" } },
      env,
    );
    expect(res.status).toBe(200);
    expect(res.headers.get("access-control-allow-origin")).toBeNull();
  });

  it("sends no CORS headers on a rejected cross-origin GET", async () => {
    const env = makeEnv();
    const res = await app.request(
      route,
      { method: "GET", headers: { Origin: "https://example.com" } },
      env,
    );
    expect(res.status).toBe(401);
    expect(res.headers.get("access-control-allow-origin")).toBeNull();
  });

  it("does not answer a CORS preflight", async () => {
    const env = makeReadEnv();
    const res = await app.request(
      route,
      {
        method: "OPTIONS",
        headers: {
          Origin: "https://example.com",
          "Access-Control-Request-Method": "GET",
          "Access-Control-Request-Headers": "authorization",
        },
      },
      env,
    );
    expect(res.headers.get("access-control-allow-origin")).toBeNull();
    expect(res.headers.get("access-control-allow-headers")).toBeNull();
  });
});

describe.each(["/v1/events", "/v1/batch"])("POST %s — CORS kept for ingestion", (route) => {
  it("answers the CORS preflight with Access-Control-Allow-Origin: *", async () => {
    const env = makeEnv();
    const res = await app.request(
      route,
      {
        method: "OPTIONS",
        headers: {
          Origin: "https://example.com",
          "Access-Control-Request-Method": "POST",
          "Access-Control-Request-Headers": "content-type",
        },
      },
      env,
    );
    expect(res.status).toBe(204);
    expect(res.headers.get("access-control-allow-origin")).toBe("*");
    expect(res.headers.get("access-control-allow-methods")).toContain("POST");
    expect(res.headers.get("access-control-max-age")).toBe("86400");
  });

  it("sends Access-Control-Allow-Origin: * on the POST response", async () => {
    const env = makeEnv();
    const body = route === "/v1/batch" ? [validPayload()] : validPayload();
    const res = await post(route, body, env, { Origin: "https://example.com" });
    expect(res.status).toBe(202);
    expect(res.headers.get("access-control-allow-origin")).toBe("*");
  });
});

describe("Ingestion is independent of STATS_TOKEN", () => {
  it.each([
    ["not configured", makeEnv],
    ["configured", makeReadEnv],
  ])("POST /v1/events stores the event without any bearer when STATS_TOKEN is %s", async (_label, mk) => {
    const env = mk();
    const res = await post("/v1/events", validPayload(), env);
    expect(res.status).toBe(202);
    expect(await res.json()).toEqual({ ok: true });
    const db = env.DB as unknown as ReturnType<typeof createMockD1>;
    expect(db._statements).toHaveLength(1);
    expect(db._statements[0].sql).toContain("INSERT INTO events");
  });

  it.each([
    ["not configured", makeEnv],
    ["configured", makeReadEnv],
  ])("POST /v1/batch stores the events without any bearer when STATS_TOKEN is %s", async (_label, mk) => {
    const env = mk();
    const res = await post("/v1/batch", [validPayload(), validPayload({ event: "init", tool: undefined })], env);
    expect(res.status).toBe(202);
    expect(await res.json()).toEqual({ ok: true, accepted: 2 });
    const db = env.DB as unknown as ReturnType<typeof createMockD1>;
    expect(db._statements).toHaveLength(2);
  });

  it("GET /health stays public with Access-Control-Allow-Origin: *", async () => {
    const res = await app.request("/health", { method: "GET" }, makeEnv());
    expect(res.status).toBe(200);
    expect(res.headers.get("access-control-allow-origin")).toBe("*");
  });
});
