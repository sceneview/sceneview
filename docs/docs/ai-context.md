# Use SceneView with AI Assistants

Copy the context block below into any AI assistant (Claude, ChatGPT, Gemini, Copilot)
to get accurate SceneView code generation on the first try.

## Quick context block

Paste this at the start of your conversation:

```
I'm building with SceneView — the Compose-native 3D & AR SDK for Android.
- 3D only: io.github.sceneview:sceneview:4.15.2
- 3D + AR: io.github.sceneview:arsceneview:4.15.2
- Use SceneView { } or ARSceneView { } composables
- Nodes are composables inside the content block
- Load models with rememberModelInstance(modelLoader, "models/file.glb")
- LightNode uses named parameter: apply = { intensity(...) }
- All Filament calls must be on the main thread
- Full API reference: https://sceneview.github.io/llms.txt
```

## MCP Server (recommended for Claude)

For the best experience with Claude, install the SceneView MCP server:

```bash
npx sceneview-mcp
```

This gives Claude direct access to 28 tools: code generation, validation,
samples, model search (Sketchfab), and the complete API reference.

## Industry-specific MCPs

| Domain | Install | Tools |
|--------|---------|-------|
| Automotive | `npx automotive-3d-mcp` | Car configurators, HUD, showrooms |
| Healthcare | `npx healthcare-3d-mcp` | Anatomy, imaging, surgical planning |
| Gaming | `npx gaming-3d-mcp` | Game scenes, characters, terrain |
| Interior Design | `npx interior-design-3d-mcp` | Room planners, furniture, lighting |

## IDE Integration

### GitHub Copilot
SceneView includes `.github/copilot-instructions.md` — Copilot automatically
uses it when working in any SceneView project.

### Cursor / Windsurf
SceneView includes `.cursorrules` and `.windsurfrules` with patterns for
correct 3D/AR code generation.

## Full API Reference

For AI system prompts, use:
- **Compact**: `https://sceneview.github.io/llms-full.txt` (fits most context windows)
- **Complete**: `https://sceneview.github.io/llms.txt` (3000+ lines, full API)
- **MCP resource**: `sceneview://api` (served by sceneview-mcp)
