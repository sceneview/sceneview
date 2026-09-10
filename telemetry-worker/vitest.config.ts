import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // Node pool: Hono's app.request() is pure JS, no Workers runtime needed.
    environment: "node",
    include: ["test/**/*.test.ts"],
  },
});
