package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
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

    private val thickness = HashMap<Int, Float>()

    /**
     * How thick [rect]'s plane (id [planeId]) reads in the dense cloud, toward the side it is seen
     * from: a phone's raw depth smears a floor over a band a hand or more deep, and pose drift
     * across the scan adds to it. The points in that band are the plane itself, not something
     * standing on it, so they hide nothing. Measured from the cloud's points over the plane's
     * polygon: the band runs up from their densest height until they thin out; `0` when too few
     * points lie over it to say.
     */
    fun thicknessOf(planeId: Int, rect: PlaneRect): Float = thickness.getOrPut(planeId) {
        val slab = slabs.firstOrNull { it.id == planeId } ?: return@getOrPut 0f
        PlaneBand.thickness(points, rect.normal, rect.origin, slab)
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
            // The camera's axes in world space: a world offset's view coordinates are its dot
            // products with them, with no allocation per point — the splat runs once per dense
            // point per photo, a hundred thousand times each.
            val axes = CameraAxes(pose.rotate(1f, 0f, 0f), pose.rotate(0f, 1f, 0f), pose.rotate(0f, 0f, 1f))
            view.splat(pose, axes, lens, points, voxelM)
            if (slabs.isNotEmpty()) view.cast(pose, axes, lens, slabs)
            return view
        }
    }

    /** A camera's right ([right]), up ([up]) and backward ([back]) axes, in world space. */
    internal class CameraAxes(val right: Vec3, val up: Vec3, val back: Vec3)

    /** Every dense point, drawn as the square of cells its voxel covers, nearest wins. */
    private fun splat(pose: DebugPose, axes: CameraAxes, lens: ReplayLens, points: FloatArray, voxelM: Float) {
        val cellPerDepth = 2f * lens.halfWidthPerDepth / COLUMNS
        val (rx, ry, rz) = axes.right
        val (ux, uy, uz) = axes.up
        val (bx, by, bz) = axes.back
        val toCol = COLUMNS / (2f * lens.halfWidthPerDepth)
        val toRow = rows / (2f * lens.halfHeightPerDepth)
        for (i in 0 until points.size / 3) {
            val dx = points[i * 3] - pose.x
            val dy = points[i * 3 + 1] - pose.y
            val dz = points[i * 3 + 2] - pose.z
            val depth = -(bx * dx + by * dy + bz * dz)
            if (depth < NEAR_M) continue
            // Cells, floored so a point just off the picture does not land on its edge column.
            val col = floor((rx * dx + ry * dy + rz * dz) / depth * toCol + COLUMNS / 2f).toInt()
            val row = floor(rows / 2f - (ux * dx + uy * dy + uz * dz) / depth * toRow).toInt()
            if (nearGrid(col, row)) {
                val radius = ceil(SPLAT_SCALE * voxelM / 2f / (cellPerDepth * depth)).toInt().coerceIn(1, MAX_SPLAT)
                fill(col, row, radius, depth)
            }
        }
    }

    /** Whether a splat centred on ([col], [row]) can still reach the grid. */
    private fun nearGrid(col: Int, row: Int): Boolean =
        col in -MAX_SPLAT until COLUMNS + MAX_SPLAT && row in -MAX_SPLAT until rows + MAX_SPLAT

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
    private fun cast(pose: DebugPose, axes: CameraAxes, lens: ReplayLens, slabs: List<PlaneSlab>) {
        val (rx, ry, rz) = axes.right
        val (ux, uy, uz) = axes.up
        val (bx, by, bz) = axes.back
        for (row in 0 until rows) {
            val y = (1f - (row + 0.5f) / rows * 2f) * lens.halfHeightPerDepth
            for (col in 0 until COLUMNS) {
                val x = ((col + 0.5f) / COLUMNS * 2f - 1f) * lens.halfWidthPerDepth
                // The ray at unit view depth: its parameter is the depth along the view axis.
                val dx = rx * x + ux * y - bx
                val dy = ry * x + uy * y - by
                val dz = rz * x + uz * y - bz
                val k = row * COLUMNS + col
                for (slab in slabs) {
                    val depth = slab.hit(pose.x, pose.y, pose.z, dx, dy, dz)
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
    /** The outline's `u` and `v` ranges: most rays miss most planes, and miss their bounds first. */
    private val bounds = floatArrayOf(
        outline.filterIndexed { i, _ -> i % 2 == 0 }.min(),
        outline.filterIndexed { i, _ -> i % 2 == 0 }.max(),
        outline.filterIndexed { i, _ -> i % 2 == 1 }.min(),
        outline.filterIndexed { i, _ -> i % 2 == 1 }.max(),
    )

    /**
     * The ray parameter where `from + ray · t` meets the polygon, [Float.NEGATIVE_INFINITY] if it
     * misses. Plain floats: it runs for every cell of every photo against every plane.
     */
    @Suppress("LongParameterList") // a point and a direction, unboxed
    fun hit(fx: Float, fy: Float, fz: Float, rx: Float, ry: Float, rz: Float): Float {
        val facing = rx * normal.x + ry * normal.y + rz * normal.z
        if (abs(facing) < PARALLEL) return Float.NEGATIVE_INFINITY
        val t = ((origin.x - fx) * normal.x + (origin.y - fy) * normal.y + (origin.z - fz) * normal.z) / facing
        if (t <= 0f) return Float.NEGATIVE_INFINITY
        val dx = fx + rx * t - origin.x
        val dy = fy + ry * t - origin.y
        val dz = fz + rz * t - origin.z
        val u = dx * a.x + dy * a.y + dz * a.z
        val v = dx * b.x + dy * b.y + dz * b.z
        return if (contains(u, v)) t else Float.NEGATIVE_INFINITY
    }

    /** Whether the world point ([x], [y], [z]), projected onto the plane, falls inside its polygon. */
    fun covers(x: Float, y: Float, z: Float): Boolean {
        val dx = x - origin.x
        val dy = y - origin.y
        val dz = z - origin.z
        return contains(dx * a.x + dy * a.y + dz * a.z, dx * b.x + dy * b.y + dz * b.z)
    }

    /** Even-odd test of (`u`, `v`) against [outline]. */
    private fun contains(u: Float, v: Float): Boolean {
        if (u !in bounds[0]..bounds[1] || v !in bounds[2]..bounds[3]) return false
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

/** A plane's thickness in the dense cloud: how deep a band of points is the plane itself. */
internal object PlaneBand {
    /** Heights searched on each side of the plane. */
    private const val SEARCH_M = 0.4f

    /** Height bins: the dense cloud's voxel. */
    private const val BIN_M = 0.02f

    /** The band ends where the points thin out to this share of its densest height. */
    private const val THIN_SHARE = 0.25f

    /** Fewer points than this over the plane say nothing about its thickness. */
    private const val MIN_POINTS = 200

    /** No band is thicker than a low step: a bed or a sofa seat always stands out of it. */
    const val MAX_M = 0.3f

    /**
     * The height, toward [normal], under which the [points] over [slab] are its plane (through
     * [origin]): from the densest height bin up to where the points thin out to [THIN_SHARE] of
     * it, at most [MAX_M]; `0` with fewer than [MIN_POINTS] points over it.
     */
    fun thickness(points: FloatArray, normal: Vec3, origin: Vec3, slab: PlaneSlab): Float {
        val bins = (2 * SEARCH_M / BIN_M).toInt()
        val counts = IntArray(bins)
        var total = 0
        for (i in 0 until points.size / 3) {
            val x = points[i * 3]
            val y = points[i * 3 + 1]
            val z = points[i * 3 + 2]
            val height = (x - origin.x) * normal.x + (y - origin.y) * normal.y + (z - origin.z) * normal.z
            if (height <= -SEARCH_M || height >= SEARCH_M || !slab.covers(x, y, z)) continue
            counts[((height + SEARCH_M) / BIN_M).toInt().coerceIn(0, bins - 1)]++
            total++
        }
        if (total < MIN_POINTS) return 0f
        // Smoothed over three bins: depth quantisation leaves every other bin half empty.
        val smooth = FloatArray(bins) { k ->
            (counts[max(0, k - 1)] + counts[k] + counts[min(bins - 1, k + 1)]) / 3f
        }
        var peak = 0
        for (k in 1 until bins) if (smooth[k] > smooth[peak]) peak = k
        var top = peak
        while (top + 1 < bins && smooth[top + 1] >= THIN_SHARE * smooth[peak]) top++
        // The top bin's lower edge: smoothing spread the band by one bin.
        return (top * BIN_M - SEARCH_M).coerceIn(0f, MAX_M)
    }
}
