package io.github.sceneview.demo.demos.internal.feel

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Rest-space lattice and solid share one displacement field; the supported bottom stays fixed. */
internal class MatterGeometry(val rest: FloatArray, val indices: ShortArray, val contactRing: Boolean = false) {
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

    const val RIPPLE_MILLIS = 350L
    fun rippleRadius(age: Long, x: Float = 0f, z: Float = 0f): Float {
        val farX = w + abs(x)
        val farZ = l + abs(z)
        val reach = sqrt(farX * farX + farZ * farZ)
        return 0.08f + (age / RIPPLE_MILLIS.toFloat()).coerceIn(0f, 1f) * (reach - 0.08f)
    }
    fun rippleAlpha(age: Long): Float = (1f - age / RIPPLE_MILLIS.toFloat()).coerceIn(0f, 0.99f)

    /** Six triangles per clipped ring segment; unused slots collapse to a point. */
    fun contact(disc: Boolean): MatterGeometry {
        if (!disc) {
            val count = SEGMENTS * 18
            return MatterGeometry(FloatArray(count * 3), ShortArray(count) { it.toShort() }, contactRing = true)
        }
        return Builder().apply {
            patch(SEGMENTS, 1) { u, v ->
                val angle = 2f * PI.toFloat() * u
                floatArrayOf(cos(angle) * v, LIFT * 2f, sin(angle) * v)
            }
        }.build()
    }

    fun updateContact(
        mesh: MatterGeometry, radius: Float, x: Float, z: Float, depth: Float,
        material: FeelMaterial, strokeWidth: Float = 0.02f,
    ) {
        fun write(vertex: Int, px: Float, pz: Float) {
            mesh.positions[vertex * 3] = px
            mesh.positions[vertex * 3 + 1] = LIFT * 2f - depth * FeelMaterial.WORLD_PER_MM *
                MatterGeometry.well(px - x, pz - z, material.radius)
            mesh.positions[vertex * 3 + 2] = pz
        }
        if (mesh.contactRing) {
            for (segment in 0 until SEGMENTS) {
                fun point(step: Int, outside: Boolean): Pair<Float, Float> {
                    val angle = 2f * PI.toFloat() * step / SEGMENTS
                    // Offset in projected space: the ring stays ~1.5 dp wide even along Z.
                    val half = strokeWidth / 2f * if (outside) 1f else -1f
                    return x + cos(angle) * (radius + half).coerceAtLeast(0f) to
                        z + sin(angle) * (radius + half / MatterStage.SIN).coerceAtLeast(0f)
                }
                var polygon = listOf(point(segment, false), point(segment, true),
                    point(segment + 1, true), point(segment + 1, false))
                // Clip geometry, rather than clamping its vertices into thick arcs at the edge.
                for (edge in 0..3) {
                    fun distance(p: Pair<Float, Float>): Float = when (edge) {
                        0 -> p.first + w
                        1 -> w - p.first
                        2 -> p.second + l
                        else -> l - p.second
                    }
                    val clipped = mutableListOf<Pair<Float, Float>>()
                    if (polygon.isNotEmpty()) {
                        var previous = polygon.last()
                        for (current in polygon) {
                            val before = distance(previous)
                            val after = distance(current)
                            if ((before >= 0f) != (after >= 0f)) {
                                val t = before / (before - after)
                                clipped += (previous.first + t * (current.first - previous.first)).coerceIn(-w, w) to
                                    (previous.second + t * (current.second - previous.second)).coerceIn(-l, l)
                            }
                            if (after >= 0f) clipped += current
                            previous = current
                        }
                    }
                    polygon = clipped
                }
                val start = segment * 18
                for (vertex in 0 until 18) write(start + vertex, 0f, 0f)
                for (triangle in 0 until (polygon.size - 2).coerceAtLeast(0)) {
                    // Clockwise in X/Z gives an upward normal.
                    val points = listOf(polygon[0], polygon[triangle + 2], polygon[triangle + 1])
                    points.forEachIndexed { j, p -> write(start + triangle * 3 + j, p.first, p.second) }
                }
            }
            return
        }
        for (i in mesh.rest.indices step 3) {
            write(i / 3, (x + mesh.rest[i] * radius).coerceIn(-w, w),
                (z + mesh.rest[i + 2] * radius).coerceIn(-l, l))
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
