<!-- category: Added -->
- **The hosted MCP server has a permanent address of its own — `https://mcp.sceneview.dev/mcp`
  — and a step-by-step way to add it to claude.ai in one paste.** The Worker shipped in the
  previous release answered on a Cloudflare-generated `*.workers.dev` hostname, which is fine
  for a smoke test and wrong for anything a user is asked to keep: the URL a connector is
  added with is effectively permanent, because re-pointing it later forces every connected
  user to disconnect and re-add. `mcp.sceneview.dev` is now bound as a Cloudflare custom
  domain on the same zone as `quota.sceneview.dev`, with Cloudflare owning the DNS record and
  the certificate, so the address the docs publish is the address that stays.

  `mcp/README.md` gains a **Use as a Claude connector** section and `llms.txt` a matching
  entry: the URL, the exact path through claude.ai (**Settings → Connectors → Add custom
  connector**), and what the endpoint is — authless and read-only, no account, no API key,
  nothing of yours stored, since every tool is a pure function of the SDK's own docs, samples
  and API surface. It also says plainly when *not* to use it: `analyze_project` reads a
  project from disk and `search_models` / `generate_3d_model` want your own API keys, and a
  shared anonymous endpoint can offer neither — that is what `npx sceneview-mcp` over stdio
  is still for. The deployment test that asserted the domain stayed unbound now asserts the
  binding instead, so the route cannot be dropped from `wrangler.toml` unnoticed.
