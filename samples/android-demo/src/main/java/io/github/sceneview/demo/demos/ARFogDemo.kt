package io.github.sceneview.demo.demos

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.createARCameraStream
import io.github.sceneview.ar.node.ARFogNode
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.node.FogNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberView
import io.github.sceneview.sample.ui.LabeledSlider
import java.util.Locale

/**
 * Environment-aware AR fog demo (issue #1717).
 *
 * Demonstrates fog that covers **both** real-world geometry (via the ARCore
 * depth image piped through the depth-aware camera material) and virtual
 * geometry (via Filament's per-View `FogNode`). Sliders drive both layers
 * with the same parameters so the user can verify they match visually.
 *
 * Inspired by ARCore Depth Lab's *AR Fog* sample. Without environment fog
 * the demo collapses to a virtual-only `FogNode` similar to [FogDemo], so
 * users on devices without Depth API support still see the controls do
 * something meaningful.
 *
 * @see io.github.sceneview.ar.node.ARFogNode
 * @see io.github.sceneview.node.FogNode
 */
@Composable
fun ARFogDemo(onBack: () -> Unit) {
    data class FogPreset(val label: String, val color: Color)

    val presets = remember {
        listOf(
            FogPreset("Mist", Color(0xFFCCDDFF)),
            FogPreset("Warm Haze", Color(0xFFFFDDAA)),
            FogPreset("Eerie Green", Color(0xFFAAFFCC)),
            FogPreset("Deep Smoke", Color(0xFF888888)),
        )
    }

    // Reset defaults so the bottom-sheet "Reset" button restores them.
    val defaultEnabled = true
    val defaultDensity = 0.08f
    val defaultStart = 0.5f
    val defaultEnd = 6f
    val defaultPreset = presets[0]

    var fogEnabled by remember { mutableStateOf(defaultEnabled) }
    var fogDensity by remember { mutableFloatStateOf(defaultDensity) }
    var fogStart by remember { mutableFloatStateOf(defaultStart) }
    var fogEnd by remember { mutableFloatStateOf(defaultEnd) }
    var selectedPreset by remember { mutableStateOf(defaultPreset) }
    var depthSupported by remember { mutableStateOf<Boolean?>(null) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val view = rememberView(engine)

    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var isTracking by remember { mutableStateOf(false) }

    // Cover the jet-black ARSceneView surface until ARCore delivers its first camera
    // frame, so the ~1–3 s warm-up on entry doesn't read as a frozen screen (#2484).
    var cameraReady by remember { mutableStateOf(false) }
    // #3341: non-null once ARCore has ruled this device out. `cameraReady` never flips
    // then, so the init scrim below has to read the verdict or it covers the SDK's own
    // explanation card forever.
    var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }

    // Replay a recorded ARCore dataset when the device-QA harness deep-links
    // the demo with `--es ar_playback_file <path>`. `null` for every normal
    // launch — see `rememberArPlaybackDataset`.
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Capture the enabled state once per keyed scope. Toggling fog on/off
    // is reactive through `ARFogNode` and does not need to rebuild the
    // camera stream — the material's `fogEnabled` parameter handles it.
    val depthOn = depthSupported != false

    DemoScaffold(
        title = stringResource(R.string.demo_ar_fog_title),
        onBack = onBack,
        onResetSettings = {
            fogEnabled = defaultEnabled
            fogDensity = defaultDensity
            fogStart = defaultStart
            fogEnd = defaultEnd
            selectedPreset = defaultPreset
        },
        controls = {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text(
                    text = "How to test",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp),
                )
                Text(
                    text = "1. Stand in a space with depth (a hallway, a room).\n" +
                        "2. Toggle fog on — distant real surfaces fade into the haze; near ones stay crisp.\n" +
                        "3. Density + range sliders drive both real and virtual fog identically.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }

            if (depthSupported == false) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "Your device doesn't support ARCore Depth API. " +
                            "Real-world fog is disabled — only virtual geometry will fog.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = fogEnabled,
                        onValueChange = { fogEnabled = it },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Fog Enabled", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = fogEnabled, onCheckedChange = null)
            }

            Spacer(Modifier.height(12.dp))

            LabeledSlider(
                label = "Density",
                value = fogDensity,
                onValueChange = { fogDensity = it },
                valueRange = 0f..1f,
                valueText = "%.2f".format(Locale.US, fogDensity),
                enabled = fogEnabled,
            )

            Spacer(Modifier.height(8.dp))

            LabeledSlider(
                label = "Start",
                value = fogStart,
                onValueChange = { fogStart = it; if (fogEnd <= it + 0.1f) fogEnd = it + 0.1f },
                valueRange = 0f..20f,
                valueText = "${"%.1f".format(Locale.US, fogStart)} m",
                enabled = fogEnabled,
            )

            Spacer(Modifier.height(8.dp))

            LabeledSlider(
                label = "End",
                value = fogEnd,
                onValueChange = { fogEnd = it.coerceAtLeast(fogStart + 0.1f) },
                valueRange = 0f..30f,
                valueText = "${"%.1f".format(Locale.US, fogEnd)} m",
                enabled = fogEnabled,
            )

            Spacer(Modifier.height(12.dp))

            Text("Color Preset", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { preset ->
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = { selectedPreset = preset },
                        label = { Text(preset.label) },
                        enabled = fogEnabled,
                    )
                }
            }

            ForceTrackingFailureMenu()
        },
        topOverlay = {
            Surface(
                color = if (fogEnabled) {
                    Color(0xFF1B5E20).copy(alpha = 0.85f)
                } else {
                    Color(0xFF555555).copy(alpha = 0.85f)
                },
                contentColor = Color.White,
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = if (fogEnabled) "FOG ON" else "FOG OFF",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        bottomOverlay = {
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
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // The depth occlusion material is wired at camera-stream creation
            // (cf. `ARDepthOcclusionDemo`). We always want depth on here —
            // toggling the *fog effect* itself is reactive through `ARFogNode`
            // and does NOT require rebuilding the camera stream.
            key(depthOn) {
                // Hoist the camera stream so the `ARFogNode` inside the
                // content block can address it. `rememberARCameraStream` survives
                // the keyed rebuild as long as `materialLoader` is the same.
                val cameraStream = rememberARCameraStream(
                    materialLoader = materialLoader,
                    creator = {
                        createARCameraStream(materialLoader).apply {
                            isDepthOcclusionEnabled = depthOn
                        }
                    },
                )
                ARSceneView(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    view = view,
                    playbackDataset = arPlaybackDataset,
                    sessionConfiguration = { session: Session, config: Config ->
                        config.planeFindingMode =
                            Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        val supported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                        depthSupported = supported
                        config.depthMode = if (supported) {
                            Config.DepthMode.AUTOMATIC
                        } else {
                            Config.DepthMode.DISABLED
                        }
                    },
                    cameraStream = cameraStream,
                    onARCoreAvailability = { arCoreAvailability = it },
                    onSessionUpdated = { _, frame: Frame ->
                        cameraReady = true
                        isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    },
                    onTrackingFailureChanged = { reason ->
                        trackingFailureReason = reason
                    },
                ) {
                    cameraStream?.let { stream ->
                        // Real-world fog — drives the depth-aware camera material.
                        ARFogNode(
                            cameraStream = stream,
                            density = fogDensity,
                            start = fogStart,
                            end = fogEnd,
                            color = selectedPreset.color,
                            enabled = fogEnabled && depthOn,
                        )
                    }
                    // Virtual-geometry fog — drives Filament's per-View fog so
                    // any virtual node we ever place gets the same haze.
                    // Density is the only consistent knob `FogNode` exposes —
                    // start/end are baked into Filament's volumetric formula.
                    FogNode(
                        view = view,
                        density = fogDensity,
                        color = selectedPreset.color,
                        enabled = fogEnabled,
                    )
                }
            }

            // Cover the still-black AR viewport until the first camera frame (#2484).
            ARCameraInitScrim(
                initializing = !cameraReady,
                arCoreAvailability = arCoreAvailability,
            )
        }
    }
}
