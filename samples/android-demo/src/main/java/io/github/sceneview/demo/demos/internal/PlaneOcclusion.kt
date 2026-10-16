package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * What stands between a scan's photo and a plane: the sofa in front of the wall, the table over
 * the floor. Without it a plane is painted with whatever the photo saw along the ray — furniture
 * smeared over the floor and the walls (#4540). Pure Kotlin: the scan's dense cloud and its
 * planes in, a coarse depth picture per photo out.
 */

/**
 * The scan's geometry, as seen from its photos: [points] the dense cloud's flat world xyz
 * (`DenseCloud.positions`, empty on a sparse-tier scan), each standing for a [voxelM] cube, and
 * the scan's [planes]. Single-threaded, like the bake it serves.
 */
class BakeOcclusion(
    private val points: FloatArray,
    planes: List<DebugPlane>,
    private val voxelM: Float = DenseFusion.VOXEL_M,
) {
    private val slabs = planes.mapNotNull(PlaneSlab::of)

    private val views = object : LinkedHashMap<DebugPose, OcclusionView>(CACHE_VIEWS, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DebugPose, OcclusionView>?) =
            size > CACHE_VIEWS
    }

    /** `true` when there is nothing to hide anything: no point and no plane. */
    val isEmpty: Boolean get() = points.isEmpty() && slabs.isEmpty()

    /** The depth picture of the camera at [pose] through [lens], built once and kept for a while. */
    fun viewOf(pose: DebugPose, lens: ReplayLens): OcclusionView =
        views.getOrPut(pose) { OcclusionView.build(pose, lens, points, voxelM, slabs) }

    companion object {
        /** Photos whose depth picture is kept: about one plane's worth, shared by its neighbours. */
        const val CACHE_VIEWS = 48
        private const val LOAD_FACTOR = 0.75f

        /** The occlusion of a scan with [dense] (or none) and [planes]; `null` when it has neither. */
        fun of(dense: DenseCloud?, planes: List<DebugPlane>): BakeOcclusion? =
            BakeOcclusion(dense?.positions ?: FloatArray(0), planes).takeUnless { it.isEmpty }
    }
}

/**
 * One photo's depth picture on a [COLUMNS] × rows grid over its normalised image (`x`, `y` in
 * [-1, 1], `y` up): per cell, the nearest depth along the view axis of a dense point, and the
 * nearest and second-nearest plane, so a plane can ask what stands in front of it but itself.
 */
class OcclusionView private constructor(
    private val rows: Int,
    private val pointDepth: FloatArray,
    private val planeDepth: FloatArray,
    private val planeId: IntArray,
    private val secondDepth: FloatArray,
) {
    /**
     * The nearest depth at normalised ([x], [y]) of anything but the plane [exclude]:
     * [Float.POSITIVE_INFINITY] where the photo saw nothing the scan knows of.
     */
    fun depthAt(x: Float, y: Float, exclude: Int): Float {
        val col = ((x + 1f) / 2f * COLUMNS).toInt().coerceIn(0, COLUMNS - 1)
        val row = ((1f - y) / 2f * rows).toInt().coerceIn(0, rows - 1)
        val i = row * COLUMNS + col
        val plane = if (planeId[i] == exclude) secondDepth[i] else planeDepth[i]
        return min(pointDepth[i], plane)
    }

    companion object {
        /** Grid columns: a cell is ~2 cm at 3 m, the dense cloud's own voxel. */
        const val COLUMNS = 128

        /** A dense point covers at least this many cells around its own, at most [MAX_SPLAT]. */
        private const val SPLAT_SCALE = 1.5f
        private const val MAX_SPLAT = 4

        /** Nothing nearer the lens than this hides anything. */
        private const val NEAR_M = 0.05f

        private const val NO_PLANE = Int.MIN_VALUE

        internal fun build(
            pose: DebugPose,
            lens: ReplayLens,
            points: FloatArray,
            voxelM: Float,
            slabs: List<PlaneSlab>,
        ): OcclusionView {
            val rows = max(1, (COLUMNS * lens.halfHeightPerDepth / lens.halfWidthPerDepth + 0.5f).toInt())
            val view = OcclusionView(
                rows,
                FloatArray(COLUMNS * rows) { Float.POSITIVE_INFINITY },
                FloatArray(COLUMNS * rows) { Float.POSITIVE_INFINITY },
                IntArray(COLUMNS * rows) { NO_PLANE },
                FloatArray(COLUMNS * rows) { Float.POSITIVE_INFINITY },
            )
            val inverse = DebugPose(0f, 0f, 0f, -pose.qx, -pose.qy, -pose.qz, pose.qw)
            view.splat(pose, inverse, lens, points, voxelM)
            if (slabs.isNotEmpty()) view.cast(pose, lens, slabs)
            return view
        }
    }

    /** Every dense point, drawn as the square of cells its voxel covers, nearest wins. */
    private fun splat(pose: DebugPose, inverse: DebugPose, lens: ReplayLens, points: FloatArray, voxelM: Float) {
        val cellPerDepth = 2f * lens.halfWidthPerDepth / COLUMNS
        for (i in 0 until points.size / 3) {
            val local = inverse.rotate(points[i * 3] - pose.x, points[i * 3 + 1] - pose.y, points[i * 3 + 2] - pose.z)
            val depth = -local.z
            if (depth < NEAR_M) continue
            val x = local.x / depth / lens.halfWidthPerDepth
            val y = local.y / depth / lens.halfHeightPerDepth
            val col = ((x + 1f) / 2f * COLUMNS).toInt()
            val row = ((1f - y) / 2f * rows).toInt()
            val radius = ceil(SPLAT_SCALE * voxelM / 2f / (cellPerDepth * depth)).toInt().coerceIn(1, MAX_SPLAT)
            fill(col, row, radius, depth)
        }
    }

    /** The cells within [radius] of ([col], [row]) that saw nothing nearer than [depth] now see it. */
    private fun fill(col: Int, row: Int, radius: Int, depth: Float) {
        // Empty ranges when the square falls outside the grid: nothing is drawn.
        for (r in max(0, row - radius)..min(rows - 1, row + radius)) {
            for (c in max(0, col - radius)..min(COLUMNS - 1, col + radius)) {
                val k = r * COLUMNS + c
                if (depth < pointDepth[k]) pointDepth[k] = depth
            }
        }
    }

    /** Each cell's ray, met against every plane's polygon: nearest and second nearest kept. */
    private fun cast(pose: DebugPose, lens: ReplayLens, slabs: List<PlaneSlab>) {
        for (row in 0 until rows) {
            val y = 1f - (row + 0.5f) / rows * 2f
            for (col in 0 until COLUMNS) {
                val x = (col + 0.5f) / COLUMNS * 2f - 1f
                // The ray at unit view depth: its parameter is the depth along the view axis.
                val ray = pose.rotate(x * lens.halfWidthPerDepth, y * lens.halfHeightPerDepth, -1f)
                val k = row * COLUMNS + col
                for (slab in slabs) {
                    val depth = slab.hit(pose.position, ray)
                    if (depth < NEAR_M) continue
                    keep(k, depth, slab.id)
                }
            }
        }
    }

    private fun keep(k: Int, depth: Float, id: Int) {
        if (depth < planeDepth[k]) {
            if (planeId[k] != id) secondDepth[k] = planeDepth[k]
            planeDepth[k] = depth
            planeId[k] = id
        } else if (id != planeId[k] && depth < secondDepth[k]) {
            secondDepth[k] = depth
        }
    }
}

/** A plane's polygon in its own 2D basis, for ray hits. */
internal class PlaneSlab private constructor(
    val id: Int,
    private val origin: Vec3,
    private val normal: Vec3,
    private val a: Vec3,
    private val b: Vec3,
    private val outline: FloatArray,
) {
    /** The ray parameter where `from + ray · t` meets the polygon, [Float.NEGATIVE_INFINITY] if it misses. */
    fun hit(from: Vec3, ray: Vec3): Float {
        val facing = ray.dot(normal)
        if (abs(facing) < PARALLEL) return Float.NEGATIVE_INFINITY
        val t = (origin - from).dot(normal) / facing
        if (t <= 0f) return Float.NEGATIVE_INFINITY
        val d = from + ray * t - origin
        return if (contains(d.dot(a), d.dot(b))) t else Float.NEGATIVE_INFINITY
    }

    /** Even-odd test of (`u`, `v`) against [outline]. */
    private fun contains(u: Float, v: Float): Boolean {
        val n = outline.size / 2
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val ui = outline[i * 2]
            val vi = outline[i * 2 + 1]
            val uj = outline[j * 2]
            val vj = outline[j * 2 + 1]
            if ((vi > v) != (vj > v) && u < (uj - ui) * (v - vi) / (vj - vi) + ui) inside = !inside
            j = i
        }
        return inside
    }

    companion object {
        private const val PARALLEL = 1e-5f

        fun of(plane: DebugPlane): PlaneSlab? {
            val rect = PlaneBake.rectOf(plane) ?: return null
            val a = rect.u.normalized()
            val b = rect.v.normalized()
            val p = plane.polygon
            val outline = FloatArray(p.size / 3 * 2)
            for (i in 0 until p.size / 3) {
                val d = Vec3(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]) - rect.origin
                outline[i * 2] = d.dot(a)
                outline[i * 2 + 1] = d.dot(b)
            }
            return PlaneSlab(plane.id, rect.origin, rect.normal, a, b, outline)
        }
    }
}
