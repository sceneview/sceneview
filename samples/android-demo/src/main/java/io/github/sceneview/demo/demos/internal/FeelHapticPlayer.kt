package io.github.sceneview.demo.demos.internal

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import io.github.sceneview.demo.demos.internal.feel.FeelPulse
import io.github.sceneview.demo.demos.internal.feel.FeelPulseKind

/** Android boundary only. Timing, strength and one-shot stops live in the pure scheduler. */
internal class FeelHapticPlayer(private val view: View) {
    private val vibrator = view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val supported = mutableMapOf<Int, Boolean>()

    fun play(pulse: FeelPulse) {
        // Vibrator does not consistently apply the View's touch-feedback setting itself.
        if (!view.isAttachedToWindow || !view.hasWindowFocus() || !view.isHapticFeedbackEnabled ||
            Settings.System.getInt(view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0
        ) return
        val motor = vibrator
        if (motor != null && motor.hasVibrator() && Build.VERSION.SDK_INT >= 26) {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val primitive = when (pulse.kind) {
                        FeelPulseKind.LowTick -> if (Build.VERSION.SDK_INT >= 31) {
                            VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                        } else VibrationEffect.Composition.PRIMITIVE_TICK
                        FeelPulseKind.Tick -> VibrationEffect.Composition.PRIMITIVE_TICK
                        FeelPulseKind.Click -> VibrationEffect.Composition.PRIMITIVE_CLICK
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
                    FeelPulseKind.Tick -> 12L
                    FeelPulseKind.Click -> 20L
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
        try { vibrator?.cancel() } catch (_: RuntimeException) { /* Optional device capability. */ }
    }
}
