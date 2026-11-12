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
    private val haptics = FeelHaptics()
    private var travelXmm = 0f
    private var travelZmm = 0f
    private var lastTravelX = 0f
    val contactX = FloatArray(pads.size)
    val contactZ = FloatArray(pads.size)
    val contactMillis = LongArray(pads.size) { Long.MIN_VALUE }
    private var foamReleasedAt: Long? = null
    private var openingLoaded = false
    fun radius(material: FeelMaterial): Float = if (material == FeelMaterial.MemoryFoam &&
        openingLoaded && !hasPressed) MatterOpening.WELL_RADIUS else material.radius

    /** Peak top-surface depth in scene mm. Usually the main well is the exact maximum:
     * the shader's bounded trail union cannot beat it once depth >= 0.22 units.
     * Only a shallow foam drag needs a small top-grid search for a deeper old groove.
     * At 0.10-unit spacing the smooth 0.46-unit trail is resolved well within rounding to mm.
     */
    fun captionDepthMm(material: FeelMaterial): Float {
        val i = material.ordinal
        val depth = MatterSurfaces.visualDepth(pads[i].depthMm, material)
        if (material != FeelMaterial.MemoryFoam || active != material || depth >= .22f) {
            return depth / FeelMaterial.WORLD_PER_MM
        }
        val trail = trails[i]
        var sum = 0f
        for (slot in 0 until TRAIL_POINTS) sum += trail[slot * 4 + 2]
        if (.22f * (1f - exp(-sum)) <= depth) return depth / FeelMaterial.WORLD_PER_MM
        val finish = MatterSurfaces[material]
        var peak = depth
        for (row in 0..28) for (column in 0..48) {
            val x = -PAD_HALF_WIDTH + column * (PAD_HALF_WIDTH * 2f / 48f)
            val z = -PAD_HALF_LENGTH + row * (PAD_HALF_LENGTH * 2f / 28f)
            val dx = x - dentX[i]; val dz = z - dentZ[i]
            val r = hypot(dx, dz) / radius(material)
            val bowl = exp(-2f * r * r) // Foam falloff is exactly 2.
            val gateT = ((r - .20f) / .35f).coerceIn(0f, 1f)
            val gate = gateT * gateT * (3f - 2f * gateT)
            val rimQ = (r - finish.rimRadius) / finish.rimWidth
            var memory = 0f
            for (slot in 0 until TRAIL_POINTS) {
                val tx = x - trail[slot * 4]; val tz = z - trail[slot * 4 + 1]
                memory += trail[slot * 4 + 2] * exp(-(tx * tx + tz * tz) * trail[slot * 4 + 3])
            }
            peak = maxOf(peak, depth * bowl - depth * finish.bulge * exp(-rimQ * rimQ) * gate +
                .22f * (1f - exp(-memory)) * (1f - bowl))
        }
        return peak / FeelMaterial.WORLD_PER_MM
    }

    fun select(material: FeelMaterial, now: Long) {
        opening.cancel()
        release(now, hapticRelease = false)
        selected = material
    }

    /** Only visible dynamics need GPU frames; hidden pads catch up when the scene next ticks. */
    fun needsFrames(material: FeelMaterial, now: Long, motion: Boolean): Boolean {
        if (active != null || !opening.finished) return true
        val i = material.ordinal
        if (pads[i].depthMm != 0f || pads[i].velocity != 0f) return true
        if ((0 until TRAIL_POINTS).any { trails[i][it * 4 + 2] > 0f }) return true
        if (motion && material == FeelMaterial.Gel && gelReleasedAt != Long.MIN_VALUE &&
            now - gelReleasedAt in 0L..(GEL_WAVE_SECONDS * 1000).toLong()) return true
        return motion && material.rigid && contactMillis[i] != Long.MIN_VALUE &&
            now - contactMillis[i] in 0L until MatterMeshes.RIPPLE_MILLIS
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
        travelXmm = 0f
        travelZmm = 0f
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
        haptics.begin(material, pads[material.ordinal])
    }

    fun move(x: Float, z: Float, pressure: Float, size: Float) {
        val material = active ?: return
        // Measure real pointer travel on both axes; reprojection never brushes the material.
        val clampedX = x.coerceIn(-PAD_HALF_WIDTH, PAD_HALF_WIDTH)
        val clampedZ = z.coerceIn(-PAD_HALF_LENGTH, PAD_HALF_LENGTH)
        pathTravel += hypot(clampedX - lastTravelX, clampedZ - lastTravelZ)
        travelXmm += kotlin.math.abs(clampedX - lastTravelX) / FeelMaterial.WORLD_PER_MM
        travelZmm += kotlin.math.abs(clampedZ - lastTravelZ) / FeelMaterial.WORLD_PER_MM
        lastTravelX = clampedX
        lastTravelZ = clampedZ
        fingerX[material.ordinal] = clampedX
        fingerZ[material.ordinal] = clampedZ
        samples = observePressure(samples, pressure, size)
    }

    fun release(now: Long, hapticRelease: Boolean = true) {
        if (hapticRelease) active?.let { haptics.release(pads[it.ordinal], now) } else haptics.cancel()
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
    }

    fun advance(now: Long, startOpening: Boolean = true): FeelEffect? {
        val dt = lastFrameMillis?.let { ((now - it) / 1000f).coerceIn(0f, 0.05f) } ?: 0f
        lastFrameMillis = now
        val previousLoad = opening.loadKg
        val impact = startOpening && opening.advance(now)
        if (opening.loadKg > 0f) openingLoaded = true
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
            if (material == FeelMaterial.MemoryFoam && opening.impactDepthMm > pads[i].depthMm) {
                pads[i] = Indentation(opening.impactDepthMm)
            }
            pads[material.ordinal] = stepIndentation(
                pads[material.ordinal], material, pressure, dt,
            )
        }
        val effect = haptics.advance(pads[selected.ordinal], filteredPressure,
            travelXmm, travelZmm, dt, now)
        travelXmm = 0f; travelZmm = 0f
        return if (impact) FeelEffect(FeelPrimitive.Thud, 1f) else effect
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
