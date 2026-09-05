/**
 * The Streamable HTTP surface (`sceneview-mcp --http`), end to end over a
 * real socket: MCP JSON-RPC on `/mcp`, the health and OpenAI domain-challenge
 * routes, CORS, and the free-tier-only rule.
 */

import type { Server as NodeHttpServer } from "node:http";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { PACKAGE_VERSION } from "./generated/version.js";
import { CORS_HEADERS, startHttpServer } from "./http.js";
import { getProToolNames, getToolTier } from "./tiers.js";
import { TOOL_DEFINITIONS } from "./tools/index.js";

const CHALLENGE_TOKEN = "test-challenge-token-1234";

let server: NodeHttpServer;
let base: string;

beforeAll(async () => {
  server = await startHttpServer({
    port: 0,
    host: "127.0.0.1",
    env: {
      ...process.env,
      OPENAI_APPS_CHALLENGE_TOKEN: CHALLENGE_TOKEN,
      SCENEVIEW_TELEMETRY: "0",
      SCENEVIEW_SPONSOR_CTA: "0",
    },
    log: null,
  });
  const address = server.address();
  if (typeof address !== "object" || !address) throw new Error("server has no address");
  base = `http://127.0.0.1:${address.port}`;
});

afterAll(async () => {
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

type JsonRpcResponse = {
  jsonrpc: "2.0";
  id: number;
  result?: Record<string, unknown>;
  error?: { code: number; message: string };
};

let nextId = 1;

/** POST one JSON-RPC request to /mcp with the Accept header the spec requires. */
async function rpc(method: string, params?: unknown): Promise<JsonRpcResponse> {
  const id = nextId++;
  const res = await fetch(`${base}/mcp`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json, text/event-stream",
    },
    body: JSON.stringify({ jsonrpc: "2.0", id, method, params }),
  });
  expect(res.status, `${method} status`).toBe(200);
  expect(res.headers.get("content-type")).toContain("application/json");
  return (await res.json()) as JsonRpcResponse;
}

const INITIALIZE_PARAMS = {
  protocolVersion: "2025-11-25",
  capabilities: {},
  clientInfo: { name: "http-test", version: "0.0.0" },
};

describe("POST /mcp — Streamable HTTP, stateless", () => {
  it("answers initialize with the server identity and no session id", async () => {
    const res = await fetch(`${base}/mcp`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json, text/event-stream",
      },
      body: JSON.stringify({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: INITIALIZE_PARAMS,
      }),
    });
    expect(res.status).toBe(200);
    // Stateless: the transport never mints a session.
    expect(res.headers.get("mcp-session-id")).toBeNull();
    const body = (await res.json()) as JsonRpcResponse;
    expect(body.error).toBeUndefined();
    expect(body.result?.serverInfo).toEqual({ name: "sceneview-mcp", version: PACKAGE_VERSION });
    expect(body.result?.capabilities).toMatchObject({ tools: {}, resources: {} });
    // #3192 — end-to-end, over a real handshake: MCP Apps is opt-in via the
    // extensions mechanism, so a host that follows the spec learns the widget
    // exists from this block and nowhere else.
    expect(body.result?.capabilities).toMatchObject({
      extensions: { "io.modelcontextprotocol/ui": { mimeTypes: ["text/html;profile=mcp-app"] } },
    });
  });

  it("tools/list exposes the free tier only, with view_3d_model bound to the widget", async () => {
    const body = await rpc("tools/list");
    expect(body.error).toBeUndefined();
    const tools = body.result?.tools as Array<{
      name: string;
      description: string;
      _meta?: { ui?: { resourceUri?: string } };
    }>;
    const names = tools.map((t) => t.name);

    const expectedFree = TOOL_DEFINITIONS.filter((t) => getToolTier(t.name) === "free").map(
      (t) => t.name
    );
    expect(names).toEqual(expectedFree);
    expect(names).toContain("view_3d_model");
    expect(names).toContain("get_sample");

    for (const pro of getProToolNames()) expect(names).not.toContain(pro);
    // And no "[PRO]"-prefixed leftovers from the stdio listing either.
    expect(tools.some((t) => t.description.startsWith("[PRO]"))).toBe(false);

    const viewer = tools.find((t) => t.name === "view_3d_model");
    expect(viewer?._meta?.ui?.resourceUri).toBe("ui://widget/3d-viewer.html");
    expect(viewer?._meta).toMatchObject({ "openai/outputTemplate": "ui://widget/3d-viewer.html" });
  });

  it("tools/call view_3d_model returns structuredContent + _meta.ui.resourceUri", async () => {
    const body = await rpc("tools/call", {
      name: "view_3d_model",
      arguments: {
        modelUrl: "https://sceneview.github.io/models/Astronaut.glb",
        title: "Astronaut",
      },
    });
    expect(body.error).toBeUndefined();
    expect(body.result?.isError).toBeFalsy();
    expect(body.result?.structuredContent).toEqual({
      modelUrl: "https://sceneview.github.io/models/Astronaut.glb",
      title: "Astronaut",
      autoRotate: true,
      ar: true,
      alt: "Astronaut",
    });
    expect(body.result?._meta).toEqual({ ui: { resourceUri: "ui://widget/3d-viewer.html" } });
    const content = body.result?.content as Array<{ type: string; text: string }>;
    expect(content[0]?.type).toBe("text");
    expect(content[0]?.text).toContain("Astronaut");
  });

  it("tools/call on a free tool runs the same handler as stdio", async () => {
    const body = await rpc("tools/call", { name: "list_samples", arguments: {} });
    expect(body.error).toBeUndefined();
    const content = body.result?.content as Array<{ type: string; text: string }>;
    expect(content[0]?.text).toContain("model-viewer");
  });

  it("tools/call on a Pro tool is refused with a clear isError text", async () => {
    const body = await rpc("tools/call", {
      name: "generate_scene",
      arguments: { description: "a chair" },
    });
    expect(body.error).toBeUndefined();
    expect(body.result?.isError).toBe(true);
    const content = body.result?.content as Array<{ type: string; text: string }>;
    expect(content[0]?.text).toContain("generate_scene");
    expect(content[0]?.text).toContain("Pro tool");
    expect(content[0]?.text).toContain("npx sceneview-mcp");
    // Never forwarded to the gateway, never a widget.
    expect(body.result?._meta).toBeUndefined();
  });

  it("tools/call on an unknown tool says so instead of calling it Pro", async () => {
    const body = await rpc("tools/call", { name: "no_such_tool", arguments: {} });
    expect(body.result?.isError).toBe(true);
    const content = body.result?.content as Array<{ type: string; text: string }>;
    expect(content[0]?.text).toBe("Unknown tool: no_such_tool");
  });

  it("resources/list includes the widget after the four historical resources", async () => {
    const body = await rpc("resources/list");
    const resources = body.result?.resources as Array<{ uri: string; mimeType: string }>;
    expect(resources.map((r) => r.uri)).toEqual([
      "sceneview://api",
      "sceneview://known-issues",
      "examples://demo-with-settings",
      "examples://sketchfab-streaming",
      "ui://widget/3d-viewer.html",
    ]);
  });

  it("resources/read serves the widget with the MCP Apps mime type and _meta.ui.csp", async () => {
    const body = await rpc("resources/read", { uri: "ui://widget/3d-viewer.html" });
    expect(body.error).toBeUndefined();
    const contents = body.result?.contents as Array<{
      uri: string;
      mimeType: string;
      text: string;
      _meta?: { ui?: { prefersBorder?: boolean; csp?: Record<string, string[]> } };
    }>;
    expect(contents).toHaveLength(1);
    expect(contents[0]?.uri).toBe("ui://widget/3d-viewer.html");
    expect(contents[0]?.mimeType).toBe("text/html;profile=mcp-app");
    expect(contents[0]?.text).toContain("<!DOCTYPE html>");
    expect(contents[0]?.text).toContain("SceneView.modelViewer(");
    expect(contents[0]?._meta?.ui?.prefersBorder).toBe(true);
    expect(contents[0]?._meta?.ui?.csp?.resourceDomains).toEqual(["https://sceneview.github.io"]);
    expect(contents[0]?._meta?.ui?.csp?.connectDomains).toContain("https://media.sketchfab.com");
  });

  it("resources/read still serves the API reference", async () => {
    const body = await rpc("resources/read", { uri: "sceneview://api" });
    const contents = body.result?.contents as Array<{ mimeType: string; text: string }>;
    expect(contents[0]?.mimeType).toBe("text/markdown");
    expect(contents[0]?.text.length).toBeGreaterThan(1000);
  });

  it("rejects a POST without the required Accept header", async () => {
    const res = await fetch(`${base}/mcp`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 99, method: "tools/list" }),
    });
    expect(res.status).toBe(406);
  });

  it("GET and DELETE /mcp are 405 (no SSE stream, no session, in stateless mode)", async () => {
    const get = await fetch(`${base}/mcp`, { headers: { Accept: "text/event-stream" } });
    expect(get.status).toBe(405);
    expect(get.headers.get("allow")).toBe("POST, OPTIONS");
    const del = await fetch(`${base}/mcp`, { method: "DELETE" });
    expect(del.status).toBe(405);
  });
});

describe("plain routes", () => {
  it("GET /health reports ok + version", async () => {
    const res = await fetch(`${base}/health`);
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toContain("application/json");
    expect(await res.json()).toEqual({ status: "ok", version: PACKAGE_VERSION });
  });

  it("GET /.well-known/openai-apps-challenge returns the token as text/plain, nothing else", async () => {
    const res = await fetch(`${base}/.well-known/openai-apps-challenge`);
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toContain("text/plain");
    expect(await res.text()).toBe(CHALLENGE_TOKEN);
  });

  it("GET /.well-known/openai-apps-challenge is 404 when the token is unset", async () => {
    const quiet = await startHttpServer({
      port: 0,
      host: "127.0.0.1",
      env: { ...process.env, OPENAI_APPS_CHALLENGE_TOKEN: undefined },
      log: null,
    });
    try {
      const address = quiet.address();
      const port = typeof address === "object" && address ? address.port : 0;
      const res = await fetch(`http://127.0.0.1:${port}/.well-known/openai-apps-challenge`);
      expect(res.status).toBe(404);
    } finally {
      await new Promise<void>((resolve) => quiet.close(() => resolve()));
    }
  });

  it("everything else is 404", async () => {
    for (const path of ["/", "/mcp/extra", "/widget/3d-viewer.html", "/health/x"]) {
      const res = await fetch(`${base}${path}`);
      expect(res.status, path).toBe(404);
    }
  });

  it("answers CORS preflight and sets CORS headers on every response", async () => {
    const preflight = await fetch(`${base}/mcp`, {
      method: "OPTIONS",
      headers: {
        Origin: "https://chatgpt.com",
        "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "content-type, mcp-session-id",
      },
    });
    expect(preflight.status).toBe(204);
    expect(preflight.headers.get("access-control-allow-origin")).toBe("*");
    expect(preflight.headers.get("access-control-allow-headers")).toBe(
      CORS_HEADERS["Access-Control-Allow-Headers"]
    );
    expect(preflight.headers.get("access-control-expose-headers")).toBe("Mcp-Session-Id");

    const health = await fetch(`${base}/health`);
    expect(health.headers.get("access-control-allow-origin")).toBe("*");

    const mcp = await fetch(`${base}/mcp`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json, text/event-stream",
      },
      body: JSON.stringify({ jsonrpc: "2.0", id: 7, method: "tools/list" }),
    });
    expect(mcp.headers.get("access-control-allow-origin")).toBe("*");
  });
});
