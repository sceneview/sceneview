# SceneView — GitHub Copilot instructions

**Read [`AGENTS.md`](../AGENTS.md) at the repo root.** It is the canonical rules file for
every coding agent — dependencies, the six rules that decide whether generated SceneView
code works, the Android 3D/AR templates, the Apple snippet, and the full node-type list.

Copilot reads `AGENTS.md` natively. This file exists so an older Copilot build that only
looks at `.github/copilot-instructions.md` is not left with nothing, and it is
deliberately a pointer rather than a copy: the version it used to carry sat 30 minors
stale, and its node-type list named four types that do not exist (`GeospatialNode`,
`DepthNode`, `InstantPlacementNode`, `ArrowNode`). One file cannot drift from itself.

For the real API surface rather than recall, add the MCP server:

```json
{ "mcpServers": { "sceneview": { "command": "npx", "args": ["-y", "sceneview-mcp"] } } }
```

Maven: `io.github.sceneview:sceneview:4.30.0` · SPM: `https://github.com/sceneview/sceneview.git` (from: "4.30.0")
