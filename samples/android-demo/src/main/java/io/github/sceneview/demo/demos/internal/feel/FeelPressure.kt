package io.github.sceneview.demo.demos.internal.feel

internal enum class PressureSource { Pressure, ContactSize, Duration }

/** Reset on each DOWN. Only real, positive DOWN/MOVE samples establish sensor variability. */
internal data class PressureSamples(
    val pressureMin: Float = Float.POSITIVE_INFINITY,
    val pressureMax: Float = 0f,
    val sizeMin: Float = Float.POSITIVE_INFINITY,
    val sizeMax: Float = 0f,
    val pressureCount: Int = 0,
    val sizeCount: Int = 0,
    val pressure: Float = 0f,
    val size: Float = 0f,
)

internal fun observePressure(state: PressureSamples, pressure: Float, size: Float): PressureSamples {
    val p = pressure.takeIf { it.isFinite() && it > 0f }
    val s = size.takeIf { it.isFinite() && it > 0f && it <= 1f }
    return state.copy(
        pressureMin = p?.let { minOf(state.pressureMin, it) } ?: state.pressureMin,
        pressureMax = p?.let { maxOf(state.pressureMax, it) } ?: state.pressureMax,
        sizeMin = s?.let { minOf(state.sizeMin, it) } ?: state.sizeMin,
        sizeMax = s?.let { maxOf(state.sizeMax, it) } ?: state.sizeMax,
        pressureCount = state.pressureCount + if (p != null) 1 else 0,
        sizeCount = state.sizeCount + if (s != null) 1 else 0,
        pressure = p ?: 0f,
        size = s ?: 0f,
    )
}

internal data class InferredPressure(val value: Float, val source: PressureSource)

/**
 * Prefer varying pressure, then varying contact size. Constant 1.0/0.0 sensors never qualify.
 * A held finger generates no MOVE events: evaluate the duration fallback on every frame.
 * Three samples plus a minimum span reject quantization noise. Once a range qualifies it
 * remains valid for the gesture, so holding a real sensor steady does not switch to a ramp.
 */
internal fun inferPressure(samples: PressureSamples, heldMillis: Long): InferredPressure = when {
    samples.pressure > 0f && samples.pressureCount >= 3 &&
        samples.pressureMax - samples.pressureMin >= maxOf(0.04f, samples.pressureMin * 0.08f) ->
        InferredPressure(samples.pressure.coerceIn(0f, 1f), PressureSource.Pressure)
    samples.size > 0f && samples.sizeCount >= 3 &&
        samples.sizeMax - samples.sizeMin >= maxOf(0.008f, samples.sizeMin * 0.12f) ->
        InferredPressure(
            (0.12f + 0.88f * (samples.size / samples.sizeMin - 1f) / 0.6f).coerceIn(0f, 1f),
            PressureSource.ContactSize,
        )
    else -> InferredPressure(
        0.12f + 0.88f * (heldMillis.coerceAtLeast(0L) / 1400f).coerceIn(0f, 1f),
        PressureSource.Duration,
    )
}
