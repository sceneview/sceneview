<!-- category: Fixed -->
- **The MCP gateway no longer refuses discovery to hosts on a newer protocol revision, and
  a tool result no longer carries a widget the session negotiated away
  ([#3502](https://github.com/sceneview/sceneview/issues/3502)).** `server/discover` is the
  handshake-free call whose whole purpose is to say which revisions this server speaks, yet
  it sat behind the `MCP-Protocol-Version` gate: a host sending its own current revision in
  that header — exactly what the spec invites — got a 400 instead of the one answer that
  would have let it negotiate down. Discovery is now exempt from the gate; every other
  method still is not. Second half: `tools/list` already stripped `_meta.ui.resourceUri`
  for a client that declared MCP Apps mime types excluding `text/html;profile=mcp-app`, but
  `tools/call` attached the pointer unconditionally, so a host that discovers widgets from
  results rather than declarations was still handed a widget it had just said it cannot
  render. The call path now reads the same session decision and takes the pointer back off
  the result — the text content and `structuredContent` are untouched, which is the
  graceful degradation the extension asks for. Four transport tests pin both halves,
  including that a client declaring nothing still gets its widget.
