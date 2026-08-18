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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.SemanticLabel
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.semanticLabelFraction
import io.github.sceneview.ar.createARCameraStream
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.common.SceneAction
import io.github.sceneview.demo.common.SceneActionBar
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener

/**
 * AR people-occlusion demo — virtual objects correctly hide behind real **people** (#1761).
 *
 * `arsceneview/`'s [io.github.sceneview.ar.camera.ARCameraStream] ships a people-occlusion
 * camera material that samples ARCore Scene Semantics' `PERSON`-class segmentation mask and
 * pushes the camera fragment to the near plane wherever a real person is detected — so
 * virtual content behind the person is depth-tested away. This is the flagship
 * cross-ecosystem AR effect (ARKit `ARFrame.segmentationBuffer`, AR Foundation
 * `AROcclusionManager`, Niantic Lightship).
 *
 * Wiring summary:
 * 1. The session is configured with [Config.SemanticMode.ENABLED] when the toggle is ON
 *    (and [Config.DepthMode.AUTOMATIC] so the people material's depth-occlusion path keeps
 *    static geometry occluding too). When OFF both downgrade to `DISABLED`.
 * 2. The camera stream's [io.github.sceneview.ar.camera.ARCameraStream.isPersonOcclusionEnabled]
 *    flag is set at construction time — re-keying the whole `ARSceneView` on the toggle is
 *    the boring-and-correct way to swap the camera-background material cleanly (same
 *    strategy as `ARDepthOcclusionDemo`).
 * 3. Tapping a detected plane spawns a single helmet model so the user has one fixed
 *    reference object to walk behind.
 *
 * The HUD shows the live PERSON-pixel fraction (`Frame.semanticLabelFraction(PERSON)`) so the
 * tester can confirm ARCore is actually classifying a person in frame.
 *
 * **Limitations** (surfaced honestly to the user): ARCore Scene Semantics is outdoor-trained
 * — indoor people may not be classified, and on devices without the on-device model the
 * effect is a silent no-op. See [io.github.sceneview.ar.camera.ARCameraStream.isPersonOcclusionEnabled].
 */
@Composable
fun ARPeopleOcclusionDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo.
    // `null` for every normal launch — see `rememberArPlaybackDataset`.
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Hoisted so the model loads once for the whole demo, surviving the camera-stream
    // rebuild triggered by the people-occlusion toggle.
    val helmetInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    var peopleOcclusionEnabled by remember { mutableStateOf(true) }
    // `null` until the first session reports whether the device supports Scene Semantics.
    var semanticsSupported by remember { mutableStateOf<Boolean?>(null) }

    var placedAnchor by remember { mutableStateOf<Anchor?>(null) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var isTracking by remember { mutableStateOf(false) }
    // Live PERSON-pixel fraction for the HUD — `0f` when no person is in frame.
    var personFraction by remember { mutableFloatStateOf(0f) }

    // Latest Frame for hit testing in the gesture callback.
    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    // The toggle drives both ARCore's session config AND the camera stream's material
    // selection. Snapshot the value so the keyed scope below sees a stable boolean.
    val peopleOn = peopleOcclusionEnabled && (semanticsSupported != false)

    DemoScaffold(
        title = stringResource(R.string.demo_ar_people_occlusion_title),
        onBack = onBack,
        controls = {
            Text(
                text = "Tap a flat surface to place the model, then have someone walk in " +
                    "front of it — the person should occlude the model.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (semanticsSupported == false) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "Your device doesn't support ARCore Scene Semantics. " +
                            "People occlusion is disabled.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Scene Semantics is outdoor-trained — works best outside, in good " +
                    "light, with the whole person in view.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "People occlusion",
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = peopleOcclusionEnabled,
                    enabled = semanticsSupported != false,
                    onCheckedChange = { peopleOcclusionEnabled = it }
                )
            }

            // Developer-only debug toggle — visible when QA mode is on.
            ForceTrackingFailureMenu()
        },
        // Status pill — green when people occlusion is ON, red when OFF, and showing
        // the live PERSON-pixel fraction so QA can confirm the model is actually
        // classifying a person.
        topOverlay = {
            Surface(
                color = if (peopleOn) {
                    Color(0xFF1B5E20).copy(alpha = 0.85f)
                } else {
                    Color(0xFFB71C1C).copy(alpha = 0.85f)
                },
                contentColor = Color.White,
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = if (peopleOn) {
                        "PEOPLE OCCLUSION ON · person ${(personFraction * 100).toInt()}%"
                    } else {
                        "PEOPLE OCCLUSION OFF"
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        bottomOverlay = {
            // Scanning / tracking-failure overlay — same vocabulary as ARDepthOcclusionDemo.
            val effectiveReason = ForcedTrackingFailure.override ?: trackingFailureReason
            AnimatedVisibility(
                visible = !isTracking || ForcedTrackingFailure.override != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                val trackingHint = trackingFailureMessage(effectiveReason)
                DemoStatusBanner(
                    text = trackingHint ?: stringResource(R.string.ar_status_scanning),
                    // A reported tracking failure always asks the user to move the
                    // phone (more light, slower motion, a textured surface); plain
                    // scanning is a normal transient state.
                    tone = if (trackingHint != null) {
                        DemoStatusTone.Guidance
                    } else {
                        DemoStatusTone.Progress
                    },
                )
            }

            // Primary action on-screen (#1964) — Clear re-arms placement.
            if (placedAnchor != null) {
                SceneActionBar(
                    SceneAction("Clear", onClick = {
                        placedAnchor?.let { runCatching { it.detach() } }
                        placedAnchor = null
                    }),
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Re-keying the scene rebuilds the camera stream from scratch — the boring-and-
            // correct way to swap the camera-background material (same as ARDepthOcclusionDemo).
            key(peopleOn) {
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

                        // Scene Semantics is the segmentation surface that drives people
                        // occlusion. Probe support once per reconfigure so the user sees
                        // *why* the toggle does nothing on unsupported hardware.
                        val supported =
                            session.isSemanticModeSupported(Config.SemanticMode.ENABLED)
                        semanticsSupported = supported

                        if (peopleOn && supported) {
                            config.semanticMode = Config.SemanticMode.ENABLED
                            // The people-occlusion material keeps the depth path so static
                            // real-world geometry still occludes — enable depth too.
                            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                                config.depthMode = Config.DepthMode.AUTOMATIC
                            }
                        } else {
                            config.semanticMode = Config.SemanticMode.DISABLED
                            config.depthMode = Config.DepthMode.DISABLED
                        }
                    },
                    // Keyed on `peopleOn` above, so the creator lambda runs once per toggle
                    // flip — set the flag at construction time for a clean material swap.
                    cameraStream = rememberARCameraStream(
                        materialLoader = materialLoader,
                        creator = {
                            createARCameraStream(materialLoader).apply {
                                isPersonOcclusionEnabled = peopleOn
                            }
                        }
                    ),
                    onSessionUpdated = { _, frame: Frame ->
                        latestFrame = frame
                        isTracking = frame.camera.trackingState == TrackingState.TRACKING
                        // Cheap GPU-side fraction — no full semantic-image walk needed.
                        personFraction = frame.semanticLabelFraction(SemanticLabel.PERSON)
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
                                placedAnchor?.let { runCatching { it.detach() } }
                                placedAnchor = hit.createAnchor()
                            }
                        }
                    )
                ) {
                    placedAnchor?.let { anchor ->
                        helmetInstance?.let { instance ->
                            AnchorNode(anchor = anchor) {
                                ModelNode(
                                    modelInstance = instance,
                                    scaleToUnits = 0.3f,
                                    // The bundled DamagedHelmet GLB carries a residual +90° X
                                    // root rotation — correct it at placement time (#1477).
                                    rotation = DemoMath.placementRotationFor(DemoMath.HELMET_ASSET)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
