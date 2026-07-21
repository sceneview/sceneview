# SceneView cheat sheet

Verified against `llms.txt` at the repo root and the demos in
`samples/android-demo/src/main/java/io/github/sceneview/demo/demos/`. When in
doubt **read the demo, do not improvise**.

## Top-level entrypoints

| Composable | Artifact | Demo |
| --- | --- | --- |
| `SceneView { … }` | `io.github.sceneview:sceneview:4.25.0` | `ModelViewerDemo.kt` |
| `ARSceneView { … }` | `io.github.sceneview:arsceneview:4.25.0` | `ARPlacementDemo.kt` |

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
    surfaceMirrorer = rememberSurfaceMirrorer(),        // optional: record the rendered scene to MP4 in-app (no MediaProjection). Also on ARSceneView
) { /* SceneScope content */ }
```

Full signature is in `llms.txt § Core Composables`.

## `ARSceneView` extras

```kotlin
ARSceneView(
    /* same as SceneView, plus: */
    planeRenderer = true,
    cameraExposure = null,                              // null = default (recommended). ABSOLUTE exposure scale (1.0 ≈ ISO 100), NOT EV stops — negative clamps to a black frame (#1179)
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

## AR placement UX kit (#2241, v4.19+)

Prefer these built-ins over hand-rolled banners/reticles/shadows:

```kotlin
Box {
    ARSceneView(...) {
        PlacementReticle(xPx = viewWidth/2f, yPx = viewHeight/2f,
            onHitResultChanged = { reticleHit = it })      // smoothed cursor (slerp 0.75)
        detectedPlanes.forEach { key(it) { ShadowReceiverPlane(plane = it) } }  // grounded shadows
    }
    PlaneDiscoveryGuide(cameraReady, isTracking, anyPlaneTracked, failure)  // onboarding overlay
}
```

Never keep `ShadowReceiverPlane`s AND `planeRenderer = true` live on the same plane — the plane
renderer attaches its own coplanar shadow receiver, so stacking both z-fights and double-darkens
shadows (#2657). Gate them mutually exclusively (grid while scanning, shadow receivers after
placement), or just use `PlacementScene`, which handles this for you.

`PlaneDiscoveryGuide` = timed hand-hint/help onboarding (replaces static "Scanning…" banners).
`snapToPlane=false` on the reticle = free placement (points accepted, planes in-polygon).
**Vertical surfaces (#2740):** `WallPlacementScene(mountHeight = …, onSeamChanged = …, onPlaced = …)`
— wall-flush upright orientation + floor-relative height; exposes the floor↔wall seam for the
"align to the edge" guide. Pure math helpers: `wallFacingRotation` / `roomFacingNormal` /
`floorWallSeam` / `wallAnchorPose`.
**Grounding (#2740):** `ContactShadow(size, context = ContactShadowContext.Wall|Floor|TableTop,
normal)` — procedural gradient, no shadow map, so it works on a WALL where a real shadow can't
(ceiling light grazes it). `size` decides the quad plane and must agree with `normal`:
`Size(x, 0f, z)`+`Direction(y=1f)` floor, `Size(x, y, 0f)`+`Direction(z=1f)` wall. In `sceneview`,
not `arsceneview`. Real floor shadows stay `ShadowReceiverPlane`.
iOS: coaching overlay = `ARSceneView(showCoachingOverlay: true)` (native); reticle = `showPlacementReticle: true`, contact shadows = `groundingShadows` (default on) — #894 shipped.

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
| `rememberARRecorder()` | `ARRecorder` | Record/replay AR sessions — ARCore **dataset** for deterministic replay (no args) |
| `rememberSurfaceMirrorer()` | `SurfaceMirrorer` | Record the **rendered scene** to MP4 in-app (no MediaProjection). Pass to `SceneView`/`ARSceneView(surfaceMirrorer = …)`, then `startMirroring(surface)` / `stopMirroring(surface)` |

## Common 3D nodes

| Node | Where verified | Notes |
| --- | --- | --- |
| `ModelNode(modelInstance, scaleToUnits, centerOrigin, isEditable)` | `ModelViewerDemo.kt` | Render a GLB. `isEditable = true` enables drag/scale/rotate |
| `LightNode(type, intensity, direction, position, color, apply = { … })` | `LightingDemo.kt` | `type` is `LightManager.Type.POINT / SPOT / DIRECTIONAL / FOCUSED_SPOT / SUN` |
| `CubeNode / SphereNode / CylinderNode / PlaneNode / CapsuleNode / TorusNode / ConeNode` | `GeometryDemo.kt`, `LightingDemo.kt` (Sphere) | Each takes `materialInstance` and shape-specific dimensions (e.g. `SphereNode(radius, ...)`) |
| `BillboardNode` | `TwoDInThreeDDemo.kt` | Always faces the camera (Billboard tab of unified 2D in 3D demo) |
| `ImageNode` | `TwoDInThreeDDemo.kt` | 2D image quad (Image tab of unified 2D in 3D demo) |
| `TextNode` | `TwoDInThreeDDemo.kt` | 3D text (Text tab of unified 2D in 3D demo). Uses `widthMeters` / `heightMeters`, NOT `scaleToUnits` |
| `ViewNode` | `PickingAndCollisionDemo.kt` | Embeds a Compose UI inside 3D (View Node tab of the unified demo) |
| `LineNode / PathNode` | `LinesPathsDemo.kt` | Procedural lines/paths |
| `PhysicsNode(node, mass, restitution, …)` | `AnimationPhysicsDemo.kt` | Wraps an existing node; experimental (Physics tab of unified Animation & Physics demo) |
| `ReflectionProbeNode` | `LightingLabDemo.kt` | Local IBL probe (Reflections tab of unified Lighting Lab demo) |

For collision, use the **`rememberCollisionSystem(view)`** helper (it's already
plumbed by default in `SceneView`'s param list above) — not a node type. See
`PickingAndCollisionDemo.kt` (Ray Hit-Test tab of the unified Picking & Collision demo) for the API in action.

## AR-only nodes (in `arsceneview`)

| Node | Where verified | Notes |
| --- | --- | --- |
| `AnchorNode(anchor: Anchor) { … }` | `ARPlacementDemo.kt` | Wraps a `com.google.ar.core.Anchor` |
| `AugmentedImageNode(augmentedImage = …) { … }` | `ARImageDemo.kt` | Tracked image marker — takes the detected `AugmentedImage` trackable |
| `AugmentedFaceNode(augmentedFace = …) { … }` | `ARFaceDemo.kt` | Face mesh overlay — takes the detected `AugmentedFace` trackable |
| `HitResultNode(xPx, yPx) { … }` | `llms.txt § HitResultNode` | Continuous screen-coordinate surface cursor; for one-shot taps prefer `frame.hitTest(event)` + `AnchorNode` |

There are NO `AnchorNode.image() / .face() / .plane() / .body()` factory
functions on Android in v4.2. Those are iOS-only via SceneViewSwift.

## Threading rule

Filament JNI is main-thread-only. Use the `remember*` helpers (they handle
this). For imperative code, use `modelLoader.loadModelInstanceAsync`.

## Apple parity

iOS / macOS / visionOS export `SceneView { }` and `ARSceneView { }` from
the `SceneViewSwift` package with SwiftUI semantics (`@NodeBuilder`,
modifier-style configuration). The API names overlap but the SwiftUI
shape differs — never copy a Kotlin snippet verbatim to Swift. See
[`docs/docs/cheatsheet-ios.md`](https://github.com/sceneview/sceneview/blob/main/docs/docs/cheatsheet-ios.md).
