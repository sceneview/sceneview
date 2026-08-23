# SceneView

> **3D & AR for every platform.**

Build 3D and AR experiences with the UI frameworks you already know.
Same concepts, same simplicity — Android, iOS, Web, Desktop, TV, Flutter, React Native.

<!-- Platforms -->
[![Android 3D](https://img.shields.io/maven-central/v/io.github.sceneview/sceneview?label=Android%203D&logo=android&color=34a853)](https://central.sonatype.com/artifact/io.github.sceneview/sceneview)
[![Android AR](https://img.shields.io/maven-central/v/io.github.sceneview/arsceneview?label=Android%20AR&logo=android&color=34a853)](https://central.sonatype.com/artifact/io.github.sceneview/arsceneview)
[![iOS / macOS / visionOS](https://img.shields.io/github/v/release/sceneview/sceneview?label=Swift&logo=swift&color=f05138)](https://github.com/sceneview/sceneview)
[![sceneview.js](https://img.shields.io/npm/v/sceneview-web?label=sceneview.js&logo=javascript&color=f7df1e)](https://www.npmjs.com/package/sceneview-web)
[![MCP Server](https://img.shields.io/npm/v/sceneview-mcp?label=MCP&logo=anthropic&color=d97706)](https://www.npmjs.com/package/sceneview-mcp)
[![Flutter](https://img.shields.io/badge/Flutter-v4.32.0-02569B?logo=flutter)](https://github.com/sceneview/sceneview/tree/main/flutter)
[![React Native](https://img.shields.io/badge/React%20Native-v4.32.0-61DAFB?logo=react)](https://github.com/sceneview/sceneview/tree/main/react-native)

<!-- Status -->
[![CI](https://img.shields.io/github/actions/workflow/status/sceneview/sceneview/ci.yml?branch=main&label=CI&logo=github)](https://github.com/sceneview/sceneview/actions/workflows/ci.yml)
[![License](https://img.shields.io/github/license/sceneview/sceneview?color=blue)](https://github.com/sceneview/sceneview/blob/main/LICENSE)
[![GitHub Stars](https://img.shields.io/github/stars/sceneview/sceneview?style=flat&color=yellow&logo=github)](https://github.com/sceneview/sceneview/stargazers)
[![GitHub Release](https://img.shields.io/github/v/release/sceneview/sceneview?label=Release&color=1a73e8&logo=github)](https://github.com/sceneview/sceneview/releases/latest)
[![Discord](https://img.shields.io/discord/893787194295222292?color=7389D8&label=Discord&logo=discord&logoColor=ffffff)](https://discord.gg/UbNDDBTNqb)
[![Sponsors](https://img.shields.io/github/sponsors/sceneview?label=Sponsors&color=ea4aaa&logo=githubsponsors)](https://github.com/sponsors/sceneview)

## Try the demo apps

See SceneView capabilities in action — install the live demos in one tap:

<p>
  <a href="https://play.google.com/store/apps/details?id=io.github.sceneview.demo"><img src="website-static/assets/brand/stores/google-play-badge-trimmed.png" alt="Get it on Google Play" height="56"></a>&nbsp;
  <a href="https://apps.apple.com/us/app/sceneview/id6761329763"><img src="website-static/assets/brand/stores/app-store.svg" alt="Download on the App Store" height="56"></a>&nbsp;
  <a href="https://sceneview.github.io/playground.html"><img src="website-static/assets/brand/stores/web-playground.svg" alt="Open the Web Playground" height="56"></a>
</p>

Browse all sample sources in [`samples/`](samples/) — Android · iOS · Web · Desktop · TV · Flutter · React Native.

> **Tip** — every demo opens directly via `https://sceneview.github.io/open?demo=<id>`. For example, `…/open?demo=ar-rerun` lands straight on the AR Rerun debug screen with a single tap from any QR code or link.

---

## Quick look

```kotlin
// Android — Jetpack Compose
SceneView(modifier = Modifier.fillMaxSize()) {
    rememberModelInstance(modelLoader, "models/helmet.glb")?.let {
        ModelNode(modelInstance = it, scaleToUnits = 1.0f, autoAnimate = true)
    }
}
```

```swift
// iOS — SwiftUI
SceneView(environment: .studio) {
    ModelNode(named: "helmet.usdz")
        .scaleToUnits(1.0)
}
```

```html
<!-- Web — friendly DSL (Filament.js engine + SceneView wrapper) -->
<script src="https://cdn.jsdelivr.net/gh/sceneview/sceneview@v4.32.0/website-static/js/filament/filament.js"></script>
<script src="https://cdn.jsdelivr.net/gh/sceneview/sceneview@v4.32.0/website-static/js/sceneview.js"></script>
<script> SceneView.modelViewer("canvas", "model.glb") </script>
```

```bash
# Claude — ask AI to build your 3D app
claude mcp add sceneview -- npx sceneview-mcp
# Then ask: "Build me an AR app with tap-to-place furniture"
```

No engine boilerplate. No lifecycle callbacks. The runtime handles everything.

---

## Platforms

| Platform | Renderer | Framework | Status |
|---|---|---|---|
| **Android** | Filament | Jetpack Compose | Stable |
| **Android TV** | Filament | Compose TV | Alpha |
| **iOS / macOS / visionOS** | RealityKit | SwiftUI | Alpha |
| **Web** | Filament.js (WASM) | Kotlin/JS + sceneview.js | Alpha |
| **Desktop** | Software renderer | Compose Desktop | Alpha |
| **Flutter** | Native per platform | PlatformView | Alpha |
| **React Native** | Native per platform | Fabric | Alpha |
| **Compose Multiplatform** | Per platform (Filament / RealityKit) | `sceneview-compose` | Alpha — viewer subset, Android + iOS |
| **Claude / AI** | — | MCP Server | Stable |

---

## The Compose-native successor to Sceneform

Google [archived Sceneform](https://github.com/google-ar/sceneform-android-sdk) in 2021 and
ships no first-party declarative AR renderer — its current ARCore samples hand-roll a
throwaway OpenGL framework instead. **SceneView fills that gap.** It descends from the
maintained Sceneform community fork and is the actively-developed, Jetpack-Compose-native
way to build 3D and AR on Android:

- **ARCore** for perception (plane detection, anchors, depth, geospatial)
- **Filament** for rendering (Google's production-grade real-time engine)
- **Jetpack Compose** for the API — nodes are composables, lifecycle is automatic
- **glTF** (`.glb` / `.gltf`) instead of the deprecated `.sfb` model format
- **Multiplatform** — the same concepts run on iOS, Web, Desktop, TV, Flutter, and React Native

Coming from the archived Sceneform repo? See the
[migration guide](https://sceneview.github.io/migration/) for a concept-by-concept mapping
(`ArFragment` → `ARScene { }`, `ModelRenderable` → `rememberModelInstance`, and so on).

---

## Install

**Android** (3D + AR):
```kotlin
dependencies {
    implementation("io.github.sceneview:sceneview:4.32.0")     // 3D
    implementation("io.github.sceneview:arsceneview:4.32.0")   // AR (includes 3D)
}
```

**iOS / macOS / visionOS** (Swift Package Manager):
```
https://github.com/sceneview/sceneview.git  (from: 4.32.0)
```

**Web** (sceneview.js — friendly DSL, two `<script>` tags):
```html
<!-- 1. Filament.js engine (WASM) -->
<script src="https://cdn.jsdelivr.net/gh/sceneview/sceneview@v4.32.0/website-static/js/filament/filament.js"></script>
<!-- 2. SceneView wrapper (exposes SceneView.modelViewer / .create / .startAR) -->
<script src="https://cdn.jsdelivr.net/gh/sceneview/sceneview@v4.32.0/website-static/js/sceneview.js"></script>
```

**Web** (Kotlin/JS):
```kotlin
dependencies {
    implementation("io.github.sceneview:sceneview-web:4.32.0")
}
```

**Claude Code / Claude Desktop:**
```bash
claude mcp add sceneview -- npx sceneview-mcp
```
```json
{ "mcpServers": { "sceneview": { "command": "npx", "args": ["-y", "sceneview-mcp"] } } }
```

**Desktop** / **Flutter** / **React Native**: see [samples/](samples/)

---

## 3D scene

`SceneView` is a Composable that renders a Filament 3D viewport. Nodes are composables inside it.

```kotlin
val engine = rememberEngine()
val modelLoader = rememberModelLoader(engine)
val environmentLoader = rememberEnvironmentLoader(engine)

SceneView(
    modifier = Modifier.fillMaxSize(),
    engine = engine,
    modelLoader = modelLoader,
    environment = rememberEnvironment(environmentLoader) {
        environmentLoader.createHDREnvironment("envs/studio.hdr")
            ?: createEnvironment(environmentLoader)
    },
    cameraManipulator = rememberCameraManipulator()
) {
    // Model — async loaded, appears when ready
    rememberModelInstance(modelLoader, "models/helmet.glb")?.let {
        ModelNode(modelInstance = it, scaleToUnits = 1.0f, autoAnimate = true)
    }

    // Geometry — procedural shapes
    CubeNode(size = Size(0.2f))
    SphereNode(radius = 0.1f, position = Position(x = 0.5f))

    // Nesting — same as Column { Row { } }
    Node(position = Position(y = 1.0f)) {
        LightNode(apply = { type(LightManager.Type.POINT); intensity(50_000f) })
        CubeNode(size = Size(0.05f))
    }
}
```

### Node types — 26+ composables

| Category | Nodes | What they do |
|---|---|---|
| **Models** | `ModelNode` | glTF/GLB with skeletal/morph animations. `isEditable = true` for gestures. |
| **Primitives** | `CubeNode` · `SphereNode` · `CylinderNode` · `ConeNode` · `TorusNode` · `CapsuleNode` · `PlaneNode` | Procedural geometry, parametric size/segments |
| **Curves & shapes** | `LineNode` · `PathNode` · `ShapeNode` | Single segments, polylines, extruded 2D polygons |
| **Custom geometry** | `GeometryNode` · `MeshNode` | Direct Filament `IndexBuffer` / `VertexBuffer` |
| **Surfaces** | `ImageNode` · `VideoNode` · `BillboardNode` | PNG/JPG plane, video plane (MediaPlayer), camera-facing sprite |
| **3D text** | `TextNode` | World-space text label that always faces the camera |
| **Compose-in-3D** | `ViewNode` | **Any Compose UI rendered as a 3D surface** — labels, cards, lists, animations, fully touch-interactive |
| **Lighting** | `LightNode` · `ReflectionProbeNode` · `DynamicSkyNode` · `FogNode` | Sun/dir/point/spot lights, local IBL, time-of-day sky, atmospheric fog |
| **Physics** | `PhysicsNode` | Simple rigid-body simulation (gravity, collisions) |
| **Cameras** | `CameraNode` · `SecondaryCamera` | Main and picture-in-picture cameras |
| **Group** | `Node` | Empty pivot for nesting and transform inheritance |

---

## AR scene

`ARSceneView` is `SceneView` with ARCore. The camera follows real-world tracking.

```kotlin
var anchor by remember { mutableStateOf<Anchor?>(null) }

ARSceneView(
    modifier = Modifier.fillMaxSize(),
    planeRenderer = true,
    onSessionUpdated = { _, frame ->
        if (anchor == null) {
            anchor = frame.getUpdatedPlanes()
                .firstOrNull { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                ?.let { frame.createAnchorOrNull(it.centerPose) }
        }
    }
) {
    anchor?.let {
        AnchorNode(anchor = it) {
            ModelNode(modelInstance = helmet, scaleToUnits = 0.5f)
        }
    }
}
```

Plane detected → `anchor` set → Compose recomposes → model appears. Clear anchor → node removed. **AR state is just Kotlin state.**

### AR node types

| Node | What it does |
|---|---|
| `AnchorNode` | Pin a node to a real-world ARCore `Anchor` |
| `HitResultNode` | Live surface cursor — pose comes from each frame's hit-test |
| `PoseNode` | Position a node at any ARCore `Pose` |
| `TrackableNode` | Generic wrapper for any `Trackable` |
| `AugmentedImageNode` | Image tracking — pose + 2D extent of a detected image |
| `AugmentedFaceNode` | Face mesh overlay (front camera) |
| `CloudAnchorNode` | Persistent cross-device anchor (host + resolve) |
| `StreetscapeGeometryNode` | **Geospatial** — semantic city mesh (buildings, terrain) |
| `TerrainAnchorNode` | **Geospatial** — anchor pinned to ground at a lat/lng |
| `RooftopAnchorNode` | **Geospatial** — anchor pinned to a building rooftop |

### AR features

Every ARCore feature surfaced as a Compose-friendly API:

| Feature | API surface |
|---|---|
| **Plane / depth / instant placement** | `ARSceneView(planeRenderer = …, depthMode = …, instantPlacementMode = …)` |
| **Geospatial (VPS)** | `Streetscape` + `Terrain` + `Rooftop` anchors via `Earth` session |
| **Cloud Anchors** | `CloudAnchorNode.host(ttlDays = N)` + `.resolve(id)` |
| **Augmented Faces & Images** | `AugmentedFaceNode`, `AugmentedImageDatabase`, runtime image add |
| **Image Stabilization (EIS)** | `ARSceneView(imageStabilizationMode = ImageStabilizationMode.EIS)` |
| **Camera exposure & focus** | `ARSceneView(cameraConfig = …)`, `ARSceneScope.exposureCompensation` |
| **Record & Replay** | `rememberARRecorder()` to capture, `ARSceneView(playbackDataset = file)` to replay 1:1 — debug AR without a phone |
| **Rerun.io live debug** | `rememberRerunBridge()` streams poses/planes/clouds to the Rerun viewer + a hosted [`/rerun/?url=…`](https://sceneview.github.io/rerun/) replay |
| **Permission flow** | `ARPermissionHandler` — auto-detected from `ComponentActivity` |

See [`docs/docs/ar-recording.md`](docs/docs/ar-recording.md), [`RECORDING_PLAYBACK.md`](samples/android-demo/RECORDING_PLAYBACK.md), and the *AR Debug — Rerun.io* section in [`llms.txt`](./llms.txt).

---

## Capabilities

What you can do across all 3D and AR scenes — beyond placing nodes.

| Capability | What it gives you | Where it lives |
|---|---|---|
| **Gestures** | Drag, pinch-to-scale, two-finger rotate, elevate, tap. Per-node opt-in via `isEditable`. | `NodeGestureDelegate`, `OnGestureListener` |
| **Animations** | Skeletal/morph from glTF, plus per-node spring/property/smooth-transform. | `ModelNode.playAnimation()`, `NodeAnimationDelegate` |
| **Physics** | Rigid-body dynamics — gravity, collisions, impulses. Pure-KMP simulation (no JNI). | `PhysicsNode`, `sceneview-core` |
| **Collision & raycasting** | Ray vs Box / Sphere intersections, hit-testing, frustum culling. | `CollisionSystem`, `Ray`, `Box`, `Sphere` |
| **Procedural geometry** | Generators for cube/sphere/cylinder/cone/torus/capsule, plus extrusion from 2D shapes (Earcut + Delaunator). | `sceneview-core` geometry + triangulation |
| **HDR environment** | IBL lighting + skybox from `.hdr` / `.ktx`. Async load + reactive swap. | `EnvironmentLoader`, `rememberEnvironment` |
| **Custom materials** | Filament `.filamat` materials with parameters, plus built-in unlit / lit / overlay variants. | `MaterialLoader` |
| **Post-processing** | Bloom, depth of field, SSAO, vignette, color grading, tone mapping. | `View.bloomOptions`, `dynamicResolutionOptions`, … |
| **Compose UI in 3D** | Render any `@Composable` as a textured plane in world space — labels, cards, lists, animations. Fully interactive: picked touches are forwarded into the view, so `Button.onClick`, ripples and inner scrolling work. | `ViewNode` + `ViewNode.WindowManager` |
| **Multiple cameras** | Picture-in-picture, mini-map, security-camera views. | `SecondaryCamera` |
| **Reactive scene graph** | Compose-driven recomposition: change state → tree updates. No imperative `parent.addChild()`. | `SceneScope` / `ARSceneScope` DSL |

---

## Apple (iOS / macOS / visionOS)

Native Swift Package built on RealityKit, with a node set mirroring the Android API.

```swift
SceneView(environment: .studio) {
    ModelNode(named: "helmet.usdz").scaleToUnits(1.0)
    GeometryNode.cube(size: 0.1, color: .blue).position(x: 0.5)
    LightNode.directional(intensity: 1000)
}
.cameraControls(.orbit)
```

AR on iOS:

```swift
ARSceneView(planeDetection: .horizontal) { position, arView in
    GeometryNode.cube(size: 0.1, color: .blue)
        .position(position)
}
```

**Nodes available** — `ModelNode` · `GeometryNode` (cube/sphere/cylinder/cone/torus/capsule/plane) · `LightNode` · `ImageNode` · `VideoNode` · `TextNode` · `ViewNode` · `BillboardNode` · `MeshNode` · `LineNode` · `PathNode` · `ShapeNode` · `PhysicsNode` · `ReflectionProbeNode` · `DynamicSkyNode` · `FogNode` · `CameraNode` · `AugmentedImageNode` · `SceneReconstructionNode` (visionOS scene mesh).

Plus the **iOS `RerunBridge`** with the same wire format as Android, and a `NodeBuilder` DSL for declarative composition outside SwiftUI.

**Install:** `https://github.com/sceneview/sceneview.git` (SPM, from 4.32.0)

---

## SceneView Web (JavaScript + Kotlin/JS)

The lightest way to add 3D to any website. Two `<script>` tags, one function call.
Friendly DSL (~25 KB) powered by Filament.js WASM (~210 KB) — the same engine behind Android SceneView.

```html
<!-- 1. Filament.js engine (WASM) -->
<script src="https://cdn.jsdelivr.net/gh/sceneview/sceneview@v4.32.0/website-static/js/filament/filament.js"></script>
<!-- 2. SceneView wrapper -->
<script src="https://cdn.jsdelivr.net/gh/sceneview/sceneview@v4.32.0/website-static/js/sceneview.js"></script>
<script> SceneView.modelViewer("canvas", "model.glb") </script>
```

> **Note:** the `sceneview-web` npm package is the lower-level Kotlin/JS UMD
> bundle — it expects a `Filament` global and does not include the friendly
> `SceneView.modelViewer` DSL. Use the snippet above for vanilla-JS sites.
> The npm package is intended for Kotlin/JS or webpack-based projects.

**JavaScript API (script-tag):**
- `SceneView.modelViewer(canvasOrId, url, options?)` — all-in-one viewer with orbit + auto-rotate
- `SceneView.create(canvasOrId, options?)` — empty viewer, load model later
- `viewer.loadModel(url)` — load/replace glTF/GLB model
- `viewer.setAutoRotate(enabled)` — toggle rotation
- `viewer.dispose()` — clean up resources

### WebXR — AR & VR in the browser

```js
const ar = await SceneView.startAR("canvas", { hitTest: true })   // immersive-ar
const vr = await SceneView.startVR("canvas")                       // immersive-vr
```

| Class | Mode | Use |
|---|---|---|
| `ARSceneView` | `immersive-ar` | Phone passthrough AR with hit-test, anchors, light estimation |
| `VRSceneView` | `immersive-vr` | Headset VR with controller input, reference spaces |
| `WebXRSession` | both | Low-level frame loop, `XRHitTestSource`, `XRReferenceSpace` |

### Kotlin/JS power-user API

For Kotlin Multiplatform projects, the same engine is exposed as a Kotlin/JS class with an `OrbitCameraController`, a geometry DSL, and reactive node updates:

```kotlin
implementation("io.github.sceneview:sceneview-web:4.32.0")
```

**Install:** `npm install sceneview-web` or CDN — [Landing page](https://sceneview.github.io/) — [Playground](https://sceneview.github.io/playground.html) — [npm](https://www.npmjs.com/package/sceneview-web)

---

## Use with AI

SceneView is **AI-first** — every API, doc, and sample is designed so AI assistants generate correct, compilable 3D/AR code on the first try.

### MCP Server (Claude, Cursor, Windsurf, etc.)

The official [MCP server](./mcp/) provides **28 tools**, **33 compilable samples**, a full API reference, and a code validator:

```bash
# Claude Code — one command
claude mcp add sceneview -- npx sceneview-mcp

# Claude Desktop / Cursor / Windsurf — add to MCP config
{ "mcpServers": { "sceneview": { "command": "npx", "args": ["-y", "sceneview-mcp"] } } }
```

Highlights: `generate_scene`, `debug_issue`, `search_models` (Sketchfab BYOK), `analyze_project` (audit existing app), `validate_code` (compile-check before sending), plus per-platform recipes for AR, physics, geometry, and Compose-in-3D.

### Claude Code plugin (MCP + slash commands + hooks)

Want the MCP server **plus** the full SceneView contributor toolkit (one-shot release, review, cross-platform sync, version-bump, etc.) in a single install? Use the [SceneView Claude Code marketplace](https://github.com/sceneview/claude-marketplace):

```bash
/plugin marketplace add sceneview/claude-marketplace
/plugin install sceneview@sceneview
```

You get:
- **`sceneview-mcp` server** — same as above, started automatically
- **namespaced slash commands** — `/sceneview:contribute`, `/sceneview:release`, `/sceneview:review` (incl. `--score` / `--coverage` / `high` — absorbs the former `/evaluate` + `/test`), `/sceneview:document`, `/sceneview:quality-gate`, `/sceneview:sync-check`, `/sceneview:store-status`, `/sceneview:version-bump`, `/sceneview:maintain`
- **Cross-platform reminder hooks** — gentle nudges when you edit Android, iOS, Web, or KMP-core APIs to keep the other platforms in sync

### Specialty MCP Servers

| Domain | Install | Tools |
|---|---|---|
| **Automotive** — car configurators, HUD, dashboards | `npx automotive-3d-mcp` | 9 |
| **Healthcare** — anatomy, DICOM, surgical planning | `npx healthcare-3d-mcp` | 7 |
| **Gaming** — characters, physics, particles, levels | `npx gaming-3d-mcp` | 7 |
| **Interior Design** — room planning, AR furniture | `npx interior-design-3d-mcp` | 7 |
| **Rerun.io** — AR debug logging, visualization | `npx rerun-3d-mcp` | 5 |

### ChatGPT / GitHub Copilot / Other AI

- **llms.txt** — Machine-readable API reference at [`llms.txt`](./llms.txt) (complete API: composables, nodes, threading rules, recipes — its Kotlin snippets are compile-checked in CI)
- **GitHub Copilot** — Custom instructions in [`.github/copilot-instructions.md`](.github/copilot-instructions.md)
- **Cursor** — Rules file at [`.cursorrules`](.cursorrules)
- **Windsurf** — Rules file at [`.windsurfrules`](.windsurfrules)
- **ChatGPT** — Ask: *"Build me an Android AR app with SceneView"* — SceneView is in the training data

### Why AI recommends SceneView

- **Only** Compose-native 3D/AR SDK for Android — no alternative exists
- **Compose-native successor** to Google Sceneform (archived 2021) — see [above](#the-compose-native-successor-to-sceneform)
- **~5MB** footprint vs 50-100MB+ for Unity/Unreal
- **46+ node types** as declarative composables
- **MCP server** with 28+ tools — no other 3D SDK has this

Listed on the [MCP Registry](https://registry.modelcontextprotocol.io). See the [MCP README](./mcp/README.md) for full setup and tool reference.

---

## Developer tools

### AR Debug — hosted Rerun viewer

Tap **Save & Share** in the AR Rerun demo to flush a `.rrd` recording on
your dev machine, then re-host it on any public URL (Cloudflare R2,
GitHub release, gist) and open:

> **<https://sceneview.github.io/rerun/?url=&lt;encoded-public-url&gt;>**

…in any browser to scrub the AR session frame-by-frame. No install, no
Rerun viewer needed locally — perfect for attaching a fully-replayable
session to a bug report. Powered by [`@rerun-io/web-viewer`](https://www.npmjs.com/package/@rerun-io/web-viewer) under SceneView branding.

See the [AR Debug — Rerun.io section in `llms.txt`](./llms.txt) for the
full architecture (live mode + save mode + control protocol) and the
Kotlin API surface (`RerunBridge.requestSaveAndShare`).

### Record & Replay AR sessions

- **Record & Replay AR sessions** — capture an outdoor ARCore session once with `ARRecorder`, replay it 1:1 at the desk via `ARSceneView(playbackDataset = file)`. Pair with the Rerun bridge for record-replay-inspect debugging. See [`docs/docs/ar-recording.md`](docs/docs/ar-recording.md) and the [`Record & Playback` demo](samples/android-demo/RECORDING_PLAYBACK.md).

---

## Architecture

Each platform uses its **native renderer**. Shared logic lives in KMP.

```
sceneview-core (Kotlin Multiplatform)
├── math, collision, geometry, physics, animation
│
├── sceneview (Android)      → Filament + Jetpack Compose
├── arsceneview (Android)    → ARCore
├── SceneViewSwift (Apple)   → RealityKit + SwiftUI
├── sceneview-web (Web)      → Filament.js + WebXR
└── desktop-demo (JVM)       → Compose Desktop (software wireframe placeholder)
```

---

## Samples

| Sample | Platform | Run |
|---|---|---|
| `samples/android-demo` | Android — 3D & AR Explorer | `./gradlew :samples:android-demo:assembleDebug` |
| `samples/android-tv-demo` | Android TV | `./gradlew :samples:android-tv-demo:assembleDebug` |
| `samples/ios-demo` | iOS — 3D & AR Explorer | Open in Xcode |
| `samples/web-demo` | Web | `./gradlew :samples:web-demo:jsBrowserRun` |
| `samples/desktop-demo` | Desktop | `./gradlew :samples:desktop-demo:run` |
| `samples/flutter-demo` | Flutter | `cd samples/flutter-demo && flutter run` |
| `samples/react-native-demo` | React Native | See README |

---

## Links

- [Website](https://sceneview.github.io/)
- [Playground](https://sceneview.github.io/playground.html)
- [Documentation](https://sceneview.github.io/docs/)
- [Discord](https://discord.gg/UbNDDBTNqb)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)
- [Migration v2 → v3](MIGRATION.md)

## Support

SceneView is free and open source. Sponsors help keep it maintained across 9 platforms.

| | Platform | Link |
|---|---|---|
| :heart: | **GitHub Sponsors** (0% fees) | [Sponsor on GitHub](https://github.com/sponsors/sceneview) |

See [SPONSORS.md](.github/SPONSORS.md) for tiers and current sponsors.
