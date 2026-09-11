package io.github.sceneview.compose

import kotlin.math.exp
import kotlin.math.ln

/**
 * Distance ratio one double-tap covers: a double-tap halves the camera-to-target distance, a
 * two-finger tap doubles it back. The step every map and photo viewer uses (#3608).
 */
internal const val DOUBLE_TAP_ZOOM_FACTOR = 2f

/**
 * How long the double-tap zoom takes, in milliseconds — the platform's own double-tap zoom
 * timing. Long enough to read as a move rather than a jump cut, short enough not to feel slow.
 */
internal const val DOUBLE_TAP_ZOOM_DURATION_MS = 300L

/**
 * Standard cubic ease-in-out on `[0, 1]`, clamped. Mirrors the curve `:sceneview` uses for the
 * same gesture, so the façade and the Android SDK feel identical.
 */
internal fun easeInOutCubic(progress: Float): Float {
    if (progress.isNaN()) return 1f
    val t = progress.coerceIn(0f, 1f)
    return if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
}

/**
 * The camera-to-target distance part-way through a double-tap zoom.
 *
 * Interpolation is **geometric**, not linear: `start · (target / start)^eased(progress)`. What the
 * eye reads is the *ratio* of successive framings, not their difference, so a linear ramp looks
 * fast at the far end and crawls at the near end. Geometric interpolation keeps the apparent zoom
 * speed constant over the whole move.
 */
internal fun animatedZoomDistance(start: Float, target: Float, progress: Float): Float {
    if (!start.isFinite() || start <= 0f) return target
    if (!target.isFinite() || target <= 0f) return start
    val interpolated = start * exp(ln(target / start) * easeInOutCubic(progress))
    return if (interpolated.isFinite() && interpolated > 0f) interpolated else target
}
