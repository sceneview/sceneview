# gaming-3d-mcp

**Gaming 3D MCP — give any AI assistant everything it needs to build character viewers, level editors, physics games, particle effects, and 3D inventories with [SceneView](https://sceneview.github.io) on Android.**

Every tool returns complete, compilable Kotlin code using current SceneView 4.0.0 APIs (Jetpack Compose, `rememberModelInstance`, `ModelNode`, `PhysicsNode`, `LightNode` with the named `apply` parameter) — ready to drop into an Android project.

## Installation

### Option A — stdio via `npx` (Claude Desktop, Claude Code, Cursor, Windsurf)

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "gaming-3d": {
      "command": "npx",
      "args": ["-y", "gaming-3d-mcp"]
    }
  }
}
```

Then restart Claude Desktop and ask:

> *"Build me a 3D character viewer with animation controls and orbit camera."*

### Option B — hosted HTTP MCP URL (ChatGPT, Claude Desktop "Remote MCP", Cursor URL)

The 7 gaming tools are also exposed (along with sceneview-mcp + automotive/healthcare/interior/rerun verticals and the gateway's widget tool — 67 tools total) on the shared SceneView gateway:

```
https://sceneview-mcp.mcp-tools-lab.workers.dev/mcp/public
```

- Anonymous, no API key required
- All 7 gaming tools are Pro tier — they return a JSON-RPC ACCESS_DENIED with a `/pricing` pointer until you subscribe
- Rate limit: 60 req/h per IP

For the **authenticated Pro tier** (full quota, all tools dispatch):

```
https://sceneview-mcp.mcp-tools-lab.workers.dev/mcp
Authorization: Bearer sv_live_...
```

### From the command line

```bash
npx gaming-3d-mcp
```

### Claude Code

```bash
claude mcp add gaming-3d -- npx gaming-3d-mcp
```

## Tools

| Tool | Description |
|---|---|
| `get_character_viewer` | Generate character viewer with animation playback, customization, and orbit camera |
| `get_physics_game` | Generate physics-based game — ball drop, ragdoll, destruction, gravity |
| `get_particle_effects` | Generate particle systems — fire, smoke, sparks, magic, rain, snow |
| `get_level_editor` | Generate tile-based level editor — place objects, terrain, lighting |
| `get_inventory_3d` | Generate 3D inventory — item grid, 3D model inspection, rotate/zoom |
| `list_game_models` | Browse available game 3D models |
| `validate_game_code` | Validate gaming-specific SceneView code patterns |

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
