package io.github.sceneview.ar

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated AR coaching overlay — the Compose counterpart of Apple's `ARCoachingOverlayView`,
 * drawn with the same grammar: a phone sweeping over a floor diamond or a wall, a "found"
 * beat where the surface fills and a cube lands on it, then a fade-out.
 *
 * [AutoPlacementScene] shows it by default (`coaching = true`). Use this overload next to
 * your own `ARSceneView` or with `coaching = false` to position it yourself; it centres
 * itself in the enclosing [BoxScope]. Hide non-essential chrome while
 * [ArGuidanceState.isCoaching] is true.
 *
 * One glyph per [ArGuidanceCue] and one word at most on screen; the full sentence is the
 * accessible name, announced politely by TalkBack. Theme-independent ground (the camera
 * feed is the background) — only the scrim opacity follows light/dark. With animations
 * turned off in system settings the glyphs hold their most readable pose; fades remain.
 */
@Composable
fun BoxScope.ARCoachingOverlay(
    guidance: ArGuidanceState,
    modifier: Modifier = Modifier,
) {
    ARCoachingOverlay(cue = guidance.cue, surface = guidance.surface, modifier = modifier)
}

/**
 * Stateless renderer for one [cue] — for previews, screenshot tests, or a guidance state
 * machine of your own. [ArGuidanceCue.NONE] renders nothing (with an exit fade).
 */
@Composable
fun BoxScope.ARCoachingOverlay(
    cue: ArGuidanceCue,
    surface: PlacementSurface = PlacementSurface.SURFACE,
    modifier: Modifier = Modifier,
) {
    // Latch the last visible cue so the exit fade keeps drawing it instead of blanking.
    var shown by remember { mutableStateOf(cue) }
    if (cue != ArGuidanceCue.NONE) shown = cue
    val rise = with(LocalDensity.current) { CoachMotion.RISE.roundToPx() }
    AnimatedVisibility(
        visible = cue != ArGuidanceCue.NONE,
        modifier = modifier.align(Alignment.Center),
        enter = fadeIn(tween(CoachMotion.ENTER_MS, easing = CoachMotion.Expressive)) +
            slideInVertically(tween(CoachMotion.ENTER_MS, easing = CoachMotion.Expressive)) { rise },
        exit = fadeOut(tween(CoachMotion.EXIT_MS)) +
            slideOutVertically(tween(CoachMotion.EXIT_MS)) { rise },
    ) {
        CoachingContent(cue = shown, surface = surface)
    }
}

@Composable
private fun CoachingContent(cue: ArGuidanceCue, surface: PlacementSurface) {
    val dark = isSystemInDarkTheme()
    val scrim = if (dark) CoachColors.ScrimDark else CoachColors.ScrimLight
    val border = if (dark) CoachColors.ScrimBorderDark else CoachColors.ScrimBorderLight
    val sentence = coachingSentence(cue, surface)
    val word = coachingWord(cue)
    Column(
        modifier = Modifier.semantics(mergeDescendants = false) {
            contentDescription = sentence
            liveRegion = LiveRegionMode.Polite
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CoachMotion.GAP),
    ) {
        Box(
            modifier = Modifier
                .size(CoachMotion.DISC)
                .shadow(CoachMotion.SHADOW, CircleShape, clip = false)
                .background(scrim, CircleShape)
                .border(1.dp, border, CircleShape),
        ) {
            CoachGlyph(cue = cue, surface = surface, scrim = scrim)
        }
        if (word != null) {
            BasicText(
                text = word,
                modifier = Modifier
                    .background(scrim, RoundedCornerShape(percent = 50))
                    .border(1.dp, border, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                style = TextStyle(
                    color = CoachColors.OnScrim,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

@Composable
private fun coachingSentence(cue: ArGuidanceCue, surface: PlacementSurface): String {
    val wall = surface == PlacementSurface.WALL
    return when (cue) {
        ArGuidanceCue.NONE -> ""
        ArGuidanceCue.INITIALIZING -> stringResource(R.string.sceneview_coaching_initializing)
        ArGuidanceCue.SCAN -> stringResource(
            if (wall) R.string.sceneview_coaching_scan_wall else R.string.sceneview_coaching_scan_surface
        )
        ArGuidanceCue.SURFACE_FOUND -> stringResource(
            if (wall) R.string.sceneview_coaching_found_wall else R.string.sceneview_coaching_found_surface
        )
        ArGuidanceCue.TRACKING_LIMITED -> stringResource(R.string.sceneview_coaching_limited)
        ArGuidanceCue.RELOCALIZING -> stringResource(R.string.sceneview_coaching_relocalizing)
    }
}

@Composable
private fun coachingWord(cue: ArGuidanceCue): String? = when (cue) {
    ArGuidanceCue.SCAN -> stringResource(R.string.sceneview_coaching_word_scan)
    ArGuidanceCue.TRACKING_LIMITED -> stringResource(R.string.sceneview_coaching_word_paused)
    ArGuidanceCue.RELOCALIZING -> stringResource(R.string.sceneview_coaching_word_look_back)
    ArGuidanceCue.NONE, ArGuidanceCue.INITIALIZING, ArGuidanceCue.SURFACE_FOUND -> null
}

/** Animations off in system settings, or a one-frame preview/screenshot. */
@Composable
private fun rememberCoachMotionEnabled(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

@Composable
private fun CoachGlyph(cue: ArGuidanceCue, surface: PlacementSurface, scrim: Color) {
    val motion = rememberCoachMotionEnabled()
    val transition = rememberInfiniteTransition(label = "arCoaching")
    // One slow cycle = two sweeps at motion-coach-sweep speed, or one at half speed.
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(CoachMotion.SWEEP_MS * 2, easing = LinearEasing)),
        label = "sweep",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(CoachMotion.SPIN_MS, easing = LinearEasing)),
        label = "spin",
    )
    val found = cue == ArGuidanceCue.SURFACE_FOUND
    val resolve = remember(found, motion) { Animatable(if (found && motion) 0f else 1f) }
    LaunchedEffect(resolve) {
        if (found && motion) resolve.animateTo(1f, tween(CoachMotion.RESOLVE_MS, easing = CoachMotion.Expressive))
    }
    val cycles = if (cue == ArGuidanceCue.TRACKING_LIMITED) 1f else 2f
    val sweep = if (motion) sin(cycle * cycles * 2f * PI.toFloat()) else 0f
    val turn = when {
        !motion -> FROZEN_SPIN
        // The look-back arrow turns once per slow cycle — calmer than the progress dot.
        cue == ArGuidanceCue.RELOCALIZING -> cycle
        else -> spin
    }
    Canvas(modifier = Modifier.size(CoachMotion.DISC)) {
        drawCoachGlyph(
            cue = cue,
            surface = surface,
            sweep = sweep,
            spin = turn,
            resolve = if (found) resolve.value else 0f,
            scrim = scrim.copy(alpha = 1f),
        )
    }
}

/**
 * Draws one cue into a square [DrawScope] (the 96 dp disc). Internal so previews and
 * screenshot tests can pin poses deterministically.
 *
 * @param sweep −1..1 phone offset in the sweep (0 = centred, the frozen pose).
 * @param spin 0..1 progress of the rotating accents (initializing dot, look-back arrow).
 * @param resolve 0..1 progress of the "found" resolve (surface fills, cube lands).
 */
internal fun DrawScope.drawCoachGlyph(
    cue: ArGuidanceCue,
    surface: PlacementSurface,
    sweep: Float,
    spin: Float,
    resolve: Float,
    scrim: Color = Color.Black,
) {
    val s = size.minDimension
    val stroke = s * 0.026f
    val wall = surface == PlacementSurface.WALL
    when (cue) {
        ArGuidanceCue.NONE -> Unit
        ArGuidanceCue.INITIALIZING -> {
            val center = Offset(s * 0.5f, s * 0.5f)
            drawCoachPhone(center, s * 0.24f, CoachColors.OnScrim, stroke)
            // Progress dot orbiting on the phone's screen.
            val angle = spin * 2f * PI.toFloat()
            val r = s * 0.07f
            drawCircle(
                color = CoachColors.Primary,
                radius = s * 0.035f,
                center = center + Offset(cos(angle) * r, sin(angle) * r),
            )
        }
        ArGuidanceCue.SCAN -> drawScan(s, stroke, wall, sweep, scrim, alpha = 1f)
        ArGuidanceCue.SURFACE_FOUND -> drawFound(s, stroke, wall, resolve, scrim)
        ArGuidanceCue.TRACKING_LIMITED -> {
            drawScan(s, stroke, wall, sweep, scrim, alpha = 0.6f)
            // Pause badge in the guidance accent.
            val c = Offset(s * 0.74f, s * 0.27f)
            drawCircle(CoachColors.Warning, radius = s * 0.1f, center = c)
            val barW = s * 0.028f
            val barH = s * 0.09f
            for (dx in listOf(-s * 0.03f, s * 0.03f)) {
                drawRect(
                    color = scrim,
                    topLeft = Offset(c.x + dx - barW / 2f, c.y - barH / 2f),
                    size = Size(barW, barH),
                )
            }
        }
        ArGuidanceCue.RELOCALIZING -> {
            drawScan(s, stroke, wall, sweep, scrim, alpha = 0.6f)
            drawLookBackArrow(s, stroke, spin)
        }
    }
}

private fun DrawScope.drawTarget(
    s: Float,
    stroke: Float,
    wall: Boolean,
    color: Color,
    dashed: Boolean,
    fill: Color? = null,
) {
    val path = if (wall) {
        Path().apply { addRect(Rect(Offset(s * 0.24f, s * 0.16f), Size(s * 0.52f, s * 0.5f))) }
    } else {
        Path().apply {
            moveTo(s * 0.2f, s * 0.7f)
            lineTo(s * 0.5f, s * 0.6f)
            lineTo(s * 0.8f, s * 0.7f)
            lineTo(s * 0.5f, s * 0.8f)
            close()
        }
    }
    if (fill != null) drawPath(path, fill, style = Fill)
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = stroke * 0.8f,
            cap = StrokeCap.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(s * 0.05f, s * 0.04f)) else null,
        ),
    )
}

private fun DrawScope.drawScan(
    s: Float,
    stroke: Float,
    wall: Boolean,
    sweep: Float,
    scrim: Color,
    alpha: Float,
    phoneAlpha: Float = 1f,
) {
    drawTarget(s, stroke, wall, CoachColors.OnScrimDim.copy(alpha = 0.72f * alpha), dashed = true)
    if (phoneAlpha <= 0f) return
    // motion-coach-sweep: ±18 dp of a 96 dp disc, ±10° of roll.
    val dx = sweep * s * 0.19f
    val center = if (wall) Offset(s * 0.5f + dx, s * 0.68f) else Offset(s * 0.5f + dx, s * 0.38f)
    val width = if (wall) s * 0.17f else s * 0.19f
    rotate(degrees = sweep * 10f, pivot = center) {
        drawCoachPhone(
            center = center,
            width = width,
            color = CoachColors.OnScrim.copy(alpha = alpha * phoneAlpha),
            stroke = stroke,
            fill = if (wall) scrim else null,
        )
    }
}

/** motion-coach-resolve: the surface fills in `primary` and a cube lands on it. */
private fun DrawScope.drawFound(s: Float, stroke: Float, wall: Boolean, resolve: Float, scrim: Color) {
    val r = resolve.coerceIn(0f, 1f)
    drawTarget(
        s, stroke, wall,
        color = lerp(CoachColors.OnScrimDim, CoachColors.Primary, r),
        dashed = r < 0.5f,
        fill = CoachColors.Primary.copy(alpha = 0.35f * r),
    )
    // The phone lifts away as the cube arrives.
    drawScan(s, stroke, wall, sweep = 0f, scrim = scrim, alpha = 0f, phoneAlpha = 1f - r)
    if (r <= 0f) return
    val top = CoachColors.OnScrim.copy(alpha = r)
    val left = CoachColors.Primary.copy(alpha = r)
    val right = lerp(CoachColors.Primary, scrim, 0.35f).copy(alpha = r)
    if (wall) {
        // Cube pressed onto the wall: comes from the viewer (1.4×) down to 1×.
        val k = 1.4f - 0.4f * r
        val e = s * 0.2f * k
        val d = e * 0.3f
        val o = Offset(s * 0.5f - e / 2f, s * 0.41f - e / 2f)
        drawPath(quad(o, o + Offset(d, -d), o + Offset(e + d, -d), o + Offset(e, 0f)), top)
        drawPath(quad(o + Offset(e, 0f), o + Offset(e + d, -d), o + Offset(e + d, e - d), o + Offset(e, e)), right)
        drawRect(left, topLeft = o, size = Size(e, e))
    } else {
        // Cube dropping onto the diamond's centre.
        val hw = s * 0.14f
        val hh = s * 0.047f
        val h = s * 0.16f
        val by = s * 0.7f - (1f - r) * s * 0.22f
        val bx = s * 0.5f
        fun p(x: Float, y: Float) = Offset(bx + x, by + y)
        drawPath(quad(p(-hw, -h), p(0f, -h - hh), p(hw, -h), p(0f, -h + hh)), top)
        drawPath(quad(p(-hw, -h), p(0f, -h + hh), p(0f, hh), p(-hw, 0f)), left)
        drawPath(quad(p(0f, -h + hh), p(hw, -h), p(hw, 0f), p(0f, hh)), right)
    }
}

private fun quad(a: Offset, b: Offset, c: Offset, d: Offset) = Path().apply {
    moveTo(a.x, a.y)
    lineTo(b.x, b.y)
    lineTo(c.x, c.y)
    lineTo(d.x, d.y)
    close()
}

/** Circular "come back" arrow around the glyph, in the guidance accent. */
private fun DrawScope.drawLookBackArrow(s: Float, stroke: Float, spin: Float) {
    val radius = s * 0.38f
    val center = Offset(s * 0.5f, s * 0.5f)
    val start = spin * 360f - 90f
    val sweepAngle = 270f
    drawArc(
        color = CoachColors.Warning,
        startAngle = start,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = center - Offset(radius, radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
    val end = Math.toRadians((start + sweepAngle).toDouble()).toFloat()
    val tip = center + Offset(cos(end) * radius, sin(end) * radius)
    // Tangent direction of travel (clockwise in screen space).
    val tangent = Offset(-sin(end), cos(end))
    val normal = Offset(cos(end), sin(end))
    val head = s * 0.06f
    val arrow = Path().apply {
        moveTo(tip.x + tangent.x * head, tip.y + tangent.y * head)
        lineTo(tip.x + normal.x * head * 0.8f, tip.y + normal.y * head * 0.8f)
        lineTo(tip.x - normal.x * head * 0.8f, tip.y - normal.y * head * 0.8f)
        close()
    }
    drawPath(arrow, CoachColors.Warning)
}

/** Dot position when animations are off: upper-right of the screen, readable at a glance. */
private const val FROZEN_SPIN = 0.875f

/** `DESIGN.md` → *AR Coaching Motion*. Kept together so a token change is one edit. */
internal object CoachMotion {
    /** `motion-coach-sweep`: one left-right-left sweep. */
    const val SWEEP_MS = 1_600

    /** `motion-coach-resolve`: surface fills, cube lands. */
    const val RESOLVE_MS = 450

    /** Initializing dot / look-back arrow revolution. */
    const val SPIN_MS = 1_000

    /** `duration-medium` enter (fade + 8 dp rise). */
    const val ENTER_MS = 350

    /** `duration-short` exit. */
    const val EXIT_MS = 200

    /** `ease-expressive`, cubic-bezier(0.2, 0, 0, 1). */
    val Expressive = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    val RISE = 8.dp
    val DISC = 96.dp
    val GAP = 8.dp
    val SHADOW = 12.dp
}

/** `DESIGN.md` AR tokens. Accents are the dark-scheme values in both themes (read on `ar-scrim`). */
private object CoachColors {
    val ScrimLight = Color(0xF0000000) // ar-scrim light, black at 0.94
    val ScrimDark = Color(0xE0000000) // ar-scrim dark, black at 0.88
    val ScrimBorderLight = Color(0x29FFFFFF) // ar-scrim-border light, white at 0.16
    val ScrimBorderDark = Color(0x1AFFFFFF) // ar-scrim-border dark, white at 0.10
    val OnScrim = Color.White // on-ar-scrim
    val OnScrimDim = Color(0xB8FFFFFF) // on-ar-scrim-dim, white at 0.72
    val Primary = Color(0xFFA4C1FF) // primary, dark-scheme value
    val Warning = Color(0xFFF59E0B) // warning
}
