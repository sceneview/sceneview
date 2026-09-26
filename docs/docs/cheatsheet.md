---
title: API Cheatsheet — SceneView Android
description: "Complete API reference for SceneView's 48+ node types, composables, resource loading, camera controls, gestures, and AR features on Android."
---

# API Cheatsheet

A quick reference for SceneView's most-used APIs. Print it, pin it, keep it next to your keyboard.

!!! tip "Building for Apple platforms?"
    See the [Apple API Cheatsheet](cheatsheet-ios.md) for SwiftUI + RealityKit equivalents.

---

## Setup

```kotlin
// build.gradle
implementation("io.github.sceneview:sceneview:4.40.0")     // 3D
implementation("io.github.sceneview:arsceneview:4.40.0")    // AR + 3D
```

---

## Core Remember Hooks

```kotlin
val engine = rememberEngine()
val modelLoader = rememberModelLoader(engine)
val materialLoader = rememberMaterialLoader(engine)
val environmentLoader = rememberEnvironmentLoader(engine)

val model = rememberModelInstance(modelLoader, "models/file.glb")  // null while loading
val env = rememberEnvironment(environmentLoader) {
    createHDREnvironment("environments/sky.hdr")
        ?: createEnvironment(environmentLoader)
}

val cameraManipulator = rememberCameraManipulator()
val mainLight = rememberMainLightNode(engine) { intensity = 100_000f }
val cameraNode = rememberCameraNode(engine) { position = Position(0f, 2f, 5f) }
val viewNodeManager = rememberViewNodeManager()
```

---

## SceneView

```kotlin
SceneView(
    modifier = Modifier.fillMaxSize(),
    engine = engine,
    modelLoader = modelLoader,
    cameraManipulator = cameraManipulator,    // orbit/pan/zoom
    cameraNode = cameraNode,                  // OR fixed camera
    environment = env,
    mainLightNode = mainLight,
    surfaceType = SurfaceType.Surface,        // or TextureSurface
    isOpaque = true,
    viewNodeWindowManager = viewNodeManager,  // for ViewNode
    onGestureListener = rememberOnGestureListener(
        onSingleTapConfirmed = { event, node -> },
        onDoubleTap = { event, node -> },
        onLongPress = { event, node -> }
    ),
    onTouchEvent = { event, hitResult -> false },
    onFrame = { frameTimeNanos -> }
) {
    // SceneScope — declare nodes here
}
```

---

## Automatic placement (recommended)

`AutoPlacementScene` is additive: one usable detected plane consumes one placement
request. It uses normal camera tracking, upward-facing horizontal surfaces (`SURFACE`)
or vertical planes (`WALL`), a center ray followed by visible polygon-validated plane
centers, and a 0.25–3 m interaction range. It renders no plane grid or reticle.

```kotlin
import io.github.sceneview.ar.*

val engine = rememberEngine()
val modelLoader = rememberModelLoader(engine)
val model = rememberModelInstance(modelLoader, "models/khronos_toy_car.glb")
val placement = rememberAutoPlacementState()
AutoPlacementScene(
    assetReady = model != null,
    state = placement,
    engine = engine,
    modelLoader = modelLoader,
    surface = PlacementSurface.SURFACE, // WALL accepts vertical planes directly
    onPlaced = { result -> /* result.anchor, result.plane, result.pose */ },
) { result ->
    model?.let { AutoPlacementModel(result, placement, it, scaleToUnits = 0.3f) }
}
// Explicit reset retains the asset and the camera session:
// placement.resetPlacement(android.os.SystemClock.uptimeMillis())
```

`AutoPlacementModel` grounds the complete model bounds, preserves the contact pivot
while rotating/scaling, and constrains dragging to supported plane geometry. Its
0.3 m longest-dimension default is **Preview size**; `scaleToUnits = null` retains
trustworthy authored units (**Actual size**). Scale limits are 25–400% of that base.

For asynchronous selection, call `placement.selectModel()` before loading and attach
only while `placement.acceptsAsset(ticket)` is true. Keep the previous rendered model
until its replacement succeeds. Observe `placement.phase`; use `requestPlacement()`,
`resetPlacement(nowMillis)` and `keepScanning(nowMillis)` for explicit actions. Reset
removes the wrapper-owned anchor without restarting the camera. Interruption freezes
manipulation and recovers the existing placement; it does not arm a new request.
`onARCoreAvailability`, `onTrackingFailureChanged`, and `onSessionFailed` expose
capability, tracking, and camera failures. Copy, permissions, asset selection and
semantic haptics belong to the app.

States match Swift's `ARPlacementPhase`: `INITIALIZING`, `SCANNING`, `NO_SURFACE`,
`PLACED`, `ADJUSTING`, `TRACKING_LOST`, `RECOVERING`, `RECOVERY_FAILED`, `CAMERA_ERROR`.
The no-surface and recovery deadlines are both ten seconds. A controller manages one
object; repeated requests while placed are ignored. Multi-object hosts explicitly own
separate requests/controllers; tapping empty space never places.

**Manual-placement compatibility:** `PlacementScene`, `WallPlacementScene`
(`WallPlacement`), `onTapOnPlane`, `ReticleNode` and the placement-reticle options remain
manual-placement APIs. Their published defaults and tap behavior are unchanged. Use
them for deliberate manual interactions or diagnostics; new placement flows should
use `AutoPlacementScene`.


### Direct wall placement

Use `surface = PlacementSurface.WALL`: detection is vertical-only, with no floor,
seam alignment or placement tap. The first usable wall creates one real plane anchor.
New plane detections never move a standing object; drag to a valid alternative wall or
reset explicitly. Tracking loss retains that placement and enters recovery.

Author wall models with **+Y up and +Z front** (or supply `assetRotation`).
`AutoPlacementModel` puts the bounding box's back and bottom at the contact pivot and
faces its front toward the camera side of the wall, even if the detected normal points
away. Drag projects the grab offset into the destination wall and validates its polygon;
twist rotates in the wall plane; pinch preserves contact at 25–400% of the base size.
The anchor frame retains the Android surface convention: +Y is the wall normal and
−Z points up. `directWallPose(point, normal, towardViewer)` is an additive pure helper
returning the authored +Y-up/+Z-front frame at the exact wall point; it requires a finite
normal with a nonzero horizontal component. Legacy `wallAnchorPose`, `wallFacingRotation`,
`floorWallSeam` and `WallPlacementPhase` keep their floor/seam semantics unchanged.

For procedural geometry, `AutoPlacementNode(result, placement) { opacity -> … }` uses
that same gesture hierarchy. Supply content already sized (demo: 0.3 m longest dimension),
with bottom at y=0 and back at z=0. Mark selectable children editable, but disable their
individual position/rotation/scale editing so gestures reach the contact pivot. Apply
`opacity` to transparent materials for the 300 ms placement/tracking fade.

Selected-object accessibility alternatives share the gesture constraints:
`placement.moveBy(x, y)` moves in metres right/up on a wall, `rotateBy(degrees)` twists
around its normal, and `scaleTo(factor)` changes its base-size multiplier.
`scaleFactor` is observable. Keep these controls in a sheet, with **Reset placement**.
`playbackDataset` is forwarded by the Android wall demo; a floor-only replay does not
validate wall placement.

| Wall-demo rendering | Android | iOS |
|---|---|---|
| TV geometry and size | Two boxes, 0.3 m preview | Same dimensions and material parameters |
| Contact | Back/bottom pivot on the real wall | Same |
| Wall contact shading | Disabled in the demo; no procedural shadow blob | No wall shadow; RealityKit grounding shadows project downward |
| Reveal / tracking loss | 300 ms opacity fade | 300 ms opacity fade |

Native renderer lighting can differ. The demo does not claim physically identical
wall shadows, and never substitutes a synthetic pool for renderer shading.

## ARSceneView (low-level / manual placement)

```kotlin
ARSceneView(
    modifier = Modifier.fillMaxSize(),
    engine = engine,
    modelLoader = modelLoader,
    planeRenderer = true,
    sessionConfiguration = { session, config ->
        config.depthMode = Config.DepthMode.AUTOMATIC
        // ENVIRONMENTAL_HDR is the v4.3.0+ library default — pre-set BEFORE this callback.
        // Override only to opt back into AMBIENT_INTENSITY for the cost profile.
    },
    sessionFeatures = setOf(),  // e.g., Session.Feature.FRONT_CAMERA
    // fillLightNode = null,     // v4.3.0+: pass null to disable the dual-light AR baseline
    cameraExposure = null,      // null = default (recommended); absolute exposure scale, NOT EV stops (#1179)
    flashMode = Config.FlashMode.OFF,  // v4.11+: Config.FlashMode.TORCH for low-light tracking
    // playbackDataset = file,      // v4.5+: deterministic replay from a recorded MP4 (File)
    // playbackDatasetUri = uri,    // v4.11+ scoped-storage equivalent (mutually exclusive)
    onSessionUpdated = { session, frame -> },
    onSessionFailure = { failure ->     // v4.11+: typed exhaustive when (#1759)
        when (failure) {
            is ARSessionFailure.ArCoreNotInstalled -> { /* install ARCore */ }
            is ARSessionFailure.CameraNotAvailable -> { /* camera busy — close other camera apps */ }
            // ... see ARSessionFailure for the full sealed hierarchy. Prefer an exhaustive
            // `when` with NO `else` branch so the compiler flags new failure modes;
            // an `else ->` silently swallows future subtypes (llms.txt § Error Handling).
            else -> { /* fallback while prototyping only */ }
        }
    },
    onTouchEvent = { event, hitResult -> true }
) {
    // ARSceneScope — declare AR nodes here
}
```

### Recording / playback

```kotlin
val recorder = rememberARRecorder()
val status by rememberARPlaybackStatus(arSession)   // v4.11+: PlaybackStatus as State

ARSceneView(
    playbackDatasetUri = pickedUri,                 // scoped-storage Uri
    onSessionUpdated = { s, _ -> recorder.recordFrame(s) },
    onPlaybackFailed = { e -> /* MP4 unreadable */ },
) { /* DSL */ }
```

**Two different "recordings" — pick by what you capture:**

- **`SurfaceMirrorer`** (`sceneview` **and** `arsceneview`) — records **the rendered scene to an
  MP4**: exactly what Filament draws (in AR, camera feed + virtual content composited), no Compose
  UI. In-app, no MediaProjection consent dialog / foreground service. Share-ready video.
- **`ARRecorder`** (above, `arsceneview` only) — records an **ARCore session dataset** (raw sensor
  streams) for deterministic **replay** through `playbackDataset` — a debugging tool, not a video.

```kotlin
val surfaceMirrorer = rememberSurfaceMirrorer()
SceneView(surfaceMirrorer = surfaceMirrorer) { /* DSL */ }   // or ARSceneView(surfaceMirrorer = …)

// Point a MediaRecorder (SURFACE video source) at the scene; frame is letterboxed to fit.
surfaceMirrorer.startMirroring(recorder.surface, width = 1280, height = 720)  // any thread, JNI-free
recorder.start()
// …record…
surfaceMirrorer.stopMirroring(recorder.surface)                              // main thread only
recorder.stop(); recorder.release()
```

### Camera exposure override

`cameraExposure` is Filament's **absolute exposure scale** (the single-`Float` `setExposure`
overload — `1.0 ≈ ISO 100 ≈ EV 0`). It is **NOT a signed EV-stop bias**: negative values clamp
to zero and render a fully black framebuffer (#1179). Realistic range is roughly `0.05`–`16`.

```kotlin
// Brighten a too-dark camera preview
ARSceneView(
    cameraExposure = 2.0f   // > 1.0 = brighter, < 1.0 = darker, null = default (recommended)
) { }

// Darken a washed-out preview
ARSceneView(
    cameraExposure = 0.5f   // NEVER pass a negative value — it clamps to a black frame
) { }
```

Prefer leaving it `null` — the default AR camera tuning is correct for both back- and
front-camera sessions. Note: the **iOS** `ARSceneView(cameraExposure:)` is a different
mechanism (EV-stop post-process via CIColorControls) — do not copy values across platforms.

---

## Node Types — 3D

| Node | Key Parameters |
|---|---|
| `ModelNode` | `modelInstance`, `scaleToUnits`, `centerOrigin`, `position`, `rotation`, `isEditable`, `autoAnimate`, `animationName`, `animationLoop` |
| `CubeNode` | `size: Size`, `materialInstance` |
| `SphereNode` | `radius: Float`, `materialInstance` |
| `CylinderNode` | `radius`, `height`, `materialInstance` |
| `PlaneNode` | `size: Size`, `materialInstance` |
| `LightNode` | `type: LightManager.Type`, `apply = { intensity(); color(); castShadows() }` |
| `ImageNode` | `imageFileLocation` / `imageResId` / `bitmap`, `size` |
| `VideoNode` | `videoPath` (simple) / `player: MediaPlayer` (advanced), `chromaKeyColor`, `size` |
| `ViewNode` | `windowManager`, content = `@Composable` |
| `TextNode` | `text`, `fontSize`, `textColor`, `backgroundColor`, `widthMeters` |
| `BillboardNode` | `bitmap`, `widthMeters`, `heightMeters` |
| `LineNode` | `start`, `end`, `materialInstance` |
| `PathNode` | `points: List<Position>`, `closed`, `materialInstance` |
| `DynamicSkyNode` | `timeOfDay` (0-24), `turbidity`, `sunIntensity` |
| `FogNode` | `view`, `density`, `height`, `color`, `enabled` |
| `ReflectionProbeNode` | `filamentScene`, `environment`, `position`, `radius`, `cameraPosition` |
| `PhysicsNode` | `node`, `restitution`, `linearVelocity`, `floorY`, `radius`, `floorProvider` (`mass` overload is deprecated — no-op) |
| `MeshNode` | `primitiveType`, `vertexBuffer`, `indexBuffer`, `materialInstance` |
| `Node` | `position`, `rotation`, `scale` + child content |
| `SecondaryCamera` | `apply` — non-active camera (formerly `CameraNode`) |

---

## Node Types — AR

| Node | Key Parameters |
|---|---|
| `AnchorNode` | `anchor: Anchor` + child content |
| `HitResultNode` | `xPx`, `yPx` + child content (reticle) |
| `AugmentedImageNode` | `augmentedImage` + child content |
| `AugmentedFaceNode` | `augmentedFace`, `meshMaterialInstance` |
| `CloudAnchorNode` | `anchor`, `cloudAnchorId`, `onHosted` + child content |

---

## Common Node Properties

```kotlin
node.position = Position(x, y, z)      // meters
node.rotation = Rotation(x, y, z)      // degrees
node.scale = Scale(x, y, z)            // multiplier
node.isVisible = true
node.isEditable = true                 // pinch-scale, drag-move, rotate
node.isTouchable = true
node.onSingleTapConfirmed = { event -> true }
node.onFrame = { frameTimeNanos -> }

// Smooth movement
node.transform(position = Position(2f, 0f, 0f), smooth = true, smoothSpeed = 5f)
node.lookAt(targetNode)

// Animation
node.animateRotations(Rotation(0f), Rotation(y = 360f)).also {
    it.duration = 2000
    it.repeatCount = ValueAnimator.INFINITE
}.start()
```

---

## Math Types

```kotlin
import io.github.sceneview.math.*

Position(x = 0f, y = 1f, z = -2f)     // Float3, meters
Rotation(x = 0f, y = 90f, z = 0f)     // Float3, degrees
Scale(1.5f)                             // uniform
Scale(x = 2f, y = 1f, z = 2f)         // non-uniform
Direction(x = 0f, y = 1f, z = 0f)     // unit vector
Size(x = 1f, y = 0.5f, z = 0f)        // Float3 — dimensions in meters
```

---

## Resource Loading

```kotlin
// Composable (preferred)
val model = rememberModelInstance(modelLoader, "models/file.glb")

// Imperative
val model = modelLoader.loadModelInstance("models/file.glb")   // suspend — call from a coroutine
modelLoader.loadModelInstanceAsync("models/file.glb") { instance -> }

// Environment
environmentLoader.createHDREnvironment("environments/sky.hdr")
environmentLoader.createKTX1Environment(iblAssetFile = "environments/studio_ibl.ktx")

// Material
materialLoader.createColorInstance(Color.Red)
```

### Custom 3D content

Authoring your own model? See the [Blender pipeline recipe](recipes/blender-pipeline.md):
export `.glb` from Blender for Android (native), or convert `.glb` → `.usdz` via Reality
Converter + Reality Composer Pro for Apple platforms.

---

## Threading Rules

| Safe | Unsafe |
|---|---|
| `rememberModelInstance(...)` | `modelLoader.createModelInstance(...)` on IO |
| `loadModelInstanceAsync(...)` | `materialLoader.createMaterial(...)` on IO |
| Any composable in `SceneView { }` | Direct Filament API on background thread |

**Rule:** Filament JNI = main thread only. `remember*` hooks handle this for you.

---

## AR debug — Rerun.io

Stream ARCore frames into the [Rerun](https://rerun.io) viewer for
scrub-and-replay debugging.

```kotlin
import io.github.sceneview.ar.rerun.rememberRerunBridge

@Composable
fun ARDebugScreen() {
    val bridge = rememberRerunBridge(rateHz = 10, enabled = BuildConfig.DEBUG)
    ARSceneView(onSessionUpdated = { s, f -> bridge.logFrame(s, f) })
}
```

| Mode | Sidecar command | Shareable? |
|---|---|---|
| Live | `python rerun-bridge.py` | No — viewer is local-only |
| Save | `python rerun-bridge.py --save` | Yes — writes a `.rrd` file |

**Save & Share** trigger from the app:

```kotlin
bridge.requestSaveAndShare { result ->
    // result.path     -> /Users/dev/.sceneview/recordings/<ts>.rrd
    // result.viewerUrl -> https://sceneview.github.io/rerun/?url=<…>
    // result.events   -> 1234
}
```

Drop the saved `.rrd` onto **<https://sceneview.github.io/rerun/>** to scrub
the AR session frame-by-frame in any browser — no install required, no
re-hosting needed for local inspection. To share with a remote teammate,
re-host the file (R2, GitHub release, gist) and send them
**`https://sceneview.github.io/rerun/?url=<encoded-public-url>`**.

---

## Spatial Audio & Haptic — cross-platform availability

v4.12.0 shipped Spatial Audio (#1900) and Haptic Feedback (#1901). Both have
real implementations on **all three platforms** — they are *not* Android-only —
each using the platform-native audio / vibration backend:

| Feature | Android | Web (`sceneview-web`) | iOS |
|---|---|---|---|
| **Spatial Audio** | `SpatialAudioNode { }` composable | `io.github.sceneview.web.audio.SpatialAudioNode` — a real Kotlin/JS class backed by the Web Audio `PannerNode` (HRTF panning + distance falloff). Load assets with `loadAudioSource(url)` / `loadAudioSourcePromise(url)`; drive the listener with `setSpatialAudioListenerPose(...)`. **Exposed to Kotlin/JS consumers** — it is *not* `@JsExport`-ed to a plain-JavaScript `sceneview.js` global (the module uses `suspend`, `external` Web Audio declarations and a `sealed interface`, none `@JsExport`-compatible). The web Spatial Audio demo (#1944) uses this API. | `SpatialAudioNode.spatial(...)` |
| **Haptic Feedback** | `rememberHapticFeedback()` → `SceneViewHaptic` | `io.github.sceneview.web.haptic.SceneViewHaptic` — semantic presets via the [Web Vibration API](https://developer.mozilla.org/docs/Web/API/Navigator/vibrate) (`navigator.vibrate`). **`@JsExport`-ed** — callable from plain JavaScript as `sceneview.haptic.light()` / `.success()` / `.continuous(intensity, durationMs)` etc. Durations only — `intensity` / `sharpness` are accepted for cross-platform parity but ignored at runtime (the Vibration API has no amplitude control). Silent no-op on browsers without `navigator.vibrate` (most desktop, Safari iOS). | `SceneViewHaptic()` (Core Haptics) |

See the [Apple API Cheatsheet](cheatsheet-ios.md#spatial-audio--haptic-parity-1900-1901)
for the iOS maturity detail.

---

## Apple platforms

Building for iOS, macOS, or visionOS? See the [Apple API Cheatsheet](cheatsheet-ios.md).
