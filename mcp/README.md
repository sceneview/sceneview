# sceneview-mcp

**Give any AI assistant expert-level knowledge of 3D and AR development.**

[![npm version](https://img.shields.io/npm/v/sceneview-mcp?color=6c35aa)](https://www.npmjs.com/package/sceneview-mcp)
[![npm downloads](https://img.shields.io/npm/dm/sceneview-mcp?color=blue)](https://www.npmjs.com/package/sceneview-mcp)
[![Tests](https://img.shields.io/badge/tests-2918%20passing-brightgreen)](#quality)
[![MCP](https://img.shields.io/badge/MCP-v1.12-blue)](https://modelcontextprotocol.io/)
[![Registry](https://img.shields.io/badge/MCP%20Registry-listed-blueviolet)](https://registry.modelcontextprotocol.io)
[![License](https://img.shields.io/badge/License-MIT-green)](./LICENSE)
[![Node](https://img.shields.io/badge/Node-%3E%3D18-brightgreen)](https://nodejs.org/)

The official [Model Context Protocol](https://modelcontextprotocol.io/) server for **[SceneView](https://sceneview.github.io)** — the cross-platform 3D & AR SDK for Android (Jetpack Compose + Filament), iOS / macOS / visionOS (SwiftUI + RealityKit), and Web (Filament.js + WebXR).

Connect it to Claude, Cursor, Windsurf, or any MCP client. Your AI assistant gets specialized tools, compilable code samples, the full API reference, and a code validator — so it writes correct, working 3D/AR code on the first try.

> **Disclaimer:** Generated code is provided "as is" without warranty. Always review before production use. See [TERMS.md](./TERMS.md) and [PRIVACY.md](./PRIVACY.md).

---

## Quick start

**One command — no install required:**

```bash
npx sceneview-mcp
```

### Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or `%APPDATA%\Claude\claude_desktop_config.json` (Windows):

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

Restart Claude Desktop after saving.

### Claude Code

Two options.

**Recommended — install the [SceneView Claude Code plugin](https://github.com/sceneview/claude-marketplace)** to get this MCP server **plus** 11 namespaced contributor commands and cross-platform reminder hooks in one shot:

```bash
/plugin marketplace add sceneview/claude-marketplace
/plugin install sceneview@sceneview
```

**Or — just the MCP server** (lighter, no commands or hooks):

```bash
claude mcp add sceneview -- npx -y sceneview-mcp
```

### Cursor

Open **Settings > MCP**, add a new server named `sceneview` with command `npx -y sceneview-mcp`. Or add to `.cursor/mcp.json`:

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

### Windsurf / Other MCP clients

Same JSON config as above. The server communicates via **stdio** using the standard MCP protocol.

---

## What you get

Every developer tool is **free**: setup guides for every platform, code samples, the API reference, the migration tooling, the validator, model search, and the project analyzer.

### Free tools

#### Setup & integration

| Tool | What it does |
|---|---|
| `get_setup` | Gradle + manifest setup for Android 3D or AR |
| `get_ios_setup` | SPM dependency, Info.plist, SwiftUI for iOS / macOS / visionOS |
| `get_web_setup` | Kotlin/JS + Filament.js (WASM) for browser-based 3D |
| `get_ar_setup` | Permissions, session options, plane detection, image tracking |
| `get_platform_setup` | Unified setup guide for any platform (Android, iOS, Web, Flutter, RN, Desktop, TV) |

#### Code generation & migration

| Tool | What it does |
|---|---|
| `get_sample` | Returns a complete, compilable code sample for any of 33 scenarios (Kotlin or Swift) |
| `list_samples` | Browse all samples, filter by tag (`ar`, `3d`, `ios`, `animation`, `geometry`, ...) |
| `validate_code` | Checks generated code against 15+ rules before presenting it to the user |
| `migrate_code` | Automatically migrates SceneView 2.x / 3.x code with detailed changelog |
| `get_migration_guide` | Every breaking change with before/after code |

#### API reference

| Tool | What it does |
|---|---|
| `get_node_reference` | Full API reference for any of 44+ node types — exact signatures, defaults, examples |
| `list_platforms` | Supported platforms with their status, renderer, and framework |
| `get_platform_roadmap` | Multi-platform status and timeline |

#### Guides

`get_best_practices` · `get_animation_guide` · `get_gesture_guide` · `get_performance_tips` · `get_material_guide` · `get_collision_guide` · `get_model_optimization_guide` · `get_web_rendering_guide` · `get_troubleshooting` · `debug_issue`

#### Discovery & analysis

| Tool | What it does |
|---|---|
| `search_models` | Searches Sketchfab for free 3D models (BYOK — set `SKETCHFAB_API_KEY`) |
| `analyze_project` | Scans a local SceneView project on disk — detects platform, extracts version, flags outdated deps and known anti-patterns |
| `search_android_docs` | Searches Google's stock Android docs knowledge base (needs the `android` CLI on PATH) |
| `fetch_android_doc` | Fetches a full Android docs entry by its `kb://...` URI (needs the `android` CLI on PATH) |

### 2 resources

| Resource URI | What it provides |
|---|---|
| `sceneview://api` | Complete SceneView 4.0.x API reference (the full `llms.txt`) |
| `sceneview://known-issues` | Live open issues from GitHub (cached 10 min) |

---

## `search_models` — find real 3D assets from the AI

Generated SceneView code is only useful if it points at an asset that actually exists. `search_models` queries Sketchfab's public search API and returns a shortlist with names, authors, licenses, thumbnails, triangle counts, and viewer/embed URLs that the assistant can drop straight into `rememberModelInstance(modelLoader, ...)` or embed as a live preview.

**Bring your own key (BYOK).** SceneView never proxies the request — you keep the rate limit and the cost stays at zero. To set it up:

1. Create a free account at [sketchfab.com/register](https://sketchfab.com/register)
2. Copy your API token from [sketchfab.com/settings/password](https://sketchfab.com/settings/password)
3. Set `SKETCHFAB_API_KEY` in your MCP client config:

```json
{
  "mcpServers": {
    "sceneview": {
      "command": "npx",
      "args": ["-y", "sceneview-mcp"],
      "env": { "SKETCHFAB_API_KEY": "YOUR_TOKEN_HERE" }
    }
  }
}
```

Call it like `search_models({ query: "red sports car", category: "cars-vehicles", maxResults: 6 })`. If the key is missing, the tool returns a clear message explaining how to get one instead of failing silently.

## `analyze_project` — local project scan

Because the MCP server runs on the user's machine, `analyze_project` can read their project files directly. Given a `path` (default: `process.cwd()`), it:

- Detects the project type by looking for `build.gradle(.kts)` with `io.github.sceneview:sceneview` (Android), `Package.swift` with `SceneViewSwift` (iOS), or `package.json` with `sceneview-web` (Web).
- Extracts the SceneView dependency version and compares it against the latest release known to this MCP build, flagging outdated projects.
- Walks up to **30** source files (`.kt`, `.kts`, `.swift`, `.js`, `.ts`) and up to **500 KB** total, scanning for well-known anti-patterns: Filament/ModelLoader calls inside background coroutines, the `LightNode(...) { ... }` trailing-lambda bug, deprecated 2.x APIs (`ArSceneView`, `TransformableNode`, `PlacementNode`, `ViewRenderable`, `loadModelAsync`), and `com.google.ar.sceneform.*` imports.
- Returns a structured `{ projectType, sceneViewVersion, latestVersion, isOutdated, warnings, suggestions }` report, plus a Markdown summary.

The tool is read-only, never writes to disk, and gracefully handles missing directories. Use it when the user asks "is my project up to date?" or as a quick sanity check before generating new code for an existing codebase.

---

## Examples

### "Build me an AR app"

The assistant calls `get_ar_setup` + `get_sample("ar-model-viewer")` and returns a complete, compilable Kotlin composable with all imports, Gradle dependencies, and manifest entries. Ready to paste into Android Studio.

### "Create a 3D model viewer for iOS"

The assistant calls `get_ios_setup("3d")` + `get_sample("ios-model-viewer")` and returns Swift code with the SPM dependency, Info.plist entries, and a working SwiftUI view.

### "What parameters does LightNode accept?"

The assistant calls `get_node_reference("LightNode")` and returns the exact function signature, parameter types, defaults, and a usage example — including the critical detail that `apply` is a named parameter, not a trailing lambda.

### "Validate this code before I use it"

The assistant calls `validate_code` with the generated snippet and checks it against 15+ rules: threading violations, null safety, API correctness, lifecycle issues, deprecated APIs. Problems are flagged with explanations before the code reaches the user.

---

## Why this exists

**Without** this MCP server, AI assistants regularly:
- Recommend deprecated **Sceneform** (abandoned 2021) instead of SceneView
- Generate imperative **View-based** code instead of Jetpack Compose
- Use **wrong API signatures** or outdated parameter names
- Miss the `LightNode` named-parameter gotcha (`apply =` not trailing lambda)
- Forget null-checks on `rememberModelInstance` (it returns `null` while loading)
- Have no knowledge of SceneView's iOS/Swift API at all

**With** this MCP server, AI assistants:
- Always use the current SceneView 4.0.x API surface
- Generate correct **Compose-native** 3D/AR code for Android
- Generate correct **SwiftUI-native** code for iOS/macOS/visionOS
- Know about all 44+ node types and their exact parameters
- Validate code against 15+ rules before presenting it
- Provide working, tested sample code for 33 scenarios

---

## Quality

The MCP server is tested with **2,918 unit tests** across 132 test suites covering:

- Every tool response (correct output, error handling, edge cases)
- All 33 code samples (compilable structure, correct imports, no deprecated APIs)
- Code validator rules (true positives and false-positive resistance)
- Node reference parsing (all node types extracted correctly from `llms.txt`)
- Resource responses (API reference, GitHub issues integration)

```
 Test Files  132 passed (132)
      Tests  2918 passed (2918)
```

All tools work **fully offline** except `sceneview://known-issues` (GitHub API, cached 10 min) and `search_models` (Sketchfab, BYOK).

---

## Troubleshooting

### "MCP server not found" or connection errors

1. Ensure Node.js 18+ is installed: `node --version`
2. Test manually: `npx sceneview-mcp` — should start without errors
3. Restart your AI client after changing the MCP configuration

### "npx command not found"

Install Node.js from [nodejs.org](https://nodejs.org/) (LTS recommended). npm and npx are included.

### Server starts but tools are not available

- **Claude Desktop:** check the MCP icon in the input bar — it should show "sceneview" as connected
- **Cursor:** check **Settings > MCP** for green status
- Restart the AI client to force a reconnect

### Firewall or proxy issues

The only network calls are to the GitHub API (for known issues) and Sketchfab (when `SKETCHFAB_API_KEY` is set). Everything else works offline.

```json
{
  "mcpServers": {
    "sceneview": {
      "command": "npx",
      "args": ["-y", "sceneview-mcp"],
      "env": {
        "HTTPS_PROXY": "http://proxy.example.com:8080"
      }
    }
  }
}
```

---

## Optional: vertical packages

A small set of domain-specific tools is gated behind an optional subscription. They aren't required for general 3D/AR work — only useful if you happen to be building one of these specific verticals:

- **Automotive** — car configurator, paint shader, parts catalog, HUD overlay, AR showroom
- **Gaming** — physics, particles, level editor, character viewer, inventory 3D
- **Healthcare** — surgical planning, dental viewer, medical imaging, anatomy, molecule viewer
- **Interior** — room planner, lighting design, material switcher, furniture placement, room tour

Plus 3 generation helpers: `render_3d_preview`, `create_3d_artifact`, `generate_scene`.

If you need any of these, see the [pricing page](https://sceneview-mcp.mcp-tools-lab.workers.dev/pricing). The base SDK and every developer tool listed above stay free, always.

---

## Sponsor

If sceneview-mcp saves you time, consider [sponsoring on GitHub Sponsors](https://github.com/sponsors/sceneview). Building this is a one-dev labor of love and donations keep the free tier covered.

---

## Anonymous telemetry

Enabled by default on the free tier (MCP client name/version and tool names — no personal data, no prompt content). Opt out with `SCENEVIEW_TELEMETRY=0`. See [PRIVACY.md](./PRIVACY.md#telemetry-free-tier) for the full payload shape.

---

## Development

```bash
cd mcp
npm install
npm run prepare  # Copy llms.txt + build TypeScript
npm test         # 2918 tests
npm run dev      # Start with tsx (hot reload)
```

### Project structure

```
mcp/
  src/
    index.ts             # MCP server entry point
    tools/handler.ts     # Tool dispatcher (free + pro)
    tiers.ts             # Free vs Pro tier mapping
    samples.ts           # 33 compilable code samples (Kotlin + Swift)
    validator.ts         # Code validator (15+ rules)
    node-reference.ts    # Node type parser
    guides.ts            # Best practices, AR setup, roadmap, troubleshooting
    migration.ts         # v2 -> v3 -> v4 migration guide
    preview.ts           # 3D preview URL generator
    artifact.ts          # HTML artifact generator (model-viewer, charts, product 360)
    issues.ts            # GitHub issues fetcher (cached)
    search-models.ts     # Sketchfab BYOK search
    analyze-project.ts   # Local project scanner
    proxy.ts             # Pro-tool proxy to hosted gateway
  llms.txt               # Bundled API reference (copied from repo root)
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Add tests for new tools or rules
4. Run `npm test` — all 2918+ tests must pass
5. Submit a pull request

See [CONTRIBUTING.md](../CONTRIBUTING.md) for the full guide.

## Legal

- [LICENSE](./LICENSE) — MIT License
- [TERMS.md](./TERMS.md) — Terms of Service
- [PRIVACY.md](./PRIVACY.md) — Privacy Policy
