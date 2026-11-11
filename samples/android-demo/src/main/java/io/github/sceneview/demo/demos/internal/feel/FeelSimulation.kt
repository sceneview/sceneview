package io.github.sceneview.demo.demos.internal.feel

/** Main-thread owner of the pure reducers. Each pad keeps recovering after the finger moves away. */
internal class FeelSimulation(openingEnabled: Boolean = false) {
    val opening = MatterOpening(openingEnabled)
    val pads = Array(FeelMaterial.entries.size) { Indentation() }
    val fingerX = FloatArray(pads.size)
    val fingerZ = FloatArray(pads.size)
    var active: FeelMaterial? = null
        private set
    var hasPressed = false
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
    private var travelMm = 0f
    private var lastTravelX = 0f
    val contactX = FloatArray(pads.size)
    val contactZ = FloatArray(pads.size)
    val contactMillis = LongArray(pads.size) { Long.MIN_VALUE }
    private var foamReleasedAt: Long? = null

    fun begin(material: FeelMaterial, x: Float, z: Float, pressure: Float, size: Float, now: Long) {
        opening.cancel()
        hasPressed = true
        release(now)
        active = material
        selected = material
        downMillis = now
        samples = PressureSamples()
        filteredPressure = 0.12f
        travelMm = 0f
        lastTravelX = x
        contactMillis[material.ordinal] = now
        if (material == FeelMaterial.Foam) foamReleasedAt = null
        move(x, z, pressure, size)
        contactX[material.ordinal] = fingerX[material.ordinal]
        contactZ[material.ordinal] = fingerZ[material.ordinal]
    }

    fun move(x: Float, z: Float, pressure: Float, size: Float) {
        val material = active ?: return
        // The grain runs along Z. Only real X travel crosses it; reprojection never adds ticks.
        val clampedX = x.coerceIn(-PAD_HALF_WIDTH, PAD_HALF_WIDTH)
        travelMm += kotlin.math.abs(clampedX - lastTravelX) / FeelMaterial.WORLD_PER_MM
        lastTravelX = clampedX
        fingerX[material.ordinal] = clampedX
        fingerZ[material.ordinal] = z.coerceIn(-PAD_HALF_LENGTH, PAD_HALF_LENGTH)
        samples = observePressure(samples, pressure, size)
    }

    fun release(now: Long) {
        if (active == FeelMaterial.Foam) {
            foamReleasedAt = now
            pads[0] = pads[0].copy(velocity = 0f)
        }
        active = null
        filteredPressure = 0f
        inferred = InferredPressure(0f, PressureSource.Duration)
        haptics = scheduleHaptic(haptics, null, 0f, now).schedule
    }

    fun advance(now: Long, startOpening: Boolean = true): FeelPulse? {
        val dt = lastFrameMillis?.let { ((now - it) / 1000f).coerceIn(0f, 0.05f) } ?: 0f
        lastFrameMillis = now
        val previousLoad = opening.loadKg
        if (startOpening) opening.advance(now)
        if (previousLoad > 0f && opening.loadKg == 0f) {
            foamReleasedAt = now
            pads[0] = pads[0].copy(velocity = 0f)
        }
        val pressed = active
        if (pressed != null) {
            inferred = inferPressure(samples, now - downMillis)
            filteredPressure += (inferred.value - filteredPressure) * dt / (0.06f + dt)
        }
        FeelMaterial.entries.forEach { material ->
            val pressure = when {
                material == pressed -> filteredPressure
                material == FeelMaterial.Foam -> opening.loadKg * 9.81f / FeelMaterial.MAX_FORCE_N
                else -> 0f
            }
            // Short memory plateau before viscous recovery, including after the mark lifts.
            if (material == FeelMaterial.Foam && pressure == 0f &&
                foamReleasedAt?.let { now - it < 140L } == true
            ) return@forEach
            pads[material.ordinal] = stepIndentation(
                pads[material.ordinal], material, pressure, dt,
            )
        }
        val step = scheduleHaptic(haptics, pressed, filteredPressure, now, travelMm)
        haptics = step.schedule
        return step.pulse
    }

    companion object {
        const val PAD_HALF_WIDTH = 2.4f
        const val PAD_HALF_LENGTH = 1.05f
        const val PAD_THICKNESS = 1.25f
        fun contains(x: Float, z: Float): Boolean =
            kotlin.math.abs(x) <= PAD_HALF_WIDTH && kotlin.math.abs(z) <= PAD_HALF_LENGTH
    }
}
