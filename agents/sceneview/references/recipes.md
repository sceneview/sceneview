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
[`PickingAndCollisionDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/PickingAndCollisionDemo.kt) — the unified Picking & Collision demo bundles two modes: **Ray Hit-Test** with `CollisionSystem` + per-node tap highlights, **View Node** with `ViewNode { Card { Text("…") } }` (requires `viewNodeWindowManager` on `SceneView`). Touches now reach the embedded view (#2845), so `Button.onClick` fires — but a Material `Surface`/`Card` consumes the gesture even with nothing clickable inside, and a consumed gesture never reaches `onSingleTapUp`. Set `isTouchForwardingEnabled = false` on the node when you want the scene-level tap handler instead.

## 14. Point & Ask (on-device AI explains the augmented scene)
[`PointAndAskDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/PointAndAskDemo.kt) — tap → **composited window capture** (`PixelCopy.request(activity.window, …)` — camera + placed 3D nodes, so the model sees the *augmented* scene; hide overlays first) → **Gemini Nano** via ML Kit GenAI Prompt API: `Generation.getClient()`, gate on `checkStatus()`, then either one-shot `generateContent(generateContentRequest(ImagePart(bitmap), TextPart(question)) {})` or, as the demo does, **streamed** `generateContentStream(request)` (a `Flow` of text deltas grown into the answer card — see `AskEngine.askStream`). Camera-only variant: `frame.cameraImage()` (in `onSessionUpdated`, current frame only) → `Image.toArgbBitmap(rotationDegrees)` off the main thread. The question is a free-form user field (blank → default prompt); long-press drops a prop (`hitTest` → `AnchorNode` + `ModelNode`). Answers are **world-anchored**: the tap is hit-tested (tracked `Point`, or a `Plane` that is `HORIZONTAL_UPWARD_FACING` **and** in-polygon), `hit.createAnchor()` pins an `AnchorNode { ViewNode { … } }` card at that pose (capped list, explicit width on the content, **no facing rotation for those hits** — a horizontal/Point hit pose already faces the device and a `ViewNode` faces its own +Z; this does NOT hold on a VERTICAL plane, whose Z+ lies in the wall and would pin the card edge-on, so wall taps are filtered out and fall through to the screen-space card — pin them with `wallFacingRotation()` if you want them; `viewNodeWindowManager` required, `createAnchor()` wrapped since it throws when anchors pile up, hidden during the capture so the model never re-reads its own answers); no hit falls back to the screen-space card. Fully on-device (AICore, Pixel 8+); emulators are always `UNAVAILABLE` — swap in a canned engine under QA mode (see `AskEngine.kt`). Markdown recipe: `samples/recipes/point-and-ask.md`.

## AR recording / playback
[`ARRecordPlaybackDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/ARRecordPlaybackDemo.kt) — `val recorder = rememberARRecorder(); recorder.start(file); recorder.stop()`. To replay, pass `playbackDataset = file` to `ARSceneView`.

## Video recording (rendered scene → MP4, no MediaProjection)
[`VideoRecordingDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/VideoRecordingDemo.kt) — `val surfaceMirrorer = rememberSurfaceMirrorer(); SceneView(surfaceMirrorer = surfaceMirrorer)` (also on `ARSceneView`). Point a `MediaRecorder` (SURFACE video source, `MPEG_4`/`H264`) at the scene: `surfaceMirrorer.startMirroring(recorder.surface, width = 1280, height = 720); recorder.start()`, then `surfaceMirrorer.stopMirroring(recorder.surface); recorder.stop()`. Captures exactly what Filament renders (in AR, camera feed + virtual content composited) — never the Compose UI — with no MediaProjection consent dialog or foreground service. `startMirroring` is JNI-free / any-thread; `stopMirroring` is main-thread only. **Unlike `ARRecorder`** (which records an ARCore dataset for deterministic replay), this produces a shareable video.

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
val anchors = remember { mutableStateListOf<Anchor>() }

Box {
    ARSceneView(
        // Grid while scanning, shadow receivers after placement — NEVER both (#2657): the plane
        // renderer carries its own coplanar shadow receiver, so stacking ShadowReceiverPlanes on
        // top z-fights and double-darkens the contact shadow to near-black.
        planeRenderer = anchors.isEmpty(),
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
            onSingleTapConfirmed = { _, _ -> reticleHit?.createAnchor()?.let { anchors += it } }
        ),
    ) {
        PlacementReticle(xPx = viewWidth / 2f, yPx = viewHeight / 2f,
            onHitResultChanged = { reticleHit = it })
        if (anchors.isNotEmpty()) {  // the grid is gone — the catchers are the single receiver
            planes.forEach { key(it) { ShadowReceiverPlane(plane = it) } }
        }
        anchors.forEach { key(it) { AnchorNode(anchor = it) { /* ModelNode(...) */ } } }
    }
    PlaneDiscoveryGuide(cameraReady, isTracking, anyPlaneTracked, failure)
}
```

The demo-app reference implementation is
`samples/android-demo/.../common/placement/TapToPlaceArSession.kt`, which expresses this gating as
the mutually-exclusive `shouldRenderPlaneGrid` / `shouldCatchGroundShadows` predicates in
`TapToPlaceState.kt` (#2657) — copy that pattern whenever you hand-wire `ShadowReceiverPlane`s
alongside a plane renderer.

## Recipe: wall placement (TV, framed art, mirror) — #2740

For **vertical surfaces**, do not hand-roll `ARSceneView` + raw vertical-plane hits — use
`WallPlacementScene`, the vertical-surface sibling of `PlacementScene`. It decouples the two noisy
axes the way Amazon "AR View" / IKEA Place do: **orientation from the wall** (object flush +
upright, no hit-pose tilt) and **height from the floor** (`floorY + mountHeight`), so the placement
stays put while ARCore refines the vertical plane:

```kotlin
WallPlacementScene(
    mountHeight = 1.2f,                    // anchor height above the floor (TV centre height);
                                           // base-on-floor: pass the object's half-height
    onSeamChanged = { seam -> /* draw the "align to the floor↔wall edge" guide from it */ },
    onPhaseChanged = { phase -> /* FINDING_FLOOR → FINDING_WALL → ALIGNING_EDGE → PLACED */ },
    onPlaced = { anchor ->
        AnchorNode(anchor = anchor) {
            rememberModelInstance(modelLoader, "models/tv.glb")?.let {
                ModelNode(modelInstance = it, scaleToUnits = 1.4f)
            }
        }
    },
)
```

The placement math is public for custom flows: `wallFacingRotation(wallNormal)`,
`roomFacingNormal(wallNormal, towardViewer)` (ARCore does not guarantee a vertical plane's normal
sign — always flip it toward the camera), `floorWallSeam(...)`, `wallAnchorPose(...)`. First
increment of #2740: the seam/phase come back via callbacks so the app draws its own guide; an
in-scene 3D seam line and a gizmo/D-pad fine-adjust UI are tracked follow-ups.

## Recipe: grounding an object with a contact shadow — #2740

A model without a shadow reads as *floating*. On a floor, catch a real one with
`ShadowReceiverPlane`. On a **wall**, you cannot: indoor light comes from the ceiling, so it
grazes the wall and a flat-mounted TV casts nothing onto it. Use `ContactShadow`, which draws its
own gradient in the shader — no shadow map, no light dependency:

```kotlin
ContactShadow(
    size = Size(x = 2.4f, y = 1.6f, z = 0f),   // XY quad → a WALL
    context = ContactShadowContext.Wall,        // Floor / Wall / TableTop
    normal = Direction(z = 1f),
    position = Position(x = 0f, y = 1.3f, z = -1.99f),
)
```

**The one footgun:** `Plane` does not rotate its geometry to match `normal`, so `size` decides the
quad's plane and the two must agree — `Size(x, 0f, z)` + `Direction(y = 1f)` for a floor,
`Size(x, y, 0f)` + `Direction(z = 1f)` for a wall. Mismatch them and the shadow either z-fights
with the surface or floats off it.

Pick the `ContactShadowContext` rather than tuning numbers: `Floor` is centred and dense, `Wall`
is fainter, wider than tall and pushed below the object, `TableTop` is tight and crisp. It lives
in `sceneview`, not `arsceneview` — plain 3D scenes ground models the same way. Non-AR preview
demo: [`ContactShadowPreviewDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/ContactShadowPreviewDemo.kt).
