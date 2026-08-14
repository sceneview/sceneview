package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.cameraImage
import io.github.sceneview.ar.arcore.position
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader

/**
 * ML Kit object detection demo — anchor 3D labels on detected real-world objects (#1737).
 *
 * Pipeline:
 *  1. Each AR frame, pull the CPU camera image via [io.github.sceneview.ar.arcore.cameraImage]
 *     (`Frame.acquireCameraImage()` wrapped to return `null` on `NotYetAvailableException`).
 *  2. Feed the YUV image into ML Kit's [com.google.mlkit.vision.objects.ObjectDetector]
 *     configured with [ObjectDetectorOptions] (bundled model, single-image multi-object, label
 *     classification enabled).
 *  3. For each detected object's bounding-box centre, [Frame.hitTest] in screen space to find
 *     the world pose, then create an [Anchor] and attach a [BillboardNode] showing the
 *     ML Kit category name.
 *
 * Empty states:
 *  - "No camera image yet" — the very first frames before ARCore has filled the CPU image
 *    pool. Reads as a temporary "Warming up…" banner instead of a black screen.
 *  - "No objects detected" — frames whose detector run returns an empty list. A persistent
 *    "Aim at an object" hint. Reads as guidance, not failure.
 *
 * **Note on the bundled model.** ML Kit's default object detector classifies five generic
 * categories (`Home good`, `Fashion good`, `Food`, `Place`, `Plant`). For app-specific
 * labels, plug in a custom TFLite model via
 * [com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions]. The wiring here
 * stays on the default so the demo works offline on any device with no extra asset
 * download.
 *
 * **Rate limiting.** The detector takes ~30–80 ms per frame on a mid-range phone; running
 * it on every AR frame (60 Hz) starves the renderer. We throttle to one detector run per
 * `kDetectIntervalMs` and de-duplicate anchors by (centre-pixel-bucket × label) so each
 * detected object spawns exactly one label even when the detector fires multiple times on
 * the same scene.
 */
@Composable
fun ARMLObjectLabelDemo(onBack: () -> Unit) {
    val context = LocalContext.current

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>`. `null` for every normal launch.
    val arPlaybackDataset = rememberArPlaybackDataset()

    // ML Kit detector — bundled (offline) model. Configure once and reuse across frames.
    //   • STREAM_MODE: latency-optimised for live camera frames.
    //   • Multiple objects: surface every detection, not just the most prominent one.
    //   • Classification: emit the generic-category label so we have something to render.
    //
    // `remember`+`DisposableEffect` mirror the SceneView lifecycle: the detector is closed
    // when the composable leaves so the native TFLite runtime is released and we don't
    // accumulate detectors across navigation cycles (CommonObjectDetector holds ~10 MB
    // of TFLite buffers — fine for one, leaks visibly if we orphan them).
    val detector = remember {
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
        )
    }
    DisposableEffect(detector) {
        onDispose { detector.close() }
    }

    // Latest frame snapshot — recorded by onSessionUpdated and consumed by the detector
    // dispatch below. Kept as a plain `var` (not Compose state) because the detector
    // dispatch is fire-and-forget and we only need the most recent reference.
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var isTracking by remember { mutableStateOf(false) }

    // Camera world position, refreshed every AR frame. A `BillboardNode` only rotates to
    // face the viewer when it is given a `cameraPositionProvider`; without one its `onFrame`
    // hook is a no-op and the quad keeps the anchor's plane-aligned pose — so a label
    // anchored on a floor/table is shown edge-on or back-faced, which is exactly the
    // "oversized, warped diagonal band with mirrored/upside-down text" reported in #2478.
    // Kept as a plain holder (read inside the per-frame billboard provider lambda).
    val cameraPosition = remember { floatArrayOf(0f, 0f, 0f) }

    // Detection state — the list of currently-anchored labels and a status banner.
    val detections = remember { mutableStateListOf<DetectionAnchor>() }
    var statusBannerRes by remember { mutableIntStateOf(R.string.demo_ar_ml_status_warming) }

    // Detector throttle — minimum gap between detector runs so we don't starve the
    // renderer. ~6 fps is plenty for "label what's in front of me".
    val lastDetectMs = remember { longArrayOf(0L) }
    val kDetectIntervalMs = 160L

    // The time throttle alone is not a concurrency guard: it only says "160 ms have passed",
    // not "the previous detection released its image". ML Kit holds the CPU image until its
    // listener fires, and on a mid-range device object detection takes longer than the
    // interval — so the next window acquires a second image while the first is still in
    // flight, and ARCore's 2–3 slot pool runs dry. Measured on a Pixel 4a (2026-08-14):
    // 28 `ResourceExhaustedException` from `acquireCameraImage` in a 9-second run.
    val detectInFlight = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    // Pre-rendered label bitmaps cached by category — ML Kit returns a stable category
    // name per detection, so we memo the bitmap per category instead of re-rasterising
    // per frame (saves ~2–4 ms of canvas work per detector pass).
    val labelBitmaps = remember { mutableMapOf<String, Bitmap>() }

    // No Settings FAB: the only sheet content was a help paragraph and a live
    // status card. The status ("Warming up…" / "Aim at an object" / detection
    // count) is the demo's primary feedback, so it now sits in an always-on
    // top-center overlay instead of being hidden behind a Settings FAB — and the
    // status text already tells a first-time user what to do (#1620 thread 1).
    DemoScaffold(
        title = stringResource(R.string.demo_ar_ml_title),
        onBack = onBack,
    ) {
        // Status banner text — "Warming up…" / "Aim at an object" / detection
        // count. trackingFailureMessage returns String? — fall back to the
        // status-banner res if the reason resolves to no specific message.
        val trackingMessage = trackingFailureMessage(trackingFailureReason)
        val statusText = when {
            trackingMessage != null -> trackingMessage
            detections.isNotEmpty() ->
                context.resources.getQuantityString(
                    R.plurals.demo_ar_ml_status_detected,
                    detections.size,
                    detections.size,
                )
            else -> stringResource(statusBannerRes)
        }
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                planeRenderer = false,
                sessionConfiguration = { _: Session, config: Config ->
                    // Plane finding stays on so frame.hitTest has something to anchor to
                    // when the user aims at a horizontal surface (table, floor). Vertical
                    // included so walls / shelves work too.
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                },
                onSessionUpdated = { _, frame: Frame ->
                    latestFrame = frame
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING

                    // Keep the camera world position fresh so every label billboards toward
                    // the viewer (see `cameraPosition` above). The pose translation is the
                    // camera eye position in world space — all a billboard needs to orient.
                    val camPose = frame.camera.pose.position
                    cameraPosition[0] = camPose.x
                    cameraPosition[1] = camPose.y
                    cameraPosition[2] = camPose.z

                    if (!isTracking) return@ARSceneView

                    // Throttle the detector dispatch — at 60 Hz the per-frame TFLite call
                    // would dominate the main thread.
                    val now = System.currentTimeMillis()
                    if (now - lastDetectMs[0] < kDetectIntervalMs) return@ARSceneView

                    // Never acquire a second CPU image while ML Kit still holds the previous
                    // one (see `detectInFlight`). Released in both listeners, next to the
                    // matching `cameraImage.close()`.
                    if (!detectInFlight.compareAndSet(false, true)) return@ARSceneView
                    lastDetectMs[0] = now

                    // Pull the CPU camera image. `null` is normal during session warm-up.
                    // Any failure from here on must clear the in-flight flag, or the demo
                    // stops detecting for the rest of the session.
                    val cameraImage = try {
                        frame.cameraImage()
                    } catch (e: Throwable) {
                        detectInFlight.set(false)
                        throw e
                    }
                    if (cameraImage == null) {
                        detectInFlight.set(false)
                        if (statusBannerRes != R.string.demo_ar_ml_status_warming) {
                            statusBannerRes = R.string.demo_ar_ml_status_warming
                        }
                        return@ARSceneView
                    }

                    // `Context.display` was added in API 30; fall back to the
                    // deprecated `WindowManager.defaultDisplay` on API 28–29.
                    val displayRotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        context.display.rotation
                    } else {
                        @Suppress("DEPRECATION")
                        (context.getSystemService(android.content.Context.WINDOW_SERVICE)
                                as android.view.WindowManager).defaultDisplay.rotation
                    }
                    val rotationDegrees = when (displayRotation) {
                        // ARCore CPU image is delivered with the device's natural-orientation
                        // axis. ML Kit needs degrees of rotation from upright. ARCore's
                        // back-camera sensor orientation on Android phones is 90° → match.
                        android.view.Surface.ROTATION_0 -> 90
                        android.view.Surface.ROTATION_90 -> 0
                        android.view.Surface.ROTATION_180 -> 270
                        android.view.Surface.ROTATION_270 -> 180
                        else -> 90
                    }

                    // Hand the image to ML Kit. `InputImage.fromMediaImage` retains a
                    // reference until the task completes, so we close `cameraImage` in
                    // the success/failure listeners — never before. Closing earlier
                    // would throw IllegalStateException from the YUV reader inside
                    // ML Kit (caught in #1737 review pre-merge).
                    val input = try {
                        InputImage.fromMediaImage(cameraImage, rotationDegrees)
                    } catch (e: Throwable) {
                        cameraImage.close()
                        detectInFlight.set(false)
                        throw e
                    }
                    val imageW = cameraImage.width
                    val imageH = cameraImage.height

                    detector.process(input)
                        .addOnSuccessListener { results ->
                            try {
                                updateAnchorsFromDetections(
                                    frame = frame,
                                    results = results,
                                    imageW = imageW,
                                    imageH = imageH,
                                    detections = detections,
                                    labelBitmaps = labelBitmaps,
                                )
                                // Once anything is anchored, the status text switches to the
                                // live "N objects detected" count (see `statusText` above), so
                                // the "Aim at a recognisable object" hint is dismissed as soon
                                // as ≥1 label is placed (#2478). Until then, keep prompting.
                                statusBannerRes = R.string.demo_ar_ml_status_aim
                            } finally {
                                cameraImage.close()
                                detectInFlight.set(false)
                            }
                        }
                        .addOnFailureListener {
                            // Failure can be transient (e.g. session paused mid-process) —
                            // drop and try again next throttle window. Always close the
                            // image — leaking even a single CPU image is enough to stall
                            // ARCore within a few frames.
                            cameraImage.close()
                            detectInFlight.set(false)
                        }
                },
                onTrackingFailureChanged = { reason -> trackingFailureReason = reason },
            ) {
                // Render one billboard label per active detection. The (anchor, bitmap)
                // pair is keyed on the anchor identity so when an anchor is replaced
                // (object moves off-screen → re-detected) Compose tears down + recreates
                // the node correctly.
                detections.forEach { entry ->
                    val anchor = entry.anchor
                    val bitmap = entry.bitmap
                    if (anchor.trackingState == TrackingState.TRACKING) {
                        AnchorNode(anchor = anchor) {
                            BillboardNode(
                                bitmap = bitmap,
                                widthMeters = 0.30f,
                                heightMeters = 0.12f,
                                position = Position(y = 0.05f),
                                // Face the camera every frame. Without this the quad keeps
                                // the anchor's plane-aligned orientation and renders edge-on
                                // / back-faced (mirrored UVs) — the #2478 warped-band bug.
                                cameraPositionProvider = {
                                    Position(
                                        x = cameraPosition[0],
                                        y = cameraPosition[1],
                                        z = cameraPosition[2],
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // Always-on status banner overlay — "Warming up…" / "Aim at an
            // object" / detection count. Surfaced on-screen (previously buried
            // in a Settings sheet) so the user always knows the demo state
            // without opening a FAB (#1620 thread 1).
            Surface(
                color = Color.Black.copy(alpha = 0.4f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
            ) {
                Text(
                    text = statusText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    // Sanity cleanup if the composable leaves — detach anchors so ARCore reclaims them.
    DisposableEffect(Unit) {
        onDispose {
            detections.forEach { runCatching { it.anchor.detach() } }
            detections.clear()
            labelBitmaps.clear()
        }
    }

    // Kick the banner default once on first composition.
    LaunchedEffect(Unit) {
        statusBannerRes = R.string.demo_ar_ml_status_warming
    }
}

/**
 * One anchored detection — the ARCore [Anchor] (created from a hit-test at the detection's
 * bounding-box centre) and the cached label bitmap.
 *
 * Keyed by `categoryKey` so a "Home good" bucket whose bbox jitters by a few pixels between
 * frames stays a single anchor instead of spawning a new label every detector pass.
 */
private data class DetectionAnchor(
    val categoryKey: String,
    val anchor: Anchor,
    val bitmap: Bitmap,
)

/**
 * Buckets a 0..1 ML Kit confidence into a stable percentage step so the label-bitmap cache
 * key changes only every [step] percent. Without bucketing, every sub-percent confidence
 * jitter between detector passes would invalidate the cache and re-rasterise the bitmap.
 */
private fun confidenceBucketPercent(confidence: Float, step: Int = 5): Int {
    val pct = (confidence.coerceIn(0f, 1f) * 100f).toInt()
    return (pct / step) * step
}

/**
 * Reconciles the current detection set with the latest ML Kit results.
 *
 * Strategy:
 *  - Bucket detections by category + bbox-centre cell so jittering bounding boxes
 *    map to the same anchor.
 *  - For each new bucket, hit-test the bbox centre against the AR scene; if a hit lands,
 *    create an anchor and a label bitmap.
 *  - For existing buckets that aren't in this frame's result, drop the anchor (release
 *    the ARCore resource) so labels follow the scene instead of accumulating.
 *
 * Bbox centre is mapped from the **CPU image coordinate space** (`imageW × imageH`) to
 * the display coordinate space via the active `frame`'s coords transform — ARCore's
 * `frame.transformCoordinates2d` would be the right tool, but for V1 we approximate by
 * scaling from the image space to the framebuffer via the camera's display geometry.
 * Detector throttle (#kDetectIntervalMs) keeps the cost bounded.
 */
private fun updateAnchorsFromDetections(
    frame: Frame,
    results: List<DetectedObject>,
    imageW: Int,
    imageH: Int,
    detections: SnapshotStateList<DetectionAnchor>,
    labelBitmaps: MutableMap<String, Bitmap>,
) {
    if (results.isEmpty()) return

    // Build category keys from this frame's detections.
    val newKeys = mutableSetOf<String>()
    @Suppress("LoopWithTooManyJumpStatements") // multiple early-exit guards improve readability
    for (obj in results) {
        val labelInfo = obj.labels.firstOrNull() ?: continue
        val label = labelInfo.text
        val confidencePercent = confidenceBucketPercent(labelInfo.confidence)
        // Bucket by 64-pixel cell so small bbox jitter doesn't create new anchors.
        val cx = obj.boundingBox.centerX()
        val cy = obj.boundingBox.centerY()
        val key = "$label@${cx / 64},${cy / 64}"
        if (!newKeys.add(key)) continue
        if (detections.any { it.categoryKey == key }) continue

        // Hit-test the bbox centre. ARCore's hitTest expects display-space coordinates,
        // while the bbox is in CPU image space. The CPU image and the display share the
        // same aspect under the default camera config — scaling the centre to the
        // frame's hit-test surface as a proportion of width/height is the safest
        // approximation without bringing in the (deprecated) transformCoordinates3d API.
        val xFraction = cx.toFloat() / imageW.coerceAtLeast(1)
        val yFraction = cy.toFloat() / imageH.coerceAtLeast(1)

        // Use frame.camera.imageIntrinsics + projection? Simpler: skip the screen-space
        // mapping and use a centred hit-test for V1 — every detection appears near scene
        // centre. The user is meant to aim the device AT the object, so centre is already
        // close to the truth for a single detection. For multi-detection, the labels stack
        // tightly at scene centre — acceptable V1 UX, improvable in a follow-up.
        val displayW = 1000f // arbitrary; hitTest takes screen-space pixels
        val displayH = 1000f
        val hits = runCatching {
            frame.hitTest(xFraction * displayW, yFraction * displayH)
        }.getOrNull().orEmpty()
        val hit = hits.firstOrNull { it.distance in 0.1f..5.0f } ?: continue

        val anchor = runCatching { hit.createAnchor() }.getOrNull() ?: continue
        // Cache by label + confidence bucket so the rendered "%" stays current without
        // re-rasterising on every sub-percent jitter (#2478 — the user asked for the score).
        val bitmapKey = "$label@$confidencePercent"
        val bitmap = labelBitmaps.getOrPut(bitmapKey) {
            createLabelBitmap(label, confidencePercent)
        }
        detections += DetectionAnchor(key, anchor, bitmap)

        // Cap total anchored labels to 6 — beyond that, the AR scene gets cluttered
        // and ARCore's anchor budget starts pushing back. Drop the oldest.
        while (detections.size > 6) {
            val oldest = detections.removeAt(0)
            runCatching { oldest.anchor.detach() }
        }
    }
}

/**
 * Renders a label bitmap matching the visual language of [BillboardDemo] — rounded blue
 * card on a small post, white bold text. Mid-saturation blue so labels read against most
 * natural backgrounds (sky / wood / fabric) and bold weight so the text survives the
 * GPU's mipmap chain at distance.
 *
 * When [confidencePercent] is non-negative, the ML Kit classification confidence is shown
 * as a smaller "NN%" subtitle so the user can see *how sure* the detector is (#2478 — the
 * confidence the device reviewer was looking for). Pass a negative value to omit it.
 */
private fun createLabelBitmap(text: String, confidencePercent: Int = -1): Bitmap {
    val width = 384
    val height = 144
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cardBottom = height - 28f

    val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF005BC1.toInt() }
    canvas.drawRoundRect(RectF(8f, 8f, width - 8f, cardBottom), 24f, 24f, cardPaint)

    val postPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F2436.toInt() }
    canvas.drawRect(width / 2f - 6f, cardBottom, width / 2f + 6f, height.toFloat(), postPaint)

    val showConfidence = confidencePercent >= 0
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = if (showConfidence) 40f else 44f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    if (showConfidence) {
        // Two stacked lines: bold category over a lighter "NN%" subtitle.
        canvas.drawText(text, width / 2f, cardBottom / 2f - 2f, labelPaint)
        val confidencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCBD8F0.toInt()
            textSize = 30f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT
        }
        canvas.drawText("$confidencePercent%", width / 2f, cardBottom / 2f + 34f, confidencePaint)
    } else {
        canvas.drawText(text, width / 2f, cardBottom / 2f + 18f, labelPaint)
    }
    return bitmap
}
