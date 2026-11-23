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
[`CameraAndGesturesDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/CameraAndGesturesDemo.kt) — the Camera & Gestures demo: pass a custom `cameraManipulator =` to `SceneView`, or `null` to lock the camera. Its `StudioCameraManipulator` owns a spherical pose, so it can also coast on release and fly between framings — things Filament's own `Manipulator` cannot express.

## 3. Automatic AR model placement
Use `AutoPlacementScene` and `AutoPlacementModel` in the [placement recipe](#place-a-model-in-ar).
The demo is [`ARPlacementDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/ARPlacementDemo.kt).

## 4. Augmented image tracking
[`ARImageDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/ARImageDemo.kt) — `AugmentedImageNode(augmentedImage = …) { ModelNode(...) }` — one node per detected `AugmentedImage` from `frame.getUpdatedAugmentedImages()`. The reference-image database is configured via `sessionConfiguration`.

## 5. Augmented face mesh
[`ARFaceDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/ARFaceDemo.kt) — `AugmentedFaceNode(augmentedFace = face) { ModelNode(...) }` — one node per tracked `AugmentedFace`. `Config.AugmentedFaceMode.MESH3D` in `sessionConfiguration` + `sessionCameraConfig = ::frontCameraConfig`.

## 6. Movable light (drag the light source)
[`LightingDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/LightingDemo.kt) — the "Movable Light" mode of the consolidated Lighting demo: `LightNode(type = LightManager.Type.POINT, intensity = 30_000f, direction, position, color, apply = { falloff(6f) })`. Disable default main light via `mainLightNode = null` for clean drag effect.

## 7. Multi-model and animation
[`AnimationPhysicsDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/AnimationPhysicsDemo.kt) — `ModelNode` exposes `animationName`, `autoAnimate`, `animationLoop`, `animationSpeed`. For imperative control, call `node.playAnimation(name, speed = …, loop = …)` from `onFrame` or a button callback.

## 8. Lights and environment
[`LightingDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/LightingDemo.kt) (Image rig) — `environment = rememberEnvironment(environmentLoader) { environmentLoader.createHDREnvironment("env.hdr") }`.

## 9. Procedural geometry
[`GeometryDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/GeometryDemo.kt) — use the per-shape composables (`CubeNode`, `SphereNode`, `CylinderNode`, etc.) directly. Each takes `materialInstance` plus its shape parameter (e.g. `radius`, `size`).

## 10. Custom geometry / mesh
[`CustomGeometryDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/CustomGeometryDemo.kt) — generates a torus knot's vertices at runtime and draws them with `MeshNode`. The four steps: build `List<Geometry.Vertex>` (position + normal + UV) in plain Kotlin, build the index list, `Geometry.Builder(primitiveType).vertices(…).indices(…).build(engine)`, then `MeshNode(primitiveType, vertexBuffer, indexBuffer, boundingBox, materialInstance)`. To animate the mesh, `remember` the `Geometry` on the vertex **count** and push new positions with `geometry.update(engine, vertices)` — that rewrites the existing buffers instead of allocating new ones — and free them yourself with `engine.safeDestroyGeometry(geometry)` in a `DisposableEffect`, since `MeshNode` does not own buffers it was handed. `PrimitiveType.LINES` with an edge index list gives you a wireframe of the same vertices.

For a 2D polygon extruded into a 3D mesh, use `ShapeNode(polygonPath = listOf(Position2(…), …), materialInstance = …)` instead — it triangulates the outline for you and supports holes and Delaunay refinement.

## 11. Physics (bouncing spheres)
[`RollingBallsDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/RollingBallsDemo.kt) — `PhysicsNode(node, mass, restitution, floorY)`. Experimental; only handles sphere collisions on a Y=0 floor.

## 12. Gesture editing (drag / pinch / rotate a node)
[`CameraAndGesturesDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/CameraAndGesturesDemo.kt) — the Camera & Gestures demo, behind its Move dock item: `ModelNode(isEditable = true)`. Listen via `rememberOnGestureListener(onMoveBegin = …, onScaleBegin = …, onRotateBegin = …)`.

## 13. ViewNode (Compose UI inside 3D)
[`PickingAndCollisionDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/PickingAndCollisionDemo.kt) — the unified Picking & Collision demo puts both halves in **one** scene (#3329): a `CollisionSystem` ray hit-test with per-node tap highlights, and a `ViewNode { Card { Text("…") } }` card floating over the same shapes (requires `viewNodeWindowManager` on `SceneView`). Touches now reach the embedded view (#2845), so `Button.onClick` fires — but a Material `Surface`/`Card` consumes the gesture even with nothing clickable inside, and a consumed gesture never reaches `onSingleTapUp`. Set `isTouchForwardingEnabled = false` on the node when you want the scene-level tap handler instead.

## 14. Point & Ask (on-device AI explains the augmented scene)
[`PointAndAskDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/PointAndAskDemo.kt) — tap → **composited window capture** (`PixelCopy.request(activity.window, …)` — camera + placed 3D nodes, so the model sees the *augmented* scene; hide overlays first) → **Gemini Nano** via ML Kit GenAI Prompt API: `Generation.getClient()`, gate on `checkStatus()`, then either one-shot `generateContent(generateContentRequest(ImagePart(bitmap), TextPart(question)) {})` or, as the demo does, **streamed** `generateContentStream(request)` (a `Flow` of text deltas grown into the answer card — see `AskEngine.askStream`). Camera-only variant: `frame.cameraImage()` (in `onSessionUpdated`, current frame only) → `Image.toArgbBitmap(rotationDegrees)` off the main thread. The question is a free-form user field (blank → default prompt); long-press drops a prop (`hitTest` → `AnchorNode` + `ModelNode`). Answers are **world-anchored**: the tap is hit-tested (tracked `Point`, or a `Plane` that is `HORIZONTAL_UPWARD_FACING` **and** in-polygon), `hit.createAnchor()` pins an `AnchorNode { ViewNode { … } }` card at that pose (capped list, explicit width on the content, **no facing rotation for those hits** — a horizontal/Point hit pose already faces the device and a `ViewNode` faces its own +Z; this does NOT hold on a VERTICAL plane, whose Z+ lies in the wall and would pin the card edge-on, so wall taps are filtered out and fall through to the screen-space card — pin them with `wallFacingRotation()` if you want them; `viewNodeWindowManager` required, `createAnchor()` wrapped since it throws when anchors pile up, hidden during the capture so the model never re-reads its own answers); no hit falls back to the screen-space card. Fully on-device (AICore, Pixel 8+); emulators are always `UNAVAILABLE` — swap in a canned engine under QA mode (see `AskEngine.kt`). Markdown recipe: `samples/recipes/point-and-ask.md`.

## AR recording / playback
[`ARRecordPlaybackDemo.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/demos/ARRecordPlaybackDemo.kt) — `val recorder = rememberARRecorder(); recorder.start(file); recorder.stop()`. To replay, pass `playbackDataset = file` to `ARSceneView`.

## Photo and video capture (rendered scene → JPEG / MP4, no MediaProjection)
[`SceneCapture.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/recording/SceneCapture.kt), the shared Take photo / Record video actions of the demo app's `DemoScaffold` — `val recorder = rememberSceneRecorder(); ARSceneView(surfaceMirrorer = recorder.surfaceMirrorer)` (also on `SceneView`). Photo: `scope.launch { recorder.savePhoto() }` — a full-resolution `PixelCopy` saved as JPEG (or `PhotoFormat.PNG`). Video: `recorder.startRecording(settings = VideoSettings(recordAudio = true, maxDurationMillis = 60_000))`, then `recorder.stopRecording()` — an H.264 MP4 at 1080p30, the mic only when the app holds `RECORD_AUDIO`; the recording also ends, file finalised, when the screen goes to the background (`ON_STOP`) or the scene leaves composition. A watermark: `recorder.overlay = CaptureOverlay { canvas, width, height -> … }`, drawn on photos and on video frames (API 29+), never on screen. Captures exactly what Filament renders (in AR, camera feed + virtual content composited) — never the Compose UI — with no MediaProjection consent dialog or foreground service. **Unlike `ARRecorder`** (which records an ARCore dataset for deterministic replay), this produces a shareable photo or video. For a custom encoder, `rememberSurfaceMirrorer()` + `startMirroring(surface)` / `stopMirroring(surface)` is the layer underneath.

## Cross-platform parity

Apple (`SceneViewSwift`) and Web (`sceneview-web`) expose the same node names
but with platform-idiomatic shapes (SwiftUI `@SceneBuilder`, JavaScript
declarative API). **Don't copy-paste between platforms.** The platform docs
are:

- iOS: [`docs/docs/cheatsheet-ios.md`](https://github.com/sceneview/sceneview/blob/main/docs/docs/cheatsheet-ios.md)
- Web: [`sceneview-web README`](https://www.npmjs.com/package/sceneview-web)
- Flutter / RN: bridge packages; both render via the native SceneView underneath.

## Place a model in AR

Use `AutoPlacementScene` with `AutoPlacementModel`. It places the model automatically at its authored size, lets you drag along the floor past detected plane edges, and accepts gestures anywhere on screen.

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.sceneview.ar.AutoPlacementModel
import io.github.sceneview.ar.AutoPlacementScene
import io.github.sceneview.ar.rememberAutoPlacementState
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

@Composable
fun ARPlacementScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val instance = rememberModelInstance(modelLoader, "models/chair.glb")
    val state = rememberAutoPlacementState()

    AutoPlacementScene(
        assetReady = instance != null,
        modifier = Modifier.fillMaxSize(),
        state = state,
        engine = engine,
        modelLoader = modelLoader,
    ) { placement ->
        instance?.let { AutoPlacementModel(placement, state, it) }
    }
}
```

Put the GLB in `src/main/assets/models/`. After 600 ms of tracked search without a usable surface, the horizontal flow tries an estimated floor and eases to the refined pose. Set `state.estimatedFallbackMillis = null` to wait for a detected surface. Call `state.recenter()` to move the object back in front of the camera.
Set `surface = PlacementSurface.WALL` for wall placement. For a host toolbar or bottom sheet,
pass its occupied space as `coachingContentPadding` to keep built-in cards clear of it.

**Manual placement.** `PlacementScene` and `WallPlacementScene` are deprecated with a
warning. Existing flows that need multiple objects placed by tap can keep using them.
See [From PlacementScene to AutoPlacementScene](https://sceneview.github.io/docs/migration/#from-placementscene-to-autoplacementscene).

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

## Recipe: open a `.3mf` print (#3482)

A `.3mf` is what ChatGPT, every slicer and every image-to-print flow emit for a printable model.
**There is no 3MF API to learn.** `ModelLoader` sniffs the payload by its ZIP magic and converts it
to GLB in memory, so the file goes through the loader entry point you already use — the code below
is the same code you would write for a `.glb`:

```kotlin
@Composable
fun PrintViewer(location: String) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    SceneView(modifier = Modifier.fillMaxSize(), engine = engine, modelLoader = modelLoader) {
        // `location` is a .3mf, a .glb or a .gltf — an asset path, a URL, or a file:// / content://
        // URI shared in from another app. Same call for all of them.
        rememberModelInstance(modelLoader, location)?.let { instance ->
            ModelNode(modelInstance = instance, scaleToUnits = 0.3f)
        }
    }
}
```

Identical in AR: an anchored `ModelNode` takes the same instance, because by the time a node sees
it a 3MF *is* a glTF model.

**Do not** add a format check, a branch, or a "3MF support" flag before calling the loader — and do
not decide the format from the file's extension or its MIME type. Android does not reliably report
either: measured on an emulator, a `.3mf` arriving through the share sheet has
`application/octet-stream` as its type *and* no queryable display name at all. When you genuinely
need to know what a buffer holds, ask its bytes, on any platform:

```kotlin
import io.github.sceneview.core.threemf.ThreeMfLoader

fun describe(bytes: ByteArray): String =
    if (ThreeMfLoader.isThreeMf(bytes)) {
        val model = ThreeMfLoader.parse(bytes)
        "a print: ${model.triangleCount} triangles, unit ${model.unit.id}"
    } else {
        "not a 3MF"
    }
```

**Drop `scaleToUnits` when the real size is the point.** Conversion scales the file's declared unit
to metres, so a 60 mm print is 0.06 scene units and lands life-size in AR with no magic number;
`scaleToUnits` overrides that to frame the model at a fixed size, which is what a viewer wants and
what an AR preview of a real object does not.

Full behaviour — Z-up to Y-up, flat per-face normals, `<basematerials>` and `<colorgroup>` colours,
`ThreeMfModel` — is in `llms.txt § 3MF`. The demo app's "Open with" path
([`OpenedModel.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/OpenedModel.kt))
is the worked example of receiving one of these files from another app.
