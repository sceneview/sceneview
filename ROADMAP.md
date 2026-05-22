# Roadmap

> Ship fast, ship often. Every feature = a release.
>
> **Maintenance note:** the "Current" version below must be refreshed at every
> release. The source of truth is `gradle.properties` → `VERSION_NAME` — read
> that, not this file, if they ever disagree.

## Current: v4.15.1 stable (May 2026)

**AI-first SDK** — 9 platforms, MCP server on npm, Claude Code plugin marketplace, Rerun.io debug integration.

| What | Status |
|---|---|
| Android SDK (Filament + Compose) | **Stable** |
| iOS / macOS / visionOS (RealityKit + SwiftUI) | Alpha |
| Web (Filament.js + WebXR) | Alpha |
| Desktop (Compose Desktop) | Placeholder (no Filament JNI) |
| Android TV | Alpha |
| Flutter bridge | Alpha |
| React Native bridge | Alpha |
| MCP on npm | **Live** ([`sceneview-mcp`](https://www.npmjs.com/package/sceneview-mcp) — independent version track) |
| Claude Code plugin marketplace | **Live** ([`sceneview/claude-marketplace`](https://github.com/sceneview/claude-marketplace)) |
| Telemetry Worker | **Live** |
| Rerun.io debug integration | **Shipped** (Android + iOS + Python) |
| Play Store demo app | Deployed |
| App Store demo app | **Live** (`id6761329763`) |
| GitHub Release | **v4.15.1 stable** |

### Completed since v4.0

- [x] Unify naming: `SceneView {}` / `ARSceneView {}` (v3.6)
- [x] Separate modules intentionally: `sceneview` (3D-only) + `arsceneview` (opt-in AR)
- [x] Render tests CI (SwiftShader)
- [x] Anonymous telemetry (Cloudflare Worker + D1)
- [x] Claude playground on website
- [x] Claude Code plugin marketplace (Apache-2.0, single `sceneview` plugin)
- [x] iOS V1 parity sprint — LightSlot, RenderQuality, NodeGesture, AR anchors (v4.1–v4.3)
- [x] iOS App Store release — demo app live (`id6761329763`)
- [x] visionOS spatial features — immersive-space skybox, `SceneViewSwift` visionOS target builds (v4.5, v4.10)
- [x] ARCore Recording / Playback — `ARRecorder` + `ARSceneView(playbackDataset = …)` (v4.3)
- [x] Auto-fit camera framing from glTF bounds — Android + iOS (v4.10, #1439 / #1391)
- [x] Sketchfab model streaming
- [x] Cross-platform demo unification — one showcase app per platform
- [x] Fragment-based changelog system (`changelog.d/`)
- [x] Autonomous cross-platform device-QA harness (Maestro Android/iOS + Playwright web + AR replay)

---

## Next

### Platform maturity

- [ ] Filament JNI for Desktop (hardware 3D, replace placeholder)
- [ ] Android XR module (Jetpack XR SceneCore)
- [ ] sceneview-core WASM target (when kotlin-math supports wasmJs)
- [ ] Promote iOS / Web / Flutter / React Native from Alpha toward Stable

### Quality

- [ ] Visual regression testing across platforms
- [ ] Performance benchmarks (FPS, memory, load time)
- [ ] Raise unit-test coverage on `sceneview` / `arsceneview`

### Growth

- [ ] First external paying customer
- [ ] Rerun.io official partnership / listing
- [ ] Community announcements (Reddit, HN, LinkedIn)

---

## Future

### v5.0+

- Compose Multiplatform renderer (shared Compose UI across Android + Desktop + Web)
- Filament 2.x migration (when available)
- Scene graph serialization (save/load scenes as files)
- `ViewNode<Content>` generic API

### Exploration

- Android Auto / AAOS (when custom 3D views are supported)
- Wear OS (Canvas-based 3D for watch faces)
- Cloud rendering API (server-side Filament)
- Real-time multiplayer scenes (WebSocket sync)
