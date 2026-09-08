<!-- category: Fixed -->
- **The `npx sceneview-mcp` stdio server now honours the MCP Apps mime negotiation it
  advertises ([#3485](https://github.com/sceneview/sceneview/issues/3485)).** The server
  declares the `io.modelcontextprotocol/ui` extension, which tells a host it follows the
  extension's rule: degrade to text when the client declares mime types excluding
  `text/html;profile=mcp-app`. The HTTP gateway did that; the stdio server did not — it
  mapped every tool declaration through unchanged, `_meta.ui.resourceUri` included, and
  attached the same pointer to every `view_3d_model` result, so a host that had just said
  it cannot render our widget was pointed at one anyway. Both handlers now read the
  client's declared extensions from the handshake and take the pointer off declaration and
  result together, so the two transports cannot disagree. What does **not** change: a
  client that declares no extension at all — ChatGPT today, which drives the widget off the
  `openai/*` keys — still gets its widget, and so does one that names our mime type. The
  tool itself stays listed, callable and text-answering in every case: degradation, not
  failure. An explicit empty `mimeTypes` list is now documented as counting with silence,
  since the spec makes the field required and an empty one is malformed rather than a
  refusal.
