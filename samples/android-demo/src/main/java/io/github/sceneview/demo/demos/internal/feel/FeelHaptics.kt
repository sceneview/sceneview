package io.github.sceneview.demo.demos.internal.feel

internal enum class FeelPulseKind { FoamEnvelope, LowTick, Click }
internal data class FeelPulse(val kind: FeelPulseKind, val scale: Float)
internal data class HapticSchedule(
    val material: FeelMaterial? = null,
    val lastPulseMillis: Long? = null,
    val stopSent: Boolean = false,
    val grainTravelMm: Float = 0f,
)
internal data class HapticStep(val schedule: HapticSchedule, val pulse: FeelPulse? = null)

/** Authored foam intensity, linearly interpolated; zero really means silence. */
internal fun foamIntensity(timeMillis: Long): Float {
    val times = longArrayOf(0, 60, 300, 850, 1200, 1400)
    val values = floatArrayOf(0f, 0.08f, 0.14f, 0.20f, 0.12f, 0f)
    if (timeMillis <= 0 || timeMillis >= times.last()) return 0f
    val end = times.indexOfFirst { it >= timeMillis }
    val t = (timeMillis - times[end - 1]).toFloat() / (times[end] - times[end - 1])
    return values[end - 1] + (values[end] - values[end - 1]) * t
}

/** One contact, then silence (or distance-driven leather grain). No platform calls or timers. */
internal fun scheduleHaptic(
    previous: HapticSchedule,
    material: FeelMaterial?,
    pressure: Float,
    nowMillis: Long,
    travelMm: Float = 0f,
): HapticStep {
    val state = if (previous.material == material) previous else previous.copy(
        material = material, stopSent = false, grainTravelMm = travelMm,
    )
    if (material == null) return HapticStep(state)
    val since = state.lastPulseMillis?.let { nowMillis - it } ?: Long.MAX_VALUE
    if (since < 30L) return HapticStep(state)
    val p = if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 0f
    if (state.stopSent) {
        if (material != FeelMaterial.Leather || travelMm - state.grainTravelMm < 6f) return HapticStep(state)
        // Discard crossings during the actuator's 100 ms refractory window: never queue a burst.
        val next = state.copy(grainTravelMm = travelMm)
        if (since < 100L) return HapticStep(next)
        return HapticStep(next.copy(lastPulseMillis = nowMillis), FeelPulse(FeelPulseKind.LowTick, 0.12f + 0.08f * p))
    }
    val pulse = when (material) {
        FeelMaterial.Marble, FeelMaterial.BrushedSteel -> FeelPulse(FeelPulseKind.Click, 0.90f)
        FeelMaterial.Leather -> FeelPulse(FeelPulseKind.Click, 0.45f)
        FeelMaterial.MemoryFoam, FeelMaterial.Gel, FeelMaterial.Velvet -> FeelPulse(FeelPulseKind.FoamEnvelope, 1f)
    }
    return HapticStep(
        state.copy(lastPulseMillis = nowMillis, stopSent = true, grainTravelMm = travelMm),
        pulse,
    )
}
