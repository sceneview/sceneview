# game-dev-mcp

MCP server for game development with [SceneView](https://github.com/sceneview/sceneview).

Give Claude (or any MCP-compatible AI assistant) the ability to generate complete, compilable Kotlin code for Android indie games using Jetpack Compose and SceneView — character viewers, level editors, physics, particle effects, 3D inventories, and ready-to-use game assets.

## Renamed from `gaming-3d-mcp`

This package is the v2 rename of `gaming-3d-mcp`. Same tools, same source, clearer name:

- `gaming-3d-mcp` is the legacy name and will be phased out.
- `game-dev-mcp` (this package) is the canonical name going forward.

If you were using `gaming-3d-mcp`, switch to `game-dev-mcp` at your convenience — there is no urgency, the legacy package still works.

## Tools

| Tool | Description |
|---|---|
| `get_character_viewer` | 3D character viewer — animated models, skeletal rigs, blend shapes, outfits. |
| `get_level_editor` | Level editor helpers — tile placement, prop placement, bounding volumes. |
| `get_physics_game` | Physics game setup — rigid bodies, constraints, collisions, joints. |
| `get_particle_effects` | Particle effect templates — fire, smoke, explosions, magic, weather. |
| `get_inventory_3d` | 3D inventory UI — rotating item previews, slot grids, item tooltips. |
| `list_game_models` | Database of free game-ready 3D models — characters, props, environments. |
| `validate_game_code` | Validates SceneView game code for threading, null-safety, API misuse. |

## Installation

### Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "game-dev": {
      "command": "npx",
      "args": ["-y", "game-dev-mcp"]
    }
  }
}
```

### From source

```bash
git clone https://github.com/sceneview/sceneview.git
cd sceneview/mcp/packages/game-dev
npm install
npm run build
```

## License

Apache-2.0 — see [LICENSE](./LICENSE).

## Links

- [SceneView repository](https://github.com/sceneview/sceneview)
- [SceneView website](https://sceneview.github.io)
- [Report an issue](https://github.com/sceneview/sceneview/issues)
- [Sponsor SceneView](https://github.com/sponsors/sceneview)
