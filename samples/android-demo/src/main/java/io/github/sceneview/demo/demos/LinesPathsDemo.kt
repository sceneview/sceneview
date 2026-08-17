package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.rememberUnlitMaterialInstance
import io.github.sceneview.sample.ui.LabeledSlider
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Base radius (metres) of every stroke bead — built once, then driven by [Scale]. */
private const val BEAD_BASE_RADIUS = 0.012f

/**
 * Maximum uniform scale applied to a bead at full Stroke Width slider. At `1.0` the line
 * reads as a continuous thick tube (~0.05 m visual radius); the default slider value of
 * 0.5 gives a balanced medium stroke that no longer dwarfs the scene.
 */
private const val BEAD_MAX_SCALE = 4f

/**
 * Demonstrates LineNode (single segment) and PathNode (polyline through points).
 * Controls toggle visibility, adjust the number of path points, and drive the stroke
 * width — the Stroke Width slider scales the per-point beads that give the lines a
 * visible thickness (GPU LINES primitives are capped at 1 px).
 */
@Composable
fun LinesPathsDemo(onBack: () -> Unit) {
    var showLine by remember { mutableStateOf(true) }
    var showPath by remember { mutableStateOf(true) }
    var pointCount by remember { mutableFloatStateOf(12f) }
    // Filament's LINES primitive is hardware-capped at 1 GPU pixel on most backends, so a line
    // "width" slider cannot drive the native line width. Instead we render per-point sphere beads
    // and drive their *scale* — not their geometry radius — from this slider. Scale is a pure
    // transform that `SphereNode`'s `SideEffect` re-applies unconditionally on every
    // recomposition, so the beads track the slider every frame with no vertex-buffer rebuild;
    // a radius-driven geometry update only ran on inequality and rebuilt ~600 verts per bead.
    // Set to 0 to disable the beads entirely.
    var lineWidth by remember { mutableFloatStateOf(0.5f) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_lines_paths_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            Text("Visibility", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(showLine, onClick = { showLine = !showLine }, label = { Text("Line") })
                FilterChip(showPath, onClick = { showPath = !showPath }, label = { Text("Path") })
            }
            Spacer(modifier = Modifier.height(8.dp))
            LabeledSlider(
                label = "Path Points",
                value = pointCount,
                onValueChange = { pointCount = it },
                valueRange = 3f..30f,
                decimals = 0,
                steps = 27,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LabeledSlider(
                label = "Stroke Width",
                value = lineWidth,
                onValueChange = { lineWidth = it },
                valueRange = 0f..1f,
                valueText = "${"%.0f".format(Locale.US, lineWidth * 100)}%",
            )
        }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            onFrame = firstFrame.onFrame,
            engine = engine,
            materialLoader = materialLoader,
            cameraManipulator = rememberCameraManipulator()
        ) {
            // On-brand line colors — Primary blue for the line segment, Accent purple for the
            // path polyline. Same hero gradient as the product. Unlit because lines have ~0
            // surface area (lighting contributes nothing useful) and we want crisp readable
            // strokes regardless of scene illumination.
            val lineMaterial = rememberUnlitMaterialInstance(materialLoader, SceneViewColors.Primary)
            val pathMaterial = rememberUnlitMaterialInstance(materialLoader, SceneViewColors.Accent)

            // The bead sphere is built once at BEAD_BASE_RADIUS; the slider drives its uniform
            // `scale` (0..1 → 0..BEAD_MAX_SCALE) so moving the slider visibly rebalances every
            // bead every frame. At full slider the line reads as a continuous thick stroke
            // rather than a string of oversized balls.
            val beadScale = lineWidth * BEAD_MAX_SCALE

            // Single line segment — 1 px on most GPUs, so we also draw a row of spheres along
            // the segment (scaled by the Stroke Width slider) to give the line a visible
            // thickness. The bead count is high enough that they overlap into a smooth tube.
            if (showLine) {
                val lineStart = Position(x = -1.0f, y = -0.5f, z = 0f)
                val lineEnd = Position(x = 1.0f, y = 0.5f, z = 0f)
                val lineOrigin = Position(x = 0f, y = 0.4f)
                LineNode(
                    start = lineStart,
                    end = lineEnd,
                    materialInstance = lineMaterial,
                    position = lineOrigin
                )
                if (lineWidth > 0f) {
                    // Interpolate beads along the segment — dense enough that adjacent beads
                    // overlap into a continuous thick stroke instead of a dotted line.
                    val beadCount = 28
                    for (i in 0..beadCount) {
                        val t = i.toFloat() / beadCount
                        SphereNode(
                            radius = BEAD_BASE_RADIUS,
                            materialInstance = lineMaterial,
                            scale = Scale(beadScale),
                            position = Position(
                                x = lineOrigin.x + lineStart.x + (lineEnd.x - lineStart.x) * t,
                                y = lineOrigin.y + lineStart.y + (lineEnd.y - lineStart.y) * t,
                                z = lineOrigin.z + lineStart.z + (lineEnd.z - lineStart.z) * t
                            )
                        )
                    }
                }
            }

            // 3-D helix path — the points sweep through x/y on a circle and step out
            // along z, so the polyline reads as a true 3D corkscrew instead of the flat
            // 2-D ring it used to be. Much more visible from the default front camera.
            if (showPath) {
                val count = pointCount.toInt()
                val pathPoints = remember(count) {
                    (0 until count).map { i ->
                        val t = i.toFloat() / (count - 1).coerceAtLeast(1)
                        val angle = t * 4f * Math.PI.toFloat()  // 2 full turns
                        val radius = 0.45f
                        Position(
                            x = cos(angle) * radius,
                            y = sin(angle) * radius,
                            z = (t - 0.5f) * 0.6f,  // helix depth -0.3 → +0.3 m
                        )
                    }
                }
                val pathOrigin = Position(x = 0f, y = -0.3f)
                PathNode(
                    points = pathPoints,
                    closed = true,
                    materialInstance = pathMaterial,
                    position = pathOrigin
                )
                if (lineWidth > 0f) {
                    // Thick-path representation: one sphere bead at each path point, uniformly
                    // scaled by the Stroke Width slider. Gives the path a visible stroke width
                    // independent of GPU line-rasterisation limits.
                    pathPoints.forEach { p ->
                        SphereNode(
                            radius = BEAD_BASE_RADIUS,
                            materialInstance = pathMaterial,
                            scale = Scale(beadScale),
                            position = Position(
                                x = pathOrigin.x + p.x,
                                y = pathOrigin.y + p.y,
                                z = pathOrigin.z + p.z
                            )
                        )
                    }
                }
            }
        }
    }
}
