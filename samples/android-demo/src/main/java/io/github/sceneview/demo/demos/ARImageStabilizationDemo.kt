package io.github.sceneview.demo.demos

import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.configure
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener

/**
 * AR Electronic Image Stabilization (EIS) demo — toggle ARCore's hardware-assisted camera
 * stabilization and feel the difference under hand-shake.
 *
 * EIS is a single ARCore [Config] flag added in ARCore 1.37+. When enabled, ARCore smooths
 * the camera background image so that small hand jitter doesn't translate into perceived
 * judder of the AR scene. The virtual content stays anchored at the same world pose either
 * way — it's the *camera image* (and therefore the perceived overall motion) that is
 * stabilized. Useful for handheld AR, panoramic captures, or any video-style recording where
 * micro-shake is distracting.
 *
 * SceneView's `arsceneview/` library doesn't ship a wrapper because none is needed: the
 * effect is one line in `sessionConfiguration` (`config.imageStabilizationMode = …`). This
 * demo wires it up end-to-end so the toggle is discoverable in-app.
 *
 * Wiring summary:
 * 1. `sessionConfiguration` applies the *initial* EIS state on session creation. ARCore's
 *    [Config.ImageStabilizationMode] is a **runtime-reconfigurable** flag, so we don't tear
 *    the session down to flip it.
 * 2. A [LaunchedEffect] keyed on the user toggle calls [Session.configure] live whenever the
 *    switch flips. It first queries [Session.isImageStabilizationModeSupported] — if the
 *    device (or an AR recording played back) doesn't support [Config.ImageStabilizationMode.EIS],
 *    [eisSupported] is set `false`, the switch is disabled, and a banner explains why. This
 *    replaces the previous `key(eisOn)` full-session-rebuild, which silently invalidated the
 *    placed anchor on every toggle — the helmet (the demo's whole reference object) vanished
 *    the instant the user switched EIS, so the on/off comparison was impossible (#1475).
 * 3. The status pill reflects what ARCore *actually applied* (the result of the last
 *    reconfigure), not just the requested toggle — so an unsupported device honestly reads
 *    "EIS OFF" with the banner rather than a misleading "ON".
 * 4. Tap-to-place a single helmet GLB on a detected plane, mirroring [ARDepthOcclusionDemo].
 *    The fixed reference object is what makes the stabilization difference visible: the
 *    model stays glued to its world pose while the camera image around it goes from shaky
 *    (EIS OFF) to smooth (EIS ON).
 */
@Composable
fun ARImageStabilizationDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch - see
    // `rememberArPlaybackDataset` - so live AR is completely unchanged for real users.
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Hoisted so the model loads once for the whole demo, not on every re-placement.
    // The remember slot survives anchor clears + re-drops, so re-tapping is instant
    // instead of paying the ~200 ms GLB parse hitch each time.
    val helmetInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    // User toggle. Off by default so the user can flip it ON and *feel* the difference —
    // starting OFF is the more instructive cold-start state.
    var eisEnabled by remember { mutableStateOf(false) }

    // `null` = "we haven't yet been told whether the device supports EIS". Once the first
    // session reports `isImageStabilizationModeSupported`, we update [eisSupported] so the
    // controls drawer can disable the switch and surface a banner.
    var eisSupported by remember { mutableStateOf<Boolean?>(null) }

    // What ARCore *actually* applied after the last (re)configure. Drives the status pill so
    // it reflects the real session state, never just the requested toggle. Starts `false`
    // because the session boots with EIS OFF.
    var eisApplied by remember { mutableStateOf(false) }

    // Live ARCore session, captured from `onSessionUpdated`. Used by the LaunchedEffect below
    // to reconfigure EIS at runtime when the user flips the switch — no session teardown, so
    // the placed helmet anchor stays valid across toggles.
    var arSession by remember { mutableStateOf<Session?>(null) }

    var placedAnchor by remember { mutableStateOf<Anchor?>(null) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var isTracking by remember { mutableStateOf(false) }

    // Auto-placement guard. The demo's whole point — "model stays anchored while
    // the camera bg stabilizes" — only lands if the user *sees* a model on screen
    // the moment they open the demo. Without auto-place, a fresh-open shows the
    // raw camera feed with no virtual content; the user has no point of reference
    // to feel the EIS toggle and walks away unimpressed (see #1183).
    //
    // Once true, we never re-auto-place — Clear + manual re-tap stays in the
    // user's hands. Re-entering the demo resets the flag (remembered per scope).
    var autoPlaced by remember { mutableStateOf(false) }

    // Latest Frame for hit testing in the gesture callback.
    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    // Reconfigure ARCore's EIS mode live whenever the user flips the switch (or once the
    // session first becomes available). [Config.ImageStabilizationMode] is runtime-mutable,
    // so a single `Session.configure` call is enough — no session/anchor teardown.
    LaunchedEffect(arSession, eisEnabled) {
        val session = arSession ?: return@LaunchedEffect
        val supported = runCatching {
            session.isImageStabilizationModeSupported(Config.ImageStabilizationMode.EIS)
        }.getOrDefault(false)
        eisSupported = supported

        val wantEis = eisEnabled && supported
        runCatching {
            session.configure { config ->
                config.imageStabilizationMode = if (wantEis) {
                    Config.ImageStabilizationMode.EIS
                } else {
                    Config.ImageStabilizationMode.OFF
                }
            }
            // Read back what ARCore committed so the pill never over-promises.
            eisApplied = session.config.imageStabilizationMode ==
                Config.ImageStabilizationMode.EIS
        }.onFailure {
            // ARCore rejected the reconfigure (e.g. EIS unsupported on a recording
            // playback). Surface it honestly rather than leaving a stuck pill.
            eisSupported = false
            eisApplied = false
        }
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_image_stabilization_title),
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
                    text = "A helmet auto-places ~1 m in front of you the moment " +
                        "tracking stabilizes. Tap anywhere on a plane to relocate, " +
                        "or use Clear to remove.\n\n" +
                        "1. Toggle EIS on.\n" +
                        "2. Move the phone around — notice the camera feed is much smoother.\n" +
                        "3. Toggle off — judder returns.\n" +
                        "4. The virtual model stays anchored either way; only the camera " +
                        "background is stabilized.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }

            if (eisSupported == false) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "Your device doesn't support Electronic Image " +
                            "Stabilization. Toggle has no effect.",
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
                    text = "Image Stabilization (EIS)",
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = eisEnabled,
                    enabled = eisSupported != false,
                    onCheckedChange = { eisEnabled = it }
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
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Single, stable ARSceneView — NOT re-keyed on the EIS toggle. ARCore's
            // `imageStabilizationMode` is runtime-reconfigurable, so the LaunchedEffect
            // above flips it live via `Session.configure`. The previous `key(eisOn)`
            // rebuilt the whole session on every toggle, which invalidated the placed
            // helmet anchor — the demo's only reference object — making the on/off
            // comparison impossible (#1475).
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                planeRenderer = true,
                sessionConfiguration = { _: Session, config: Config ->
                    config.planeFindingMode =
                        Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    config.lightEstimationMode =
                        Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    // Boot with EIS OFF — the LaunchedEffect reconfigures it (and probes
                    // device support) as soon as the session is captured.
                    config.imageStabilizationMode = Config.ImageStabilizationMode.OFF
                },
                onSessionUpdated = { session: Session, frame: Frame ->
                    arSession = session
                    latestFrame = frame
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING

                    // Auto-place the helmet ~1 m in front of the camera on the
                    // first stable TRACKING frame. We don't wait for plane
                    // detection — the EIS demo doesn't need a plane-anchored
                    // pose to demonstrate "model stays glued while bg
                    // stabilizes", and waiting for ARCore's plane finder
                    // (5–10 s indoors) eats the first impression.
                    //
                    // The pose is built from the camera's world pose
                    // composed with a -1 m Z translation in the camera's
                    // local frame, so the helmet appears straight ahead at
                    // eye-level regardless of camera tilt.
                    if (!autoPlaced &&
                        placedAnchor == null &&
                        frame.camera.trackingState == TrackingState.TRACKING
                    ) {
                        val anchorPose = frame.camera.pose.compose(
                            Pose.makeTranslation(0f, 0f, -1.0f)
                        )
                        runCatching { session.createAnchor(anchorPose) }
                            .onSuccess { anchor ->
                                placedAnchor = anchor
                                autoPlaced = true
                            }
                    }
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
                            // user has one fixed reference to walk around for testing.
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
                                centerOrigin = Position(0.0f, 0.0f, 0.0f)
                            )
                        }
                    }
                }
            }

            // Top-center status pill — reflects what ARCore *actually applied* (the result
            // of the last reconfigure), so it never over-promises. Green when EIS is live,
            // grey when the device can't do EIS, red when EIS is off but available.
            // Big enough for a screenshot to read at a glance.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
                color = when {
                    eisApplied -> Color(0xFF1B5E20).copy(alpha = 0.85f)
                    eisSupported == false -> Color(0xFF424242).copy(alpha = 0.85f)
                    else -> Color(0xFFB71C1C).copy(alpha = 0.85f)
                },
                contentColor = Color.White,
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = when {
                        eisApplied -> "EIS ON"
                        eisSupported == false -> "EIS UNSUPPORTED"
                        else -> "EIS OFF"
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // Scanning / tracking-failure overlay — same vocabulary as ARPlacementDemo and
            // ARDepthOcclusionDemo so all three AR demos share the same visual language.
            AnimatedVisibility(
                visible = !isTracking,
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
                        text = trackingFailureMessage(trackingFailureReason)
                            ?: stringResource(R.string.ar_status_scanning),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}
