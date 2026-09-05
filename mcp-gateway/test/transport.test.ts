/**
 * Unit tests for the MCP Streamable HTTP transport.
 *
 * These tests exercise `handleMcpRequest` directly with mocked KV and
 * a stub dispatch context — no Hono, no D1, no real Workers runtime.
 */

import { describe, expect, it } from "vitest";
import {
  handleMcpRequest,
  JSON_RPC_ERRORS,
  SUPPORTED_PROTOCOL_VERSIONS,
  type JsonRpcResponse,
} from "../src/mcp/transport.js";
import { MockKv } from "./helpers/mock-kv.js";

function mcpRequest(body: unknown, headers: Record<string, string> = {}) {
  return new Request("https://example.com/mcp", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function asJsonRpc(res: Response): Promise<JsonRpcResponse> {
  return (await res.json()) as JsonRpcResponse;
}

describe("transport: initialize handshake", () => {
  it("returns protocol version + capabilities + serverInfo", async () => {
    const kv = new MockKv();
    const res = await handleMcpRequest(
      mcpRequest({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: {
          protocolVersion: "2025-03-26",
          clientInfo: { name: "test-client", version: "0.0.1" },
        },
      }),
      { kv: kv.asKv() },
    );
    expect(res.status).toBe(200);
    expect(res.headers.get("mcp-session-id")).toBeTruthy();
    const body = await asJsonRpc(res);
    expect(body.error).toBeUndefined();
    const result = body.result as {
      protocolVersion: string;
      capabilities: { tools: { listChanged: boolean } };
      serverInfo: { name: string; version: string };
    };
    expect(result.protocolVersion).toBe("2025-03-26");
    expect(result.capabilities.tools).toBeDefined();
    expect(result.serverInfo.name).toBe("sceneview-mcp-gateway");
  });

  it("negotiates the latest supported version when the client version is unknown", async () => {
    const res = await handleMcpRequest(
      mcpRequest({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: { protocolVersion: "2099-01-01" },
      }),
      { kv: new MockKv().asKv() },
    );
    const body = await asJsonRpc(res);
    expect((body.result as { protocolVersion: string }).protocolVersion).toBe(
      "2025-06-18",
    );
  });

  it("accepts the latest supported client version", async () => {
    const res = await handleMcpRequest(
      mcpRequest({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: { protocolVersion: "2025-06-18" },
      }),
      { kv: new MockKv().asKv() },
    );
    const body = await asJsonRpc(res);
    expect((body.result as { protocolVersion: string }).protocolVersion).toBe(
      "2025-06-18",
    );
  });

  it("acknowledges the initialized notification with 202 and no body", async () => {
    const kv = new MockKv();
    const res = await handleMcpRequest(
      mcpRequest({
        jsonrpc: "2.0",
        method: "notifications/initialized",
      }),
      { kv: kv.asKv() },
    );
    expect(res.status).toBe(202);
    expect(res.headers.get("mcp-session-id")).toBeTruthy();
  });
});

describe("transport: tools/list", () => {
  it("returns the multiplexed tool list", async () => {
    const kv = new MockKv();
    const res = await handleMcpRequest(
      mcpRequest({ jsonrpc: "2.0", id: 2, method: "tools/list" }),
      { kv: kv.asKv() },
    );
    expect(res.status).toBe(200);
    const body = await asJsonRpc(res);
    const result = body.result as { tools: { name: string }[] };
    expect(Array.isArray(result.tools)).toBe(true);
    // The multiplexed registry must have at least the known free tools.
    const names = new Set(result.tools.map((t) => t.name));
    expect(names.has("list_samples")).toBe(true);
    expect(names.has("get_sample")).toBe(true);
  });

  it("declares the widget pointer on the tool itself, not only on results", async () => {
    const kv = new MockKv();
    const res = await handleMcpRequest(
      mcpRequest({ jsonrpc: "2.0", id: 3, method: "tools/list" }),
      { kv: kv.asKv() },
    );
    const body = await asJsonRpc(res);
    const result = body.result as {
      tools: { name: string; _meta?: { ui?: { resourceUri?: string } } }[];
    };
    const widget = result.tools.find((t) => t.name === "view_3d_model");
    expect(widget?._meta?.ui?.resourceUri).toBe("ui://widget/3d-viewer.html");
    // A host deciding from tools/list must not see phantom widgets either.
    const plain = result.tools.find((t) => t.name === "list_samples");
    expect(plain?._meta).toBeUndefined();
  });

  it("keeps the OpenAI Apps SDK keys alongside the MCP Apps pointer", async () => {
    // The transport re-affirms `_meta.ui.resourceUri` on top of the upstream
    // declaration; overwriting `_meta` (or `_meta.ui`) wholesale would strip
    // the `openai/*` spellings the live ChatGPT listing renders from.
    const res = await handleMcpRequest(
      mcpRequest({ jsonrpc: "2.0", id: 3, method: "tools/list" }),
      { kv: new MockKv().asKv() },
    );
    const body = await asJsonRpc(res);
    const result = body.result as {
      tools: { name: string; _meta?: Record<string, unknown> }[];
    };
    const widget = result.tools.find((t) => t.name === "view_3d_model");
    expect(widget?._meta?.["openai/outputTemplate"]).toBe("ui://widget/3d-viewer.html");
  });

  it("still declares the widget to a client that named no extension", async () => {
    // Every host predating SEP-1724 — ChatGPT included — sends no `extensions`
    // block. Silence must stay permissive or the live listing goes dark.
    const kv = new MockKv();
    const init = await handleMcpRequest(
      mcpRequest({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: { protocolVersion: "2025-06-18", capabilities: {} },
      }),
      { kv: kv.asKv() },
    );
    const sessionId = init.headers.get("mcp-session-id") as string;
    const res = await handleMcpRequest(
      mcpRequest({ jsonrpc: "2.0", id: 2, method: "tools/list" }, {
        "mcp-session-id": sessionId,
      }),
      { kv: kv.asKv() },
    );
    const result = (await asJsonRpc(res)).result as {
      tools: { name: string; _meta?: { ui?: { resourceUri?: string } } }[];
    };
    const widget = result.tools.find((t) => t.name === "view_3d_model");
    expect(widget?._meta?.ui?.resourceUri).toBe("ui://widget/3d-viewer.html");
  });

  it("degrades to text for a client that negotiated MCP Apps without our mime type", async () => {
    const kv = new MockKv();
    const init = await handleMcpRequest(
      mcpRequest({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: {
          protocolVersion: "2025-06-18",
          capabilities: {
            extensions: { "io.modelcontextprotocol/ui": { mimeTypes: ["text/uri-list"] } },
          },
        },
      }),
      { kv: kv.asKv() },
    );
    const sessionId = init.headers.get("mcp-session-id") as string;
    const res = await handleMcpRequest(
      mcpRequest({ jsonrpc: "2.0", id: 2, method: "tools/list" }, {
        "mcp-session-id": sessionId,
      }),
      { kv: kv.asKv() },
    );
    const result = (await asJsonRpc(res)).result as {
      tools: { name: string; _meta?: { ui?: { resourceUri?: string } } }[];
    };
    // The tool stays listed and callable — only the UI pointer is withheld,
    // which is the graceful degradation the extension spec asks for.
    const widget = result.tools.find((t) => t.name === "view_3d_model");
    expect(widget).toBeDefined();
    expect(widget?._meta?.ui?.resourceUri).toBeUndefined();
  });

  it("advertises outputSchema only for structured tools", async () => {
    const res = await handleMcpRequest(
      mcpRequest({ jsonrpc: "2.0", id: 2, method: "tools/list" }),
      { kv: new MockKv().asKv() },
    );
    const body = await asJsonRpc(res);
    const result = body.result as {
      tools: { name: string; outputSchema?: unknown }[];
    };
    expect(result.tools.filter((tool) => tool.outputSchema).map((tool) => tool.name).sort()).toEqual([
      "create_3d_artifact",
      "embed_web_viewer",
      "list_car_models",
      "list_furniture_models",
      "list_game_models",
      "list_medical_models",
      "list_platforms",
      "render_3d_preview",
      "setup_rerun_project",
      "validate_automotive_code",
      "validate_game_code",
      "validate_interior_code",
      "validate_medical_code",
      "view_3d_model",
    ]);
  });
});

describe("transport: MCP Apps extension + server/discover (#3192)", () => {
  it("declares the MCP Apps extension in the initialize result", async () => {
    const res = await handleMcpRequest(
      mcpRequest({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: { protocolVersion: "2025-06-18", capabilities: {} },
      }),
      { kv: new MockKv().asKv() },
    );
    const result = (await asJsonRpc(res)).result as {
      capabilities: { extensions?: Record<string, { mimeTypes?: string[] }> };
    };
    expect(result.capabilities.extensions).toBeDefined();
    expect(result.capabilities.extensions?.["io.modelcontextprotocol/ui"]).toEqual({
      mimeTypes: ["text/html;profile=mcp-app"],
    });
  });

  it("declares it on the OLDEST revision it serves too", async () => {
    // The extensions framework is versioned into 2026-07-28, but the ext-apps
    // spec advertises the very same capability over `2024-11-05` in its own
    // example: it is additive, and a peer that does not know the key ignores
    // it. Backward compatibility is the point, not a caveat.
    const res = await handleMcpRequest(
      mcpRequest({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: { protocolVersion: "2025-03-26" },
      }),
      { kv: new MockKv().asKv() },
    );
    const result = (await asJsonRpc(res)).result as {
      protocolVersion: string;
      capabilities: { extensions?: Record<string, unknown> };
    };
    expect(result.protocolVersion).toBe("2025-03-26");
    expect(result.capabilities.extensions?.["io.modelcontextprotocol/ui"]).toBeDefined();
  });

  it("answers server/discover with no session and no handshake", async () => {
    // A 2026-07-28 client never sends `initialize`, so this is the only place
    // it can read the extension declaration — and it beats a bare -32601.
    const res = await handleMcpRequest(
      mcpRequest({ jsonrpc: "2.0", id: 1, method: "server/discover", params: {} }),
      { kv: new MockKv().asKv() },
    );
    expect(res.status).toBe(200);
    const body = await asJsonRpc(res);
    expect(body.error).toBeUndefined();
    const result = body.result as {
      resultType: string;
      ttlMs: number;
      cacheScope: string;
      supportedVersions: string[];
      capabilities: { extensions?: Record<string, unknown> };
      serverInfo: { name: string };
    };
    // The five fields the 2026-07-28 DiscoverResult schema requires.
    for (const field of ["cacheScope", "capabilities", "resultType", "supportedVersions", "ttlMs"]) {
      expect(result, `missing required field: ${field}`).toHaveProperty(field);
    }
    expect(result.resultType).toBe("complete");
    expect(result.cacheScope).toBe("public");
    expect(result.ttlMs).toBeGreaterThan(0);
    expect(result.serverInfo.name).toBe("sceneview-mcp-gateway");
    expect(result.capabilities.extensions?.["io.modelcontextprotocol/ui"]).toBeDefined();
  });

  it("advertises through discover only the revisions it really implements", async () => {
    // Announcing 2026-07-28 here would be the actual bug: none of its
    // per-request `_meta` versioning or result envelopes is implemented.
    const res = await handleMcpRequest(
      mcpRequest({ jsonrpc: "2.0", id: 1, method: "server/discover" }),
      { kv: new MockKv().asKv() },
    );
    const result = (await asJsonRpc(res)).result as { supportedVersions: string[] };
    expect(result.supportedVersions).toEqual([...SUPPORTED_PROTOCOL_VERSIONS]);
    expect(result.supportedVersions).not.toContain("2026-07-28");
  });

  it("gives discover and initialize the same capabilities, from one source", async () => {
    const kv = new MockKv();
    const init = await handleMcpRequest(
      mcpRequest({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: { protocolVersion: "2025-06-18" },
      }),
      { kv: kv.asKv() },
    );
    const discover = await handleMcpRequest(
      mcpRequest({ jsonrpc: "2.0", id: 2, method: "server/discover" }),
      { kv: kv.asKv() },
    );
    const initCaps = ((await asJsonRpc(init)).result as { capabilities: unknown }).capabilities;
    const discCaps = ((await asJsonRpc(discover)).result as { capabilities: unknown }).capabilities;
    expect(discCaps).toEqual(initCaps);
  });
});

describe("transport: tools/call", () => {
  it("returns schema-matching structuredContent for a structured tool", async () => {
    const res = await handleMcpRequest(
      mcpRequest({
        jsonrpc: "2.0",
        id: 3,
        method: "tools/call",
        params: {
          name: "view_3d_model",
          arguments: { modelUrl: "https://example.com/chair.glb" },
        },
      }),
      { kv: new MockKv().asKv() },
    );
    const body = await asJsonRpc(res);
    const result = body.result as { structuredContent?: Record<string, unknown> };
    expect(result.structuredContent).toMatchObject({
      modelUrl: "https://example.com/chair.glb",
      title: "3D model",
      autoRotate: true,
      ar: true,
      alt: "3D model",
    });
  });

  it("routes to the sceneview-mcp handler for a known free tool", async () => {
    const kv = new MockKv();
    const res = await handleMcpRequest(
      mcpRequest({
        jsonrpc: "2.0",
        id: 3,
        method: "tools/call",
        params: { name: "list_samples", arguments: {} },
      }),
      { kv: kv.asKv() },
    );
    expect(res.status).toBe(200);
    const body = await asJsonRpc(res);
    expect(body.error).toBeUndefined();
    const result = body.result as { content: { type: string; text: string }[] };
    expect(result.content?.[0]?.type).toBe("text");
    expect(typeof result.content[0].text).toBe("string");
  });

  it("returns INVALID_PARAMS when the tool name is missing", async () => {
    const kv = new MockKv();
    const res = await handleMcpRequest(
      mcpRequest({
        jsonrpc: "2.0",
        id: 4,
        method: "tools/call",
        params: {},
      }),
      { kv: kv.asKv() },
    );
    const body = await asJsonRpc(res);
    expect(body.error).toBeDefined();
    expect(body.error?.code).toBe(JSON_RPC_ERRORS.INVALID_PARAMS);
  });

  it("returns ACCESS_DENIED when the caller rejects the tier check", async () => {
    const kv = new MockKv();
    const res = await handleMcpRequest(
      mcpRequest({
        jsonrpc: "2.0",
        id: 5,
        method: "tools/call",
        params: { name: "generate_scene", arguments: {} },
      }),
      {
        kv: kv.asKv(),
        dispatchContext: { tier: "free" },
        canCallTool: () => false,
      },
    );
    const body = await asJsonRpc(res);
    expect(body.error).toBeDefined();
    expect(body.error?.code).toBe(JSON_RPC_ERRORS.ACCESS_DENIED);
  });
});

describe("transport: JSON-RPC errors", () => {
  it("parse error on malformed JSON body", async () => {
    const kv = new MockKv();
    const res = await handleMcpRequest(
      new Request("https://example.com/mcp", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: "{not json",
      }),
      { kv: kv.asKv() },
    );
    const body = await asJsonRpc(res);
    expect(body.error?.code).toBe(JSON_RPC_ERRORS.PARSE_ERROR);
  });

  it("invalid request when jsonrpc field is missing", async () => {
    const kv = new MockKv();
    const res = await handleMcpRequest(
      mcpRequest({ id: 1, method: "initialize" }),
      { kv: kv.asKv() },
    );
    const body = await asJsonRpc(res);
    expect(body.error?.code).toBe(JSON_RPC_ERRORS.INVALID_REQUEST);
  });

  it("method not found for unknown method", async () => {
    const kv = new MockKv();
    const res = await handleMcpRequest(
      mcpRequest({ jsonrpc: "2.0", id: 7, method: "does_not_exist" }),
      { kv: kv.asKv() },
    );
    const body = await asJsonRpc(res);
    expect(body.error?.code).toBe(JSON_RPC_ERRORS.METHOD_NOT_FOUND);
  });

  it("rejects batch requests with INVALID_REQUEST", async () => {
    const kv = new MockKv();
    const res = await handleMcpRequest(
      mcpRequest([
        { jsonrpc: "2.0", id: 1, method: "ping" },
        { jsonrpc: "2.0", id: 2, method: "ping" },
      ]),
      { kv: kv.asKv() },
    );
    const body = await asJsonRpc(res);
    expect(body.error?.code).toBe(JSON_RPC_ERRORS.INVALID_REQUEST);
  });
});

describe("transport: session id", () => {
  it("mints a new session id on the first request and echoes it", async () => {
    const kv = new MockKv();
    const res = await handleMcpRequest(
      mcpRequest({ jsonrpc: "2.0", id: 1, method: "ping" }),
      { kv: kv.asKv() },
    );
    const sessionId = res.headers.get("mcp-session-id");
    expect(sessionId).toBeTruthy();
    expect(kv.store.has(`sess:${sessionId}`)).toBe(true);
  });

  it("preserves an existing session id when the client sends one", async () => {
    const kv = new MockKv();
    // First call: mint a session.
    const first = await handleMcpRequest(
      mcpRequest({ jsonrpc: "2.0", id: 1, method: "ping" }),
      { kv: kv.asKv() },
    );
    const sessionId = first.headers.get("mcp-session-id") as string;

    // Second call with the same session id should reuse it.
    const second = await handleMcpRequest(
      mcpRequest(
        { jsonrpc: "2.0", id: 2, method: "ping" },
        { "mcp-session-id": sessionId },
      ),
      { kv: kv.asKv() },
    );
    expect(second.headers.get("mcp-session-id")).toBe(sessionId);
  });
});

describe("transport: origin validation", () => {
  it("allows localhost by default", async () => {
    const kv = new MockKv();
    const res = await handleMcpRequest(
      mcpRequest(
        { jsonrpc: "2.0", id: 1, method: "ping" },
        { origin: "http://localhost:3000" },
      ),
      { kv: kv.asKv() },
    );
    expect(res.status).toBe(200);
  });

  it("rejects an origin that is not on the allowlist", async () => {
    const kv = new MockKv();
    const res = await handleMcpRequest(
      mcpRequest(
        { jsonrpc: "2.0", id: 1, method: "ping" },
        { origin: "https://evil.example.com" },
      ),
      { kv: kv.asKv() },
    );
    expect(res.status).toBe(403);
  });

  it("allows an origin when explicitly on the caller-supplied allowlist", async () => {
    const kv = new MockKv();
    const res = await handleMcpRequest(
      mcpRequest(
        { jsonrpc: "2.0", id: 1, method: "ping" },
        { origin: "https://dashboard.sceneview.dev" },
      ),
      {
        kv: kv.asKv(),
        allowedOrigins: ["https://dashboard.sceneview.dev"],
      },
    );
    expect(res.status).toBe(200);
  });
});

describe("transport: HTTP-level protections", () => {
  it("accepts a supported MCP-Protocol-Version header", async () => {
    const res = await handleMcpRequest(
      mcpRequest(
        { jsonrpc: "2.0", id: 1, method: "ping" },
        { "mcp-protocol-version": "2025-06-18" },
      ),
      { kv: new MockKv().asKv() },
    );
    expect(res.status).toBe(200);
  });

  it("accepts an absent MCP-Protocol-Version header for compatibility", async () => {
    const res = await handleMcpRequest(
      mcpRequest({ jsonrpc: "2.0", id: 1, method: "ping" }),
      { kv: new MockKv().asKv() },
    );
    expect(res.status).toBe(200);
  });

  it("rejects an unsupported MCP-Protocol-Version header with HTTP 400", async () => {
    const res = await handleMcpRequest(
      mcpRequest(
        { jsonrpc: "2.0", id: 1, method: "ping" },
        { "mcp-protocol-version": "2099-01-01" },
      ),
      { kv: new MockKv().asKv() },
    );
    expect(res.status).toBe(400);
    const body = await asJsonRpc(res);
    expect(body.error?.message).toContain("Unsupported protocol version");
  });

  it("allows MCP-Protocol-Version in CORS preflight", async () => {
    const res = await handleMcpRequest(
      new Request("https://example.com/mcp", { method: "OPTIONS" }),
      { kv: new MockKv().asKv() },
    );
    expect(res.headers.get("access-control-allow-headers")).toContain(
      "mcp-protocol-version",
    );
  });

  it("returns 405 for non-POST/GET methods", async () => {
    const kv = new MockKv();
    const res = await handleMcpRequest(
      new Request("https://example.com/mcp", { method: "DELETE" }),
      { kv: kv.asKv() },
    );
    expect(res.status).toBe(405);
  });

  it("returns 501 for GET (SSE placeholder)", async () => {
    const kv = new MockKv();
    const res = await handleMcpRequest(
      new Request("https://example.com/mcp", { method: "GET" }),
      { kv: kv.asKv() },
    );
    expect(res.status).toBe(501);
  });

  it("returns 415 when the content-type is not JSON", async () => {
    const kv = new MockKv();
    const res = await handleMcpRequest(
      new Request("https://example.com/mcp", {
        method: "POST",
        headers: { "content-type": "text/plain" },
        body: "hello",
      }),
      { kv: kv.asKv() },
    );
    expect(res.status).toBe(415);
  });
});
