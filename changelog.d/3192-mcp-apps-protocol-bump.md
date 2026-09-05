<!-- category: Fixed -->
- **MCP Apps is now declared as an extension, so a host that follows the negotiation rules can
  find the 3D viewer ([#3192](https://github.com/sceneview/sceneview/issues/3192), workstream
  4).** Everything the widget needs shipped a while ago — the `ui://widget/3d-viewer.html`
  resource, its `text/html;profile=mcp-app` mime type, and `_meta.ui.resourceUri` on both tool
  declarations and tool results — but MCP Apps is *opt-in*, negotiated through
  `capabilities.extensions` (SEP-1724), and neither `sceneview-mcp` nor the hosted gateway ever
  named `io.modelcontextprotocol/ui` anywhere. A spec-following host had nothing to switch on: it
  saw a server with tools and resources and no reason to look for a UI. Both handshakes now
  declare `extensions: { "io.modelcontextprotocol/ui": { mimeTypes: ["text/html;profile=mcp-app"] } }`
  from one shared source in `mcp/src/widgets.ts`, so the gateway and `npx sceneview-mcp` cannot
  drift. The declaration is additive on every revision either server speaks — the `ext-apps` spec
  advertises the same capability over `protocolVersion: "2024-11-05"` in its own example — so
  hosts negotiating an earlier revision are unaffected.
- **The gateway answers `server/discover`, so a 2026-07-28 client can discover it at all.** That
  revision removes the `initialize` handshake, which means an `extensions` block living only in
  the handshake result is invisible to a modern client; it would have got a bare `-32601` and
  learned nothing. The gateway now returns the same identity, capabilities and revision list
  without a session or a handshake, the way `sceneview-mcp` already did (#3349). It advertises
  only the revisions it actually implements (`2025-06-18`, `2025-03-26`) — announcing 2026-07-28
  while implementing none of its per-request `_meta` versioning or result envelopes would be the
  worse bug.
- **The gateway degrades to text for a client that negotiated MCP Apps without our mime type.**
  The extension spec asks servers to check the peer's capabilities before advertising UI-enabled
  tools. Silence stays permissive on purpose: every host predating the extension framework —
  ChatGPT included, which drives the widget off the `openai/*` `_meta` keys — declares nothing,
  and gating on silence would have dark-shipped the live listing. Only a client that names the
  extension *and* lists mime types excluding ours loses the pointer; the tool stays listed and
  callable. Tool declarations also merge `_meta.ui` instead of replacing it, so re-affirming the
  widget pointer can no longer drop the `openai/*` spellings or any key the declaration gains
  later. The mime type itself is unchanged and correct: `text/html;profile=mcp-app` is the
  current MCP Apps value, not the withdrawn `text/html+skybridge`.
