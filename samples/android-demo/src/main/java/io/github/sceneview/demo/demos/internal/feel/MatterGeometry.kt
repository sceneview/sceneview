package io.github.sceneview.demo.demos.internal.feel

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Rest-space lattice and solid share one displacement field; the supported bottom stays fixed. */
internal class MatterGeometry(val rest: FloatArray, val indices: ShortArray) {
    val positions = rest.copyOf()

    fun deform(depthMm: Float, fingerX: Float, fingerZ: Float, radius: Float) {
        for (i in rest.indices step 3) {
            val x = rest[i]
            val y = rest[i + 1]
            val z = rest[i + 2]
            val weight = (1f + y / FeelSimulation.PAD_THICKNESS).coerceIn(0f, 1f)
            val dent = depthMm * FeelMaterial.WORLD_PER_MM * well(x - fingerX, z - fingerZ, radius) * weight
            positions[i] = x
            positions[i + 1] = y - dent
            positions[i + 2] = z
        }
    }

    companion object {
        /** Normalized, finite Plummer well, so the centre agrees with the simulated millimetres. */
        fun well(x: Float, z: Float, radius: Float): Float {
            val r2 = (x * x + z * z) / (radius * radius)
            val edge = 1f / sqrt(37f)
            return ((1f / sqrt(1f + r2) - edge) / (1f - edge)).coerceIn(0f, 1f)
        }
    }
}

/** Built once. Triangle ribbons give the lattice a stable width on mobile GLES. */
internal object MatterMeshes {
    private const val COLUMNS = 64
    private const val ROWS = 24
    private const val LINE = 0.008f
    private const val LIFT = 0.003f
    private const val SEGMENTS = 64
    private val w get() = FeelSimulation.PAD_HALF_WIDTH
    private val l get() = FeelSimulation.PAD_HALF_LENGTH
    private val h get() = FeelSimulation.PAD_THICKNESS

    fun top(): MatterGeometry = Builder().apply {
        patch(COLUMNS, ROWS) { u, v -> floatArrayOf(-w + 2f * w * u, 0f, l - 2f * l * v) }
    }.build()

    fun sides(): MatterGeometry = Builder().apply {
        // Outward winding on all four faces. Every layer follows the top's local compression.
        patch(COLUMNS, 8) { u, v -> floatArrayOf(-w + 2f * w * u, -h + h * v, l) }
        patch(COLUMNS, 8) { u, v -> floatArrayOf(w - 2f * w * u, -h + h * v, -l) }
        patch(ROWS, 8) { u, v -> floatArrayOf(w, -h + h * v, l - 2f * l * u) }
        patch(ROWS, 8) { u, v -> floatArrayOf(-w, -h + h * v, -l + 2f * l * u) }
    }.build()

    fun lattice(): MatterGeometry = Builder().apply {
        for (x in 0..16) {
            val at = -w + 2f * w * x / 16f
            patch(1, ROWS) { u, v -> floatArrayOf((at + (u - 0.5f) * LINE).coerceIn(-w, w), LIFT, l - 2f * l * v) }
            patch(1, 8) { u, v -> floatArrayOf((at + (u - 0.5f) * LINE).coerceIn(-w, w), -h + h * v, l + LIFT) }
        }
        for (z in 0..6) {
            val at = l - 2f * l * z / 6f
            patch(COLUMNS, 1) { u, v -> floatArrayOf(-w + 2f * w * u, LIFT, (at - (v - 0.5f) * LINE).coerceIn(-l, l)) }
        }
        for (layer in 0..4) {
            val at = -h * layer / 4f
            patch(COLUMNS, 1) { u, v -> floatArrayOf(-w + 2f * w * u, (at + (v - 0.5f) * LINE).coerceIn(-h, 0f), l + LIFT) }
        }
    }.build()

    /** Unit disc/ring; the runtime maps it onto the current surface without allocating a mesh. */
    fun contact(disc: Boolean): MatterGeometry = Builder().apply {
        patch(SEGMENTS, 1) { u, v ->
            val angle = 2f * PI.toFloat() * u
            val radius = if (disc) v else 0.94f + 0.06f * v
            floatArrayOf(cos(angle) * radius, LIFT * 2f, sin(angle) * radius)
        }
    }.build()

    fun updateContact(mesh: MatterGeometry, radius: Float, x: Float, z: Float, depth: Float, material: FeelMaterial) {
        for (i in mesh.rest.indices step 3) {
            val px = (x + mesh.rest[i] * radius).coerceIn(-w, w)
            val pz = (z + mesh.rest[i + 2] * radius).coerceIn(-l, l)
            mesh.positions[i] = px
            mesh.positions[i + 1] = LIFT * 2f - depth * FeelMaterial.WORLD_PER_MM *
                MatterGeometry.well(px - x, pz - z, material.radius)
            mesh.positions[i + 2] = pz
        }
    }

    private class Builder {
        val vertices = mutableListOf<Float>()
        val indices = mutableListOf<Short>()
        fun patch(columns: Int, rows: Int, point: (Float, Float) -> FloatArray) {
            val start = vertices.size / 3
            for (v in 0..rows) for (u in 0..columns) {
                point(u.toFloat() / columns, v.toFloat() / rows).forEach(vertices::add)
            }
            for (v in 0 until rows) for (u in 0 until columns) {
                val a = start + v * (columns + 1) + u
                val b = a + 1
                val c = a + columns + 1
                val d = c + 1
                listOf(a, b, c, b, d, c).forEach { indices += it.toShort() }
            }
        }
        fun build() = MatterGeometry(vertices.toFloatArray(), indices.toShortArray())
    }
}
