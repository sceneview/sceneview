# interior-design-mcp

MCP server for interior design with [SceneView](https://github.com/sceneview/sceneview).

Give Claude (or any MCP-compatible AI assistant) the ability to generate complete, compilable Kotlin code for Android home design apps using Jetpack Compose and SceneView — room planning, furniture placement, material library, lighting design, AR furniture preview, and room tours.

## Renamed from `interior-design-3d-mcp`

This package is the v2 rename of `interior-design-3d-mcp`. Same tools, same source, clearer name:

- `interior-design-3d-mcp` is the legacy name and will be phased out.
- `interior-design-mcp` (this package) is the canonical name going forward.

If you were using `interior-design-3d-mcp`, switch to `interior-design-mcp` at your convenience — there is no urgency, the legacy package still works.

## Tools

| Tool | Description |
|---|---|
| `get_room_planner` | Room planning — walls, floors, doors, windows, measurements. |
| `get_furniture_placement` | Furniture placement helpers — snap-to-floor, collision avoidance, rotation. |
| `get_material_switcher` | Material library — fabric, wood, metal, stone, leather, presets. |
| `get_lighting_design` | Lighting design — point, spot, area lights, IBL, sun, shadows. |
| `get_room_tour` | Room tour / walkthrough — camera paths, waypoints, guided tours. |
| `list_furniture_models` | Database of free interior-design 3D models — furniture, decor, appliances. |
| `validate_interior_code` | Validates SceneView interior code for threading, null-safety, API misuse. |

## Installation

### Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "interior-design": {
      "command": "npx",
      "args": ["-y", "interior-design-mcp"]
    }
  }
}
```

### From source

```bash
git clone https://github.com/sceneview/sceneview.git
cd sceneview/mcp/packages/interior-design
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
