// ─── Anonymous opt-out telemetry for sceneview-mcp ───────────────────────────
//
// Fire-and-forget, non-blocking, no personal data. Ever.
//
// What gets sent:
//   - timestamp (UTC ISO string)
//   - event: "init" | "tool"
//   - client: MCP client name (e.g. "claude-desktop", "cursor")
//   - clientVersion: MCP client version string reported during handshake
//   - mcpVersion: this package's version
//   - tier: "free" | "pro" — tier the TOOL resolved to, NOT the user's plan
//   - tool?: tool name (only for "tool" events)
//   - installId?: opaque random UUID per install — lets the worker count
//     unique developers per client runtime without identifying who they are
//   - botLikelihood?: float in [0.0, 1.0] computed at the sender side from
//     environment signals (CI flags, no-TTY, container hints). Worker uses
//     it to exclude bot traffic from monetization analytics by default.
//
// What NEVER gets sent:
//   - IP address (the endpoint strips it server-side; we never send headers)
//   - hostname, OS user, machine id, cwd, project path, package name
//   - prompt content, tool arguments, tool results
//   - API keys, billing info
//
// Opt out with `SCENEVIEW_TELEMETRY=0` or by running in CI (`CI=true`).

import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";

import type { Tier } from "./tiers.js";

// Worker implementation: telemetry-worker/ (Hono + D1 + KV rate limiting).
// Deploy with: cd telemetry-worker && see DEPLOY.md
const TELEMETRY_ENDPOINT = "https://sceneview-telemetry.mcp-tools-lab.workers.dev/v1/events";
const TELEMETRY_BATCH_ENDPOINT = "https://sceneview-telemetry.mcp-tools-lab.workers.dev/v1/batch";

// Hard cap so we never hang the process on a slow endpoint.
const TELEMETRY_TIMEOUT_MS = 2000;

// Client-side batching: flush when buffer reaches this size or after this delay.
const BATCH_MAX_SIZE = 10;
const BATCH_FLUSH_INTERVAL_MS = 30_000;

// Shared between init and tool events. Populated by `recordClientInit`.
interface ClientContext {
  client: string;
  clientVersion: string;
}

/** Exposed for tests. */
export interface TelemetryPayload {
  timestamp: string;
  event: "init" | "tool";
  client: string;
  clientVersion: string;
  mcpVersion: string;
  tier: Tier;
  tool?: string;
  /** Opaque random UUID per install. Stable across runs, never tied to identity. */
  installId?: string;
  /** Bot likelihood in [0.0, 1.0], computed at sender side from env signals. */
  botLikelihood?: number;
}

let clientContext: ClientContext | undefined;

// ─── Client-side batch buffer ─────────────────────────────────────────────────
let buffer: TelemetryPayload[] = [];
let flushTimer: ReturnType<typeof setTimeout> | undefined;

// Cached fingerprint values — computed once per process.
let cachedInstallId: string | undefined;
let cachedBotLikelihood: number | undefined;

// Read lazily so tests that mutate env vars between runs see the latest value.
function isEnabled(): boolean {
  if (process.env.SCENEVIEW_TELEMETRY === "0") return false;
  if (process.env.CI === "true") return false;
  return true;
}

// Read the package version from the build-time generated module so the
// telemetry payload reports the real published version. Pre-4.0.8 this
// fell back to a hardcoded "4.0.0" string that was never bumped, which
// made it impossible to track adoption of any new release.
import { PACKAGE_VERSION } from "./generated/version.js";

function getMcpVersion(): string {
  return process.env.SCENEVIEW_MCP_VERSION ?? PACKAGE_VERSION;
}

// ─── Install ID — anonymous per-install fingerprint ──────────────────────────
//
// Stored at `$XDG_CONFIG_HOME/sceneview-mcp/install.json` (default
// `~/.config/sceneview-mcp/install.json`). Created on first run if missing.
// Contents: `{ "installId": "<uuid v4>", "createdAt": "<ISO>" }`.
//
// Why a UUID and not a hash of hostname / cwd / etc.:
//   - hostnames/cwd are personally identifying (employee laptop name,
//     `/Users/<name>/...`) — even hashed, they're predictable enough that
//     a determined attacker with worker-side DB access could brute-force
//     them back. A UUID is provably non-identifying.
//   - The point is to count unique developers per `client` runtime, not to
//     fingerprint the host. A fresh install = a fresh identity, by design.
//
// Disabled when telemetry is disabled (no file is written).

function getInstallIdConfigPath(): string {
  const xdg = process.env.XDG_CONFIG_HOME;
  const base = xdg && xdg.length > 0 ? xdg : join(homedir(), ".config");
  return join(base, "sceneview-mcp", "install.json");
}

function loadOrCreateInstallId(): string | undefined {
  if (cachedInstallId !== undefined) return cachedInstallId;

  let path: string;
  try {
    path = getInstallIdConfigPath();
  } catch {
    return undefined;
  }

  // Try to read an existing install record.
  try {
    const raw = readFileSync(path, "utf8");
    const parsed = JSON.parse(raw) as { installId?: unknown };
    if (typeof parsed.installId === "string" && parsed.installId.length > 0) {
      cachedInstallId = parsed.installId;
      return cachedInstallId;
    }
  } catch {
    // File missing or unreadable — fall through to create.
  }

  // Create a fresh install record. All failures are swallowed: a read-only
  // home directory (CI sandboxes, container images) should not break telemetry
  // or the MCP server itself.
  try {
    const installId = randomUUID();
    const dir = join(path, "..");
    mkdirSync(dir, { recursive: true });
    writeFileSync(
      path,
      JSON.stringify({ installId, createdAt: new Date().toISOString() }, null, 2),
      { encoding: "utf8" },
    );
    cachedInstallId = installId;
    return cachedInstallId;
  } catch {
    // Fallback: an ephemeral per-process UUID. Better than nothing —
    // still distinguishes parallel scripted runs from a real install.
    cachedInstallId = randomUUID();
    return cachedInstallId;
  }
}

// ─── Bot likelihood — sender-side heuristic ──────────────────────────────────
//
// Returns a score in [0.0, 1.0] where 1.0 = almost certainly automation.
// Signals (each independently bumps the score):
//
//   - GITHUB_ACTIONS / GITLAB_CI / CIRCLECI / BUILDKITE / TF_BUILD / JENKINS_URL
//     are set                                              → +0.45 (strong)
//   - CI is set to any truthy value                        → +0.30 (medium)
//   - stdout is not a TTY                                  → +0.15 (weak)
//   - common container/sandbox hints
//     (KUBERNETES_SERVICE_HOST, container=docker, AWS_LAMBDA_FUNCTION_NAME)
//                                                          → +0.20 (medium)
//   - DEBIAN_FRONTEND=noninteractive (popular in scripts)  → +0.10 (weak)
//
// The score is computed ONCE per process and reused on every event. It is
// clamped to [0.0, 1.0]. The worker uses it as an advisory exclusion filter
// (default threshold 0.7); raw events are still stored for forensics.

function computeBotLikelihood(): number {
  if (cachedBotLikelihood !== undefined) return cachedBotLikelihood;

  let score = 0;
  const env = process.env;

  // Strong: a named CI provider env var.
  const strongCiVars = [
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
  ];
  if (strongCiVars.some((k) => typeof env[k] === "string" && env[k]!.length > 0)) {
    score += 0.45;
  }

  // Medium: generic CI=truthy. We don't double-count if a strong var fired,
  // but for cheap envs ("CI=true" alone), this captures them.
  const ci = env.CI;
  if (typeof ci === "string" && (ci === "true" || ci === "1")) {
    score += 0.3;
  }

  // Weak: no TTY → almost always automation or piped invocation. We test
  // stdout because the MCP protocol uses stdin/stdout, but stderr is the
  // more reliable indicator of "is a human watching".
  try {
    // process.stderr is most reliable; some MCP runners pipe stdout but leave
    // stderr attached. Both being non-TTY is a stronger bot signal.
    const stderrTty =
      typeof process.stderr === "object" &&
      typeof (process.stderr as { isTTY?: boolean }).isTTY === "boolean" &&
      (process.stderr as { isTTY?: boolean }).isTTY === true;
    if (!stderrTty) score += 0.15;
  } catch {
    // Defensive — if stderr isn't introspectable, treat as non-TTY.
    score += 0.15;
  }

  // Medium: containerized environments.
  const containerVars = [
    "KUBERNETES_SERVICE_HOST",
    "AWS_LAMBDA_FUNCTION_NAME",
    "VERCEL",
    "NETLIFY",
    "CODESPACE_NAME",
  ];
  if (
    containerVars.some((k) => typeof env[k] === "string" && env[k]!.length > 0) ||
    env.container === "docker"
  ) {
    score += 0.2;
  }

  // Weak: scripted invocations frequently set this.
  if (env.DEBIAN_FRONTEND === "noninteractive") score += 0.1;

  cachedBotLikelihood = Math.min(1, Math.max(0, score));
  return cachedBotLikelihood;
}

// Build a base payload populated with the install fingerprint + bot score.
// `installId` is omitted when telemetry is disabled or the install file
// could not be created. `botLikelihood` is always present (default 0).
function buildBase(): Pick<TelemetryPayload, "installId" | "botLikelihood"> {
  const out: Pick<TelemetryPayload, "installId" | "botLikelihood"> = {
    botLikelihood: computeBotLikelihood(),
  };
  const installId = loadOrCreateInstallId();
  if (installId) out.installId = installId;
  return out;
}

// Fire-and-forget POST of a single payload to the individual event endpoint.
// Used as fallback when batch delivery fails.
function sendSingle(payload: TelemetryPayload): void {
  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), TELEMETRY_TIMEOUT_MS);

    fetch(TELEMETRY_ENDPOINT, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(payload),
      signal: controller.signal,
    })
      .catch(() => {
        // Swallow all failures — network errors, DNS, timeouts, non-2xx.
        // Telemetry is best-effort.
      })
      .finally(() => {
        clearTimeout(timeoutId);
      });
  } catch {
    // Swallow synchronous throws too.
  }
}

// Fire-and-forget POST of a batch of payloads. Falls back to individual sends
// if the batch request fails.
function sendBatch(events: TelemetryPayload[]): void {
  if (events.length === 0) return;

  // Single-event shortcut: avoid the batch overhead.
  if (events.length === 1) {
    sendSingle(events[0]!);
    return;
  }

  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), TELEMETRY_TIMEOUT_MS);

    fetch(TELEMETRY_BATCH_ENDPOINT, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(events),
      signal: controller.signal,
    })
      .catch(() => {
        // Batch failed — fall back to individual sends so no data is lost.
        clearTimeout(timeoutId);
        for (const payload of events) {
          sendSingle(payload);
        }
      })
      .finally(() => {
        clearTimeout(timeoutId);
      });
  } catch {
    // Synchronous throw from fetch — fall back to individual sends.
    for (const payload of events) {
      sendSingle(payload);
    }
  }
}

// Schedule the 30-second auto-flush timer (idempotent — only one timer at a time).
function scheduleFlushTimer(): void {
  if (flushTimer !== undefined) return;
  flushTimer = setTimeout(() => {
    flushTimer = undefined;
    flushTelemetry();
  }, BATCH_FLUSH_INTERVAL_MS);
  // Allow Node.js to exit even if the timer is still pending.
  if (typeof flushTimer === "object" && flushTimer !== null && "unref" in flushTimer) {
    (flushTimer as { unref(): void }).unref();
  }
}

/**
 * Flush all buffered telemetry events immediately. Safe to call at any time.
 * Useful for graceful shutdown. Fire-and-forget — never throws.
 */
export function flushTelemetry(): void {
  if (flushTimer !== undefined) {
    clearTimeout(flushTimer);
    flushTimer = undefined;
  }
  if (buffer.length === 0) return;
  const snapshot = buffer.splice(0);
  sendBatch(snapshot);
}

// Push a payload into the buffer and flush when the batch is full.
function send(payload: TelemetryPayload): void {
  if (!isEnabled()) return;

  buffer.push(payload);
  scheduleFlushTimer();

  if (buffer.length >= BATCH_MAX_SIZE) {
    flushTelemetry();
  }
}

/** Exposed for tests — resets the cached client context and the batch buffer. */
export function __resetClientContext(): void {
  clientContext = undefined;
  cachedInstallId = undefined;
  cachedBotLikelihood = undefined;
  buffer = [];
  if (flushTimer !== undefined) {
    clearTimeout(flushTimer);
    flushTimer = undefined;
  }
}

/**
 * Record an MCP client handshake. Called from the server's `oninitialized`
 * callback once the client has advertised its name/version. Safe to call
 * with `undefined` if the client didn't report info — the event is dropped.
 */
export function recordClientInit(clientInfo: { name: string; version: string } | undefined): void {
  if (!clientInfo) return;

  clientContext = {
    client: clientInfo.name,
    clientVersion: clientInfo.version,
  };

  if (!isEnabled()) return;

  send({
    timestamp: new Date().toISOString(),
    event: "init",
    client: clientContext.client,
    clientVersion: clientContext.clientVersion,
    mcpVersion: getMcpVersion(),
    tier: "free",
    ...buildBase(),
  });
}

/**
 * Record a tool invocation. Called from the `CallToolRequestSchema` handler
 * after the tier check has passed. `tier` is the tier the tool resolved to,
 * not the user's subscription status.
 */
export function recordToolCall(toolName: string, tier: Tier): void {
  if (!isEnabled()) return;

  // If the client never completed initialization (which shouldn't happen in
  // practice), fall back to "unknown" so we can still see the tool mix.
  const ctx = clientContext ?? { client: "unknown", clientVersion: "unknown" };

  send({
    timestamp: new Date().toISOString(),
    event: "tool",
    client: ctx.client,
    clientVersion: ctx.clientVersion,
    mcpVersion: getMcpVersion(),
    tier,
    tool: toolName,
    ...buildBase(),
  });
}

// ─── Test-only exports ────────────────────────────────────────────────────────

/** Exposed for tests — bypass the cache and recompute the bot score. */
export function __computeBotLikelihoodForTest(): number {
  cachedBotLikelihood = undefined;
  return computeBotLikelihood();
}

/** Exposed for tests — read the install ID without going through send(). */
export function __getInstallIdForTest(): string | undefined {
  return loadOrCreateInstallId();
}
