package io.github.sceneview.haptic

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default [SceneViewHaptic] implementation backed by an Android [Vibrator].
 *
 * Internally testable: the [engine] parameter is an injection point so JVM
 * unit tests can pass a recording fake (`RecordingHapticEngine`) and assert
 * the exact platform call each preset issues, without needing Robolectric.
 *
 * No-op behaviour:
 * - `vibratorOrNull = null` → no vibrator on device → every method returns
 *   silently. One `Log.d` is emitted on the first call.
 * - `hasVibratePermission = false` → consumer app didn't add `VIBRATE` →
 *   every method returns silently. One `Log.d` is emitted on the first call.
 */
internal class AndroidSceneViewHaptic internal constructor(
    private val engine: HapticEngine?,
    private val hasVibratePermission: Boolean,
) : SceneViewHaptic {

    constructor(vibratorOrNull: Vibrator?, hasVibratePermission: Boolean) : this(
        engine = vibratorOrNull?.let(::SystemHapticEngine),
        hasVibratePermission = hasVibratePermission,
    )

    private val loggedDegradation = AtomicBoolean(false)

    private inline fun whenEnabled(block: HapticEngine.() -> Unit) {
        if (engine == null) {
            logDegradation("no vibrator on device; all calls are no-op")
            return
        }
        if (!hasVibratePermission) {
            logDegradation(
                "VIBRATE permission missing; add " +
                    "<uses-permission android:name=\"android.permission.VIBRATE\" /> " +
                    "to the consumer app manifest. All calls are no-op."
            )
            return
        }
        engine.block()
    }

    private fun logDegradation(message: String) {
        if (loggedDegradation.compareAndSet(false, true)) {
            // Wrapped: Log.d throws a RuntimeException("Method d in
            // android.util.Log not mocked") in pure-JVM unit tests. The
            // diagnostic line is non-essential — swallow so the degradation
            // path stays observable from instrumented tests but never breaks
            // the JVM suite.
            try {
                Log.d(SCENEVIEW_HAPTIC_TAG, message)
            } catch (_: Throwable) {
                // Pure-JVM test runtime — Log.d is not mocked. Ignore.
            }
        }
    }

    override fun light(): Unit = whenEnabled {
        if (sdkInt >= Build.VERSION_CODES.Q) {
            playPredefined(VibrationEffect.EFFECT_CLICK)
        } else {
            // No EFFECT_CLICK pre-Q; a short single tick is the closest match.
            playOneShot(durationMs = 10, amplitude = VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    override fun medium(): Unit = whenEnabled {
        if (sdkInt >= Build.VERSION_CODES.Q) {
            playPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            playOneShot(durationMs = 20, amplitude = VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    override fun heavy(): Unit = whenEnabled {
        if (sdkInt >= Build.VERSION_CODES.Q) {
            playPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        } else {
            playOneShot(durationMs = 40, amplitude = VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    override fun success(): Unit = whenEnabled {
        if (sdkInt >= Build.VERSION_CODES.Q) {
            playPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        } else {
            playWaveform(longArrayOf(0, 30, 80, 30))
        }
    }

    override fun warning(): Unit = whenEnabled {
        // No `EFFECT_*` for warning — use a triple-tick waveform on every API.
        playWaveform(longArrayOf(0, 30, 30, 30))
    }

    override fun error(): Unit = whenEnabled {
        // No `EFFECT_*` for error — use a longer descending-tick waveform.
        playWaveform(longArrayOf(0, 50, 30, 50, 30, 50))
    }

    override fun selection(): Unit = whenEnabled {
        if (sdkInt >= Build.VERSION_CODES.Q) {
            playPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            playOneShot(durationMs = 10, amplitude = VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    override fun continuous(intensity: Float, durationMs: Long): Unit = whenEnabled {
        if (durationMs <= 0L) return@whenEnabled
        val amplitude = scaleIntensityToAmplitude(intensity)
        playOneShot(durationMs = durationMs, amplitude = amplitude)
    }

    override fun pattern(events: List<HapticEvent>): Unit = whenEnabled {
        if (events.isEmpty()) return@whenEnabled
        // Build alternating off/on timings — first entry is a leading delay,
        // matching the `[0,30,30,30]` warning layout above.
        val timings = LongArray(events.size * 2)
        val amplitudes = IntArray(events.size * 2)
        for ((i, event) in events.withIndex()) {
            timings[i * 2] = event.delayMs.coerceAtLeast(0)
            timings[i * 2 + 1] = event.durationMs.coerceAtLeast(0)
            amplitudes[i * 2] = 0
            amplitudes[i * 2 + 1] = scaleIntensityToAmplitude(event.intensity)
        }
        if (sdkInt >= Build.VERSION_CODES.O) {
            playAmplitudeWaveform(timings, amplitudes)
        } else {
            playWaveform(timings)
        }
    }

    private fun scaleIntensityToAmplitude(intensity: Float): Int {
        val clamped = intensity.coerceIn(0f, 1f)
        // Avoid 0 (interpreted as DEFAULT_AMPLITUDE off / pause on some
        // devices when used with a one-shot); clamp lower bound to 1.
        val amplitude = (clamped * 255f).toInt().coerceIn(1, 255)
        return amplitude
    }
}
