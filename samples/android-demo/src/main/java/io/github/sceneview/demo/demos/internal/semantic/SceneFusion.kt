package io.github.sceneview.demo.demos.internal.semantic

import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.ScanBox
import java.util.BitSet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * One part of the room the scan recognised: what it is ([label]), the upright [box] round it, how
 * sure the recognition is ([confidence], 0–1), in how many keyframes it was seen ([views]; 0 for
 * what the geometry alone found, a hole in a wall), and the [surfels] of the cloud it is made of.
 */
class RecognizedItem(
    val label: SceneLabel,
    val box: ScanBox,
    val confidence: Float,
    val views: Int,
    val surfels: IntArray,
)

/**
 * Many keyframes, one room: the same chair seen five times is one chair. Observations whose
 * surfels overlap — or, grown a little differently from another angle, whose footprints do — are
 * one object; its label is the one the views agree on most, its box is fitted again round the
 * surfels most of them share, and its confidence grows with the number of keyframes that saw it.
 */
object SceneFusion {
    /** Two observations sharing this much of the smaller one's surfels are one object. */
    const val SAME_SURFELS = 0.3f

    /** …or whose footprints overlap this much. */
    const val SAME_FOOTPRINT = 0.4f

    /** A surfel is the object's when this share of its observations has it. */
    const val VOTE = 0.3f

    /** One view is enough only this sure; otherwise an object needs two. */
    const val SINGLE_VIEW_SCORE = 0.6f

    /** Of two objects left overlapping this much on the floor, the less sure one goes. */
    const val SUPPRESS_FOOTPRINT = 0.5f

    /** Fewer voted surfels than this, and the best observation's own box is kept. */
    private const val MIN_FIT_SURFELS = 24

    private class Group(first: SceneObservation, val members: MutableList<SceneObservation> = mutableListOf(first)) {
        val union = BitSet().apply { first.surfels.forEach { set(it) } }
        val box: ScanBox = first.box

        fun add(observation: SceneObservation) {
            members += observation
            observation.surfels.forEach { union.set(it) }
        }
    }

    /** [observations] of the scan of [cloud], as the objects they are, surest first. */
    fun fuse(observations: List<SceneObservation>, cloud: DenseCloud): List<RecognizedItem> {
        val groups = ArrayList<Group>()
        for (observation in observations.sortedByDescending { it.score * it.iou }) {
            val surfels = BitSet().apply { observation.surfels.forEach { set(it) } }
            var into: Group? = null
            var bestShare = 0f
            for (group in groups) {
                val shared = surfels.clone() as BitSet
                shared.and(group.union)
                val smaller = min(surfels.cardinality(), group.union.cardinality()).coerceAtLeast(1)
                val surfelShare = shared.cardinality().toFloat() / smaller
                val footprint = footprintIou(observation.box, group.box)
                val share = max(surfelShare, footprint)
                val same = surfelShare >= SAME_SURFELS || footprint >= SAME_FOOTPRINT
                if (same && share > bestShare) {
                    bestShare = share
                    into = group
                }
            }
            if (into != null) into.add(observation) else groups += Group(observation)
        }
        val items = groups.mapNotNull { item(it, cloud) }.sortedByDescending { it.confidence }
        val kept = ArrayList<RecognizedItem>()
        for (item in items) if (kept.none { footprintIou(it.box, item.box) >= SUPPRESS_FOOTPRINT }) kept += item
        return kept
    }

    private fun item(group: Group, cloud: DenseCloud): RecognizedItem? {
        val votes = HashMap<SceneLabel, Float>()
        group.members.forEach { votes[it.label] = (votes[it.label] ?: 0f) + it.score }
        val label = votes.maxByOrNull { it.value }?.key ?: return null
        val perView = group.members.groupBy { it.view }.mapValues { (_, seen) -> seen.maxOf { it.score } }
        val views = perView.size
        val score = perView.values.average().toFloat()
        if (views < 2 && score < SINGLE_VIEW_SCORE) return null
        val confidence = score * (1f - 0.5f.pow(views))

        // The surfels most observations share: one view's region may have run into the wall.
        val counts = HashMap<Int, Int>()
        group.members.forEach { m -> m.surfels.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val need = max(1, ceil(group.members.size * VOTE).toInt())
        val voted = counts.filterValues { it >= need }.keys.sorted().toIntArray()
        val baseY = group.members.minOf { it.box.baseY }
        val fitted = if (voted.size >= MIN_FIT_SURFELS) FootprintFit.fit(cloud, voted, baseY) else null
        val best = group.members.maxBy { it.score * it.iou }
        return RecognizedItem(label, fitted ?: best.box, confidence, views, voted.takeIf { it.isNotEmpty() } ?: best.surfels)
    }

    /**
     * How much the footprints of [a] and [b] overlap, intersection over union: [a]'s rectangle
     * clipped by [b]'s four sides, the area of what is left.
     */
    fun footprintIou(a: ScanBox, b: ScanBox): Float {
        val areaA = a.length * a.depth
        val areaB = b.length * b.depth
        if (areaA <= 0f || areaB <= 0f) return 0f
        var polygon = corners(a)
        val clip = corners(b)
        for (k in 0 until CORNERS) {
            if (polygon.isEmpty()) break
            polygon = clipBy(polygon, clip[k], clip[(k + 1) % CORNERS])
        }
        val both = area(polygon)
        return (both / (areaA + areaB - both)).coerceIn(0f, 1f)
    }

    /** The footprint's corners, counter-clockwise seen from above (x right, z towards the viewer). */
    private fun corners(box: ScanBox): List<FloatArray> {
        val c = cos(box.yaw)
        val s = sin(box.yaw)
        val hl = box.length / 2
        val hd = box.depth / 2
        return listOf(-hl to -hd, hl to -hd, hl to hd, -hl to hd).map { (u, v) ->
            floatArrayOf(box.centerX + u * c - v * s, box.centerZ + u * s + v * c)
        }
    }

    /** What of [polygon] is on the inner side of the edge [from]–[to] (Sutherland–Hodgman). */
    private fun clipBy(polygon: List<FloatArray>, from: FloatArray, to: FloatArray): List<FloatArray> {
        fun side(p: FloatArray) = (to[0] - from[0]) * (p[1] - from[1]) - (to[1] - from[1]) * (p[0] - from[0])
        val out = ArrayList<FloatArray>(polygon.size + 1)
        for (i in polygon.indices) {
            val p = polygon[i]
            val q = polygon[(i + 1) % polygon.size]
            val sp = side(p)
            val sq = side(q)
            if (sp >= 0f) out += p
            if ((sp >= 0f) != (sq >= 0f)) {
                val t = sp / (sp - sq)
                out += floatArrayOf(p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1]))
            }
        }
        return out
    }

    private fun area(polygon: List<FloatArray>): Float {
        var twice = 0f
        for (i in polygon.indices) {
            val p = polygon[i]
            val q = polygon[(i + 1) % polygon.size]
            twice += p[0] * q[1] - q[0] * p[1]
        }
        return abs(twice) / 2
    }

    /** Whether ([x], [z]) is on [box]'s footprint. */
    fun inside(box: ScanBox, x: Float, z: Float): Boolean {
        val dx = x - box.centerX
        val dz = z - box.centerZ
        val c = cos(box.yaw)
        val s = sin(box.yaw)
        return abs(dx * c + dz * s) <= box.length / 2 && abs(-dx * s + dz * c) <= box.depth / 2
    }

    private const val CORNERS = 4
}

/**
 * The upright box round a set of surfels: the turn about +Y their faces stack up along, then
 * their extents along it, a sliver of outliers trimmed at each end. The same
 * convention as [ScanBox]: length ≥ depth, [ScanBox.yaw] along the length.
 */
object FootprintFit {
    /** Points sampled for the angle search: enough for a centimetre, few enough to be instant. */
    const val SAMPLES = 2000

    /** Share of the points trimmed off each end of each extent: depth noise, stray surfels. */
    const val TRIM = 0.01f

    /** The top: the height this share of the points stays under. */
    const val TOP = 0.99f

    private const val STEPS_DEG = 90

    /** The bin the points of one face stack up in. */
    private const val BIN_M = 0.02f

    /** The box of [surfels] of [cloud], standing on [baseY] (their own lowest when `null`). */
    fun fit(cloud: DenseCloud, surfels: IntArray, baseY: Float? = null): ScanBox? {
        if (surfels.isEmpty()) return null
        val n = surfels.size
        val xs = FloatArray(n) { cloud.positions[surfels[it] * 3] }
        val ys = FloatArray(n) { cloud.positions[surfels[it] * 3 + 1] }
        val zs = FloatArray(n) { cloud.positions[surfels[it] * 3 + 2] }
        return fit(xs, ys, zs, baseY)
    }

    /** The box of the points ([xs], [ys], [zs]), standing on [baseY] (their own lowest when `null`). */
    fun fit(xs: FloatArray, ys: FloatArray, zs: FloatArray, baseY: Float? = null): ScanBox? {
        val n = xs.size
        if (n == 0) return null
        val step = max(1, n / SAMPLES)
        val sample = (0 until n step step).toList().toIntArray()
        // The turn whose sides the points stack up on most sharply: a box seen from one side is a
        // face or an L of two, which squares up by its faces where a smallest-area box would tilt.
        var bestAngle = 0f
        var bestScore = -1L
        // Turned about the points' own middle, none of them is ever further than [reach] from it:
        // none falls off an end of the histograms, where piling up would win the search for a
        // wrong turn (sized on |x| and |z|, a turned point reaches √2 times as far).
        var mx = 0f
        var mz = 0f
        for (i in sample) {
            mx += xs[i]
            mz += zs[i]
        }
        mx /= sample.size
        mz /= sample.size
        var reach = 0f
        for (i in sample) reach = max(reach, hypot(xs[i] - mx, zs[i] - mz))
        val bins = ceil(2 * (reach + BIN_M) / BIN_M).toInt() + 1
        val hu = IntArray(bins)
        val hv = IntArray(bins)
        for (deg in 0 until STEPS_DEG) {
            val angle = (deg * PI / 180).toFloat()
            val c = cos(angle)
            val s = sin(angle)
            hu.fill(0)
            hv.fill(0)
            for (i in sample) {
                val x = xs[i] - mx
                val z = zs[i] - mz
                hu[((x * c + z * s + reach + BIN_M) / BIN_M).toInt().coerceIn(0, bins - 1)]++
                hv[((-x * s + z * c + reach + BIN_M) / BIN_M).toInt().coerceIn(0, bins - 1)]++
            }
            var score = 0L
            for (k in 0 until bins) score += hu[k].toLong() * hu[k] + hv[k].toLong() * hv[k]
            if (score > bestScore) {
                bestScore = score
                bestAngle = angle
            }
        }
        val c = cos(bestAngle)
        val s = sin(bestAngle)
        val (u0, u1) = trimmed(FloatArray(n) { xs[it] * c + zs[it] * s })
        val (v0, v1) = trimmed(FloatArray(n) { -xs[it] * s + zs[it] * c })
        val sortedY = ys.sortedArray()
        val bottom = baseY ?: sortedY[(n * TRIM).toInt().coerceIn(0, n - 1)]
        val top = sortedY[(n * TOP).toInt().coerceIn(0, n - 1)]
        val cu = (u0 + u1) / 2
        val cv = (v0 + v1) / 2
        val centerX = cu * c - cv * s
        val centerZ = cu * s + cv * c
        val alongU = u1 - u0
        val alongV = v1 - v0
        val (length, depth, yaw) = if (alongU >= alongV) {
            Triple(alongU, alongV, bestAngle)
        } else {
            Triple(alongV, alongU, bestAngle + (PI / 2).toFloat())
        }
        return ScanBox(centerX, centerZ, bottom, yaw, length, depth, max(0f, top - bottom))
    }

    /** The lowest and the highest of [values], [TRIM] of them left out at each end. */
    private fun trimmed(values: FloatArray): Pair<Float, Float> {
        val sorted = values.sortedArray()
        val cut = (sorted.size * TRIM).toInt()
        return sorted[cut] to sorted[sorted.size - 1 - cut]
    }
}
