package io.github.sceneview.demo.demos.internal.feel

import kotlin.math.exp
import kotlin.math.hypot

/** Main-thread owner of the pure reducers. Each pad keeps recovering after the finger moves away. */
internal class FeelSimulation(openingEnabled: Boolean = false) {
    val opening = MatterOpening(openingEnabled)
    val pads = Array(FeelMaterial.entries.size) { Indentation() }
    val fingerX = FloatArray(pads.size)
    val fingerZ = FloatArray(pads.size)
    val dentX = FloatArray(pads.size)
    val dentZ = FloatArray(pads.size)
    // Fixed storage: x, z, fading strength, inverse squared width. Uploaded without copying.
    val trails = Array(pads.size) { FloatArray(TRAIL_POINTS * 4) }
    private val trailBirth = Array(pads.size) { LongArray(TRAIL_POINTS) { Long.MIN_VALUE } }
    private val trailStrength = Array(pads.size) { FloatArray(TRAIL_POINTS) }
    private val trailNext = IntArray(pads.size)
    private val trailHeadFade = FloatArray(pads.size) { 1f }
    private var pathX = 0f
    private var pathZ = 0f
    private var pathTravel = 0f
    private var lastTravelZ = 0f
    var gelReleasedAt = Long.MIN_VALUE
        private set
    var gelReleaseDepth = 0f
        private set
    var gelReleaseX = 0f
        private set
    var gelReleaseZ = 0f
        private set
    var active: FeelMaterial? = null
        private set
    var hasPressed = false
        private set
    var selected = FeelMaterial.MemoryFoam
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

    fun select(material: FeelMaterial, now: Long) {
        opening.cancel()
        release(now)
        selected = material
    }

    fun begin(material: FeelMaterial, x: Float, z: Float, pressure: Float, size: Float, now: Long) {
        opening.cancel()
        hasPressed = true
        release(now)
        active = material
        selected = material
        downMillis = now
        val i = material.ordinal
        trailStrength[i][trailNext[i]] *= trailHeadFade[i]
        trailHeadFade[i] = 1f
        samples = PressureSamples()
        filteredPressure = 0.12f
        travelMm = 0f
        lastTravelX = x
        lastTravelZ = z
        pathTravel = 0f
        contactMillis[material.ordinal] = now
        if (material == FeelMaterial.MemoryFoam) foamReleasedAt = null
        move(x, z, pressure, size)
        dentX[material.ordinal] = fingerX[material.ordinal]
        dentZ[material.ordinal] = fingerZ[material.ordinal]
        pathX = dentX[material.ordinal]
        pathZ = dentZ[material.ordinal]
        if (material == FeelMaterial.Gel) gelReleasedAt = Long.MIN_VALUE
        contactX[material.ordinal] = fingerX[material.ordinal]
        contactZ[material.ordinal] = fingerZ[material.ordinal]
    }

    fun move(x: Float, z: Float, pressure: Float, size: Float) {
        val material = active ?: return
        // The grain runs along Z. Only real X travel crosses it; reprojection never adds ticks.
        val clampedX = x.coerceIn(-PAD_HALF_WIDTH, PAD_HALF_WIDTH)
        val clampedZ = z.coerceIn(-PAD_HALF_LENGTH, PAD_HALF_LENGTH)
        pathTravel += hypot(clampedX - lastTravelX, clampedZ - lastTravelZ)
        travelMm += kotlin.math.abs(clampedX - lastTravelX) / FeelMaterial.WORLD_PER_MM
        lastTravelX = clampedX
        lastTravelZ = clampedZ
        fingerX[material.ordinal] = clampedX
        fingerZ[material.ordinal] = clampedZ
        samples = observePressure(samples, pressure, size)
    }

    fun release(now: Long) {
        if (active == FeelMaterial.Gel) {
            gelReleasedAt = now
            gelReleaseDepth = MatterSurfaces.visualDepth(pads[1].depthMm, FeelMaterial.Gel).coerceAtLeast(0f)
            gelReleaseX = dentX[1]
            gelReleaseZ = dentZ[1]
        }
        if (active == FeelMaterial.MemoryFoam) {
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
            val i = material.ordinal
            val keepsTrail = material == FeelMaterial.MemoryFoam || material == FeelMaterial.Velvet
            if (material == pressed) {
                val follow = if (material.dragSeconds == 0f) 1f else 1f - exp(-dt / material.dragSeconds)
                dentX[i] += (fingerX[i] - dentX[i]) * follow
                dentZ[i] += (fingerZ[i] - dentZ[i]) * follow
                if (keepsTrail &&
                    pathTravel >= TRAIL_SPACING && hypot(dentX[i] - pathX, dentZ[i] - pathZ) >= TRAIL_SPACING
                ) {
                    val slot = trailNext[i]
                    trails[i][slot * 4] = pathX
                    trails[i][slot * 4 + 1] = pathZ
                    val width = if (material == FeelMaterial.MemoryFoam) .46f else .34f
                    trails[i][slot * 4 + 3] = 1f / (width * width)
                    trailBirth[i][slot] = now
                    val fullDepth = if (material == FeelMaterial.MemoryFoam) .65f else .25f
                    trailStrength[i][slot] = (MatterSurfaces.visualDepth(pads[i].depthMm, material) / fullDepth).coerceIn(0f, 1f)
                    trailNext[i] = (slot + 1) % TRAIL_POINTS
                    pathX = dentX[i]; pathZ = dentZ[i]; pathTravel = 0f
                }
                if (keepsTrail) {
                    // Fade the oldest slot as the next arrives, avoiding a disappearing groove at wrap.
                    val t = (hypot(dentX[i] - pathX, dentZ[i] - pathZ) / TRAIL_SPACING).coerceIn(0f, 1f)
                    trailHeadFade[i] = 1f - t * t * (3f - 2f * t)
                }
            }
            if (keepsTrail) {
                val lifetime = if (material == FeelMaterial.MemoryFoam) 4800L else 3200L
                val halfLife = if (material == FeelMaterial.MemoryFoam) 1150f else 900f
                for (slot in 0 until TRAIL_POINTS) {
                    val born = trailBirth[i][slot]
                    if (born == Long.MIN_VALUE) continue
                    val age = (now - born).coerceAtLeast(0L)
                    val tail = ((lifetime - age) / 500f).coerceIn(0f, 1f)
                    val headFade = if (slot == trailNext[i]) trailHeadFade[i] else 1f
                    trails[i][slot * 4 + 2] = trailStrength[i][slot] *
                        exp(-.6931472f * (age - 140L).coerceAtLeast(0L) / halfLife) * tail * tail * (3f - 2f * tail) *
                        headFade
                    if (age >= lifetime) trailBirth[i][slot] = Long.MIN_VALUE
                }
            }
            val pressure = when {
                material == pressed -> filteredPressure
                material == FeelMaterial.MemoryFoam -> opening.loadKg * 9.81f / FeelMaterial.MAX_FORCE_N
                else -> 0f
            }
            // Short memory plateau before viscous recovery, including after the mark lifts.
            if (material == FeelMaterial.MemoryFoam && pressure == 0f &&
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
        const val TRAIL_POINTS = 8
        const val TRAIL_SPACING = .28f
        const val GEL_WAVE_SECONDS = 2.6f
        const val PAD_HALF_WIDTH = 2.4f
        const val PAD_HALF_LENGTH = 1.45f
        const val PAD_THICKNESS = 0.95f
        fun contains(x: Float, z: Float): Boolean =
            kotlin.math.abs(x) <= PAD_HALF_WIDTH && kotlin.math.abs(z) <= PAD_HALF_LENGTH
    }
}
