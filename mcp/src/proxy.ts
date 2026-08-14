/**
 * Proxy helper for the v4 "lite" npm package.
 *
 * In v4 the stdio package keeps serving free-tier tools locally (no
 * network round-trip), but Pro-tier tools are forwarded as JSON-RPC
 * `tools/call` requests to the hosted gateway. This keeps the install
 * footprint small for free users while letting paying customers hit a
 * metered, auth'd backend.
 *
 * The gateway lives at
 *   https://sceneview-mcp.mcp-tools-lab.workers.dev/mcp
 * (Cloudflare Workers, post-Stripe-first pivot). Override with
 * `SCENEVIEW_MCP_URL` if you run a self-hosted fork or the staging
 * worker.
 *
 * This module is intentionally dependency-free: it relies on the
 * global `fetch` that Node 18+ exposes and on nothing else from the
 * package.
 */

/** Default URL of the hosted gateway (post-Stripe-first, Apr 2026). */
export const DEFAULT_GATEWAY_URL = "https://sceneview-mcp.mcp-tools-lab.workers.dev/mcp";

/** Public pricing/signup page shown in stubs when no API key is set. */
export const DEFAULT_PRICING_URL = "https://sceneview-mcp.mcp-tools-lab.workers.dev/pricing";

/** Response shape returned by `dispatchProxyToolCall`. */
export interface ProxyToolResult {
  /** Array of content blocks as returned by the gateway. */
  content: Array<{ type: "text"; text: string }>;
  /** True if the gateway reported an error (isError flag or JSON-RPC error). */
  isError?: boolean;
}

/** Options for {@link dispatchProxyToolCall}. */
export interface ProxyOptions {
  /** The Bearer API key. Falls back to `SCENEVIEW_API_KEY` env var. */
  apiKey?: string;
  /** Override the gateway URL (defaults to {@link DEFAULT_GATEWAY_URL}). */
  gatewayUrl?: string;
  /** Injected fetch — tests pass in a mock here. */
  fetchImpl?: typeof fetch;
}

// Minimal JSON-RPC 2.0 request id, auto-incremented per process.
let nextRpcId = 1;

/**
 * Forwards a Pro-tier tool call to the hosted gateway and returns its
 * `ToolResult`. Network, HTTP, and JSON-RPC errors are converted into
 * a user-visible error block so the MCP client surfaces them in Claude.
 *
 * When no API key is configured at all, returns a helpful stub that
 * points at the pricing page instead of trying to call the gateway.
 * The stub is the user's first touch point with the Pro upsell — it
 * shows up verbatim in the Claude UI.
 */
export async function dispatchProxyToolCall(
  toolName: string,
  args: Record<string, unknown> | undefined,
  options: ProxyOptions = {}
): Promise<ProxyToolResult> {
  const apiKey = options.apiKey ?? process.env.SCENEVIEW_API_KEY;
  if (!apiKey) {
    return {
      content: [
        {
          type: "text",
          text:
            `## 🔒 Pro feature\n\n` +
            `\`${toolName}\` is a SceneView MCP Pro tool. ` +
            `Set \`SCENEVIEW_API_KEY\` to an API key from ` +
            `${DEFAULT_PRICING_URL} to unlock it.\n\n` +
            `Pro unlocks 27 specialized tools: 3D preview / artifact / scene ` +
            `generation, plus the Automotive / Gaming / Healthcare / Interior ` +
            `packages. All setup, migration, and reference guides are free.`,
        },
      ],
      isError: true,
    };
  }

  const gatewayUrl = options.gatewayUrl ?? process.env.SCENEVIEW_MCP_URL ?? DEFAULT_GATEWAY_URL;
  const fetchImpl = options.fetchImpl ?? fetch;

  const requestBody = {
    jsonrpc: "2.0" as const,
    id: nextRpcId++,
    method: "tools/call",
    params: { name: toolName, arguments: args ?? {} },
  };

  let response: Response;
  try {
    response = await fetchImpl(gatewayUrl, {
      method: "POST",
      headers: {
        authorization: `Bearer ${apiKey}`,
        "content-type": "application/json",
        accept: "application/json",
      },
      body: JSON.stringify(requestBody),
    });
  } catch (err) {
    const detail = err instanceof Error ? err.message : String(err);
    return {
      content: [
        {
          type: "text",
          text:
            `Failed to reach SceneView MCP gateway (${gatewayUrl}): ${detail}.\n\n` +
            `The gateway may be temporarily down. Try again in a few seconds, ` +
            `or check status at ${DEFAULT_PRICING_URL}.`,
        },
      ],
      isError: true,
    };
  }

  const text = await response.text().catch(() => "");

  if (response.status === 401 || response.status === 403) {
    return {
      content: [
        {
          type: "text",
          text:
            `## 🔑 Invalid or expired API key\n\n` +
            `The gateway rejected your \`SCENEVIEW_API_KEY\` (HTTP ${response.status}).\n\n` +
            `- If you just subscribed, make sure you copied the full key from ` +
            `the Stripe success page.\n` +
            `- If your subscription was cancelled, reactivate it at ${DEFAULT_PRICING_URL}.\n\n` +
            (text ? `Gateway response: ${text}` : ""),
        },
      ],
      isError: true,
    };
  }

  if (response.status === 429) {
    return {
      content: [
        {
          type: "text",
          text:
            `## ⏳ Rate limited\n\n` +
            `You've hit the rate limit for \`${toolName}\` (HTTP 429). ` +
            `Wait a few seconds and retry, or upgrade your tier at ` +
            `${DEFAULT_PRICING_URL}.\n\n` +
            (text ? `Gateway response: ${text}` : ""),
        },
      ],
      isError: true,
    };
  }

  if (!response.ok) {
    return {
      content: [
        {
          type: "text",
          text:
            `Gateway HTTP ${response.status} while calling ${toolName}. ` +
            (text || "No response body."),
        },
      ],
      isError: true,
    };
  }

  let parsed: {
    result?: {
      content?: Array<{ type: "text"; text: string }>;
      isError?: boolean;
    };
    error?: { message?: string; data?: unknown };
  };
  try {
    parsed = JSON.parse(text) as typeof parsed;
  } catch {
    return {
      content: [
        {
          type: "text",
          text: `Gateway returned non-JSON response: ${text}`,
        },
      ],
      isError: true,
    };
  }

  if (parsed.error) {
    return {
      content: [
        {
          type: "text",
          text: parsed.error.message ?? `Gateway error while calling ${toolName}.`,
        },
      ],
      isError: true,
    };
  }

  const result = parsed.result;
  return {
    content: result?.content ?? [{ type: "text", text: "" }],
    isError: result?.isError ?? false,
  };
}

/**
 * Returns true when proxy mode is active: an API key is configured
 * (either via `SCENEVIEW_API_KEY` env var or an explicit override).
 */
export function isProxyConfigured(apiKey?: string): boolean {
  if (apiKey && apiKey.length > 0) return true;
  return !!process.env.SCENEVIEW_API_KEY;
}
