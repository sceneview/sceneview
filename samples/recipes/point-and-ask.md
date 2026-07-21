# Recipe: Point & Ask — explain what the camera sees (on-device AI)

**Intent:** "Build an AR app where the user taps and AI explains what the camera sees"

Fully offline: the AR camera frame goes to **Gemini Nano on-device** (ML Kit GenAI
Prompt API via AICore) — no cloud, frames never leave the device.

## Android (Kotlin + Jetpack Compose)

```gradle
// minSdk 26+ (demo app uses 28). No model asset in the APK — Gemini Nano lives
// in the system AICore app (Pixel 8+, recent flagships).
implementation("com.google.mlkit:genai-prompt:1.0.0-beta3")
```

```kotlin
@Composable
fun PointAndAsk() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val scope = rememberCoroutineScope()

    // Gemini Nano client — gate on availability before letting the user ask.
    val generativeModel = remember { Generation.getClient() }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        ready = generativeModel.checkStatus() == FeatureStatus.AVAILABLE
        // DOWNLOADABLE -> offer generativeModel.download() (a Flow of progress);
        // UNAVAILABLE  -> unsupported device: show an explanation, don't hide the UI.
    }
    DisposableEffect(Unit) { onDispose { generativeModel.close() } }

    var captureRequested by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            onSessionUpdated = { _, frame ->
                // Serve the pending tap with THIS frame's CPU image — acquiring
                // from a stored older frame throws once the session advances.
                if (captureRequested &&
                    frame.camera.trackingState == TrackingState.TRACKING
                ) {
                    frame.cameraImage()?.let { image ->   // null while warming up
                        captureRequested = false
                        scope.launch {
                            // JPEG round-trip — keep it OFF the main thread, and
                            // always close the Image (a leaked CPU image stalls
                            // ARCore within a few frames).
                            val bitmap = withContext(Dispatchers.Default) {
                                image.use { it.toArgbBitmap(rotationDegrees = 90) }
                            } ?: return@launch
                            try {
                                val response = generativeModel.generateContent(
                                    generateContentRequest(
                                        ImagePart(bitmap),
                                        TextPart("What am I looking at? Answer in one short sentence.")
                                    ) {}
                                )
                                answer = response.candidates.firstOrNull()?.text
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { _, _ -> if (ready) captureRequested = true }
            ),
        )

        answer?.let {
            Surface(
                Modifier.align(Alignment.BottomCenter).padding(16.dp),
                shape = MaterialTheme.shapes.large,
            ) { Text(it, Modifier.padding(16.dp)) }
        }
    }
}
```

Key imports: `io.github.sceneview.ar.arcore.cameraImage`,
`io.github.sceneview.ar.arcore.toArgbBitmap`, `com.google.mlkit.genai.prompt.*`,
`com.google.mlkit.genai.common.FeatureStatus`.

### Streamed answers (progressive display)

`generateContent` above returns the full answer in one shot — simplest to start
with. For a live "typing" card, use `generateContentStream`: it emits text
**deltas** to concatenate as they arrive (this is what the reference demo does):

```kotlin
var text = ""
generativeModel.generateContentStream(
    generateContentRequest(ImagePart(bitmap), TextPart(question)) {}
).collect { chunk ->
    chunk.candidates.firstOrNull()?.text?.let { delta ->
        text += delta
        answer = text          // recomposes the card per delta
    }
}
```

### Composited capture — let the AI see your virtual objects

`frame.cameraImage()` above captures the **camera image only** — placed 3D nodes are
invisible to the model. To let the AI see the *augmented* scene (camera + virtual
objects, exactly what the user sees), capture the composited window with
[`PixelCopy`](https://developer.android.com/reference/android/view/PixelCopy) instead:

```kotlin
// Hide your Compose overlays first (a state flag + one-frame settle delay), or
// they get baked into the AI's input image.
val activity = context.findActivity() ?: return   // unwrap Activity from ContextWrapper
val decor = activity.window.decorView
val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
PixelCopy.request(activity.window, bitmap, { result ->
    if (result == PixelCopy.SUCCESS) ask(bitmap) else bitmap.recycle()
}, Handler(Looper.getMainLooper()))
```

Trade-offs: no YUV conversion or rotation mapping needed (the copy is already an
upright ARGB screen frame), but every visible overlay ends up in the image — hide
them for the capture. Use `cameraImage()` when you want the raw camera view only;
use `PixelCopy` when the question is about the augmented scene ("Is there an
animal in this room?" after placing a virtual dog).

### World-anchored answers — pin each answer where the user tapped

Instead of stacking answers in a screen-space overlay, pin each one **in the room**
at the tapped surface: hit-test the tap, anchor the hit, and render the card on a
`ViewNode` under an `AnchorNode`. It holds its place while the camera moves — orbit
around it and the answer stays on the object it describes.

```kotlin
class AnswerPanel(val id: Int, val anchor: Anchor) {
    var text by mutableStateOf("")           // grows with the stream deltas
}
val panels = remember { mutableStateListOf<AnswerPanel>() }
val viewNodeManager = rememberViewNodeManager()
// ARCore anchors cost per frame while attached — always detach on dispose.
DisposableEffect(Unit) { onDispose { panels.forEach { it.anchor.detach() } } }

ARSceneView(
    planeRenderer = true,                    // show planes: where a tap will pin
    viewNodeWindowManager = viewNodeManager, // required by ViewNode
    onSessionUpdated = { _, frame -> latestFrame = frame },
    onGestureListener = rememberOnGestureListener(
        onSingleTapConfirmed = { e, _ ->
            // Tracked planes (tap inside the polygon) and feature points both accept.
            val hit = latestFrame?.hitTest(e)?.firstOrNull { result ->
                val t = result.trackable
                t.trackingState == TrackingState.TRACKING &&
                    (t is Point || (t is Plane && t.isPoseInPolygon(result.hitPose)))
            }
            // createAnchor() throws once too many anchors exist — degrade, don't crash.
            val anchor = hit?.let { runCatching { it.createAnchor() }.getOrNull() }
            val panel = anchor?.let { AnswerPanel(nextId++, it).also { p -> panels += p } }
            // Stream the answer into `panel` when non-null, into the screen-space
            // card when the tap hit nothing trackable.
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
                    // No rotation: an ARCore hit pose is already oriented "Z+ roughly
                    // toward the user's device", and a ViewNode's quad faces its own +Z,
                    // so identity already faces where the user stood at tap time. Adding
                    // a world-space yaw here double-counts it and turns the card away.
                    scale = Scale(0.15f),                // ViewNode renders at 250 px/m
                ) {
                    // A ViewNode has no parent to measure against — give the content
                    // an explicit width.
                    Card(Modifier.width(320.dp)) { Text(panel.text) }
                }
            }
        }
    }
}
```

Combining this with the composited capture above: the anchored cards are UI, not
scenery, so hide them for the capture (`isVisible = false` on the `ViewNode`, same
flag as your 2D overlays) — otherwise the model reads its own earlier answers back
as part of the next question. Placed props stay visible on purpose: they are what
you want the model to see.

## iOS (Swift + SwiftUI)

Not available yet — SceneViewSwift has no on-device multimodal prompt API wired
(an Apple Foundation Models / Apple Intelligence integration would be a separate
feature). Tracked on [#2648](https://github.com/sceneview/sceneview/issues/2648).

## Key concepts

| Concept | Android |
|---|---|
| On-device model | Gemini Nano via AICore (`Generation.getClient()`) |
| Availability gate | `checkStatus()` → `AVAILABLE` / `DOWNLOADABLE` / `UNAVAILABLE` |
| Frame capture (camera only) | `frame.cameraImage()` inside `onSessionUpdated` (current frame only) |
| Frame capture (composited) | `PixelCopy.request(activity.window, …)` — camera + virtual objects, hide overlays first |
| YUV → Bitmap | `Image.toArgbBitmap(rotationDegrees)` — off main thread, close the Image |
| Multimodal ask | `generateContent(generateContentRequest(ImagePart, TextPart) {})` |
| Streamed answers | `generateContentStream(request)` — a `Flow` of text deltas to concatenate |
| World-anchored answers | `frame.hitTest(e)` → `hit.createAnchor()` → `AnchorNode { ViewNode { … } }` — detach anchors on dispose |
| Rotation | 90° at portrait `ROTATION_0` (map from display rotation for other orientations) |
| Emulator QA | AICore is never available on emulators — inject a canned engine under QA mode (see `PointAndAskDemo.kt` / `AskEngine.kt`) |

Reference demo: [`PointAndAskDemo.kt`](../android-demo/src/main/java/io/github/sceneview/demo/demos/PointAndAskDemo.kt)
(demo id `point-and-ask`), with the production-grade extras: download CTA with
progress, **composited `PixelCopy` capture** (the model sees placed 3D props —
long-press drops one), overlays hidden during capture, deterministic QA engine,
streamed answers with a live typing cursor, a free-form question field (blank
falls back to the default prompt), an offline badge (`ConnectivityManager`
active-network check), an auto-dismissing answer card, and **world-anchored answer
panels** (one `AnchorNode` + `ViewNode` per tap that lands on a tracked surface,
hidden during the capture, with the screen-space card as the fallback).
