import { Hono } from "hono";
import { cors } from "hono/cors";
import type { Env } from "./env.js";
import { isRateLimited } from "./rate-limit.js";
import { rollupYesterday } from "./rollup.js";

const app = new Hono<{ Bindings: Env }>();

// ── CORS — allow calls from any origin (npx, browser, etc.) ─────────────────
app.use(
  "/v1/*",
  cors({
    origin: "*",
    allowMethods: ["GET", "POST", "OPTIONS"],
    allowHeaders: ["content-type", "authorization"],
    maxAge: 86400,
  }),
);

// ── Health check ─────────────────────────────────────────────────────────────
app.get("/health", (c) => {
  c.header("Access-Control-Allow-Origin", "*");
  return c.json({ ok: true, service: "sceneview-telemetry", version: "1.0.0" });
});

// ── Payload validation ───────────────────────────────────────────────────────
const VALID_EVENTS = new Set(["init", "tool"]);
const VALID_TIERS = new Set(["free", "pro"]);
const MAX_STRING_LEN = 128;

// Bot exclusion threshold — events with bot_likelihood >= this are filtered
// from /v1/stats, /v1/funnel, /v1/top-clients by default. Tunable via
// ?include_bots=1. NULL bot_likelihood (pre-2208 SDKs) is treated as non-bot.
const BOT_THRESHOLD = 0.7;

// SQL fragment shared by every analytics endpoint that supports include_bots.
// Defaults to excluding bots; pass `?include_bots=1` to keep them in.
function botFilter(includeBots: boolean): string {
  return includeBots
    ? ""
    : ` AND (bot_likelihood IS NULL OR bot_likelihood < ${BOT_THRESHOLD})`;
}

interface EventPayload {
  timestamp: string;
  event: "init" | "tool";
  client: string;
  clientVersion: string;
  mcpVersion: string;
  tier: "free" | "pro";
  tool?: string;
  installId?: string;
  botLikelihood?: number;
}

function validatePayload(body: unknown): EventPayload | null {
  if (!body || typeof body !== "object") return null;

  const b = body as Record<string, unknown>;

  if (typeof b.timestamp !== "string" || b.timestamp.length > MAX_STRING_LEN)
    return null;
  if (typeof b.event !== "string" || !VALID_EVENTS.has(b.event)) return null;
  if (typeof b.client !== "string" || b.client.length > MAX_STRING_LEN)
    return null;
  if (
    typeof b.clientVersion !== "string" ||
    b.clientVersion.length > MAX_STRING_LEN
  )
    return null;
  if (typeof b.mcpVersion !== "string" || b.mcpVersion.length > MAX_STRING_LEN)
    return null;
  if (typeof b.tier !== "string" || !VALID_TIERS.has(b.tier)) return null;

  // tool is optional but must be a bounded string if present
  if (b.tool !== undefined) {
    if (typeof b.tool !== "string" || b.tool.length > MAX_STRING_LEN)
      return null;
  }

  // installId is optional — a bounded string. Reject if present-but-invalid.
  if (b.installId !== undefined) {
    if (typeof b.installId !== "string" || b.installId.length === 0 || b.installId.length > MAX_STRING_LEN)
      return null;
  }

  // botLikelihood is optional — must be a finite number in [0, 1] if present.
  if (b.botLikelihood !== undefined) {
    if (
      typeof b.botLikelihood !== "number" ||
      !Number.isFinite(b.botLikelihood) ||
      b.botLikelihood < 0 ||
      b.botLikelihood > 1
    )
      return null;
  }

  return {
    timestamp: b.timestamp,
    event: b.event as "init" | "tool",
    client: b.client,
    clientVersion: b.clientVersion,
    mcpVersion: b.mcpVersion,
    tier: b.tier as "free" | "pro",
    tool: typeof b.tool === "string" ? b.tool : undefined,
    installId: typeof b.installId === "string" ? b.installId : undefined,
    botLikelihood:
      typeof b.botLikelihood === "number" ? b.botLikelihood : undefined,
  };
}

// ── POST /v1/events ──────────────────────────────────────────────────────────
app.post("/v1/events", async (c) => {
  // Body size guard — reject oversized payloads before parsing JSON
  const maxSize = 65536; // 64 KB
  const cl = parseInt(c.req.header("content-length") ?? "0", 10);
  if (cl > maxSize) return c.json({ error: "payload_too_large" }, 413);

  // Rate limit by IP (hashed — never stored raw)
  const ip = c.req.header("cf-connecting-ip") ?? "unknown";
  const rl = await isRateLimited(ip, c.env.RL_KV);
  if (rl.limited) {
    return c.json({ error: "rate_limited" }, 429, {
      "X-RateLimit-Limit": String(rl.limit),
      "X-RateLimit-Remaining": String(rl.remaining),
      "Retry-After": "60",
    });
  }

  // Parse & validate
  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    return c.json({ error: "invalid_json" }, 400, {
      "X-RateLimit-Limit": String(rl.limit),
      "X-RateLimit-Remaining": String(rl.remaining),
    });
  }

  const payload = validatePayload(body);
  if (!payload) {
    return c.json({ error: "invalid_payload" }, 400, {
      "X-RateLimit-Limit": String(rl.limit),
      "X-RateLimit-Remaining": String(rl.remaining),
    });
  }

  // Insert into D1 — fire-and-forget style but we await for correctness
  try {
    await c.env.DB.prepare(
      `INSERT INTO events (timestamp, event, client, client_ver, mcp_ver, tier, tool, install_id, bot_likelihood)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    )
      .bind(
        payload.timestamp,
        payload.event,
        payload.client,
        payload.clientVersion,
        payload.mcpVersion,
        payload.tier,
        payload.tool ?? null,
        payload.installId ?? null,
        payload.botLikelihood ?? null,
      )
      .run();
  } catch (e) {
    // D1 write failed — log but don't fail the client.
    // In production, this would go to Workers Logpush.
    console.error("D1 insert failed", e);
    return c.json({ ok: true, stored: false }, 202, {
      "X-RateLimit-Limit": String(rl.limit),
      "X-RateLimit-Remaining": String(rl.remaining),
    });
  }

  return c.json({ ok: true }, 202, {
    "X-RateLimit-Limit": String(rl.limit),
    "X-RateLimit-Remaining": String(rl.remaining),
  });
});

// ── Batch endpoint (future-proof for client-side batching) ───────────────────
app.post("/v1/batch", async (c) => {
  // Body size guard — 1 MB ceiling for batch payloads
  const maxBatchSize = 1_048_576; // 1 MB
  const cl = parseInt(c.req.header("content-length") ?? "0", 10);
  if (cl > maxBatchSize) return c.json({ error: "payload_too_large" }, 413);

  const ip = c.req.header("cf-connecting-ip") ?? "unknown";
  const rl = await isRateLimited(ip, c.env.RL_KV);
  if (rl.limited) {
    return c.json({ error: "rate_limited" }, 429, {
      "X-RateLimit-Limit": String(rl.limit),
      "X-RateLimit-Remaining": String(rl.remaining),
      "Retry-After": "60",
    });
  }

  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    return c.json({ error: "invalid_json" }, 400, {
      "X-RateLimit-Limit": String(rl.limit),
      "X-RateLimit-Remaining": String(rl.remaining),
    });
  }

  // Batch size is capped at 50 events per request (enforced below).
  // Rate limiting counts the whole batch as a single hit — acceptable because
  // the 50-event cap bounds the per-minute write amplification to 50×30 = 1500
  // rows, well within D1 limits. Tighten if abuse is observed.
  if (!Array.isArray(body) || body.length === 0 || body.length > 50) {
    return c.json({ error: "invalid_batch", max: 50 }, 400, {
      "X-RateLimit-Limit": String(rl.limit),
      "X-RateLimit-Remaining": String(rl.remaining),
    });
  }

  const validated = body.map(validatePayload).filter(Boolean) as EventPayload[];
  if (validated.length === 0) {
    return c.json({ error: "no_valid_events" }, 400, {
      "X-RateLimit-Limit": String(rl.limit),
      "X-RateLimit-Remaining": String(rl.remaining),
    });
  }

  // Batch insert with D1 batch API
  try {
    const stmt = c.env.DB.prepare(
      `INSERT INTO events (timestamp, event, client, client_ver, mcp_ver, tier, tool, install_id, bot_likelihood)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    );

    await c.env.DB.batch(
      validated.map((p) =>
        stmt.bind(
          p.timestamp,
          p.event,
          p.client,
          p.clientVersion,
          p.mcpVersion,
          p.tier,
          p.tool ?? null,
          p.installId ?? null,
          p.botLikelihood ?? null,
        ),
      ),
    );
  } catch (e) {
    console.error("D1 batch insert failed", e);
    return c.json({ ok: true, accepted: validated.length, stored: false }, 202, {
      "X-RateLimit-Limit": String(rl.limit),
      "X-RateLimit-Remaining": String(rl.remaining),
    });
  }

  return c.json({ ok: true, accepted: validated.length }, 202, {
    "X-RateLimit-Limit": String(rl.limit),
    "X-RateLimit-Remaining": String(rl.remaining),
  });
});

// ── Stats endpoint (simple read-only dashboard data) ─────────────────────────
app.get("/v1/stats", async (c) => {
  // Bearer token guard — only enforced when STATS_TOKEN is configured
  const token = c.env.STATS_TOKEN;
  if (token) {
    const auth = c.req.header("Authorization") ?? "";
    if (auth !== `Bearer ${token}`) {
      return c.json({ error: "unauthorized" }, 401);
    }
  }

  // Period selector: ?days=1|7|30|90 (default: 1 → preserves prior 24h behavior)
  const rawDays = c.req.query("days") ?? "1";
  const days = Math.min(Math.max(parseInt(rawDays, 10) || 1, 1), 90);
  const window = `-${days} days`;
  const period = days === 1 ? "24h" : `${days}d`;
  const includeBots = c.req.query("include_bots") === "1";
  const bf = botFilter(includeBots);

  try {
    const [totals, topTools, versions, tiers, clientVers, bots] = await Promise.all([
      c.env.DB.prepare(
        `SELECT
           COUNT(*) as total,
           COUNT(CASE WHEN event = 'init' THEN 1 END) as inits,
           COUNT(CASE WHEN event = 'tool' THEN 1 END) as tools,
           COUNT(DISTINCT client) as unique_clients,
           COUNT(DISTINCT install_id) as unique_installs
         FROM events
         WHERE ingested > datetime('now', ?)${bf}`,
      )
        .bind(window)
        .first(),
      c.env.DB.prepare(
        `SELECT tool, COUNT(*) as count
         FROM events
         WHERE event = 'tool' AND ingested > datetime('now', ?)${bf}
         GROUP BY tool
         ORDER BY count DESC
         LIMIT 20`,
      )
        .bind(window)
        .all(),
      c.env.DB.prepare(
        `SELECT mcp_ver, COUNT(*) as count
         FROM events
         WHERE ingested > datetime('now', ?)${bf}
         GROUP BY mcp_ver
         ORDER BY count DESC`,
      )
        .bind(window)
        .all(),
      // Tier breakdown — free vs pro distribution (events + unique users)
      c.env.DB.prepare(
        `SELECT tier,
                COUNT(*) as count,
                COUNT(DISTINCT client) as unique_clients,
                COUNT(DISTINCT install_id) as unique_installs
         FROM events
         WHERE ingested > datetime('now', ?)${bf}
         GROUP BY tier
         ORDER BY count DESC`,
      )
        .bind(window)
        .all(),
      // Claude Code / client version distribution (top 10)
      c.env.DB.prepare(
        `SELECT client_ver, COUNT(*) as count, COUNT(DISTINCT client) as unique_clients
         FROM events
         WHERE ingested > datetime('now', ?) AND client_ver != ''${bf}
         GROUP BY client_ver
         ORDER BY count DESC
         LIMIT 10`,
      )
        .bind(window)
        .all(),
      // Count of bot events excluded by the default filter — surface in the
      // response so dashboards can show a "Bot calls excluded" badge.
      c.env.DB.prepare(
        `SELECT
           COUNT(*) as count,
           COUNT(DISTINCT install_id) as unique_installs
         FROM events
         WHERE ingested > datetime('now', ?)
           AND bot_likelihood IS NOT NULL AND bot_likelihood >= ${BOT_THRESHOLD}`,
      )
        .bind(window)
        .first(),
    ]);

    return c.json({
      period,
      days,
      includeBots,
      botThreshold: BOT_THRESHOLD,
      totals,
      topTools: topTools.results,
      versions: versions.results,
      tiers: tiers.results,
      clientVersions: clientVers.results,
      botsExcluded: includeBots ? null : bots,
    });
  } catch {
    return c.json({ error: "query_failed" }, 500);
  }
});

// ── Timeseries endpoint (Grafana JSON datasource / charting) ─────────────────
app.get("/v1/timeseries", async (c) => {
  // Bearer token guard — same as /v1/stats
  const token = c.env.STATS_TOKEN;
  if (token) {
    const auth = c.req.header("Authorization") ?? "";
    if (auth !== `Bearer ${token}`) {
      return c.json({ error: "unauthorized" }, 401);
    }
  }

  // Query param validation
  const rawDays = c.req.query("days") ?? "30";
  const metric = c.req.query("metric") ?? "events";
  const days = Math.min(Math.max(parseInt(rawDays, 10) || 30, 1), 90);

  const VALID_METRICS = new Set(["events", "tools", "versions", "clients"]);
  if (!VALID_METRICS.has(metric)) {
    return c.json({ error: "invalid_metric", valid: [...VALID_METRICS] }, 400);
  }

  // today's date string (UTC) — rollups exist for dates strictly before today
  const todayStr = new Date().toISOString().slice(0, 10);

  type TimeseriesRow = { time: string; value: number; label?: string };

  try {
    let rows: TimeseriesRow[] = [];

    if (metric === "events") {
      // Daily total event counts — rollups for older days, live events for today
      const [rollupRes, liveRes] = await Promise.all([
        c.env.DB.prepare(
          `SELECT date as time, SUM(count) as value
           FROM daily_rollups
           WHERE date >= date('now', '-' || ? || ' days') AND date < ?
           GROUP BY date
           ORDER BY date ASC`,
        )
          .bind(days, todayStr)
          .all<{ time: string; value: number }>(),
        c.env.DB.prepare(
          `SELECT ? as time, COUNT(*) as value
           FROM events
           WHERE date(ingested) = ?`,
        )
          .bind(todayStr, todayStr)
          .first<{ time: string; value: number }>(),
      ]);
      rows = rollupRes.results;
      if (liveRes && liveRes.value > 0) rows.push(liveRes);
    } else if (metric === "tools") {
      // Daily counts per tool
      const [rollupRes, liveRes] = await Promise.all([
        c.env.DB.prepare(
          `SELECT date as time, tool as label, SUM(count) as value
           FROM daily_rollups
           WHERE date >= date('now', '-' || ? || ' days') AND date < ?
             AND tool != ''
           GROUP BY date, tool
           ORDER BY date ASC, value DESC`,
        )
          .bind(days, todayStr)
          .all<{ time: string; label: string; value: number }>(),
        c.env.DB.prepare(
          `SELECT ? as time, tool as label, COUNT(*) as value
           FROM events
           WHERE date(ingested) = ? AND event = 'tool' AND tool IS NOT NULL
           GROUP BY tool
           ORDER BY value DESC`,
        )
          .bind(todayStr, todayStr)
          .all<{ time: string; label: string; value: number }>(),
      ]);
      rows = [...rollupRes.results, ...liveRes.results];
    } else if (metric === "versions") {
      // Daily counts per mcp_ver
      const [rollupRes, liveRes] = await Promise.all([
        c.env.DB.prepare(
          `SELECT date as time, mcp_ver as label, SUM(count) as value
           FROM daily_rollups
           WHERE date >= date('now', '-' || ? || ' days') AND date < ?
           GROUP BY date, mcp_ver
           ORDER BY date ASC, value DESC`,
        )
          .bind(days, todayStr)
          .all<{ time: string; label: string; value: number }>(),
        c.env.DB.prepare(
          `SELECT ? as time, mcp_ver as label, COUNT(*) as value
           FROM events
           WHERE date(ingested) = ?
           GROUP BY mcp_ver
           ORDER BY value DESC`,
        )
          .bind(todayStr, todayStr)
          .all<{ time: string; label: string; value: number }>(),
      ]);
      rows = [...rollupRes.results, ...liveRes.results];
    } else {
      // metric === "clients" — daily counts per client
      const [rollupRes, liveRes] = await Promise.all([
        c.env.DB.prepare(
          `SELECT date as time, client as label, SUM(count) as value
           FROM daily_rollups
           WHERE date >= date('now', '-' || ? || ' days') AND date < ?
           GROUP BY date, client
           ORDER BY date ASC, value DESC`,
        )
          .bind(days, todayStr)
          .all<{ time: string; label: string; value: number }>(),
        c.env.DB.prepare(
          `SELECT ? as time, client as label, COUNT(*) as value
           FROM events
           WHERE date(ingested) = ?
           GROUP BY client
           ORDER BY value DESC`,
        )
          .bind(todayStr, todayStr)
          .all<{ time: string; label: string; value: number }>(),
      ]);
      rows = [...rollupRes.results, ...liveRes.results];
    }

    return c.json({ metric, days, data: rows });
  } catch {
    return c.json({ error: "query_failed" }, 500);
  }
});

// ── Top clients endpoint (most active users) ─────────────────────────────────
app.get("/v1/top-clients", async (c) => {
  const token = c.env.STATS_TOKEN;
  if (token) {
    const auth = c.req.header("Authorization") ?? "";
    if (auth !== `Bearer ${token}`) {
      return c.json({ error: "unauthorized" }, 401);
    }
  }

  const days = Math.min(
    Math.max(parseInt(c.req.query("days") ?? "7", 10) || 7, 1),
    90,
  );
  const limit = Math.min(
    Math.max(parseInt(c.req.query("limit") ?? "20", 10) || 20, 1),
    100,
  );
  const window = `-${days} days`;
  const includeBots = c.req.query("include_bots") === "1";
  const bf = botFilter(includeBots);

  try {
    const result = await c.env.DB.prepare(
      `SELECT
         client,
         COUNT(*) as events,
         COUNT(CASE WHEN event = 'tool' THEN 1 END) as tool_calls,
         COUNT(DISTINCT install_id) as unique_installs,
         MAX(ingested) as last_seen,
         MIN(ingested) as first_seen,
         (SELECT tier FROM events e2 WHERE e2.client = events.client ORDER BY ingested DESC LIMIT 1) as tier,
         (SELECT mcp_ver FROM events e2 WHERE e2.client = events.client ORDER BY ingested DESC LIMIT 1) as mcp_ver,
         (SELECT client_ver FROM events e2 WHERE e2.client = events.client ORDER BY ingested DESC LIMIT 1) as client_ver
       FROM events
       WHERE ingested > datetime('now', ?)${bf}
       GROUP BY client
       ORDER BY events DESC
       LIMIT ?`,
    )
      .bind(window, limit)
      .all();

    return c.json({ days, limit, includeBots, clients: result.results });
  } catch {
    return c.json({ error: "query_failed" }, 500);
  }
});

// ── Funnel endpoint (conversion: install → first call → 10th call → pro) ─────
app.get("/v1/funnel", async (c) => {
  const token = c.env.STATS_TOKEN;
  if (token) {
    const auth = c.req.header("Authorization") ?? "";
    if (auth !== `Bearer ${token}`) {
      return c.json({ error: "unauthorized" }, 401);
    }
  }

  const days = Math.min(
    Math.max(parseInt(c.req.query("days") ?? "30", 10) || 30, 1),
    90,
  );
  const window = `-${days} days`;
  const includeBots = c.req.query("include_bots") === "1";
  const bf = botFilter(includeBots);

  try {
    // Group by install_id when present, fall back to client for legacy events.
    // COALESCE(install_id, 'client:' || client) keeps the two namespaces
    // disjoint so a runtime named "foo" cannot collide with an installId
    // literally equal to "foo".
    const stages = await c.env.DB.prepare(
      `WITH per_dev AS (
         SELECT
           COALESCE(install_id, 'client:' || client) as dev_key,
           COUNT(CASE WHEN event = 'init' THEN 1 END) as inits,
           COUNT(CASE WHEN event = 'tool' THEN 1 END) as tools,
           MAX(CASE WHEN tier = 'pro' THEN 1 ELSE 0 END) as is_pro
         FROM events
         WHERE ingested > datetime('now', ?)${bf}
         GROUP BY dev_key
       )
       SELECT
         COUNT(*) as installed,
         COUNT(CASE WHEN tools >= 1 THEN 1 END) as activated,
         COUNT(CASE WHEN tools >= 10 THEN 1 END) as engaged,
         COUNT(CASE WHEN tools >= 50 THEN 1 END) as power_users,
         COUNT(CASE WHEN is_pro = 1 THEN 1 END) as pro_users
       FROM per_dev`,
    )
      .bind(window)
      .first<{
        installed: number;
        activated: number;
        engaged: number;
        power_users: number;
        pro_users: number;
      }>();

    const installed = stages?.installed ?? 0;
    const activated = stages?.activated ?? 0;
    const engaged = stages?.engaged ?? 0;
    const power = stages?.power_users ?? 0;
    const pro = stages?.pro_users ?? 0;
    const pct = (n: number) =>
      installed > 0 ? +((n / installed) * 100).toFixed(1) : 0;

    return c.json({
      days,
      includeBots,
      stages: [
        { stage: "installed", label: "Installed (any event)", count: installed, pct: 100 },
        { stage: "activated", label: "≥1 tool call", count: activated, pct: pct(activated) },
        { stage: "engaged", label: "≥10 tool calls", count: engaged, pct: pct(engaged) },
        { stage: "power_users", label: "≥50 tool calls", count: power, pct: pct(power) },
        { stage: "pro_users", label: "Pro tier", count: pro, pct: pct(pro) },
      ],
    });
  } catch {
    return c.json({ error: "query_failed" }, 500);
  }
});

// ── Export endpoint (CSV download for analytics) ─────────────────────────────
app.get("/v1/export", async (c) => {
  // Bearer token guard — same as /v1/stats
  const token = c.env.STATS_TOKEN;
  if (token) {
    const auth = c.req.header("Authorization") ?? "";
    if (auth !== `Bearer ${token}`) {
      return c.json({ error: "unauthorized" }, 401);
    }
  }

  // Query param validation
  const rawDays = c.req.query("days") ?? "7";
  const eventType = c.req.query("event_type");

  const days = Math.min(Math.max(parseInt(rawDays, 10) || 7, 1), 30);

  if (eventType !== undefined && eventType !== "init" && eventType !== "tool") {
    return c.json({ error: "invalid_event_type", valid: ["init", "tool"] }, 400);
  }

  try {
    let query: string;
    let bindings: (string | number)[];

    if (eventType) {
      query = `SELECT timestamp, event, client, client_ver, mcp_ver, tier, tool, install_id, bot_likelihood
               FROM events
               WHERE ingested >= datetime('now', '-' || ? || ' days')
                 AND event = ?
               ORDER BY ingested DESC
               LIMIT 10000`;
      bindings = [days, eventType];
    } else {
      query = `SELECT timestamp, event, client, client_ver, mcp_ver, tier, tool, install_id, bot_likelihood
               FROM events
               WHERE ingested >= datetime('now', '-' || ? || ' days')
               ORDER BY ingested DESC
               LIMIT 10000`;
      bindings = [days];
    }

    type EventRow = {
      timestamp: string;
      event: string;
      client: string;
      client_ver: string;
      mcp_ver: string;
      tier: string;
      tool: string | null;
      install_id: string | null;
      bot_likelihood: number | null;
    };

    const result = await c.env.DB.prepare(query)
      .bind(...bindings)
      .all<EventRow>();

    // Build CSV — header + rows
    const lines: string[] = [
      "timestamp,event,client,client_ver,mcp_ver,tier,tool,install_id,bot_likelihood",
    ];

    for (const row of result.results) {
      const fields = [
        row.timestamp,
        row.event,
        row.client,
        row.client_ver,
        row.mcp_ver,
        row.tier,
        row.tool ?? "",
        row.install_id ?? "",
        row.bot_likelihood !== null && row.bot_likelihood !== undefined
          ? String(row.bot_likelihood)
          : "",
      ].map((v) => {
        // Escape fields that contain commas, quotes, or newlines
        if (v.includes(",") || v.includes('"') || v.includes("\n")) {
          return `"${v.replace(/"/g, '""')}"`;
        }
        return v;
      });
      lines.push(fields.join(","));
    }

    const csv = lines.join("\n");

    return new Response(csv, {
      headers: {
        "content-type": "text/csv; charset=utf-8",
        "content-disposition": `attachment; filename="events-${days}d.csv"`,
      },
    });
  } catch {
    return c.json({ error: "query_failed" }, 500);
  }
});

// ── Landing page ─────────────────────────────────────────────────────────────
app.get("/", (c) => {
  const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>SceneView Telemetry</title>
  <style>
    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      background: #0d1117;
      color: #e6edf3;
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 2rem;
    }
    .card {
      max-width: 520px;
      width: 100%;
      background: #161b22;
      border: 1px solid #30363d;
      border-radius: 12px;
      padding: 2.5rem 2rem;
    }
    .logo {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      margin-bottom: 1.5rem;
    }
    .logo-icon {
      width: 40px;
      height: 40px;
      background: #4285f4;
      border-radius: 8px;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }
    .logo-icon svg { display: block; }
    h1 {
      font-size: 1.4rem;
      font-weight: 600;
      color: #e6edf3;
      line-height: 1.2;
    }
    .badge {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      background: #1a2f1a;
      border: 1px solid #2ea04326;
      color: #3fb950;
      font-size: 0.75rem;
      font-weight: 500;
      padding: 0.25rem 0.6rem;
      border-radius: 20px;
      margin-bottom: 1.25rem;
    }
    .badge-dot {
      width: 7px;
      height: 7px;
      background: #3fb950;
      border-radius: 50%;
    }
    p {
      color: #8b949e;
      font-size: 0.9rem;
      line-height: 1.65;
      margin-bottom: 1.75rem;
    }
    .links {
      display: flex;
      flex-wrap: wrap;
      gap: 0.75rem;
    }
    a {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      text-decoration: none;
      font-size: 0.85rem;
      font-weight: 500;
      padding: 0.45rem 1rem;
      border-radius: 6px;
      transition: background 0.15s, color 0.15s;
    }
    a.primary {
      background: #4285f4;
      color: #fff;
    }
    a.primary:hover { background: #5a95f5; }
    a.secondary {
      background: #21262d;
      color: #c9d1d9;
      border: 1px solid #30363d;
    }
    a.secondary:hover { background: #30363d; color: #e6edf3; }
    .divider {
      border: none;
      border-top: 1px solid #21262d;
      margin: 1.75rem 0;
    }
    .meta {
      font-size: 0.75rem;
      color: #484f58;
    }
  </style>
</head>
<body>
  <div class="card">
    <div class="logo">
      <div class="logo-icon">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </div>
      <h1>SceneView Telemetry</h1>
    </div>
    <div class="badge">
      <span class="badge-dot"></span>
      Operational
    </div>
    <p>
      Anonymous usage analytics for SceneView MCP tools. This service collects
      aggregate, privacy-preserving data (no IP addresses stored) to help improve
      the SceneView developer experience.
    </p>
    <div class="links">
      <a class="primary" href="https://sceneview.github.io">SceneView</a>
      <a class="secondary" href="/health">Health check</a>
    </div>
    <hr class="divider" />
    <span class="meta">sceneview-telemetry v1.0.0 &mdash; Cloudflare Workers</span>
  </div>
</body>
</html>`;
  return c.html(html);
});

// ── Catch-all ────────────────────────────────────────────────────────────────
app.all("*", (c) => c.json({ error: "not_found" }, 404));

export { app };

export default {
  fetch: app.fetch,
  async scheduled(event: ScheduledEvent, env: Env, ctx: ExecutionContext) {
    ctx.waitUntil(rollupYesterday(env.DB));
  },
};
