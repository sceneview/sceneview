package io.github.sceneview.ar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * The pinch-scale detent of automatic placement: one source of truth for the 25–400 % range,
 * the 100 % snap window and the elastic rebound played when a pinch lands on 100 %.
 *
 * Pure math — the pivot node applies it, tests pin it. The same numbers ship on iOS
 * (`ARScaleSnap`).
 */
internal object ScaleSnap {
    const val MIN: Float = 0.25f
    const val MAX: Float = 4f

    /** ±4 % around the real-world size snaps to exactly 100 %. */
    const val WINDOW: Float = 0.04f

    /** Rebound amplitude (5 % of the size), decay constant and period. */
    const val REBOUND_AMPLITUDE: Float = 0.05f
    const val REBOUND_DECAY_MS: Float = 80f
    const val REBOUND_PERIOD_MS: Float = 160f

    /** After this, the rebound ends on exactly 1. */
    const val REBOUND_MS: Long = 280L

    private const val EPSILON = 0.001f

    /**
     * One pinch update.
     *
     * @property displayed the logical scale to apply (exactly 1 inside the window).
     * @property snapped the value sits in the 100 % detent.
     * @property enteredSnap this update entered the detent — play the tick and the rebound.
     * @property enteredLimit this update reached 25 % or 400 % — play the limit tick once.
     */
    data class Step(
        val displayed: Float,
        val snapped: Boolean,
        val enteredSnap: Boolean,
        val enteredLimit: Boolean,
    )

    fun step(previousDisplayed: Float, raw: Float): Step {
        val clamped = raw.coerceIn(MIN, MAX)
        val snapped = abs(clamped - 1f) < WINDOW
        val displayed = if (snapped) 1f else clamped
        val wasSnapped = abs(previousDisplayed - 1f) < EPSILON
        val atLimit = isAtLimit(displayed)
        return Step(
            displayed = displayed,
            snapped = snapped,
            enteredSnap = snapped && !wasSnapped,
            enteredLimit = atLimit && !isAtLimit(previousDisplayed),
        )
    }

    fun isAtLimit(scale: Float): Boolean = scale <= MIN + EPSILON || scale >= MAX - EPSILON

    /**
     * The raw (pre-snap) scale of a pinch, **relative to the gesture's start**: the scale the
     * object had when two fingers landed, times the ratio of the current finger span to the
     * span at that moment. 1:1 with the fingers — spreading them twice as far doubles the
     * object — which is what Scene Viewer and AR Quick Look do (`UIPinchGestureRecognizer.scale`
     * on iOS is exactly this ratio), and it is path-independent: bringing the fingers back to
     * where they started returns the object to exactly the size it started at, however much
     * the span jittered on the way.
     *
     * Returns [startScale] unchanged for a degenerate span (zero, negative, non-finite).
     */
    fun pinchRaw(startScale: Float, startSpan: Float, currentSpan: Float): Float {
        val ratio = currentSpan / startSpan
        // NaN, ±∞, zero and negative spans all fail this one test.
        return if (startSpan > 0f && ratio > 0f && ratio.isFinite()) startScale * ratio else startScale
    }

    /**
     * Where a double-tap on the placed object takes it, or `null` for "nowhere — acknowledge".
     *
     * Resized away from 100 % ⇒ back to exactly 100 % (the AR Quick Look reset). Already at
     * 100 % ⇒ back to the size the user had pinched to before the last reset, so a double-tap
     * is a toggle between real-world size and *their* size rather than a one-way door. Never
     * resized ⇒ `null`: the caller plays the rebound so the tap is felt, but nothing changes.
     */
    fun doubleTapTarget(current: Float, remembered: Float?): Float? = when {
        abs(current - 1f) > EPSILON -> 1f
        remembered != null && abs(remembered - 1f) > EPSILON -> remembered.coerceIn(MIN, MAX)
        else -> null
    }

    /** Duration of the animated double-tap resize, milliseconds. */
    const val DOUBLE_TAP_MS: Long = 260L

    /**
     * Visual scale [elapsedMs] into the animated double-tap resize from [from] to [to]:
     * cubic ease-out, the same curve as the placement entrance, landing exactly on [to].
     */
    fun doubleTapProgress(from: Float, to: Float, elapsedMs: Float): Float {
        if (elapsedMs >= DOUBLE_TAP_MS) return to
        val t = (elapsedMs / DOUBLE_TAP_MS).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        return from + (to - from) * eased
    }

    /**
     * The visual scale [elapsedMs] after entering the detent: a damped sine around 1 that
     * first continues the pinch's direction (below 1 when shrinking into it, [fromAbove]),
     * then settles. Exactly 1 from [REBOUND_MS] on.
     */
    fun rebound(elapsedMs: Float, fromAbove: Boolean): Float {
        if (elapsedMs <= 0f || elapsedMs >= REBOUND_MS) return 1f
        val sign = if (fromAbove) -1f else 1f
        val decay = exp(-elapsedMs / REBOUND_DECAY_MS)
        return 1f + sign * REBOUND_AMPLITUDE * decay * sin(2f * PI.toFloat() * elapsedMs / REBOUND_PERIOD_MS)
    }
}
