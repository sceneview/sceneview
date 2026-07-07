# SceneView recipes — pointers to verified demos

**Do not improvise.** Every pattern below has a matching demo file in
`samples/android-demo/src/main/java/io/github/sceneview/demo/demos/`. Read
that file before writing code — the demo is the authoritative recipe.

The repo also ships markdown recipes in
[`samples/recipes/`](https://github.com/sceneview/sceneview/tree/main/samples/recipes)
mirroring the same surface.

## 1. Model viewer (3D, GLB)
[`ModelViewerDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/ModelViewerDemo.kt) — `ModelNode(modelInstance, scaleToUnits, centerOrigin)` with hero-orbit camera manipulator.

## 2. Camera controls (orbit / zoom / pan)
[`CameraAndGesturesDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/CameraAndGesturesDemo.kt) — the unified Camera & Gestures demo (Camera Modes tab): pass a custom `cameraManipulator =` to `SceneView`, or `null` to lock the camera.

## 3. AR tap-to-place
[`ARPlacementDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/ARPlacementDemo.kt) — `rememberOnGestureListener(onSingleTapConfirmed = { event, node -> frame.hitTest(event)... })` + `AnchorNode(anchor = hit.createAnchor()) { ModelNode(isEditable = true) }`.

## 4. Augmented image tracking
[`ARImageDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/ARImageDemo.kt) — `AugmentedImageNode(augmentedImage = …) { ModelNode(...) }` — one node per detected `AugmentedImage` from `frame.getUpdatedAugmentedImages()`. The reference-image database is configured via `sessionConfiguration`.

## 5. Augmented face mesh
[`ARFaceDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/ARFaceDemo.kt) — `AugmentedFaceNode(augmentedFace = face) { ModelNode(...) }` — one node per tracked `AugmentedFace`. `Config.AugmentedFaceMode.MESH3D` in `sessionConfiguration` + `sessionCameraConfig = ::frontCameraConfig`.

## 6. Movable light (drag the light source)
[`LightingDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/LightingDemo.kt) — the "Movable Light" mode of the consolidated Lighting demo: `LightNode(type = LightManager.Type.POINT, intensity = 30_000f, direction, position, color, apply = { falloff(6f) })`. Disable default main light via `mainLightNode = null` for clean drag effect.

## 7. Multi-model and animation
[`AnimationPhysicsDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/AnimationPhysicsDemo.kt) (Animation tab) — `ModelNode` exposes `animationName`, `autoAnimate`, `animationLoop`, `animationSpeed`. For imperative control, call `node.playAnimation(name, speed = …, loop = …)` from `onFrame` or a button callback.

## 8. Lights and environment
[`LightingDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/LightingDemo.kt), [`LightingLabDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/LightingLabDemo.kt) (Environment tab) — `environment = rememberEnvironment(environmentLoader) { environmentLoader.createHDREnvironment("env.hdr") }`.

## 9. Procedural geometry
[`GeometryDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/GeometryDemo.kt) — use the per-shape composables (`CubeNode`, `SphereNode`, `CylinderNode`, etc.) directly. Each takes `materialInstance` plus its shape parameter (e.g. `radius`, `size`).

## 10. Custom geometry / mesh
[`CustomGeometryDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/CustomGeometryDemo.kt) — the unified Custom Geometry demo bundles two modes via a segmented-button toggle: **Custom Mesh** composes built-in shape nodes (`SphereNode` + `CylinderNode`) into a molecule, **Shape Extrude** triangulates 2D `Position2` polygon paths via `ShapeNode`. For a fully custom mesh from raw vertex/index buffers, use `MeshNode(primitiveType, vertexBuffer, indexBuffer, …)` directly — see `SceneScope.kt`.

## 11. Physics (bouncing spheres)
[`AnimationPhysicsDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/AnimationPhysicsDemo.kt) (Physics tab) — `PhysicsNode(node, mass, restitution, floorY)`. Experimental; only handles sphere collisions on a Y=0 floor.

## 12. Gesture editing (drag / pinch / rotate a node)
[`CameraAndGesturesDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/CameraAndGesturesDemo.kt) — the unified Camera & Gestures demo (Node Gestures tab): `ModelNode(isEditable = true)`. Listen via `rememberOnGestureListener(onMoveBegin = …, onScaleBegin = …, onRotateBegin = …)`.

## 13. ViewNode (Compose UI inside 3D)
[`PickingAndCollisionDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/PickingAndCollisionDemo.kt) — the unified Picking & Collision demo bundles two modes: **Ray Hit-Test** with `CollisionSystem` + per-node tap highlights, **View Node** with `ViewNode { Card { Text("…") } }` (requires `viewNodeWindowManager` on `SceneView`).

## AR recording / playback
[`ARRecordPlaybackDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/ARRecordPlaybackDemo.kt) — `val recorder = rememberARRecorder(); recorder.start(file); recorder.stop()`. To replay, pass `playbackDataset = file` to `ARSceneView`.

## Cross-platform parity

Apple (`SceneViewSwift`) and Web (`sceneview-web`) expose the same node names
but with platform-idiomatic shapes (SwiftUI `@SceneBuilder`, JavaScript
declarative API). **Don't copy-paste between platforms.** The platform docs
are:

- iOS: [`docs/docs/cheatsheet-ios.md`](https://github.com/sceneview/sceneview/blob/main/docs/docs/cheatsheet-ios.md)
- Web: [`sceneview-web README`](https://www.npmjs.com/package/sceneview-web)
- Flutter / RN: bridge packages; both render via the native SceneView underneath.

## Recipe: complete tap-to-place UX (onboarding + reticle + grounded shadow) — #2241

**The one-liner (preferred).** `PlacementScene` bundles the animated onboarding guide, a ring
reticle that brightens once a surface is ready, tap-to-place, the instant-placement fallback, the
plane grid fading after first placement, and a per-model contact shadow — all opt-in via flags:

```kotlin
PlacementScene(
    coaching = true,        // animated onboarding guide while searching for a surface
    groundShadows = true,   // contact shadow under each placed model
    // reticleStyle = PlacementReticleStyle.RING is the default; DISC for the legacy flat puck
    onPlaced = { anchor ->
        AnchorNode(anchor = anchor) {
            rememberModelInstance(modelLoader, "models/model.glb")?.let {
                ModelNode(modelInstance = it, scaleToUnits = 0.3f)
            }
        }
    },
)
```

**The manual assembly (custom flow).** Only hand-wire the pieces when you need a bespoke pipeline
`PlacementScene`'s flags don't cover — the building blocks are public:

```kotlin
var cameraReady by remember { mutableStateOf(false) }
var isTracking by remember { mutableStateOf(false) }
var anyPlaneTracked by remember { mutableStateOf(false) }
var failure by remember { mutableStateOf<TrackingFailureReason?>(null) }
var reticleHit by remember { mutableStateOf<HitResult?>(null) }
val planes = remember { mutableStateListOf<Plane>() }

Box {
    ARSceneView(
        onSessionUpdated = { session, frame ->
            cameraReady = true
            isTracking = frame.camera.trackingState == TrackingState.TRACKING
            val tracked = session.getAllTrackables(Plane::class.java)
                .filter { it.trackingState == TrackingState.TRACKING }
            anyPlaneTracked = tracked.isNotEmpty()
            if (planes.toList() != tracked) { planes.clear(); planes.addAll(tracked) }
        },
        onTrackingFailureChanged = { failure = it },
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { _, _ -> reticleHit?.createAnchor()?.let { /* AnchorNode + ModelNode */ } }
        ),
    ) {
        PlacementReticle(xPx = viewWidth / 2f, yPx = viewHeight / 2f,
            onHitResultChanged = { reticleHit = it })
        planes.forEach { key(it) { ShadowReceiverPlane(plane = it) } }
    }
    PlaneDiscoveryGuide(cameraReady, isTracking, anyPlaneTracked, failure)
}
```

The demo-app reference implementation is `samples/android-demo/.../common/placement/TapToPlaceArSession.kt`.

