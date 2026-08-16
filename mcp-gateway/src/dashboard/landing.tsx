/** @jsxImportSource hono/jsx */

import type { FC } from "hono/jsx";
import { Layout } from "./layout.js";
import { renderToHtml } from "./render.js";
import {
  HOSTED_FREE_TOOL_COUNT,
  PRO_TOOL_COUNT,
  VERTICAL_PACKAGE_COUNT,
} from "./counts.js";

/** Public landing page at `/`. */
export const Landing: FC = () => (
  <Layout
    title="Expert 3D and AR knowledge for every AI coding agent"
    description="SceneView MCP — expert 3D and AR knowledge for Claude, Cursor, and every AI coding agent. Hosted HTTP endpoint, free tier, upgrade for Pro packages."
    active="home"
  >
    <section class="hero">
      <h1>Expert 3D and AR knowledge for Claude, Cursor, and every AI agent</h1>
      <p class="lead">
        SceneView MCP gives your AI coding agent the full SceneView 3D
        and AR SDK — composables, threading rules, platform setup, and
        a vetted library of samples. First prompts ship correct,
        compilable code.
      </p>
      <div class="hero-cta">
        <a href="/pricing" class="btn btn-primary">
          Get your API key
        </a>
        <a href="/docs" class="btn btn-secondary">
          Read the docs
        </a>
      </div>
    </section>

    <section class="dash-grid">
      <div class="stat-card">
        <div class="label">Free tools</div>
        <div class="value">{HOSTED_FREE_TOOL_COUNT}</div>
        <p style="margin:.5rem 0 0;font-size:.875rem;">
          Setup, samples, validator, migration, docs. No key required.
        </p>
      </div>
      <div class="stat-card">
        <div class="label">Pro tools</div>
        <div class="value">{PRO_TOOL_COUNT}</div>
        <p style="margin:.5rem 0 0;font-size:.875rem;">
          Vertical packages + 3D preview / artifact / scene generation.
        </p>
      </div>
      <div class="stat-card">
        <div class="label">Specialized packages</div>
        <div class="value">{VERTICAL_PACKAGE_COUNT}</div>
        <p style="margin:.5rem 0 0;font-size:.875rem;">
          Automotive, Gaming, Healthcare, Interior, Rerun.
        </p>
      </div>
    </section>

    <section>
      <h2>Why SceneView MCP</h2>
      <p>
        Every other 3D SDK leaves AI agents guessing. SceneView MCP
        exposes the whole API surface as structured context — node
        types, threading rules, Filament and RealityKit backends, AR
        setup, platform caveats — so the model never has to invent.
      </p>
      <ul style="color:var(--sv-fg-muted);line-height:1.8;">
        <li>Accurate composables for every node type, auto-validated</li>
        <li>Android + iOS + Web + Flutter + React Native in one server</li>
        <li>Hosted behind a fast Cloudflare Worker, no local setup</li>
        <li>Get your API key in one click from the pricing page</li>
      </ul>
    </section>

    <section>
      <h2>How it works</h2>
      <ol style="color:var(--sv-fg-muted);line-height:1.8;">
        <li>
          Pick a plan on the <a href="/pricing">pricing page</a> and pay
          with a card — Stripe handles checkout and email receipts.
        </li>
        <li>
          Copy the <code>sv_live_</code> API key from the success page
          (shown once, so save it in your password manager).
        </li>
        <li>
          Paste it into your Claude Desktop, Cursor, or Zed config and
          start prompting — the hosted gateway is already running.
        </li>
      </ol>
    </section>

    <section>
      <h2>Works with every MCP-capable agent</h2>
      <p>
        Claude Desktop, Claude Code, Cursor, Zed, Continue, and any
        MCP-capable AI client. HTTP-native clients (Cursor, Continue,
        raw curl) point straight at the gateway; stdio-only clients
        (Claude Desktop, Zed) run the thin <code>sceneview-mcp</code>
        npm package which forwards Pro tool calls to the gateway for
        you.
      </p>
      <pre><code>{`{
  "mcpServers": {
    "sceneview": {
      "url": "https://sceneview-mcp.mcp-tools-lab.workers.dev/mcp",
      "headers": { "Authorization": "Bearer sv_live_..." }
    }
  }
}`}</code></pre>
      <p style="margin-top:1.5rem;">
        <a href="/docs" class="btn btn-primary">
          Read the install guide
        </a>
      </p>
    </section>
  </Layout>
);

/** Top-level renderer used by the route handler. */
export function renderLanding(): Promise<string> {
  return renderToHtml(<Landing />);
}
