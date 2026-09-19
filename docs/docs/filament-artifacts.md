# SceneView 3D Artifacts for AI Assistants

## Design Document: Lightweight 3D Rendering in Claude Artifacts

**Status**: Implementation Phase 1 (Pure WebGL) — Live
**Authors**: SceneView Team
**Date**: 2026-03-25

---

## Problem Statement

AI assistants like Claude can generate interactive HTML artifacts, but **3D content is
currently impractical** because existing solutions are too heavy:

| Library | Bundle Size | Load Time (3G) | Memory | Claude Artifact Compatible |
|---------|------------|----------------|--------|---------------------------|
| **SceneView WebGL** | **~15 KB** | **< 0.5s** | **~8 MB** | **Yes** |
| model-viewer | ~180 KB + Three.js | 3-8s | ~120 MB | Barely (lags) |
| Three.js | ~600 KB | 5-12s | ~80 MB | No (too large) |
| Babylon.js | ~1.2 MB | 8-15s | ~150 MB | No |
| A-Frame | ~300 KB + Three.js | 6-12s | ~100 MB | No |

Claude artifacts have constraints: single HTML file, no persistent CDN caching,
limited memory, must load fast. SceneView's approach is the only viable path.

## Architecture: Three-Phase Strategy

### Phase 1: Pure WebGL PBR Renderer (Current - Live)

**Zero-dependency, single-file WebGL2 renderer with real PBR shading.**

Files:
- `website-static/filament-demo.html` — Interactive 3D shape viewer
- `website-static/filament-chart.html` — 3D data visualization charts

Features:
- GGX/Trowbridge-Reitz physically-based shading (same BRDF as Filament)
- Schlick Fresnel approximation
- Smith GGX geometry function
- ACES tone mapping
- Hemisphere ambient lighting
- Orbit camera with mouse/touch/scroll controls
- Procedural geometry (sphere, cube, torus, cylinder)
- Animated bar charts with elastic easing
- Multiple datasets, URL parameter customization
- Grid floor, axis lines
- ~15 KB total (HTML + JS + CSS, no external dependencies)

**Why PBR matters**: The shader math is identical to what Filament uses internally
(Cook-Torrance microfacet model). This means scenes rendered with our lightweight
WebGL renderer look visually consistent with native SceneView Android/iOS apps.

### Phase 2: Filament.js WASM Integration (Planned)

**Full Filament engine compiled to WebAssembly for advanced features.**

Filament.js is Google's Filament renderer compiled to WASM. It provides:
- glTF/GLB model loading
- Image-based lighting (IBL) from KTX
- Full PBR material system with clearcoat, anisotropy, sheen
- Shadow mapping
- Bloom, SSAO post-processing

**CDN availability status** (as of March 2026):

The `filament` npm package exists but has practical limitations for artifacts:

1. **WASM binary**: ~2-4 MB compressed, must be loaded before any rendering
2. **No single-file UMD bundle**: requires separate `.wasm` file
3. **CDN URLs**: Available via jsdelivr/unpkg from npm, but the WASM binary
   must be fetched separately — no single `<script>` tag solution
4. **Init is async**: `Filament.init(['assets...'], callback)` pattern

```
// How Filament.js loads (NOT suitable for single-file artifacts)
<script src="https://cdn.jsdelivr.net/npm/filament/filament.js"></script>
<script>
  Filament.init([], () => {
    // WASM loaded, now Filament API available
    const engine = Filament.Engine.create(canvas);
    // ...
  });
</script>
```

**Path to artifact compatibility**:
1. Host Filament WASM on SceneView CDN (`cdn.sceneview.dev/filament/`)
2. Create a thin wrapper that inlines the WASM as base64 (for small scenes)
3. Or use the Phase 1 WebGL renderer for artifacts and Filament.js for the full
   website/playground experience

### Phase 3: sceneview-web npm Package (Future)

**Full SceneView Web SDK with Kotlin/JS bindings to Filament.js.**

The `sceneview-web` module already has Kotlin/JS bindings for Filament.js:
- `SceneView.create(canvas) { camera { ... }; model("file.glb") }`
- Orbit camera controller
- glTF/GLB model loading
- WebXR AR/VR support

Publishing path:
1. `@sceneview/renderer-webgl` — Phase 1 pure WebGL (artifact-ready)
2. `@sceneview/renderer-filament` — Phase 2 Filament.js WASM
3. `sceneview-web` — Full SDK (Kotlin/JS compiled, published on npm)

## MCP Tool: `create_3d_artifact`

The SceneView MCP server can generate 3D artifacts for AI assistants.

### How It Works

When a user asks Claude to "show me a 3D chart" or "render a 3D product view",
the MCP tool generates a self-contained HTML file using the Phase 1 WebGL renderer.

```
User: "Show me quarterly revenue as a 3D chart"

MCP tool generates:
- Single HTML file with embedded WebGL shaders
- PBR-lit 3D bar chart
- Animated bars with elastic easing
- Orbit camera controls
- Data embedded or via URL params
```

### Artifact Types

| Type | Description | Use Case |
|------|-------------|----------|
| `3d-chart` | Animated bar/pie/scatter chart | Data visualization |
| `3d-model-viewer` | Interactive model viewer | Product showcase |
| `3d-scene` | Custom scene with multiple objects | Architecture, games |
| `3d-product` | Product configurator | E-commerce |
| `3d-dashboard` | Multi-chart 3D dashboard | Analytics |

### Data Input Formats

**Inline JSON** (embedded in HTML):
```json
{
  "title": "Revenue by Quarter",
  "labels": ["Q1", "Q2", "Q3", "Q4"],
  "series": [
    { "name": "2025", "color": [0.3, 0.5, 0.9], "values": [18, 24, 22, 31] },
    { "name": "2026", "color": [0.2, 0.8, 0.5], "values": [28, 35, 30, 42] }
  ]
}
```

**URL Parameters** (for dynamic updates):
```
filament-chart.html?dataset=revenue&data={"title":"My Chart","labels":["A","B"],...}
```

## Why This Is SceneView's Killer Feature

1. **Only viable 3D engine for AI artifacts**: Nothing else is light enough
2. **Same rendering math as native**: PBR shaders match Filament on Android/iOS
3. **AI-first design**: Generated by AI, for AI workflows
4. **Progressive enhancement**: Start with WebGL, upgrade to Filament.js for full features
5. **Zero dependencies**: No CDN, no build step, no framework
6. **Cross-platform consistency**: Same visual output as SceneView Android/iOS apps

## Competitive Moat

No competing library can match this combination:
- **model-viewer**: Requires Three.js (~600KB), too heavy for artifacts
- **Three.js**: Full scene graph overhead, not designed for single-file use
- **Babylon.js**: Even heavier, enterprise-focused
- **Raw WebGL**: No PBR, poor developer experience

SceneView is uniquely positioned because:
1. We already have Filament expertise (Android SDK uses Filament natively)
2. We have the PBR shader math (Cook-Torrance, same as Filament)
3. We have the AI-first philosophy (MCP server, llms.txt, artifact tools)
4. We can progressively upgrade from WebGL to Filament.js WASM

## Roadmap

| Milestone | Target | Status |
|-----------|--------|--------|
| Phase 1: WebGL PBR demos | March 2026 | Done |
| Phase 1: MCP `create_3d_artifact` tool | April 2026 | Planned |
| Phase 1: npm `@sceneview/renderer-webgl` | April 2026 | Planned |
| Phase 2: Filament.js CDN hosting | May 2026 | Planned |
| Phase 2: Filament.js artifact wrapper | June 2026 | Planned |
| Phase 3: sceneview-web npm publish | Q3 2026 | Planned |
| Phase 3: Claude artifact marketplace | Q3 2026 | Planned |

## File Reference

- `/website-static/filament-demo.html` — Phase 1 interactive 3D demo
- `/website-static/filament-chart.html` — Phase 1 3D chart renderer
- `/sceneview-web/` — Kotlin/JS Filament.js bindings (Phase 3 foundation)
- `/mcp/` — MCP server (will host `create_3d_artifact` tool)
- `/docs/docs/filament-artifacts.md` — This document
