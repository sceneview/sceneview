/**
 * MCP session management on top of Workers KV.
 *
 * The Streamable HTTP transport (spec 2025-06-18) assigns each logical
 * client session a server-generated identifier that is echoed back on
 * every subsequent request through the `Mcp-Session-Id` header. This
 * module provides the small surface needed by `transport.ts`:
 *
 *   - {@link generateSessionId}: create a fresh UUID v4 session id.
 *   - {@link loadSession}: read session state from KV by id.
 *   - {@link saveSession}: write session state to KV with a sliding TTL.
 *
 * KV key layout:
 *   sess:{id} → JSON SessionState
 * TTL: 1800 s (30 min) on each write, which effectively slides on use.
 *
 * The state we persist today is intentionally minimal (initialize flag
 * and client info). Future tickets can extend it without a migration
 * because everything is JSON-shaped.
 */

/** Minimal session snapshot shared between requests within a session. */
export interface SessionState {
  /** Server-generated unique session identifier (UUID v4). */
  id: string;
  /** Whether the client has completed the MCP `initialize` handshake. */
  initialized: boolean;
  /** Protocol revision selected during `initialize`. */
  protocolVersion?: string;
  /** Optional client info captured during `initialize`. */
  clientInfo?: {
    name?: string;
    version?: string;
  };
  /** Unix epoch ms the session was created. */
  createdAt: number;
}

/** KV key prefix used for session storage. */
export const SESSION_KEY_PREFIX = "sess:";

/** Sliding TTL applied on every session write, in seconds. */
export const SESSION_TTL_SECONDS = 1800;

/**
 * Generates a fresh RFC 4122 v4 UUID using the Web Crypto API.
 *
 * `crypto.randomUUID()` is available in both the Workers runtime and
 * modern Node test environments, so no polyfill is needed.
 */
export function generateSessionId(): string {
  return crypto.randomUUID();
}

/** Reads a session snapshot from KV by id. Returns null if missing or malformed. */
export async function loadSession(
  kv: KVNamespace,
  id: string,
): Promise<SessionState | null> {
  if (!id) return null;
  const raw = await kv.get(SESSION_KEY_PREFIX + id);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as SessionState;
    if (parsed && typeof parsed.id === "string") return parsed;
    return null;
  } catch {
    return null;
  }
}

/** Persists a session snapshot to KV with the sliding TTL. */
export async function saveSession(
  kv: KVNamespace,
  state: SessionState,
): Promise<void> {
  await kv.put(SESSION_KEY_PREFIX + state.id, JSON.stringify(state), {
    expirationTtl: SESSION_TTL_SECONDS,
  });
}

/** Builds a fresh, uninitialized session snapshot. */
export function newSession(): SessionState {
  return {
    id: generateSessionId(),
    initialized: false,
    createdAt: Date.now(),
  };
}
