<!-- category: Added -->
- **`sceneview-mcp` now has a hosted endpoint, so Claude, ChatGPT and anything else that
  speaks remote MCP can reach it without installing anything.** Until now the server only
  existed as a local process: `npx sceneview-mcp` over stdio, or `--http` on a port you
  hosted yourself. Nothing was deployed, which meant every remote-MCP surface — a Claude
  custom connector, the OpenAI API `mcp` tool — was out of reach no matter how complete the
  protocol work was. A Cloudflare Worker now runs that same server publicly: `POST /mcp`
  speaks Streamable HTTP, `GET /health` reports the version, and the 29 free tools are
  listed and callable anonymously with no key and no account. The Worker is 15 lines,
  because it does not reimplement anything — Cloudflare's `httpServerHandler` runs the
  existing `node:http` listener from `--http` verbatim, so the hosted endpoint and the local
  one are the same code path and a bug fixed in one is fixed in both.
- **Every tool now declares a human-readable `title`.** 31 of the 32 tools shipped with
  `readOnlyHint` / `openWorldHint` / `destructiveHint` but no title, so clients that show a
  tool picker had nothing to show but the raw function name — and Anthropic's connectors
  directory rejects a server whose tools lack one. `view_3d_model` also stopped describing
  itself as rendering "inline in ChatGPT": it renders inline in whatever conversation is
  hosting it, and a host reading its own description should not be told it is a different
  product. A contract test now fails the build if a tool is added without a title or without
  its behaviour hints.
<!-- RELEASE NOTE (maintainer-only):
     The Worker is deployed to a workers.dev URL only. `mcp.sceneview.dev` is deliberately
     NOT bound in wrangler.toml (a test asserts it stays unbound): a connector's URL is
     effectively permanent — re-pointing it forces every existing user to disconnect and
     re-add — so binding the custom domain is a release decision made when the listing is
     submitted, not a deploy detail.

     Found while reading, NOT fixed here because it needs a product decision first: the Pro
     tier's gateway is dead. `DEFAULT_GATEWAY_URL` and `DEFAULT_PRICING_URL` in `proxy.ts`
     both point at `sceneview-mcp.mcp-tools-lab.workers.dev`, which now returns Cloudflare
     error 1042 / HTTP 404 — that worker went with the 2026-08-23 MCP-portfolio deletion.
     The shipped npm package therefore advertises a EUR19/month product whose checkout page
     404s, and any user who sets SCENEVIEW_API_KEY gets a proxy failure. Either the gateway
     comes back or the Pro upsell comes out. -->
