package io.github.sceneview.ar

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs

/*
 * The coaching illustration: a phone sweeping over a surface drawn in perspective, its view
 * cone lighting the points it has "seen" — the gesture and its result in one loop. Ported from
 * the scan coaching of AR Model Viewer (same author), generalised to walls, the "found" beat,
 * the tracking-limited and look-back cues.
 *
 * Everything here is a pure function of explicit progress values, so previews and screenshot
 * tests pin any pose deterministically. All geometry is in fractions of the canvas, which is
 * [CoachSpec.ILLUSTRATION_W] × [CoachSpec.ILLUSTRATION_H] in the card.
 */

/**
 * One frame of the illustration.
 *
 * @param sweep 0..1 loop progress of the scan sweep (out over the first half, back over the
 *   second). The reduce-motion resting pose is [CoachSpec.RESTING_SWEEP].
 * @param rise 0..1 progress of the phone being raised (initializing).
 * @param dim 0..1 opacity multiplier of the whole scene (tracking limited draws at 0.6).
 * @param resting animations are off: a static ↔ chevron under the phone says "move".
 */
internal data class CoachPose(
    val sweep: Float = CoachSpec.RESTING_SWEEP,
    val rise: Float = 1f,
    val dim: Float = 1f,
    val resting: Boolean = false,
)

internal fun DrawScope.drawCoachIllustration(
    cue: ArGuidanceCue,
    surface: PlacementSurface,
    pose: CoachPose,
    colors: CoachIllustrationColors = CoachIllustrationColors.Default,
) {
    val wall = surface == PlacementSurface.WALL
    val plane = coachPlane(wall)
    when (cue) {
        ArGuidanceCue.NONE, ArGuidanceCue.SURFACE_FOUND -> Unit
        ArGuidanceCue.INITIALIZING -> {
            drawPlane(plane, colors, alpha = 0.5f * pose.dim)
            val phoneX = size.width * 0.5f
            drawPhone(plane, phoneX, colors, alpha = pose.dim, rise = pose.rise)
        }
        ArGuidanceCue.SCAN, ArGuidanceCue.TRACKING_LIMITED -> {
            drawScanScene(plane, pose.sweep, colors, pose.dim)
            if (pose.resting) drawMoveChevrons(plane, colors, pose.dim)
        }
        ArGuidanceCue.RELOCALIZING -> drawLookBackScene(plane, pose.sweep, colors, pose.dim)
    }
}

/** Colours of the illustration. All are read on `ar-scrim`, so they do not follow the theme. */
internal data class CoachIllustrationColors(
    val line: Color,
    val phone: Color,
    val point: Color,
    val accent: Color,
    val warning: Color,
    val onAccent: Color,
) {
    companion object {
        val Default = CoachIllustrationColors(
            line = CoachColors.OnScrim,
            phone = CoachColors.OnScrim,
            point = CoachColors.Primary,
            accent = CoachColors.Primary,
            warning = CoachColors.Warning,
            onAccent = CoachColors.OnAccent,
        )
    }
}

/**
 * The surface in perspective: four corners (near-left, near-right, far-right, far-left) and
 * where the phone stands. A floor recedes upward under a phone held above it; a wall rises
 * above a phone held below it, looking up at it.
 */
internal class CoachPlane(
    val nearLeft: Offset,
    val nearRight: Offset,
    val farRight: Offset,
    val farLeft: Offset,
    val phoneY: Float,
    val wall: Boolean,
) {
    /** A point on the plane: [u] 0..1 left→right, [v] 0..1 far→near. */
    fun at(u: Float, v: Float): Offset {
        val left = lerpOffset(farLeft, nearLeft, v)
        val right = lerpOffset(farRight, nearRight, v)
        return lerpOffset(left, right, u)
    }

    fun path(): Path = Path().apply {
        moveTo(nearLeft.x, nearLeft.y)
        lineTo(nearRight.x, nearRight.y)
        lineTo(farRight.x, farRight.y)
        lineTo(farLeft.x, farLeft.y)
        close()
    }
}

private fun lerpOffset(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

private fun DrawScope.coachPlane(wall: Boolean): CoachPlane {
    val w = size.width
    val h = size.height
    return if (wall) {
        // Upright wall seen from below: the far (top) edge is the narrow one.
        CoachPlane(
            nearLeft = Offset(0f, h * 0.50f),
            nearRight = Offset(w, h * 0.50f),
            farRight = Offset(w * 0.80f, h * 0.02f),
            farLeft = Offset(w * 0.20f, h * 0.02f),
            phoneY = h * 0.58f,
            wall = true,
        )
    } else {
        CoachPlane(
            nearLeft = Offset(0f, h * 0.98f),
            nearRight = Offset(w, h * 0.98f),
            farRight = Offset(w * 0.78f, h * 0.52f),
            farLeft = Offset(w * 0.22f, h * 0.52f),
            phoneY = h * 0.04f,
            wall = false,
        )
    }
}

private fun DrawScope.drawPlane(
    plane: CoachPlane,
    colors: CoachIllustrationColors,
    alpha: Float,
    tint: Float = 0f,
) {
    val line = lerp(colors.line, colors.accent, tint)
    val hair = CoachSpec.HAIRLINE.toPx()
    val path = plane.path()
    drawPath(path, line.copy(alpha = 0.10f * alpha))
    // The outline is the one graphic that must read (>= 3:1 on the card); the grid is decoration.
    drawPath(path, line.copy(alpha = 0.55f * alpha), style = Stroke(width = hair))
    // Rows bunch up toward the horizon (quadratic spacing) — the depth cue.
    for (i in 1..3) {
        val f = i / 4f
        val v = f * f
        drawLine(line.copy(alpha = 0.24f * alpha), plane.at(0f, v), plane.at(1f, v), hair)
    }
    for (i in 1..4) {
        val u = i / 5f
        drawLine(line.copy(alpha = 0.24f * alpha), plane.at(u, 0f), plane.at(u, 1f), hair)
    }
}

/** Phone x as a fraction of the width for a 0..1 sweep loop: out, then back, smoothstep. */
internal fun coachPhoneX(sweep: Float): Float {
    val t = sweep.coerceIn(0f, 1f)
    val leg = if (t < 0.5f) t * 2f else 2f - t * 2f
    val eased = leg * leg * (3f - 2f * leg)
    return CoachSpec.SWEEP_FROM + (CoachSpec.SWEEP_TO - CoachSpec.SWEEP_FROM) * eased
}

/**
 * How far right the lit points reach for a sweep value: up to the phone on the way out, all
 * of them on the way back (they stay lit until the loop resets).
 */
internal fun coachLitReach(sweep: Float): Float =
    if (sweep.coerceIn(0f, 1f) < 0.5f) coachPhoneX(sweep) else 1f

private fun DrawScope.drawScanScene(
    plane: CoachPlane,
    sweep: Float,
    colors: CoachIllustrationColors,
    alpha: Float,
) {
    drawPlane(plane, colors, alpha)
    val w = size.width
    val phoneX = w * coachPhoneX(sweep)
    val reach = coachLitReach(sweep)
    // Everything fades over the last 12 % of the loop so the restart is not a jump cut.
    val fade = if (sweep > 0.88f) (1f - (sweep - 0.88f) / 0.12f).coerceIn(0f, 1f) else 1f
    drawCone(plane, phoneX, colors.accent, alpha)
    COACH_FEATURE_POINTS.forEach { (fu, fv) ->
        if (fu <= reach) {
            val p = plane.at(fu, fv)
            val pop = (1f - abs(p.x - phoneX) / (w * 0.12f)).coerceIn(0f, 1f)
            drawCircle(
                colors.point.copy(alpha = 0.95f * fade * alpha),
                radius = (2.2f + 1.6f * pop) * CoachSpec.DOT_UNIT.toPx(),
                center = p,
            )
        }
    }
    drawPhone(plane, phoneX, colors, alpha)
}

private fun DrawScope.drawCone(plane: CoachPlane, phoneX: Float, color: Color, alpha: Float) {
    val pw = CoachSpec.PHONE_W.toPx()
    val ph = CoachSpec.PHONE_H.toPx()
    // The cone leaves the phone's camera edge and lands halfway into the plane.
    val mid = if (plane.wall) plane.farLeft.y + (plane.nearLeft.y - plane.farLeft.y) * 0.45f
    else plane.farLeft.y + (plane.nearLeft.y - plane.farLeft.y) * 0.55f
    val startY = if (plane.wall) plane.phoneY else plane.phoneY + ph
    val cone = Path().apply {
        moveTo(phoneX - pw * 0.3f, startY)
        lineTo(phoneX - pw * 1.1f, mid)
        lineTo(phoneX + pw * 1.1f, mid)
        lineTo(phoneX + pw * 0.3f, startY)
        close()
    }
    drawPath(cone, color.copy(alpha = 0.16f * alpha))
}

/**
 * The phone, portrait, with its camera dot. [rise] 0..1 raises it from lying flat (a sliver)
 * to upright — the initializing gesture "hold your phone up".
 */
private fun DrawScope.drawPhone(
    plane: CoachPlane,
    phoneX: Float,
    colors: CoachIllustrationColors,
    alpha: Float,
    rise: Float = 1f,
) {
    if (alpha <= 0f) return
    val pw = CoachSpec.PHONE_W.toPx()
    val fullH = CoachSpec.PHONE_H.toPx()
    val ph = fullH * (0.18f + 0.82f * rise.coerceIn(0f, 1.1f))
    // Anchored at its bottom edge, so raising it reads as tilting up from the hand.
    val bottom = plane.phoneY + fullH
    val top = bottom - ph
    val corner = CornerRadius(CoachSpec.PHONE_CORNER.toPx())
    val stroke = CoachSpec.PHONE_STROKE.toPx()
    drawRoundRect(
        colors.phone.copy(alpha = 0.18f * alpha),
        topLeft = Offset(phoneX - pw / 2f, top),
        size = Size(pw, ph),
        cornerRadius = corner,
    )
    drawRoundRect(
        colors.phone.copy(alpha = alpha),
        topLeft = Offset(phoneX - pw / 2f, top),
        size = Size(pw, ph),
        cornerRadius = corner,
        style = Stroke(width = stroke),
    )
    // Camera dot: on the far side for a floor (seen from above), the near side for a wall.
    val dotY = if (plane.wall) bottom - ph * 0.14f else top + ph * 0.14f
    drawCircle(colors.phone.copy(alpha = alpha), radius = CoachSpec.CAMERA_DOT.toPx(), center = Offset(phoneX, dotY))
}

/**
 * The found check: an accent disc with a dark tick, filling the canvas and scaled by [scale]
 * (a spring may overshoot).
 */
internal fun DrawScope.drawCheckDisc(scale: Float, colors: CoachIllustrationColors = CoachIllustrationColors.Default) {
    if (scale <= 0f) return
    val radius = size.minDimension / 2f * scale
    val centre = center
    drawCircle(colors.accent, radius = radius, center = centre)
    val tick = Path().apply {
        moveTo(centre.x - radius * 0.42f, centre.y + radius * 0.02f)
        lineTo(centre.x - radius * 0.12f, centre.y + radius * 0.32f)
        lineTo(centre.x + radius * 0.44f, centre.y - radius * 0.30f)
    }
    drawPath(
        tick,
        colors.onAccent,
        style = Stroke(width = radius * 0.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** Reduce motion: a static double chevron under the phone, so the still picture still says "move". */
private fun DrawScope.drawMoveChevrons(plane: CoachPlane, colors: CoachIllustrationColors, alpha: Float) {
    val c = CoachSpec.CHEVRON.toPx()
    val phoneX = size.width * coachPhoneX(CoachSpec.RESTING_SWEEP)
    val pw = CoachSpec.PHONE_W.toPx()
    val y = plane.phoneY + CoachSpec.PHONE_H.toPx() / 2f
    val stroke = Stroke(width = CoachSpec.PHONE_STROKE.toPx() * 0.75f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val color = colors.phone.copy(alpha = 0.72f * alpha)
    listOf(-1f, 1f).forEach { side ->
        val x = phoneX + side * (pw / 2f + c * 0.9f)
        val tip = x + side * c / 2f
        val back = x - side * c / 2f
        drawPath(
            Path().apply {
                moveTo(back, y - c / 2f)
                lineTo(tip, y)
                lineTo(back, y + c / 2f)
            },
            color,
            style = stroke,
        )
    }
}

/**
 * Look back: the placed object is a dashed ghost on the surface, the phone pans toward it and
 * the ghost firms up while the view cone is on it.
 */
private fun DrawScope.drawLookBackScene(
    plane: CoachPlane,
    sweep: Float,
    colors: CoachIllustrationColors,
    alpha: Float,
) {
    drawPlane(plane, colors, alpha)
    val w = size.width
    val phoneX = w * coachPhoneX(sweep)
    drawCone(plane, phoneX, colors.warning, alpha)
    // On a floor, near enough that the cube's top stays clear of the phone above it.
    val ghostAt = plane.at(0.5f, if (plane.wall) 0.5f else 0.78f)
    val seen = (1f - abs(ghostAt.x - phoneX) / (w * 0.18f)).coerceIn(0f, 1f)
    drawGhostCube(ghostAt, colors.warning, alpha * (0.55f + 0.45f * seen), dashed = seen < 0.5f)
    drawPhone(plane, phoneX, colors, alpha)
}

private fun DrawScope.drawGhostCube(base: Offset, color: Color, alpha: Float, dashed: Boolean) {
    val e = CoachSpec.GHOST_EDGE.toPx()
    val d = e * 0.45f
    val o = Offset(base.x - e / 2f, base.y - e * 0.2f)
    val path = Path().apply {
        // Front face.
        moveTo(o.x, o.y); lineTo(o.x + e, o.y); lineTo(o.x + e, o.y - e); lineTo(o.x, o.y - e); close()
        // Top and side faces.
        moveTo(o.x, o.y - e); lineTo(o.x + d, o.y - e - d); lineTo(o.x + e + d, o.y - e - d); lineTo(o.x + e, o.y - e)
        moveTo(o.x + e + d, o.y - e - d); lineTo(o.x + e + d, o.y - d); lineTo(o.x + e, o.y)
    }
    drawPath(path, color.copy(alpha = 0.16f * alpha))
    drawPath(
        path,
        color.copy(alpha = alpha),
        style = Stroke(
            width = CoachSpec.PHONE_STROKE.toPx() * 0.75f,
            join = StrokeJoin.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(e * 0.16f, e * 0.12f)) else null,
        ),
    )
}

/** Points on the surface as (u left→right, v far→near). Ten, spread so the sweep lights them in turn. */
internal val COACH_FEATURE_POINTS = listOf(
    0.18f to 0.70f, 0.27f to 0.30f, 0.34f to 0.85f, 0.41f to 0.50f, 0.47f to 0.18f,
    0.53f to 0.72f, 0.59f to 0.40f, 0.66f to 0.88f, 0.72f to 0.25f, 0.80f to 0.60f,
)
