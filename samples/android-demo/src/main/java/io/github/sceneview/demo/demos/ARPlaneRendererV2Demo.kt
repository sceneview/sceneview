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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.scene.PlaneRendererBase
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader

/**
 * AR showcase demo — **Plane Renderer V2** ([#2203](https://github.com/sceneview/sceneview/issues/2203)).
 *
 * Single-screen AR scene that runs `ARSceneView` with **V2 as the live default** and gives
 * the user a top-end [Switch] to flip V1 ↔ V2 on the fly so the difference reads instantly:
 *
 * | Toggle state | Renderer       | What you see                                                     |
 * |--------------|----------------|------------------------------------------------------------------|
 * | **V2 (on)**  | [PlaneRendererBase.Version.V2] | Depth-driven mesh + PBR + HDR cubemap reflection + type-aware floor / ceiling / wall + 800 ms scan-in ring. |
 * | **V1 (off)** | [PlaneRendererBase.Version.V1] | Flat polygon textured with a procedural unlit grid — the legacy renderer. |
 *
 * Flipping the switch triggers a renderer rebuild (the new value flows into
 * `ARSceneView(planeRendererVersion = …)` which is wired into the surrounding
 * `remember(...)` keys); the toggle is wrapped in `key(v2Enabled)` so the engine swaps
 * cleanly with no leftover state.
 *
 * A small legend card anchored bottom-start names the colour codes V2 ships per
 * `Plane.Type`, so the user can recognise what is on screen:
 *
 *  - **Floor** (`HORIZONTAL_UPWARD_FACING`) — cool-white grid, `metallic 0.00`, `roughness 0.35`.
 *  - **Ceiling** (`HORIZONTAL_DOWNWARD_FACING`) — warm-white grid, `roughness 0.65`.
 *  - **Wall** (`VERTICAL`) — neutral-grey grid, `roughness 0.80`.
 *
 * Same RGB values as `planeMaterialPresetFor` so the swatches and the on-screen grid match
 * pixel-for-pixel — see the type-aware shading table in
 * [io.github.sceneview.ar.scene.PlaneRendererV2]'s KDoc.
 *
 * The `v2Enabled` toggle survives process death via [rememberSaveable]. No content nodes
 * are spawned — the demo is about the *plane visualisation itself*; pair it with
 * `ARPlaneNodeDemo` to drop a marker cube on detected planes and compare reflections.
 *
 * Closes [#2203](https://github.com/sceneview/sceneview/issues/2203).
 */
@Composable
fun ARPlaneRendererV2Demo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch.
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Live V1 ↔ V2 toggle — process-death survival via rememberSaveable so a user
    // who configurationChange-rotates the device mid-comparison keeps their state.
    var v2Enabled by rememberSaveable { mutableStateOf(true) }

    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var planeDetected by remember { mutableStateOf(false) }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_plane_renderer_v2_title),
        onBack = onBack,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Re-key the ARSceneView on the toggle so the renderer rebuilds cleanly — the
            // `planeRendererVersion` parameter is already wired into ARScene's internal
            // `remember(...)` keys, but wrapping the whole composable in `key(...)` is the
            // boring-and-correct path that mirrors `ARDepthOcclusionDemo`'s engine swap on
            // its own toggle. Cost: one engine restart per tap, paid only on user
            // interaction.
            key(v2Enabled) {
                ARSceneView(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    playbackDataset = arPlaybackDataset,
                    planeRenderer = true,
                    planeRendererVersion = if (v2Enabled) {
                        PlaneRendererBase.Version.V2
                    } else {
                        PlaneRendererBase.Version.V1
                    },
                    onTrackingFailureChanged = { reason -> trackingFailureReason = reason },
                    onSessionUpdated = { _, frame ->
                        if (!planeDetected) {
                            // Flip the scan-in / legend visibility once at least one plane
                            // is tracking. `getUpdatedTrackables` is cheap and avoids
                            // walking the whole trackable set every frame.
                            val updated = frame.getUpdatedTrackables(
                                com.google.ar.core.Plane::class.java
                            )
                            if (updated.any { it.trackingState == com.google.ar.core.TrackingState.TRACKING }) {
                                planeDetected = true
                            }
                        }
                    },
                )
            }

            // Top-end V1 ↔ V2 live toggle.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp),
                color = Color.Black.copy(alpha = 0.72f),
                contentColor = Color.White,
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column {
                        Text(
                            text = if (v2Enabled) {
                                "V2 (depth + PBR + HDR)"
                            } else {
                                "V1 (legacy grid)"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = "Tap to switch renderer",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    Switch(
                        checked = v2Enabled,
                        onCheckedChange = { v2Enabled = it },
                    )
                }
            }

            // Bottom-start legend card — names the colour codes V2 ships per plane type so
            // the user can connect the on-screen grid colour to its `Plane.Type`. RGB
            // values come straight from `planeMaterialPresetFor` (PR #4) — the swatches
            // and the rendered grid match.
            //
            // Hidden when V1 is active (V1's procedural grid is type-agnostic, single
            // white) so we never lie about what's on screen.
            AnimatedVisibility(
                visible = v2Enabled,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 24.dp),
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    contentColor = Color.White,
                    tonalElevation = 4.dp,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Plane V2 — type-aware shading",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        LegendRow(
                            // Cool-white floor — matches FLOOR_PRESET.gridR/G/B (0.85, 0.92, 1.0).
                            swatch = Color(red = 0.85f, green = 0.92f, blue = 1.0f),
                            label = "Floor — roughness 0.35",
                        )
                        LegendRow(
                            // Warm-white ceiling — matches CEILING_PRESET (1.0, 0.96, 0.88).
                            swatch = Color(red = 1.0f, green = 0.96f, blue = 0.88f),
                            label = "Ceiling — roughness 0.65",
                        )
                        LegendRow(
                            // Neutral-grey wall — matches WALL_PRESET (0.92, 0.92, 0.92).
                            swatch = Color(red = 0.92f, green = 0.92f, blue = 0.92f),
                            label = "Wall — roughness 0.80",
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (planeDetected) {
                                "Move slowly — depth mesh + scan-in fired."
                            } else {
                                "Move slowly to scan a surface — scan-in fires on first detection."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            // Scanning / tracking-failure overlay — never leave the user staring at a black
            // screen (#1617). Visible until at least one plane is tracked.
            AnimatedVisibility(
                visible = !planeDetected,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Surface(
                    modifier = Modifier.padding(bottom = 88.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        text = if (trackingFailureReason != null) {
                            "Move the device slowly to scan a surface…"
                        } else {
                            stringResource(R.string.ar_status_scanning)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** One row of the V2 legend — a coloured circle swatch + label. */
@Composable
private fun LegendRow(swatch: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(swatch),
        )
        Spacer(Modifier.width(0.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
