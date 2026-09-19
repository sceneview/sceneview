# Use SceneView with AI Assistants

Copy the context block below into any AI assistant to get accurate SceneView
code generation on the first try. Nothing here is specific to one assistant.

## Quick context block

Paste this at the start of your conversation:

```
I'm building with SceneView — the Compose-native 3D & AR SDK for Android.
- 3D only: io.github.sceneview:sceneview:4.37.0
- 3D + AR: io.github.sceneview:arsceneview:4.37.0
- Use SceneView { } or ARSceneView { } composables
- Nodes are composables inside the content block
- Load models with rememberModelInstance(modelLoader, "models/file.glb")
- LightNode uses named parameter: apply = { intensity(...) }
- All Filament calls must be on the main thread
- Full API reference: https://sceneview.github.io/llms.txt
```

## MCP server

The pasted block is a summary. The MCP server gives an assistant the whole
thing — 32 tools: code generation, validation, samples, model search
(Sketchfab) and the complete API reference.

```bash
npx sceneview-mcp
```

Any MCP client can run it. The config file and its exact shape differ per
client — see
[AI-assisted development](ai-development.md#per-tool-setup) for the one your
assistant reads, or the client's own documentation.

## Industry-specific MCPs

| Domain | Install | Tools |
|--------|---------|-------|
| Automotive | `npx automotive-3d-mcp` | Car configurators, HUD, showrooms |
| Healthcare | `npx healthcare-3d-mcp` | Anatomy, imaging, surgical planning |
| Gaming | `npx gaming-3d-mcp` | Game scenes, characters, terrain |
| Interior Design | `npx interior-design-3d-mcp` | Room planners, furniture, lighting |

## Rules files in the repo

A checkout of SceneView carries the context file each of these tools reads on
its own, with no setup. They hold the same API contract; only the filename
differs, because each tool looks for its own.

| File | Read by |
|---|---|
| `AGENTS.md` | Codex, and any agent that follows the AGENTS.md convention |
| `CLAUDE.md` | Claude Code |
| `.cursorrules` | Cursor |
| `.github/copilot-instructions.md` | GitHub Copilot |
| `.windsurfrules` | Windsurf |

Working in your own project rather than in a SceneView checkout? Copy the one
your tool reads, or point your assistant at `llms.txt`.

## Full API reference

For AI system prompts, use:

- **Compact**: `https://sceneview.github.io/llms-full.txt` (fits most context windows)
- **Complete**: `https://sceneview.github.io/llms.txt` (full API reference)
- **MCP resource**: `sceneview://api` (served by sceneview-mcp)
