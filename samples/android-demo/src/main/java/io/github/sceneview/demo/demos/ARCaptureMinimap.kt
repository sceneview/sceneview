package io.github.sceneview.demo.demos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.CaptureMapSnapshot
import io.github.sceneview.demo.demos.internal.MapPoint
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal const val AR_REC_MINIMAP_TAG = "ar_rec_minimap"

// Built from the spacing scale, like every other size of this demo's cards.
private val MinimapHeight = Space.x4l + Space.x2l // 144 dp
private val PathWidth = Space.xs / 2 // 2 dp
private val WallWidth = Space.xs - Space.xs / 4 // 3 dp
private val KeyframeRadius = Space.xs / 2 // 2 dp: 25 cm apart, they stay distinct dots
private val HereRadius = Space.xs + Space.xs / 2 // 6 dp
private val HeadingLength = Space.md + Space.xs // 20 dp

/** Clear space round the plan: enough for the dot, not the whole wedge, which may overhang. */
private val PlanMargin = Space.md // 16 dp

/** The map never zooms in closer than this many metres across: a first step stays small. */
private const val MIN_SPAN_METERS = 1.2f

/** Half-angle of the "looking this way" wedge. */
private const val HEADING_HALF_ANGLE = 0.45f

/**
 * The take's floor plan, drawn live in the recording card (#4083): surfaces the session has
 * found (floor and tables filled, walls as lines), the path the phone walked, a dot per
 * keyframe, and the phone itself with the direction it faces. North-up — the world's -z is up —
 * and fitted to everything captured so far, so the map grows with the walk.
 */
@Composable
internal fun CaptureMinimap(map: CaptureMapSnapshot, modifier: Modifier = Modifier) {
    val description = stringResource(
        R.string.ar_rec_minimap_cd,
        map.keyframes.size,
        map.surfaces.size,
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(MinimapHeight)
            .clip(RoundedCornerShape(SceneViewTokens.Radius.md))
            .background(ArOverlay.meterTrack)
            .testTag(AR_REC_MINIMAP_TAG)
            .semantics { contentDescription = description },
    ) {
        if (map.isEmpty) return@Canvas
        val project = fit(map)
        map.surfaces.forEach { surface ->
            val outline = surface.outline.map(project)
            if (surface.vertical) {
                // A wall seen from above is its base line: its two farthest points.
                val (a, b) = farthestPair(outline)
                drawLine(ArOverlay.accentProgress, a, b, WallWidth.toPx(), StrokeCap.Round)
            } else {
                val path = polygon(outline)
                drawPath(path, ArOverlay.accentProgress.copy(alpha = SURFACE_FILL_ALPHA), style = Fill)
                drawPath(
                    path,
                    ArOverlay.accentProgress.copy(alpha = SURFACE_EDGE_ALPHA),
                    style = Stroke(width = PathWidth.toPx() / 2f, join = StrokeJoin.Round),
                )
            }
        }
        if (map.path.size >= 2) {
            val walked = Path().apply {
                map.path.map(project).forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            }
            drawPath(
                walked,
                ArOverlay.onScrimMuted,
                style = Stroke(width = PathWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        map.keyframes.forEach { drawCircle(ArOverlay.onScrim, KeyframeRadius.toPx(), project(it)) }
        map.current?.let { here -> drawHere(project(here), map.headingRadians) }
    }
}

/** The phone: a dot and a soft wedge towards where it looks. */
private fun DrawScope.drawHere(at: Offset, heading: Float) {
    val length = HeadingLength.toPx()
    val wedge = Path().apply {
        moveTo(at.x, at.y)
        lineTo(at.x + sin(heading - HEADING_HALF_ANGLE) * length, at.y - cos(heading - HEADING_HALF_ANGLE) * length)
        lineTo(at.x + sin(heading + HEADING_HALF_ANGLE) * length, at.y - cos(heading + HEADING_HALF_ANGLE) * length)
        close()
    }
    drawPath(wedge, ArOverlay.onScrim.copy(alpha = HEADING_ALPHA))
    drawCircle(ArOverlay.onScrim, HereRadius.toPx(), at)
    drawCircle(ArOverlay.accentProgress, HereRadius.toPx() * 0.55f, at)
}

/** World → canvas: centred on everything captured, one scale for both axes, a margin all round. */
private fun DrawScope.fit(map: CaptureMapSnapshot): (MapPoint) -> Offset {
    var minX = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    fun include(p: MapPoint) {
        minX = min(minX, p.x); maxX = max(maxX, p.x)
        minZ = min(minZ, p.z); maxZ = max(maxZ, p.z)
    }
    map.path.forEach(::include)
    map.surfaces.forEach { it.outline.forEach(::include) }
    map.current?.let(::include)
    val centreX = (minX + maxX) / 2f
    val centreZ = (minZ + maxZ) / 2f
    val spanX = max(maxX - minX, MIN_SPAN_METERS)
    val spanZ = max(maxZ - minZ, MIN_SPAN_METERS)
    val margin = PlanMargin.toPx()
    val scale = min((size.width - 2 * margin) / spanX, (size.height - 2 * margin) / spanZ)
    return { p -> Offset(size.width / 2f + (p.x - centreX) * scale, size.height / 2f + (p.z - centreZ) * scale) }
}

private fun polygon(points: List<Offset>): Path = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    close()
}

private fun farthestPair(points: List<Offset>): Pair<Offset, Offset> {
    var best = points.first() to points.last()
    var bestDistance = -1f
    for (i in points.indices) for (j in i + 1 until points.size) {
        val d = (points[i] - points[j]).getDistanceSquared()
        if (d > bestDistance) {
            bestDistance = d
            best = points[i] to points[j]
        }
    }
    return best
}

private const val SURFACE_FILL_ALPHA = 0.22f
private const val SURFACE_EDGE_ALPHA = 0.6f
private const val HEADING_ALPHA = 0.28f
