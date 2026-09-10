# interior-design-3d-mcp

**Interior Design 3D MCP — give any AI assistant everything it needs to build room planners, AR furniture placement, material switchers, lighting designers, and virtual room tours with [SceneView](https://sceneview.github.io) on Android.**

Every tool returns complete, compilable Kotlin code using current SceneView 4.0.0 APIs (Jetpack Compose, `rememberModelInstance`, `ModelNode`, `ARSceneView`, `LightNode` with the named `apply` parameter) — ready to drop into an Android project.

## Installation

### stdio via `npx` (Claude Desktop, Claude Code, Cursor, Windsurf)

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "interior-design-3d": {
      "command": "npx",
      "args": ["-y", "interior-design-3d-mcp"]
    }
  }
}
```

Then restart Claude Desktop and ask:

> *"Build me an AR furniture placement app where I can place a sofa in my living room."*

### From the command line

```bash
npx interior-design-3d-mcp
```

### Claude Code

```bash
claude mcp add interior-design-3d -- npx interior-design-3d-mcp
```

## Tools

| Tool | Description |
|---|---|
| `get_room_planner` | Generate interactive room planner — drag furniture, resize room, top-down + 3D views |
| `get_furniture_placement` | Generate AR furniture placement — measure space, place items, scale/rotate |
| `get_material_switcher` | Generate material switching UI — floor, wall, countertop textures with live preview |
| `get_lighting_design` | Generate lighting design tool — natural light, fixtures, color temperature |
| `get_room_tour` | Generate virtual room tour — walkthrough, panorama, smooth camera transitions |
| `list_furniture_models` | Browse available furniture 3D models |
| `validate_interior_code` | Validate interior-design-specific SceneView code patterns |

## Requirements

- Node.js 18+
- Works with any MCP client (Claude, Cursor, Windsurf, etc.)

## Built on SceneView

[SceneView](https://sceneview.github.io) is the #1 open-source 3D & AR SDK for Android (Jetpack Compose + Filament) and iOS (SwiftUI + RealityKit).

## Links

- SceneView: https://sceneview.github.io
- GitHub: https://github.com/sceneview/sceneview
- MCP Registry: https://registry.modelcontextprotocol.io

## License

Apache-2.0
