/**
 * Cloudflare Worker entrypoint — the hosted remote surface for
 * `sceneview-mcp`, i.e. what a Claude custom connector (and, once submitted,
 * a Claude connectors-directory listing) points at.
 *
 * Why a Worker at all: `http.ts` is a `node:http` server, which is exactly
 * what `sceneview-mcp --http` runs locally and what the ChatGPT / Codex
 * plugins directory expects. Cloudflare's `httpServerHandler` runs that same
 * server inside a Worker, so the hosted endpoint and the local `--http` mode
 * are the *same code path* — the MCP layer is not reimplemented, and a bug
 * fixed in one is fixed in both. Requires `nodejs_compat` and a
 * `compatibility_date` of 2025-09-01 or later (see `../wrangler.toml`).
 *
 * What it exposes: everything except the local-only tools of `./surfaces.ts`.
 * `createRequestListener` builds each request's server with
 * `surface: "remote"` (`./server.ts`), so `tools/list` omits those three names
 * and refuses them at call time. There is no API key on a shared public
 * endpoint, hence no authentication at all — the `none` auth type in
 * Anthropic's connector docs. Every tool is free.
 *
 * Routes are `http.ts`'s: POST /mcp, GET /health, OPTIONS for CORS.
 *
 * Not exported from the npm package: `package.json`'s `files` excludes
 * `dist/worker.js`, because a Worker entrypoint is useless to an npm consumer
 * and would fail to resolve `cloudflare:node` outside the Workers runtime.
 */

import { httpServerHandler } from "cloudflare:node";
import { createServer } from "node:http";
import { createRequestListener } from "./http.js";

/**
 * The listen port is a routing key inside the Workers runtime, not a real TCP
 * port — `httpServerHandler` uses it to pick which server handles a request.
 */
const ROUTING_PORT = 8080;

const server = createServer(createRequestListener());
server.listen(ROUTING_PORT);

export default httpServerHandler({ port: ROUTING_PORT });
