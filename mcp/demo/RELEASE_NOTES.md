# sceneview-mcp v3.0.0 — Release Notes

## What is it?

`sceneview-mcp` is an MCP (Model Context Protocol) server that gives AI assistants — Claude Code, Claude Desktop, and any MCP-compatible client — direct access to the SceneView 3D & AR SDK for Android and iOS.

One config line. Then just describe the app you want.

## Why?

LLMs hallucinate APIs. SceneView 3.0.0 shipped a completely new Jetpack Compose API, and no model has it in training data yet. This MCP server solves that: Claude reads the real API reference and gets working sample code, so the generated Kotlin compiles on the first try.

## What's included

**1 resource:**
- `sceneview://api` — the full SceneView 3.0.0 API reference (composable signatures, node types, threading rules, patterns)

**2 tools:**
- `get_sample(scenario)` — returns complete, compilable Kotlin for 5 scenarios:
  - `model-viewer` — 3D scene with GLB model, HDR environment, orbit camera
  - `ar-tap-to-place` — tap to place models on surfaces, pinch/rotate gestures
  - `ar-placement-cursor` — reticle snaps to surfaces, tap to confirm
  - `ar-augmented-image` — detect a printed image, overlay a 3D model
  - `ar-face-filter` — front-camera face mesh with custom material
- `get_setup(type)` — Gradle + AndroidManifest snippet for `"3d"` or `"ar"`

## Install

```json
{
  "mcpServers": {
    "sceneview": {
      "command": "npx",
      "args": ["-y", "sceneview-mcp"]
    }
  }
}
```

In Claude Code, run `claude mcp add --scope project sceneview -- npx -y sceneview-mcp`, which writes the snippet above to `.mcp.json` at the project root — the only project-scoped MCP file Claude Code reads (`.claude/mcp.json` is silently ignored). Use `--scope user` for all your projects; that entry lives in `~/.claude.json`. In Claude Desktop, add it to `claude_desktop_config.json`.

## Links

- npm: https://www.npmjs.com/package/sceneview-mcp
- SDK: https://github.com/sceneview/sceneview
- MCP spec: https://modelcontextprotocol.io
