package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.stopIfInterrupted
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The scan's cloud in the room's own frame: `u`, `v` across the floor (the room squared to them
 * by its yaw), `h` above the floor, and each surfel's normal in that frame.
 */
internal class FrameCloud(
    val count: Int,
    val u: FloatArray,
    val v: FloatArray,
    val h: FloatArray,
    val nu: FloatArray,
    val nv: FloatArray,
    val ny: FloatArray,
) {
    companion object {
        /** [positions] and [normals] turned by [yaw] about +Y, heights from [floorY]; every [stride]th surfel. */
        fun of(positions: FloatArray, normals: FloatArray, floorY: Float, yaw: Float, stride: Int = 1): FrameCloud {
            val all = positions.size / 3
            val n = (all + stride - 1) / stride
            val c = cos(yaw)
            val s = sin(yaw)
            val u = FloatArray(n)
            val v = FloatArray(n)
            val h = FloatArray(n)
            val nu = FloatArray(n)
            val nv = FloatArray(n)
            val ny = FloatArray(n)
            var k = 0
            var i = 0
            while (i < all && k < n) {
                stopIfInterrupted()
                val x = positions[i * 3]
                val z = positions[i * 3 + 2]
                u[k] = c * x + s * z
                v[k] = -s * x + c * z
                h[k] = positions[i * 3 + 1] - floorY
                nu[k] = c * normals[i * 3] + s * normals[i * 3 + 2]
                nv[k] = -s * normals[i * 3] + c * normals[i * 3 + 2]
                ny[k] = normals[i * 3 + 1]
                k++
                i += stride
            }
            return FrameCloud(k, u, v, h, nu, nv, ny)
        }
    }
}

/**
 * One wall line: a vertical plane in the room's frame, its normal ([nu], [nv]) pointing into
 * the room, at [offset] along it (`p · n`), its surfels spread by [spread]. Along it (`t = p · τ`,
 * τ = (−nv, nu)) it is cut into [column]-wide columns from [t0]; [seen] are those the scan saw a
 * wall on, [tops] how high each was seen.
 */
internal class WallLine(
    val family: Int,
    val nu: Float,
    val nv: Float,
    val offset: Float,
    val spread: Float,
    val t0: Float,
    val column: Float,
    val seen: BooleanArray,
    val tops: FloatArray,
    val top: Float,
    val members: IntArray,
) {
    val tu: Float get() = -nv
    val tv: Float get() = nu
    val from: Float = t0 + max(0, seen.indexOfFirst { it }) * column
    val to: Float = t0 + (seen.indexOfLast { it } + 1) * column

    /** The stretches of the line drawn as a barrier: seen pieces, bridged gaps and corner extensions. */
    var barrier: List<ClosedFloatingPointRange<Float>> = emptyList()

    /** The stretches of the line that are seen pieces, small gaps filled. */
    var pieces: List<ClosedFloatingPointRange<Float>> = emptyList()

    /** The stretches of the line hidden behind furniture standing against it: the wall goes on there. */
    var screened: List<ClosedFloatingPointRange<Float>> = emptyList()

    fun seenAt(t: Float): Boolean {
        val k = floor((t - t0) / column).toInt()
        return k in seen.indices && seen[k]
    }

    fun tOf(u: Float, v: Float): Float = u * tu + v * tv
    fun oOf(u: Float, v: Float): Float = u * nu + v * nv
    fun pointU(t: Float): Float = offset * nu + t * tu
    fun pointV(t: Float): Float = offset * nv + t * tv
    fun parallel(other: WallLine): Boolean = abs(nu * other.nv - nv * other.nu) < PARALLEL_SIN

    companion object {
        /** Lines whose directions differ by less than this (sine) are parallel. */
        const val PARALLEL_SIN = 0.34f
    }
}

/**
 * The walls of a scan, as lines: vertical surfels grouped by the way they face (a family per
 * direction, snapped square to the room when within a few degrees), then by how far along it
 * they stand. Two layers of one wall — a scan that drifted draws it twice — are one line; a face
 * seen lower than the wall behind it is furniture. A face seen only low, as a phone held towards
 * the floor sees walls, is a wall when nothing stands behind it.
 */
internal object WallLines {
    /** A vertical face reaching this high above the floor is a wall, not furniture, unless the wall is seen behind it. */
    const val WALL_MIN_TOP_M = 1.3f

    /**
     * A vertical face seen only this high — a phone held towards the floor sees walls up to about
     * 0.9 m — is a wall when nothing stands within [STRIP_M] behind it: a sofa's front has its
     * seat and back there, a wall has the other side nobody saw.
     */
    const val LOW_WALL_MIN_TOP_M = 0.6f

    /** A wall is a line seen on more than this much of its length. */
    const val MIN_WALL_SEEN_M = 0.6f

    /**
     * Layers of one wall are one wall within this: a scan that drifted draws a wall twice, and
     * 0.3 m is under the thinnest gap two real facing walls leave between them.
     */
    const val WALL_MERGE_M = 0.3f

    /** A vertical face is furniture when the same wall is seen this much higher, within [STRIP_M] behind it. */
    const val FURNITURE_UNDER_M = 0.2f

    /** A direction within this of square to the room is squared, when its surfels agree ([SQUARE_STACK_SHARE]). */
    const val SNAP_DEG = 7f

    /** A family is squared when its surfels stack along the square normal at least this tightly, against their own. */
    const val SQUARE_STACK_SHARE = 0.9f

    private const val STRIP_M = 1f
    private const val BEHIND_MIN_M = 0.1f
    private const val BIN_M = 0.02f
    private const val SMOOTH_BINS = 2
    private const val PEAK_APART_M = 0.08f
    private const val BAND_M = 0.1f
    private const val H_CELL_M = 0.1f
    private const val DEGREES = 360
    private const val AZIMUTH_SMOOTH = 3
    private const val FAMILY_APART_DEG = 15
    private const val FAMILY_REFINE_DEG = 10f
    private const val FAMILY_REACH_DEG = 20f
    private const val FAMILY_MIN_SHARE = 0.03f

    /**
     * A square direction of the room is a family with this share of the vertical surfels: the
     * walls of a cluttered bedroom scanned with a phone made 1.4 to 3 % each.
     */
    private const val FAMILY_SQUARE_SHARE = 0.01f
    private const val FAMILY_MIN_SURFELS = 50

    /** How far apart two shards of a wall may face: the normals of a wall's shards in a real scan were 19° apart. */
    private const val SHARD_REACH_DEG = 25f

    /**
     * How far apart along the wall two shards of it may stand: further apart, they are the faces
     * of two things standing before a wall never seen, not one wall.
     */
    private const val SHARD_GAP_M = 1f
    private const val QUARTER = 90f
    private const val GAP_FILL_M = 0.15f
    private const val END_NEAR_M = 0.3f
    private const val EXTEND_M = 0.6f
    private const val MIN_COLUMN_SURFELS = 3
    private const val MAX_BINS = 4000
    private const val TOP_SHARE = 0.9f
    private const val OVERLAP_SHARE = 0.5f
    private const val MIN_HORIZONTAL = 0.5f
    private const val LOW_BEHIND_FROM_M = 0.15f
    private const val LOW_ALONG_M = 0.1f
    private const val STANDING_FROM_M = 0.25f
    private const val STANDING_TO_M = 2f
    private const val BEHIND_SHARE = 4
    private const val FACING_ALONG = 0.5f
    private const val ASIDE_M = 0.3f

    /** Reads the wall lines of [cloud] on [column]-wide columns; [minSupport] cells make a candidate. */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun of(cloud: FrameCloud, column: Float, minSupport: Int): List<WallLine> {
        val vertical = IntArray(cloud.count)
        var nVertical = 0
        for (i in 0 until cloud.count) {
            if (abs(cloud.ny[i]) > RoomLevels.VERTICAL) continue
            val h = cloud.h[i]
            if (h < RoomLevels.WALL_FROM_M || h > RoomLevels.CEILING_MAX_M) continue
            if (hypot(cloud.nu[i], cloud.nv[i]) < MIN_HORIZONTAL) continue
            vertical[nVertical++] = i
        }
        if (nVertical == 0) return emptyList()
        val families = familiesOf(cloud, vertical, nVertical)
        if (families.isEmpty()) return emptyList()
        val standing = standingOf(cloud)
        // Each vertical surfel joins the family it faces, if any is near enough.
        val assigned = Array(families.size) { IntArrayList() }
        for (k in 0 until nVertical) {
            val i = vertical[k]
            val a = degreesOf(cloud.nu[i], cloud.nv[i])
            var best = -1
            var bestDiff = FAMILY_REACH_DEG
            for (f in families.indices) {
                val d = angleDiff(a, families[f])
                if (d < bestDiff) {
                    bestDiff = d
                    best = f
                }
            }
            if (best >= 0) assigned[best].add(i)
        }
        val candidates = ArrayList<WallLine>()
        for (f in families.indices) {
            val rad = families[f] * PI.toFloat() / HALF_TURN
            val nu = cos(rad).let { if (abs(it) < EPSILON) 0f else it }
            val nv = sin(rad).let { if (abs(it) < EPSILON) 0f else it }
            candidates += linesOf(cloud, assigned[f].toArray(), f, nu, nv, column, minSupport, standing)
        }
        val merged = mergeLayers(cloud, candidates, column, standing)
        return withoutFurniture(cloud, shardsJoined(cloud, merged, families, column, standing), column, standing)
    }

    /** What could stand behind a face seen low: surfels from [STANDING_FROM_M] to [STANDING_TO_M] above the floor. */
    private fun standingOf(cloud: FrameCloud): IntArray {
        val standing = IntArrayList()
        for (i in 0 until cloud.count) if (cloud.h[i] in STANDING_FROM_M..STANDING_TO_M) standing.add(i)
        return standing.toArray()
    }

    /**
     * For each of [columns] columns of a line at [offset], how many [standing] surfels that are
     * not its [members] stand from [LOW_BEHIND_FROM_M] to [STRIP_M] behind it, within
     * [LOW_ALONG_M] along. A face running away from the line — the next wall at a corner — does
     * not count: only a top, or a face turned the line's way.
     */
    @Suppress("LongParameterList")
    private fun behindOf(
        cloud: FrameCloud,
        members: IntArray,
        standing: IntArray,
        nu: Float,
        nv: Float,
        offset: Float,
        t0: Float,
        column: Float,
        columns: Int,
    ): IntArray {
        val behind = IntArray(columns)
        val own = members.copyOf().also { it.sort() }
        for (i in standing) {
            val o = cloud.u[i] * nu + cloud.v[i] * nv
            if (o > offset - LOW_BEHIND_FROM_M || o < offset - STRIP_M) continue
            if (abs(cloud.ny[i]) <= RoomLevels.VERTICAL && abs(cloud.nu[i] * nu + cloud.nv[i] * nv) < FACING_ALONG) continue
            val t = cloud.u[i] * -nv + cloud.v[i] * nu
            val k0 = max(0, floor((t - LOW_ALONG_M - t0) / column).toInt())
            val k1 = min(columns - 1, floor((t + LOW_ALONG_M - t0) / column).toInt())
            if (k0 > k1 || own.binarySearch(i) >= 0) continue
            for (k in k0..k1) behind[k]++
        }
        return behind
    }

    /** The directions the walls face, in degrees of the room's frame: the peaks of the vertical surfels' azimuths. */
    private fun familiesOf(cloud: FrameCloud, vertical: IntArray, n: Int): List<Float> {
        val hist = IntArray(DEGREES)
        for (k in 0 until n) {
            val i = vertical[k]
            hist[(degreesOf(cloud.nu[i], cloud.nv[i]).toInt()).coerceIn(0, DEGREES - 1)]++
        }
        val smooth = IntArray(DEGREES)
        for (b in 0 until DEGREES) {
            var sum = 0
            for (d in -AZIMUTH_SMOOTH..AZIMUTH_SMOOTH) sum += hist[(b + d + DEGREES) % DEGREES]
            smooth[b] = sum
        }
        val need = max(FAMILY_MIN_SURFELS, (n * FAMILY_MIN_SHARE).toInt())
        val order = (0 until DEGREES).filter { smooth[it] >= need }.sortedWith(compareBy({ -smooth[it] }, { it }))
        val peaks = ArrayList<Int>()
        for (b in order) {
            if (peaks.none { angleDiff(it.toFloat(), b.toFloat()) <= FAMILY_APART_DEG }) peaks += b
        }
        val families = ArrayList<Float>()
        for (p in peaks) {
            // The peak's own direction: the mean of the surfels around it.
            var sx = 0.0
            var sy = 0.0
            val near = IntArrayList()
            for (k in 0 until n) {
                val i = vertical[k]
                val a = degreesOf(cloud.nu[i], cloud.nv[i])
                if (angleDiff(a, p + HALF) > FAMILY_REFINE_DEG) continue
                near.add(i)
                val len = hypot(cloud.nu[i], cloud.nv[i])
                sx += cloud.nu[i] / len
                sy += cloud.nv[i] / len
            }
            var a = degreesOf(sx.toFloat(), sy.toFloat())
            val square = round(a / QUARTER) * QUARTER
            // Squared only when the surfels say so: they stack along the square normal about as
            // tightly as along their own. A wall built a few degrees off square keeps its turn.
            if (abs(a - square) <= SNAP_DEG && stackOf(cloud, near, square) >= stackOf(cloud, near, a) * SQUARE_STACK_SHARE) {
                a = square % DEGREES
            }
            if (families.none { angleDiff(it, a) < FAMILY_APART_DEG }) families += a
        }
        // In a cluttered room a wall can make under [FAMILY_MIN_SHARE] of the vertical surfels,
        // its faces lost among things turned every way. The room's frame was squared on all the
        // walls together: each of its four directions no family covers is one when
        // [FAMILY_SQUARE_SHARE] of the surfels face it — whether a wall stands there, its lines say.
        val squareNeed = max(FAMILY_MIN_SURFELS, (n * FAMILY_SQUARE_SHARE).toInt())
        for (q in 0 until DEGREES step QUARTER.toInt()) {
            val a = q.toFloat()
            if (smooth[q] >= squareNeed && families.none { angleDiff(it, a) < FAMILY_APART_DEG }) families += a
        }
        return families.sorted()
    }

    /**
     * How tightly [members] stack along the normal at [degrees]: the chance that two of them fall
     * in the same [BIN_M] slice of offset, averaged over two placements of the slices.
     */
    private fun stackOf(cloud: FrameCloud, members: IntArrayList, degrees: Float): Double {
        if (members.size == 0) return 0.0
        val rad = degrees * PI.toFloat() / HALF_TURN
        val nu = cos(rad)
        val nv = sin(rad)
        val o = FloatArray(members.size) { cloud.u[members[it]] * nu + cloud.v[members[it]] * nv }
        val lo = o.min()
        val bins = min(MAX_BINS, ((o.max() - lo) / BIN_M).toInt() + 2)
        var sum = 0.0
        for (shift in floatArrayOf(0f, HALF)) {
            val hist = IntArray(bins)
            for (x in o) hist[((x - lo) / BIN_M + shift).toInt().coerceIn(0, bins - 1)]++
            for (c in hist) sum += c.toDouble() * c
        }
        return sum / 2 / (members.size.toDouble() * members.size)
    }

    /** The candidate lines of one family: peaks of the surfels' offsets, weighed by the cells they cover. */
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    private fun linesOf(
        cloud: FrameCloud,
        members: IntArray,
        family: Int,
        nu: Float,
        nv: Float,
        column: Float,
        minSupport: Int,
        standing: IntArray,
    ): List<WallLine> {
        if (members.isEmpty()) return emptyList()
        val o = FloatArray(members.size)
        var oMin = Float.POSITIVE_INFINITY
        var oMax = Float.NEGATIVE_INFINITY
        for (k in members.indices) {
            val i = members[k]
            o[k] = cloud.u[i] * nu + cloud.v[i] * nv
            oMin = min(oMin, o[k])
            oMax = max(oMax, o[k])
        }
        val bins = min(MAX_BINS, ((oMax - oMin) / BIN_M).toInt() + 1)
        // Support: how many (along, height) cells a bin's surfels cover — a wall seen closely
        // weighs no more than one seen from afar.
        val keys = LongArray(members.size)
        for (k in members.indices) {
            val i = members[k]
            val ob = ((o[k] - oMin) / BIN_M).toInt().coerceIn(0, bins - 1)
            val t = cloud.u[i] * -nv + cloud.v[i] * nu
            val tc = floor(t / column).toInt() + KEY_SHIFT
            val hc = (cloud.h[i] / H_CELL_M).toInt().coerceIn(0, KEY_H - 1)
            keys[k] = (ob.toLong() shl KEY_O_BITS) or (tc.toLong() shl KEY_T_BITS) or hc.toLong()
        }
        keys.sort()
        val support = IntArray(bins)
        for (k in keys.indices) {
            if (k > 0 && keys[k] == keys[k - 1]) continue
            support[(keys[k] ushr KEY_O_BITS).toInt()]++
        }
        val smooth = IntArray(bins)
        for (b in 0 until bins) {
            var sum = 0
            for (d in -SMOOTH_BINS..SMOOTH_BINS) if (b + d in 0 until bins) sum += support[b + d]
            smooth[b] = sum
        }
        val order = (0 until bins).filter {
            smooth[it] >= minSupport && (it == 0 || smooth[it] >= smooth[it - 1]) &&
                (it == bins - 1 || smooth[it] >= smooth[it + 1])
        }.sortedWith(compareBy({ -smooth[it] }, { it }))
        val peaks = ArrayList<Float>()
        for (b in order) {
            val at = oMin + (b + HALF) * BIN_M
            if (peaks.none { abs(it - at) < PEAK_APART_M }) peaks += at
        }
        peaks.sort()
        val groups = Array(peaks.size) { IntArrayList() }
        for (k in members.indices) {
            var best = -1
            var bestD = Float.MAX_VALUE
            for (p in peaks.indices) {
                val d = abs(o[k] - peaks[p])
                val left = if (p > 0) (peaks[p] - peaks[p - 1]) / 2 else BAND_M
                val right = if (p < peaks.size - 1) (peaks[p + 1] - peaks[p]) / 2 else BAND_M
                val band = min(BAND_M, if (o[k] < peaks[p]) left else right)
                if (d <= band && d < bestD) {
                    bestD = d
                    best = p
                }
            }
            if (best >= 0) groups[best].add(members[k])
        }
        return groups.mapNotNull { lineOf(cloud, it.toArray(), family, nu, nv, column, null, standing) }
    }

    /**
     * A line from its surfels: where they stand, how they spread, which columns they cover and how
     * high. A column is seen up to [WALL_MIN_TOP_M]; one seen only from [LOW_WALL_MIN_TOP_M] is
     * when less than a [BEHIND_SHARE]th of its own surfels of [standing] stand behind it.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    fun lineOf(
        cloud: FrameCloud,
        members: IntArray,
        family: Int,
        nu: Float,
        nv: Float,
        column: Float,
        keep: ((Int) -> Boolean)?,
        standing: IntArray? = null,
    ): WallLine? {
        if (members.isEmpty()) return null
        var tMin = Float.POSITIVE_INFINITY
        var tMax = Float.NEGATIVE_INFINITY
        var offsetSum = 0.0
        for (i in members) {
            val t = cloud.u[i] * -nv + cloud.v[i] * nu
            tMin = min(tMin, t)
            tMax = max(tMax, t)
            offsetSum += cloud.u[i] * nu + cloud.v[i] * nv
        }
        val t0 = floor(tMin / column) * column
        val columns = ((tMax - t0) / column).toInt() + 1
        val counts = IntArray(columns)
        val tops = FloatArray(columns)
        for (i in members) {
            val k = ((cloud.u[i] * -nv + cloud.v[i] * nu - t0) / column).toInt().coerceIn(0, columns - 1)
            counts[k]++
            tops[k] = max(tops[k], cloud.h[i])
        }
        val low = { k: Int -> counts[k] >= MIN_COLUMN_SURFELS && tops[k] >= LOW_WALL_MIN_TOP_M && tops[k] < WALL_MIN_TOP_M }
        val behind = if (standing != null && (0 until columns).any(low)) {
            behindOf(cloud, members, standing, nu, nv, (offsetSum / members.size).toFloat(), t0, column, columns)
        } else {
            null
        }
        val seen = BooleanArray(columns) {
            val wall = tops[it] >= WALL_MIN_TOP_M ||
                behind != null && low(it) && behind[it] < max(MIN_COLUMN_SURFELS, counts[it] / BEHIND_SHARE)
            counts[it] >= MIN_COLUMN_SURFELS && wall && (keep == null || keep(it))
        }
        val kept = IntArrayList()
        var sum = 0.0
        var squares = 0.0
        for (i in members) {
            val k = ((cloud.u[i] * -nv + cloud.v[i] * nu - t0) / column).toInt().coerceIn(0, columns - 1)
            if (!seen[k]) continue
            kept.add(i)
            val o = (cloud.u[i] * nu + cloud.v[i] * nv).toDouble()
            sum += o
            squares += o * o
        }
        if (kept.size == 0) return null
        val mean = sum / kept.size
        val spread = sqrt(max(0.0, squares / kept.size - mean * mean)).toFloat()
        val seenTops = (0 until columns).filter { seen[it] }.map { tops[it] }.sorted()
        val top = seenTops[min(seenTops.size - 1, (seenTops.size * TOP_SHARE).toInt())]
        return WallLine(family, nu, nv, mean.toFloat(), spread, t0, column, seen, tops, top, kept.toArray())
    }

    /**
     * Two layers of one wall, close and side by side and as high as each other, are one line —
     * but a face standing against a wall seen only low, the wall seen beside it and hardly behind
     * it, is furniture before the wall however near: a headboard is not a layer of its wall.
     */
    @Suppress("NestedBlockDepth")
    private fun mergeLayers(cloud: FrameCloud, lines: List<WallLine>, column: Float, standing: IntArray): List<WallLine> {
        val parent = IntArray(lines.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            return r
        }
        for (a in lines.indices) {
            for (b in a + 1 until lines.size) {
                val la = lines[a]
                val lb = lines[b]
                if (la.family != lb.family || abs(la.offset - lb.offset) > WALL_MERGE_M) continue
                val overlap = min(la.to, lb.to) - max(la.from, lb.from)
                if (overlap < OVERLAP_SHARE * min(la.to - la.from, lb.to - lb.from)) continue
                if (abs(la.top - lb.top) > FURNITURE_UNDER_M) continue
                val front = if (la.offset > lb.offset) la else lb
                val back = if (front === la) lb else la
                val standsAgainst = back.top < WALL_MIN_TOP_M && front.offset - back.offset >= BEHIND_MIN_M && against(front, back)
                if (standsAgainst) continue
                parent[root(b)] = root(a)
            }
        }
        val groups = lines.indices.groupBy { root(it) }
        return groups.values.mapNotNull { group ->
            if (group.size == 1) return@mapNotNull lines[group[0]]
            val first = lines[group[0]]
            val members = group.flatMap { lines[it].members.asList() }.sorted().toIntArray()
            lineOf(cloud, members, first.family, first.nu, first.nv, column, null, standing)
        }
    }

    /**
     * A wall the depth returned only in shards is one line: pieces each seen along too little to
     * be a wall, their normals turned up to [SHARD_REACH_DEG] apart — so in two families, which
     * [mergeLayers] never joins — and in layers up to [WALL_MERGE_M] out of line. Short lines are
     * chained when two face within [SHARD_REACH_DEG] of each other and stand within [WALL_MERGE_M]
     * along the normal they share, with at most [SHARD_GAP_M] between them along it. A chain is refitted on its pieces' mean normal, weighed by
     * their surfels — squared when within [SNAP_DEG] of the room's square, the frame the walls
     * together were squared to — when its pieces all stand within [WALL_MERGE_M] along it and no
     * line facing its way stands behind it: what has something behind it is clutter, not the
     * room's edge. The line keeps only the columns its pieces saw, and is kept when they make a
     * wall's length together.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    private fun shardsJoined(
        cloud: FrameCloud,
        lines: List<WallLine>,
        families: List<Float>,
        column: Float,
        standing: IntArray,
    ): List<WallLine> {
        val minSeen = MIN_WALL_SEEN_M + column / 2
        val short = lines.filter { it.seen.count { s -> s } * column <= minSeen }
        if (short.size < 2) return lines
        val reach = cos(SHARD_REACH_DEG * PI.toFloat() / HALF_TURN)
        val parent = IntArray(short.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            return r
        }
        fun middleAlong(line: WallLine, nu: Float, nv: Float): Float {
            val t = (line.from + line.to) / 2
            return line.pointU(t) * nu + line.pointV(t) * nv
        }
        for (a in short.indices) {
            for (b in a + 1 until short.size) {
                val la = short[a]
                val lb = short[b]
                if (la.nu * lb.nu + la.nv * lb.nv < reach) continue
                val len = hypot(la.nu + lb.nu, la.nv + lb.nv)
                val nu = (la.nu + lb.nu) / len
                val nv = (la.nv + lb.nv) / len
                if (abs(middleAlong(la, nu, nv) - middleAlong(lb, nu, nv)) > WALL_MERGE_M) continue
                if (gapAlong(la, lb, nu, nv) > SHARD_GAP_M) continue
                parent[root(b)] = root(a)
            }
        }
        val joined = ArrayList<WallLine>()
        val used = HashSet<WallLine>()
        for (group in short.indices.groupBy { root(it) }.values) {
            if (group.size < 2) continue
            val pieces = group.map { short[it] }
            val members = IntArrayList()
            var su = 0f
            var sv = 0f
            for (piece in pieces) {
                for (i in piece.members) members.add(i)
                su += piece.nu * piece.members.size
                sv += piece.nv * piece.members.size
            }
            val mean = degreesOf(su, sv)
            val square = round(mean / QUARTER) * QUARTER % DEGREES
            val best = if (angleDiff(mean, square) <= SNAP_DEG) square else mean
            val rad = best * PI.toFloat() / HALF_TURN
            val nu = cos(rad).let { if (abs(it) < EPSILON) 0f else it }
            val nv = sin(rad).let { if (abs(it) < EPSILON) 0f else it }
            val along = pieces.map { middleAlong(it, nu, nv) }
            if (along.max() - along.min() > WALL_MERGE_M) continue
            if (pieces.any { angleDiff(degreesOf(it.nu, it.nv), best) > SHARD_REACH_DEG }) continue
            var tMin = Float.POSITIVE_INFINITY
            var offsetSum = 0.0
            for (k in 0 until members.size) {
                val i = members[k]
                tMin = min(tMin, cloud.u[i] * -nv + cloud.v[i] * nu)
                offsetSum += cloud.u[i] * nu + cloud.v[i] * nv
            }
            val offset = (offsetSum / members.size).toFloat()
            val t0 = floor(tMin / column) * column
            val inLine = { t: Float -> pieces.any { it.seenAt(it.tOf(offset * nu + t * -nv, offset * nv + t * nu)) } }
            val ends = pieces.flatMap { p -> listOf(p.from, p.to).map { t -> p.pointU(t) * -nv + p.pointV(t) * nu } }
            val behind = lines.any { other ->
                other !in pieces && angleDiff(degreesOf(other.nu, other.nv), best) <= SHARD_REACH_DEG &&
                    run {
                        val t = (other.from + other.to) / 2
                        val o = other.pointU(t) * nu + other.pointV(t) * nv
                        val a = other.pointU(other.from) * -nv + other.pointV(other.from) * nu
                        val b = other.pointU(other.to) * -nv + other.pointV(other.to) * nu
                        o in (offset - STRIP_M)..(offset - BEHIND_MIN_M) && max(a, b) > ends.min() && min(a, b) < ends.max()
                    }
            }
            if (behind) continue
            val family = families.indices.minBy { angleDiff(families[it], best) }
            val line = lineOf(cloud, members.toArray(), family, nu, nv, column, { k -> inLine(t0 + (k + HALF) * column) }, standing)
                ?: continue
            if (line.seen.count { it } * column <= minSeen) continue
            joined += line
            used += pieces
        }
        if (joined.isEmpty()) return lines
        return lines.filter { it !in used } + joined
    }

    /** How far apart [a] and [b] stand along the wall of normal ([nu], [nv]): 0 when they overlap. */
    private fun gapAlong(a: WallLine, b: WallLine, nu: Float, nv: Float): Float {
        fun along(line: WallLine, t: Float) = line.pointU(t) * -nv + line.pointV(t) * nu
        val a0 = along(a, a.from)
        val a1 = along(a, a.to)
        val b0 = along(b, b.from)
        val b1 = along(b, b.to)
        return max(0f, max(min(a0, a1), min(b0, b1)) - min(max(a0, a1), max(b0, b1)))
    }

    /**
     * Drops what is furniture: a column seen lower than a line of the same family standing
     * behind it (within [STRIP_M]) by [FURNITURE_UNDER_M] — a wardrobe's front before the wall
     * seen over it. A line left with too little of it seen is no wall.
     *
     * A wall seen only low says nothing of what stands before it higher up, and a wardrobe
     * against it is seen no higher: a face in front of it (within [STRIP_M]) that the wall goes on
     * beside and is hardly seen behind is furniture too — dropped with its sides, the wall
     * [WallLine.screened] behind it. A chimney breast seen only low is taken for one: its
     * footprint is counted in the room.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun withoutFurniture(cloud: FrameCloud, lines: List<WallLine>, column: Float, standing: IntArray): List<WallLine> {
        val dropped = HashSet<WallLine>()
        val screens = HashMap<WallLine, ArrayList<ClosedFloatingPointRange<Float>>>()
        for (front in lines) {
            val wall = lines.firstOrNull {
                it !== front && it.family == front.family && it.offset <= front.offset - BEHIND_MIN_M &&
                    it.offset >= front.offset - STRIP_M && it.top < WALL_MIN_TOP_M && against(front, it)
            } ?: continue
            dropped += front
            screens.getOrPut(wall) { ArrayList() } += front.from..front.to
            for (side in lines) if (!side.parallel(wall) && sideOf(side, front, wall)) dropped += side
        }
        val result = ArrayList<WallLine>()
        for (line in lines) {
            if (line in dropped) continue
            val behind = lines.filter {
                it !in dropped && it !== line && it.family == line.family && it.offset <= line.offset - BEHIND_MIN_M &&
                    it.offset >= line.offset - STRIP_M
            }
            val kept = if (behind.isEmpty()) {
                line
            } else {
                lineOf(cloud, line.members, line.family, line.nu, line.nv, column, { k ->
                    val t = line.t0 + (k + HALF) * column
                    behind.none { other ->
                        val j = floor((t - other.t0) / column).toInt()
                        j in other.seen.indices && other.seen[j] && other.tops[j] > line.tops[k] + FURNITURE_UNDER_M
                    }
                }, standing)
            } ?: continue
            if (kept.seen.count { it } * column > MIN_WALL_SEEN_M + column / 2) {
                screens[line]?.let { kept.screened = it }
                result += kept
            }
        }
        return result.sortedWith(compareBy({ it.family }, { it.offset }))
    }

    /** Whether [side] runs, end to end, between [front] and the [wall] it stands against: a side of that furniture. */
    private fun sideOf(side: WallLine, front: WallLine, wall: WallLine): Boolean =
        floatArrayOf(side.from, side.to).all { t ->
            val u = side.pointU(t)
            val v = side.pointV(t)
            val along = wall.tOf(u, v)
            val depth = wall.oOf(u, v)
            along in (front.from - ASIDE_M)..(front.to + ASIDE_M) &&
                depth in (wall.offset - BEHIND_MIN_M)..(front.offset + BEHIND_MIN_M)
        }

    /** Whether [front] stands against [wall]: the wall is seen right beside it, within [ASIDE_M] of one of its ends, and hardly behind it. */
    private fun against(front: WallLine, wall: WallLine): Boolean {
        fun seenOver(from: Float, to: Float): Int {
            var count = 0
            var t = from + wall.column / 2
            while (t < to) {
                if (wall.seenAt(t)) count++
                t += wall.column
            }
            return count
        }
        val beside = seenOver(front.from - ASIDE_M, front.from) > 0 || seenOver(front.to, front.to + ASIDE_M) > 0
        val columns = ((front.to - front.from) / wall.column).toInt()
        return beside && seenOver(front.from, front.to) * 2 < columns
    }

    /**
     * Each line's pieces (seen stretches, gaps under [GAP_FILL_M] filled) and its barrier: the
     * pieces, the gaps between them that are not a way into more of the room — a gap is one when
     * at both its ends another wall sets off away from the room, as the walls of a T's stem or
     * of an alcove do — and its ends carried to the next wall when that is within [EXTEND_M].
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    fun connect(lines: List<WallLine>) {
        for (line in lines) {
            val pieces = ArrayList<ClosedFloatingPointRange<Float>>()
            var start = -1
            var lastSeen = -1
            for (k in 0..line.seen.size) {
                val s = k < line.seen.size && line.seen[k]
                if (s) {
                    if (start < 0) {
                        start = k
                    } else if ((k - lastSeen - 1) * line.column >= GAP_FILL_M) {
                        pieces += (line.t0 + start * line.column)..(line.t0 + (lastSeen + 1) * line.column)
                        start = k
                    }
                    lastSeen = k
                }
            }
            if (start >= 0) pieces += (line.t0 + start * line.column)..(line.t0 + (lastSeen + 1) * line.column)
            line.pieces = pieces
        }
        for (line in lines) {
            val barrier = ArrayList<ClosedFloatingPointRange<Float>>()
            val pieces = joined(line.pieces, line.screened)
            if (pieces.isEmpty()) continue
            var from = pieces[0].start
            for (p in 0 until pieces.size - 1) {
                val a = pieces[p].endInclusive
                val b = pieces[p + 1].start
                val open = leavesRoom(line, a, lines, gapAhead = true) && leavesRoom(line, b, lines, gapAhead = false)
                if (open) {
                    barrier += from..a
                    from = b
                }
            }
            barrier += from..pieces.last().endInclusive
            // Ends carried to the nearest wall they meet, open gap ends included.
            val extended = barrier.map { range ->
                val lo = reachOf(line, range.start, lines, backward = true) ?: range.start
                val hi = reachOf(line, range.endInclusive, lines, backward = false) ?: range.endInclusive
                lo..hi
            }
            line.barrier = extended
        }
    }

    /** The seen [pieces] with the [screened] stretches beside them, one stretch where they are within [ASIDE_M]. */
    private fun joined(
        pieces: List<ClosedFloatingPointRange<Float>>,
        screened: List<ClosedFloatingPointRange<Float>>,
    ): List<ClosedFloatingPointRange<Float>> {
        if (screened.isEmpty() || pieces.isEmpty()) return pieces
        val all = (pieces + screened).sortedBy { it.start }
        val result = ArrayList<ClosedFloatingPointRange<Float>>()
        var from = all[0].start
        var to = all[0].endInclusive
        for (k in 1 until all.size) {
            val range = all[k]
            if (range.start <= to + ASIDE_M) {
                to = max(to, range.endInclusive)
            } else {
                result += from..to
                from = range.start
                to = range.endInclusive
            }
        }
        result += from..to
        return result
    }

    /** Another wall sets off from [t] of [line], away from the room, facing the gap ([gapAhead]: the gap runs on towards +t). */
    private fun leavesRoom(line: WallLine, t: Float, lines: List<WallLine>, gapAhead: Boolean): Boolean {
        val pu = line.pointU(t)
        val pv = line.pointV(t)
        for (other in lines) {
            if (other === line || other.parallel(line)) continue
            for (piece in other.pieces) {
                for (end in floatArrayOf(piece.start, piece.endInclusive)) {
                    val eu = other.pointU(end)
                    val ev = other.pointV(end)
                    if (hypot(eu - pu, ev - pv) > END_NEAR_M) continue
                    // The other wall runs from that end into the piece: which way is that?
                    val sign = if (end == piece.start) 1f else -1f
                    val du = other.tu * sign
                    val dv = other.tv * sign
                    // Away from the room is against the line's normal; its face looks into the gap.
                    val away = -(du * line.nu + dv * line.nv)
                    val facesGap = (other.nu * line.tu + other.nv * line.tv) * (if (gapAhead) 1f else -1f)
                    if (away > HALF && facesGap > -HALF) return true
                }
            }
        }
        return false
    }

    /**
     * How far the end [t] of [line] carries to meet another wall, within [EXTEND_M]; `null` when
     * none. The other wall's extent counts the stretches of it hidden behind furniture, and where
     * the crossing is behind furniture the end carries as far as furniture stands out from a wall
     * ([STRIP_M]): a wardrobe in a corner hides both walls' ends.
     */
    private fun reachOf(line: WallLine, t: Float, lines: List<WallLine>, backward: Boolean): Float? {
        var best: Float? = null
        for (other in lines) {
            if (other === line || other.parallel(line)) continue
            // Where the two lines cross, along this one.
            val denom = line.tu * other.nu + line.tv * other.nv
            if (abs(denom) < EPSILON) continue
            val cross = (other.offset - line.offset * (line.nu * other.nu + line.nv * other.nv)) / denom
            val beyond = if (backward) t - cross else cross - t
            if (beyond < 0f) continue
            // The crossing has to be near the other wall's own extent, what furniture hides of it included.
            val along = other.tOf(line.pointU(cross), line.pointV(cross))
            val from = other.screened.minOfOrNull { it.start }?.let { min(it, other.from) } ?: other.from
            val to = other.screened.maxOfOrNull { it.endInclusive }?.let { max(it, other.to) } ?: other.to
            if (along < from - EXTEND_M || along > to + EXTEND_M) continue
            val hidden = other.screened.any { along >= it.start - ASIDE_M && along <= it.endInclusive + ASIDE_M }
            if (beyond > if (hidden) STRIP_M else EXTEND_M) continue
            val reach = if (backward) min(t, cross) else max(t, cross)
            if (best == null || abs(reach - t) < abs(best - t)) best = reach
        }
        return best
    }

    fun degreesOf(x: Float, y: Float): Float {
        var a = atan2(y, x) * HALF_TURN / PI.toFloat()
        if (a < 0f) a += DEGREES
        if (a >= DEGREES) a -= DEGREES
        return a
    }

    fun angleDiff(a: Float, b: Float): Float {
        val d = abs(a - b) % DEGREES
        return min(d, DEGREES - d)
    }

    private const val HALF = 0.5f
    private const val HALF_TURN = 180f
    private const val EPSILON = 1e-6f
    private const val KEY_O_BITS = 40
    private const val KEY_T_BITS = 20
    private const val KEY_H = 1 shl 20
    private const val KEY_SHIFT = 1 shl 19
}

/** A growable list of ints, without boxing. */
internal class IntArrayList(capacity: Int = 16) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    operator fun get(index: Int): Int = data[index]

    fun toArray(): IntArray = data.copyOf(size)
}
