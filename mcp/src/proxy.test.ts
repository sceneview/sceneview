/**
 * Unit tests for `./proxy.ts` — the v4 lite-mode forwarder.
 *
 * We mock `fetch` to avoid hitting the real gateway. The goal is to
 * pin down:
 *   - missing API key → helpful stub, no network
 *   - explicit key or env var → Bearer header, correct URL
 *   - `SCENEVIEW_MCP_URL` override respected
 *   - network, 401/403, 429, 5xx, non-JSON, JSON-RPC error — all
 *     converted into user-visible `isError` blocks
 *   - happy-path payload is returned verbatim
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  DEFAULT_GATEWAY_URL,
  DEFAULT_PRICING_URL,
  dispatchProxyToolCall,
  isProxyConfigured,
} from "./proxy.js";

const ORIGINAL_ENV = { ...process.env };

beforeEach(() => {
  process.env = { ...ORIGINAL_ENV };
  delete process.env.SCENEVIEW_API_KEY;
  delete process.env.SCENEVIEW_MCP_URL;
});

afterEach(() => {
  process.env = { ...ORIGINAL_ENV };
});

describe("isProxyConfigured", () => {
  it("is false when the env var is unset", () => {
    expect(isProxyConfigured()).toBe(false);
  });

  it("is true when SCENEVIEW_API_KEY is set", () => {
    process.env.SCENEVIEW_API_KEY = "sv_live_xxx";
    expect(isProxyConfigured()).toBe(true);
  });

  it("is true when an explicit key is passed", () => {
    expect(isProxyConfigured("sv_live_override")).toBe(true);
  });

  it("is false for empty strings", () => {
    expect(isProxyConfigured("")).toBe(false);
  });
});

describe("dispatchProxyToolCall", () => {
  it("points at the Cloudflare workers.dev gateway by default", () => {
    expect(DEFAULT_GATEWAY_URL).toBe("https://sceneview-mcp.mcp-tools-lab.workers.dev/mcp");
    expect(DEFAULT_PRICING_URL).toBe("https://sceneview-mcp.mcp-tools-lab.workers.dev/pricing");
  });

  it("returns a helpful stub when no API key is configured", async () => {
    const fetchMock = vi.fn();
    const result = await dispatchProxyToolCall("get_ar_setup", undefined, {
      fetchImpl: fetchMock as unknown as typeof fetch,
    });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toMatch(/Pro feature/);
    expect(result.content[0].text).toMatch(/get_ar_setup/);
    expect(result.content[0].text).toMatch(/mcp-tools-lab\.workers\.dev/);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("forwards to the default gateway URL with a Bearer header", async () => {
    const fetchMock = vi.fn<typeof fetch>(
      async () =>
        new Response(
          JSON.stringify({
            jsonrpc: "2.0",
            id: 1,
            result: {
              content: [{ type: "text", text: "remote ok" }],
              isError: false,
            },
          }),
          { status: 200 }
        )
    );
    const result = await dispatchProxyToolCall(
      "generate_scene",
      { prompt: "octopus" },
      {
        apiKey: "sv_live_abcdef",
        fetchImpl: fetchMock as unknown as typeof fetch,
      }
    );
    expect(result.isError).toBe(false);
    expect(result.content[0].text).toBe("remote ok");
    expect(fetchMock).toHaveBeenCalledOnce();
    const call = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(call[0]).toBe(DEFAULT_GATEWAY_URL);
    const headers = call[1].headers as Record<string, string>;
    expect(headers.authorization).toBe("Bearer sv_live_abcdef");
    expect(headers["content-type"]).toBe("application/json");
    expect(headers.accept).toBe("application/json");
    const body = JSON.parse(call[1].body as string) as {
      jsonrpc: string;
      method: string;
      params: { name: string; arguments: Record<string, unknown> };
    };
    expect(body.jsonrpc).toBe("2.0");
    expect(body.method).toBe("tools/call");
    expect(body.params.name).toBe("generate_scene");
    expect(body.params.arguments).toEqual({ prompt: "octopus" });
  });

  it("falls back to the env var when no explicit key is passed", async () => {
    process.env.SCENEVIEW_API_KEY = "sv_live_from_env";
    const fetchMock = vi.fn<typeof fetch>(
      async () =>
        new Response(
          JSON.stringify({
            jsonrpc: "2.0",
            id: 1,
            result: { content: [{ type: "text", text: "ok" }] },
          }),
          { status: 200 }
        )
    );
    await dispatchProxyToolCall("get_ios_setup", undefined, {
      fetchImpl: fetchMock as unknown as typeof fetch,
    });
    const init = (fetchMock.mock.calls[0] as [string, RequestInit])[1];
    const headers = init.headers as Record<string, string>;
    expect(headers.authorization).toBe("Bearer sv_live_from_env");
  });

  it("respects SCENEVIEW_MCP_URL override", async () => {
    process.env.SCENEVIEW_API_KEY = "sv_live_env";
    process.env.SCENEVIEW_MCP_URL = "https://staging.example.com/mcp";
    const fetchMock = vi.fn<typeof fetch>(
      async () =>
        new Response(JSON.stringify({ jsonrpc: "2.0", id: 1, result: { content: [] } }), {
          status: 200,
        })
    );
    await dispatchProxyToolCall("get_ios_setup", undefined, {
      fetchImpl: fetchMock as unknown as typeof fetch,
    });
    const url = (fetchMock.mock.calls[0] as [string])[0];
    expect(url).toBe("https://staging.example.com/mcp");
  });

  it("converts a network error into a user-visible isError", async () => {
    const fetchMock = vi.fn(async () => {
      throw new Error("ECONNREFUSED");
    });
    const result = await dispatchProxyToolCall("get_ios_setup", undefined, {
      apiKey: "sv_live_abc",
      fetchImpl: fetchMock as unknown as typeof fetch,
    });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toMatch(/ECONNREFUSED/);
    expect(result.content[0].text).toMatch(/gateway may be temporarily down/);
  });

  it("shows an API-key-specific stub on HTTP 401", async () => {
    const fetchMock = vi.fn(async () => new Response("invalid key", { status: 401 }));
    const result = await dispatchProxyToolCall("get_ios_setup", undefined, {
      apiKey: "sv_live_bad",
      fetchImpl: fetchMock as unknown as typeof fetch,
    });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toMatch(/Invalid or expired/);
    expect(result.content[0].text).toMatch(/invalid key/);
  });

  it("shows an API-key-specific stub on HTTP 403", async () => {
    const fetchMock = vi.fn(async () => new Response("", { status: 403 }));
    const result = await dispatchProxyToolCall("get_ios_setup", undefined, {
      apiKey: "sv_live_bad",
      fetchImpl: fetchMock as unknown as typeof fetch,
    });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toMatch(/Invalid or expired/);
  });

  it("shows a rate-limit stub on HTTP 429", async () => {
    const fetchMock = vi.fn(async () => new Response("slow down", { status: 429 }));
    const result = await dispatchProxyToolCall("get_ios_setup", undefined, {
      apiKey: "sv_live_abc",
      fetchImpl: fetchMock as unknown as typeof fetch,
    });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toMatch(/Rate limited/);
    expect(result.content[0].text).toMatch(/slow down/);
  });

  it("converts a 5xx response into a user-visible isError", async () => {
    const fetchMock = vi.fn(async () => new Response("internal", { status: 500 }));
    const result = await dispatchProxyToolCall("get_ios_setup", undefined, {
      apiKey: "sv_live_abc",
      fetchImpl: fetchMock as unknown as typeof fetch,
    });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toMatch(/HTTP 500/);
    expect(result.content[0].text).toMatch(/internal/);
  });

  it("returns a clear stub when the gateway returns non-JSON", async () => {
    const fetchMock = vi.fn(async () => new Response("<html>oops</html>", { status: 200 }));
    const result = await dispatchProxyToolCall("get_ios_setup", undefined, {
      apiKey: "sv_live_abc",
      fetchImpl: fetchMock as unknown as typeof fetch,
    });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toMatch(/non-JSON/);
  });

  it("surfaces a JSON-RPC error message when the gateway returns one", async () => {
    const fetchMock = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            jsonrpc: "2.0",
            id: 1,
            error: { code: -32002, message: "Access denied" },
          }),
          { status: 200 }
        )
    );
    const result = await dispatchProxyToolCall("get_ios_setup", undefined, {
      apiKey: "sv_live_abc",
      fetchImpl: fetchMock as unknown as typeof fetch,
    });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toBe("Access denied");
  });

  it("propagates isError from a successful gateway response", async () => {
    const fetchMock = vi.fn<typeof fetch>(
      async () =>
        new Response(
          JSON.stringify({
            jsonrpc: "2.0",
            id: 1,
            result: {
              content: [{ type: "text", text: "bad input" }],
              isError: true,
            },
          }),
          { status: 200 }
        )
    );
    const result = await dispatchProxyToolCall(
      "generate_scene",
      {},
      {
        apiKey: "sv_live_abc",
        fetchImpl: fetchMock as unknown as typeof fetch,
      }
    );
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toBe("bad input");
  });

  it("defaults args to {} when undefined", async () => {
    const fetchMock = vi.fn<typeof fetch>(
      async () =>
        new Response(JSON.stringify({ jsonrpc: "2.0", id: 1, result: { content: [] } }), {
          status: 200,
        })
    );
    await dispatchProxyToolCall("get_ios_setup", undefined, {
      apiKey: "sv_live_abc",
      fetchImpl: fetchMock as unknown as typeof fetch,
    });
    const body = JSON.parse(
      (fetchMock.mock.calls[0] as [string, RequestInit])[1].body as string
    ) as { params: { arguments: Record<string, unknown> } };
    expect(body.params.arguments).toEqual({});
  });
});
