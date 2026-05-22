---
title: Migration Guide
description: "Migration guides for SceneView: 3.6.x to 4.0.0 Rerun integration, 3.5.x to 3.6.0 API simplification, and 2.x to 3.x full rewrite."
---

# Migration Guide

---

## SceneView 4.10.x to 4.11.0 (Android) — `CloudAnchorNode.host()` returns `HostCloudAnchorFuture`

### `CloudAnchorNode.host()` now returns the underlying ARCore `HostCloudAnchorFuture` ([#1768](https://github.com/sceneview/sceneview/pull/1768))

Pre-v4.11.0, `CloudAnchorNode.host(session, ttlDays, onCompleted)` returned `Unit`
and stored the in-flight future privately — apps had no way to cancel a pending
host request when the UI scope was leaving. The network round-trip ran to
completion regardless, accruing a Google Cloud billing event whether or not the
caller was still listening.

v4.11.0 surfaces the underlying ARCore [`HostCloudAnchorFuture`](https://developers.google.com/ar/reference/java/com/google/ar/core/HostCloudAnchorFuture)
as the return value so callers can cancel via `future.cancel()` — typically from
`DisposableEffect.onDispose { future?.cancel() }`. The same change applies to
`CloudAnchorNode.resolve(...)`, `TerrainAnchorNode.resolve(...)`, and
`RooftopAnchorNode.resolve(...)`.

**Billing rationale.** Each pending host/resolve future is **one Google Cloud
ARCore API request**. Without explicit cancellation, the request runs to
completion (a few seconds, sometimes longer for cross-continent resolves) and
the billing event lands on your Google Cloud project even after the user has
navigated away from the screen that started it. For apps that surface a "Place
anchor" CTA inside a navigable hierarchy (Compose / Fragment / Activity stack)
this is a measurable cost on cancelled user flows — see
[Google's ARCore Cloud Anchors pricing](https://developers.google.com/ar/develop/cloud-anchors-faq#pricing).
Cancellation does **not** invoke the `onCompleted` callback (matches ARCore
semantics), so observers stay clean.

**Action — source compatibility.** Most call sites are unaffected — the new
return type is purely additive. The only break is for callers that wrote
`val unused: Unit = node.host(...)` or any test that asserts the return type is
`Unit`. Adjust the local binding and (recommended) cancel the future on dispose:

```kotlin
// pre-v4.11.0
DisposableEffect(anchorNode) {
    anchorNode.host(session, ttlDays = 7) { id, state -> /* ... */ }
    onDispose { /* nothing to cancel — the future was internal */ }
}

// v4.11.0 — capture the future, cancel on dispose to free the billing event
DisposableEffect(anchorNode) {
    val future = anchorNode.host(session, ttlDays = 7) { id, state -> /* ... */ }
    onDispose { future.cancel() }
}
```

The same pattern applies to `CloudAnchorNode.resolve(engine, session, id) { ... }`,
`TerrainAnchorNode.resolve(...)`, and `RooftopAnchorNode.resolve(...)`. The
node's own `destroy()` lifecycle still calls `cancelHost()` internally, so an
app that doesn't cancel explicitly still cleans up on scene disposal — but it
pays the round-trip until destroy fires.

---

## SceneView 4.3.x to 4.4.0 (iOS) — true camera motion + skybox renders + mirror retired

### `Environment.showSkybox = true` now actually paints the HDR as background ([PR #1215](https://github.com/sceneview/sceneview/pull/1215))

Pre-v4.4.0 the flag set up IBL lighting from the HDR but never rendered the HDR
as the scene background — the void stayed neutral. v4.4.0 wires the HDR resource
through `RealityViewContent.environment = .skybox(...)`.

**Action**:

- If your app set `showSkybox: false` to *explicitly* hide a background that was
  already invisible, you can leave the code as-is — `false` continues to render
  the neutral default.
- If your app set `showSkybox: true` and was depending on the void-background
  bug (e.g. compositing the scene over a custom SwiftUI background), explicitly
  flip to `showSkybox: false`. The IBL still lights your scene.

```swift
// pre-v4.4.0 (the bug)
.environment(.custom(name: "city", hdrFile: "city.hdr", showSkybox: true))
//                                                       ^^^^ silently ignored

// v4.4.0 (intended behaviour)
.environment(.custom(name: "city", hdrFile: "city.hdr", showSkybox: true))
//                                                       ^^^^ now renders HDR as background
.environment(.custom(name: "city", hdrFile: "city.hdr", showSkybox: false))
//                                                       ^^^^ neutral background, IBL still applies
```

### Orbit + pan modes now physically move the perspective camera ([PR #1215](https://github.com/sceneview/sceneview/pull/1215))

Pre-v4.4.0 `applyCamera()` rotated + scaled the scene root while the perspective
camera stayed pinned at `[0, 0.3, 2]`. v4.4.0 positions the camera in world-space
via `CameraControls.cameraPosition()` so the skybox correctly wraps around the
camera as it orbits.

**Action**:

- Most apps need no code change — the apparent on-screen framing of existing
  scenes is preserved at the new defaults (the SceneView's internal `CameraControls`
  default `orbitRadius` was bumped from `5.0` to `2.0` to match the old camera
  distance, so a 1m model fills the same fraction of viewport).
- If your code reads `entities.root.scale` / `.orientation` via reflection,
  debug-overlay, or a custom modifier, those now stay at identity in orbit / pan
  modes. Migrate to `CameraControls.cameraTransform()` to recover the camera
  matrix.

### `CameraControls` defaults changed (BREAKING for direct constructors)

If your app constructs `CameraControls()` directly (rather than using the
`.cameraControls(_:)` modifier):

- `orbitRadius` default: `5.0` → `2.0` (camera-to-target distance, was unreachable
  through any public modifier before so this aligns with the SceneView's
  internal default)
- `minRadius` default: `0.5` → `1.0` (pinch-in floor — `0.5` clipped into 1m-extent
  models under the new true-camera path; `1.0` matches typical bundled demo content)

**Action**:

```swift
// pre-v4.4.0 implicit defaults
var controls = CameraControls()
// controls.orbitRadius == 5.0, controls.minRadius == 0.5

// v4.4.0 — bump explicitly if you want the pre-v4.4.0 framing for non-bundled content
var controls = CameraControls()
controls.orbitRadius = 5.0
controls.minRadius = 0.5
```

### FOV no longer bleeds from `firstPerson` pinch into `orbit` / `pan`

Pre-v4.4.0, pinching FOV down to e.g. 30° in `.firstPerson` then switching to
`.orbit` kept the 30° pinched FOV on the perspective camera (visible as a stuck
zoom-in). v4.4.0 writes the baseline `60°` FOV in orbit/pan regardless, and
mirrors `camera.fov` only in `.firstPerson`. On `.firstPerson` exit, `camera.fov`
itself is reset to `60` so the next entry starts fresh.

**Action**: no code change required — the previous behaviour was a bug.

### Swift Package Manager URL changed

The `sceneview/sceneview-swift` mirror has been archived read-only. SPM consumers
should re-add the package in Xcode pointing at the monorepo directly:

```diff
- .package(url: "https://github.com/sceneview/sceneview-swift.git", from: "4.0.0")
+ .package(url: "https://github.com/sceneview/sceneview.git", from: "4.4.0")
```

The frozen `v4.0.0` tag on the mirror still resolves for any consumer that hasn't
migrated, but no further releases will be cut there. The root `Package.swift` in
the monorepo (added in [#920](https://github.com/sceneview/sceneview/pull/920))
declares the `SceneViewSwift` product, so the import statement
(`import SceneViewSwift`) is unchanged.

### Attribution

The skybox-renders + true-orbit camera fixes were ported with `Co-authored-by`
credit from [@radcli14](https://github.com/radcli14)'s
[sceneview-swift PR #1](https://github.com/sceneview/sceneview-swift/pull/1).

---

## SceneView 4.2.x to 4.3.0 (iOS) — silent-stub modes now active

### `.cameraControls(.pan)` and `.cameraControls(.firstPerson)` no longer silently orbit ([#1034](https://github.com/sceneview/sceneview/issues/1034))

In v4.2.0 these two modes existed in the enum but `applyCamera()` ignored
them — they produced orbit behaviour at runtime. v4.3.0 wires them to
real per-mode handlers.

**Action**:

- Apps that called `.cameraControls(.pan)` *expecting* orbit behaviour
  should switch to `.cameraControls(.orbit)` (or drop the modifier
  entirely — orbit is the default).
- Apps that called `.cameraControls(.firstPerson)` get FOV-zoom pinch
  instead of dolly. To keep the v4.2.0 orbit-with-dolly behaviour,
  switch to `.orbit`.

```swift
// v4.2.0 (silent stub)
SceneView { /* ... */ }
  .cameraControls(.pan)   // actually orbited

// v4.3.0 (wired)
SceneView { /* ... */ }
  .cameraControls(.pan)   // now translates target; drop modifier for orbit
```

### Library-level auto-center is on by default ([#1026](https://github.com/sceneview/sceneview/issues/1026))

iOS v4.3.0 introduces an intermediate `contentRoot` Entity and translates
it on the first frame the scene's `visualBounds` is non-empty so the
centroid lands at the orbit pivot. Most demos benefit; scenes that rely
on intentional off-centre placement (carousels, dioramas, story-mode)
will see content re-centre.

**Action**: append `.autoCenterContent(false)` for off-centre-by-design
scenes.

```swift
// v4.2.0 — manual per-demo centring
SceneView { root in
    let model = ModelNode.load("hero.usdz")
    model.entity.position = .init(x: 0, y: -0.5, z: -2)  // intentional offset
    root.addChild(model.entity)
}

// v4.3.0 — opt out of library centring to keep the offset
SceneView { root in
    let model = ModelNode.load("hero.usdz")
    model.entity.position = .init(x: 0, y: -0.5, z: -2)
    root.addChild(model.entity)
}
.autoCenterContent(false)   // ← restore strict v4.2.0 placement
```

---

## SceneView 3.6.x to 4.0.0 (Release Candidate)

**Status:** `v4.0.0` is live as a release candidate. Maven Central and Swift Package Manager artifacts are **not** built from the RC tag — pin to the tag manually to test, or wait for the `v4.0.0` stable tag.

4.0.0 is a **strictly additive** release for the SDK side. Existing 3.6.x code compiles and runs unchanged. The version bump reflects two new capabilities, not breaking changes.

### 1. New: AR Debug — Rerun.io integration

SceneView can now stream an ARCore / ARKit session to the [Rerun](https://rerun.io) viewer for scrub-and-replay debugging. See the full guide in the [llms.txt reference](https://github.com/sceneview/sceneview/blob/main/llms.txt) or the [AR Debug (Rerun)](https://sceneview.github.io/playground.html) example in the playground.

Quick wire-up on Android:

```kotlin
import io.github.sceneview.ar.rerun.rememberRerunBridge

@Composable
fun MyARScreen() {
    val bridge = rememberRerunBridge(enabled = BuildConfig.DEBUG)
    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        onSessionUpdated = { session, frame ->
            bridge.logFrame(session, frame)
        }
    )
}
```

And on iOS:

```swift
import SceneViewSwift

struct MyARView: View {
    @StateObject private var bridge = RerunBridge(host: "192.168.1.42")

    var body: some View {
        ARSceneView()
            .onFrame { frame, _ in bridge.logFrame(frame) }
            .onAppear { bridge.connect() }
            .onDisappear { bridge.disconnect() }
    }
}
```

No breaking changes — this is a new `io.github.sceneview.ar.rerun` package on Android and a new `SceneViewSwift.RerunBridge` type on iOS. Nothing existing was touched.

### 2. New: `ARSceneView.onFrame` hook on iOS

`SceneViewSwift.ARSceneView` gained a new `onFrame(_ handler:)` modifier mirroring Android's `onSessionUpdated` callback. Fires from the existing `ARSessionDelegate.session(_:didUpdate:)` without adding a second delegate. Used by the Rerun bridge, but useful for any per-frame custom logic (pose streaming, custom tracking analytics, etc.).

```swift
ARSceneView()
    .onFrame { frame, arView in
        // runs on the ARKit delegate queue — do NOT block
    }
```

### 3. Version bump — just the number

Update your Gradle / SPM / pubspec / package.json references:

```kotlin
// Before
implementation("io.github.sceneview:sceneview:4.15.1")
implementation("io.github.sceneview:arsceneview:4.15.1")

// After (4.0.0)
implementation("io.github.sceneview:sceneview:4.15.1")
implementation("io.github.sceneview:arsceneview:4.15.1")
```

**Release candidate caveat:** Maven Central does **not** currently ship `4.0.0`. Either build from source (`./gradlew :sceneview:publishToMavenLocal`) or wait for `v4.0.0` stable.

### 4. `sceneview-mcp` gained a `@next` dist-tag

If you use the [`sceneview-mcp`](https://www.npmjs.com/package/sceneview-mcp) npm package in Claude Desktop / Cursor / etc., the `@latest` tag is still on `3.6.4` (unchanged, intentionally). The `@next` tag is on `4.0.0`, which includes the Rerun integration docs and v4 lite-proxy routing to the hosted gateway. Opt in with:

```json
"sceneview": {
  "command": "npx",
  "args": ["-y", "sceneview-mcp@next"]
}
```

There is also a **new** [`rerun-3d-mcp`](https://www.npmjs.com/package/rerun-3d-mcp) package (`@latest = 1.0.0`) that generates the Rerun integration boilerplate on demand:

```bash
npx rerun-3d-mcp
```

---

## SceneView 3.5.x to 3.6.0

SceneView 3.6.0 simplifies the API surface so that AI assistants and developers can write correct
code on the first try. All changes use `@Deprecated(replaceWith = ...)` — the Kotlin compiler will
auto-suggest fixes via IDE quick actions.

### 1. `Scene` composable renamed to `SceneView`, `ARScene` to `ARSceneView`

The `Scene { }` composable is renamed to `SceneView { }` and `ARScene { }` is renamed to
`ARSceneView { }`. This aligns Android naming with Apple (SwiftUI already uses `SceneView` and
`ARSceneView`) and avoids confusion with Filament's internal `Scene` class.

```kotlin
// Before
Scene(modifier = Modifier.fillMaxSize()) { /* nodes */ }
ARScene(modifier = Modifier.fillMaxSize()) { /* AR nodes */ }

// After (3.6.0)
SceneView(modifier = Modifier.fillMaxSize()) { /* nodes */ }
ARSceneView(modifier = Modifier.fillMaxSize()) { /* AR nodes */ }
```

`SceneScope` and `ARSceneScope` are **unchanged** — only the top-level composable names changed.

### 2. `CameraNode` composable renamed to `SecondaryCamera`

The `CameraNode { }` composable inside `SceneView { }` creates a non-active camera — it does NOT
become the scene's rendering camera. The name was misleading, so it's renamed to `SecondaryCamera`.

```kotlin
// Before
SceneView(cameraNode = rememberCameraNode(engine)) {
    CameraNode { /* secondary camera — name was confusing */ }
}

// After (3.6.0)
SceneView(cameraNode = rememberCameraNode(engine)) {
    SecondaryCamera { /* clearly named as non-primary */ }
}
```

The scene's active camera is still set via `SceneView(cameraNode = rememberCameraNode(engine))` —
this has NOT changed.

### 3. All geometry nodes now have uniform transform params

Every geometry composable (`CubeNode`, `SphereNode`, `CylinderNode`, `PlaneNode`, `LineNode`,
`PathNode`) now accepts the same `position`, `rotation`, `scale` trio. Previously, some were missing.

```kotlin
// Before — SphereNode had no rotation or scale
SphereNode(radius = 0.2f, materialInstance = mat)

// After (3.6.0) — all geometry nodes have the full trio
SphereNode(
    radius = 0.2f,
    materialInstance = mat,
    position = Position(x = 1f),
    rotation = Rotation(y = 45f),
    scale = Scale(2f)
)
```

### 4. `LightNode` — explicit params instead of dual lambdas

`LightNode` now exposes `intensity`, `direction`, and `position` as direct parameters instead of
requiring two separate `apply` / `nodeApply` lambdas.

```kotlin
// Before
LightNode(
    type = LightManager.Type.DIRECTIONAL,
    apply = { intensity(100_000f); direction(0f, -1f, 0f) },
    nodeApply = { position = Position(0f, 5f, 0f) }
)

// After (3.6.0)
LightNode(
    type = LightManager.Type.DIRECTIONAL,
    intensity = 100_000f,
    direction = Direction(0f, -1f, 0f),
    position = Position(0f, 5f, 0f)
)
```

### 5. `VideoNode` — convenience overload with asset path

New overload that handles `MediaPlayer` lifecycle automatically:

```kotlin
// Before — manual MediaPlayer setup
val player = rememberMediaPlayer(context, assetFileLocation = "videos/promo.mp4")
SceneView {
    player?.let { VideoNode(player = it, position = Position(z = -2f)) }
}

// After (3.6.0) — one-liner
SceneView {
    VideoNode(videoPath = "videos/promo.mp4", position = Position(z = -2f))
}
```

### 6. New composables: `ShapeNode` and `PhysicsNode`

Both are now available directly in the `SceneView { }` DSL:

```kotlin
SceneView {
    ShapeNode(
        polygonPath = listOf(Position2(0f, 0f), Position2(1f, 0f), Position2(0.5f, 1f)),
        color = Color(0xFF2196F3.toInt())
    )
    PhysicsNode(gravity = -9.81f, floorY = 0f) {
        SphereNode(radius = 0.1f, materialInstance = mat)
    }
}
```

### 7. Swift: Declarative `SceneView` with `@NodeBuilder`

SwiftUI `SceneView` now supports a declarative builder matching Android's `SceneView { }`:

```swift
// Before — imperative
SceneView { root in
    root.addChild(cube.entity)
    root.addChild(sphere.entity)
}

// After (3.6.0) — declarative
SceneView {
    GeometryNode.cube(size: 0.3, color: .red)
        .position(.init(x: -1, y: 0, z: -2))
    GeometryNode.sphere(radius: 0.2, color: .blue)
        .position(.init(x: 1, y: 0, z: -2))
}
```

### 8. Swift: `NodeGesture` automatic cleanup

`NodeGesture` now tracks entities with weak references and automatically purges stale handlers.
Entity fluent extensions are available for cleaner syntax:

```swift
// Before — static calls only
NodeGesture.onTap(entity) { print("Tapped!") }

// After (3.6.0) — fluent chaining
entity.onTap { print("Tapped!") }
       .onDrag { translation in entity.position += translation }
```

---

## SceneView 2.x to 3.x

SceneView 3.0 is a ground-up rewrite around Jetpack Compose. The core concepts are the same
(Filament engine, ARCore session, node graph), but the API is fully Compose-native. This guide
walks through every breaking change with before/after examples.

SceneView 3.0 is a ground-up rewrite around Jetpack Compose. The core concepts are the same
(Filament engine, ARCore session, node graph), but the API is fully Compose-native. This guide
walks through every breaking change with before/after examples.

---

## 1. Dependency version

```kotlin
// Before
implementation("io.github.sceneview:sceneview:2.3.0")
implementation("io.github.sceneview:arsceneview:2.3.0")

// After
implementation("io.github.sceneview:sceneview:4.15.1")
implementation("io.github.sceneview:arsceneview:4.15.1")
```

---

## 2. `SceneView` — nodes move into the content block

The `childNodes` parameter is gone. Declare nodes directly inside the `SceneView { }` trailing lambda.

```kotlin
// Before — nodes passed as a list
SceneView(
    modifier = Modifier.fillMaxSize(),
    engine = engine,
    modelLoader = modelLoader,
    childNodes = rememberNodes {
        add(
            ModelNode(
                modelInstance = modelLoader.createModelInstance("models/helmet.glb"),
                scaleToUnits = 1.0f
            )
        )
        add(CylinderNode(engine = engine, radius = 0.1f, height = 1.0f))
    },
    cameraManipulator = rememberCameraManipulator()
)

// After — nodes declared as composables in the DSL
val modelInstance = rememberModelInstance(modelLoader, "models/helmet.glb")

SceneView(
    modifier = Modifier.fillMaxSize(),
    cameraManipulator = rememberCameraManipulator()
) {
    modelInstance?.let { instance ->
        ModelNode(modelInstance = instance, scaleToUnits = 1.0f)
    }
    CylinderNode(radius = 0.1f, height = 1.0f)
}
```

Key differences:
- `engine` and `modelLoader` parameters have sensible defaults — you only need to provide them
  explicitly if you're sharing resources across multiple scenes.
- `rememberModelInstance` is async and returns `null` while loading. Use `?.let { }` to show the
  node only when ready. It triggers recomposition automatically.
- No more `add()` calls. The Compose runtime manages the node lifecycle.

---

## 3. Node hierarchy — `NodeScope` replaces `addChildNode`

```kotlin
// Before — imperative parent/child wiring
val parentNode = Node(engine).apply {
    addChildNode(
        ModelNode(modelInstance = helmet).apply {
            position = Position(y = 0.1f)
        }
    )
}

// After — declarative nesting via NodeScope
Node(position = Position(y = 0.0f)) {   // trailing lambda opens a NodeScope
    ModelNode(modelInstance = helmet, position = Position(y = 0.1f))
}
```

Every node composable in `SceneScope` accepts an optional `content` trailing lambda. Nodes
declared inside that lambda are automatically parented to the enclosing node.

---

## 4. `ARSceneView` — AR nodes move into the content block

```kotlin
// Before
var anchor: Anchor? = null

ARSceneView(
    modifier = Modifier.fillMaxSize(),
    childNodes = rememberNodes { /* populated imperatively in onSessionUpdated */ },
    onSessionUpdated = { session, frame ->
        if (anchor == null) {
            anchor = frame.hitTest(centerX, centerY)
                .firstOrNull { it.trackable is Plane }
                ?.createAnchor()
                ?.also { a ->
                    childNodes.add(AnchorNode(engine, a).apply {
                        addChildNode(ModelNode(modelInstance = helmet))
                    })
                }
        }
    }
)

// After — state drives composition
var anchor by remember { mutableStateOf<Anchor?>(null) }

ARSceneView(
    modifier = Modifier.fillMaxSize(),
    onSessionUpdated = { _, frame ->
        if (anchor == null) {
            anchor = frame.getUpdatedPlanes()
                .firstOrNull { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                ?.let { frame.createAnchorOrNull(it.centerPose) }
        }
    }
) {
    anchor?.let { a ->
        AnchorNode(anchor = a) {
            ModelNode(modelInstance = helmet, scaleToUnits = 0.5f)
        }
    }
}
```

The anchor state variable drives everything. When `anchor` becomes non-null, `AnchorNode` enters
the composition. When it is cleared, the node is removed and destroyed automatically.

---

## 5. Model loading — `rememberModelInstance` replaces synchronous creation

```kotlin
// Before — blocking, called inside rememberNodes or init
val instance = modelLoader.createModelInstance("models/helmet.glb")

// After — async, null while loading
val instance = rememberModelInstance(modelLoader, "models/helmet.glb")
// instance is null until the file is loaded, then recomposition fires
```

`rememberModelInstance` reads the file on `Dispatchers.IO` and creates the Filament asset on the
main thread, so it is both non-blocking and thread-safe.

---

## 6. `SurfaceType` — replaces boolean flags

```kotlin
// Before (if the flag existed in your version)
SceneView(isOpaque = false)

// After — explicit enum
SceneView(surfaceType = SurfaceType.TextureSurface)  // TextureView, supports alpha blending
SceneView(surfaceType = SurfaceType.Surface)          // SurfaceView, best performance (default)
```

---

## 7. `ViewNode` — Compose UI as a 3D surface

`ViewNode` is now a first-class composable in `SceneScope`. It requires a `WindowManager`
obtained with `rememberViewNodeManager()`.

```kotlin
// After
val windowManager = rememberViewNodeManager()

SceneView {
    ViewNode(windowManager = windowManager) {
        Card { Text("Hello from 3D!") }
    }
}
```

---

## 8. Activity / Fragment structure

All samples (and the recommended app structure) have moved from Fragment + XML layout to a single
`ComponentActivity` with `setContent { }`. There is no Fragment API in 3.0.

```kotlin
// Before — Fragment with layout inflation
class MainFragment : Fragment() {
    override fun onCreateView(...) = layoutInflater.inflate(R.layout.fragment_main, ...)
    override fun onViewCreated(view: View, ...) {
        val sceneView = view.findViewById<ARSceneView>(R.id.sceneView)
        sceneView.onSessionUpdated = { ... }
    }
}

// After — Activity with Compose
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ARSceneView(modifier = Modifier.fillMaxSize()) {
                // AR content here
            }
        }
    }
}
```

---

## 9. Sceneform / legacy Java classes

All classes that were still present under `com.google.ar.sceneform.*` and
`io.github.sceneview.collision.*` as Java files have been converted to Kotlin. The class names
and package paths are unchanged — only the file extension changes from `.java` to `.kt`.

If you were importing these classes directly (e.g. `com.google.ar.sceneform.rendering.Color`),
the imports continue to work. No action required.

---

## Summary checklist

| Change | Action |
|---|---|
| Bump dependency to `4.0.0` | Update `build.gradle` |
| Remove `childNodes = rememberNodes { }` | Move node declarations into `SceneView { }` |
| Replace `add(ModelNode(...))` | Use `ModelNode(...)` composable directly |
| Replace `addChildNode(...)` | Use nested `NodeScope` content lambda |
| Replace `modelLoader.createModelInstance(...)` | Use `rememberModelInstance(modelLoader, path)` |
| Replace `isOpaque = false` | Use `surfaceType = SurfaceType.TextureSurface` |
| Replace Fragment + XML layout | Use `ComponentActivity` + `setContent { }` |
| Replace imperative `anchor` node wiring | Drive with `mutableStateOf<Anchor?>` |

---

# v3.1.x → v3.2.x

## New node types (non-breaking)

v3.2.0 adds 8 new node composables in `SceneScope`. No migration required — these are additive.

| Node | Purpose |
|---|---|
| `PhysicsNode` | Rigid body simulation (gravity, floor collision, sleep) |
| `DynamicSkyNode` | Time-of-day sun positioning and coloring |
| `FogNode` | Atmospheric fog (density, height, color) |
| `ReflectionProbeNode` | Local/global IBL override zones |
| `LineNode` | Single line segment between two points |
| `PathNode` | Polyline through ordered points |
| `BillboardNode` | Camera-facing image quad |
| `TextNode` | Camera-facing text label |

## Dependency management change

Sample apps now use the Gradle version catalog (`libs.*`) instead of hardcoded versions.
If you copied a sample `build.gradle` as a starting point, update your dependencies:

```kotlin
// Before (hardcoded)
implementation "androidx.compose.ui:ui:1.10.5"
implementation "androidx.compose.material3:material3:1.3.2"

// After (version catalog)
implementation libs.androidx.compose.ui
implementation libs.androidx.compose.material3
```

Or if you're not using a version catalog, bump to the latest versions listed in
`gradle/libs.versions.toml`.

## Edge-to-edge

All sample activities now call `enableEdgeToEdge()` before `setContent {}`. If you're
building on a sample, add it to your `onCreate`:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { /* ... */ }
}
```
