package io.github.sceneview.demo.demos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.CoverageGrid
import io.github.sceneview.demo.demos.internal.MiniatureFrame
import io.github.sceneview.demo.demos.internal.ScanAreaCopy
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.overMediaEdge
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * The Live Room's small model of the room: the floor seen from above, north up — it never turns —
 * the walls recognised so far, the gaps still open dashed, the floor covered filled over the floor
 * still to scan, and the phone where it really stands, its arrow the way it faces. Not a control:
 * nothing to tap, nothing to drag; the room is scanned on the camera, this only says where in it
 * the phone is.
 */
@Composable
internal fun ScanRoomMiniature(state: ScanCoverageState, walls: ScanWallsState?, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < LUMINANCE_MID
    val scrim = if (dark) ArOverlay.scrimDark else ArOverlay.scrimLight
    val shape = RoundedCornerShape(SceneViewTokens.Radius.md)
    val area by remember(state) { derivedStateOf { ScanAreaCopy.area(state.coveredSquareMetres) } }
    val label = stringResource(R.string.scan_coverage_label, area)
    val ease = remember(state) { MiniatureEase() }
    Canvas(
        modifier
            .size(MiniatureSize)
            .overMediaEdge(shape)
            .clip(shape)
            .background(scrim, shape)
            .semantics { contentDescription = label }
            .testTag(SCAN_ROOM_MINIATURE_TAG),
    ) {
        drawMiniature(state, walls, ease, MiniatureMargin.toPx(), MiniatureEdge.toPx())
    }
}

/** The framing eased draw to draw: out at once so nothing leaves the model, back in gently. */
private class MiniatureEase {
    var frame: MiniatureFrame? = null

    fun next(target: MiniatureFrame): MiniatureFrame {
        val now = frame
        val eased = if (now == null) {
            target
        } else {
            val scale = if (target.pxPerMetre < now.pxPerMetre) {
                target.pxPerMetre
            } else {
                now.pxPerMetre + (target.pxPerMetre - now.pxPerMetre) * EASE
            }
            MiniatureFrame(
                centreX = now.centreX + (target.centreX - now.centreX) * EASE,
                centreZ = now.centreZ + (target.centreZ - now.centreZ) * EASE,
                pxPerMetre = scale,
            )
        }
        frame = eased
        return eased
    }
}

@Suppress("LongMethod") // one plan, drawn in its layers
private fun DrawScope.drawMiniature(
    state: ScanCoverageState,
    walls: ScanWallsState?,
    ease: MiniatureEase,
    margin: Float,
    edge: Float,
) {
    // Read here only: a step or a new cell redraws the model, never recomposes it.
    state.tick
    val camera = state.camera
    val extent = state.extent
    val drawnWalls = walls?.snapshot?.walls.orEmpty()
    val xs = ArrayList<Float>(extent.size + drawnWalls.size * 2 + 1)
    val zs = ArrayList<Float>(xs.size)
    extent.forEach { (x, z) -> xs += x; zs += z }
    drawnWalls.forEach { xs += it.ax; zs += it.az; xs += it.bx; zs += it.bz }
    if (camera != null) {
        xs += camera.x
        zs += camera.z
    }
    val target = MiniatureFrame.of(xs.toFloatArray(), zs.toFloatArray(), size.width, size.height, margin) ?: return
    val frame = ease.next(target)
    val k = frame.pxPerMetre
    val line = edge / k
    translate(size.width / 2f, size.height / 2f) {
        // North up: world x to the right, world z down the page, whichever way the phone faces.
        scale(k, k, pivot = Offset.Zero) {
            translate(-frame.centreX, -frame.centreZ) {
                if (extent.size >= 3) {
                    val (minor, major) = state.hatch(cellsPerLine(HATCH_STEP_PX * edge / k))
                    drawPoints(minor, PointMode.Lines, ArOverlay.onScrimMuted.copy(alpha = HATCH_ALPHA), line)
                    drawPoints(major, PointMode.Lines, ArOverlay.onScrimMuted.copy(alpha = MAJOR_ALPHA), line)
                    // The room's estimated floor, outlined: what the hatching fills.
                    drawPath(state.floorPath(), ArOverlay.onScrimMuted, style = Stroke(width = line))
                }
                val (cells, _) = state.paths()
                drawPath(cells, ArOverlay.accentProgress.copy(alpha = COVERED_ALPHA))
                walls?.openEdges?.forEach { open ->
                    drawLine(
                        ArOverlay.accentGuidance,
                        Offset(open[0], open[1]),
                        Offset(open[2], open[3]),
                        strokeWidth = line * 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_PX * edge / k, DASH_PX * edge / k)),
                    )
                }
                drawnWalls.forEach {
                    drawLine(
                        ArOverlay.onScrim,
                        Offset(it.ax, it.az),
                        Offset(it.bx, it.bz),
                        strokeWidth = line * WALL_WIDTH,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
    if (camera == null) return
    // The phone where it stands, its arrow the way the lens faces, turned on the fixed plan.
    val at = Offset(
        size.width / 2f + (camera.x - frame.centreX) * k,
        size.height / 2f + (camera.z - frame.centreZ) * k,
    )
    val facing = camera.rotate(0f, 0f, -1f)
    val degrees = Math.toDegrees(atan2(facing.x, -facing.z).toDouble()).toFloat()
    val r = MARKER_RADIUS * edge
    drawCircle(ArOverlay.onScrim, radius = r + edge, center = at)
    drawCircle(ArOverlay.accentProgress, radius = r, center = at)
    rotate(degrees, pivot = at) {
        val arrow = Path().apply {
            moveTo(at.x, at.y - r * ARROW_TIP)
            lineTo(at.x + r * ARROW_HALF_WIDTH, at.y + r * ARROW_BASE)
            lineTo(at.x, at.y + r * ARROW_NOTCH)
            lineTo(at.x - r * ARROW_HALF_WIDTH, at.y + r * ARROW_BASE)
            close()
        }
        drawPath(arrow, ArOverlay.onAccentProgress)
    }
}

/** The hatching's pitch in coverage cells: one cell, or a metre's when cells are too small to read. */
private fun cellsPerLine(minStepMetres: Float): Int =
    if (CoverageGrid.CELL_METRES >= minStepMetres) 1 else (1f / CoverageGrid.CELL_METRES).roundToInt()

/** `112 dp`, the old map card's size: read at a glance, the camera keeps the screen. */
private val MiniatureSize = Space.x4l + Space.md
private val MiniatureMargin = Space.sm
private val MiniatureEdge = SceneViewTokens.Glass.edgeWidth

private const val EASE = 0.12f
private const val HATCH_STEP_PX = 6f
private const val DASH_PX = 4f
private const val WALL_WIDTH = 2.5f
private const val MARKER_RADIUS = 6f
private const val HATCH_ALPHA = 0.3f
private const val MAJOR_ALPHA = 0.6f
private const val COVERED_ALPHA = 0.6f
private const val LUMINANCE_MID = 0.5f
private const val ARROW_TIP = 0.62f
private const val ARROW_BASE = 0.5f
private const val ARROW_NOTCH = 0.22f
private const val ARROW_HALF_WIDTH = 0.46f

internal const val SCAN_ROOM_MINIATURE_TAG = "scan_room_miniature"
