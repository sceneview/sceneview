# SceneView cheat sheet

Verified against `llms.txt` at the repo root and the demos in
`samples/android-demo/src/main/java/io/github/sceneview/demo/demos/`. When in
doubt **read the demo, do not improvise**.

## Top-level entrypoints

| Composable | Artifact | Demo |
| --- | --- | --- |
| `SceneView { … }` | `io.github.sceneview:sceneview:4.12.0` | `ModelViewerDemo.kt` |
| `ARSceneView { … }` | `io.github.sceneview:arsceneview:4.12.0` | `ARPlacementDemo.kt` |

## `SceneView` parameters (most common)

```kotlin
SceneView(
    modifier = Modifier.fillMaxSize(),
    engine = rememberEngine(),
    modelLoader = rememberModelLoader(engine),
    materialLoader = rememberMaterialLoader(engine),
    environmentLoader = rememberEnvironmentLoader(engine),
    cameraNode = rememberCameraNode(engine),
    cameraManipulator = rememberCameraManipulator(),   // null to disable orbit
    environment = rememberEnvironment(environmentLoader) { /* HDR */ },
    mainLightNode = rememberMainLightNode(engine),     // null to disable
    fillLightNode = rememberFillLightNode(engine),     // null to disable
    isOpaque = true,
    renderQuality = RenderQuality.Default,              // Cinematic / Default / Performance
    onGestureListener = rememberOnGestureListener(/* … */),
    onFrame = { frameTimeNanos -> /* … */ },
) { /* SceneScope content */ }
```

Full signature is in `llms.txt § Core Composables`.

## `ARSceneView` extras

```kotlin
ARSceneView(
    /* same as SceneView, plus: */
    planeRenderer = true,
    cameraExposure = null,                              // null = auto (default); negative = darken, positive = brighten
    sessionFeatures = setOf(/* Session.Feature.* */),
    sessionCameraConfig = { cameraConfigFilter -> /* … */ },
    sessionConfiguration = { session, config ->
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
    },
    playbackDataset = file,                              // ARRecorder replay
    onSessionUpdated = { session, frame -> /* hit-test, etc. */ },
    onTrackingFailureChanged = { reason -> /* … */ },
)
```

There is **no `rememberARSession`** helper — configure via the
`sessionConfiguration` lambda.

## Remember helpers (always use these)

| Helper | Returns | Notes |
| --- | --- | --- |
| `rememberEngine()` | `Engine` | One per SceneView |
| `rememberModelLoader(engine)` | `ModelLoader` | Async GLB/GLTF loader |
| `rememberMaterialLoader(engine)` | `MaterialLoader` | `.filamat` loader |
| `rememberEnvironmentLoader(engine)` | `EnvironmentLoader` | IBL loader |
| `rememberCameraNode(engine) { … }` | `CameraNode` | Configure in trailing lambda |
| `rememberModelInstance(modelLoader, "asset.glb")` | `ModelInstance?` | **Nullable while loading** |
| `rememberMainLightNode(engine) { … }` | `LightNode` | Default key light (use `null` to disable) |
| `rememberFillLightNode(engine) { … }` | `LightNode` | Default fill (use `null` to disable) |
| `rememberCameraManipulator(...)` | `CameraGestureDetector.CameraManipulator?` | Orbit/pan controller; `null` to lock |
| `rememberOnGestureListener(onSingleTapConfirmed = …)` | `GestureDetector.OnGestureListener` | Wire AR taps |
| `rememberARRecorder()` | `ARRecorder` | Record/replay AR sessions (no args) |

## Common 3D nodes

| Node | Where verified | Notes |
| --- | --- | --- |
| `ModelNode(modelInstance, scaleToUnits, centerOrigin, isEditable)` | `ModelViewerDemo.kt` | Render a GLB. `isEditable = true` enables drag/scale/rotate |
| `LightNode(type, intensity, direction, position, color, apply = { … })` | `MovableLightDemo.kt` | `type` is `LightManager.Type.POINT / SPOT / DIRECTIONAL / FOCUSED_SPOT / SUN` |
| `CubeNode / SphereNode / CylinderNode / PlaneNode / CapsuleNode / TorusNode / ConeNode` | `GeometryDemo.kt`, `MovableLightDemo.kt` (Sphere) | Each takes `materialInstance` and shape-specific dimensions (e.g. `SphereNode(radius, ...)`) |
| `BillboardNode` | `BillboardDemo.kt` | Always faces the camera |
| `ImageNode` | `ImageDemo.kt` | 2D image quad |
| `TextNode` | `TextDemo.kt` | 3D text (uses `widthMeters` / `heightMeters`, NOT `scaleToUnits`) |
| `ViewNode` | `ViewNodeDemo.kt` | Embeds a Compose UI inside 3D |
| `LineNode / PathNode` | `LinesPathsDemo.kt` | Procedural lines/paths |
| `PhysicsNode(node, mass, restitution, …)` | `PhysicsDemo.kt` | Wraps an existing node; experimental |
| `ReflectionProbeNode` | `ReflectionProbesDemo.kt` | Local IBL probe |

For collision, use the **`rememberCollisionSystem(view)`** helper (it's already
plumbed by default in `SceneView`'s param list above) — not a node type. See
`CollisionDemo.kt` for the API in action.

## AR-only nodes (in `arsceneview`)

| Node | Where verified | Notes |
| --- | --- | --- |
| `AnchorNode(anchor: Anchor) { … }` | `ARPlacementDemo.kt` | Wraps a `com.google.ar.core.Anchor` |
| `AugmentedImageNode(referenceImage = …) { … }` | `ARImageDemo.kt` | Tracked image marker |
| `AugmentedFaceNode { … }` | `ARFaceDemo.kt` | Face mesh overlay |
| `HitResultNode(hitResult) { … }` | only used internally — prefer `frame.hitTest(event)` + `AnchorNode` | — |

There are NO `AnchorNode.image() / .face() / .plane() / .body()` factory
functions on Android in v4.2. Those are iOS-only via SceneViewSwift.

## Threading rule

Filament JNI is main-thread-only. Use the `remember*` helpers (they handle
this). For imperative code, use `modelLoader.loadModelInstanceAsync`.

## Apple parity

iOS / macOS / visionOS export `SceneView { }` and `ARSceneView { }` from
the `SceneViewSwift` package with SwiftUI semantics (`@SceneBuilder`,
modifier-style configuration). The API names overlap but the SwiftUI
shape differs — never copy a Kotlin snippet verbatim to Swift. See
[`docs/docs/cheatsheet-ios.md`](https://github.com/sceneview/sceneview/blob/main/docs/docs/cheatsheet-ios.md).
