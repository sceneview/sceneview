package io.github.sceneview.demo.demos.internal.feel

import kotlin.math.abs

internal enum class FeelPulseKind { LowTick, Tick, Click }
internal data class FeelPulse(val kind: FeelPulseKind, val scale: Float)
internal data class HapticSchedule(
    val material: FeelMaterial? = null,
    val lastPulseMillis: Long? = null,
    val stopSent: Boolean = false,
)
internal data class HapticStep(val schedule: HapticSchedule, val pulse: FeelPulse? = null)

/** No timers or platform calls: at most one pulse per 30 ms, including across new presses. */
internal fun scheduleHaptic(
    previous: HapticSchedule,
    material: FeelMaterial?,
    indentation: Indentation,
    pressure: Float,
    nowMillis: Long,
): HapticStep {
    val state = if (previous.material == material) previous else previous.copy(material = material, stopSent = false)
    if (material == null) return HapticStep(state)
    val since = state.lastPulseMillis?.let { nowMillis - it } ?: Long.MAX_VALUE
    if (since < 30L || state.stopSent) return HapticStep(state)
    val speed = (abs(indentation.velocity) / 24f).coerceIn(0f, 1f)
    val p = if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 0f
    val pulse = when (material) {
        FeelMaterial.Steel -> FeelPulse(FeelPulseKind.Click, 1f)
        FeelMaterial.Oak -> if (indentation.atStop(material)) {
            FeelPulse(FeelPulseKind.Click, 0.85f)
        } else {
            if (since < 70L) return HapticStep(state)
            FeelPulse(FeelPulseKind.Tick, 0.15f + 0.35f * p)
        }
        FeelMaterial.Foam -> {
            if (since < (160f - 115f * speed).toLong()) return HapticStep(state)
            FeelPulse(FeelPulseKind.LowTick, 0.08f + 0.18f * p + 0.24f * speed)
        }
    }
    return HapticStep(
        state.copy(lastPulseMillis = nowMillis, stopSent = pulse.kind == FeelPulseKind.Click),
        pulse,
    )
}
