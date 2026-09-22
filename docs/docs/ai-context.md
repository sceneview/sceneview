# Use SceneView with AI Assistants

Copy the context block below into any AI assistant to get accurate SceneView
code generation on the first try. Nothing here is specific to one assistant.

## Quick context block

Paste this at the start of your conversation:

```
I'm building with SceneView — the Compose-native 3D & AR SDK for Android.
- 3D only: io.github.sceneview:sceneview:4.38.0
- 3D + AR: io.github.sceneview:arsceneview:4.38.0
- Use SceneView { } or ARSceneView { } composables
- Nodes are composables inside the content block
- Load models with rememberModelInstance(modelLoader, "models/file.glb")
- LightNode uses named parameter: apply = { intensity(...) }
- All Filament calls must be on the main thread
- Full API reference: https://sceneview.github.io/llms.txt
```

## MCP server

The pasted block is a summary. The MCP server gives an assistant the whole
thing — 33 tools: code generation, validation, samples, model search
(Sketchfab) and the complete API reference.

```bash
npx -y sceneview-mcp
```

Any MCP client can run it. The config file and its exact shape differ per
client — see
[AI-assisted development](ai-development.md#per-tool-setup) for the one your
assistant reads, or the client's own documentation.

## Rules files in the repo

A checkout of SceneView carries the context file each of these tools reads on
its own, with no setup. They hold the same API contract; only the filename
differs, because each tool looks for its own. Alphabetical:

| Read by | File |
|---|---|
| Claude Code | `CLAUDE.md` |
| Codex, and any agent that follows the AGENTS.md convention | `AGENTS.md` |
| Cursor | `.cursorrules` — documented as a legacy single file that is still read; Cursor's current mechanism is `.cursor/rules/*.mdc` plus `AGENTS.md` |
| Devin Desktop / Windsurf | `.windsurfrules` — documented as a legacy single file that is still read; the current mechanism is `.devin/rules/` (or `.windsurf/rules/`) plus `AGENTS.md` |
| GitHub Copilot | `.github/copilot-instructions.md` |

Working in your own project rather than in a SceneView checkout? Copy the one
your tool reads, or point your assistant at `llms.txt`.

## Full API reference

For AI system prompts, use:

- **Compact**: `https://sceneview.github.io/llms-full.txt` (~12 kB, fits most context windows)
- **Complete**: `https://sceneview.github.io/llms.txt` (full API reference, and an index of these docs at the top)
- **MCP resource**: `sceneview://api` (served by sceneview-mcp)

Yes, the names are the reverse of the [llmstxt.org](https://llmstxt.org)
convention, which reserves `llms.txt` for a short linked index and
`llms-full.txt` for the whole content. `/llms.txt` has served the full
reference since it was first published and is what the sitemap, `AGENTS.md`,
the per-tool rule files, the `sceneview-mcp` package and outside indexes all
point at, so the URLs stay put; the index the convention asks for was added to
the top of `/llms.txt` instead.
