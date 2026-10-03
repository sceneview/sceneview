package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
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
import io.github.sceneview.ar.camera.CameraImageSize
import io.github.sceneview.ar.camera.CameraImageToViewMapping
import io.github.sceneview.ar.camera.cameraImageToViewMapping
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.cameraImageRotationDegrees
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.demos.internal.DetectionCameraPose
import io.github.sceneview.demo.demos.internal.ObjectLabelObservation
import io.github.sceneview.demo.demos.internal.ObjectLabelTrack
import io.github.sceneview.demo.demos.internal.ObjectLabelTracker
import io.github.sceneview.demo.demos.internal.isDetectionCaptureCompatible
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
 *     (`Frame.acquireCameraImage()` wrapped to return `null` while ARCore has no image to give:
 *     warm-up, stale frame, full image pool).
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
 * `kDetectIntervalMs`, associate labels by ML Kit tracking ID (nearest same-label fallback),
 * and replace matched anchors so each label follows its object instead of accumulating.
 */
@Composable
fun ARMLObjectLabelDemo(onBack: () -> Unit) {
    var arSessionFailed by remember { mutableStateOf(false) }
    var arSessionUnavailable by remember { mutableStateOf(false) }
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
    //
    // Guarded (#4025): creating the client is the first ML Kit call of the demo, made during
    // the first composition. An uncaught throw there — a missing class in a shrunk build, a
    // native init failure — took the whole app down before any AR frame was drawn. `null`
    // now means "no detector on this device": the banner says so and the AR scene keeps
    // running, the same contract the Body Tracker's MediaPipe init has.
    val detector = remember {
        runCatching {
            ObjectDetection.getClient(
                ObjectDetectorOptions.Builder()
                    .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                    .enableMultipleObjects()
                    .enableClassification()
                    .build()
            )
        }.onFailure {
            android.util.Log.e(ML_LOG_TAG, "ML Kit object detector init failed", it)
        }.getOrNull()
    }
    DisposableEffect(detector) {
        onDispose { detector?.let { runCatching { it.close() } } }
    }

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
    val detectionTracker = remember {
        ObjectLabelTracker<Anchor> { anchor -> runCatching { anchor.detach() } }
    }
    var statusBannerRes by remember { mutableIntStateOf(R.string.demo_ar_ml_status_warming) }

    // Real pixel size of the AR surface, measured by Compose layout. It replaced a hardcoded
    // 1000×1000 square (#3337) and is the view size the camera-image mapping is captured for.
    // Zero until the first layout pass.
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

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

    // Result of the most recent completed ML Kit pass, waiting to be turned into anchors.
    // `onSessionUpdated` runs on the render thread that owns the ARCore session (see
    // ARRecordInterpreter's kdoc); `detector.process`'s listeners run on the main thread
    // (the Play Services Tasks default when no Executor is given). Anchor creation needs
    // `frame.hitTest`, an ARCore `Frame`/`Session` call — and ARCore's session state is not
    // thread-safe, nor is a `Frame` reference still valid once the render thread has moved on
    // to later frames, which it always has by the time a ~30–80 ms TFLite pass completes. Doing
    // the hit-test from the async listener against the `Frame` captured at dispatch time was a
    // cross-thread, stale-frame access to ARCore's native session — the root cause of the
    // reported post-launch crash (#3268): it surfaced once enough detector passes had run for
    // the timing skew between "frame captured" and "hit-test executed" to hit the race.
    // The fix: the listener only stashes the raw ML Kit results here; the actual hit-test runs
    // below, back on the render thread, against that frame's own *current* `frame` — always
    // fresh, always on the right thread.
    val pendingDetection = remember {
        java.util.concurrent.atomic.AtomicReference<PendingDetection?>(null)
    }

    // Status banner text — "Warming up…" / "Aim at an object" / detection count.
    // trackingFailureMessage returns String? — fall back to the status-banner res if
    // the reason resolves to no specific message.
    val trackingMessage = trackingFailureMessage(trackingFailureReason)
    val statusText = when {
        detector == null -> stringResource(R.string.demo_ar_ml_status_unavailable)
        trackingMessage != null -> trackingMessage
        detections.isNotEmpty() ->
            context.resources.getQuantityString(
                R.plurals.demo_ar_ml_status_detected,
                detections.size,
                detections.size,
            )
        else -> stringResource(statusBannerRes)
    }

    DemoScaffold(
        arSessionFailed = arSessionFailed,
        arOverlaysEnabled = !arSessionUnavailable,
        title = stringResource(R.string.demo_ar_ml_title),
        onBack = onBack,
        // No Settings FAB: the only sheet content was a help paragraph and a live status
        // card. The status ("Warming up…" / "Aim at an object" / detection count) is the
        // demo's primary feedback, so it is an always-on banner in the scaffold's top slot
        // instead of being hidden behind a Settings FAB — and the status text already tells
        // a first-time user what to do (#1620 thread 1).
        topOverlay = {
            Surface(
                color = Color.Black.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = statusText,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // The bundled ML Kit detector classifies five broad categories (Home
                    // good, Fashion good, Food, Place, Plant) rather than specific object
                    // names — indoor scenes land on "Home good" for almost everything, which
                    // reads as "the demo only ever detects one thing" if unexplained (#3268).
                    // Shown only once something is anchored so the warm-up / aim prompts stay
                    // uncluttered.
                    if (detections.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.demo_ar_ml_explainer),
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
            ARSceneView(
                onSessionFailure = { arSessionFailed = true },
                onARCoreAvailability = { arSessionUnavailable = it != null },
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
                onSessionUpdated = { session, frame: Frame ->
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING

                    // Keep the camera world position fresh so every label billboards toward
                    // the viewer (see `cameraPosition` above). The pose translation is the
                    // camera eye position in world space — all a billboard needs to orient.
                    val camPose = frame.camera.pose.position
                    cameraPosition[0] = camPose.x
                    cameraPosition[1] = camPose.y
                    cameraPosition[2] = camPose.z

                    // Turn the previous detector pass's results into anchors now, on the
                    // render thread, against *this* frame — see `pendingDetection` above.
                    pendingDetection.getAndSet(null)?.let { pending ->
                        val currentMapping = if (viewSize.width > 0 && viewSize.height > 0) {
                            runCatching {
                                frame.cameraImageToViewMapping(
                                    imageSize = pending.mapping.imageSize,
                                    viewSize = CameraImageSize(viewSize.width, viewSize.height),
                                    inputRotationDegrees = cameraImageRotationDegrees(context, session),
                                )
                            }.getOrNull()
                        } else {
                            null
                        }
                        val isCompatible = currentMapping != null && isDetectionCaptureCompatible(
                            capturedPose = pending.cameraPose,
                            currentPose = frame.camera.pose.toDetectionCameraPose(),
                            capturedTimestampNanos = pending.frameTimestampNanos,
                            currentTimestampNanos = frame.timestamp,
                            capturedMapping = pending.mapping,
                            currentMapping = currentMapping,
                        )
                        if (isCompatible) {
                            updateAnchorsFromDetections(
                                frame = frame,
                                results = pending.results,
                                mapping = pending.mapping,
                                tracker = detectionTracker,
                                detections = detections,
                                labelBitmaps = labelBitmaps,
                            )
                        }
                        // A rejected result is dropped whole: it must not cast stale rays, and
                        // it says nothing about which objects are still there, so it does not
                        // advance track expiry either. The labels already placed are world
                        // anchors — they stay on their objects while the phone moves, and the
                        // next pass taken from a steady camera reconciles them.
                    }

                    if (!isTracking) return@ARSceneView
                    val activeDetector = detector ?: return@ARSceneView

                    // Throttle the detector dispatch — at 60 Hz the per-frame TFLite call
                    // would dominate the main thread.
                    val now = System.currentTimeMillis()
                    if (now - lastDetectMs[0] < kDetectIntervalMs) return@ARSceneView

                    // Never acquire a second CPU image while ML Kit still holds the previous
                    // one (see `detectInFlight`). Released in all three terminal listeners —
                    // success, failure, cancel — next to the matching `cameraImage.close()`,
                    // and in the `finally` below for the paths that throw before any listener
                    // is attached.
                    if (!detectInFlight.compareAndSet(false, true)) return@ARSceneView
                    lastDetectMs[0] = now

                    // Pull the CPU camera image. `null` is normal during session warm-up, and
                    // `cameraImage()` also maps a stale frame or a full image pool to `null`.
                    // Never re-throw from here: ARSceneView only logs what escapes the render
                    // callback, and the frame's detection is lost either way. Anything else
                    // ARCore raises (a dead session)
                    // resurfaces from the next `session.update()` through the normal error
                    // path. Every exit must clear the in-flight flag, or the demo stops
                    // detecting for the rest of the session.
                    val cameraImage = runCatching { frame.cameraImage() }.getOrNull()
                    if (cameraImage == null) {
                        detectInFlight.set(false)
                        if (statusBannerRes != R.string.demo_ar_ml_status_warming) {
                            statusBannerRes = R.string.demo_ar_ml_status_warming
                        }
                        return@ARSceneView
                    }

                    // Everything from here until the listeners are attached runs under one
                    // guard. `detector.process` is the hand-off point: once it has returned a
                    // Task with both listeners attached, ownership of the image and the flag
                    // moves to whichever listener fires. Before that point they are still ours,
                    // and any throw in between — `context.display` on a non-visual context, a
                    // detector closed by a recomposition — would otherwise leak the image AND
                    // strand the flag at true, which silently ends detection for the rest of
                    // the session. That is worse than the exhaustion this guard was added to
                    // prevent, because nothing in the UI would say so.
                    var dispatched = false
                    try {
                        val rotationDegrees = cameraImageRotationDegrees(context, session)

                        // Hand the image to ML Kit. `InputImage.fromMediaImage` retains a
                        // reference until the task completes, so we close `cameraImage` in
                        // the success/failure listeners — never before. Closing earlier
                        // would throw IllegalStateException from the YUV reader inside
                        // ML Kit (caught in #1737 review pre-merge).
                        val input = InputImage.fromMediaImage(cameraImage, rotationDegrees)
                        val imageW = cameraImage.width
                        val imageH = cameraImage.height
                        if (viewSize.width <= 0 || viewSize.height <= 0) return@ARSceneView
                        val captureMapping = frame.cameraImageToViewMapping(
                            imageSize = CameraImageSize(imageW, imageH),
                            viewSize = CameraImageSize(viewSize.width, viewSize.height),
                            inputRotationDegrees = rotationDegrees,
                        )
                        val capturePose = frame.camera.pose.toDetectionCameraPose()
                        val captureTimestamp = frame.timestamp

                        activeDetector.process(input)
                            .addOnSuccessListener { results ->
                                try {
                                    // Stash for `onSessionUpdated` to turn into anchors against
                                    // the *next* current frame — never hit-test here (see
                                    // `pendingDetection` above for why).
                                    pendingDetection.set(
                                        PendingDetection(
                                            results = results,
                                            cameraPose = capturePose,
                                            frameTimestampNanos = captureTimestamp,
                                            mapping = captureMapping,
                                        )
                                    )
                                    // Once anything is anchored, the status text switches to
                                    // the live "N objects detected" count (see `statusText`
                                    // above), so the "Aim at a recognisable object" hint is
                                    // dismissed as soon as ≥1 label is placed (#2478). Until
                                    // then, keep prompting.
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
                            .addOnCanceledListener {
                                // Unreachable in practice — `process(InputImage)` takes no
                                // CancellationToken and ML Kit surfaces teardown as a failure,
                                // not a cancel. Attached anyway because it is the third and
                                // last terminal state a Task has: without it, "released on
                                // every exit path" would be a claim resting on an ML Kit
                                // implementation detail rather than on the Task contract.
                                cameraImage.close()
                                detectInFlight.set(false)
                            }

                        // All three terminal listeners are attached: the image and the flag
                        // are theirs now.
                        dispatched = true
                    } finally {
                        if (!dispatched) {
                            cameraImage.close()
                            detectInFlight.set(false)
                        }
                    }
                },
                onTrackingFailureChanged = { reason -> trackingFailureReason = reason },
            ) {
                // Render one billboard label per active detection, keyed on the anchor — never
                // on the track. A slot whose anchor changes in place rebuilds its AnchorNode and
                // destroys the old one together with its billboard, while the billboard slot,
                // `remember`ed on a shared cached bitmap, keeps the destroyed node (the label
                // vanishes) and destroys it again later, after its Filament entity id has been
                // recycled. Keying on the anchor gives a re-anchored label a fresh slot, so each
                // node is built and destroyed exactly once; `updateAnchorsFromDetections` keeps
                // the anchor of a still object, so a label is only rebuilt when it really moves.
                // This removes the double destroy only; the "Invalid texture still bound to
                // MaterialInstance" abort at the sixth label is a separate issue, still
                // reproducible with this change.
                detections.forEach { entry ->
                    key(entry.anchor) {
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
            }
        }
    }

    // Sanity cleanup if the composable leaves — detach anchors so ARCore reclaims them.
    DisposableEffect(Unit) {
        onDispose {
            detectionTracker.clear()
            detections.clear()
            labelBitmaps.clear()
        }
    }

    // Kick the banner default once on first composition.
    LaunchedEffect(Unit) {
        statusBannerRes = R.string.demo_ar_ml_status_warming
    }
}

/** One visible label: the ARCore [Anchor] its track currently uses and the cached label bitmap. */
private data class DetectionAnchor(
    val anchor: Anchor,
    val bitmap: Bitmap,
)

/**
 * One completed-but-not-yet-anchored ML Kit detector pass, captured off the render thread.
 * Camera pose, timestamp, and display geometry travel with the result so a later render frame
 * never casts an old detector point through a materially different camera ray.
 */
private data class PendingDetection(
    val results: List<DetectedObject>,
    val cameraPose: DetectionCameraPose,
    val frameTimestampNanos: Long,
    val mapping: CameraImageToViewMapping,
)

/**
 * Reconciles the current detection set with the latest ML Kit results.
 *
 * Tracking IDs survive large screen motion. Objects without an ID use nearest same-label
 * association. A matched detection keeps its anchor while the fresh hit stays within
 * [isWithinReanchorTolerance] (a still object must not rebuild its label on every pass) and is
 * re-anchored once the object has moved; missing tracks expire through [ObjectLabelTracker],
 * which detaches their anchors even when the result list is empty.
 */
private fun updateAnchorsFromDetections(
    frame: Frame,
    results: List<DetectedObject>,
    mapping: CameraImageToViewMapping,
    tracker: ObjectLabelTracker<Anchor>,
    detections: MutableList<DetectionAnchor>,
    labelBitmaps: MutableMap<String, Bitmap>,
) {
    val observations = results.mapNotNull { obj ->
        val label = obj.labels.firstOrNull() ?: return@mapNotNull null
        ObjectLabelObservation(
            trackingId = obj.trackingId,
            label = label.text,
            confidence = label.confidence,
            centerX = obj.boundingBox.exactCenterX(),
            centerY = obj.boundingBox.exactCenterY(),
        )
    }
    val tracks = tracker.reconcile(observations) { observation, previous ->
        val screen = mapping.mapPixel(observation.centerX, observation.centerY)
        val hits = runCatching { frame.hitTest(screen.x, screen.y) }.getOrNull().orEmpty()
        val hitIndex = nearestUsableHitIndex(hits.map { it.distance }) ?: return@reconcile null
        val hit = hits[hitIndex]
        val keepsAnchor = previous != null &&
            previous.trackingState == TrackingState.TRACKING &&
            isWithinReanchorTolerance(previous.pose.translation, hit.hitPose.translation)
        if (keepsAnchor) previous else runCatching { hit.createAnchor() }.getOrNull()
    }
    syncVisibleDetections(tracks, detections, labelBitmaps)
}

/**
 * Publishes the tracker's anchors to the composition. The label bitmap comes from the track's
 * latest observation, so the confidence shown on a label stays current while its anchor is
 * kept; the list is only rewritten when it really changed, so an unchanged pass does not
 * recompose the scene.
 */
private fun syncVisibleDetections(
    tracks: List<ObjectLabelTrack<Anchor>>,
    detections: MutableList<DetectionAnchor>,
    labelBitmaps: MutableMap<String, Bitmap>,
) {
    val visible = tracks.mapNotNull { track ->
        val anchor = track.payload ?: return@mapNotNull null
        val label = track.observation.label
        // Cache by label + confidence bucket so the rendered "%" stays current without
        // re-rasterising on every sub-percent jitter (#2478 — the user asked for the score).
        val confidencePercent = confidenceBucketPercent(track.observation.confidence)
        DetectionAnchor(
            anchor = anchor,
            bitmap = labelBitmaps.getOrPut("$label@$confidencePercent") {
                createLabelBitmap(label, confidencePercent)
            },
        )
    }
    if (detections.toList() != visible) {
        detections.clear()
        detections.addAll(visible)
    }
}

private fun com.google.ar.core.Pose.toDetectionCameraPose(): DetectionCameraPose {
    val translation = translation
    val rotation = rotationQuaternion
    return DetectionCameraPose(
        tx = translation[0],
        ty = translation[1],
        tz = translation[2],
        qx = rotation[0],
        qy = rotation[1],
        qz = rotation[2],
        qw = rotation[3],
    )
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

/** Logcat tag for the demo's ML Kit failures (#4025). */
private const val ML_LOG_TAG = "ARMLObjectLabel"
