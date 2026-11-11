package io.github.sceneview.demo.demos.internal.feel

import kotlin.math.ceil

/** Illustrative effective spring parameters, not measurements of commercial materials. */
internal enum class FeelMaterial(
    val label: String,
    val stiffness: Float, // N/mm
    val damping: Float, // N s/mm
    val mass: Float, // N s²/mm (effective mass)
    val stopMm: Float,
    val radius: Float, // Scene units; 1 mm of indentation is WORLD_PER_MM units.
) {
    Foam("Foam", 0.35f, 0.28f, 0.03f, 24f, 0.85f),
    Oak("Oak", 12f, 0.65f, 0.008f, 0.35f, 0.38f),
    Steel("Steel", 240f, 1f, 0.008f, 0f, 0.22f);

    companion object {
        const val MAX_FORCE_N = 8f
        const val WORLD_PER_MM = 0.05f
    }
}

internal data class Indentation(val depthMm: Float = 0f, val velocity: Float = 0f) {
    fun atStop(material: FeelMaterial): Boolean =
        material != FeelMaterial.Foam && depthMm >= material.stopMm - 0.0001f
}

/** Semi-implicit Euler, substepped at <= 1/240 s; a suspended app cannot inject a huge impulse. */
internal fun stepIndentation(
    state: Indentation,
    material: FeelMaterial,
    pressure: Float,
    elapsedSeconds: Float,
): Indentation {
    if (material == FeelMaterial.Steel) return Indentation()
    if (!elapsedSeconds.isFinite() || elapsedSeconds <= 0f) return state
    val elapsed = elapsedSeconds.coerceAtMost(0.05f)
    val steps = ceil(elapsed * 240f).toInt().coerceAtLeast(1)
    val dt = elapsed / steps
    val force = (if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 0f) * FeelMaterial.MAX_FORCE_N
    var d = state.depthMm
    var v = state.velocity
    repeat(steps) {
        v += (force - material.stiffness * d - material.damping * v) / material.mass * dt
        d += v * dt
        if (d <= 0f) { d = 0f; v = v.coerceAtLeast(0f) }
        if (d >= material.stopMm) { d = material.stopMm; v = v.coerceAtMost(0f) }
    }
    return Indentation(d, v)
}
