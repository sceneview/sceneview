package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.stopIfInterrupted
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A raster over the room's frame: [nu] × [nv] cells of [cell] metres from ([minU], [minV]). */
internal class Raster(val minU: Float, val minV: Float, val cell: Float, val nu: Int, val nv: Int) {
    val size: Int get() = nu * nv

    fun index(u: Float, v: Float): Int {
        val i = floor((u - minU) / cell).toInt()
        val j = floor((v - minV) / cell).toInt()
        return if (i in 0 until nu && j in 0 until nv) j * nu + i else -1
    }

    fun centreU(index: Int): Float = minU + (index % nu + HALF) * cell
    fun centreV(index: Int): Float = minV + (index / nu + HALF) * cell

    private companion object {
        const val HALF = 0.5f
    }
}

/** One side of the outline: on [line] when a wall was seen there, else deduced — normal ([nu], [nv]) into the room, at [offset]. */
internal class Side(val line: WallLine?, val nu: Float, val nv: Float, val offset: Float, val floorEdge: Boolean = false) {
    fun parallel(other: Side): Boolean = abs(nu * other.nv - nv * other.nu) < WallLine.PARALLEL_SIN
}

/**
 * The room's outline: corner `i` at ([u]`[i]`, [v]`[i]`) starts side `i`, counter-clockwise in
 * the room's frame. [region] is the room's floor on [raster], [regionArea] its area; [traced] is
 * `false` when the sides could not be read off the walls and the outline is the region's own
 * edge, [miss] then saying why, and [tracedArea] the area the sides held when they closed.
 */
@Suppress("LongParameterList")
internal class Outline(
    val u: FloatArray,
    val v: FloatArray,
    val sides: List<Side>,
    val raster: Raster,
    val region: BooleanArray,
    val traced: Boolean,
    val regionArea: Float = 0f,
    val miss: TraceMiss? = null,
    val tracedArea: Float? = null,
)

/** The sides read off the floor's edge, or why none could be. */
private sealed interface Trace {
    /** [pushed]: the floor area the deduced sides add, moved out past the floor seen to the walls' ends. */
    class Sides(val u: FloatArray, val v: FloatArray, val sides: List<Side>, val pushed: Float) : Trace
    class Missed(val why: TraceMiss) : Trace
}

/**
 * Where the room is, and the polygon that bounds it. The walls are drawn as barriers on a raster
 * of the floor; the room is what the camera's path reaches over seen floor without crossing a
 * wall — from the seen floor nearest to it where the floor under the camera was not seen — with
 * every place no way from outside reaches; its edge is then read side by side off the
 * walls it runs along. A side no wall was seen along is deduced: closed where the floor was seen
 * to end, in the room's directions when one fits.
 */
@Suppress("TooManyFunctions")
internal object RoomOutline {
    /** No outline is read on more cells than this: the raster's memory stays bounded. */
    const val MAX_CELLS = 500_000

    private const val REACH_M = 8f
    private const val PAD_CELLS = 4
    private const val SAMPLE_M = 0.02f
    private const val FLOOR_BAND_M = 0.1f
    private const val EVIDENCE_FROM_M = 0.05f
    private const val EVIDENCE_TO_M = 2f
    private const val CLOSING_CELLS = 2
    private const val LABEL_BEHIND_M = 0.08f
    private const val LABEL_AHEAD_M = 0.2f
    private const val LABEL_ALONG_M = 0.5f
    private const val LABEL_FACING = 0.3f
    private const val MIN_RUN_M = 0.15f
    private const val CORNER_RUN_M = 1f
    private const val CORNER_NEAR_M = 0.75f
    private const val FAMILY_FIT_DEG = 20f

    /** A stretch no wall was seen along turns a corner where it strays this far off its chord. */
    private const val TURN_M = 0.5f

    /** Corners a stretch no wall was seen along turns at most, run from one wall line back to itself. */
    private const val SAME_LINE_CUTS = 2
    private const val ENDS_NEAR_M = 1f
    private const val SAME_SIDE_M = 0.15f
    private const val SAME_FACING = 0.94f
    private const val AREA_AGREE = 0.15f
    private const val AREA_AGREE_M2 = 1f
    private const val SIMPLIFY_M = 0.1f

    /** A corner of the outline along the region's edge moves this far at most to where the walls beside it meet. */
    private const val CORNER_MOVE_M = 0.3f
    private const val EDGE_BAND_CELLS = 3
    private const val EDGE_PERCENTILE = 0.01f
    private const val MIN_EDGE_SURFELS = 20
    private const val MAX_STEPS = 4_000_000
    private const val UNLABELED = -1
    private const val DEGENERATE_M = 0.05f

    /** A step of the camera's path over unseen floor looks this far for the seen floor nearest to it. */
    private const val NEAREST_FLOOR_M = 3f

    /** Two facing walls' ends this close along them stop at the same place: a side open between them. */
    private const val OPEN_ALIGN_M = 0.3f

    /** Another wall this close to a wall's end meets it there: a corner, not an open end. */
    private const val OPEN_MET_M = 0.3f

    /** Two facing walls closer than this are no room's sides. */
    private const val OPEN_MIN_APART_M = 1f

    /** The camera's path this far past an open side's ends goes on into the room: it stays open. */
    private const val OPEN_TRAIL_M = 0.3f

    /** The floor past a wall's end is looked for this far on along it… */
    private const val OPEN_PAST_FROM_M = 0.15f
    private const val OPEN_PAST_TO_M = 0.75f

    /** …this far into the room from it… */
    private const val OPEN_IN_FROM_M = 0.05f
    private const val OPEN_IN_TO_M = 0.35f

    /** …and seen on this share of the cells there. */
    private const val OPEN_FLOOR_SHARE = 0.5f

    /** A deduced side this many cells from an open side closes it. */
    private const val OPEN_NEAR_CELLS = 3

    /**
     * The outline of the room in [cloud] bounded by [lines], on [cell]-metre cells; `null` when
     * the raster would exceed [MAX_CELLS] or no floor was found to start from.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount", "LongParameterList")
    fun of(
        cloud: FrameCloud,
        lines: List<WallLine>,
        trailU: FloatArray,
        trailV: FloatArray,
        floorThickness: Float,
        cell: Float,
        members: BooleanArray,
    ): Outline? {
        val raster = rasterOf(cloud, lines, trailU, trailV, floorThickness, cell) ?: return null
        val n = raster.size
        val barrier = BooleanArray(n)
        for (line in lines) {
            for (range in line.barrier) {
                var t = range.start
                while (t <= range.endInclusive + SAMPLE_M / 2) {
                    val k = raster.index(line.pointU(t), line.pointV(t))
                    if (k >= 0) barrier[k] = true
                    t += SAMPLE_M
                }
            }
        }
        var evidence = BooleanArray(n)
        val floorSeen = BooleanArray(n)
        val band = max(FLOOR_BAND_M, floorThickness)
        for (i in 0 until cloud.count) {
            val h = cloud.h[i]
            val floorSurfel = abs(h) <= band && cloud.ny[i] >= RoomLevels.UP
            val standing = h in EVIDENCE_FROM_M..EVIDENCE_TO_M && !members[i]
            if (!floorSurfel && !standing) continue
            val k = raster.index(cloud.u[i], cloud.v[i])
            if (k < 0) continue
            evidence[k] = true
            if (floorSurfel) floorSeen[k] = true
        }
        evidence = closing(evidence, raster, CLOSING_CELLS)
        // A side open onto the next room: closed between the ends of the walls it runs between.
        val open = openSides(lines, raster, floorSeen, trailU, trailV)
        for (side in open) {
            val length = hypot(side[2] - side[0], side[3] - side[1])
            var d = 0f
            while (d <= length + SAMPLE_M / 2) {
                val f = if (length > 0f) d / length else 0f
                val k = raster.index(side[0] + (side[2] - side[0]) * f, side[1] + (side[3] - side[1]) * f)
                if (k >= 0) barrier[k] = true
                d += SAMPLE_M
            }
        }
        // The room: what the camera's path reaches over seen floor without crossing a wall.
        val inside = insideOf(raster, evidence, barrier, trailU, trailV) ?: return null
        // Everything a way from outside reaches is not the room; what is left of it, around the inside, is.
        val outside = BooleanArray(n)
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        for (k in 0 until n) {
            val i = k % raster.nu
            val j = k / raster.nu
            val border = i == 0 || j == 0 || i == raster.nu - 1 || j == raster.nv - 1
            if (border && !inside[k] && !barrier[k]) {
                outside[k] = true
                queue[tail++] = k
            }
        }
        while (head < tail) {
            stopIfInterrupted()
            val k = queue[head++]
            forNeighbours(raster, k) { m ->
                if (!outside[m] && !inside[m] && !barrier[m]) {
                    outside[m] = true
                    queue[tail++] = m
                }
            }
        }
        val region = BooleanArray(n)
        head = 0
        tail = 0
        for (k in 0 until n) {
            if (inside[k]) {
                region[k] = true
                queue[tail++] = k
            }
        }
        while (head < tail) {
            stopIfInterrupted()
            val k = queue[head++]
            forNeighbours(raster, k) { m ->
                if (!region[m] && !outside[m] && !barrier[m]) {
                    region[m] = true
                    queue[tail++] = m
                }
            }
        }
        val loop = traceOf(raster, region) ?: return null
        val regionArea = region.count { it } * cell * cell
        val floorAt = ArrayList<Float>()
        for (i in 0 until cloud.count) {
            if (abs(cloud.h[i]) <= band && cloud.ny[i] >= RoomLevels.UP) {
                floorAt += cloud.u[i]
                floorAt += cloud.v[i]
            }
        }
        val miss: TraceMiss
        var tracedArea: Float? = null
        when (val trace = sidesOf(loop, raster, lines, cloud, floorAt.toFloatArray(), open)) {
            is Trace.Missed -> miss = trace.why
            is Trace.Sides -> {
                val area = signedArea(trace.u, trace.v)
                tracedArea = area
                miss = when {
                    area <= 0f || !simple(trace.u, trace.v) -> TraceMiss.Crossing
                    // What the walls' ends add past the floor seen is the sides' own, not a disagreement.
                    abs(area - regionArea - trace.pushed) > max(AREA_AGREE * regionArea, AREA_AGREE_M2) -> TraceMiss.AreaApart
                    else -> return Outline(trace.u, trace.v, trace.sides, raster, region, traced = true, regionArea)
                }
            }
        }
        // The sides did not close into a polygon that holds the room: its own edge.
        val (su, sv, sides) = alongRegion(loop, lines) ?: return null
        return Outline(su, sv, sides, raster, region, traced = false, regionArea, miss, tracedArea)
    }

    /**
     * The sides of the room open onto the next room, as segments (`u0, v0, u1, v1`): where two
     * walls facing each other both stop within [OPEN_ALIGN_M] of the same place, no other wall
     * meeting them there, the floor seen on past each end along its wall. A wall would be seen
     * before the floor at its foot: floor seen there says the wall ends, not that the scan
     * stopped short of it. Not where the camera's path goes on past the ends — that is still
     * the room.
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongParameterList")
    private fun openSides(
        lines: List<WallLine>,
        raster: Raster,
        floorSeen: BooleanArray,
        trailU: FloatArray,
        trailV: FloatArray,
    ): List<FloatArray> {
        // Each wall's free ends the floor goes on past: (line, t, +1 forward or −1 backward).
        val ends = ArrayList<Triple<WallLine, Float, Float>>()
        for (line in lines) {
            if (line.barrier.isEmpty()) continue
            for (sign in floatArrayOf(-1f, 1f)) {
                val t = if (sign < 0f) line.barrier.first().start else line.barrier.last().endInclusive
                if (met(line, t, lines)) continue
                if (floorOn(line, t, sign, raster, floorSeen)) ends += Triple(line, t, sign)
            }
        }
        val sides = ArrayList<FloatArray>()
        for (x in ends.indices) {
            for (y in x + 1 until ends.size) {
                val (a, ta, sa) = ends[x]
                val (b, tb, sb) = ends[y]
                // Facing each other, each on the room's side of the other, both ends the same way.
                if (a.nu * b.nu + a.nv * b.nv > -SAME_FACING) continue
                if ((a.tu * sa) * (b.tu * sb) + (a.tv * sa) * (b.tv * sb) < SAME_FACING) continue
                val au = a.pointU(ta)
                val av = a.pointV(ta)
                val bu = b.pointU(tb)
                val bv = b.pointV(tb)
                val apart = a.oOf(bu, bv) - a.offset
                if (apart < OPEN_MIN_APART_M || b.oOf(au, av) - b.offset < OPEN_MIN_APART_M) continue
                if (abs(a.tOf(au, av) - a.tOf(bu, bv)) > OPEN_ALIGN_M) continue
                // How far on the ends are, along a's end's way; the path past that, between the walls, goes on.
                val reach = max(ta * sa, a.tOf(bu, bv) * sa)
                val past = trailU.indices.any { k ->
                    val u = trailU[k]
                    val v = trailV[k]
                    a.oOf(u, v) > a.offset && b.oOf(u, v) > b.offset && a.tOf(u, v) * sa - reach > OPEN_TRAIL_M
                }
                if (!past) sides += floatArrayOf(au, av, bu, bv)
            }
        }
        return sides
    }

    /** Another wall meets [line] at its end [t]: within [OPEN_MET_M] of it, on its own extent. */
    private fun met(line: WallLine, t: Float, lines: List<WallLine>): Boolean {
        val pu = line.pointU(t)
        val pv = line.pointV(t)
        return lines.any { other ->
            if (other === line || other.parallel(line) || other.barrier.isEmpty()) return@any false
            val along = other.tOf(pu, pv)
            abs(other.oOf(pu, pv) - other.offset) <= OPEN_MET_M &&
                along >= other.barrier.first().start - OPEN_MET_M && along <= other.barrier.last().endInclusive + OPEN_MET_M
        }
    }

    /** The floor was seen along [line] past its end [t], towards [sign], on the room's side of it. */
    private fun floorOn(line: WallLine, t: Float, sign: Float, raster: Raster, floorSeen: BooleanArray): Boolean {
        var cells = 0
        var seen = 0
        var d = OPEN_PAST_FROM_M
        while (d <= OPEN_PAST_TO_M) {
            var o = OPEN_IN_FROM_M
            while (o <= OPEN_IN_TO_M) {
                val tt = t + d * sign
                val k = raster.index(line.pointU(tt) + o * line.nu, line.pointV(tt) + o * line.nv)
                cells++
                if (k >= 0 && floorSeen[k]) seen++
                o += raster.cell
            }
            d += raster.cell
        }
        return cells > 0 && seen >= cells * OPEN_FLOOR_SHARE
    }

    /**
     * The outline along the region's edge [loop] (`u, v` pairs, closed, counter-clockwise),
     * simplified, each side deduced but one that runs along one of [lines]: that wall's, even
     * where the walls did not close — on the wall itself, not on the region's edge, which stops
     * short of the cells the wall is drawn on. Each corner is then where its two sides meet,
     * unless that is more than [CORNER_MOVE_M] off the region's corner or the outline would
     * cross itself. `null` under three corners.
     */
    fun alongRegion(loop: FloatArray, lines: List<WallLine>): Triple<FloatArray, FloatArray, List<Side>>? {
        val simplified = simplify(loop, SIMPLIFY_M)
        val m = simplified.size / 2
        if (m < 3) return null
        val su = FloatArray(m) { simplified[it * 2] }
        val sv = FloatArray(m) { simplified[it * 2 + 1] }
        val sides = (0 until m).map { i ->
            val next = (i + 1) % m
            val side = sideThrough(su[i], sv[i], su[next], sv[next])
            val line = lines.firstOrNull {
                it.nu * side.nu + it.nv * side.nv >= SAME_FACING && abs(it.offset - side.offset) <= SAME_SIDE_M
            }
            if (line == null) side else Side(line, line.nu, line.nv, line.offset)
        }
        if (sides.none { it.line != null }) return Triple(su, sv, sides)
        val cu = su.copyOf()
        val cv = sv.copyOf()
        for (i in 0 until m) {
            val at = meet(sides[(i + m - 1) % m], sides[i]) ?: continue
            if (hypot(at[0] - su[i], at[1] - sv[i]) > CORNER_MOVE_M) continue
            cu[i] = at[0]
            cv[i] = at[1]
        }
        if (signedArea(cu, cv) <= 0f || !simple(cu, cv)) {
            // The walls do not hold the corners: the region's own edge, sides deduced.
            return Triple(su, sv, (0 until m).map { sideThrough(su[it], sv[it], su[(it + 1) % m], sv[(it + 1) % m]) })
        }
        return Triple(cu, cv, sides)
    }

    @Suppress("LongParameterList", "ReturnCount")
    private fun rasterOf(
        cloud: FrameCloud,
        lines: List<WallLine>,
        trailU: FloatArray,
        trailV: FloatArray,
        floorThickness: Float,
        cell: Float,
    ): Raster? {
        var lo0 = Float.POSITIVE_INFINITY
        var lo1 = Float.POSITIVE_INFINITY
        var hi0 = Float.NEGATIVE_INFINITY
        var hi1 = Float.NEGATIVE_INFINITY
        fun take(u: Float, v: Float) {
            lo0 = min(lo0, u)
            lo1 = min(lo1, v)
            hi0 = max(hi0, u)
            hi1 = max(hi1, v)
        }
        for (k in trailU.indices) take(trailU[k], trailV[k])
        for (line in lines) {
            for (range in line.barrier) {
                take(line.pointU(range.start), line.pointV(range.start))
                take(line.pointU(range.endInclusive), line.pointV(range.endInclusive))
            }
        }
        val band = max(FLOOR_BAND_M, floorThickness)
        for (i in 0 until cloud.count) {
            if (abs(cloud.h[i]) <= band && cloud.ny[i] >= RoomLevels.UP) take(cloud.u[i], cloud.v[i])
        }
        if (lo0 > hi0) return null
        if (trailU.isNotEmpty()) {
            val tu0 = trailU.min() - REACH_M
            val tu1 = trailU.max() + REACH_M
            val tv0 = trailV.min() - REACH_M
            val tv1 = trailV.max() + REACH_M
            lo0 = max(lo0, tu0)
            hi0 = min(hi0, tu1)
            lo1 = max(lo1, tv0)
            hi1 = min(hi1, tv1)
        }
        val minU = floor(lo0 / cell) * cell - PAD_CELLS * cell
        val minV = floor(lo1 / cell) * cell - PAD_CELLS * cell
        val nu = ceil((hi0 - minU) / cell).toInt() + PAD_CELLS + 1
        val nv = ceil((hi1 - minV) / cell).toInt() + PAD_CELLS + 1
        if (nu.toLong() * nv > MAX_CELLS || nu <= 0 || nv <= 0) return null
        return Raster(minU, minV, cell, nu, nv)
    }

    /**
     * The cells of the room's inside: the component of passable floor that holds most of the
     * camera's path. A phone sees the floor ahead of it, seldom the floor under it: a step over
     * floor not seen votes for the seen floor nearest to it, within [NEAREST_FLOOR_M] and without
     * crossing a wall.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    private fun insideOf(
        raster: Raster,
        evidence: BooleanArray,
        barrier: BooleanArray,
        trailU: FloatArray,
        trailV: FloatArray,
    ): BooleanArray? {
        val n = raster.size
        val label = IntArray(n) { -1 }
        val queue = IntArray(n)
        val seeds = trailU.indices.map { raster.index(trailU[it], trailV[it]) }.filter { it >= 0 && !barrier[it] }
        var components = 0
        val votes = ArrayList<Int>()
        val sizes = ArrayList<Int>()
        fun flood(start: Int) {
            var head = 0
            var tail = 0
            label[start] = components
            queue[tail++] = start
            while (head < tail) {
                stopIfInterrupted()
                val k = queue[head++]
                forNeighbours(raster, k) { m ->
                    if (label[m] < 0 && evidence[m] && !barrier[m]) {
                        label[m] = components
                        queue[tail++] = m
                    }
                }
            }
            sizes += tail
            votes += 0
            components++
        }
        if (seeds.isNotEmpty()) {
            val stray = ArrayList<Int>()
            for (s in seeds) {
                if (!evidence[s]) {
                    stray += s
                    continue
                }
                if (label[s] < 0) flood(s)
                votes[label[s]] = votes[label[s]] + 1
            }
            if (stray.isNotEmpty()) {
                // One search from every stray step at once, each cell held by the step nearest to it.
                val origin = IntArray(n) { -1 }
                val search = IntArray(n)
                val steps = ArrayList<Int>()
                val weight = ArrayList<Int>()
                var head = 0
                var tail = 0
                for (s in stray) {
                    if (origin[s] < 0) {
                        origin[s] = steps.size
                        steps += s
                        weight += 0
                        search[tail++] = s
                    }
                    weight[origin[s]] = weight[origin[s]] + 1
                }
                val voted = BooleanArray(steps.size)
                val reach = (NEAREST_FLOOR_M / raster.cell).toInt()
                var depth = 0
                var layerEnd = tail
                var any = false
                while (head < tail) {
                    stopIfInterrupted()
                    if (head == layerEnd) {
                        depth++
                        layerEnd = tail
                        if (depth > reach) break
                    }
                    val k = search[head++]
                    val o = origin[k]
                    if (evidence[k]) {
                        if (!voted[o]) {
                            voted[o] = true
                            any = true
                            if (label[k] < 0) flood(k)
                            votes[label[k]] = votes[label[k]] + weight[o]
                        }
                        continue
                    }
                    forNeighbours(raster, k) { m ->
                        if (origin[m] < 0 && !barrier[m]) {
                            origin[m] = o
                            search[tail++] = m
                        }
                    }
                }
                // No seen floor near the path at all: each stray step stands on its own.
                if (!any) {
                    for (s in stray) {
                        if (label[s] < 0) flood(s)
                        votes[label[s]] = votes[label[s]] + 1
                    }
                }
            }
        } else {
            for (k in 0 until n) if (label[k] < 0 && evidence[k] && !barrier[k]) flood(k)
            if (components == 0) return null
            for (c in 0 until components) votes[c] = sizes[c]
        }
        if (components == 0) return null
        // Most of the path; between equals, the larger floor.
        val best = (0 until components).maxWith(compareBy({ votes[it] }, { sizes[it] }))
        return BooleanArray(n) { label[it] == best }
    }

    private inline fun forNeighbours(raster: Raster, k: Int, action: (Int) -> Unit) {
        val i = k % raster.nu
        val j = k / raster.nu
        if (i > 0) action(k - 1)
        if (i < raster.nu - 1) action(k + 1)
        if (j > 0) action(k - raster.nu)
        if (j < raster.nv - 1) action(k + raster.nu)
    }

    /** [mask] closed over [radius] cells: holes in a sparsely seen floor filled, its edge kept. */
    private fun closing(mask: BooleanArray, raster: Raster, radius: Int): BooleanArray {
        val grown = dilate(mask, raster, radius)
        val inverse = BooleanArray(grown.size) { !grown[it] }
        val shrunk = dilate(inverse, raster, radius)
        return BooleanArray(grown.size) { !shrunk[it] }
    }

    /** [mask] grown by [radius] cells each way: a square window, rows then columns. */
    private fun dilate(mask: BooleanArray, raster: Raster, radius: Int): BooleanArray {
        val nu = raster.nu
        val nv = raster.nv
        val rows = BooleanArray(mask.size)
        for (j in 0 until nv) {
            var count = 0
            for (i in 0 until min(radius, nu)) if (mask[j * nu + i]) count++
            for (i in 0 until nu) {
                if (i + radius < nu && mask[j * nu + i + radius]) count++
                if (i - radius - 1 >= 0 && mask[j * nu + i - radius - 1]) count--
                rows[j * nu + i] = count > 0
            }
        }
        val out = BooleanArray(mask.size)
        for (i in 0 until nu) {
            var count = 0
            for (j in 0 until min(radius, nv)) if (rows[j * nu + i]) count++
            for (j in 0 until nv) {
                if (j + radius < nv && rows[(j + radius) * nu + i]) count++
                if (j - radius - 1 >= 0 && rows[(j - radius - 1) * nu + i]) count--
                out[j * nu + i] = count > 0
            }
        }
        return out
    }

    /**
     * The region's outer edge as a loop of cell corners (`u, v` pairs), counter-clockwise: each
     * step one cell side, the region on its left. Where two cells of it touch only at a corner,
     * the edge keeps to the cell it is on.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun traceOf(raster: Raster, region: BooleanArray): FloatArray? {
        val nu = raster.nu
        val start = region.indexOfFirst { it }
        if (start < 0) return null
        fun inRegion(i: Int, j: Int) = i in 0 until nu && j in 0 until raster.nv && region[j * nu + i]
        // Corners are (i, j) in cell units; start on the lowest cell's bottom side, heading +u.
        var ci = start % nu
        var cj = start / nu
        val si = ci
        val sj = cj
        var di = 1
        var dj = 0
        val points = ArrayList<Float>()
        var steps = 0
        do {
            points += raster.minU + ci * raster.cell
            points += raster.minV + cj * raster.cell
            ci += di
            cj += dj
            // At the new corner: turn left when the cell ahead-left is not the region's, right when
            // both cells ahead are, else straight on between them.
            val left = cellLeft(ci, cj, di, dj)
            val right = cellRight(ci, cj, di, dj)
            val leftIn = inRegion(left[0], left[1])
            val rightIn = inRegion(right[0], right[1])
            if (!leftIn) {
                val t = di
                di = -dj
                dj = t
            } else if (rightIn) {
                val t = di
                di = dj
                dj = -t
            }
            if (++steps > MAX_STEPS) return null
        } while (ci != si || cj != sj || di != 1 || dj != 0)
        return points.toFloatArray()
    }

    /** The cell ahead and to the left of a walk arriving at corner ([ci], [cj]) heading ([di], [dj]). */
    private fun cellLeft(ci: Int, cj: Int, di: Int, dj: Int): IntArray = when {
        di == 1 -> intArrayOf(ci, cj)
        di == -1 -> intArrayOf(ci - 1, cj - 1)
        dj == 1 -> intArrayOf(ci - 1, cj)
        else -> intArrayOf(ci, cj - 1)
    }

    /** The cell ahead and to the right of a walk arriving at corner ([ci], [cj]) heading ([di], [dj]). */
    private fun cellRight(ci: Int, cj: Int, di: Int, dj: Int): IntArray = when {
        di == 1 -> intArrayOf(ci, cj - 1)
        di == -1 -> intArrayOf(ci - 1, cj)
        dj == 1 -> intArrayOf(ci, cj)
        else -> intArrayOf(ci - 1, cj - 1)
    }

    /**
     * The outline's sides read off the [loop]: each step labelled with the wall it runs along,
     * labels smoothed, then each stretch no wall runs along either cut off (a corner the raster
     * rounded) or deduced. [TraceMiss.TooFewSides] when fewer than three sides are left.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount", "NestedBlockDepth")
    private fun sidesOf(
        loop: FloatArray,
        raster: Raster,
        lines: List<WallLine>,
        cloud: FrameCloud,
        floorAt: FloatArray,
        open: List<FloatArray>,
    ): Trace {
        val m = loop.size / 2
        if (m < 4) return Trace.Missed(TraceMiss.TooFewSides)
        val labels = IntArray(m)
        for (e in 0 until m) {
            val next = (e + 1) % m
            val au = loop[e * 2]
            val av = loop[e * 2 + 1]
            val bu = loop[next * 2]
            val bv = loop[next * 2 + 1]
            val mu = (au + bu) / 2
            val mv = (av + bv) / 2
            val du = (bu - au) / raster.cell
            val dv = (bv - av) / raster.cell
            // The region is on the left: its inward normal is (−dv, du).
            val iu = -dv
            val iv = du
            var best = UNLABELED
            var bestD = Float.MAX_VALUE
            for (l in lines.indices) {
                val line = lines[l]
                if (iu * line.nu + iv * line.nv < LABEL_FACING) continue
                val d = line.oOf(mu, mv) - line.offset
                if (d < -LABEL_BEHIND_M || d > LABEL_AHEAD_M + raster.cell) continue
                val t = line.tOf(mu, mv)
                val lo = min(line.from, line.barrier.firstOrNull()?.start ?: line.from)
                val hi = max(line.to, line.barrier.lastOrNull()?.endInclusive ?: line.to)
                if (t < lo - LABEL_ALONG_M || t > hi + LABEL_ALONG_M) continue
                if (abs(d) < bestD) {
                    bestD = abs(d)
                    best = l
                }
            }
            labels[e] = best
        }
        if (labels.all { it == UNLABELED }) return Trace.Missed(TraceMiss.NoWallAlong)
        // Runs: start where the label changes, so no run wraps around the loop's start.
        val shift = (0 until m).firstOrNull { labels[it] != labels[(it + m - 1) % m] }
            ?: return Trace.Missed(TraceMiss.TooFewSides)
        val runLabel = ArrayList<Int>()
        val runStart = ArrayList<Int>()
        val runCount = ArrayList<Int>()
        for (k in 0 until m) {
            val e = (shift + k) % m
            if (runLabel.isNotEmpty() && runLabel.last() == labels[e]) {
                runCount[runCount.size - 1] = runCount.last() + 1
            } else {
                runLabel += labels[e]
                runStart += e
                runCount += 1
            }
        }
        // Short runs take the label of their longer neighbour.
        val minRun = max(1, (MIN_RUN_M / raster.cell + HALF).toInt())
        while (runLabel.size > 2) {
            var shortest = -1
            for (r in runLabel.indices) {
                if (runCount[r] < minRun && (shortest < 0 || runCount[r] < runCount[shortest])) shortest = r
            }
            if (shortest < 0) break
            val prev = (shortest + runLabel.size - 1) % runLabel.size
            val next = (shortest + 1) % runLabel.size
            val into = if (runLabel[prev] == runLabel[next] || runCount[prev] >= runCount[next]) prev else next
            if (into == prev) {
                runCount[prev] = runCount[prev] + runCount[shortest]
            } else {
                runCount[next] = runCount[next] + runCount[shortest]
                runStart[next] = runStart[shortest]
            }
            runLabel.removeAt(shortest)
            runStart.removeAt(shortest)
            runCount.removeAt(shortest)
            mergeEqual(runLabel, runStart, runCount)
        }
        if (runLabel.size < 3) return Trace.Missed(TraceMiss.TooFewSides)
        // Each run as a side: a wall's own line, or a deduced one.
        data class Run(val label: Int, val start: Int, val count: Int)
        val runs = runLabel.indices.map { Run(runLabel[it], runStart[it], runCount[it]) }
        fun point(e: Int) = floatArrayOf(loop[(e % m) * 2], loop[(e % m) * 2 + 1])
        val sides = ArrayList<Side>()
        val junctions = ArrayList<FloatArray>()
        var pushed = 0f
        for ((r, run) in runs.withIndex()) {
            val first = point(run.start)
            val last = point(run.start + run.count)
            if (run.label != UNLABELED) {
                val line = lines[run.label]
                sides += Side(line, line.nu, line.nv, line.offset)
                junctions += first
                continue
            }
            val prev = runs[(r + runs.size - 1) % runs.size]
            val next = runs[(r + 1) % runs.size]
            val length = run.count * raster.cell
            if (prev.label != UNLABELED && next.label != UNLABELED && length < CORNER_RUN_M) {
                val a = lines[prev.label]
                val b = lines[next.label]
                if (!a.parallel(b)) {
                    val corner = meet(Side(a, a.nu, a.nv, a.offset), Side(b, b.nu, b.nv, b.offset))
                    val mu = (first[0] + last[0]) / 2
                    val mv = (first[1] + last[1]) / 2
                    if (corner != null && hypot(corner[0] - mu, corner[1] - mv) <= CORNER_NEAR_M) continue
                }
            }
            // A stretch that turns a corner — two walls unseen side by side — is one side per straight
            // piece, as many as the walls on either end need: none between parallel ones, the floor's
            // edge bulging out of line on its own being no corner.
            val pieces = straightPieces(loop, run.start, run.count, m, cutsBetween(prev.label, next.label, lines))
            for ((p, piece) in pieces.withIndex()) {
                val from = point(piece[0])
                val to = point(piece[0] + piece[1])
                val ends = ArrayList<FloatArray>()
                if (p == 0 && prev.label != UNLABELED) nearestEnd(lines[prev.label], from)?.let { ends += it }
                if (p == pieces.lastIndex && next.label != UNLABELED) nearestEnd(lines[next.label], to)?.let { ends += it }
                val side = deduced(loop, piece[0], piece[1], m, lines, ends)
                val across = acrossOpening(side, from, to, open, raster.cell)
                if (across == null) pushed += beyond(loop, piece[0], piece[1], m, side)
                sides += across ?: side
                junctions += from
            }
        }
        // Parallel neighbours: one wall when they are one line, else a deduced step between them.
        val merged = ArrayList<Side>()
        val mergedAt = ArrayList<FloatArray>()
        for (i in sides.indices) {
            val side = sides[i]
            if (merged.isNotEmpty()) {
                val last = merged.last()
                if (last.parallel(side)) {
                    val same = last.nu * side.nu + last.nv * side.nv >= SAME_FACING
                    if (same && abs(last.offset - side.offset) <= SAME_SIDE_M) {
                        if (last.line == null && side.line != null) merged[merged.size - 1] = side
                        continue
                    }
                    merged += stepBetween(last, side, junctions[i])
                    mergedAt += junctions[i]
                }
            }
            merged += side
            mergedAt += junctions[i]
        }
        // The loop closes: the last side against the first.
        while (merged.size >= 2 && merged.last().parallel(merged.first())) {
            val last = merged.last()
            val first = merged.first()
            val same = last.nu * first.nu + last.nv * first.nv >= SAME_FACING
            if (same && abs(last.offset - first.offset) <= SAME_SIDE_M) {
                merged.removeAt(merged.size - 1)
                mergedAt.removeAt(mergedAt.size - 1)
                if (first.line == null && last.line != null) merged[0] = last
            } else {
                merged += stepBetween(last, first, mergedAt[0])
                mergedAt += mergedAt[0]
                break
            }
        }
        dropDegenerate(merged)
        if (merged.size < 3) return Trace.Missed(TraceMiss.TooFewSides)
        val n = merged.size
        var cu = FloatArray(n)
        var cv = FloatArray(n)
        for (i in 0 until n) {
            val corner = meet(merged[(i + n - 1) % n], merged[i]) ?: return Trace.Missed(TraceMiss.SidesApart)
            cu[i] = corner[0]
            cv[i] = corner[1]
        }
        // A side closed on the floor's edge stands on the raster's cell: moved onto the floor's last surfels.
        if (merged.any { it.floorEdge }) {
            val refined = ArrayList<Side>(n)
            for (i in 0 until n) {
                val side = merged[i]
                refined += if (side.floorEdge) {
                    val beside = listOfNotNull(merged[(i + n - 1) % n].line, merged[(i + 1) % n].line)
                    onFloorEdge(side, cu[i], cv[i], cu[(i + 1) % n], cv[(i + 1) % n], floorAt, cloud, beside, raster.cell)
                } else {
                    side
                }
            }
            val ru = FloatArray(n)
            val rv = FloatArray(n)
            var met = true
            for (i in 0 until n) {
                val corner = meet(refined[(i + n - 1) % n], refined[i])
                if (corner == null) {
                    met = false
                    break
                }
                ru[i] = corner[0]
                rv[i] = corner[1]
            }
            if (met) {
                cu = ru
                cv = rv
                merged.clear()
                merged += refined
            }
        }
        return Trace.Sides(cu, cv, merged, pushed)
    }

    /**
     * [side] moved onto the edge of what was seen along it between ([au], [av]) and ([bu], [bv]):
     * the outermost of the floor's surfels there and of the [beside] walls' own, each read at its
     * [EDGE_PERCENTILE], within a cell or so of where the raster put it. Left where it is when too
     * little was seen there.
     */
    @Suppress("LongParameterList")
    private fun onFloorEdge(
        side: Side,
        au: Float,
        av: Float,
        bu: Float,
        bv: Float,
        floorAt: FloatArray,
        cloud: FrameCloud,
        beside: List<WallLine>,
        cell: Float,
    ): Side {
        val ta = -au * side.nv + av * side.nu
        val tb = -bu * side.nv + bv * side.nu
        val lo = min(ta, tb) + cell
        val hi = max(ta, tb) - cell
        if (hi <= lo) return side
        val near = ArrayList<Float>()
        for (k in 0 until floorAt.size / 2) {
            val u = floorAt[k * 2]
            val v = floorAt[k * 2 + 1]
            val t = -u * side.nv + v * side.nu
            if (t < lo || t > hi) continue
            val d = u * side.nu + v * side.nv
            if (d >= side.offset - cell && d <= side.offset + EDGE_BAND_CELLS * cell) near += d
        }
        if (near.size < MIN_EDGE_SURFELS) return side
        near.sort()
        var edge = near[(near.size * EDGE_PERCENTILE).toInt()]
        for (line in beside) {
            val reach = ArrayList<Float>()
            for (i in line.members) {
                val d = cloud.u[i] * side.nu + cloud.v[i] * side.nv
                if (d >= side.offset - cell && d <= side.offset + EDGE_BAND_CELLS * cell) reach += d
            }
            if (reach.size < MIN_EDGE_SURFELS) continue
            reach.sort()
            edge = min(edge, reach[(reach.size * EDGE_PERCENTILE).toInt()])
        }
        return Side(null, side.nu, side.nv, edge, floorEdge = true)
    }

    /**
     * Sides left with no length between their corners — a piece of furniture's end met square on
     * the wall behind it — are not walls: dropped, their neighbours met again, one wall when they
     * are one line.
     */
    private fun dropDegenerate(sides: ArrayList<Side>) {
        var changed = true
        while (changed && sides.size > 3) {
            changed = false
            val n = sides.size
            for (i in 0 until n) {
                val prev = sides[(i + n - 1) % n]
                val side = sides[i]
                val next = sides[(i + 1) % n]
                val a = meet(prev, side) ?: continue
                val b = meet(side, next) ?: continue
                // The outline walks each side along (nv, −nu).
                val length = (b[0] - a[0]) * side.nv - (b[1] - a[1]) * side.nu
                if (length >= DEGENERATE_M) continue
                if (prev.parallel(next)) {
                    val same = prev.nu * next.nu + prev.nv * next.nv >= SAME_FACING
                    if (!same || abs(prev.offset - next.offset) > SAME_SIDE_M) continue
                    // One line on both sides of it: one wall.
                    val keep = if (prev.line == null && next.line != null) next else prev
                    val lo = minOf(i, (i + 1) % n)
                    val hi = maxOf(i, (i + 1) % n)
                    sides.removeAt(hi)
                    sides.removeAt(lo)
                    sides[if (i == 0) sides.size - 1 else (i - 1).coerceAtMost(sides.size - 1)] = keep
                } else {
                    sides.removeAt(i)
                }
                changed = true
                break
            }
        }
    }

    private fun mergeEqual(label: ArrayList<Int>, start: ArrayList<Int>, count: ArrayList<Int>) {
        var r = 0
        while (label.size > 1 && r < label.size) {
            val next = (r + 1) % label.size
            if (next != r && label[r] == label[next]) {
                count[r] = count[r] + count[next]
                label.removeAt(next)
                start.removeAt(next)
                count.removeAt(next)
                if (next < r) r--
            } else {
                r++
            }
        }
    }

    /**
     * A deduced [side] from [first] to [last] that closes one of the [open] sides: across it, at
     * the end of the shorter of its two walls, not on the floor's edge — the floor goes on into
     * the next room. `null` when it closes none.
     */
    private fun acrossOpening(side: Side, first: FloatArray, last: FloatArray, open: List<FloatArray>, cell: Float): Side? {
        val mu = (first[0] + last[0]) / 2
        val mv = (first[1] + last[1]) / 2
        for (segment in open) {
            val du = segment[2] - segment[0]
            val dv = segment[3] - segment[1]
            val length = hypot(du, dv)
            if (length <= 0f || abs(du * side.nu + dv * side.nv) / length > WallLine.PARALLEL_SIN) continue
            val f = ((mu - segment[0]) * du + (mv - segment[1]) * dv) / (length * length)
            if (f !in 0f..1f) continue
            val off = abs((mu - segment[0]) * dv - (mv - segment[1]) * du) / length
            if (off > OPEN_NEAR_CELLS * cell) continue
            val offset = max(segment[0] * side.nu + segment[1] * side.nv, segment[2] * side.nu + segment[3] * side.nv)
            return Side(null, side.nu, side.nv, offset)
        }
        return null
    }

    /** The end of [line]'s barrier nearest to [p], when within [ENDS_NEAR_M] of it. */
    private fun nearestEnd(line: WallLine, p: FloatArray): FloatArray? {
        var best: FloatArray? = null
        var bestD = ENDS_NEAR_M
        for (range in line.barrier) {
            for (t in floatArrayOf(range.start, range.endInclusive)) {
                val u = line.pointU(t)
                val v = line.pointV(t)
                val d = hypot(u - p[0], v - p[1])
                if (d <= bestD) {
                    bestD = d
                    best = floatArrayOf(u, v)
                }
            }
        }
        return best
    }

    /**
     * A side no wall was seen along: in the room's direction nearest its chord when one fits, at
     * the outermost of the floor seen there and the ends of the walls beside it ([ends]).
     */
    @Suppress("LongParameterList")
    private fun deduced(loop: FloatArray, start: Int, count: Int, m: Int, lines: List<WallLine>, ends: List<FloatArray>): Side {
        val au = loop[(start % m) * 2]
        val av = loop[(start % m) * 2 + 1]
        val bu = loop[((start + count) % m) * 2]
        val bv = loop[((start + count) % m) * 2 + 1]
        val du = bu - au
        val dv = bv - av
        val len = max(hypot(du, dv), 1e-6f)
        // Inward: the left of the walk.
        var nu = -dv / len
        var nv = du / len
        val chord = WallLines.degreesOf(nu, nv)
        var bestDiff = FAMILY_FIT_DEG
        for (line in lines) {
            val diff = WallLines.angleDiff(chord, WallLines.degreesOf(line.nu, line.nv))
            if (diff < bestDiff) {
                bestDiff = diff
                nu = line.nu
                nv = line.nv
            }
        }
        val squares = floatArrayOf(0f, 90f, 180f, 270f)
        if (bestDiff == FAMILY_FIT_DEG) {
            for (a in squares) {
                val diff = WallLines.angleDiff(chord, a)
                if (diff < bestDiff) {
                    bestDiff = diff
                    val rad = Math.toRadians(a.toDouble())
                    nu = Math.cos(rad).toFloat().let { if (abs(it) < 1e-6f) 0f else it }
                    nv = Math.sin(rad).toFloat().let { if (abs(it) < 1e-6f) 0f else it }
                }
            }
        }
        var offset = outermost(loop, start, count, m, nu, nv)
        for (end in ends) offset = min(offset, end[0] * nu + end[1] * nv)
        return Side(null, nu, nv, offset, floorEdge = true)
    }

    /**
     * The area between the [loop]'s [count] steps from [start] and the deduced [side] standing
     * outside them: floor the side holds that was not seen, up to the walls' ends it reached.
     */
    private fun beyond(loop: FloatArray, start: Int, count: Int, m: Int, side: Side): Float {
        var twice = 0f
        var pu = 0f
        var pv = 0f
        fun to(u: Float, v: Float, k: Int) {
            if (k > 0) twice += pu * v - u * pv
            pu = u
            pv = v
        }
        for (k in 0..count) {
            val e = (start + k) % m
            to(loop[e * 2], loop[e * 2 + 1], k)
        }
        // Back along the side, from the stretch's last point's foot on it to its first point's.
        for (e in intArrayOf((start + count) % m, start % m)) {
            val d = loop[e * 2] * side.nu + loop[e * 2 + 1] * side.nv - side.offset
            to(loop[e * 2] - d * side.nu, loop[e * 2 + 1] - d * side.nv, 1)
        }
        to(loop[(start % m) * 2], loop[(start % m) * 2 + 1], 1)
        return abs(twice) / 2
    }

    /** The outermost of the [loop]'s points [count] steps on from [start] along the inward normal ([nu], [nv]). */
    private fun outermost(loop: FloatArray, start: Int, count: Int, m: Int, nu: Float, nv: Float): Float {
        var offset = Float.POSITIVE_INFINITY
        for (k in 0..count) {
            val e = (start + k) % m
            offset = min(offset, loop[e * 2] * nu + loop[e * 2 + 1] * nv)
        }
        return offset
    }

    /**
     * How many corners a stretch no wall was seen along turns between the walls [before] and
     * [after] it: none between parallel walls — one side bridges them, facing or stepped — one
     * between two that meet, [SAME_LINE_CUTS] back round to the same wall.
     */
    private fun cutsBetween(before: Int, after: Int, lines: List<WallLine>): Int {
        if (before == UNLABELED || after == UNLABELED || before == after) return SAME_LINE_CUTS
        return if (lines[before].parallel(lines[after])) 0 else 1
    }

    /**
     * The stretch of [loop] [count] steps long from [start], cut where it turns a corner: at most
     * [cuts] times, each at the point furthest from its piece's chord when that is more than
     * [TURN_M] off it and leaves both pieces [CORNER_RUN_M] long at least. As `[start, count]`
     * pairs, in order along the loop.
     */
    private fun straightPieces(loop: FloatArray, start: Int, count: Int, m: Int, cuts: Int): List<IntArray> {
        val pieces = arrayListOf(intArrayOf(start, count))
        fun u(e: Int) = loop[(e % m) * 2]
        fun v(e: Int) = loop[(e % m) * 2 + 1]
        var left = cuts
        while (left-- > 0) {
            var best = -1
            var bestK = -1
            var farD = TURN_M
            for ((p, piece) in pieces.withIndex()) {
                val from = piece[0]
                val steps = piece[1]
                val du = u(from + steps) - u(from)
                val dv = v(from + steps) - v(from)
                val len = hypot(du, dv)
                for (k in 1 until steps) {
                    val pu = u(from + k) - u(from)
                    val pv = v(from + k) - v(from)
                    val d = if (len > 1e-6f) abs(pu * dv - pv * du) / len else hypot(pu, pv)
                    if (d <= farD) continue
                    if (hypot(pu, pv) < CORNER_RUN_M) continue
                    if (hypot(u(from + steps) - u(from + k), v(from + steps) - v(from + k)) < CORNER_RUN_M) continue
                    farD = d
                    best = p
                    bestK = k
                }
            }
            if (best < 0) break
            val piece = pieces[best]
            pieces[best] = intArrayOf(piece[0], bestK)
            pieces.add(best + 1, intArrayOf(piece[0] + bestK, piece[1] - bestK))
        }
        return pieces
    }

    /** A deduced side through [at], square to two parallel sides it steps between, facing the room. */
    private fun stepBetween(a: Side, b: Side, at: FloatArray): Side {
        // Square to a, facing the way the walk goes on: from a's level towards b's.
        var nu = -a.nv
        var nv = a.nu
        val toward = (b.offset - a.offset) * (if (a.nu * b.nu + a.nv * b.nv >= 0f) 1f else -1f)
        // Which way along a the walk heads is a's tangent with the room on the left: (nv, −nu) rotated.
        val walkU = a.nv
        val walkV = -a.nu
        // A step inward (towards b further into the room) faces back along the walk, outward faces ahead.
        if ((nu * walkU + nv * walkV) * (if (toward < 0f) -1f else 1f) > 0f) {
            nu = -nu
            nv = -nv
        }
        return Side(null, nu, nv, at[0] * nu + at[1] * nv)
    }

    /** Where two sides' lines cross; `null` when they are parallel. */
    fun meet(a: Side, b: Side): FloatArray? {
        val det = a.nu * b.nv - a.nv * b.nu
        if (abs(det) < 1e-4f) return null
        val u = (a.offset * b.nv - a.nv * b.offset) / det
        val v = (a.nu * b.offset - a.offset * b.nu) / det
        return floatArrayOf(u, v)
    }

    /** A deduced side from ([au], [av]) to ([bu], [bv]), the room on its left. */
    private fun sideThrough(au: Float, av: Float, bu: Float, bv: Float): Side {
        val len = max(hypot(bu - au, bv - av), 1e-6f)
        val nu = -(bv - av) / len
        val nv = (bu - au) / len
        return Side(null, nu, nv, au * nu + av * nv)
    }

    fun signedArea(u: FloatArray, v: FloatArray): Float {
        var sum = 0.0
        for (i in u.indices) {
            val j = (i + 1) % u.size
            sum += u[i].toDouble() * v[j] - u[j].toDouble() * v[i]
        }
        return (sum / 2).toFloat()
    }

    /** No two sides cross but neighbours at their shared corner. */
    fun simple(u: FloatArray, v: FloatArray): Boolean {
        val n = u.size
        for (i in 0 until n) {
            val i2 = (i + 1) % n
            for (j in i + 1 until n) {
                val j2 = (j + 1) % n
                if (j == i2 || i == j2) continue
                if (crosses(u[i], v[i], u[i2], v[i2], u[j], v[j], u[j2], v[j2])) return false
            }
        }
        return true
    }

    @Suppress("LongParameterList")
    private fun crosses(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Boolean {
        fun orient(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float) =
            (qx - px) * (ry - py) - (qy - py) * (rx - px)
        val d1 = orient(cx, cy, dx, dy, ax, ay)
        val d2 = orient(cx, cy, dx, dy, bx, by)
        val d3 = orient(ax, ay, bx, by, cx, cy)
        val d4 = orient(ax, ay, bx, by, dx, dy)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    /** [loop] (`u, v` pairs, closed) simplified to within [tolerance] metres. */
    private fun simplify(loop: FloatArray, tolerance: Float): FloatArray {
        val n = loop.size / 2
        if (n < 4) return loop
        // Split at the two points furthest apart, simplify each half.
        var far = 0
        var farD = 0f
        for (i in 1 until n) {
            val d = hypot(loop[i * 2] - loop[0], loop[i * 2 + 1] - loop[1])
            if (d > farD) {
                farD = d
                far = i
            }
        }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[far] = true
        fun dp(from: Int, to: Int) {
            if (to - from < 2) return
            val ax = loop[from * 2]
            val ay = loop[from * 2 + 1]
            val bx = loop[(to % n) * 2]
            val by = loop[(to % n) * 2 + 1]
            val len = max(hypot(bx - ax, by - ay), 1e-6f)
            var worst = -1
            var worstD = tolerance
            for (i in from + 1 until to) {
                val px = loop[(i % n) * 2]
                val py = loop[(i % n) * 2 + 1]
                val d = abs((bx - ax) * (ay - py) - (ax - px) * (by - ay)) / len
                if (d > worstD) {
                    worstD = d
                    worst = i
                }
            }
            if (worst < 0) return
            keep[worst % n] = true
            dp(from, worst)
            dp(worst, to)
        }
        dp(0, far)
        dp(far, n)
        val out = ArrayList<Float>()
        for (i in 0 until n) if (keep[i]) {
            out += loop[i * 2]
            out += loop[i * 2 + 1]
        }
        return out.toFloatArray()
    }

    private const val HALF = 0.5f
}
