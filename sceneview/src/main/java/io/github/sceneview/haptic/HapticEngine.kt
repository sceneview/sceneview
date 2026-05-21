package io.github.sceneview.haptic

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.annotation.RequiresApi

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

    /** One-shot vibration of [durationMs] at [amplitude] (1..255). */
    fun playOneShot(durationMs: Long, amplitude: Int)

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
            // Fallback for API 26..28: tick-like single shot.
            playOneShot(durationMs = 20, amplitude = VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    @Suppress("DEPRECATION")
    override fun playWaveform(timings: LongArray, repeat: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, repeat))
        } else {
            vibrator.vibrate(timings, repeat)
        }
    }

    @Suppress("DEPRECATION")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun playAmplitudeWaveform(timings: LongArray, amplitudes: IntArray, repeat: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, repeat))
        } else {
            vibrator.vibrate(timings, repeat)
        }
    }

    @Suppress("DEPRECATION")
    override fun playOneShot(durationMs: Long, amplitude: Int) {
        if (durationMs <= 0L) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        } else {
            vibrator.vibrate(durationMs)
        }
    }
}
