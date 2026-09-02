<!--
  GENERATED FILE — DO NOT EDIT.
  Source of truth: /llms.txt  (SceneView 4.33.0)
  Regenerate:      node tools/generate-gpt-knowledge.js
  Drift is caught in CI (ci.yml -> repo-hygiene). Edit llms.txt instead.
  See issue #2724.
-->

# SceneView — Recipes & Sample Index

> Copy-paste recipes and the full demo/sample catalog.
> Auto-generated from `llms.txt` (SceneView 4.33.0). This is a slice of the machine-readable API reference — the same content an AI reads to generate SceneView code.

## Recipes — "I want to..."

### Record the scene to MP4 (in-app, no MediaProjection)

```kotlin
val surfaceMirrorer = rememberSurfaceMirrorer()
SceneView(surfaceMirrorer = surfaceMirrorer, ...)  // or ARSceneView — records camera feed + virtual content

// Start: point a MediaRecorder's input surface at the scene.
val recorder = MediaRecorder(context).apply {
    setVideoSource(MediaRecorder.VideoSource.SURFACE)
    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
    setVideoSize(1280, 720)
    setOutputFile(outputFile.absolutePath)
    prepare()
}
surfaceMirrorer.startMirroring(recorder.surface, width = 1280, height = 720)
recorder.start()

// Stop:
surfaceMirrorer.stopMirroring(recorder.surface)
recorder.stop(); recorder.release()
```

No consent dialog, no foreground service, no UI in the frame. Full guide: "Record the scene to MP4 — Surface mirroring".

### Show a 3D model with orbit camera

```kotlin
@Composable
fun ModelViewer() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val model = rememberModelInstance(modelLoader, "models/helmet.glb")

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        cameraManipulator = rememberCameraManipulator()
    ) {
        model?.let { ModelNode(modelInstance = it, scaleToUnits = 1f, autoAnimate = true) }
    }
}
```

### AR tap-to-place on a surface

```kotlin
@Composable
fun ARTapToPlace() {
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val model = rememberModelInstance(modelLoader, "models/chair.glb")

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = true,
        onSessionUpdated = { _, frame ->
            if (anchor == null) {
                anchor = frame.getUpdatedPlanes()
                    .firstOrNull { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                    ?.let { plane -> plane.createAnchorOrNull(plane.centerPose) }
            }
        }
    ) {
        anchor?.let { a ->
            AnchorNode(anchor = a) {
                model?.let { ModelNode(modelInstance = it, scaleToUnits = 0.5f) }
            }
        }
    }
}
```

### Point & Ask — AI explains the AR scene (on-device, offline)

Tap → capture the AR frame → **Gemini Nano on-device** (ML Kit GenAI
Prompt API, `com.google.mlkit:genai-prompt:1.0.0-beta3`, minSdk 26) → answer.
Camera-only capture below is the simpler baseline; the reference demo defaults
to the composited capture (camera + placed virtual objects — see the
"Composited variant" paragraph).
No cloud; frames never leave the device. AICore devices only (Pixel 8+,
recent flagships) — gate with `checkStatus()` and degrade honestly.

```kotlin notest external AICore / Gemini Nano dependency (Generation, FeatureStatus, ImagePart) — not on the SDK classpath
val generativeModel = remember { Generation.getClient() }   // Gemini Nano via AICore
var ready by remember { mutableStateOf(false) }
LaunchedEffect(Unit) { ready = generativeModel.checkStatus() == FeatureStatus.AVAILABLE }
// DOWNLOADABLE -> generativeModel.download() (Flow of progress); UNAVAILABLE -> explain, don't hide.

var captureRequested by remember { mutableStateOf(false) }
ARSceneView(
    engine = engine, modelLoader = modelLoader,
    onSessionUpdated = { _, frame ->
        // Must use THIS frame's CPU image — acquiring from a stored older frame throws.
        if (captureRequested && frame.camera.trackingState == TrackingState.TRACKING) {
            frame.cameraImage()?.let { image ->            // null while warming up
                captureRequested = false
                scope.launch {
                    val bitmap = withContext(Dispatchers.Default) {   // JPEG round-trip: off main
                        image.use { it.toArgbBitmap(rotationDegrees = 90) }  // ALWAYS close the Image
                    } ?: return@launch
                    try {
                        val response = generativeModel.generateContent(
                            generateContentRequest(
                                ImagePart(bitmap),
                                TextPart("What am I looking at? Answer in one short sentence.")
                            ) {}
                        )
                        answer = response.candidates.firstOrNull()?.text
                    } finally { bitmap.recycle() }
                }
            }
        }
    },
    onGestureListener = rememberOnGestureListener(
        onSingleTapConfirmed = { _, _ -> if (ready) captureRequested = true }
    ),
)
```

Gotchas: a leaked CPU `Image` stalls ARCore within a few frames (`use { }` closes
it); `toArgbBitmap` is main-thread-hostile; 90° is the portrait `ROTATION_0`
rotation; emulators never have AICore — inject a canned engine under QA mode.

Composited variant: `frame.cameraImage()` sees the **camera only** — placed 3D
nodes are invisible to the model. To let the AI see the *augmented* scene
(camera + virtual objects), capture the composited window instead:
`PixelCopy.request(activity.window, bitmap, callback, mainHandler)` — already
an upright ARGB frame (no YUV/rotation), but hide your Compose overlays first
or they get baked into the AI's input. The reference demo uses this: long-press
places a prop, then "Is there an animal in this room?" is answered about a dog
that only exists in AR.

Streamed variant: `generateContentStream(request)` returns a `Flow` of
`GenerateContentResponse` **deltas** — concatenate `candidates.first().text`
per emission for a live "typing" card. The reference demo streams, and its
question is a free-form user field (blank falls back to the default prompt).

World-anchored variant: pin each answer where the user tapped instead of
stacking them in a screen overlay. Hit-test the tap against the latest frame,
anchor the hit, and render the card on a `ViewNode` under an `AnchorNode`:

```kotlin
val viewNodeManager = rememberViewNodeManager()

// One card per tap: the anchor ARCore refines each frame, plus the streamed text.
class AnswerPanel(val id: Int, val anchor: Anchor) { var text by mutableStateOf("") }

val panels = remember { mutableStateListOf<AnswerPanel>() }
var latestFrame by remember { mutableStateOf<Frame?>(null) }
var isTracking by remember { mutableStateOf(false) }
var nextId by remember { mutableIntStateOf(0) }
// ARCore anchors cost per frame while attached — always detach them.
DisposableEffect(Unit) { onDispose { panels.forEach { it.anchor.detach() } } }

ARSceneView(
    planeRenderer = true,                     // show where a tap will pin
    viewNodeWindowManager = viewNodeManager,  // required by ViewNode
    onSessionUpdated = { _, frame ->
        latestFrame = frame
        // Feed the gate below — without this, isTracking stays false and
        // every tap silently hit-tests nothing.
        isTracking = frame.camera.trackingState == TrackingState.TRACKING
    },
    onGestureListener = rememberOnGestureListener(
        onSingleTapConfirmed = { e, _ ->
            // Gate on camera tracking, not just the trackable's own state.
            // HORIZONTAL_UPWARD_FACING only: on a horizontal plane (and on a
            // Point) the hit pose's Z+ really does point back toward the
            // device, which is what lets the card below skip any rotation. A
            // VERTICAL plane's Z+ lies IN the wall, so the same code would pin
            // the card edge-on — handle walls with `wallFacingRotation(...)`
            // instead, or let them fall through to the screen-space card.
            val hit = latestFrame?.takeIf { isTracking }?.hitTest(e)?.firstOrNull { result ->
                val t = result.trackable
                t.trackingState == TrackingState.TRACKING &&
                    (t is Point || (t is Plane &&
                        t.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                        t.isPoseInPolygon(result.hitPose)))
            }
            val panel = hit?.let { AnswerPanel(nextId++, it.createAnchor()) }
            if (panel != null) panels += panel
            // …then run the ask, streaming its deltas into `panel` when non-null,
            // and into the screen-space card when the tap hit nothing trackable.
        }
    ),
) {
    panels.forEach { panel ->
        key(panel.id) {
            AnchorNode(anchor = panel.anchor) {          // follows ARCore's refined pose
                ViewNode(
                    windowManager = viewNodeManager,
                    unlit = true,                        // UI card: ignore scene lighting
                    position = Position(y = 0.12f),      // float above the surface
                    // No rotation — valid because the hit is horizontal/Point.
                    scale = Scale(0.15f),                // ViewNode renders at 250 px/m
                    // No parent to measure against — size the content explicitly.
                ) { Card(Modifier.width(320.dp)) { Text(panel.text) } }
            }
        }
    }
}
```

Gotchas: give the `ViewNode`'s content an explicit width (it has no parent to
measure against); **do not add a facing rotation for horizontal-plane or Point
hits** — there the ARCore hit pose is already oriented "Z+ … roughly toward the
user's device" and a `ViewNode`'s quad faces its own +Z, so identity faces the
user and any extra yaw turns the card away. **This does not hold on a VERTICAL
plane**: its Y+ is the wall normal and its Z+ lies in the wall surface, so
reusing the hit pose pins the card edge-on. Either filter the hit to
`Plane.Type.HORIZONTAL_UPWARD_FACING` (above) and let wall taps fall through to
the screen-space card, or orient walls explicitly with `wallFacingRotation()`
from `arsceneview`. Also: `createAnchor()` throws once too many anchors exist,
so cap the panels and wrap it; a tap that hits nothing trackable must fall back
to the screen-space card; and with the composited capture above, hide the
anchored cards during the capture (`isVisible = false`) or the model reads
its own earlier answers back as part of the next question.

Working demo: `point-and-ask` (`PointAndAskDemo.kt` + `AskEngine.kt`). Full
recipe (one-shot + composited + streamed + world-anchored variants):
`samples/recipes/point-and-ask.md`.

### Procedural geometry (no model files)

```kotlin
@Composable
fun ProceduralScene() {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val material = remember(materialLoader) {
        materialLoader.createColorInstance(Color.Gray, metallic = 0f, roughness = 0.4f)
    }

    SceneView(modifier = Modifier.fillMaxSize(), engine = engine) {
        CubeNode(size = Size(0.5f), materialInstance = material)
        SphereNode(radius = 0.3f, materialInstance = material, position = Position(x = 1f))
        CylinderNode(radius = 0.2f, height = 0.8f, materialInstance = material, position = Position(x = -1f))
    }
}
```

### Embed Compose UI inside 3D space

```kotlin
@Composable
fun ComposeIn3D() {
    val engine = rememberEngine()
    val windowManager = rememberViewNodeManager()

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        viewNodeWindowManager = windowManager
    ) {
        ViewNode(windowManager = windowManager) {
            Card { Text("Hello from 3D!") }
        }
    }
}
```

### Animated model with play/pause

```kotlin
@Composable
fun AnimatedModel() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val model = rememberModelInstance(modelLoader, "models/character.glb")
    var isPlaying by remember { mutableStateOf(true) }

    Column {
        SceneView(modifier = Modifier.weight(1f).fillMaxWidth(), engine = engine, modelLoader = modelLoader) {
            model?.let { ModelNode(modelInstance = it, autoAnimate = isPlaying) }
        }
        Button(onClick = { isPlaying = !isPlaying }) {
            Text(if (isPlaying) "Pause" else "Play")
        }
    }
}
```

### Multiple models positioned in a scene

```kotlin
@Composable
fun MultiModelScene() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val helmet = rememberModelInstance(modelLoader, "models/helmet.glb")
    val car = rememberModelInstance(modelLoader, "models/car.glb")

    SceneView(modifier = Modifier.fillMaxSize(), engine = engine, modelLoader = modelLoader) {
        helmet?.let { ModelNode(modelInstance = it, scaleToUnits = 0.5f, position = Position(x = -0.5f)) }
        car?.let { ModelNode(modelInstance = it, scaleToUnits = 0.5f, position = Position(x = 0.5f)) }
    }
}
```

### Interactive model with tap and gesture

```kotlin
@Composable
fun InteractiveModel() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val model = rememberModelInstance(modelLoader, "models/helmet.glb")
    var selectedNode by remember { mutableStateOf<String?>(null) }

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine, modelLoader = modelLoader,
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { _, node -> selectedNode = node?.name }
        )
    ) {
        model?.let {
            ModelNode(modelInstance = it, scaleToUnits = 1f, isEditable = true, apply = {
                scaleGestureSensitivity = 0.3f
                editableScaleRange = 0.2f..2.0f
            })
        }
    }
}
```

### HDR environment with custom lighting

```kotlin
@Composable
fun CustomEnvironment() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val model = rememberModelInstance(modelLoader, "models/helmet.glb")
    val environment = rememberEnvironment(environmentLoader) {
        environmentLoader.createHDREnvironment("environments/sunset.hdr")!!
    }

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine, modelLoader = modelLoader,
        environment = environment,
        mainLightNode = rememberMainLightNode(engine) { intensity = 100_000f },
        cameraManipulator = rememberCameraManipulator()
    ) {
        model?.let { ModelNode(modelInstance = it, scaleToUnits = 1f) }
    }
}
```

### Post-processing effects (bloom, DoF, SSAO)

```kotlin
@Composable
fun PostProcessingScene() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val model = rememberModelInstance(modelLoader, "models/helmet.glb")

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine, modelLoader = modelLoader,
        cameraManipulator = rememberCameraManipulator(),
        view = rememberView(engine) {
            engine.createView().apply {
                bloomOptions = bloomOptions.apply { enabled = true; strength = 0.3f }
                depthOfFieldOptions = depthOfFieldOptions.apply { enabled = true; cocScale = 4f }
                ambientOcclusionOptions = ambientOcclusionOptions.apply { enabled = true }
            }
        }
    ) {
        model?.let { ModelNode(modelInstance = it, scaleToUnits = 1f) }
    }
}
```

### Lines, paths, and curves

```kotlin
@Composable
fun LinesAndPaths() {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val material = remember(materialLoader) {
        materialLoader.createColorInstance(colorOf(r = 0f, g = 0.7f, b = 1f))
    }

    SceneView(modifier = Modifier.fillMaxSize(), engine = engine) {
        LineNode(start = Position(-1f, 0f, 0f), end = Position(1f, 0f, 0f), materialInstance = material)
        PathNode(
            points = listOf(Position(0f, 0f, 0f), Position(0.5f, 1f, 0f), Position(1f, 0f, 0f)),
            materialInstance = material
        )
    }
}
```

### World-space text labels

```kotlin
@Composable
fun TextLabels() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val model = rememberModelInstance(modelLoader, "models/helmet.glb")

    SceneView(modifier = Modifier.fillMaxSize(), engine = engine, modelLoader = modelLoader) {
        model?.let { ModelNode(modelInstance = it, scaleToUnits = 1f) }
        TextNode(text = "Damaged Helmet", position = Position(y = 0.8f))
    }
}
```

### AR image tracking

```kotlin
@Composable
fun ARImageTracking(coverBitmap: Bitmap) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    var detectedImages by remember { mutableStateOf(listOf<AugmentedImage>()) }

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine, modelLoader = modelLoader,
        sessionConfiguration = { session, config ->
            config.augmentedImageDatabase = AugmentedImageDatabase(session).also { db ->
                db.addImage("cover", coverBitmap)
            }
        },
        onSessionUpdated = { _, frame ->
            detectedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)
                .filter { it.trackingState == TrackingState.TRACKING }
        }
    ) {
        detectedImages.forEach { image ->
            AugmentedImageNode(augmentedImage = image) {
                rememberModelInstance(modelLoader, "models/drone.glb")?.let {
                    ModelNode(modelInstance = it, scaleToUnits = 0.2f)
                }
            }
        }
    }
}
```

### AR face tracking

```kotlin
@Composable
fun ARFaceTracking() {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    var trackedFaces by remember { mutableStateOf(listOf<AugmentedFace>()) }
    val faceMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(colorOf(r = 1f, g = 0f, b = 0f, a = 0.5f))
    }

    ARSceneView(
        sessionFeatures = setOf(Session.Feature.FRONT_CAMERA),
        // REQUIRED: FRONT_CAMERA alone only makes the front camera eligible — the session
        // stays on the default BACK camera until sessionCameraConfig selects a FRONT config.
        // Without ::frontCameraConfig, AugmentedFaceMode.MESH3D yields no faces and no mesh.
        sessionCameraConfig = ::frontCameraConfig,
        sessionConfiguration = { _, config ->
            config.augmentedFaceMode = Config.AugmentedFaceMode.MESH3D
        },
        onSessionUpdated = { session, _ ->
            trackedFaces = session.getAllTrackables(AugmentedFace::class.java)
                .filter { it.trackingState == TrackingState.TRACKING }
        }
    ) {
        trackedFaces.forEach { face ->
            AugmentedFaceNode(augmentedFace = face, meshMaterialInstance = faceMaterial)
        }
    }
}
```

### Generate a 3D model with AI (Tripo) and place it in AR

When no existing asset fits, generate one. The `sceneview-mcp` server's `generate_3d_model` tool
creates a GLB from a text prompt or a source image via the Tripo AI API (BYOK — set `TRIPO_API_KEY`
in the MCP client config; `quality: "fast"` = P1 low-poly AR-ready ~25–30 s, `quality: "hd"` = H3.1
quad topology up to ~100 s). Full flow: generate → download → `rememberModelInstance` → place in AR.

```
1. MCP: generate_3d_model({ prompt: "a low-poly cactus in a striped pot" })
   → returns a GLB download URL (⚠️ expires ~5 minutes) + license/attribution metadata
2. Download the GLB IMMEDIATELY and self-host it — e.g. save as app/src/main/assets/models/cactus.glb
```

```kotlin
@Composable
fun ARGeneratedModel() {
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    // The GLB generated by generate_3d_model, bundled into assets (preferred: URLs expire).
    val model = rememberModelInstance(modelLoader, "models/cactus.glb")

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = true,
        onSessionUpdated = { _, frame ->
            if (anchor == null) {
                anchor = frame.getUpdatedPlanes()
                    .firstOrNull { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                    ?.let { plane -> plane.createAnchorOrNull(plane.centerPose) }
            }
        }
    ) {
        anchor?.let { a ->
            AnchorNode(anchor = a) {
                model?.let { ModelNode(modelInstance = it, scaleToUnits = 0.5f) }
            }
        }
    }
}
```

Prototyping shortcut: `rememberModelInstance(modelLoader, "https://…/generated.glb")` loads straight
from the URL — fine for a quick preview, but never ship it (the Tripo URL expires ~5 minutes after
generation; bundle the file or re-host it yourself). Prefer `search_models` (free, Sketchfab) before
generating — generation consumes the user's own Tripo credits.

---

<!-- BEGIN GENERATED DEMOS — DO NOT EDIT — run samples/android-demo/scripts/collate-demos.sh -->

## Sample app demos (Android)

Every demo bundled in `samples/android-demo` (the Play Store showcase).
Each demo is addressable through the deep-link router as `sceneview://demo/<id>`
and surfaces in the Samples tab. This section is generated from the per-demo
fragments under `samples/android-demo/src/main/java/io/github/sceneview/demo/fragments/`
by `samples/android-demo/scripts/collate-demos.sh` — never edit between the markers (#1871).

### 3D Basics

- `model-viewer` — Models. Any glTF, HDR lighting, one tap to AR.
- `animation-physics` — Animation & Physics. Skeletal clips plus rigid-body physics.
- `geometry` — Geometry Primitives. Cube, sphere, cylinder and plane primitives.

### Lighting & Environment

- `lighting` — Lighting. Light types with a movable orbiting light.
- `lighting-lab` — Lighting Lab. Sky, HDR environment, reflections, post-FX.
- `fog` — Fog. Linear, exponential and height fog.
- `contact-shadow-preview` — Contact Shadow Preview. Procedural contact shadow, grounded vs floating.

### Content

- `two-d-in-three-d` — 2D in 3D. Text, image, video and billboard quads in 3D.
- `lines-paths` — Lines & Paths. Polylines, helix, grids and circles.

### Interaction

- `camera-gestures` — Camera & Gestures. Orbit, pan, zoom and per-node edit gestures.
- `picking-collision` — Picking & Collision. Ray hit-test with picked ViewNode overlays.
- `gesture-feedback-preview` — Gesture Feedback Preview. On-model rotation ring, scale badge and drag shadow.

### Advanced

- `materials` — Materials. PBR extensions and runtime material streaming.
- `custom-geometry` — Custom Geometry. A torus knot generated vertex by vertex at runtime.
- `spatial-audio` — Spatial Audio. Positional sound that pans as you orbit.
- `splat-preview` — Gaussian Splatting. 3D Gaussian splat radiance-field rendering.
- `double-pendulum` — Double Pendulum. Chaotic two-link physics from shared KMP.
- `video-recording` — Video Recording. Record the scene to MP4 in-app.
- `secondary-camera` — Secondary Camera (PiP). Picture-in-picture second camera view.
- `debug-overlay` — Debug Overlay. Live FPS and render stats overlay.

### Augmented Reality

- `ar-placement` — Tap to Place. Tap a plane to place and move a model.
- `placement-scene` — Placement Scene. One-line tap-to-place AR.
- `ar-depth-occlusion` — Depth Occlusion. Real-world depth hides virtual objects.
- `ar-instant-placement` — Instant Placement. Place before plane detection converges.
- `ar-image` — Image Tracking. Detect and track reference images.
- `ar-face` — Augmented Faces. Face mesh tracking with 3D overlays.
- `ar-depth-collider` — Depth Collider. Virtual balls bounce off the real floor.
- `ar-plane-renderer-v2` — Plane Renderer V2. Depth, PBR and HDR plane renderer, V1↔V2.
- `ar-plane-node` — Plane Lifecycle. PlaneNode lifecycle: added, updated, removed.
- `ar-point-cloud` — Point Cloud. World-space feature points as a point cloud.
- `ar-depth-visualization` — Depth Visualization. False-color depth map blended with camera.
- `ar-raw-depth-point-cloud` — Raw Depth Point Cloud. Confidence-filtered raw depth point cloud.
- `ar-depth-of-field` — AR Depth of Field. Tap to focus, real-world bokeh blur.
- `ar-people-occlusion` — People Occlusion. Real people hide virtual objects.
- `ar-fog` — AR Fog. Distance fog over real and virtual geometry.
- `ar-orbital` — Orbital AR. Models orbit around you in AR.
- `ar-pose` — Pose Placement. Free pose positioning of a node in AR.
- `ar-ml-object-label` — ML Kit Object Labels. ML Kit detection with anchored 3D labels.
- `ar-body-tracker` — AR Body Tracker. Live MediaPipe pose skeleton on the AR feed.
- `point-and-ask` — Point & Ask. On-device Gemini Nano explains the AR scene.
- `ar-image-stabilization` — Image Stabilization (EIS). Electronic stabilization of the AR feed.
- `ar-cloud-anchor` — Cloud Anchors. Persistent anchors shared across devices.
- `ar-collaborative` — Collaborative AR. Multi-user session sync over any transport.
- `ar-streetscape` — Streetscape Geometry. Geospatial building and terrain meshes.
- `ar-scene-mesh` — Scene Mesh. Color-coded Streetscape terrain and buildings.
- `ar-terrain` — Terrain Anchors. Anchor models on geospatial terrain.
- `ar-rooftop` — Rooftop Anchors. Anchor models on geospatial rooftops.
- `ar-scene-semantics` — Scene Semantics. 12-class outdoor scene labeling HUD.
- `ar-record-playback` — AR Recording. Record an AR session and replay it anywhere.
- `ar-rerun` — Rerun Debug. Stream pose and planes to the Rerun viewer.
- `ar-measure` — Measure. Tap two points, read the distance.
- `wall-placement` — Wall Placement. Mount a TV on a wall, floor-to-wall aligned.
- `ar-hand-tracking` — Hand Tracking (Jetpack XR). Hand skeleton on Android XR headsets.
- `ar-xr-face` — Face Tracking (Jetpack XR). Face mesh on Android XR headsets.

<!-- END GENERATED DEMOS -->

---

## Sketchfab streaming for samples (#1152)

SceneView's sample app (`samples/android-demo`) streams CC-BY licensed glTF models from Sketchfab on demand instead of bundling 30 MB of GLBs in the APK. The same pattern works in any SceneView consumer.

**Entry point:** `SketchfabAssetResolver.getInstance(context).resolve(slug)` returns a local `File` (Android) or `URL` (iOS) ready for `rememberModelInstance(modelLoader, "file://...")`.

**Curated registry:** `SampleAssets.byCategory["<category>"]` — categories are `solar`, `gallery`, `animation`, `park`, `ar_placement`, `physics`, `materials`. Each entry is CC-BY 4.0, validated at construction time. Every entry has a `fallbackBundledPath` (a small bundled GLB / USDZ) that the resolver serves when the network or key is unavailable.

```kotlin notest demo-app pattern — SketchfabAssetResolver/SampleAssets live in the demo app (samples/android-demo, sketchfab package), not the SDK
@Composable
fun MyDemo() {
    val context = LocalContext.current
    val resolver = remember { SketchfabAssetResolver.getInstance(context) }
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    // Warm the category cache so the first frame doesn't pop in.
    LaunchedEffect(Unit) {
        runCatching { resolver.prefetchAll("animation") }
    }

    val slug = remember { SampleAssets.byCategory["animation"].orEmpty().first() }

    // Resolve to a local file (null while downloading / staging the fallback).
    val file: File? by produceState<File?>(initialValue = null, key1 = slug.uid) {
        value = runCatching { resolver.resolve(slug) }.getOrNull()
    }

    val instance = file?.let {
        rememberModelInstance(modelLoader, "file://${it.absolutePath}")
    }

    SceneView(modifier = Modifier.fillMaxSize(), engine = engine, modelLoader = modelLoader) {
        instance?.let {
            ModelNode(
                modelInstance = it,
                scaleToUnits = slug.scaleToUnits,
                autoAnimate = slug.hasBakedAnimation,
            )
        }
    }
}
```

**Hard rules.** Never open a Sketchfab WebView / external link (Sketchfab is an invisible CDN, not a UX surface). Always show the `slug.author` byline in a Credits sheet — that's the CC-BY 4.0 contract. Never ship a build that depends on the network to render the first frame — the resolver's per-slug fallback path keeps the demo working when `SketchfabConfig.apiKey == null`.

**LRU cache.** `Context.cacheDir/sketchfab/` (250 MB samples-side cap, evicted oldest-first by `lastModified`). `prefetchAll(category)` fans every slug in the category out in parallel.

**Bounds sanity check.** The resolver verifies the `glTF` magic header + file size ≥ 12 B before returning a streamed file; truncated downloads / wrong-format payloads fall back to the bundled asset.

Full recipe + add-a-slug checklist: `docs/docs/recipes/sketchfab-streaming.md`. Pairs with the `DemoScaffold` v2 modal bottom-sheet pattern below.

---

## DemoScaffold v2 — full-screen scene + ModalBottomSheet controls (#1154)

`DemoScaffold` is the shared scaffold every sample-app demo uses. v2 ([PR #1169](https://github.com/sceneview/sceneview/pull/1169)) renders the 3D / AR scene **full-screen** under the top app bar, with a `Tune` FAB pinned bottom-right that opens a `ModalBottomSheet` containing the demo's controls.

```kotlin notest DemoScaffold and DemoBottomOverlayScope live in samples/android-demo, which is deliberately not on :snippets-check's classpath (it depends on the libraries, not the sample app)
@Composable
fun DemoScaffold(
    title: String,
    onBack: () -> Unit,
    controls: (@Composable ColumnScope.() -> Unit)? = null,
    bottomOverlay: (@Composable DemoBottomOverlayScope.() -> Unit)? = null,
    scene: @Composable BoxScope.() -> Unit,
)
```

- `controls == null` → scene fills the whole viewport, no FAB.
- `controls != null` → FAB + peek chip + sheet. Controls render inside a vertically-scrolling `Column` so v1 side-panel `controls = { ... }` blocks port unchanged.
- `bottomOverlay != null` → a floating bottom banner / status pill / answer card, laid out **by the scaffold** so it can never be masked by the bottom-end Settings FAB.

**Gestures:** tap FAB or peek chip → opens sheet; long-press peek chip → toggles `DemoSettings.qaMode` (deterministic screenshot mode); drag handle / outside tap / back → dismiss. AR sessions keep tracking underneath while the sheet is open.

**Bottom overlays go in the slot, never in `scene` at a bare `Alignment.BottomCenter` ([#2779](https://github.com/sceneview/sceneview/issues/2779)).** The FAB is scaffold chrome and its very existence depends on `controls`, so a demo placing its own bottom-center overlay cannot know whether — or by how much — it must get out of the way. Pixel 9 device QA found demos rendering status text straight under the FAB, words masked. The slot's receiver carries the one number that fixes it:

```kotlin
DemoScaffold(
    title = stringResource(R.string.demo_my_title),
    onBack = onBack,
    controls = { /* … */ },              // ← decides whether a FAB exists at all
    bottomOverlay = {
        // Full-width card / banner: only its end edge can reach the FAB.
        Surface(modifier = Modifier.fillMaxWidth().padding(end = settingsFabReservedSpace)) { /* … */ }

        // Centred content-width pill: STILL end-only, centred inside what is left.
        // A symmetric inset spends the reserve twice to protect one corner and
        // starves the pill (73 dp vs 242 dp on a 411 dp screen, #3229).
        Box(
            modifier = Modifier.fillMaxWidth().padding(end = settingsFabReservedSpace),
            contentAlignment = Alignment.Center,
        ) { Text(text = status) }

        // Or just use the shared one, which is exactly this idiom:
        DemoStatusBanner(text = status, tone = DemoStatusTone.Progress)
    },
) { /* scene */ }
```

`settingsFabReservedSpace` resolves to the **measured** width of the Settings cluster, floored at `SETTINGS_FAB_RESERVED_SPACE` (104 dp), when the demo passes `controls`, and to `0.dp` when it does not. It is measured rather than assumed because the widest thing in that corner is the peek chip, and a chip is text — its width follows the font scale, the locale and the demo's own `peekHeader` — computed once, scaffold-side, from the same condition that composes the FAB. A demo whose `controls` is itself conditional (`controls = if (DemoSettings.qaMode) { … } else null`) therefore gets the correct inset with no duplicated condition to drift.

**Picker pattern.** The horizontal-scroll FilterChip row in the controls sheet picks between bundled / streamed assets. Used in `OrbitalARDemo`, `ModelViewerDemo`, `AnimationPhysicsDemo`, `MaterialsDemo`, `ARPlacementDemo`, `ARInstantPlacementDemo`:

```kotlin notest demo-app pattern — SampleAssets/selectedSlug live in the demo app (samples/android-demo), not the SDK
controls = {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedSlug == null,
            onClick = { selectedSlug = null },
            label = { Text("Bundled") },
        )
        SampleAssets.byCategory["ar_placement"].orEmpty().forEach { slug ->
            FilterChip(
                selected = selectedSlug?.uid == slug.uid,
                onClick = { selectedSlug = slug },
                label = { Text(slug.displayName) },
            )
        }
    }
}
```

Full recipe: `docs/docs/recipes/demo-settings-sheet.md`.

---
