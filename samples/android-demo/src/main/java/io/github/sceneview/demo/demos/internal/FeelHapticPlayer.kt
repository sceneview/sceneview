package io.github.sceneview.demo.demos.internal

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import io.github.sceneview.demo.demos.internal.feel.FeelPulse
import io.github.sceneview.demo.demos.internal.feel.FeelPulseKind
import io.github.sceneview.demo.demos.internal.feel.foamIntensity
import kotlin.math.roundToInt

/** Android boundary only. Timing, strength and one-shot stops live in the pure scheduler. */
internal class FeelHapticPlayer(private val view: View) {
    private val vibrator = view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val supported = mutableMapOf<Int, Boolean>()
    // Exact design envelope sampled at 20 ms. A zero amplitude is an off segment.
    private val foamTimings = LongArray(71) { if (it == 0) 0L else 20L }
    private val foamAmplitudes = IntArray(71) { (255f * foamIntensity(it * 20L)).roundToInt() }
    private var foamStartedAt: Long? = null

    fun play(pulse: FeelPulse) {
        // Vibrator does not consistently apply the View's touch-feedback setting itself.
        if (!view.isAttachedToWindow || !view.hasWindowFocus() || !view.isHapticFeedbackEnabled ||
            Settings.System.getInt(view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0
        ) return
        val motor = vibrator
        if (pulse.kind == FeelPulseKind.FoamEnvelope) {
            // Without amplitude control there is no faithful soft bed: omit it, never buzz.
            if (motor != null && motor.hasVibrator() && Build.VERSION.SDK_INT >= 26 && motor.hasAmplitudeControl()) {
                try {
                    motor.vibrate(VibrationEffect.createWaveform(foamTimings, foamAmplitudes, -1), attributes)
                    foamStartedAt = SystemClock.uptimeMillis()
                } catch (_: RuntimeException) { /* Optional device capability. */ }
            }
            return
        }
        if (motor != null && motor.hasVibrator() && Build.VERSION.SDK_INT >= 26) {
            try {
                // API 30 cannot reliably query individual primitive support.
                if (Build.VERSION.SDK_INT >= 31) {
                    val primitive = when (pulse.kind) {
                        FeelPulseKind.LowTick -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                        FeelPulseKind.Click -> VibrationEffect.Composition.PRIMITIVE_CLICK
                        FeelPulseKind.FoamEnvelope -> return
                    }
                    if (supported.getOrPut(primitive) { motor.areAllPrimitivesSupported(primitive) }) {
                        motor.vibrate(
                            VibrationEffect.startComposition().addPrimitive(primitive, pulse.scale).compose(),
                            attributes,
                        )
                        return
                    }
                }
                val duration = when (pulse.kind) {
                    FeelPulseKind.LowTick -> 8L
                    FeelPulseKind.Click -> 20L
                    FeelPulseKind.FoamEnvelope -> return
                }
                val amplitude = if (motor.hasAmplitudeControl()) {
                    (pulse.scale * 255f).toInt().coerceIn(1, 255)
                } else VibrationEffect.DEFAULT_AMPLITUDE
                motor.vibrate(VibrationEffect.createOneShot(duration, amplitude), attributes)
                return
            } catch (_: RuntimeException) {
                // Missing permission or a vendor implementation rejecting an advertised effect.
            }
        }
        view.performHapticFeedback(
            if (pulse.kind == FeelPulseKind.Click) HapticFeedbackConstants.CONTEXT_CLICK
            else HapticFeedbackConstants.CLOCK_TICK,
        )
    }

    fun cancel() {
        foamStartedAt = null
        try { vibrator?.cancel() } catch (_: RuntimeException) { /* Optional device capability. */ }
    }

    /** A normal finger lift gets the design's 20 ms tail; interruption always uses cancel(). */
    fun release() {
        val started = foamStartedAt
        val amplitude = started?.let { (255f * foamIntensity(SystemClock.uptimeMillis() - it)).roundToInt() } ?: 0
        cancel()
        val motor = vibrator ?: return
        if (amplitude == 0 || Build.VERSION.SDK_INT < 26 || !view.hasWindowFocus()) return
        try {
            motor.vibrate(VibrationEffect.createWaveform(
                longArrayOf(0, 5, 5, 5, 5),
                intArrayOf(0, amplitude, amplitude * 2 / 3, amplitude / 3, 0), -1,
            ), attributes)
        } catch (_: RuntimeException) { /* Optional device capability. */ }
    }
}
