package io.github.sceneview.demo.demos.internal.feel

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Immutable rest mesh. Tangent frames are packed XYZW quaternions, as required by Filament. */
internal class MatterGeometry(
    val positions: FloatArray,
    val normals: FloatArray,
    val tangents: FloatArray,
    val uv0: FloatArray,
    val indices: ShortArray,
)

/** A rounded box, built once. All six faces meet on identical rounded-edge positions. */
internal object MatterMeshes {
    const val TOP_COLUMNS = 128
    const val TOP_ROWS = 64
    const val BEVEL = FeelSimulation.PAD_THICKNESS * 0.1f
    const val RIPPLE_MILLIS = 350L
    private const val BEVEL_STEPS = 6

    fun slab(): MatterGeometry {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val tangents = ArrayList<Float>()
        val uv = ArrayList<Float>()
        val indices = ArrayList<Short>()
        val half = floatArrayOf(FeelSimulation.PAD_HALF_WIDTH, FeelSimulation.PAD_THICKNESS / 2f,
            FeelSimulation.PAD_HALF_LENGTH)

        // Extra samples in each bevel strip keep even the thin bottom/side roundovers smooth.
        fun coordinates(extent: Float, divisions: Int): List<Float> = buildList {
            for (i in 0 until BEVEL_STEPS) add(-extent + BEVEL * i / BEVEL_STEPS)
            for (i in 0..divisions) add(-extent + BEVEL + 2f * (extent - BEVEL) * i / divisions)
            for (i in 1..BEVEL_STEPS) add(extent - BEVEL + BEVEL * i / BEVEL_STEPS)
        }
        fun face(axis: Int, sign: Float, uAxis: Int, vAxis: Int, columns: Int, rows: Int) {
            val us = coordinates(half[uAxis], columns)
            val vs = coordinates(half[vAxis], rows)
            val start = positions.size / 3
            for (v in vs) for (u in us) {
                val p = FloatArray(3)
                p[axis] = sign * half[axis]; p[uAxis] = u; p[vAxis] = v
                val core = FloatArray(3) { p[it].coerceIn(-half[it] + BEVEL, half[it] - BEVEL) }
                val n = normalize(FloatArray(3) { p[it] - core[it] })
                val rounded = FloatArray(3) { core[it] + BEVEL * n[it] }
                rounded[1] -= half[1] // Top at zero; supported bottom at -thickness.
                positions.addAll(rounded.toList())
                normals.addAll(n.toList())
                val t = normalize(FloatArray(3) { (if (it == uAxis) 1f else 0f) - n[it] * n[uAxis] })
                tangents.addAll(packFrame(t, n).toList())
                uv.add((u / half[uAxis] + 1f) / 2f); uv.add((v / half[vAxis] + 1f) / 2f)
            }
            val u = FloatArray(3).apply { this[uAxis] = 1f }
            val v = FloatArray(3).apply { this[vAxis] = 1f }
            val forward = cross(u, v)[axis] * sign > 0f
            for (row in 0 until vs.lastIndex) for (col in 0 until us.lastIndex) {
                val a = start + row * us.size + col
                val b = a + 1; val c = a + us.size; val d = c + 1
                val tri = if (forward) intArrayOf(a, b, c, b, d, c) else intArrayOf(a, c, b, b, c, d)
                tri.forEach { indices.add(it.toShort()) }
            }
        }
        face(1, 1f, 0, 2, TOP_COLUMNS, TOP_ROWS)
        face(1, -1f, 0, 2, 32, 16)
        for (sign in listOf(-1f, 1f)) {
            face(2, sign, 0, 1, TOP_COLUMNS, 12)
            face(0, sign, 2, 1, TOP_ROWS, 12)
        }
        check(positions.size / 3 < 65536)
        return MatterGeometry(positions.toFloatArray(), normals.toFloatArray(), tangents.toFloatArray(),
            uv.toFloatArray(), indices.toShortArray())
    }

    /** Rounded-box signed distance; same core/radius as slab() and the shaders' rest normal. */
    fun distance(x: Float, y: Float, z: Float): Float {
        val qx = abs(x) - (FeelSimulation.PAD_HALF_WIDTH - BEVEL)
        val qy = abs(y + FeelSimulation.PAD_THICKNESS / 2f) - (FeelSimulation.PAD_THICKNESS / 2f - BEVEL)
        val qz = abs(z) - (FeelSimulation.PAD_HALF_LENGTH - BEVEL)
        return sqrt(max(qx, 0f) * max(qx, 0f) + max(qy, 0f) * max(qy, 0f) +
            max(qz, 0f) * max(qz, 0f)) + minOf(maxOf(qx, qy, qz), 0f) - BEVEL
    }

    private fun normalize(v: FloatArray): FloatArray {
        val length = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(3) { v[it] / length }
    }
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    /** Shoemake matrix → quaternion, equivalent to packTangentFrame for a right-handed frame. */
    private fun packFrame(t: FloatArray, n: FloatArray): FloatArray {
        val b = cross(n, t)
        val m = arrayOf(t, b, n) // columns
        val q = FloatArray(4)
        val trace = m[0][0] + m[1][1] + m[2][2]
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            q[3] = s / 4f
            q[0] = (m[1][2] - m[2][1]) / s
            q[1] = (m[2][0] - m[0][2]) / s
            q[2] = (m[0][1] - m[1][0]) / s
        } else {
            val i = (0..2).maxBy { m[it][it] }
            val j = (i + 1) % 3; val k = (i + 2) % 3
            val s = sqrt(1f + m[i][i] - m[j][j] - m[k][k]) * 2f
            q[i] = s / 4f
            q[j] = (m[j][i] + m[i][j]) / s
            q[k] = (m[k][i] + m[i][k]) / s
            q[3] = (m[j][k] - m[k][j]) / s
        }
        if (q[3] < 0f) for (i in q.indices) q[i] = -q[i]
        // Filament uses sign(w) for handedness. Never encode an exact zero (180° frame).
        val bias = 1f / 32767f
        if (q[3] < bias) {
            val factor = sqrt((1f - bias * bias) / (1f - q[3] * q[3]))
            for (i in 0..2) q[i] *= factor
            q[3] = bias
        }
        return q
    }
}
