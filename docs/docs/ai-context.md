# Use SceneView with AI Assistants

Copy the context block below into any AI assistant (Claude, ChatGPT, Gemini, Copilot)
to get accurate SceneView code generation on the first try.

## Quick context block

Paste this at the start of your conversation:

```
I'm building with SceneView — the Compose-native 3D & AR SDK for Android.
- 3D only: io.github.sceneview:sceneview:4.30.0
- 3D + AR: io.github.sceneview:arsceneview:4.30.0
- Use SceneView { } or ARSceneView { } composables
- Nodes are composables inside the content block
- Load models with rememberModelInstance(modelLoader, "models/file.glb")
- LightNode uses named parameter: apply = { intensity(...) }
- All Filament calls must be on the main thread
- Full API reference: https://sceneview.github.io/llms.txt
```

## MCP Server (recommended)

The MCP server gives the assistant direct access to 31 tools: code generation,
validation, samples, model search (Sketchfab), and the complete API reference — the
real symbols instead of recall.

```bash
claude mcp add sceneview -- npx -y sceneview-mcp   # Claude Code
codex mcp add sceneview -- npx -y sceneview-mcp    # OpenAI Codex CLI
```

Any other MCP client takes the same stdio server:

```json
{ "mcpServers": { "sceneview": { "command": "npx", "args": ["-y", "sceneview-mcp"] } } }
```

Clients that want a URL rather than a command — Gemini Enterprise, ChatGPT's connector
picker, Claude Desktop's remote MCP — can point at the hosted HTTP endpoint instead; see
the [MCP README](https://github.com/sceneview/sceneview/tree/main/mcp#readme).

## Industry-specific MCPs

| Domain | Install | Tools |
|--------|---------|-------|
| Automotive | `npx automotive-3d-mcp` | Car configurators, HUD, showrooms |
| Healthcare | `npx healthcare-3d-mcp` | Anatomy, imaging, surgical planning |
| Gaming | `npx gaming-3d-mcp` | Game scenes, characters, terrain |
| Interior Design | `npx interior-design-3d-mcp` | Room planners, furniture, lighting |

## Agent rules file

SceneView ships [`AGENTS.md`](https://github.com/sceneview/sceneview/blob/main/AGENTS.md)
at the repo root — the cross-vendor standard read natively by Codex, Cursor, Copilot,
Gemini CLI, Aider, Windsurf and Zed. Working inside a SceneView project, the agent picks
it up on its own; copy it into your own project to get the same rules there.

Its Kotlin snippets are compile-checked in CI, so the templates it hands the assistant
are the ones that build. `.cursorrules`, `.windsurfrules` and
`.github/copilot-instructions.md` remain as thin pointers for older builds that only look
for those paths.

## Full API Reference

For AI system prompts, use:
- **Compact**: `https://sceneview.github.io/llms-full.txt` (fits most context windows)
- **Complete**: `https://sceneview.github.io/llms.txt` (full API reference)
- **MCP resource**: `sceneview://api` (served by sceneview-mcp)
