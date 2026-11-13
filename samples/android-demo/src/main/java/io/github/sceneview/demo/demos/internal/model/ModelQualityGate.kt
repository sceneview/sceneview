package io.github.sceneview.demo.demos.internal.model

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * What the quality gate compares of a mesh: its [triangles] and [vertices], its whole [area], and
 * its floor's — the faces turned up within [FLOOR_BAND_M] of [floorY], projected onto the floor.
 */
internal data class MeshFigures(
    val triangles: Int,
    val vertices: Int,
    val area: Float,
    val floorArea: Float,
    val floorY: Float,
) {
    val verticesPerTriangle: Float get() = if (triangles > 0) vertices.toFloat() / triangles else Float.POSITIVE_INFINITY

    companion object {
        /** A face this far turned up (its normal's y) is floor, when at the floor's height. */
        const val MIN_UP = 0.9f

        /** How far above or below the floor's height a floor face's centre may be. */
        const val FLOOR_BAND_M = 0.1f

        /** The floor is looked for this far above the lowest upward faces. */
        private const val SEARCH_M = 0.6f
        private const val BIN_M = 0.05f

        /** The share of the upward area under which a face is too low to set where the floor starts (noise). */
        private const val LOW_SHARE = 0.02f

        /**
         * The figures of the triangle soup [positions] / [indices] with [vertices] vertices. The
         * floor is at [floorY] when given — the raw surface's, for the finished model to be
         * measured at the same height — or where most of the upward area lies near the lowest.
         */
        fun of(positions: FloatArray, indices: IntArray, vertices: Int = positions.size / 3, floorY: Float? = null): MeshFigures {
            val count = indices.size / 3
            val up = FloatArray(count)
            val centreY = FloatArray(count)
            val area = FloatArray(count)
            var total = 0.0
            for (t in 0 until count) {
                val a = indices[t * 3] * 3
                val b = indices[t * 3 + 1] * 3
                val c = indices[t * 3 + 2] * 3
                val ux = positions[b] - positions[a]
                val uy = positions[b + 1] - positions[a + 1]
                val uz = positions[b + 2] - positions[a + 2]
                val vx = positions[c] - positions[a]
                val vy = positions[c + 1] - positions[a + 1]
                val vz = positions[c + 2] - positions[a + 2]
                val nx = uy * vz - uz * vy
                val ny = uz * vx - ux * vz
                val nz = ux * vy - uy * vx
                val length = sqrt(nx * nx + ny * ny + nz * nz)
                if (length <= 0f) continue
                area[t] = length / 2f
                total += area[t]
                up[t] = ny / length
                centreY[t] = (positions[a + 1] + positions[b + 1] + positions[c + 1]) / 3f
            }
            val y = floorY ?: floorHeight(up, centreY, area)
            var floorArea = 0.0
            if (!y.isNaN()) {
                for (t in 0 until count) {
                    if (up[t] >= MIN_UP && abs(centreY[t] - y) <= FLOOR_BAND_M) floorArea += area[t] * up[t]
                }
            }
            return MeshFigures(count, vertices, total.toFloat(), floorArea.toFloat(), y)
        }

        /** Where most of the upward area lies, within [SEARCH_M] of the lowest; NaN with none. */
        private fun floorHeight(up: FloatArray, centreY: FloatArray, area: FloatArray): Float {
            val upward = up.indices.filter { up[it] >= MIN_UP && area[it] > 0f }.sortedBy { centreY[it] }
            if (upward.isEmpty()) return Float.NaN
            val upwardArea = upward.sumOf { area[it].toDouble() }
            var below = 0.0
            var low = centreY[upward.first()]
            for (t in upward) {
                below += area[t]
                if (below >= upwardArea * LOW_SHARE) {
                    low = centreY[t]
                    break
                }
            }
            val bins = DoubleArray((SEARCH_M / BIN_M).toInt() + 1)
            for (t in upward) {
                val bin = floor((centreY[t] - low) / BIN_M).toInt()
                if (bin in bins.indices) bins[bin] += area[t].toDouble()
            }
            val peak = bins.indices.maxBy { bins[it] }
            return low + (peak + 0.5f) * BIN_M
        }
    }
}

/**
 * Whether a room's finished model (cleaned, simplified, textured) may take the raw scan's place
 * as its result (Live Room): never when it is worse than the scan. A walk of 2026-11-10 came out
 * as 71,828 vertices for 51,006 triangles, the surface shredded into islands, its sofa doubled.
 *
 * The finished model passes when it holds together ([MAX_VERTICES_PER_TRIANGLE]: a connected
 * surface shares each vertex between about six triangles, 0.5 a triangle; a textured one splits
 * them along its charts' seams, a little more) and keeps about the room the scan had — its whole
 * area and its floor's, at the raw floor's height, within sane ratios of the raw surface's.
 */
internal object ModelQualityGate {
    /** Over this many vertices a triangle, the surface is shredded. */
    const val MAX_VERTICES_PER_TRIANGLE = 1.0f

    /** The finished model's area over the raw surface's: cleaning drops floaters, filling closes holes. */
    const val MIN_AREA_RATIO = 0.6f
    const val MAX_AREA_RATIO = 1.3f

    /** Its floor's area over the raw floor's. */
    const val MIN_FLOOR_RATIO = 0.75f
    const val MAX_FLOOR_RATIO = 1.3f

    enum class Verdict(val passes: Boolean) {
        Passes(true),
        Empty(false),
        Shredded(false),
        AreaLost(false),
        AreaGrown(false),
        FloorLost(false),
        FloorGrown(false),
    }

    /** [finished] judged against the [raw] surface it was made from. */
    fun judge(raw: MeshFigures, finished: MeshFigures): Verdict {
        if (finished.triangles <= 0 || finished.area <= 0f) return Verdict.Empty
        if (finished.verticesPerTriangle > MAX_VERTICES_PER_TRIANGLE) return Verdict.Shredded
        if (raw.area > 0f) {
            val ratio = finished.area / raw.area
            if (ratio < MIN_AREA_RATIO) return Verdict.AreaLost
            if (ratio > MAX_AREA_RATIO) return Verdict.AreaGrown
        }
        if (raw.floorArea > 0f) {
            val ratio = finished.floorArea / raw.floorArea
            if (ratio < MIN_FLOOR_RATIO) return Verdict.FloorLost
            if (ratio > MAX_FLOOR_RATIO) return Verdict.FloorGrown
        }
        return Verdict.Passes
    }
}
