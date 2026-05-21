import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // Node pool — Hono's app.request() is pure JS, no Workers runtime needed.
    // Cloudflare bindings (D1, KV, R2, AI) are mocked; see test/helpers/.
    environment: "node",
    include: ["test/**/*.test.ts"],
  },
});
