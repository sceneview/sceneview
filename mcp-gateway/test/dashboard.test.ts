/**
 * Tests for the public dashboard HTML routes (landing, pricing, docs)
 * and the MVP-disabled auth stubs.
 *
 * Since the gateway dropped dashboard auth, every user-bound page
 * (/dashboard, /billing, /dashboard/keys, ...) is gone. The tests
 * here cover only the routes that actually ship in the MVP.
 */

import { Hono } from "hono";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { dashboardRoutes } from "../src/routes/dashboard.js";
import { authRoutes } from "../src/routes/auth.js";
import type { Env } from "../src/env.js";
import { createMockD1, type MockD1 } from "./helpers/mock-d1.js";
import { MockKv } from "./helpers/mock-kv.js";
import { HOSTED_FREE_TOOL_COUNT } from "../src/dashboard/counts.js";

let mock: MockD1;
let kv: MockKv;

beforeEach(async () => {
  mock = await createMockD1();
  kv = new MockKv();
});
afterEach(() => {
  mock.close();
});

function env(): Env {
  return {
    DB: mock.db,
    RL_KV: kv.asKv(),
    ENVIRONMENT: "test",
    DASHBOARD_BASE_URL: "https://sceneview-mcp.workers.dev",
  } as Env;
}

function makeFullApp() {
  const app = new Hono<{ Bindings: Env }>();
  app.route("/", dashboardRoutes());
  app.route("/", authRoutes());
  return app;
}

describe("GET public pages", () => {
  it("GET / renders the landing page", async () => {
    const app = makeFullApp();
    const res = await app.request("/", {}, env());
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toMatch(/text\/html/);
    const body = await res.text();
    expect(body).toContain("<!doctype html>");
    expect(body).toContain("SceneView MCP");
    expect(body).toMatch(/Expert 3D and AR/);
    // The landing CTA points straight at /pricing now.
    expect(body).toContain('href="/pricing"');
    // No Sign in / Dashboard nav link since dashboard auth is gone.
    expect(body).not.toContain('href="/login"');
    expect(body).not.toContain('href="/dashboard"');
  });

  it("GET /pricing renders all three tiers", async () => {
    const app = makeFullApp();
    const res = await app.request("/pricing", {}, env());
    expect(res.status).toBe(200);
    const body = await res.text();
    expect(body).toMatch(/Free/);
    expect(body).toMatch(/Pro/);
    expect(body).toMatch(/Team/);
    expect(body).toMatch(/19 EUR/);
    expect(body).toMatch(/49 EUR/);
  });

  it("GET /pricing exposes subscribe forms for Pro and Team", async () => {
    const app = makeFullApp();
    const res = await app.request("/pricing", {}, env());
    const body = await res.text();
    expect(body).toContain('action="/billing/checkout"');
    expect(body).toContain('name="plan" value="pro-monthly"');
    expect(body).toContain('name="plan" value="pro-yearly"');
    expect(body).toContain('name="plan" value="team-monthly"');
    expect(body).toContain('name="plan" value="team-yearly"');
    // Free tier CTA points at the docs install guide, not mailto —
    // the npm package is already the free tier, no signup needed.
    expect(body).toContain('href="/docs#claude-desktop"');
    expect(body).not.toContain("Free tier coming soon");
    // hello@ is still linked elsewhere (lost key, invoice request).
    expect(body).toMatch(/mailto:hello@sceneview\.dev/);
  });

  it("GET /pricing matches the real free tool count and the real VAT regime", async () => {
    const app = makeFullApp();
    const res = await app.request("/pricing", {}, env());
    const body = await res.text();
    // The count is derived, never written down: this assertion used to read
    // `toContain("27 free tools")` and it did not catch the page going stale —
    // it PINNED the stale value, staying green while the registry moved to 29
    // and only turning red once the page was corrected. Reading the same
    // constant the page reads is what makes the drift detectable at all.
    expect(body).toContain(`${HOSTED_FREE_TOOL_COUNT} free tools`);
    // VAT FAQ must reflect the real fiscal state: we are under
    // France's franchise en base de TVA, Stripe Tax is DISABLED,
    // no VAT is collected. The opposite claim is legally risky.
    expect(body).not.toMatch(/Stripe Tax/);
    expect(body).toMatch(/franchise en base de TVA/);
    expect(body).toMatch(/no VAT is collected/);
  });

  it("GET /pricing self-host FAQ no longer mentions the @beta tag (Pro is on @latest since 4.0)", async () => {
    const app = makeFullApp();
    const res = await app.request("/pricing", {}, env());
    const body = await res.text();
    // Pre-4.0 the npm @latest tag pointed at 3.6.x which had no proxy,
    // so users had to install @beta to reach the gateway. Since 4.0
    // the proxy ships in @latest itself — @beta is obsolete.
    expect(body).not.toContain("sceneview-mcp@beta");
  });

  it("GET /docs shows install instructions", async () => {
    const app = makeFullApp();
    const res = await app.request("/docs", {}, env());
    expect(res.status).toBe(200);
    const body = await res.text();
    expect(body).toMatch(/Claude Desktop/);
    expect(body).toMatch(/Cursor/);
    expect(body).toContain("sceneview-mcp.mcp-tools-lab.workers.dev/mcp");
    // Should NEVER reference the phantom NXDOMAIN subdomain that was
    // briefly in the docs before the post-Stripe-first cleanup.
    expect(body).not.toContain("https://sceneview-mcp.workers.dev/");
    expect(body).toContain("sv_live_");
    // Never leak an sk_live_ prefix — that is Stripe's.
    expect(body).not.toContain("sk_live_");
    // Docs explain the new flow.
    expect(body).toMatch(/Subscribe/);
    expect(body).toMatch(/success page/);
  });
});

describe("MVP-disabled auth stubs", () => {
  it("GET /login returns 503", async () => {
    const app = makeFullApp();
    const res = await app.request("/login", {}, env());
    expect(res.status).toBe(503);
    const body = await res.text();
    expect(body).toMatch(/Dashboard sign-in is disabled/);
    expect(body).toMatch(/\/pricing/);
  });

  it("POST /login returns 503", async () => {
    const app = makeFullApp();
    const res = await app.request(
      "/login",
      {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: "email=alice%40example.com",
      },
      env(),
    );
    expect(res.status).toBe(503);
  });

  it("GET /auth/verify returns 503", async () => {
    const app = makeFullApp();
    const res = await app.request("/auth/verify?token=abc", {}, env());
    expect(res.status).toBe(503);
  });

  it("POST /auth/logout returns 503", async () => {
    const app = makeFullApp();
    const res = await app.request(
      "/auth/logout",
      { method: "POST" },
      env(),
    );
    expect(res.status).toBe(503);
  });
});

describe("removed dashboard routes", () => {
  it("GET /dashboard is a 404 because the route is no longer mounted", async () => {
    const app = makeFullApp();
    const res = await app.request("/dashboard", {}, env());
    expect(res.status).toBe(404);
  });

  it("GET /billing is a 404 because the route is no longer mounted", async () => {
    const app = makeFullApp();
    const res = await app.request("/billing", {}, env());
    expect(res.status).toBe(404);
  });

  it("POST /dashboard/keys is a 404 because the route is no longer mounted", async () => {
    const app = makeFullApp();
    const res = await app.request(
      "/dashboard/keys",
      {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: "name=x",
      },
      env(),
    );
    expect(res.status).toBe(404);
  });
});
