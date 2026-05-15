---
title: API Cheatsheet — SceneView Android
description: "Complete API reference for SceneView's 35+ node types, composables, resource loading, camera controls, gestures, and AR features on Android."
---

# API Cheatsheet

A quick reference for SceneView's most-used APIs. Print it, pin it, keep it next to your keyboard.

!!! tip "Building for Apple platforms?"
    See the [Apple API Cheatsheet](cheatsheet-ios.md) for SwiftUI + RealityKit equivalents.

---

## Setup

```kotlin
// build.gradle
implementation("io.github.sceneview:sceneview:4.3.5")     // 3D
implementation("io.github.sceneview:arsceneview:4.3.5")    // AR + 3D
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

## ARSceneView

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
    cameraExposure = null,      // null = ARCore default; Float (EV) to override
    onSessionUpdated = { session, frame -> },
    onTouchEvent = { event, hitResult -> true }
) {
    // ARSceneScope — declare AR nodes here
}
```

### Camera exposure override

Pass `cameraExposure` (in EV — exposure value) to correct a washed-out or too-dark camera
preview on devices where ARCore's auto-exposure does not match Camera2's output:

```kotlin
// Fix washed-out camera preview
ARSceneView(
    cameraExposure = 1.0f  // lower = darker, higher = brighter, null = ARCore default
) { }

// Fix too-dark preview (e.g. on some front-camera setups)
ARSceneView(
    cameraExposure = -1.0f
) { }
```

`0f` corresponds to the standard middle-grey reference exposure. Start with `1.0f` or `-1.0f`
and adjust by 0.5 EV steps until the preview matches the device's native camera app.

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
| `PhysicsNode` | `node`, `mass`, `restitution`, `linearVelocity`, `floorY`, `radius` |
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
Size(width = 1f, height = 0.5f)       // Float2
```

---

## Resource Loading

```kotlin
// Composable (preferred)
val model = rememberModelInstance(modelLoader, "models/file.glb")

// Imperative
val model = modelLoader.loadModelInstance("models/file.glb")
modelLoader.loadModelInstanceAsync("models/file.glb") { instance -> }

// Environment
environmentLoader.createHDREnvironment("environments/sky.hdr")
environmentLoader.createKtxEnvironment("environments/studio.ktx")

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

## Apple platforms

Building for iOS, macOS, or visionOS? See the [Apple API Cheatsheet](cheatsheet-ios.md).
