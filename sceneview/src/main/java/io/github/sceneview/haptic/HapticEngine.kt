package io.github.sceneview.haptic

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Package-internal sentinel for "let the OS pick the amplitude".
 *
 * [android.os.VibrationEffect] is an **API 26 (O)** class — referencing
 * `VibrationEffect.DEFAULT_AMPLITUDE` (or any of its static fields) on an
 * API 24-25 device throws `NoClassDefFoundError`. The preset-mapping logic in
 * [AndroidSceneViewHaptic] runs on every supported API down to `minSdk 24`,
 * so it must never name a `VibrationEffect` symbol directly. Callers pass this
 * sentinel instead; only [SystemHapticEngine]'s `>= O` branch — which is
 * already guarded by an `SDK_INT` check — resolves it to the real
 * `VibrationEffect.DEFAULT_AMPLITUDE`.
 */
internal const val HAPTIC_DEFAULT_AMPLITUDE: Int = Int.MIN_VALUE

/**
 * Internal abstraction over the actual `Vibrator.vibrate(...)` calls so the
 * preset-mapping logic in [AndroidSceneViewHaptic] is testable on a pure
 * JVM (no Robolectric). The production implementation
 * [SystemHapticEngine] delegates to a real [Vibrator]; tests pass a
 * recording fake.
 *
 * Methods take values pre-resolved into platform primitives — predefined
 * effect ids, raw waveform `long[]`, intensity-scaled one-shot durations —
 * so the engine itself doesn't care about presets and the mapping table
 * in [AndroidSceneViewHaptic] is the single source of truth.
 *
 * Amplitudes are plain `Int`s in `0..255`, or the API-safe
 * [HAPTIC_DEFAULT_AMPLITUDE] sentinel for "OS default". No method on this
 * interface — nor any of its callers — references [android.os.VibrationEffect]
 * unless it is inside an `SDK_INT >= O` branch, so the engine is safe to use
 * on API 24-25.
 */
internal interface HapticEngine {
    /**
     * Play a predefined effect (API 29+). Implementations on older API
     * levels can fall back; the parameter is always passed regardless.
     */
    fun playPredefined(effectId: Int)

    /** Play a raw waveform (timings in ms, no per-step amplitude). */
    fun playWaveform(timings: LongArray, repeat: Int = -1)

    /**
     * Play a waveform with per-step amplitudes (API 26+). Each amplitude is
     * a byte in 0..255. Implementations on older API levels fall back to a
     * timings-only waveform.
     */
    fun playAmplitudeWaveform(timings: LongArray, amplitudes: IntArray, repeat: Int = -1)

    /**
     * One-shot vibration of [durationMs] at [amplitude] (`1..255`, or
     * [HAPTIC_DEFAULT_AMPLITUDE] for the OS default).
     */
    fun playOneShot(durationMs: Long, amplitude: Int)

    /** Cancel any in-progress vibration. Maps to `Vibrator.cancel()`. */
    fun cancel()

    /** Sdk int — kept on the engine so test fakes can simulate older APIs. */
    val sdkInt: Int
}

internal class SystemHapticEngine(private val vibrator: Vibrator) : HapticEngine {

    override val sdkInt: Int get() = Build.VERSION.SDK_INT

    @Suppress("DEPRECATION")
    override fun playPredefined(effectId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(effectId))
        } else {
            // Fallback for API 24..28: tick-like single shot. `playOneShot`
            // itself is API-safe — it never names a VibrationEffect symbol on
            // API 24-25.
            playOneShot(durationMs = 20, amplitude = HAPTIC_DEFAULT_AMPLITUDE)
        }
    }

    @Suppress("DEPRECATION")
    override fun playWaveform(timings: LongArray, repeat: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, repeat))
        } else {
            // API 24-25: legacy long[] overload, no VibrationEffect reference.
            vibrator.vibrate(timings, repeat)
        }
    }

    @Suppress("DEPRECATION")
    override fun playAmplitudeWaveform(timings: LongArray, amplitudes: IntArray, repeat: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, repeat))
        } else {
            // API 24-25: legacy long[] overload — no per-step amplitude, no
            // VibrationEffect reference.
            vibrator.vibrate(timings, repeat)
        }
    }

    @Suppress("DEPRECATION")
    override fun playOneShot(durationMs: Long, amplitude: Int) {
        if (durationMs <= 0L) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // VibrationEffect is API 26 — only resolved inside this branch.
            // The sentinel maps to the OS-default amplitude here.
            val resolved = if (amplitude == HAPTIC_DEFAULT_AMPLITUDE) {
                VibrationEffect.DEFAULT_AMPLITUDE
            } else {
                amplitude
            }
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, resolved))
        } else {
            // API 24-25: the deprecated duration-only overload. No amplitude
            // control and — critically — no VibrationEffect class reference.
            vibrator.vibrate(durationMs)
        }
    }

    override fun cancel() {
        vibrator.cancel()
    }
}
