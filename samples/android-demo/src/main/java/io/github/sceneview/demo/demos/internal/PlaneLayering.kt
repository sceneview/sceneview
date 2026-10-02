package io.github.sceneview.demo.demos.internal

import kotlin.math.abs

/**
 * Where each plane of a Rerun replay is drawn, so no two of them share a depth.
 *
 * The replay's plane photos, and the dollhouse's flat fills, write depth. Two of them in the same
 * plane z-fight: the depth test picks a winner pixel by pixel, and the pick changes as the camera
 * moves — the planes shimmer. Two causes made them share a plane:
 *
 * - **every** upward-facing plane was laid on the floor, a table top or a bed included, right onto
 *   the floor's own photo;
 * - ARCore keeps overlapping planes of one surface apart (two patches of floor, two of a wall),
 *   at the same height to the millimetre.
 *
 * So: only a plane at floor height ([isGround]) is laid on the floor; the others keep their own
 * height. And each plane gets a rank ([RANKS] of them, by id, so a plane keeps its rank from one
 * frame to the next): the floor's patches step down under the grid by [STEP_M] per rank, every
 * other plane steps towards the room — the side the camera walked — by the same step. Two
 * overlapping planes of different ranks are then [STEP_M] apart, which the depth buffer resolves
 * at any distance the replay draws them from.
 *
 * [floorY] is the floor's height, [inside] a point in the room (the walked path's centroid, see
 * [of]), `null` when nothing was walked yet — then a plane steps along its own normal.
 */
class PlaneLayering(
    private val floorY: Float,
    private val inside: Vec3?,
    ids: Collection<Int>,
) {
    private val ranks: Map<Int, Int> =
        ids.distinct().sorted().withIndex().associate { (index, id) -> id to index % RANKS }

    /** The rank of plane [id]: 0 for a plane this layering was not built with. */
    fun rankOf(id: Int): Int = ranks[id] ?: 0

    /** [plane]'s fill: its polygon, moved to its layer. */
    fun fill(plane: DebugPlane): FloatArray = place(plane, lift = 0f)

    /** [plane]'s outline: a hair in front of its own fill, so the two never z-fight either. */
    fun outline(plane: DebugPlane): FloatArray = place(plane, lift = OUTLINE_LIFT_M)

    private fun place(plane: DebugPlane, lift: Float): FloatArray {
        val polygon = plane.polygon
        val out = polygon.copyOf()
        val n = plane.vertexCount
        if (n < 3) return out
        val step = rankOf(plane.id) * STEP_M
        if (isGround(plane, floorY)) {
            val y = floorY - FLOOR_UNDER_GRID_M - step + lift
            for (i in 0 until n) out[i * 3 + 1] = y
            return out
        }
        val normal = ArDebugGeometry.polygonNormal(polygon)
        val c = centroid(polygon)
        val towards = inside?.let { if (normal.dot(it - c) < 0f) -1f else 1f } ?: 1f
        val shift = (step + lift) * towards
        for (i in 0 until n) {
            out[i * 3] += normal.x * shift
            out[i * 3 + 1] += normal.y * shift
            out[i * 3 + 2] += normal.z * shift
        }
        return out
    }

    companion object {
        /** Planes one rank apart are this far apart: 1.5 mm, invisible, resolved by the depth test. */
        const val STEP_M = 0.0015f

        /** Ranks cycle past this many, so the floor's lowest patch sits at most ~1 cm under the grid. */
        const val RANKS = 6

        /** How far the photo floor sits under the grid, so the grid lines stay on top of it. */
        const val FLOOR_UNDER_GRID_M = 0.004f

        /** An outline sits this far in front of its plane's fill. */
        const val OUTLINE_LIFT_M = 0.0008f

        /**
         * An upward-facing plane this close to the floor's height is the floor: ARCore's floor
         * patches differ by a centimetre or two, a coffee table stands 30 cm up.
         */
        const val GROUND_BAND_M = 0.08f

        /** Whether [plane] is a patch of the floor at [floorY], and so is laid flat on it. */
        fun isGround(plane: DebugPlane, floorY: Float): Boolean =
            plane.kind == DebugPlaneKind.Floor && plane.vertexCount >= 3 &&
                abs(meanY(plane.polygon) - floorY) <= GROUND_BAND_M

        /** The layering of [frame]'s planes, over the floor at [floorY], towards its walked path. */
        fun of(frame: ArDebugFrame, floorY: Float): PlaneLayering =
            PlaneLayering(floorY, centroidOrNull(frame.trail), frame.planes.map { it.id })

        fun centroidOrNull(points: FloatArray): Vec3? = if (points.size < 3) null else centroid(points)

        private fun centroid(points: FloatArray): Vec3 {
            val n = points.size / 3
            var x = 0f
            var y = 0f
            var z = 0f
            for (i in 0 until n) {
                x += points[i * 3]; y += points[i * 3 + 1]; z += points[i * 3 + 2]
            }
            return Vec3(x / n, y / n, z / n)
        }

        private fun meanY(polygon: FloatArray): Float {
            val n = polygon.size / 3
            var y = 0f
            for (i in 0 until n) y += polygon[i * 3 + 1]
            return y / n
        }
    }
}
