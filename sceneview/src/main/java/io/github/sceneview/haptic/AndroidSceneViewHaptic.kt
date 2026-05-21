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
 * ### API 24-25 safety
 *
 * The module `minSdk` is **24**. [android.os.VibrationEffect] is an **API 26
 * (O)** class — referencing any of its static fields on API 24-25 throws
 * `NoClassDefFoundError`, a hard crash on a supported API. This class never
 * names a `VibrationEffect` symbol directly: legacy preset fallbacks pass the
 * API-safe [HAPTIC_DEFAULT_AMPLITUDE] sentinel, and only [SystemHapticEngine]
 * resolves it — inside an `SDK_INT >= O` branch.
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
            // VibrationEffect.EFFECT_* is only resolved inside this >= Q
            // branch — never on API 24-25, where the class is absent.
            playPredefined(VibrationEffect.EFFECT_CLICK)
        } else {
            // No EFFECT_CLICK pre-Q; a short single tick is the closest match.
            // HAPTIC_DEFAULT_AMPLITUDE is API-safe — never resolves a
            // VibrationEffect symbol on API 24-25.
            playOneShot(durationMs = 10, amplitude = HAPTIC_DEFAULT_AMPLITUDE)
        }
    }

    override fun medium(): Unit = whenEnabled {
        if (sdkInt >= Build.VERSION_CODES.Q) {
            playPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            playOneShot(durationMs = 20, amplitude = HAPTIC_DEFAULT_AMPLITUDE)
        }
    }

    override fun heavy(): Unit = whenEnabled {
        if (sdkInt >= Build.VERSION_CODES.Q) {
            playPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        } else {
            playOneShot(durationMs = 40, amplitude = HAPTIC_DEFAULT_AMPLITUDE)
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
            playOneShot(durationMs = 10, amplitude = HAPTIC_DEFAULT_AMPLITUDE)
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
        // matching the `[0,30,30,30]` warning layout above. HapticEvent
        // exposes Int milliseconds; the Vibrator long[] APIs need Long, so
        // widen here at the single boundary that touches the platform.
        val timings = LongArray(events.size * 2)
        val amplitudes = IntArray(events.size * 2)
        for ((i, event) in events.withIndex()) {
            timings[i * 2] = event.delayMs.coerceAtLeast(0).toLong()
            timings[i * 2 + 1] = event.durationMs.coerceAtLeast(0).toLong()
            amplitudes[i * 2] = 0
            amplitudes[i * 2 + 1] = scaleIntensityToAmplitude(event.intensity)
        }
        // An all-zero timing array (every event durationMs == 0 && delayMs == 0)
        // passes the isEmpty() guard above but makes VibrationEffect.createWaveform
        // throw IllegalArgumentException ("at least one timing must be non-zero").
        // Treat a zero-total pattern as a no-op.
        if (timings.sum() == 0L) return@whenEnabled
        if (sdkInt >= Build.VERSION_CODES.O) {
            playAmplitudeWaveform(timings, amplitudes)
        } else {
            playWaveform(timings)
        }
    }

    override fun cancel() {
        // Bypass whenEnabled() on purpose: cancel() is called from
        // rememberHapticFeedback's onDispose for *every* haptic instance,
        // including degraded ones. Routing it through whenEnabled() would
        // emit a spurious "no vibrator / no permission" Log.d on teardown.
        // A missing engine or permission simply means nothing to cancel.
        if (hasVibratePermission) {
            engine?.cancel()
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
