package io.github.sceneview.demo.demos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.SemanticLabel
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.semanticLabelFraction
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader

/**
 * AR demo — Scene Semantics: read ARCore's per-pixel 12-class outdoor semantic model and show
 * a live HUD listing the top-3 labels in view (SKY, BUILDING, TREE, ROAD, SIDEWALK, TERRAIN,
 * STRUCTURE, OBJECT, VEHICLE, PERSON, WATER, UNLABELED).
 *
 * Pipeline:
 *
 * 1. Configure the session with [Config.SemanticMode.ENABLED]. On devices without the on-device
 *    ML model, the session silently downgrades to `DISABLED` (`ARSession.configure` enforces
 *    the support gate via [io.github.sceneview.ar.arcore.resolveSemanticMode]) and the HUD
 *    surfaces a "not supported" banner — so the screen is never just black (#1617 principle).
 * 2. On every `onSessionUpdated`, call [Frame.semanticLabelFraction] for each of the 12 labels
 *    (this is a cheap GPU-backed query — no `Image` lifecycle to manage).
 * 3. Render the 3 highest fractions as a Compose overlay (top-left), updated each frame.
 *
 * This demo intentionally uses the lightweight `semanticLabelFraction` query rather than the
 * full `Frame.semanticImage()` raster overlay — the per-pixel label visualization is deferred
 * to a follow-up issue (custom `.filamat` shader port of `background_semantics.frag`) so that
 * matc-toolchain ABI work is sequenced after #1730 lands. The HUD is enough to demonstrate
 * the API and verify that semantics is wired and reading the right data.
 *
 * **Outdoor only.** The ARCore model has no indoor training data — pointing the camera at a
 * living-room wall will return mostly `UNLABELED`. Take this demo outside (street / park /
 * back yard).
 *
 * Closes part of [#1730](https://github.com/sceneview/sceneview/issues/1730) (Config wiring +
 * Frame accessors + simple HUD demo). The custom label-overlay material is tracked separately.
 */
@Composable
fun ARSceneSemanticsDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Capability gate: null while we wait for the first session.configure callback;
    // true if the device shipped the Scene Semantics ML model; false otherwise.
    // Driving the UI off this avoids the "black screen on unsupported device" trap (#1617).
    var semanticsSupported by remember { mutableStateOf<Boolean?>(null) }

    // Latest top-3 (label, fraction) snapshot. Captured each `onSessionUpdated` frame; rendered
    // as a Compose overlay. We snapshot the list rather than expose per-label State because the
    // 12-element computation per frame is cheap and the entire HUD recomposes together — no
    // need to atomize updates.
    var topLabels by remember { mutableStateOf<List<LabelFraction>>(emptyList()) }

    // True once we've seen at least one frame with semantic data. Drives a "warming up"
    // overlay so the user knows the model is initialising rather than broken.
    var semanticsEverReceived by remember { mutableStateOf(false) }

    var isTracking by remember { mutableStateOf(false) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_scene_semantics_title),
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
                        text = "Scene Semantics",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "ARCore classifies every pixel into 12 outdoor classes (SKY, " +
                            "BUILDING, TREE, ROAD, …). The HUD lists the 3 most-present " +
                            "labels in view. Outdoor only — indoor scenes are mostly UNLABELED.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (semanticsSupported == false) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "Your device doesn't have the ARCore Scene Semantics model. " +
                            "It ships on a subset of Google Play Services for AR devices — " +
                            "see ARCore docs for the supported device list.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            // Developer-only debug toggle — visible when QA mode is on. Lets QA
            // force-emit each TrackingFailureReason so the actionable-message
            // overlay can be validated without staging a real failure. See
            // io.github.sceneview.demo.common.ForcedTrackingFailure / #1881.
            ForceTrackingFailureMenu()
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                sessionConfiguration = { session: Session, config: Config ->
                    // Capability probe — uses ARCore's `isSemanticModeSupported` so we set the
                    // mode only when the on-device ML model is present. `ARSession.configure`
                    // applies the same gate as a defense-in-depth fallback (resolveSemanticMode).
                    val supported = session.isSemanticModeSupported(Config.SemanticMode.ENABLED)
                    semanticsSupported = supported
                    config.semanticMode = if (supported) {
                        Config.SemanticMode.ENABLED
                    } else {
                        Config.SemanticMode.DISABLED
                    }
                },
                onSessionUpdated = { _, frame: Frame ->
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    if (semanticsSupported == true && isTracking) {
                        // Cheap per-label fractions — semanticLabelFraction is a GPU-backed
                        // query, no Image acquire/close lifecycle. Returns 0f when semantics
                        // not yet available (which is the natural placement-rule default).
                        val snapshot = SemanticLabel.values()
                            .map { LabelFraction(it, frame.semanticLabelFraction(it)) }
                            .sortedByDescending { it.fraction }
                            .take(3)
                        topLabels = snapshot
                        if (snapshot.any { it.fraction > 0f }) {
                            semanticsEverReceived = true
                        }
                    }
                },
                onTrackingFailureChanged = { reason ->
                    trackingFailureReason = reason
                },
            )

            // Top-left HUD listing the 3 highest semantic-label fractions. Hidden until the
            // first non-zero update so the empty list doesn't show during startup warmup.
            if (semanticsSupported == true && semanticsEverReceived && topLabels.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    color = Color.Black.copy(alpha = 0.65f),
                    contentColor = Color.White
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            text = "Scene Semantics",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(6.dp))
                        topLabels.forEach { (label, fraction) ->
                            LabelRow(label, fraction)
                        }
                    }
                }
            }

            // Warming-up overlay — shown when supported but no semantic data yet (first
            // ~5 frames after resume on the typical device). Crucial: never leave the user
            // staring at a black screen (see #1617) if semantics takes a moment to warm up.
            AnimatedVisibility(
                visible = semanticsSupported == true && !semanticsEverReceived,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = "Warming up Scene Semantics — point the camera at an outdoor scene",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // Tracking-failure overlay — same vocabulary as the other AR demos.
            // ForcedTrackingFailure.override shadows the real ARCore-reported reason
            // when a developer has picked one in the debug menu (#1881). Read it here
            // so flipping the override re-renders the overlay immediately.
            val effectiveReason = ForcedTrackingFailure.override ?: trackingFailureReason
            AnimatedVisibility(
                visible = (!isTracking && trackingFailureReason != null) ||
                    ForcedTrackingFailure.override != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = when (effectiveReason) {
                            TrackingFailureReason.INSUFFICIENT_LIGHT -> "Not enough light"
                            TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast"
                            TrackingFailureReason.INSUFFICIENT_FEATURES ->
                                "Not enough detail — point at a textured outdoor scene"
                            TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable"
                            TrackingFailureReason.BAD_STATE -> "AR session error"
                            else -> stringResource(R.string.ar_status_scanning)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

/** Snapshot of one ARCore semantic-class fraction for the current frame. */
private data class LabelFraction(val label: SemanticLabel, val fraction: Float)

/** Single row of the HUD — semi-mono label name + a small bar + a percentage. */
@Composable
private fun LabelRow(label: SemanticLabel, fraction: Float) {
    val clampedFraction = fraction.coerceIn(0f, 1f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label.name.padEnd(10),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = Color.White
        )
        // Inline progress bar — width proportional to fraction. Drawn as a Box so we don't
        // pull in LinearProgressIndicator (which animates and would look jittery at ~30 Hz
        // semantic updates).
        Box(
            modifier = Modifier
                .height(8.dp)
                .width((80f * clampedFraction).dp)
                .background(
                    color = Color(0xFF4FC3F7),
                    shape = RoundedCornerShape(4.dp)
                )
        )
        Text(
            text = "${(clampedFraction * 100).toInt()} %",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = Color.White
        )
    }
}
