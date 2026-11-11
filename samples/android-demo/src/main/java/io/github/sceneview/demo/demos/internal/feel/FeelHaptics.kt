package io.github.sceneview.demo.demos.internal.feel

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Semantic primitives; Android IDs and device capability queries belong to the player. */
internal enum class FeelPrimitive(val durationMillis: Long) {
    LowTick(12), Tick(8), Click(20), SlowRise(500), QuickRise(150),
}
internal data class FeelPulse(val kind: FeelPrimitive, val scale: Float, val pauseMillis: Int = 0)
internal data class FeelEffect(val pulses: List<FeelPulse>) {
    constructor(kind: FeelPrimitive, scale: Float) : this(listOf(FeelPulse(kind, scale)))
    val durationMillis get() = pulses.sumOf { it.kind.durationMillis + it.pauseMillis }
}
internal data class FeelWaveform(val timings: LongArray, val amplitudes: IntArray)

/** Same per-primitive strengths and pauses when composition is unavailable. No full-strength buzz. */
internal fun FeelEffect.waveform(): FeelWaveform {
    val times = mutableListOf<Long>()
    val amplitudes = mutableListOf<Int>()
    for (pulse in pulses) {
        times += pulse.pauseMillis.toLong(); amplitudes += 0
        val segments = when (pulse.kind) {
            FeelPrimitive.SlowRise -> listOf(80L to .08f, 120L to .25f, 140L to .55f, 100L to 1f, 60L to .35f)
            FeelPrimitive.QuickRise -> listOf(20L to .15f, 30L to .4f, 50L to 1f, 50L to .3f)
            FeelPrimitive.Click -> listOf(6L to 1f, 6L to .5f, 8L to .15f)
            FeelPrimitive.Tick -> listOf(3L to 1f, 5L to .25f)
            FeelPrimitive.LowTick -> listOf(4L to .65f, 4L to 1f, 4L to .25f)
        }
        for ((duration, strength) in segments) {
            times += duration
            amplitudes += (255 * pulse.scale * strength).roundToInt().coerceIn(0, 255)
        }
    }
    return FeelWaveform(times.toLongArray(), amplitudes.toIntArray())
}

/** Pure, frame-driven policy. No Android objects, delayed jobs, or future gel peak guesses. */
internal class FeelHaptics {
    private var material: FeelMaterial? = null
    private var touching = false
    private var contactPending = false
    private var releasedAt: Long? = null
    private var previous = Indentation()
    private var releaseDepth = 0f
    private var peakCount = 0
    private var nextAvailable = Long.MIN_VALUE
    private var lastEmission: Long? = null
    private var grainPhase = 0f

    fun begin(kind: FeelMaterial, state: Indentation) {
        cancel()
        material = kind; touching = true; contactPending = true; previous = state
    }

    fun release(state: Indentation, now: Long) {
        if (!touching) return
        touching = false; contactPending = false; releasedAt = now
        previous = state; releaseDepth = state.depthMm.coerceAtLeast(0f)
        // The player cancels the sinking envelope immediately on lift.
        nextAvailable = now
        grainPhase = 0f
    }

    fun cancel() {
        material = null; touching = false; contactPending = false; releasedAt = null
        grainPhase = 0f; peakCount = 0; nextAvailable = Long.MIN_VALUE
        // Preserve the global 25 ms throttle through cancellation and material switches.
    }

    fun advance(state: Indentation, pressure: Float, dxMm: Float, dzMm: Float,
                elapsedSeconds: Float, now: Long): FeelEffect? {
        val kind = material ?: return null
        val old = previous
        previous = state
        val p = if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 0f
        val effect = if (!touching) {
            when {
                kind == FeelMaterial.MemoryFoam && releasedAt?.let { now - it >= 140L } == true -> {
                    releasedAt = null
                    if (releaseDepth < .2f) null else FeelEffect(FeelPrimitive.LowTick, .035f + .025f * (releaseDepth / 24f).coerceIn(0f, 1f))
                }
                kind == FeelMaterial.Gel && peakCount < 3 && old.velocity < 0f && state.velocity >= 0f &&
                    minOf(old.depthMm, state.depthMm) < -.15f -> {
                    peakCount++
                    FeelEffect(FeelPrimitive.LowTick, (.65f * abs(minOf(old.depthMm, state.depthMm)) / 6.15f).coerceAtMost(.75f))
                }
                else -> null
            }
        } else {
            when (kind) {
                FeelMaterial.MemoryFoam -> if (state.depthMm > .15f && state.velocity > .15f && now >= nextAvailable) {
                    FeelEffect(FeelPrimitive.SlowRise, (.05f + .10f * (state.depthMm / 24f).coerceIn(0f, 1f) +
                        .07f * (state.velocity / 20f).coerceIn(0f, 1f)))
                } else null
                FeelMaterial.Gel -> if (contactPending && state.depthMm > .5f) {
                    contactPending = false
                    FeelEffect(FeelPrimitive.QuickRise, .10f + .08f * p)
                } else null
                FeelMaterial.Marble -> if (contactPending) {
                    contactPending = false; FeelEffect(FeelPrimitive.Click, .90f)
                } else null
                FeelMaterial.BrushedSteel -> if (contactPending) {
                    contactPending = false
                    FeelEffect(listOf(FeelPulse(FeelPrimitive.Click, .85f),
                        FeelPulse(FeelPrimitive.LowTick, .28f, 12), FeelPulse(FeelPrimitive.LowTick, .12f, 12)))
                } else grain(kind, p, dxMm, dzMm, elapsedSeconds)
                FeelMaterial.Leather -> if (contactPending) {
                    contactPending = false; FeelEffect(FeelPrimitive.Tick, .38f + .20f * p)
                } else grain(kind, p, dxMm, dzMm, elapsedSeconds)
                FeelMaterial.Velvet -> grain(kind, p, dxMm, dzMm, elapsedSeconds)
            }
        }
        if (effect == null || now < nextAvailable || lastEmission?.let { now - it < 25L } == true) return null
        lastEmission = now
        nextAvailable = now + maxOf(25L, effect.durationMillis)
        return effect
    }

    private fun grain(kind: FeelMaterial, pressure: Float, dx: Float, dz: Float, dt: Float): FeelEffect? {
        if (!dx.isFinite() || !dz.isFinite() || !dt.isFinite() || dt <= 0f || dt > .05f) {
            grainPhase = 0f; return null
        }
        val travel = hypot(dx, dz)
        if (travel < .0001f) { grainPhase = 0f; return null }
        val across = abs(dz) / travel // Shader brushing runs along X.
        val spacing = when (kind) {
            FeelMaterial.Velvet -> .55f
            FeelMaterial.Leather -> 2.8f
            else -> .7f + 1.5f * across
        }
        val cap = when (kind) {
            FeelMaterial.Velvet -> 40f
            FeelMaterial.Leather -> 18f
            else -> 35f - 13f * across
        }
        grainPhase += minOf(travel / spacing, cap * dt)
        if (grainPhase < 1f) return null
        grainPhase %= 1f // Drop excess crossings; never catch up after a stall.
        val scale = when (kind) {
            FeelMaterial.Velvet -> .035f + .035f * pressure
            FeelMaterial.Leather -> .16f + .10f * pressure
            else -> .065f + .14f * across + .025f * pressure
        }
        return FeelEffect(if (kind == FeelMaterial.Leather) FeelPrimitive.Tick else FeelPrimitive.LowTick, scale)
    }
}
