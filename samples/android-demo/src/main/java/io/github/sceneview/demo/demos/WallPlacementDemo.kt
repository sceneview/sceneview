package io.github.sceneview.demo.demos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.WallPlacementPhase
import io.github.sceneview.ar.WallPlacementScene
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader

/**
 * Wall-placement demo (#2740, sub-task F) — mounts a procedural TV on a vertical surface
 * through [WallPlacementScene], reproducing the Amazon "AR View" wall flow the umbrella
 * teardown documented:
 *
 * 1. the phase banner walks the user through FINDING_FLOOR → FINDING_WALL → ALIGNING_EDGE →
 *    PLACED (the scene's own state machine, surfaced via `onPhaseChanged`);
 * 2. during ALIGNING_EDGE a fixed **orange guide line** is drawn on screen — the user
 *    physically aligns it with the floor↔wall seam before tapping (the Amazon trick that
 *    makes wall placement work without relying on the seam being perfectly tracked);
 * 3. after placement a **D-pad** fine-tunes the TV (2 cm nudges along the wall, 2° yaw
 *    steps) — the "precise" half of the umbrella's dual manipulation model (sub-task D;
 *    the free gizmo half is a follow-up).
 *
 * The TV is procedural (two [CubeNode]s: matte body + glossy screen) so the demo needs no
 * bundled asset and stays deterministic — consistent with the local-assets rule.
 */
/** Height of the orange alignment-guide Canvas — must be non-zero or the stroke is clipped. */
private val GUIDE_LINE_HEIGHT = 16.dp

@Composable
fun WallPlacementDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    var phase by remember { mutableStateOf(WallPlacementPhase.FINDING_FLOOR) }
    // D-pad state — offset in the anchor's local frame (x = along the wall, y = up)
    // and an extra yaw on top of the wall-facing orientation.
    var nudge by remember { mutableStateOf(Position(0f, 0f, 0f)) }
    var nudgeYaw by remember { mutableStateOf(0f) }

    DemoScaffold(
        title = stringResource(R.string.demo_wall_placement_title),
        onBack = onBack,
    ) {
        WallPlacementScene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            // TV centre ~1.1 m above the floor — typical living-room mount height.
            mountHeight = 1.1f,
            onPhaseChanged = { phase = it },
            onPlaced = { anchor ->
                AnchorNode(anchor = anchor) {
                    Node(position = nudge, rotation = Rotation(y = nudgeYaw)) {
                        val body = remember(materialLoader) {
                            materialLoader.createColorInstance(
                                Color(0xFF20242A), metallic = 0f, roughness = 0.8f,
                            )
                        }
                        val screen = remember(materialLoader) {
                            materialLoader.createColorInstance(
                                Color(0xFF06080C), metallic = 0f, roughness = 0.15f,
                            )
                        }
                        // 55" TV: body slightly proud of the wall, screen on its front face.
                        CubeNode(size = Size(1.26f, 0.74f, 0.04f), position = Position(z = 0.02f), materialInstance = body)
                        CubeNode(size = Size(1.20f, 0.68f, 0.01f), position = Position(z = 0.045f), materialInstance = screen)
                    }
                }
            },
        )

        // Phase banner — mirrors the scene's onboarding state machine.
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ) {
            Text(
                text = stringResource(
                    when (phase) {
                        WallPlacementPhase.FINDING_FLOOR -> R.string.wall_phase_finding_floor
                        WallPlacementPhase.FINDING_WALL -> R.string.wall_phase_finding_wall
                        WallPlacementPhase.ALIGNING_EDGE -> R.string.wall_phase_aligning_edge
                        WallPlacementPhase.PLACED -> R.string.wall_phase_placed
                    }
                ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // Orange alignment guide — fixed screen-space line the user physically aligns
        // with the floor↔wall seam (the Amazon "ligne orange" from the teardown).
        if (phase == WallPlacementPhase.ALIGNING_EDGE) {
            // The Canvas needs an explicit height — `fillMaxWidth()` alone leaves it
            // zero-high and the stroke gets clipped away entirely.
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GUIDE_LINE_HEIGHT)
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
            ) {
                val midY = size.height / 2f
                drawLine(
                    color = Color(0xFFFF8A00),
                    start = Offset(0f, midY),
                    end = Offset(size.width, midY),
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        // D-pad fine-adjust — the precise half of the dual manipulation model.
        if (phase == WallPlacementPhase.PLACED) {
            val step = 0.02f      // 2 cm nudge
            val yawStep = 2f      // 2° rotation
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilledTonalIconButton(onClick = { nudge = nudge.copy(y = nudge.y + step) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.wall_dpad_up))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalIconButton(onClick = { nudgeYaw += yawStep }) {
                        Icon(Icons.AutoMirrored.Filled.RotateLeft, stringResource(R.string.wall_dpad_rotate_left))
                    }
                    FilledTonalIconButton(onClick = { nudge = nudge.copy(x = nudge.x - step) }) {
                        Icon(Icons.Filled.KeyboardArrowLeft, stringResource(R.string.wall_dpad_left))
                    }
                    FilledTonalIconButton(onClick = { nudge = nudge.copy(x = nudge.x + step) }) {
                        Icon(Icons.Filled.KeyboardArrowRight, stringResource(R.string.wall_dpad_right))
                    }
                    FilledTonalIconButton(onClick = { nudgeYaw -= yawStep }) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, stringResource(R.string.wall_dpad_rotate_right))
                    }
                }
                FilledTonalIconButton(onClick = { nudge = nudge.copy(y = nudge.y - step) }) {
                    Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.wall_dpad_down))
                }
            }
        }
    }
}
