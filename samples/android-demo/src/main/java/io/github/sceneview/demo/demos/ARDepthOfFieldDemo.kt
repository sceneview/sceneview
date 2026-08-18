package io.github.sceneview.demo.demos

import android.view.MotionEvent
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.createARCameraStream
import io.github.sceneview.ar.postprocessing.ARDepthOfFieldOptions
import io.github.sceneview.ar.postprocessing.arDepthOfField
import io.github.sceneview.ar.postprocessing.depthFocusDistance
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberARView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.sample.ui.LabeledSlider
import java.util.Locale

/**
 * AR depth-of-field demo — Filament's native DoF post-pass driven by ARCore environment depth.
 *
 * The trick: `arsceneview/`'s depth-occlusion material already writes the real-world depth into
 * Filament's z-buffer via `gl_FragDepth` (see `camera_stream_depth.mat`). Filament's DoF samples
 * the same z-buffer, so turning on `View.DepthOfFieldOptions.enabled = true` automatically blurs
 * both the virtual scene and the camera background according to their distance from the focus
 * point — without any new render pass and without recompiling any `.filamat`. See #1716 for the
 * full investigation; the upshot is that the issue's question "can Filament's existing DoF pass
 * be fed an external depth target?" answers itself once you remember the camera stream already
 * populates the same depth target Filament samples.
 *
 * Wiring summary:
 *  1. Session is configured with [Config.DepthMode.AUTOMATIC] (required — without it the depth
 *     buffer holds only virtual content and the background never blurs).
 *  2. [ARCameraStream.isDepthOcclusionEnabled] is forced on (required — that's the toggle that
 *     swaps in the gl_FragDepth-writing camera material).
 *  3. [arDepthOfField] applies the user-facing knobs (focus depth + blur strength) to the shared
 *     Filament [com.google.android.filament.View] and [com.google.android.filament.Camera] via
 *     a SideEffect, so toggles and slider drags take effect on the next rendered frame.
 *  4. Tapping anywhere drives focus through [Frame.depthFocusDistance]: it reuses the depth
 *     hit-test added in #1712, returning the real-world distance to the tapped pixel.
 *
 * Acceptance per the issue: tapping a near object throws the far background out of focus, and
 * vice-versa; the effect is opt-in and off by default; no measurable cost when disabled because
 * Filament fully skips the DoF post-pass when [View.DepthOfFieldOptions.enabled] is `false`.
 */
@Composable
fun ARDepthOfFieldDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo with
    // `--es ar_playback_file <path>` (#1576). `null` for every normal launch, so live AR is
    // unchanged for real users.
    val arPlaybackDataset = rememberArPlaybackDataset()

    val toyCarInstance = rememberModelInstance(modelLoader, "models/khronos_toy_car.glb")

    // ── User-facing knobs ────────────────────────────────────────────────────────────────────
    var dofEnabled by remember { mutableStateOf(true) }
    var focusDepth by remember { mutableStateOf(1.0f) }     // meters
    // Filament's stock cinematic strength (cocScale identity). 2.0× doubled the circle-of-
    // confusion and crushed out-of-focus regions to black bands / colour smears (#2480) — it read
    // as a glitch, not bokeh. 1.0× gives a pleasant, legible shallow-DoF; the slider still goes to
    // 6× for anyone who wants a stronger blur.
    var blurStrength by remember { mutableStateOf(1.0f) }
    var depthSupported by remember { mutableStateOf<Boolean?>(null) }

    // Latest Frame for tap-to-focus. The depth API requires the Frame from the same update tick.
    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    // ── Filament resources, shared with arDepthOfField ───────────────────────────────────────
    val view = rememberARView(engine)
    val cameraNode = rememberARCameraNode(engine)

    // ── Drive Filament's DoF + camera focus distance via a SideEffect ───────────────────────
    arDepthOfField(
        view = view,
        camera = cameraNode,
        options = ARDepthOfFieldOptions(
            focusDepth = focusDepth,
            blurStrength = blurStrength,
            enabled = dofEnabled && (depthSupported != false),
        ),
    )

    DemoScaffold(
        title = stringResource(R.string.demo_ar_depth_of_field_title),
        onBack = onBack,
        controls = {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "How to test",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp)
                )
                Text(
                    text = "1. Walk around to scan a few flat surfaces.\n" +
                        "2. Tap anywhere — the tapped depth becomes the focus distance.\n" +
                        "3. Tap a near object → the far background blurs.\n" +
                        "4. Tap a far object → the near foreground blurs.\n" +
                        "5. Drag the Blur slider to scale the bokeh.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
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
                        text = "Your device doesn't support ARCore Depth API. " +
                            "Depth-of-field is disabled.",
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
                    text = "Depth-of-field",
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = dofEnabled,
                    enabled = depthSupported != false,
                    onCheckedChange = { dofEnabled = it }
                )
            }

            Spacer(Modifier.height(8.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                LabeledSlider(
                    label = "Focus depth",
                    value = focusDepth,
                    onValueChange = { focusDepth = it },
                    valueRange = 0.1f..5.0f,
                    valueText = "%.2f m".format(Locale.US, focusDepth),
                    enabled = depthSupported != false,
                )
            }

            Spacer(Modifier.height(4.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                LabeledSlider(
                    label = "Blur strength",
                    value = blurStrength,
                    onValueChange = { blurStrength = it },
                    valueRange = 0f..6f,
                    valueText = "%.2f×".format(Locale.US, blurStrength),
                    enabled = depthSupported != false,
                )
            }

            OutlinedButton(
                onClick = {
                    focusDepth = 1.0f
                    blurStrength = 1.0f
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text("Reset")
            }
        },
        // HUD pill — shows the current focus distance so screenshots make the
        // before/after diff obvious.
        topOverlay = {
            Surface(
                color = if (dofEnabled) {
                    Color(0xFF1B5E20).copy(alpha = 0.85f)
                } else {
                    Color(0xFF424242).copy(alpha = 0.85f)
                },
                contentColor = Color.White,
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = if (dofEnabled) "FOCUS %.2f m".format(Locale.US, focusDepth) else "DOF OFF",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                view = view,
                cameraNode = cameraNode,
                planeRenderer = false,
                sessionConfiguration = { session: Session, config: Config ->
                    config.planeFindingMode =
                        Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    config.lightEstimationMode =
                        Config.LightEstimationMode.ENVIRONMENTAL_HDR

                    // Probe device support so the toggle and sliders can disable cleanly when
                    // the device has no depth camera.
                    val supported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                    depthSupported = supported

                    config.depthMode = if (supported) {
                        Config.DepthMode.AUTOMATIC
                    } else {
                        Config.DepthMode.DISABLED
                    }
                },
                // Force depth occlusion ON: that's what installs the gl_FragDepth-writing camera
                // material, which is what populates Filament's z-buffer with real-world depth.
                // Without it, only virtual content lands in the depth buffer and the camera
                // background stays sharp regardless of focusDepth.
                cameraStream = rememberARCameraStream(
                    materialLoader = materialLoader,
                    creator = {
                        createARCameraStream(materialLoader).apply {
                            isDepthOcclusionEnabled = true
                        }
                    }
                ),
                onSessionUpdated = { _, frame: Frame ->
                    latestFrame = frame
                },
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { event: MotionEvent, _ ->
                        // Tap-to-focus: reuse the depth hit-test API (#1712). The returned
                        // distance is the real-world meters to the tapped pixel — exactly the
                        // value Filament's Camera.setFocusDistance wants.
                        val frame = latestFrame ?: return@rememberOnGestureListener
                        if (frame.camera.trackingState != TrackingState.TRACKING) {
                            return@rememberOnGestureListener
                        }
                        frame.depthFocusDistance(event.x, event.y)?.let { d ->
                            focusDepth = d
                        }
                    }
                )
            ) {
                // A single placement-free toy car at 1 m in front of the user gives a stable
                // virtual reference object to focus on against the real-world background. Plane
                // tap-to-place is intentionally not wired here — the demo's point is the DoF
                // effect, not anchor placement (which has its own dedicated demos).
                toyCarInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.3f,
                    )
                }
            }
        }
    }
}
