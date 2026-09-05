/**
 * Streamable HTTP entrypoint — `sceneview-mcp --http`.
 *
 * This is the remote surface for hosts that cannot spawn a local process:
 * the ChatGPT / Codex Plugins Directory and the OpenAI API `mcp` tool both
 * require MCP's Streamable HTTP transport at a public `/mcp` URL. Deploy this
 * anywhere Node runs (a VPS, a container, a PaaS) behind HTTPS and point the
 * connector at `https://<host>/mcp`.
 *
 * Design:
 *
 *   - **Stateless.** Every request gets a fresh `Server` + transport with
 *     `sessionIdGenerator: undefined`, the SDK's documented stateless pattern.
 *     No session store, so any number of instances can sit behind a load
 *     balancer with no affinity, and a crashed request leaks nothing.
 *   - **Free tier only.** The server is built with `surface: "remote"`
 *     (`./server.ts`): `tools/list` returns only free tools and Pro names are
 *     refused at call time. There is no API key on a shared endpoint.
 *   - **No framework.** Node's built-in `http` module and the SDK — zero new
 *     runtime dependencies for the npm package.
 *
 * Routes:
 *
 *   POST /mcp                            JSON-RPC over Streamable HTTP
 *   GET / DELETE /mcp                    405 — the spec lets a server decline
 *                                        the standalone SSE stream, and with
 *                                        no sessions there is nothing to
 *                                        DELETE. (Left to the transport, a
 *                                        GET would hold an SSE stream — and a
 *                                        whole Server — open per request.)
 *   GET /health                          {"status":"ok","version":"…"}
 *   GET /.well-known/openai-apps-challenge
 *                                        OpenAI domain verification: the
 *                                        value of OPENAI_APPS_CHALLENGE_TOKEN
 *                                        as text/plain, 404 when unset
 *   OPTIONS *                            CORS preflight
 *   anything else                        404
 *
 * Configuration (environment): PORT (default 3333), HOST (default
 * 127.0.0.1 — bind 0.0.0.0 explicitly to expose it), and the same
 * SCENEVIEW_TELEMETRY / SKETCHFAB_API_KEY / TRIPO_API_KEY knobs as stdio.
 */

import {
  createServer as createNodeServer,
  type IncomingMessage,
  type Server as NodeHttpServer,
  type ServerResponse,
} from "node:http";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { PACKAGE_VERSION } from "./generated/version.js";
import { createSceneViewServer } from "./server.js";

export const DEFAULT_PORT = 3333;
export const DEFAULT_HOST = "127.0.0.1";

export const MCP_PATH = "/mcp";
export const HEALTH_PATH = "/health";
export const OPENAI_APPS_CHALLENGE_PATH = "/.well-known/openai-apps-challenge";
export const OPENAI_APPS_CHALLENGE_ENV = "OPENAI_APPS_CHALLENGE_TOKEN";

/**
 * CORS: any origin may talk to the server (browser-based MCP clients and
 * the widget host both need it), with the headers MCP clients actually send
 * and the one response header they need to read back.
 */
export const CORS_HEADERS: Readonly<Record<string, string>> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
  "Access-Control-Allow-Headers":
    "Content-Type, Accept, Authorization, Mcp-Session-Id, Mcp-Protocol-Version",
  "Access-Control-Expose-Headers": "Mcp-Session-Id",
  "Access-Control-Max-Age": "86400",
};

export interface HttpServerOptions {
  /** TCP port; `0` asks the OS for a free one (tests). Default: `PORT` env or 3333. */
  port?: number;
  /** Bind address. Default: `HOST` env or 127.0.0.1. */
  host?: string;
  /** Environment to read the challenge token from. Default: `process.env`. */
  env?: NodeJS.ProcessEnv;
  /** Where to log the listening banner. Default: stderr. `null` silences it. */
  log?: ((line: string) => void) | null;
}

function applyCors(res: ServerResponse): void {
  for (const [name, value] of Object.entries(CORS_HEADERS)) res.setHeader(name, value);
}

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

/**
 * One MCP request, start to finish: fresh server, fresh transport, and both
 * torn down when the response closes. Stateless per the SDK docs.
 */
async function handleMcp(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const server = createSceneViewServer({ surface: "remote" });
  const transport = new StreamableHTTPServerTransport({
    sessionIdGenerator: undefined,
    // Plain JSON responses instead of an SSE stream per POST: simpler for
    // curl and for the OpenAI connector, and there is no session to stream
    // server-initiated messages on anyway.
    enableJsonResponse: true,
  });
  res.on("close", () => {
    void transport.close();
    void server.close();
  });
  await server.connect(transport);
  await transport.handleRequest(req, res);
}

/**
 * The request listener, exported so tests can mount it on an ephemeral port
 * and so embedders can hang it off their own `http.Server`.
 */
export function createRequestListener(
  options: Pick<HttpServerOptions, "env"> = {}
): (req: IncomingMessage, res: ServerResponse) => void {
  const env = options.env ?? process.env;

  return (req, res) => {
    applyCors(res);

    const method = req.method ?? "GET";
    const path = new URL(req.url ?? "/", "http://localhost").pathname;

    if (method === "OPTIONS") {
      res.writeHead(204);
      res.end();
      return;
    }

    if (path === MCP_PATH) {
      if (method !== "POST") {
        res.writeHead(405, { Allow: "POST, OPTIONS", "Content-Type": "application/json" });
        res.end(
          JSON.stringify({
            jsonrpc: "2.0",
            error: { code: -32000, message: "Method not allowed." },
            id: null,
          })
        );
        return;
      }
      handleMcp(req, res).catch((err: unknown) => {
        process.stderr.write(`[sceneview-mcp] /mcp request failed: ${String(err)}\n`);
        if (!res.headersSent) {
          sendJson(res, 500, {
            jsonrpc: "2.0",
            error: { code: -32603, message: "Internal server error" },
            id: null,
          });
        } else {
          res.end();
        }
      });
      return;
    }

    if (path === HEALTH_PATH && method === "GET") {
      sendJson(res, 200, { status: "ok", version: PACKAGE_VERSION });
      return;
    }

    if (path === OPENAI_APPS_CHALLENGE_PATH && method === "GET") {
      const token = env[OPENAI_APPS_CHALLENGE_ENV];
      if (!token) {
        sendJson(res, 404, { error: "Not found" });
        return;
      }
      // OpenAI's verifier expects the token and nothing else — no JSON, no
      // trailing newline.
      res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
      res.end(token);
      return;
    }

    sendJson(res, 404, { error: "Not found" });
  };
}

/** Boots the HTTP server and resolves once it is listening. */
export function startHttpServer(options: HttpServerOptions = {}): Promise<NodeHttpServer> {
  const env = options.env ?? process.env;
  const port = options.port ?? (env.PORT ? Number(env.PORT) : DEFAULT_PORT);
  const host = options.host ?? env.HOST ?? DEFAULT_HOST;
  const log =
    options.log === undefined ? (line: string) => process.stderr.write(`${line}\n`) : options.log;

  if (!Number.isInteger(port) || port < 0 || port > 65535) {
    return Promise.reject(new Error(`[sceneview-mcp] invalid PORT: ${env.PORT}`));
  }

  const httpServer = createNodeServer(createRequestListener({ env }));

  return new Promise((resolve, reject) => {
    httpServer.once("error", reject);
    httpServer.listen(port, host, () => {
      httpServer.off("error", reject);
      const address = httpServer.address();
      const boundPort = typeof address === "object" && address ? address.port : port;
      if (log && env.SCENEVIEW_MCP_QUIET !== "1") {
        log(`[sceneview-mcp] v${PACKAGE_VERSION} — HTTP (free tools only)`);
        log(`[sceneview-mcp] MCP endpoint: http://${host}:${boundPort}${MCP_PATH}`);
        log(`[sceneview-mcp] Health:       http://${host}:${boundPort}${HEALTH_PATH}`);
      }
      resolve(httpServer);
    });
  });
}
