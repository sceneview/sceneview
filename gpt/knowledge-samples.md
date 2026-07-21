<!--
  GENERATED FILE — DO NOT EDIT.
  Source of truth: /llms.txt  (SceneView 4.24.0)
  Regenerate:      node tools/generate-gpt-knowledge.js
  Drift is caught in CI (ci.yml -> repo-hygiene). Edit llms.txt instead.
  See issue #2724.
-->

# SceneView — Recipes & Sample Index

> Copy-paste recipes and the full demo/sample catalog.
> Auto-generated from `llms.txt` (SceneView 4.24.0). This is a slice of the machine-readable API reference — the same content an AI reads to generate SceneView code.

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

```kotlin
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
Working demo: `point-and-ask` (`PointAndAskDemo.kt` + `AskEngine.kt`). Full
recipe (one-shot + composited + streamed variants):
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

- `animation-physics` — Animation & Physics. Skeletal animation playback and rigid-body simulation.
- `geometry` — Geometry Primitives. Cube, sphere, cylinder, plane.
- `model-viewer` — Models. Single model, multi-model scene, and gallery.

### Lighting & Environment

- `fog` — Fog. Linear, exponential, and height fog.
- `lighting` — Lighting. Light types, plus a movable orbiting light.
- `lighting-lab` — Lighting Lab. Sky, environment, reflections, and post-FX.

### Content

- `lines-paths` — Lines & Paths. Polylines, helix, grids, and circles.
- `two-d-in-three-d` — 2D in 3D. Text, image, video, and billboard quads.

### Interaction

- `camera-gestures` — Camera & Gestures. Manipulator modes and per-node edit gestures.
- `picking-collision` — Picking & Collision. Ray hit-test and interactive ViewNode overlays.

### Advanced

- `custom-geometry` — Custom Geometry. Composite meshes and shape extrusion.
- `debug-overlay` — Debug Overlay. Performance stats overlay.
- `double-pendulum` — Double Pendulum. Chaotic two-link physics, shared KMP simulation.
- `materials` — Materials. PBR extension showcase, runtime material streaming, and occlusion material.
- `secondary-camera` — Secondary Camera (PiP). Picture-in-picture camera view.
- `spatial-audio` — Spatial Audio. 3D positional sound that pans as you orbit.
- `splat-preview` — Gaussian Splatting. Render a 3D Gaussian Splat radiance-field cloud.
- `video-recording` — Video Recording. Record the scene to MP4 in-app — no MediaProjection.

### Augmented Reality

- `ar-body-tracker` — AR Body Tracker. Live 2D skeleton overlay from MediaPipe Pose on the AR camera feed.
- `ar-cloud-anchor` — Cloud Anchors. Persistent multi-user anchors.
- `ar-collaborative` — Collaborative AR. Multi-user session sync over a pluggable transport.
- `ar-depth-collider` — Depth Collider. Virtual balls bounce off the real floor / table (depth-driven physics).
- `ar-depth-occlusion` — Depth Occlusion. Real-world depth masks virtual objects.
- `ar-depth-of-field` — AR Depth of Field. Tap to focus — real-world bokeh blur.
- `ar-depth-visualization` — Depth Visualization. False-color depth map with camera↔depth blend.
- `ar-face` — Augmented Faces. Face mesh tracking and overlays.
- `ar-fog` — AR Fog. Distance fog over real and virtual geometry.
- `ar-hand-tracking` — Hand Tracking (Jetpack XR). Hand skeleton on Android XR headsets.
- `ar-image` — Image Tracking. Detect and track reference images.
- `ar-image-stabilization` — Image Stabilization (EIS). EIS for smoother AR camera feed.
- `ar-instant-placement` — Instant Placement. Place models before plane detection converges.
- `ar-ml-object-label` — ML Kit Object Labels. ML Kit object detection with 3D labels anchored on real-world hits.
- `ar-orbital` — Orbital AR. Models orbit around you in a personal solar system.
- `ar-people-occlusion` — People Occlusion. Real people hide virtual objects behind them.
- `ar-placement` — Tap to Place. Place 3D models in AR.
- `ar-plane-node` — Plane Lifecycle. PlaneNode + onAdded/onUpdated/onRemoved.
- `ar-plane-renderer-v2` — Plane Renderer V2. Depth + PBR + HDR + scan-in, with live V1 ↔ V2 toggle.
- `ar-point-cloud` — Point Cloud. World-space feature points via PointCloudNode.
- `ar-pose` — Pose Placement. Free pose positioning.
- `ar-raw-depth-point-cloud` — Raw Depth Point Cloud. Confidence-filtered point cloud from raw depth.
- `ar-record-playback` — AR Recording. Record an AR session and replay it without a phone.
- `ar-rerun` — Rerun Debug. Stream camera pose and planes to the Rerun viewer.
- `ar-rooftop` — Rooftop Anchors. Anchor models on geospatial rooftops.
- `ar-scene-mesh` — Scene Mesh. Color-coded real-world geometry via ARCore Streetscape (terrain + buildings).
- `ar-scene-semantics` — Scene Semantics. 12-class outdoor scene labeling — HUD shows top-3 labels in view.
- `ar-streetscape` — Streetscape Geometry. Geospatial building and terrain meshes.
- `ar-terrain` — Terrain Anchors. Anchor models on geospatial terrain.
- `ar-xr-face` — Face Tracking (Jetpack XR). Face mesh on Android XR headsets.
- `contact-shadow-preview` — Contact Shadow Preview. Non-AR preview of the procedural contact shadow — a TV grounded on a wall and a box on the floor, with per-surface presets.
- `placement-reticle-preview` — AR Placement Reticle Preview. Non-AR preview of AR placement — reticle (searching/ready, ring/disc) and a placed model with a contact shadow.
- `placement-scene` — Placement Scene. One-line tap-to-place AR (Sceneform ArFragment parity).
- `point-and-ask` — Point & Ask. Drop 3D props, tap the augmented scene — Gemini Nano explains what it sees, fully on-device.
- `wall-placement` — Wall Placement. Mount a TV on a wall — floor↔wall edge alignment, Amazon AR-View style.

<!-- END GENERATED DEMOS -->

---

## Sketchfab streaming for samples (#1152)

SceneView's sample app (`samples/android-demo`) streams CC-BY licensed glTF models from Sketchfab on demand instead of bundling 30 MB of GLBs in the APK. The same pattern works in any SceneView consumer.

**Entry point:** `SketchfabAssetResolver.getInstance(context).resolve(slug)` returns a local `File` (Android) or `URL` (iOS) ready for `rememberModelInstance(modelLoader, "file://...")`.

**Curated registry:** `SampleAssets.byCategory["<category>"]` — categories are `solar`, `gallery`, `animation`, `park`, `ar_placement`, `physics`, `materials`. Each entry is CC-BY 4.0, validated at construction time. Every entry has a `fallbackBundledPath` (a small bundled GLB / USDZ) that the resolver serves when the network or key is unavailable.

```kotlin
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

```kotlin
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

        // Centred content-width pill: inset BOTH sides. A centred element grows
        // outwards from the middle, so reserving only the end side would shift it
        // off-centre without keeping its end edge out of the FAB band.
        Text(text = status, modifier = Modifier.padding(horizontal = settingsFabReservedSpace))
    },
) { /* scene */ }
```

`settingsFabReservedSpace` resolves to `SETTINGS_FAB_RESERVED_SPACE` (88 dp = 56 dp FAB + 2 × 16 dp gutter) when the demo passes `controls`, and to `0.dp` when it does not — computed once, scaffold-side, from the same condition that composes the FAB. A demo whose `controls` is itself conditional (`controls = if (DemoSettings.qaMode) { … } else null`) therefore gets the correct inset with no duplicated condition to drift.

**Picker pattern.** The horizontal-scroll FilterChip row in the controls sheet picks between bundled / streamed assets. Used in `OrbitalARDemo`, `ModelViewerDemo`, `AnimationPhysicsDemo`, `MaterialsDemo`, `ARPlacementDemo`, `ARInstantPlacementDemo`:

```kotlin
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
