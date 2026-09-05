/**
 * MCP Streamable HTTP transport (spec 2025-06-18) — minimal server impl.
 *
 * This module is intentionally decoupled from Hono: it consumes a
 * plain `Request` and returns a plain `Response`, so it can be mounted
 * from any route, called from tests, or re-used in a future durable
 * object implementation.
 *
 * What is implemented (MVP):
 *   - JSON-RPC 2.0 parse / validate / dispatch.
 *   - `initialize` handshake returning server capabilities.
 *   - `notifications/initialized` acknowledgement (200 with no body).
 *   - `tools/list` delegating to the multiplexed registry.
 *   - `tools/call` delegating to the multiplexed registry (`dispatch`).
 *   - `ping` (health).
 *   - `Mcp-Session-Id` header: new UUID minted on first request, stored
 *     in KV under `sess:{id}` with a sliding 30-minute TTL, and echoed
 *     on every response.
 *   - `Origin` header validation (anti-DNS-rebinding) with a default
 *     allowlist that always permits local development origins.
 *
 * Explicit NON-goals for this iteration:
 *   - Server-Sent Events (`text/event-stream`). The spec allows a pure
 *     JSON response when the client does not request SSE, and every
 *     current MCP client (Claude Desktop, Cursor, mcp-inspector) falls
 *     back to JSON transparently. A TODO below flags where to wire SSE
 *     when demand materialises.
 *   - Session replay / resume on reconnect. Not needed until SSE lands.
 *   - Per-request cancellation.
 *
 * References:
 *   - https://modelcontextprotocol.io/specification/2025-06-18/basic/transports
 *   - https://www.jsonrpc.org/specification
 */

import type { DispatchContext } from "./types.js";
import { dispatch as registryDispatch, getAllTools } from "./registry.js";
import { listWidgetResources, readWidgetResource } from "./widgets.js";
import { widgetResourceFor } from "./widget-tools.js";
import {
  loadSession,
  newSession,
  saveSession,
  SESSION_TTL_SECONDS,
  type SessionState,
} from "./session.js";

// ── JSON-RPC 2.0 shapes ────────────────────────────────────────────────────

/** JSON-RPC 2.0 request id type (string, number, or null per spec). */
export type JsonRpcId = string | number | null;

export interface JsonRpcRequest {
  jsonrpc: "2.0";
  id?: JsonRpcId;
  method: string;
  params?: Record<string, unknown>;
}

export interface JsonRpcError {
  code: number;
  message: string;
  data?: unknown;
}

export interface JsonRpcResponse {
  jsonrpc: "2.0";
  id: JsonRpcId;
  result?: unknown;
  error?: JsonRpcError;
}

// ── JSON-RPC error code constants ─────────────────────────────────────────

/** Standard JSON-RPC error codes. */
export const JSON_RPC_ERRORS = {
  PARSE_ERROR: -32700,
  INVALID_REQUEST: -32600,
  METHOD_NOT_FOUND: -32601,
  INVALID_PARAMS: -32602,
  INTERNAL_ERROR: -32603,
  // Custom gateway codes (reserved by the MCP server in -32000..-32099).
  UNAUTHORIZED: -32001,
  RATE_LIMITED: -32002,
  ACCESS_DENIED: -32003,
} as const;

// ── Transport configuration ────────────────────────────────────────────────

/** Caller-supplied context for a single MCP HTTP request. */
export interface TransportContext {
  /** KV namespace used for session storage. */
  kv: KVNamespace;
  /** Dispatch context forwarded to tool handlers (user, tier, ...). */
  dispatchContext?: DispatchContext;
  /** Optional allowed origins for anti-DNS-rebinding checks. */
  allowedOrigins?: string[];
  /** Optional tier-based access check for the given tool name. */
  canCallTool?: (toolName: string, ctx: DispatchContext | undefined) => boolean;
}

/**
 * Server metadata returned by `initialize`.
 *
 * Bumped in lockstep with `mcp-gateway/package.json`.
 */
const SERVER_INFO = {
  name: "sceneview-mcp-gateway",
  version: "1.0.0",
} as const;

/**
 * Protocol revisions implemented by this server, newest first.
 */
export const SUPPORTED_PROTOCOL_VERSIONS = ["2025-06-18", "2025-03-26"] as const;
export const PROTOCOL_VERSION = SUPPORTED_PROTOCOL_VERSIONS[0];

/** Default origins always allowed even when a caller passes an allowlist. */
const DEFAULT_ORIGIN_ALLOWLIST = [
  "http://localhost",
  "http://127.0.0.1",
  "https://localhost",
  "https://127.0.0.1",
];

// ── Public entrypoint ──────────────────────────────────────────────────────

/**
 * Entry point: handle an incoming MCP HTTP request end-to-end.
 *
 * Returns a JSON `Response` with the right `Mcp-Session-Id` header and,
 * when possible, the JSON-RPC-level result or error. Network-level
 * failures (bad origin, unsupported method) surface as HTTP status codes
 * because JSON-RPC has no vocabulary for them.
 */
export async function handleMcpRequest(
  request: Request,
  ctx: TransportContext,
): Promise<Response> {
  // MCP Streamable HTTP only defines POST for client → server messages.
  // GET is reserved for an SSE long-poll that we do not implement yet.
  if (request.method === "OPTIONS") {
    return corsPreflight();
  }

  if (request.method === "GET") {
    // TODO: implement SSE long-poll here (Accept: text/event-stream) when
    // Claude Desktop and other clients start exercising it. For now, 501.
    return httpJson(
      { error: "Not Implemented", detail: "SSE stream not supported yet." },
      501,
    );
  }

  if (request.method !== "POST") {
    return httpJson({ error: "Method Not Allowed" }, 405);
  }

  // Content-type must be JSON for the MVP (the spec also allows
  // multipart for batching, which we ignore).
  const contentType = request.headers.get("content-type") || "";
  if (!contentType.toLowerCase().includes("application/json")) {
    return httpJson(
      { error: "Unsupported Media Type", detail: "expected application/json" },
      415,
    );
  }

  // Anti-DNS-rebinding: if an Origin header is present, validate it.
  // No Origin at all is fine (e.g. curl, server-to-server).
  const origin = request.headers.get("origin");
  if (origin && !isOriginAllowed(origin, ctx.allowedOrigins)) {
    return httpJson(
      { error: "Forbidden", detail: "origin not allowed" },
      403,
    );
  }

  // Session id: echo the one the client sent, or mint a new one.
  const incomingSessionId = request.headers.get("mcp-session-id") || "";
  const session = await resolveSession(ctx.kv, incomingSessionId);

  // Read raw body and parse JSON.
  let parsed: unknown;
  try {
    const raw = await request.text();
    parsed = raw ? JSON.parse(raw) : undefined;
  } catch {
    return jsonRpcErrorResponse(null, {
      code: JSON_RPC_ERRORS.PARSE_ERROR,
      message: "Parse error",
    }, session);
  }

  // Support single requests for the MVP. Batch requests (arrays) are a
  // JSON-RPC 2.0 feature but not common in MCP clients; they would fan
  // out here if we decide to support them.
  if (Array.isArray(parsed)) {
    return jsonRpcErrorResponse(null, {
      code: JSON_RPC_ERRORS.INVALID_REQUEST,
      message: "Batch requests are not supported",
    }, session);
  }

  const req = asJsonRpcRequest(parsed);
  if (!req) {
    return jsonRpcErrorResponse(null, {
      code: JSON_RPC_ERRORS.INVALID_REQUEST,
      message: "Invalid Request",
    }, session);
  }

  // `initialize` negotiates versions in its JSON body. Every later
  // Streamable HTTP request may carry MCP-Protocol-Version; reject an
  // explicit version this server does not implement. As in the reference
  // SDK, an absent header is accepted for backwards compatibility and the
  // session's negotiated version remains in force.
  if (req.method !== "initialize") {
    const protocolVersion = request.headers.get("mcp-protocol-version");
    if (
      protocolVersion !== null &&
      !SUPPORTED_PROTOCOL_VERSIONS.includes(
        protocolVersion as (typeof SUPPORTED_PROTOCOL_VERSIONS)[number],
      )
    ) {
      return httpJson(
        {
          jsonrpc: "2.0",
          id: req.id ?? null,
          error: {
            code: -32000,
            message: `Bad Request: Unsupported protocol version: ${protocolVersion} (supported versions: ${SUPPORTED_PROTOCOL_VERSIONS.join(", ")})`,
          },
        },
        400,
      );
    }
  }

  // Notifications have no `id` and must not produce a JSON-RPC reply.
  const isNotification = req.id === undefined;

  try {
    const result = await routeMethod(req, ctx, session);

    // Persist any session-state mutations made while handling the request.
    await saveSession(ctx.kv, session);

    if (isNotification) {
      return new Response(null, {
        status: 202,
        headers: sessionHeaders(session),
      });
    }

    return jsonRpcSuccessResponse(req.id ?? null, result, session);
  } catch (err) {
    // If the handler threw a structured JSON-RPC error, propagate the
    // original code/data. Otherwise, fall back to INTERNAL_ERROR.
    const e = err as Error & { jsonRpcCode?: number; jsonRpcData?: unknown };
    const code =
      typeof e.jsonRpcCode === "number"
        ? e.jsonRpcCode
        : JSON_RPC_ERRORS.INTERNAL_ERROR;
    const message = e.message || "Internal error";
    const error: JsonRpcError = { code, message };
    if (e.jsonRpcData !== undefined) error.data = e.jsonRpcData;
    // Persist any session changes before returning the error.
    await saveSession(ctx.kv, session);
    return jsonRpcErrorResponse(req.id ?? null, error, session);
  }
}

// ── Method routing ─────────────────────────────────────────────────────────

/** Dispatches a JSON-RPC request to the matching MCP method. */
async function routeMethod(
  req: JsonRpcRequest,
  ctx: TransportContext,
  session: SessionState,
): Promise<unknown> {
  switch (req.method) {
    case "initialize":
      return handleInitialize(req, session);

    case "notifications/initialized":
    case "initialized": {
      // Notification sent by the client after `initialize` — no reply.
      session.initialized = true;
      return null;
    }

    case "ping":
      return {};

    case "tools/list":
      return handleToolsList();

    case "tools/call":
      return handleToolsCall(req, ctx);

    // ── OpenAI Apps SDK widget support (resource registry) ────────────────
    case "resources/list":
      return { resources: listWidgetResources() };

    case "resources/read":
      return handleResourcesRead(req);

    case "resources/templates/list":
      // No URI templates exposed today — empty list keeps the spec happy.
      return { resourceTemplates: [] };

    case "prompts/list":
      // No prompts exposed; the gateway is purely tool + widget driven.
      return { prompts: [] };

    default:
      throw jsonRpcError(
        JSON_RPC_ERRORS.METHOD_NOT_FOUND,
        `Method not found: ${req.method}`,
      );
  }
}

// ── Method handlers ────────────────────────────────────────────────────────

/** Handles the `initialize` handshake. */
function handleInitialize(
  req: JsonRpcRequest,
  session: SessionState,
): unknown {
  const params = (req.params ?? {}) as Record<string, unknown>;
  const requestedVersion = params.protocolVersion;
  const protocolVersion =
    typeof requestedVersion === "string" &&
    SUPPORTED_PROTOCOL_VERSIONS.includes(
      requestedVersion as (typeof SUPPORTED_PROTOCOL_VERSIONS)[number],
    )
      ? requestedVersion
      : PROTOCOL_VERSION;
  session.protocolVersion = protocolVersion;
  const clientInfo = params.clientInfo as
    | { name?: string; version?: string }
    | undefined;
  if (clientInfo) {
    session.clientInfo = {
      name: typeof clientInfo.name === "string" ? clientInfo.name : undefined,
      version:
        typeof clientInfo.version === "string" ? clientInfo.version : undefined,
    };
  }
  return {
    protocolVersion,
    capabilities: {
      tools: { listChanged: false },
      // Resources advertise the OpenAI Apps SDK widget templates served
      // by `widgets.ts` (currently the 3D viewer at
      // `ui://widget/3d-viewer.html`). Without this capability ChatGPT
      // never asks `resources/read`, so the widget pointer attached to
      // tool results would be silently ignored.
      resources: { listChanged: false, subscribe: false },
      // Empty prompts capability — keeps clients that probe for it happy
      // without inventing prompt content.
      prompts: { listChanged: false },
    },
    serverInfo: SERVER_INFO,
  };
}

/** Handles `resources/read` for widget HTML bundles. */
function handleResourcesRead(req: JsonRpcRequest): unknown {
  const params = (req.params ?? {}) as Record<string, unknown>;
  const uri = params.uri;
  if (typeof uri !== "string" || uri.length === 0) {
    throw jsonRpcError(
      JSON_RPC_ERRORS.INVALID_PARAMS,
      "Missing or invalid 'uri' parameter",
    );
  }
  const resource = readWidgetResource(uri);
  if (!resource) {
    throw jsonRpcError(
      JSON_RPC_ERRORS.METHOD_NOT_FOUND,
      `Resource not found: ${uri}`,
      { uri },
    );
  }
  return { contents: [resource] };
}

/**
 * Returns the full list of multiplexed tool definitions.
 *
 * Widget-bearing tools carry `_meta.ui.resourceUri` on the DECLARATION as
 * well as on the result (see `handleToolsCall`): a host that decides from
 * `tools/list` whether a tool has a UI never sees a result first, so a
 * pointer that only rides on results is invisible to it (#3192).
 */
function handleToolsList(): unknown {
  const tools = getAllTools().map((def) => {
    const widgetUri = widgetResourceFor(def.name);
    // Merge rather than replace: the upstream declaration already carries
    // the pointer plus the OpenAI Apps SDK keys (`openai/outputTemplate`…).
    return widgetUri
      ? { ...def, _meta: { ...(def._meta ?? {}), ui: { resourceUri: widgetUri } } }
      : def;
  });
  return { tools };
}

/** Validates params and dispatches a `tools/call` to the registry. */
async function handleToolsCall(
  req: JsonRpcRequest,
  ctx: TransportContext,
): Promise<unknown> {
  const params = (req.params ?? {}) as Record<string, unknown>;
  const toolName = params.name;
  if (typeof toolName !== "string" || toolName.length === 0) {
    throw jsonRpcError(
      JSON_RPC_ERRORS.INVALID_PARAMS,
      "Missing or invalid 'name' parameter",
    );
  }
  const args =
    params.arguments &&
    typeof params.arguments === "object" &&
    !Array.isArray(params.arguments)
      ? (params.arguments as Record<string, unknown>)
      : undefined;

  // Tier gate: the registry cannot enforce access rules on its own, so
  // the transport asks the caller via `canCallTool`. A `false` result
  // becomes a JSON-RPC `ACCESS_DENIED` error.
  if (ctx.canCallTool && !ctx.canCallTool(toolName, ctx.dispatchContext)) {
    throw jsonRpcError(
      JSON_RPC_ERRORS.ACCESS_DENIED,
      `Access denied for tool: ${toolName}`,
      { toolName },
    );
  }

  const result = await registryDispatch(toolName, args, ctx.dispatchContext);

  // Widget tools attach `_meta.ui.resourceUri` so MCP-Apps-aware clients
  // know to fetch the bundled HTML widget and render it inline. The same
  // pointer is on the tool declaration (`handleToolsList`). Other clients
  // ignore the unknown `_meta` keys and just see the text content.
  const widgetUri = widgetResourceFor(toolName);
  if (widgetUri) {
    // Narrow through `unknown` to avoid the strict-overlap check on
    // ToolResult (which has its own optional `_meta` field) — every
    // consumer of the JSON-RPC response treats unknown keys as opaque.
    const r = result as unknown as {
      _meta?: Record<string, unknown>;
      [k: string]: unknown;
    };
    r._meta = {
      ...(r._meta ?? {}),
      ui: { resourceUri: widgetUri },
    };
    return r;
  }
  return result;
}

// ── Helpers: request / response encoding ──────────────────────────────────

/** Returns the request cast to JsonRpcRequest, or null if invalid. */
function asJsonRpcRequest(raw: unknown): JsonRpcRequest | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  if (r.jsonrpc !== "2.0") return null;
  if (typeof r.method !== "string") return null;
  // id may be string, number, null, or undefined (notification).
  const id = r.id as unknown;
  const idOk =
    id === undefined ||
    id === null ||
    typeof id === "string" ||
    typeof id === "number";
  if (!idOk) return null;
  const params =
    r.params && typeof r.params === "object" && !Array.isArray(r.params)
      ? (r.params as Record<string, unknown>)
      : undefined;
  const out: JsonRpcRequest = {
    jsonrpc: "2.0",
    method: r.method,
    ...(params ? { params } : {}),
  };
  if (id !== undefined) out.id = id as JsonRpcId;
  return out;
}

/** Builds a JSON-RPC success Response with the session header set. */
function jsonRpcSuccessResponse(
  id: JsonRpcId,
  result: unknown,
  session: SessionState,
): Response {
  const body: JsonRpcResponse = {
    jsonrpc: "2.0",
    id,
    result,
  };
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: {
      "content-type": "application/json",
      ...sessionHeaders(session),
    },
  });
}

/** Builds a JSON-RPC error Response with the session header set. */
function jsonRpcErrorResponse(
  id: JsonRpcId,
  error: JsonRpcError,
  session: SessionState,
): Response {
  const body: JsonRpcResponse = {
    jsonrpc: "2.0",
    id,
    error,
  };
  // HTTP 200 even for JSON-RPC errors — the error is in the body.
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: {
      "content-type": "application/json",
      ...sessionHeaders(session),
    },
  });
}

/** Plain HTTP JSON response (for transport-layer errors). */
function httpJson(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

/** Session headers emitted on every response. */
function sessionHeaders(session: SessionState): Record<string, string> {
  return {
    "mcp-session-id": session.id,
    "cache-control": "no-store",
  };
}

/** Minimal CORS preflight response. */
function corsPreflight(): Response {
  return new Response(null, {
    status: 204,
    headers: {
      "access-control-allow-origin": "*",
      "access-control-allow-methods": "POST, OPTIONS",
      "access-control-allow-headers":
        "content-type, authorization, mcp-session-id, mcp-protocol-version",
      "access-control-max-age": "600",
    },
  });
}

/** Returns true if an origin is on the default or caller-supplied allowlist. */
function isOriginAllowed(origin: string, allowlist?: string[]): boolean {
  const list = [...DEFAULT_ORIGIN_ALLOWLIST, ...(allowlist ?? [])];
  for (const prefix of list) {
    if (origin === prefix) return true;
    if (origin.startsWith(prefix + ":")) return true;
    if (origin.startsWith(prefix + "/")) return true;
  }
  return false;
}

// ── Session resolution ─────────────────────────────────────────────────────

/** Returns an existing session from KV or mints a new one. */
async function resolveSession(
  kv: KVNamespace,
  incomingId: string,
): Promise<SessionState> {
  if (incomingId) {
    const existing = await loadSession(kv, incomingId);
    if (existing) return existing;
  }
  return newSession();
}

// ── Exposed for tests only ────────────────────────────────────────────────

/** Test helper: build a structured JSON-RPC error used by thrown errors. */
function jsonRpcError(
  code: number,
  message: string,
  data?: unknown,
): Error & { jsonRpcCode: number; jsonRpcData?: unknown } {
  const err = new Error(message) as Error & {
    jsonRpcCode: number;
    jsonRpcData?: unknown;
  };
  err.jsonRpcCode = code;
  if (data !== undefined) err.jsonRpcData = data;
  return err;
}

/** TTL constant re-exported so routes/tests can depend on the same value. */
export { SESSION_TTL_SECONDS };
