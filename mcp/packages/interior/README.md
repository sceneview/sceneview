# interior-design-3d-mcp

> **Archived.** `interior-design-3d-mcp` is deprecated on npm and no longer maintained. For SceneView
> code generation, use [`sceneview-mcp`](../../README.md) instead.

**Interior Design 3D MCP — give any AI assistant everything it needs to build room planners, AR furniture placement, material switchers, lighting designers, and virtual room tours with [SceneView](https://sceneview.github.io) on Android.**

Every tool returns Kotlin code written against the SceneView 4.53.0 APIs (Jetpack Compose, `rememberModelInstance`, `ModelNode`, `ARSceneView`, `LightNode` with its `type` and the named `apply` parameter) — ready to drop into an Android project.

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

### Lighting preview coordinates and units

`get_lighting_design` bottom-centers the 3D room and fits its largest dimension to
2 meters. Fixture positions follow the scaled bounding-box dimensions, including
the room's proportions; wall fixtures sit slightly inside the bounds. Local light
falloff scales with the room and extends below every fixture to the floor.

The AR template keeps fixture offsets in meters in a reference 4 × 2.7 × 4 meter
layout. Tap an upward-facing horizontal plane: all fixtures become children of
that plane's anchor, so the floor follows the hit pose rather than world y=0.
Add virtual receiver geometry under the same anchor to see the light's effect;
local lights do not relight the camera image.

Directional intensity uses lux. Point and `SPOT` intensity uses lumens, converted
from the preview illuminance below each fixture using its floor distance:
`lumens = lux × height² × 4π` for points and `lux × height² × π` for Filament's
cone-independent `SPOT`. These daylight-level preview presets preserve the legacy
brightness targets; they are not household bulb ratings or a photometric design
calculation. Finite-radius attenuation, materials, exposure, and the environment
also affect the rendered result. See [Filament's lighting model](https://google.github.io/filament/main/filament.html#lighting).

## Requirements

- Node.js 18+
- Works with any MCP client (Claude, Cursor, Windsurf, etc.)

## Built on SceneView

[SceneView](https://sceneview.github.io) is an open-source 3D & AR SDK for Android (Jetpack Compose + Filament) and iOS (SwiftUI + RealityKit).

## Links

- SceneView: https://sceneview.github.io
- GitHub: https://github.com/sceneview/sceneview
- MCP Registry: https://registry.modelcontextprotocol.io

## License

Apache-2.0
