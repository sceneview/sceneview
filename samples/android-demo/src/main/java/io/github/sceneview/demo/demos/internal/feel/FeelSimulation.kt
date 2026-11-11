package io.github.sceneview.demo.demos.internal.feel

/** Main-thread owner of the pure reducers. Each pad keeps recovering after the finger moves away. */
internal class FeelSimulation {
    val pads = Array(FeelMaterial.entries.size) { Indentation() }
    val fingerX = FloatArray(pads.size)
    val fingerZ = FloatArray(pads.size)
    var active: FeelMaterial? = null
        private set
    var selected = FeelMaterial.Foam
        private set
    var inferred = InferredPressure(0f, PressureSource.Duration)
        private set
    private var samples = PressureSamples()
    private var downMillis = 0L
    private var lastFrameMillis: Long? = null
    private var filteredPressure = 0f
    private var haptics = HapticSchedule()

    fun begin(material: FeelMaterial, x: Float, z: Float, pressure: Float, size: Float, now: Long) {
        release(now)
        active = material
        selected = material
        downMillis = now
        samples = PressureSamples()
        filteredPressure = 0.12f
        move(x, z, pressure, size)
    }

    fun move(x: Float, z: Float, pressure: Float, size: Float) {
        val material = active ?: return
        fingerX[material.ordinal] = x.coerceIn(-PAD_HALF_WIDTH, PAD_HALF_WIDTH)
        fingerZ[material.ordinal] = z.coerceIn(-PAD_HALF_LENGTH, PAD_HALF_LENGTH)
        samples = observePressure(samples, pressure, size)
    }

    fun release(now: Long) {
        active = null
        filteredPressure = 0f
        inferred = InferredPressure(0f, PressureSource.Duration)
        haptics = scheduleHaptic(haptics, null, Indentation(), 0f, now).schedule
    }

    fun advance(now: Long): FeelPulse? {
        val dt = lastFrameMillis?.let { ((now - it) / 1000f).coerceIn(0f, 0.05f) } ?: 0f
        lastFrameMillis = now
        val pressed = active
        if (pressed != null) {
            inferred = inferPressure(samples, now - downMillis)
            filteredPressure += (inferred.value - filteredPressure) * dt / (0.06f + dt)
        }
        FeelMaterial.entries.forEach { material ->
            pads[material.ordinal] = stepIndentation(
                pads[material.ordinal], material, if (material == pressed) filteredPressure else 0f, dt,
            )
        }
        val step = scheduleHaptic(haptics, pressed, pads[selected.ordinal], filteredPressure, now)
        haptics = step.schedule
        return step.pulse
    }

    companion object {
        const val PAD_HALF_WIDTH = 1.3f
        const val PAD_HALF_LENGTH = 2f
        const val PAD_SPACING = 3f
        fun centerX(material: FeelMaterial): Float = (material.ordinal - 1) * PAD_SPACING
        fun padAt(x: Float, z: Float): FeelMaterial? = FeelMaterial.entries.firstOrNull {
            kotlin.math.abs(x - centerX(it)) <= PAD_HALF_WIDTH && kotlin.math.abs(z) <= PAD_HALF_LENGTH
        }
    }
}
