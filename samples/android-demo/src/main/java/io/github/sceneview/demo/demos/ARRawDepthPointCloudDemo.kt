package io.github.sceneview.demo.demos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.demos.internal.RawDepthCloud
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * AR demo — sample ARCore's [Config.DepthMode.RAW_DEPTH_ONLY] depth + confidence
 * images per frame, filter out low-confidence pixels, and render the surviving
 * samples as a screen-space point cloud overlay on top of the live camera feed.
 *
 * A Compose [Slider] drives the confidence threshold: at 0 every depth pixel is
 * kept (and the noise is visible as jitter at object edges); at 1 only the
 * highest-confidence samples survive.
 *
 * Why screen-space, not world-space?
 *
 * Rendering the cloud as Filament `POINTS` primitives in world space would need
 * per-frame world-space unprojection of every depth pixel, a custom Filament
 * material with per-vertex color attributes, and a mesh re-upload every frame —
 * a non-trivial chunk of work for a demo that's about *visualising what depth
 * looks like*. The screen-space cloud uses the depth-image native coordinates
 * (which map 1:1 to the camera feed) and a Compose `Canvas` overlay. The cost
 * is one `IntArray` allocation per frame plus a sub-sampled per-pixel loop —
 * cheap, JVM-testable, and visually equivalent for a viz demo.
 *
 * The unsupported / warming-up states surface explicit banners so the user is
 * never staring at a black screen (repo non-negotiable, see #1617).
 *
 * Closes [#1715](https://github.com/sceneview/sceneview/issues/1715).
 */
@Composable
fun ARRawDepthPointCloudDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Slider in [0..1] mapped to confidence threshold in [0..255]. Default 32/255
    // (≈ 0.1255) is permissive enough for motion-stereo's noisy first frames on
    // non-LiDAR Pixels to surface visible points, see #1873. Users can still raise
    // it via the slider for cleaner edges once depth converges.
    var confidenceSlider by remember { mutableStateOf(32f / 255f) }
    var depthSupported by remember { mutableStateOf<Boolean?>(null) }
    var depthEverReceived by remember { mutableStateOf(false) }
    var isTracking by remember { mutableStateOf(false) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }

    // Onboarding hint state — survives configuration changes within the same
    // session (so rotating the device doesn't re-show the hint).
    // `hasShownHint` flips true once the hint dismisses (first non-zero frame or
    // 8 s elapsed) and stays true — we never re-show it.
    var hasShownHint by rememberSaveable { mutableStateOf(false) }
    var hintVisible by remember { mutableStateOf(!hasShownHint) }

    // Passive "still no points after >2 s" chip — recomputed each frame from
    // `zeroPointsSince`. `now` is bumped by a 1 Hz tick so the chip surfaces
    // even if the AR session is producing frames where `visiblePointCount == 0`
    // continuously (the condition wouldn't fire on a level edge otherwise).
    var zeroPointsSince by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    // Latest cloud + image dimensions. The packed cloud is `[x, y, depthMm, argb]` per point.
    var cloudPacked by remember { mutableStateOf<IntArray?>(null) }
    var depthWidth by remember { mutableStateOf(0) }
    var depthHeight by remember { mutableStateOf(0) }
    var visiblePointCount by remember { mutableStateOf(0) }

    val confidenceThreshold = (confidenceSlider * 255f).toInt().coerceIn(0, 255)

    // 8-second auto-dismiss for the first-launch onboarding hint. Skipped if the
    // hint has already been dismissed in this session (e.g. by a non-zero frame).
    LaunchedEffect(hasShownHint) {
        if (!hasShownHint) {
            delay(8.seconds)
            hintVisible = false
            hasShownHint = true
        }
    }

    // 1 Hz tick so the "stuck at zero" chip surfaces between frames. Cheap and
    // bounded — only flips a single state on every second.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1.seconds)
            now = System.currentTimeMillis()
        }
    }

    val zeroPointsStuck = depthSupported == true &&
        isTracking &&
        visiblePointCount == 0 &&
        zeroPointsSince != null &&
        (now - (zeroPointsSince ?: now)) > 2000L

    DemoScaffold(
        title = stringResource(R.string.demo_ar_raw_depth_cloud_title),
        onBack = onBack,
        controls = {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "How to read",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Raw depth samples are colored by distance (warm = near, " +
                            "cool = far). Drag the slider right to drop low-confidence " +
                            "samples — the noise should fall away at object edges first.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (depthSupported == false) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "Your device doesn't support ARCore raw depth. " +
                            "Try a Pixel 4+ / depth-capable phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "All samples",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "High confidence only",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Slider(
                value = confidenceSlider,
                onValueChange = { confidenceSlider = it.coerceIn(0f, 1f) },
                enabled = depthSupported != false,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Confidence ≥ $confidenceThreshold / 255 · $visiblePointCount points",
                style = MaterialTheme.typography.labelMedium
            )
            // Developer-only debug toggle — visible when QA mode is on. Lets QA
            // force-emit each TrackingFailureReason so the actionable-message
            // overlay can be validated without staging a real failure. See
            // io.github.sceneview.demo.common.ForcedTrackingFailure / #1881.
            ForceTrackingFailureMenu()
        },
        // The two top-anchored surfaces live in the scaffold slot (#3231): the scaffold
        // owns the top gutter and the system-bar inset, and stacks them as Column
        // siblings.
        topOverlay = {
            // Waiting-for-depth state — first few hundred ms after session start.
            AnimatedVisibility(
                visible = depthSupported == true && !depthEverReceived,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = "Computing raw depth — move slowly across a textured scene",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // Passive "still no points after >2 s" chip — small, non-blocking,
            // end-aligned. Surfaces when the user has missed (or dismissed) the
            // first-launch hint but raw depth still hasn't converged. Hides as soon
            // as a non-zero frame arrives.
            AnimatedVisibility(
                visible = zeroPointsStuck,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(end = 8.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = stringResource(R.string.demo_ar_raw_depth_cloud_move_hint_short),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        },
        // The two bottom-anchored banners live in the scaffold slot (#2779). They were
        // hand-lifted to 120 dp and 56 dp to miss each other and the Settings FAB — a
        // pair of constants that a longer sentence, a wrap or a larger font scale
        // invalidates. In the slot they are Column siblings and simply stack.
        bottomOverlay = {
            // First-launch onboarding hint — motion-stereo on non-LiDAR Pixels needs
            // parallax frames before any points show up, otherwise "0 points" looks
            // like the demo is broken (#1873). Auto-dismisses on first non-zero frame
            // or after 8 s. `rememberSaveable`-backed so configuration changes don't
            // re-show it. Co-exists with the top-centre warm-up surface during the
            // first frames.
            AnimatedVisibility(
                visible = hintVisible && depthSupported == true,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                // Guidance: nothing is broken, the user has to physically move the
                // device before depth can converge.
                DemoStatusBanner(
                    text = stringResource(R.string.demo_ar_raw_depth_cloud_move_hint),
                    tone = DemoStatusTone.Guidance,
                )
            }

            // Tracking-failure banner.
            // ForcedTrackingFailure.override shadows the real ARCore-reported reason
            // when a developer has picked one in the debug menu (#1881). Read it here
            // so flipping the override re-renders the overlay immediately.
            val effectiveReason = ForcedTrackingFailure.override ?: trackingFailureReason
            AnimatedVisibility(
                visible = (!isTracking && trackingFailureReason != null) ||
                    ForcedTrackingFailure.override != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                // Tone comes from the same branch that picks the sentence: light,
                // motion and texture are things the user fixes by moving; a lost
                // camera or a bad session state is broken until they act outside
                // the demo; no reason at all is plain scanning.
                val (statusText, statusTone) = when (effectiveReason) {
                    TrackingFailureReason.INSUFFICIENT_LIGHT ->
                        "Not enough light" to DemoStatusTone.Guidance
                    TrackingFailureReason.EXCESSIVE_MOTION ->
                        "Moving too fast" to DemoStatusTone.Guidance
                    TrackingFailureReason.INSUFFICIENT_FEATURES ->
                        "Not enough detail — point at a textured surface" to
                            DemoStatusTone.Guidance
                    TrackingFailureReason.CAMERA_UNAVAILABLE ->
                        "Camera unavailable" to DemoStatusTone.Blocked
                    TrackingFailureReason.BAD_STATE ->
                        "AR session error" to DemoStatusTone.Blocked
                    else -> stringResource(R.string.ar_status_scanning) to
                        DemoStatusTone.Progress
                }
                DemoStatusBanner(text = statusText, tone = statusTone)
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                sessionConfiguration = { session: Session, config: Config ->
                    val supported = session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
                    depthSupported = supported
                    config.depthMode = if (supported) {
                        Config.DepthMode.RAW_DEPTH_ONLY
                    } else {
                        Config.DepthMode.DISABLED
                    }
                },
                onSessionUpdated = { _, frame: Frame ->
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    if (depthSupported == true && isTracking) {
                        val depthImage = runCatching {
                            frame.acquireRawDepthImage16Bits()
                        }.getOrNull()
                        val confidenceImage = runCatching {
                            frame.acquireRawDepthConfidenceImage()
                        }.getOrNull()
                        if (depthImage != null && confidenceImage != null) {
                            try {
                                val depthPlane = depthImage.planes[0]
                                val confidencePlane = confidenceImage.planes[0]
                                val w = depthImage.width
                                val h = depthImage.height
                                cloudPacked = RawDepthCloud.buildCloud(
                                    depthBytes = depthPlane.buffer,
                                    depthRowStrideBytes = depthPlane.rowStride,
                                    confidenceBytes = confidencePlane.buffer,
                                    confidenceRowStrideBytes = confidencePlane.rowStride,
                                    width = w,
                                    height = h,
                                    confidenceThreshold = confidenceThreshold,
                                )
                                depthWidth = w
                                depthHeight = h
                                visiblePointCount = RawDepthCloud.pointCount(cloudPacked!!)
                                depthEverReceived = true
                                // Onboarding hint dismisses on the first frame that
                                // actually has points (motion-stereo has converged).
                                if (visiblePointCount > 0) {
                                    if (!hasShownHint) {
                                        hintVisible = false
                                        hasShownHint = true
                                    }
                                    zeroPointsSince = null
                                } else if (zeroPointsSince == null) {
                                    zeroPointsSince = System.currentTimeMillis()
                                }
                            } finally {
                                runCatching { depthImage.close() }
                                runCatching { confidenceImage.close() }
                            }
                        } else {
                            // ARCore returns null when raw depth isn't ready yet — that's
                            // normal in the first second after session start. Close anything
                            // we got, leave the previous cloud on screen, and try again next
                            // frame.
                            runCatching { depthImage?.close() }
                            runCatching { confidenceImage?.close() }
                        }
                    }
                },
                onTrackingFailureChanged = { reason ->
                    trackingFailureReason = reason
                },
            )

            // Compose `Canvas` overlay — sized to fill the AR view, mapping depth-image
            // pixel coordinates to fractional view coordinates.
            val packed = cloudPacked
            if (packed != null && depthWidth > 0 && depthHeight > 0) {
                val pointCount = RawDepthCloud.pointCount(packed)
                val widthF = depthWidth.toFloat()
                val heightF = depthHeight.toFloat()
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val canvasW = size.width
                    val canvasH = size.height
                    val pointRadius = 2.5f
                    for (i in 0 until pointCount) {
                        val px = RawDepthCloud.x(packed, i) / widthF * canvasW
                        val py = RawDepthCloud.y(packed, i) / heightF * canvasH
                        val argb = RawDepthCloud.argb(packed, i)
                        drawCircle(
                            color = Color(argb),
                            radius = pointRadius,
                            center = Offset(px, py),
                        )
                    }
                }
            }
        }
    }
}
