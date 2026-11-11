package io.github.sceneview.demo.demos.internal.feel

import kotlin.math.abs
import kotlin.math.ceil

/** Illustrative effective springs, not measurements of commercial products. Softest first. */
internal enum class FeelMaterial(
    val stiffness: Float, // N/mm
    val damping: Float, // N s/mm
    val mass: Float, // N s²/mm (effective mass)
    val stopMm: Float,
    val radius: Float, // Well radius in model space, independent of the physical readout.
) {
    MemoryFoam(0.35f, 0.28f, 0.03f, 24f, 1.28f),
    Gel(0.65f, 0.055f, 0.012f, 18f, 0.85f),
    Velvet(1.25f, 0.18f, 0.006f, 6f, 0.65f),
    Leather(3.5f, 0.23f, 0.004f, 2f, 0.58f),
    Marble(160f, 1f, 0.008f, 0f, 0.22f),
    BrushedSteel(240f, 1f, 0.008f, 0f, 0.22f);

    val rigid get() = stopMm == 0f

    companion object {
        const val MAX_FORCE_N = 8f
        const val WORLD_PER_MM = 0.025f
    }
}

internal data class Indentation(val depthMm: Float = 0f, val velocity: Float = 0f) {
    fun atStop(material: FeelMaterial): Boolean =
        material != FeelMaterial.MemoryFoam && depthMm >= material.stopMm - 0.0001f
}

/** Semi-implicit Euler at <= 1/240 s. Gel may cross rest, so release retains its damped wobble. */
internal fun stepIndentation(
    state: Indentation,
    material: FeelMaterial,
    pressure: Float,
    elapsedSeconds: Float,
): Indentation {
    if (material.rigid) return Indentation()
    if (!elapsedSeconds.isFinite() || elapsedSeconds <= 0f) return state
    val elapsed = elapsedSeconds.coerceAtMost(0.05f)
    val steps = ceil(elapsed * 240f).toInt().coerceAtLeast(1)
    val dt = elapsed / steps
    val force = (if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 0f) * FeelMaterial.MAX_FORCE_N
    val lowerStop = if (material == FeelMaterial.Gel) -material.stopMm * 0.35f else 0f
    var d = state.depthMm
    var v = state.velocity
    repeat(steps) {
        v += (force - material.stiffness * d - material.damping * v) / material.mass * dt
        d += v * dt
        if (d <= lowerStop) { d = lowerStop; v = v.coerceAtLeast(0f) }
        if (d >= material.stopMm) { d = material.stopMm; v = v.coerceAtMost(0f) }
    }
    return if (force == 0f && abs(d) < 0.0001f && abs(v) < 0.001f) Indentation() else Indentation(d, v)
}
