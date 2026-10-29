package io.github.sceneview.demo.demos.internal.room

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * A room's structure as one clean mesh: the floor, each wall and the ceiling flat, the doors and
 * windows cut out of the walls, every face looking into the room. [positions] are `x, y, z` per
 * vertex in the scan's frame, [indices] three per triangle. Vertices are shared wherever faces
 * meet, so the mesh is closed: an edge is used by two triangles, except around a door or a window,
 * whose rim is the hole's edge.
 */
class StructureMesh internal constructor(
    val positions: FloatArray,
    val indices: IntArray,
    private val rim: BooleanArray,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    /** How many triangles each edge is used by, the most of any edge: 2 for a clean mesh. */
    fun maxEdgeUse(): Int = edgeUses().values.maxOrNull() ?: 0

    /** Edges used by a single triangle off the rim of a door or a window: 0 when the mesh is closed. */
    fun leaks(): Int = edgeUses().count { (edge, uses) ->
        uses == 1 && !(rim[(edge ushr EDGE_BITS).toInt()] && rim[(edge and EDGE_MASK).toInt()])
    }

    /** `true` when the mesh holds the room shut: no edge used more than twice, none left open off a hole's rim. */
    val watertight: Boolean get() = maxEdgeUse() <= 2 && leaks() == 0

    private fun edgeUses(): Map<Long, Int> {
        val uses = HashMap<Long, Int>()
        for (t in 0 until triangleCount) {
            for (e in 0 until 3) {
                val a = indices[t * 3 + e]
                val b = indices[t * 3 + (e + 1) % 3]
                val key = (minOf(a, b).toLong() shl EDGE_BITS) or maxOf(a, b).toLong()
                uses[key] = (uses[key] ?: 0) + 1
            }
        }
        return uses
    }

    companion object {
        private const val EDGE_BITS = 32
        private const val EDGE_MASK = 0xffffffffL
        private const val SAME_M = 1e-3f
        private const val QUANTUM = 1e4f

        /** The mesh of [structure]; empty when it has no walls. */
        @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
        fun of(structure: RoomStructure): StructureMesh {
            val walls = structure.walls
            if (walls.size < 3) return StructureMesh(FloatArray(0), IntArray(0), BooleanArray(0))
            val height = structure.ceilingHeight?.value
                ?: walls.map { it.height }.filter { it > 0f }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
            if (height <= 0f) return StructureMesh(FloatArray(0), IntArray(0), BooleanArray(0))
            val y0 = structure.floorY
            val positions = ArrayList<Float>()
            val rims = ArrayList<Boolean>()
            val indices = ArrayList<Int>()
            val byKey = HashMap<Triple<Long, Long, Long>, Int>()
            fun vertex(x: Float, y: Float, z: Float, rim: Boolean): Int {
                val key = Triple((x * QUANTUM).roundToLong(), (y * QUANTUM).roundToLong(), (z * QUANTUM).roundToLong())
                val known = byKey[key]
                if (known != null) {
                    if (rim) rims[known] = true
                    return known
                }
                byKey[key] = rims.size
                positions += x
                positions += y
                positions += z
                rims += rim
                return rims.size - 1
            }
            fun triangle(a: Int, b: Int, c: Int, nx: Float, ny: Float, nz: Float) {
                val ax = positions[a * 3]
                val ay = positions[a * 3 + 1]
                val az = positions[a * 3 + 2]
                val ux = positions[b * 3] - ax
                val uy = positions[b * 3 + 1] - ay
                val uz = positions[b * 3 + 2] - az
                val vx = positions[c * 3] - ax
                val vy = positions[c * 3 + 1] - ay
                val vz = positions[c * 3 + 2] - az
                val cx = uy * vz - uz * vy
                val cy = uz * vx - ux * vz
                val cz = ux * vy - uy * vx
                indices += a
                if (cx * nx + cy * ny + cz * nz >= 0f) {
                    indices += b
                    indices += c
                } else {
                    indices += c
                    indices += b
                }
            }
            // Heights every wall is cut at, so that walls meet on the same vertices at each corner.
            val levels = sortedSet(
                listOf(0f, height) + walls.flatMap { w -> w.openings.flatMap { listOf(it.sill, it.sill + it.height) } },
                0f,
                height,
            )
            val breaks = walls.map { w ->
                val length = hypot(w.bx - w.ax, w.bz - w.az)
                sortedSet(listOf(0f, length) + w.openings.flatMap { listOf(it.from, it.from + it.width) }, 0f, length)
            }
            fun at(w: RoomWall, length: Float, t: Float): FloatArray = when {
                t <= 0f -> floatArrayOf(w.ax, w.az)
                t >= length -> floatArrayOf(w.bx, w.bz)
                else -> floatArrayOf(w.ax + (w.bx - w.ax) * t / length, w.az + (w.bz - w.az) * t / length)
            }
            fun onRim(w: RoomWall, t: Float, h: Float): Boolean = w.openings.any { o ->
                val inT = t >= o.from - SAME_M && t <= o.from + o.width + SAME_M
                val inH = h >= o.sill - SAME_M && h <= o.sill + o.height + SAME_M
                val onT = abs(t - o.from) < SAME_M || abs(t - o.from - o.width) < SAME_M
                val onH = abs(h - o.sill) < SAME_M || abs(h - o.sill - o.height) < SAME_M
                (onT && inH) || (onH && inT)
            }
            for ((i, w) in walls.withIndex()) {
                val length = hypot(w.bx - w.ax, w.bz - w.az)
                val ts = breaks[i]
                for (k in 0 until ts.size - 1) {
                    for (m in 0 until levels.size - 1) {
                        val tc = (ts[k] + ts[k + 1]) / 2
                        val hc = (levels[m] + levels[m + 1]) / 2
                        val cut = w.openings.any { o ->
                            tc > o.from && tc < o.from + o.width && hc > o.sill && hc < o.sill + o.height
                        }
                        if (cut) continue
                        val p0 = at(w, length, ts[k])
                        val p1 = at(w, length, ts[k + 1])
                        val a = vertex(p0[0], y0 + levels[m], p0[1], onRim(w, ts[k], levels[m]))
                        val b = vertex(p1[0], y0 + levels[m], p1[1], onRim(w, ts[k + 1], levels[m]))
                        val c = vertex(p1[0], y0 + levels[m + 1], p1[1], onRim(w, ts[k + 1], levels[m + 1]))
                        val d = vertex(p0[0], y0 + levels[m + 1], p0[1], onRim(w, ts[k], levels[m + 1]))
                        triangle(a, b, c, w.nx, 0f, w.nz)
                        triangle(a, c, d, w.nx, 0f, w.nz)
                    }
                }
            }
            // The floor and the ceiling: the outline, cut where the walls are, each a fan of ears.
            val outlineX = ArrayList<Float>()
            val outlineZ = ArrayList<Float>()
            val outlineRim = ArrayList<Boolean>()
            for ((i, w) in walls.withIndex()) {
                val length = hypot(w.bx - w.ax, w.bz - w.az)
                for (t in breaks[i].dropLast(1)) {
                    val p = at(w, length, t)
                    outlineX += p[0]
                    outlineZ += p[1]
                    outlineRim += onRim(w, t, 0f)
                }
            }
            val ears = Ears.of(outlineX.toFloatArray(), outlineZ.toFloatArray())
            for (level in 0..1) {
                val y = y0 + if (level == 0) 0f else height
                val ny = if (level == 0) 1f else -1f
                // On the floor a door's corners are on its rim; no hole reaches the ceiling.
                val ids = IntArray(outlineX.size) { k -> vertex(outlineX[k], y, outlineZ[k], level == 0 && outlineRim[k]) }
                for (e in 0 until ears.size / 3) {
                    triangle(ids[ears[e * 3]], ids[ears[e * 3 + 1]], ids[ears[e * 3 + 2]], 0f, ny, 0f)
                }
            }
            return StructureMesh(positions.toFloatArray(), indices.toIntArray(), rims.toBooleanArray())
        }

        /** [values] within [lo]..[hi], sorted, those closer than a millimetre to the one before dropped. */
        private fun sortedSet(values: List<Float>, lo: Float, hi: Float): FloatArray {
            val sorted = values.map { it.coerceIn(lo, hi) }.sorted()
            val out = ArrayList<Float>()
            for (v in sorted) if (out.isEmpty() || v - out.last() > SAME_M) out += v
            if (out.size >= 2 && hi - out.last() <= SAME_M) out[out.size - 1] = hi
            return out.toFloatArray()
        }
    }
}

/** Ear clipping of a simple polygon: three corner indices per triangle. */
internal object Ears {
    private const val FLAT = 1e-6

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    fun of(x: FloatArray, z: FloatArray): IntArray {
        val n = x.size
        if (n < 3) return IntArray(0)
        var area = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += x[i].toDouble() * z[j] - x[j].toDouble() * z[i]
        }
        val sign = if (area >= 0) 1.0 else -1.0
        val left = ArrayList((0 until n).toList())
        val out = ArrayList<Int>()
        fun cross(a: Int, b: Int, c: Int): Double =
            sign * ((x[b] - x[a]).toDouble() * (z[c] - z[a]) - (z[b] - z[a]).toDouble() * (x[c] - x[a]))
        while (left.size > 3) {
            var clipped = -1
            for (k in left.indices) {
                val a = left[(k + left.size - 1) % left.size]
                val b = left[k]
                val c = left[(k + 1) % left.size]
                if (cross(a, b, c) <= FLAT) continue
                val blocked = left.any { p ->
                    p != a && p != b && p != c && cross(a, b, p) >= -FLAT && cross(b, c, p) >= -FLAT && cross(c, a, p) >= -FLAT
                }
                if (!blocked) {
                    clipped = k
                    break
                }
            }
            if (clipped < 0) {
                // No clean ear on a polygon not quite simple: the flattest-safe corner.
                clipped = left.indices.maxBy { k ->
                    cross(left[(k + left.size - 1) % left.size], left[k], left[(k + 1) % left.size])
                }
            }
            out += left[(clipped + left.size - 1) % left.size]
            out += left[clipped]
            out += left[(clipped + 1) % left.size]
            left.removeAt(clipped)
        }
        out += left
        return out.toIntArray()
    }
}
