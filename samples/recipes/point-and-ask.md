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

## iOS (Swift + SwiftUI)

Not available yet — SceneViewSwift has no on-device multimodal prompt API wired
(an Apple Foundation Models / Apple Intelligence integration would be a separate
feature). Tracked on [#2648](https://github.com/sceneview/sceneview/issues/2648).

## Key concepts

| Concept | Android |
|---|---|
| On-device model | Gemini Nano via AICore (`Generation.getClient()`) |
| Availability gate | `checkStatus()` → `AVAILABLE` / `DOWNLOADABLE` / `UNAVAILABLE` |
| Frame capture | `frame.cameraImage()` inside `onSessionUpdated` (current frame only) |
| YUV → Bitmap | `Image.toArgbBitmap(rotationDegrees)` — off main thread, close the Image |
| Multimodal ask | `generateContent(generateContentRequest(ImagePart, TextPart) {})` |
| Rotation | 90° at portrait `ROTATION_0` (map from display rotation for other orientations) |
| Emulator QA | AICore is never available on emulators — inject a canned engine under QA mode (see `PointAndAskDemo.kt` / `AskEngine.kt`) |

Reference demo: [`PointAndAskDemo.kt`](../android-demo/src/main/java/io/github/sceneview/demo/demos/PointAndAskDemo.kt)
(demo id `point-and-ask`), with the production-grade extras: download CTA with
progress, capture timeout, cancellation-safe Image close, deterministic QA engine.
