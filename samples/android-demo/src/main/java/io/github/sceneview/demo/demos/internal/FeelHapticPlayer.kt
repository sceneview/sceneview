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
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.demos.internal.feel.FeelEffect
import io.github.sceneview.demo.demos.internal.feel.FeelPrimitive
import io.github.sceneview.demo.demos.internal.feel.waveform

/** Android boundary. Never queues work: one composition/waveform, or one View feedback, in flight. */
internal class FeelHapticPlayer(private val view: View) {
    private val vibrator = view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val supported = mutableMapOf<Int, Boolean>()
    private val durations = mutableMapOf<Int, Int>()
    private var busyUntil = 0L
    private var lastStarted: Long? = null

    private fun allowed(): Boolean = !DemoSettings.qaMode && view.isAttachedToWindow && view.hasWindowFocus() &&
        view.isHapticFeedbackEnabled && Settings.System.getInt(view.context.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    fun play(effect: FeelEffect) {
        if (!allowed()) { cancel(); return }
        val now = SystemClock.uptimeMillis()
        if (now < busyUntil || lastStarted?.let { now - it < 25L } == true) return
        val motor = vibrator
        if (motor != null && motor.hasVibrator() && Build.VERSION.SDK_INT >= 26) {
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    val ids = effect.pulses.map { primitiveId(it.kind) }
                    if (ids.all { id -> id != null && supported.getOrPut(id) {
                            motor.arePrimitivesSupported(id).firstOrNull() == true
                        } }) {
                        val composition = VibrationEffect.startComposition()
                        var duration = 0L
                        for ((index, pulse) in effect.pulses.withIndex()) {
                            val id = ids[index]!!
                            composition.addPrimitive(id, pulse.scale, pulse.pauseMillis)
                            duration += pulse.pauseMillis + if (Build.VERSION.SDK_INT >= 31) {
                                durations.getOrPut(id) { motor.getPrimitiveDurations(id).firstOrNull() ?: 0 }
                                    .takeIf { it > 0 }?.toLong() ?: pulse.kind.durationMillis
                            } else pulse.kind.durationMillis
                        }
                        motor.cancel()
                        motor.vibrate(composition.compose(), attributes)
                        busyUntil = now + maxOf(25L, duration); lastStarted = now
                        return
                    }
                } catch (_: RuntimeException) { /* Try the waveform if a vendor rejects composition. */ }
            }
            try {
                if (motor.hasAmplitudeControl()) {
                    val waveform = effect.waveform()
                    motor.cancel()
                    motor.vibrate(VibrationEffect.createWaveform(waveform.timings, waveform.amplitudes, -1), attributes)
                    busyUntil = now + maxOf(25L, waveform.timings.sum()); lastStarted = now
                    return
                }
            } catch (_: RuntimeException) { /* Missing permission or vendor implementation. */ }
        }
        // No amplitude control: let the platform choose its touch effect rather than a loud buzz.
        val feedback = when (effect.pulses.first().kind) {
            FeelPrimitive.Click -> HapticFeedbackConstants.CONTEXT_CLICK
            FeelPrimitive.Thud, FeelPrimitive.SlowRise, FeelPrimitive.QuickRise -> HapticFeedbackConstants.LONG_PRESS
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
        if (view.performHapticFeedback(feedback)) {
            busyUntil = now + maxOf(50L, effect.durationMillis); lastStarted = now
        }
    }

    private fun primitiveId(kind: FeelPrimitive): Int? = when (kind) {
        FeelPrimitive.Thud -> VibrationEffect.Composition.PRIMITIVE_THUD
        FeelPrimitive.LowTick -> if (Build.VERSION.SDK_INT >= 31) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else null
        FeelPrimitive.Tick -> VibrationEffect.Composition.PRIMITIVE_TICK
        FeelPrimitive.Click -> VibrationEffect.Composition.PRIMITIVE_CLICK
        FeelPrimitive.SlowRise -> VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
        FeelPrimitive.QuickRise -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
    }

    /** Also called while idle, so QA/focus changes stop an envelope already playing. */
    fun onFrame() {
        if (busyUntil == 0L) return
        if (!allowed()) cancel()
        else if (SystemClock.uptimeMillis() >= busyUntil) busyUntil = 0L
    }

    fun cancel() {
        if (busyUntil != 0L) {
            try { vibrator?.cancel() } catch (_: RuntimeException) { /* Optional capability. */ }
        }
        busyUntil = 0L
    }
}
