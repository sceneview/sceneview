/** @jsxImportSource hono/jsx */

import type { FC } from "hono/jsx";
import { Layout } from "./layout.js";
import { renderToHtml } from "./render.js";
import {
  HOSTED_FREE_TOOL_COUNT,
  PRO_TOOL_COUNT,
  STDIO_FREE_TOOL_COUNT,
} from "./counts.js";

/** `/docs` page with install instructions per MCP client. */
export const Docs: FC = () => (
  <Layout
    title="Docs"
    description="Install SceneView MCP in Claude Desktop, Cursor, Zed, or any MCP-capable agent. Hosted HTTP endpoint plus legacy stdio fallback."
    active="docs"
  >
    <h1>Docs</h1>
    <p>
      Point any MCP-capable agent at the hosted gateway. The{" "}
      {HOSTED_FREE_TOOL_COUNT} free tools work without authentication; the{" "}
      {PRO_TOOL_COUNT} Pro tools (vertical
      packages + generation helpers) require an API key you receive
      on the <a href="/pricing">pricing</a> checkout success page.
    </p>

    <h2>Quickstart</h2>
    <ol style="color:var(--sv-fg-muted);line-height:1.8;">
      <li>
        Subscribe to Pro or Team on the <a href="/pricing">pricing page</a>.
      </li>
      <li>
        Copy the <code>sv_live_</code> key from the success page (it is
        only shown once — store it in a password manager immediately).
      </li>
      <li>
        Paste it into your <code>claude_desktop_config.json</code> (or
        Cursor / Zed equivalent) under the <code>sceneview</code> server.
      </li>
      <li>
        Restart your MCP client and prompt away — Pro tool calls are
        transparently forwarded to the gateway at{" "}
        <code>https://sceneview-mcp.mcp-tools-lab.workers.dev/mcp</code>.
      </li>
    </ol>

    <h2 id="claude-desktop">Claude Desktop</h2>
    <p>
      Claude Desktop only supports <strong>stdio</strong> MCP servers
      (HTTP transport not yet available), so we ship a lite npm package
      that runs locally and forwards Pro calls to the gateway. Add a
      new server to your config file (macOS:{" "}
      <code>~/Library/Application Support/Claude/claude_desktop_config.json</code>,
      Windows:{" "}
      <code>%APPDATA%\Claude\claude_desktop_config.json</code>):
    </p>
    <pre><code>{`{
  "mcpServers": {
    "sceneview": {
      "command": "npx",
      "args": ["-y", "sceneview-mcp"],
      "env": {
        "SCENEVIEW_API_KEY": "sv_live_YOUR_KEY_HERE"
      }
    }
  }
}`}</code></pre>
    <p>
      Leave <code>env</code> empty (<code>{`{}`}</code>) if you only
      want the {STDIO_FREE_TOOL_COUNT} free tools of the local package — no
      signup needed. Restart Claude Desktop after editing.
    </p>

    <h2>Cursor</h2>
    <p>
      Cursor supports HTTP MCP servers natively, so you can talk to
      the gateway directly. Open Cursor Settings → MCP → Add new:
    </p>
    <pre><code>{`{
  "mcpServers": {
    "sceneview": {
      "url": "https://sceneview-mcp.mcp-tools-lab.workers.dev/mcp",
      "headers": {
        "Authorization": "Bearer sv_live_YOUR_KEY_HERE"
      }
    }
  }
}`}</code></pre>

    <h2>Zed</h2>
    <p>
      Zed's context servers support stdio too; use the same{" "}
      <code>sceneview-mcp</code> command as Claude Desktop above.
      Add it to <code>~/.config/zed/settings.json</code> under the{" "}
      <code>context_servers</code> section with the{" "}
      <code>SCENEVIEW_API_KEY</code> env var set.
    </p>

    <h2>Raw curl</h2>
    <p>
      The endpoint speaks Streamable HTTP JSON-RPC. Handy for testing
      a new key right after checkout:
    </p>
    <pre><code>{`curl -X POST https://sceneview-mcp.mcp-tools-lab.workers.dev/mcp \\
  -H "Authorization: Bearer sv_live_YOUR_KEY_HERE" \\
  -H "Content-Type: application/json" \\
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/list"
  }'`}</code></pre>

    <h2>Local stdio (free mode, zero signup)</h2>
    <p>
      If you only want the {STDIO_FREE_TOOL_COUNT} free tools of the npm
      package and no network round-trip at all, install the latest stable
      package (4.0.x):
    </p>
    <pre><code>{`npx -y sceneview-mcp`}</code></pre>
    <p>
      This runs every developer tool locally — setup, samples, validator,
      migration, full API reference. To unlock the {PRO_TOOL_COUNT} Pro tools later
      (vertical packages + generation helpers), set{" "}
      <code>SCENEVIEW_API_KEY</code> — see the Claude Desktop snippet
      above.
    </p>

    <h2>Usage and rate limits</h2>
    <ul style="color:var(--sv-fg-muted);line-height:1.8;">
      <li>Free: 60 calls/hour, 1 000 calls/month, 1 API key</li>
      <li>Pro: 600 calls/hour, 10 000 calls/month, 3 API keys</li>
      <li>Team: 3 000 calls/hour, 50 000 calls/month, 10 API keys</li>
    </ul>
    <p>
      Every <code>/mcp</code> response carries standard{" "}
      <code>X-RateLimit-Limit</code>,{" "}
      <code>X-RateLimit-Remaining</code>, and{" "}
      <code>X-RateLimit-Reset</code> headers so clients can pre-empt
      throttling.
    </p>

    <h2>Errors</h2>
    <p>
      All errors are valid JSON-RPC 2.0 responses. Common codes:
    </p>
    <ul style="color:var(--sv-fg-muted);line-height:1.8;">
      <li><code>-32001</code> Unauthorized — bad or missing API key</li>
      <li><code>-32002</code> Access denied — Pro tool on the free tier</li>
      <li><code>-32029</code> Rate limited — hourly or monthly quota exceeded</li>
    </ul>

    <h2>Need help?</h2>
    <p>
      Open an issue on{" "}
      <a href="https://github.com/sceneview/sceneview/issues">
        github.com/sceneview/sceneview
      </a>{" "}
      or email <a href="mailto:hello@sceneview.dev">hello@sceneview.dev</a>.
    </p>
  </Layout>
);

/** Top-level renderer used by the route handler. */
export function renderDocs(): Promise<string> {
  return renderToHtml(<Docs />);
}
