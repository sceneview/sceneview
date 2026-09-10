# Deploying sceneview-telemetry

Cloudflare Worker that ingests anonymous telemetry events from `sceneview-mcp`.

## Local development

No Cloudflare account or real IDs needed. `wrangler dev` automatically uses local
SQLite for D1 and an in-memory store for KV — the `TODO_REPLACE_*` placeholders in
`wrangler.toml` are ignored in local mode.

### Quick start

```bash
# One-shot: apply migrations + start worker + send test event
bash scripts/dev-setup.sh
```

### Manual steps (if you prefer)

```bash
# 1. Apply migrations to local SQLite
npx wrangler d1 migrations apply sceneview-telemetry --local

# 2. Start the dev server (port 8787)
npx wrangler dev

# 3. Send a test event
curl -X POST http://localhost:8787/v1/events \
  -H "Content-Type: application/json" \
  -d '{"timestamp":"2026-04-12T10:00:00Z","event":"init","client":"test","clientVersion":"1.0","mcpVersion":"4.0.0-rc.1","tier":"free"}'

# 4. Check stats
curl http://localhost:8787/v1/stats
```

The `[dev]` section in `wrangler.toml` pins the local server to port 8787 over HTTP.
No `STATS_TOKEN` is needed locally unless you explicitly set one via `wrangler secret put`.

---

## Prerequisites (production)

- Cloudflare account
- `wrangler` CLI authenticated (`wrangler login`)

## Step 1 — Create D1 database

```bash
wrangler d1 create sceneview-telemetry
```

Copy the returned `database_id` into `wrangler.toml` (replace `TODO_REPLACE_AFTER_D1_CREATE`).

## Step 2 — Create KV namespace

```bash
wrangler kv namespace create RL_KV
```

Copy the returned `id` into `wrangler.toml` (replace `TODO_REPLACE_AFTER_KV_CREATE`).

## Step 3 — Run D1 migration

```bash
wrangler d1 migrations apply sceneview-telemetry
```

This creates the `events` table + indexes.

## Step 4 — Deploy

```bash
wrangler deploy
```

The worker will be live at `sceneview-telemetry.<account>.workers.dev`.

## Step 4b — Enable cron trigger

Edit `wrangler.toml` to uncomment the `[triggers]` section:

```toml
[triggers]
crons = ["0 2 * * *"]
```

Then redeploy:

```bash
wrangler deploy
```

This enables the daily rollup at 02:00 UTC that aggregates events into `daily_rollups` and purges raw events older than 90 days. Without this, raw events accumulate indefinitely.

## Step 5 — Verify

```bash
# Health check
curl https://sceneview-telemetry.mcp-tools-lab.workers.dev/health

# Send a test event
curl -X POST https://sceneview-telemetry.mcp-tools-lab.workers.dev/v1/events \
  -H "Content-Type: application/json" \
  -d '{"timestamp":"2026-04-12T10:00:00Z","event":"init","client":"test","clientVersion":"1.0","mcpVersion":"4.0.0-rc.1","tier":"free"}'

# Check stats
curl https://sceneview-telemetry.mcp-tools-lab.workers.dev/v1/stats
```

## Optional secret — STATS_TOKEN

To protect `GET /v1/stats` with a bearer token:

```bash
wrangler secret put STATS_TOKEN
```

When set, all requests to `/v1/stats` must include the header:

```
Authorization: Bearer <your-token>
```

Requests without a valid token receive `401 {"error":"unauthorized"}`.

If `STATS_TOKEN` is not configured, the endpoint remains open — useful for local dev
(`wrangler dev`) without any extra setup.

**In production, always set STATS_TOKEN** to prevent public access to usage statistics.

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Health check |
| POST | `/v1/events` | Single event ingestion |
| POST | `/v1/batch` | Batch ingestion (up to 50 events) |
| GET | `/v1/stats` | Last 24h aggregated stats |

## Rate limits

30 requests/minute per IP (hashed, never stored raw). KV-backed sliding window.

## After deployment

The `TELEMETRY_ENDPOINT` in `mcp/src/telemetry.ts` is already set to `https://sceneview-telemetry.mcp-tools-lab.workers.dev/v1/events`.

## Dashboard

A standalone HTML dashboard (`dashboard.html`) provides a visual stats overview.

**To use:**
1. Open `dashboard.html` in your browser (file:// protocol works locally)
2. On first load, the dashboard prompts for:
   - **API base URL** (e.g., `https://sceneview-telemetry.mcp-tools-lab.workers.dev`)
   - **Bearer token** (your `STATS_TOKEN` value, if set)
3. Credentials are stored in the browser's localStorage and persisted between sessions
4. The dashboard auto-refreshes every 60 seconds and works offline once loaded

**Features:**
- 24-hour event totals (inits vs tool calls vs unique clients)
- Top 20 tools by call count
- MCP versions in use
- Light/dark mode toggle
- Local-first design (no analytics on the dashboard itself)
