package io.github.sceneview.demo.demos

import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.createARCameraStream
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener

/**
 * AR depth-occlusion demo — virtual objects correctly hide behind real-world geometry.
 *
 * `arsceneview/` ships a depth-aware camera material
 * ([ARCameraStream][io.github.sceneview.ar.camera.ARCameraStream]) that samples ARCore's depth
 * image to occlude virtual content. This demo wires it up end-to-end so the effect is visible
 * in the demo app.
 *
 * Wiring summary:
 * 1. The session is configured with [Config.DepthMode.AUTOMATIC] when the toggle is ON, and
 *    [Config.DepthMode.DISABLED] when OFF. The library's `ARCameraStream.update()` reads
 *    `session.config.depthMode` every frame and uploads depth pixels to the depth texture
 *    only when a supported mode is active.
 * 2. The camera-stream component picks the depth-occlusion material when both the toggle is
 *    ON *and* `isDepthOcclusionEnabled` is set. We forward the same boolean to both layers via
 *    a `cameraStream` callback so the user toggle drives them in lockstep.
 * 3. Tapping a detected plane spawns a single helmet model (replacing any previous placement)
 *    so the user has one fixed reference object to walk around for testing.
 *
 * Recreating the AR scene on toggle (with `key(occlusionEnabled)`) is intentional: ARCore
 * accepts runtime [Session.configure] calls, but the Filament renderable behind
 * `ARCameraStream` only switches its material when `isDepthOcclusionEnabled` flips. Wrapping
 * the whole `ARSceneView` in `key(...)` is the simplest correct strategy — it tears down the
 * engine + session, then rebuilds with the new config. The cost (one engine restart per
 * toggle) is paid only on user interaction and keeps the depth/non-depth path identical to a
 * cold launch.
 *
 * Devices that don't support [Config.DepthMode.AUTOMATIC] surface a banner and the toggle is
 * disabled — `Session.isDepthModeSupported(...)` is the canonical check.
 */
@Composable
fun ARDepthOcclusionDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch - see
    // `rememberArPlaybackDataset` - so live AR is completely unchanged for real users.
    // Hoisted above the `key(depthOn)` block so the resolved dataset is captured once and
    // survives the camera-stream rebuild on the depth toggle.
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Hoisted so the model loads once for the whole demo, not on every re-placement.
    // The remember slot survives anchor clears + re-drops, so re-tapping is instant
    // instead of paying the ~200 ms GLB parse hitch each time.
    val helmetInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    // User toggle. `null` = "we haven't yet been told whether the device supports depth".
    // Once the first session reports `isDepthModeSupported`, we update [depthSupported].
    var occlusionEnabled by remember { mutableStateOf(true) }
    var depthSupported by remember { mutableStateOf<Boolean?>(null) }

    var placedAnchor by remember { mutableStateOf<Anchor?>(null) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var isTracking by remember { mutableStateOf(false) }

    // Latest Frame for hit testing in the gesture callback.
    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    // The toggle ultimately drives both ARCore's session config AND the camera stream's
    // material selection. Snapshot the value so the keyed scope below sees a stable boolean.
    val depthOn = occlusionEnabled && (depthSupported != false)

    DemoScaffold(
        title = stringResource(R.string.demo_ar_depth_occlusion_title),
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
                    text = "1. Tap a flat surface to place the model.\n" +
                        "2. Walk around it, or pass your hand between the camera and the model.\n" +
                        "3. With Depth ON, real objects in front correctly hide the virtual " +
                        "object. With Depth OFF, the model is always drawn on top — wrong, " +
                        "but instructive.",
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
                            "Occlusion is disabled.",
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
                    text = "Depth occlusion",
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = occlusionEnabled,
                    enabled = depthSupported != false,
                    onCheckedChange = { occlusionEnabled = it }
                )
            }

            OutlinedButton(
                onClick = {
                    placedAnchor?.let { runCatching { it.detach() } }
                    placedAnchor = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text("Clear")
            }
            // Developer-only debug toggle — visible when QA mode is on. Lets QA
            // force-emit each TrackingFailureReason so the actionable-message
            // overlay can be validated without staging a real failure. See
            // io.github.sceneview.demo.common.ForcedTrackingFailure / #1881.
            ForceTrackingFailureMenu()
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Toggling the camera-stream material requires a clean swap: the depth and flat
            // materials live on the same Filament renderable, but flipping
            // `isDepthOcclusionEnabled` mid-session can leave the renderer in an inconsistent
            // state if a depth frame is queued. Re-keying the scene rebuilds the camera
            // stream from scratch, which is the boring-and-correct path.
            key(depthOn) {
                ARSceneView(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    playbackDataset = arPlaybackDataset,
                    planeRenderer = true,
                    sessionConfiguration = { session: Session, config: Config ->
                        config.planeFindingMode =
                            Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        config.lightEstimationMode =
                            Config.LightEstimationMode.ENVIRONMENTAL_HDR

                        // Probe device support exactly once per session reconfigure. The
                        // library's ARCameraStream gates depth-image acquisition on
                        // `session.config.depthMode`, so requesting AUTOMATIC on an
                        // unsupported device is harmless (ARCore falls back) but we still
                        // want to show the user *why* their toggle does nothing.
                        val supported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                        depthSupported = supported

                        config.depthMode = if (depthOn && supported) {
                            Config.DepthMode.AUTOMATIC
                        } else {
                            Config.DepthMode.DISABLED
                        }
                    },
                    // The whole ARSceneView tree is keyed on `depthOn` above, so the
                    // creator lambda runs once per toggle flip — set the depth flag at
                    // construction time. This avoids the side-effecting `.apply` on every
                    // recomposition, and the keyed remount is what guarantees a clean
                    // material swap (the depth and flat materials live on the same Filament
                    // renderable; flipping the flag mid-session can leave a queued depth
                    // frame in an inconsistent state).
                    cameraStream = rememberARCameraStream(
                        materialLoader = materialLoader,
                        creator = {
                            createARCameraStream(materialLoader).apply {
                                isDepthOcclusionEnabled = depthOn
                            }
                        }
                    ),
                    onSessionUpdated = { _, frame: Frame ->
                        latestFrame = frame
                        isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    },
                    onTrackingFailureChanged = { reason ->
                        trackingFailureReason = reason
                    },
                    onGestureListener = rememberOnGestureListener(
                        onSingleTapConfirmed = { event: MotionEvent, node ->
                            if (node != null) return@rememberOnGestureListener

                            val frame = latestFrame ?: return@rememberOnGestureListener
                            if (frame.camera.trackingState != TrackingState.TRACKING) {
                                return@rememberOnGestureListener
                            }

                            val hit = frame.hitTest(event).firstOrNull { result ->
                                val trackable = result.trackable
                                trackable is Plane &&
                                    trackable.isPoseInPolygon(result.hitPose) &&
                                    result.distance <= 5.0f
                            }
                            if (hit != null) {
                                // Replace any previous placement — single-model demo so the
                                // user has one fixed reference to walk around.
                                placedAnchor?.let { runCatching { it.detach() } }
                                placedAnchor = hit.createAnchor()
                            }
                        }
                    )
                ) {
                    // Wait for both the anchor AND the hoisted model instance — the latter
                    // can still be null on the very first frame while the GLB loads in the
                    // background. After that load, the instance is reused across every
                    // re-placement (anchor clear + re-drop) without reloading.
                    placedAnchor?.let { anchor ->
                        helmetInstance?.let { instance ->
                            AnchorNode(anchor = anchor) {
                                ModelNode(
                                    modelInstance = instance,
                                    scaleToUnits = 0.3f,
                                    centerOrigin = Position(0.0f, 0.0f, 0.0f),
                                    // The bundled DamagedHelmet GLB carries a residual +90° X
                                    // root rotation that lands it face-down on the plane —
                                    // correct it at placement time. See #1477.
                                    rotation = DemoMath.placementRotationFor(DemoMath.HELMET_ASSET)
                                )
                            }
                        }
                    }
                }
            }

            // Top-center status pill — green tint when depth is ON, red tint when OFF.
            // Big enough for a screenshot to read at a glance.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
                color = if (depthOn) {
                    Color(0xFF1B5E20).copy(alpha = 0.85f)
                } else {
                    Color(0xFFB71C1C).copy(alpha = 0.85f)
                },
                contentColor = Color.White,
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = if (depthOn) "DEPTH ON" else "DEPTH OFF",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // Scanning / tracking-failure overlay — same vocabulary as ARPlacementDemo.
            // ForcedTrackingFailure.override shadows the real ARCore-reported reason
            // when a developer has picked one in the debug menu (#1881). Read it here
            // so flipping the override re-renders the overlay immediately, without
            // waiting for the next ARCore tracking-failure callback.
            val effectiveReason = ForcedTrackingFailure.override ?: trackingFailureReason
            AnimatedVisibility(
                visible = !isTracking || ForcedTrackingFailure.override != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    modifier = Modifier.padding(bottom = 32.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = trackingFailureMessage(effectiveReason)
                            ?: stringResource(R.string.ar_status_scanning),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}
