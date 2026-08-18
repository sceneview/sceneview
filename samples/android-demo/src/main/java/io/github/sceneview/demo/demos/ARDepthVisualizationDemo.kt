package io.github.sceneview.demo.demos

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import io.github.sceneview.demo.demos.internal.DepthVisualization
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader

/**
 * AR demo — render ARCore's environment depth image as a false-color overlay on
 * top of the live camera feed, with a slider that blends camera (0.0) ↔ depth
 * visualization (1.0).
 *
 * Pipeline:
 *
 * 1. Configure the session with [Config.DepthMode.AUTOMATIC]. On devices that
 *    don't support it, the toggle is greyed out and an explanatory banner appears
 *    so the screen is never just black (a repo non-negotiable — see #1617).
 * 2. On every `onSessionUpdated`, acquire the depth image
 *    ([Frame.acquireDepthImage16Bits]), copy the 16-bit unsigned millimetre buffer
 *    into an `IntArray` of ARGB-coloured pixels via
 *    [DepthVisualization.depthBufferToArgb], then upload it into a recycled
 *    Compose [Bitmap]. ARCore delivers depth in the landscape **camera sensor** frame,
 *    so the colorize pass also turns it to match the display rotation — without that,
 *    the overlay sits 90° off the camera feed in portrait (#3184).
 * 3. Render the bitmap as an [Image] overlay sized to fill the [ARSceneView],
 *    with `alpha = slider`. At `0.0` the overlay is invisible and the live
 *    camera feed shows through; at `1.0` the overlay covers everything.
 *
 * The slider blends *visually* through Compose's alpha channel — this is the
 * "obvious, correct" path because ARCore's depth image (typically ~240×180) is
 * far smaller than the camera feed, so we let Compose's `ContentScale.Crop`
 * upscale it instead of trying to feed both into a Filament material variant.
 * The cost (one bitmap allocation per depth-resolution change, one CPU pass per
 * frame to colorize) is acceptable for a demo and keeps the pure colorize logic
 * in JVM-testable Kotlin.
 *
 * Closes [#1714](https://github.com/sceneview/sceneview/issues/1714).
 */
@Composable
fun ARDepthVisualizationDemo(onBack: () -> Unit) {
    val context = LocalContext.current
    // Resolved once: the Display *object* is stable for the lifetime of this context, while
    // `rotation` on it is live — so the per-frame read below stays cheap and never has to
    // touch a `Context` that may not be a visual one. `Context.display` was added in API 30;
    // fall back to the deprecated accessor on API 28–29, same as ARMLObjectLabelDemo.
    val display = remember(context) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            }
        }.getOrNull()
    }
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Blend factor [0..1]. 0 = camera feed only; 1 = depth false-color only.
    var blend by remember { mutableStateOf(0.6f) }
    var depthSupported by remember { mutableStateOf<Boolean?>(null) }
    var depthEverReceived by remember { mutableStateOf(false) }
    var isTracking by remember { mutableStateOf(false) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }

    // Mutable bitmap; reallocated when the depth resolution changes. Keeping a
    // single bitmap across frames avoids per-frame GC pressure during the demo.
    var depthBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var bitmapWidth by remember { mutableStateOf(0) }
    var bitmapHeight by remember { mutableStateOf(0) }
    // Version counter incremented after every successful upload so Compose
    // recomposes the Image even though the Bitmap reference is unchanged.
    var depthFrameVersion by remember { mutableStateOf(0) }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_depth_visualization_title),
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
                        text = "Warm colors (red/yellow) = near (~0.3 m). " +
                            "Cool colors (cyan/blue) = far (~5 m). " +
                            "Transparent pixels = no depth data.",
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
                        text = "Your device doesn't support the ARCore Depth API. " +
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
                    text = "Camera",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Depth",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Slider(
                value = blend,
                onValueChange = { blend = DepthVisualization.clampUnit(it) },
                enabled = depthSupported != false,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Blend: ${(blend * 100).toInt()} %",
                style = MaterialTheme.typography.labelMedium
            )
            // Developer-only debug toggle — visible when QA mode is on. Lets QA
            // force-emit each TrackingFailureReason so the actionable-message
            // overlay can be validated without staging a real failure. See
            // io.github.sceneview.demo.common.ForcedTrackingFailure / #1881.
            ForceTrackingFailureMenu()
        },
        // "Waiting for depth" overlay — shown until the first depth frame
        // lands. Crucial: never leave the user staring at a black screen
        // (see #1617) if depth takes a moment to warm up.
        topOverlay = {
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
                        text = "Warming up depth — move the camera slowly across a textured scene",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        },
        // Legend pill + tracking-failure banner are hosted by the scaffold's
        // `bottomOverlay` slot, a bottom-aligned Column: they stack instead of sharing
        // the same band, and the banner is laid out against the Settings FAB (#2779).
        bottomOverlay = {
            // Bottom legend pill — only relevant once we're actually showing depth.
            if (depthEverReceived && blend > 0.05f) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "Near (~0.3 m) ──── Far (~5 m)",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }

            // Tracking-failure overlay — same vocabulary as ARPlacementDemo.
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
                // Tone follows the same branches as the text: a dead camera or session is
                // Blocked, the reasons the user can act on physically are Guidance, and the
                // plain "scanning" fallback is a normal transient state.
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
                DemoStatusBanner(statusText, tone = statusTone)
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
                    val supported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                    depthSupported = supported
                    config.depthMode = if (supported) {
                        Config.DepthMode.AUTOMATIC
                    } else {
                        Config.DepthMode.DISABLED
                    }
                },
                onSessionUpdated = { _, frame: Frame ->
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    if (depthSupported == true && isTracking) {
                        val depthImage = runCatching { frame.acquireDepthImage16Bits() }.getOrNull()
                        if (depthImage != null) {
                            try {
                                val plane = depthImage.planes[0]
                                val w = depthImage.width
                                val h = depthImage.height
                                val rowStride = plane.rowStride
                                // Read the rotation per frame, not once at composition: the
                                // display can turn between two depth frames and the overlay
                                // has to follow it in the same frame the camera feed does.
                                val rotation = DepthVisualization.displayRotationToDegrees(
                                    display?.rotation ?: Surface.ROTATION_0
                                )
                                val pixels = DepthVisualization.depthBufferToArgb(
                                    depthBytes = plane.buffer,
                                    width = w,
                                    height = h,
                                    rowStrideBytes = rowStride,
                                    rotationDegrees = rotation,
                                )
                                // Post-rotation dimensions — swapped on a quarter turn, which
                                // is the portrait case (#3184).
                                val outW = DepthVisualization.rotatedWidth(w, h, rotation)
                                val outH = DepthVisualization.rotatedHeight(w, h, rotation)
                                // Reallocate the bitmap if the depth resolution — or the
                                // orientation it is displayed at — changed.
                                if (depthBitmap == null ||
                                    outW != bitmapWidth ||
                                    outH != bitmapHeight
                                ) {
                                    depthBitmap =
                                        Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                                    bitmapWidth = outW
                                    bitmapHeight = outH
                                }
                                depthBitmap?.setPixels(pixels, 0, outW, 0, 0, outW, outH)
                                depthFrameVersion++
                                depthEverReceived = true
                            } finally {
                                runCatching { depthImage.close() }
                            }
                        }
                    }
                },
                onTrackingFailureChanged = { reason ->
                    trackingFailureReason = reason
                },
            )

            // Read the version so Compose recomposes when a new depth frame arrives.
            val versionRead = depthFrameVersion
            val bitmap = depthBitmap
            if (bitmap != null && versionRead >= 0) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = blend,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
