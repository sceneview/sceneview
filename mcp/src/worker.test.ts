/**
 * The Cloudflare Worker deployment contract (`src/worker.ts` + `wrangler.toml`).
 *
 * The Worker itself is exercised for real by the `/mcp` handshake against the
 * deployed URL; what a unit test can protect is the configuration that makes
 * `cloudflare:node`'s `httpServerHandler` available at all, and the deliberate
 * choices someone editing `wrangler.toml` might undo without noticing.
 */

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const read = (relative: string): string =>
  readFileSync(fileURLToPath(new URL(relative, import.meta.url)), "utf8");

const wrangler = read("../wrangler.toml");
const worker = read("./worker.ts");
const packageJson = JSON.parse(read("../package.json")) as {
  files: string[];
  scripts: Record<string, string>;
};

describe("wrangler.toml", () => {
  it("enables nodejs_compat", () => {
    // Without it there is no `node:http` in the runtime and no
    // `cloudflare:node` module to import.
    expect(wrangler).toMatch(/compatibility_flags\s*=\s*\[\s*"nodejs_compat"\s*\]/);
  });

  it("uses a compatibility date that enables the Node HTTP server modules", () => {
    const match = wrangler.match(/compatibility_date\s*=\s*"([\d-]+)"/);
    expect(match).not.toBeNull();
    // `enable_nodejs_http_server_modules` is implied from 2025-09-01 onward.
    expect(Date.parse(match?.[1] ?? "")).toBeGreaterThanOrEqual(Date.parse("2025-09-01"));
  });

  it("points at the compiled worker entrypoint, not the TypeScript source", () => {
    // Wrangler bundles with esbuild, which does not resolve a `./http.js`
    // specifier to `http.ts`. Deploying from `dist/` keeps the import graph
    // exactly the one `tsc` produced.
    expect(wrangler).toMatch(/^main\s*=\s*"dist\/worker\.js"$/m);
  });

  it("keeps a workers.dev URL available", () => {
    expect(wrangler).toMatch(/^workers_dev\s*=\s*true$/m);
  });

  it("does not bind the custom domain yet", () => {
    // A connector's URL is effectively permanent: re-pointing it forces every
    // existing user to disconnect and re-add. Binding `mcp.sceneview.dev` is a
    // release decision, made when the listing is submitted.
    expect(wrangler).not.toMatch(/^\s*routes\s*=/m);
    expect(wrangler).not.toMatch(/custom_domain\s*=\s*true(?![\s\S]*^#)/m);
  });

  it("disables telemetry on the anonymous shared endpoint", () => {
    expect(wrangler).toMatch(/SCENEVIEW_TELEMETRY\s*=\s*"0"/);
  });
});

describe("worker.ts", () => {
  it("reuses the shared request listener rather than reimplementing routing", () => {
    expect(worker).toMatch(/import \{ createRequestListener \} from "\.\/http\.js"/);
    expect(worker).toMatch(/createServer\(createRequestListener\(\)\)/);
  });

  it("exports the httpServerHandler default the Workers runtime expects", () => {
    expect(worker).toMatch(/export default httpServerHandler\(/);
  });

  it("listens on the same routing key it hands to the handler", () => {
    // A mismatch here deploys cleanly and then 500s on every request.
    expect(worker).toMatch(/server\.listen\(ROUTING_PORT\)/);
    expect(worker).toMatch(/httpServerHandler\(\{ port: ROUTING_PORT \}\)/);
  });
});

describe("npm packaging", () => {
  it("excludes the worker entrypoint from the published package", () => {
    // `cloudflare:node` does not resolve outside the Workers runtime, and a
    // Worker entrypoint is dead weight for an npx consumer.
    expect(packageJson.files).toContain("!dist/worker.js");
  });
});
