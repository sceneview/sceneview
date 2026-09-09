/**
 * Ambient declaration for the `cloudflare:node` built-in module.
 *
 * The Workers runtime provides it; `@types/node` naturally knows nothing about
 * it, and pulling in `@cloudflare/workers-types` for one function would drag a
 * second global environment into a package that is otherwise plain Node. One
 * declaration is cheaper and cannot conflict with the Node globals.
 *
 * See https://developers.cloudflare.com/workers/runtime-apis/nodejs/http/
 */
declare module "cloudflare:node" {
  import type { Server as NodeHttpServer } from "node:http";

  /**
   * Wraps a `node:http` server as a Worker default export, routing every
   * inbound `fetch` to the server's request listener.
   */
  export function httpServerHandler(server: NodeHttpServer): ExportedHandler;
  export function httpServerHandler(options: { port: number }): ExportedHandler;

  interface ExportedHandler {
    fetch(request: unknown, env: unknown, ctx: unknown): Promise<unknown>;
  }
}
