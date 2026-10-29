package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * What the live view says while a part is scanned again (#4736): how much of the part the
 * re-scan has filled, and where the part's frame falls on the screen.
 *
 * Pure arithmetic: [fill] off the main thread, [outline] wherever the eye is read.
 */
object RescanGuidance {
    /** A floor part's cell, metres a side. */
    const val FLOOR_CELL_M = 0.1f

    /** A wall part's cell along the wall, metres. */
    const val WALL_CELL_M = 0.1f

    /** A wall part's cell up the wall, metres. */
    const val WALL_ROW_M = 0.2f

    /** The band of a wall a re-scan must fill, from the floor up, metres: under a table and over a head stay out. */
    const val WALL_LOW_M = 0.2f

    /** …and its top. */
    const val WALL_HIGH_M = 2.0f

    /** How far off a part's surface a point still fills it, metres: a re-scan's own scatter. */
    const val REACH_M = 0.1f

    /** The share of a part's cells with a point in them from which it counts as covered. */
    const val COVERED_SHARE = 0.75f

    /** The nearest a frame's edge is drawn to the eye, metres: what is behind it is cut. */
    const val NEAR_M = 0.05f

    /** Whether [fill] covers a part. */
    fun covered(fill: Float): Boolean = fill >= COVERED_SHARE

    /**
     * The share of [kind]'s part in [box] (`[minX, minY, minZ, maxX, maxY, maxZ]`, the scan's
     * frame) that [cloud], already in the scan's frame, has points on: a floor part's
     * [FLOOR_CELL_M] cells, a wall's [WALL_CELL_M] × [WALL_ROW_M] cells between [WALL_LOW_M] and
     * [WALL_HIGH_M] over its foot. A coarse point fills the cells its surfel spans.
     */
    fun fill(kind: RescanKind, box: FloatArray, cloud: DenseCloud, voxelM: Float = DenseFusion.VOXEL_M): Float =
        if (kind == RescanKind.MissingWall) wallFill(box, cloud, voxelM) else floorFill(box, cloud, voxelM)

    /**
     * The 12 edges of the box whose [corners] (8 × xyz, [RescanPose.corners]' order) stand in the
     * world of an eye at [cameraToWorld] seen through [projection] (both column-major 4×4), on a
     * [width] × [height] view: `x0, y0, x1, y1` per edge drawn, in pixels, y down. An edge behind
     * the eye is left out; one that crosses it is cut at [NEAR_M].
     */
    fun outline(corners: FloatArray, cameraToWorld: FloatArray, projection: FloatArray, width: Float, height: Float): FloatArray {
        val eye = FloatArray(corners.size)
        for (k in 0 until corners.size / 3) toEye(cameraToWorld, corners, k * 3, eye)
        val out = ArrayList<Float>(EDGES.size * 2)
        val a = FloatArray(3)
        val b = FloatArray(3)
        for (e in EDGES.indices step 2) {
            eye.copyInto(a, 0, EDGES[e] * 3, EDGES[e] * 3 + 3)
            eye.copyInto(b, 0, EDGES[e + 1] * 3, EDGES[e + 1] * 3 + 3)
            // The eye looks down -z: a point with z > -NEAR is behind the cut.
            val aIn = a[2] <= -NEAR_M
            val bIn = b[2] <= -NEAR_M
            if (!aIn && !bIn) continue
            if (!aIn) cut(b, a) else if (!bIn) cut(a, b)
            val pa = screen(projection, a, width, height) ?: continue
            val pb = screen(projection, b, width, height) ?: continue
            out += pa.first
            out += pa.second
            out += pb.first
            out += pb.second
        }
        return out.toFloatArray()
    }

    private fun floorFill(box: FloatArray, cloud: DenseCloud, voxelM: Float): Float {
        val columns = max(1, ceil((box[3] - box[0]) / FLOOR_CELL_M).toInt())
        val rows = max(1, ceil((box[5] - box[2]) / FLOOR_CELL_M).toInt())
        val seen = BooleanArray(columns * rows)
        for (i in 0 until cloud.count) {
            val y = cloud.positions[i * 3 + 1]
            if (y < box[1] - REACH_M || y > box[4] + REACH_M) continue
            val half = surfelHalf(cloud, i, voxelM)
            val x = cloud.positions[i * 3]
            val z = cloud.positions[i * 3 + 2]
            mark(seen, columns, rows, (x - half - box[0]) / FLOOR_CELL_M, (x + half - box[0]) / FLOOR_CELL_M,
                (z - half - box[2]) / FLOOR_CELL_M, (z + half - box[2]) / FLOOR_CELL_M)
        }
        return seen.count { it }.toFloat() / seen.size
    }

    /**
     * A wall part's box is its foot's bounds grown by [RescanZones.WALL_HALF_M]: the foot runs
     * along one of the box's two diagonals, the one the points lie along.
     */
    private fun wallFill(box: FloatArray, cloud: DenseCloud, voxelM: Float): Float {
        val inset = RescanZones.WALL_HALF_M
        val x0 = box[0] + inset
        val x1 = box[3] - inset
        val z0 = box[2] + inset
        val z1 = box[5] - inset
        val low = box[1] + WALL_LOW_M
        val high = min(box[4], box[1] + WALL_HIGH_M)
        if (high <= low) return floorFill(box, cloud, voxelM)
        // The two feet the box may hold: rising and falling diagonals.
        val feet = arrayOf(floatArrayOf(x0, z0, x1, z1), floatArrayOf(x0, z1, x1, z0))
        val near = IntArray(2)
        for (i in 0 until cloud.count) {
            val y = cloud.positions[i * 3 + 1]
            if (y < low || y > high) continue
            for (f in 0..1) if (offFoot(feet[f], cloud.positions[i * 3], cloud.positions[i * 3 + 2]) <= REACH_M) near[f]++
        }
        val foot = if (near[1] > near[0]) feet[1] else feet[0]
        val length = max(sqrt((foot[2] - foot[0]) * (foot[2] - foot[0]) + (foot[3] - foot[1]) * (foot[3] - foot[1])), WALL_CELL_M)
        val ux = (foot[2] - foot[0]) / length
        val uz = (foot[3] - foot[1]) / length
        val columns = max(1, ceil(length / WALL_CELL_M).toInt())
        val rows = max(1, ceil((high - low) / WALL_ROW_M).toInt())
        val seen = BooleanArray(columns * rows)
        for (i in 0 until cloud.count) {
            val x = cloud.positions[i * 3]
            val y = cloud.positions[i * 3 + 1]
            val z = cloud.positions[i * 3 + 2]
            if (y < low - REACH_M || y > high + REACH_M) continue
            if (offFoot(foot, x, z) > REACH_M) continue
            val half = surfelHalf(cloud, i, voxelM)
            val along = (x - foot[0]) * ux + (z - foot[1]) * uz
            mark(seen, columns, rows, (along - half) / WALL_CELL_M, (along + half) / WALL_CELL_M,
                (y - half - low) / WALL_ROW_M, (y + half - low) / WALL_ROW_M)
        }
        return seen.count { it }.toFloat() / seen.size
    }

    /** How far ([x], [z]) lies off the segment [foot] (`x0, z0, x1, z1`), metres. */
    private fun offFoot(foot: FloatArray, x: Float, z: Float): Float {
        val dx = foot[2] - foot[0]
        val dz = foot[3] - foot[1]
        val length2 = dx * dx + dz * dz
        val t = if (length2 <= 0f) 0f else (((x - foot[0]) * dx + (z - foot[1]) * dz) / length2).coerceIn(0f, 1f)
        val px = foot[0] + t * dx - x
        val pz = foot[1] + t * dz - z
        return sqrt(px * px + pz * pz)
    }

    private fun surfelHalf(cloud: DenseCloud, i: Int, voxelM: Float): Float =
        (1 shl cloud.levelOf(i).coerceAtMost(MAX_LEVEL)) * voxelM / 2f

    /** Marks the cells of [seen] the span `[c0, c1] × [r0, r1]`, in cells, touches. */
    @Suppress("LongParameterList")
    private fun mark(seen: BooleanArray, columns: Int, rows: Int, c0: Float, c1: Float, r0: Float, r1: Float) {
        val ca = max(0, floor(c0).toInt())
        val cb = min(columns - 1, floor(c1).toInt())
        val ra = max(0, floor(r0).toInt())
        val rb = min(rows - 1, floor(r1).toInt())
        for (r in ra..rb) for (c in ca..cb) seen[r * columns + c] = true
    }

    /** [world]'s point at [at], in the eye's frame, written at [at] of [out]. */
    private fun toEye(cameraToWorld: FloatArray, world: FloatArray, at: Int, out: FloatArray) {
        val dx = world[at] - cameraToWorld[12]
        val dy = world[at + 1] - cameraToWorld[13]
        val dz = world[at + 2] - cameraToWorld[14]
        for (axis in 0 until 3) {
            out[at + axis] = cameraToWorld[axis * 4] * dx + cameraToWorld[axis * 4 + 1] * dy + cameraToWorld[axis * 4 + 2] * dz
        }
    }

    /** Moves [outside] along the edge from [inside] onto the near cut. */
    private fun cut(inside: FloatArray, outside: FloatArray) {
        val t = (-NEAR_M - inside[2]) / (outside[2] - inside[2])
        for (axis in 0 until 3) outside[axis] = inside[axis] + t * (outside[axis] - inside[axis])
    }

    /** Where the eye-frame point [p] lands on the view, pixels, y down; `null` if it does not. */
    private fun screen(projection: FloatArray, p: FloatArray, width: Float, height: Float): Pair<Float, Float>? {
        val cx = projection[0] * p[0] + projection[4] * p[1] + projection[8] * p[2] + projection[12]
        val cy = projection[1] * p[0] + projection[5] * p[1] + projection[9] * p[2] + projection[13]
        val cw = projection[3] * p[0] + projection[7] * p[1] + projection[11] * p[2] + projection[15]
        if (abs(cw) < MIN_W || !cx.isFinite() || !cy.isFinite()) return null
        return (cx / cw + 1f) / 2f * width to (1f - cy / cw) / 2f * height
    }

    /** The box's edges: corners one bit apart, [RescanPose.corners]' order. */
    private val EDGES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 0, 2, 1, 3, 4, 6, 5, 7, 0, 4, 1, 5, 2, 6, 3, 7)

    private const val MAX_LEVEL = 3
    private const val MIN_W = 1e-6f
}
