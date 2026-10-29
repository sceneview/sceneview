package io.github.sceneview.animation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * Brightness curves for animated lights and glowing (emissive) surfaces.
 *
 * Every cue is a pure function of time that returns a **level**, `0` (dark) to `1` (full):
 * multiply a light's intensity, or a material's emissive strength, by it on every frame. No
 * state, no allocation, deterministic — the same `seconds` always gives the same level, so a
 * paused clock holds the picture and a test can assert any frame.
 *
 * ```kotlin
 * // In SceneView's onFrame: neon strikes at 0.4 s, headlights fade in at 1.2 s.
 * val neon = LightCues.ignition(seconds, startSeconds = 0.4f, seed = 3)
 * neonMaterial.setParameter("emissiveStrength", 3.2f * neon)
 * headlight.intensity = 1e6f * LightCues.fadeIn(seconds, startSeconds = 1.2f)
 * ```
 */
object LightCues {

    /**
     * A neon tube or fluorescent lamp striking: dark before [startSeconds], a burst of
     * irregular flickers over [durationSeconds] in which the tube catches more and more
     * often, then steady at `1`. [seed] picks the flicker pattern, so tubes started together
     * do not blink in lockstep.
     */
    fun ignition(
        seconds: Float,
        startSeconds: Float = 0f,
        durationSeconds: Float = 0.8f,
        seed: Int = 0,
    ): Float {
        if (durationSeconds <= 0f) return if (seconds >= startSeconds) 1f else 0f
        val u = (seconds - startSeconds) / durationSeconds
        if (u <= 0f) return 0f
        if (u >= 1f) return 1f
        val slot = floor(u * IGNITION_SLOTS).toInt()
        // The last slot is already steady, so the curve lands on 1 without a jump, and an end
        // time that rounds a hair short of the end (Kotlin/JS computes in doubles) still reads 1.
        if (slot >= IGNITION_SLOTS - 1) return 1f
        val roll = hash01(seed, slot)
        // The chance a slot is lit rises with progress.
        val lit = roll < u * u * 1.2f
        val grain = hash01(seed + 7919, slot)
        return if (lit) 0.8f + 0.2f * grain else 0.06f * grain
    }

    /**
     * A lamp powering up: `0` before [startSeconds], then [easing] up to `1` over
     * [durationSeconds], and `1` from then on.
     */
    fun fadeIn(
        seconds: Float,
        startSeconds: Float = 0f,
        durationSeconds: Float = 0.6f,
        easing: (Float) -> Float = Easing.EaseOut,
    ): Float {
        if (durationSeconds <= 0f) return if (seconds >= startSeconds) 1f else 0f
        val u = ((seconds - startSeconds) / durationSeconds).coerceIn(0f, 1f)
        return easing(u).coerceIn(0f, 1f)
    }

    /**
     * Slow breathing between [floor] and `1`, one breath per [periodSeconds], starting at
     * [floor] at `seconds = 0`.
     */
    fun breathe(seconds: Float, periodSeconds: Float = 3f, floor: Float = 0.6f): Float {
        if (periodSeconds <= 0f) return 1f
        val wave = 0.5f - 0.5f * cos(2f * PI.toFloat() * seconds / periodSeconds)
        return floor + (1f - floor) * wave
    }

    /**
     * An on/off blink — a beacon, a hazard light: lit for the first [duty] of every
     * [periodSeconds], dark for the rest. [softness] is the fraction of the period each edge
     * takes to ramp, so the lamp reads as a filament rather than a switch; `0` is a hard edge.
     */
    fun blink(
        seconds: Float,
        periodSeconds: Float = 1f,
        duty: Float = 0.5f,
        softness: Float = 0.06f,
    ): Float {
        if (periodSeconds <= 0f) return 1f
        val d = duty.coerceIn(0f, 1f)
        if (d == 0f) return 0f
        if (d == 1f) return 1f
        val phase = positiveFraction(seconds / periodSeconds)
        val edge = softness.coerceIn(0f, d / 2f).coerceAtMost((1f - d) / 2f)
        if (edge == 0f) return if (phase < d) 1f else 0f
        return when {
            phase < edge -> phase / edge
            phase < d - edge -> 1f
            phase < d -> 1f - (phase - (d - edge)) / edge
            else -> 0f
        }.coerceIn(0f, 1f)
    }

    /**
     * A brief flash once every [periodSeconds] — headlights flashed, a strobe: `0` most of
     * the time, rising to `1` over [attackSeconds] at [offsetSeconds] into each period, and
     * decaying back over [releaseSeconds]. Add it to a base level to flash *above* it.
     */
    fun flash(
        seconds: Float,
        periodSeconds: Float,
        offsetSeconds: Float = 0f,
        attackSeconds: Float = 0.05f,
        releaseSeconds: Float = 0.35f,
    ): Float {
        if (periodSeconds <= 0f) return 0f
        val local = positiveFraction((seconds - offsetSeconds) / periodSeconds) * periodSeconds
        return when {
            attackSeconds > 0f && local < attackSeconds -> local / attackSeconds
            local < attackSeconds + releaseSeconds && releaseSeconds > 0f ->
                1f - Easing.EaseOut((local - attackSeconds) / releaseSeconds)
            else -> 0f
        }.coerceIn(0f, 1f)
    }

    /**
     * A tube that is running but worn: steady at `1`, except that once in every
     * [everySeconds], at a moment [seed] picks, it drops out for [lengthSeconds] —
     * two or three quick blinks down to [depth] — and catches again.
     */
    fun glitch(
        seconds: Float,
        everySeconds: Float = 6f,
        lengthSeconds: Float = 0.22f,
        depth: Float = 0.15f,
        seed: Int = 0,
    ): Float {
        if (everySeconds <= 0f || lengthSeconds <= 0f) return 1f
        val window = floor(seconds / everySeconds).toInt()
        val room = (everySeconds - lengthSeconds).coerceAtLeast(0f)
        val at = window * everySeconds + hash01(seed, window) * room
        val u = (seconds - at) / lengthSeconds
        if (u < 0f || u >= 1f) return 1f
        val slot = floor(u * GLITCH_SLOTS).toInt()
        // Alternating dark and lit slots, the first one dark: it reads as a stutter.
        return if (slot % 2 == 0) depth else 1f
    }

    /** Slots an [ignition] is cut into; at 0.8 s each lasts 80 ms, a few frames. */
    private const val IGNITION_SLOTS = 10

    private const val GLITCH_SLOTS = 5

    private fun positiveFraction(x: Float): Float {
        val f = x - floor(x)
        return if (f >= 1f) 0f else f
    }

    /** A well-mixed integer hash of ([seed], [index]) mapped to `[0, 1)`. */
    private fun hash01(seed: Int, index: Int): Float {
        var h = seed * 0x27d4eb2d + index * 0x165667b1
        h = h xor (h ushr 15)
        h *= -0x7a143595
        h = h xor (h ushr 13)
        h *= -0x3d4d51cb
        h = h xor (h ushr 16)
        return (h ushr 8) / 16_777_216f
    }
}
