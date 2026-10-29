package io.github.sceneview.demo.demos.internal.room

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Reads a [RoomStructure] from a scan: the floor, the room's turn, the wall lines, the outline
 * they close, then each wall's openings and the figures. Same scan, same structure: nothing here
 * is random, and nothing depends on the cloud's order beyond floating-point sums over it.
 */
internal object RoomReader {
    /** The cell the full reading is done on: walls, outline and openings. */
    const val CELL_M = 0.05f

    /** The coarser cell of the live reading. */
    const val LIVE_CELL_M = 0.1f

    /** The live reading reads at most this many of the cloud's surfels. */
    const val LIVE_MAX_SURFELS = 20_000

    private const val MIN_SUPPORT = 30
    private const val LIVE_MIN_SUPPORT = 15
    private const val UNSEEN_RUN_M = 0.3f
    private const val PLANE_STEP_M = 0.1f
    private const val PLANE_RESERVE = 8_192

    /**
     * The full reading adds a wall plane where the cloud saw less than this share of it, on
     * [HINT_CELL_M] cells: the depth's surfels within [HINT_NEAR_M] of the plane are what it saw.
     */
    private const val HINT_SEEN_SHARE = 0.5f
    private const val HINT_CELL_M = 0.1f
    private const val HINT_NEAR_M = 0.1f
    private const val DOWN = -0.8f
    private const val CELL_KEY_SHIFT = 16_384
    private const val CELL_KEY_SPAN = 32_768
    private const val HALF = 0.5f
    private const val NANOS_PER_MILLI = 1e6f
    private const val RUN_SIN = 0.05f
    private const val RUN_M = 0.3f

    /**
     * Parallel planes closer than this, facing the same way, are one wall: a scan that drifted
     * draws a wall twice, two passes each seeing half of it as far apart as two layers of one wall.
     */
    private const val SAME_WALL_M = WallLines.WALL_MERGE_M
    private const val SAME_WALL_FACING = 0.94f

    /** A wall seen this near a corner reaches it: a side deduced there is held by it. */
    private const val CORNER_REACH_M = 0.3f

    /**
     * Nothing is read farther than this from the scan's origin: a stray value — a fusion's
     * overflow, a pose gone wild — would otherwise size a histogram or a raster.
     */
    const val MAX_COORD_M = 1000f

    /** An ARCore plane wider than this along either axis is no plane of a room: it is not sampled. */
    const val MAX_PLANE_M = 50f

    fun read(scan: RoomScan): RoomStructure {
        val normals = scan.normals
        val count = scan.positions.size / 3
        if (normals == null || normals.size < count * 3 || count < RoomLevels.MIN_SURFELS) {
            return RoomStructure.empty(RoomGap.NoDepth)
        }
        val (positions, sound) = soundCloud(scan.positions, normals)
        if (positions.size / 3 < RoomLevels.MIN_SURFELS) return RoomStructure.empty(RoomGap.NoDepth)
        val trail = soundTrail(scan.trail)
        val (p, n) = withWallPlanes(positions, sound, scan.planes, trail)
        return readCloud(p, n, trail, CELL_M, MIN_SUPPORT, full = true) ?: RoomStructure.empty(RoomGap.NoWalls)
    }

    /**
     * The cloud with ARCore's wall planes it saw less than [HINT_SEEN_SHARE] of added as surfels,
     * on a [CELL_M] grid where it saw nothing of them: a wall the depth missed, or saw only in
     * scraps, still stands where ARCore found it. A wall the depth saw is read from the depth
     * alone, its doorways and windows with it.
     */
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod")
    private fun withWallPlanes(
        positions: FloatArray,
        normals: FloatArray,
        planes: List<RoomPlane>,
        trail: FloatArray,
    ): Pair<FloatArray, FloatArray> {
        val walls = planes.filter { it.kind == RoomPlaneKind.Wall && usable(it.polygon) }
        if (walls.isEmpty()) return positions to normals
        val count = positions.size / 3
        val vertical = IntArray(count)
        var verticals = 0
        for (i in 0 until count) if (abs(normals[i * 3 + 1]) <= RoomLevels.VERTICAL) vertical[verticals++] = i
        val centre = centreOf(trail)
        var out: Pair<Floats, Floats>? = null
        for (plane in walls) {
            val wall = wallOf(plane.polygon, centre) ?: continue
            val t0 = wall.t.min()
            val y0 = wall.y.min()
            val cols = ceil((wall.t.max() - t0) / HINT_CELL_M).toInt() + 1
            val rows = ceil((wall.y.max() - y0) / HINT_CELL_M).toInt() + 1
            val seen = BooleanArray(cols * rows)
            for (k in 0 until verticals) {
                val i = vertical[k]
                val x = positions[i * 3]
                val z = positions[i * 3 + 2]
                if (abs(x * wall.ux + z * wall.uz - wall.o) > HINT_NEAR_M) continue
                val c = floor((x * -wall.uz + z * wall.ux - t0) / HINT_CELL_M).toInt()
                val r = floor((positions[i * 3 + 1] - y0) / HINT_CELL_M).toInt()
                if (c in 0 until cols && r in 0 until rows) seen[c * rows + r] = true
            }
            fun seenAt(t: Float, y: Float) =
                seen[floor((t - t0) / HINT_CELL_M).toInt() * rows + floor((y - y0) / HINT_CELL_M).toInt()]
            var cells = 0
            var covered = 0
            fill(wall.t, wall.y, HINT_CELL_M) { t, y ->
                cells++
                if (seenAt(t, y)) covered++
            }
            if (cells == 0 || covered >= cells * HINT_SEEN_SHARE) continue
            val (p, n) = out ?: (Floats(positions.size + PLANE_RESERVE) to Floats(normals.size + PLANE_RESERVE)).also {
                it.first.thinned(positions, 1, count)
                it.second.thinned(normals, 1, count)
                out = it
            }
            fill(wall.t, wall.y, CELL_M) { t, y ->
                if (!seenAt(t, y)) wall.emit(t, y, p, n)
            }
        }
        val added = out ?: return positions to normals
        return added.first.toArray() to added.second.toArray()
    }

    /**
     * The cloud without its broken surfels: a coordinate not finite or beyond [MAX_COORD_M], a
     * normal not finite. The arrays themselves when every surfel is sound, as a phone's always are.
     */
    fun soundCloud(positions: FloatArray, normals: FloatArray): Pair<FloatArray, FloatArray> {
        val count = positions.size / 3
        var broken = 0
        for (i in 0 until count) if (!sound(positions, normals, i)) broken++
        if (broken == 0) return positions to normals
        val p = FloatArray((count - broken) * 3)
        val n = FloatArray(p.size)
        var k = 0
        for (i in 0 until count) {
            if (!sound(positions, normals, i)) continue
            positions.copyInto(p, k, i * 3, i * 3 + 3)
            normals.copyInto(n, k, i * 3, i * 3 + 3)
            k += 3
        }
        return p to n
    }

    /** The camera's path without its broken steps. */
    fun soundTrail(trail: FloatArray): FloatArray {
        val steps = trail.size / 3
        val kept = (0 until steps).filter { within(trail, it * 3, 3) }
        if (kept.size == steps) return trail
        val out = FloatArray(kept.size * 3)
        for ((k, i) in kept.withIndex()) trail.copyInto(out, k * 3, i * 3, i * 3 + 3)
        return out
    }

    private fun sound(positions: FloatArray, normals: FloatArray, i: Int): Boolean =
        within(positions, i * 3, 3) && normals[i * 3].isFinite() && normals[i * 3 + 1].isFinite() && normals[i * 3 + 2].isFinite()

    /** [count] values from [from] all finite and within [MAX_COORD_M]: NaN fails the comparison. */
    private fun within(values: FloatArray, from: Int, count: Int): Boolean {
        for (k in from until from + count) if (!(abs(values[k]) <= MAX_COORD_M)) return false
        return true
    }

    /** The cheap reading: the cloud thinned to [LIVE_MAX_SURFELS], ARCore's planes added, read on [LIVE_CELL_M] cells. */
    fun live(scan: RoomScan): LiveRoom {
        val started = System.nanoTime()
        val trail = soundTrail(scan.trail)
        val cloudNormals = scan.normals
        val count = scan.positions.size / 3
        val usable = cloudNormals != null && cloudNormals.size >= count * 3 && count > 0
        val stride = max(1, ceil(count.toFloat() / LIVE_MAX_SURFELS).toInt())
        val kept = if (usable) (count + stride - 1) / stride else 0
        val positions = Floats(kept * 3 + PLANE_RESERVE)
        val normals = Floats(kept * 3 + PLANE_RESERVE)
        if (usable) positions.thinned(scan.positions, stride, kept)
        if (usable) normals.thinned(cloudNormals!!, stride, kept)
        planeSamples(scan.planes, trail, positions, normals)
        val (p, n) = soundCloud(positions.toArray(), normals.toArray())
        // No floor found: no floor height either, rather than the scan's origin.
        val read = if (p.size / 3 < RoomLevels.MIN_SURFELS) {
            null
        } else {
            readCloud(p, n, trail, LIVE_CELL_M, LIVE_MIN_SUPPORT, full = false)
        }
        val structure = read ?: RoomStructure.empty(RoomGap.NoDepth)
        val walls = structure.walls.filter { !it.deduced }.map {
            LiveRoomWall(it.ax, it.az, it.bx, it.bz, it.height, it.confidence)
        }
        val outline = FloatArray(structure.walls.size * 2)
        for ((k, w) in structure.walls.withIndex()) {
            outline[k * 2] = w.ax
            outline[k * 2 + 1] = w.az
        }
        val floorY = read?.floorY
        val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
        return LiveRoom(walls, outline, structure.area, floorY, millis)
    }

    /** ARCore's wall and floor planes as surfels on a [PLANE_STEP_M] grid, walls facing the camera's path. */
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod")
    private fun planeSamples(planes: List<RoomPlane>, trail: FloatArray, positions: Floats, normals: Floats) {
        val centre = centreOf(trail)
        for (plane in planes) {
            val poly = plane.polygon
            if (!usable(poly)) continue
            val m = poly.size / 3
            when (plane.kind) {
                RoomPlaneKind.Wall -> {
                    val wall = wallOf(poly, centre) ?: continue
                    fill(wall.t, wall.y, PLANE_STEP_M) { t, y -> wall.emit(t, y, positions, normals) }
                }
                RoomPlaneKind.Floor -> {
                    val xa = FloatArray(m) { poly[it * 3] }
                    val za = FloatArray(m) { poly[it * 3 + 2] }
                    val y = (0 until m).map { poly[it * 3 + 1] }.average().toFloat()
                    fill(xa, za, PLANE_STEP_M) { x, z ->
                        positions += x
                        positions += y
                        positions += z
                        normals += 0f
                        normals += 1f
                        normals += 0f
                    }
                }
                else -> Unit
            }
        }
    }

    /** A polygon of three corners or more, all sound, under [MAX_PLANE_M] along every axis. */
    private fun usable(poly: FloatArray): Boolean {
        val m = poly.size / 3
        return m >= 3 && within(poly, 0, m * 3) && narrow(poly, 0) && narrow(poly, 1) && narrow(poly, 2)
    }

    /** Where the camera went on average, `x, z`; `null` without a trail. */
    private fun centreOf(trail: FloatArray): DoubleArray? {
        val steps = trail.size / 3
        if (steps == 0) return null
        var cx = 0.0
        var cz = 0.0
        for (k in 0 until steps) {
            cx += trail[k * 3]
            cz += trail[k * 3 + 2]
        }
        return doubleArrayOf(cx / steps, cz / steps)
    }

    /**
     * A wall plane in its own frame: Newell's normal levelled, turned towards the camera's
     * [centre]; its corners along it (−uz, ux) and up. `null` for a plane lying flat.
     */
    private fun wallOf(poly: FloatArray, centre: DoubleArray?): WallPlane? {
        val m = poly.size / 3
        var nx = 0.0
        var nz = 0.0
        var mx = 0.0
        var mz = 0.0
        for (i in 0 until m) {
            val j = (i + 1) % m
            nx += (poly[i * 3 + 1] - poly[j * 3 + 1]).toDouble() * (poly[i * 3 + 2] + poly[j * 3 + 2])
            nz += (poly[i * 3] - poly[j * 3]).toDouble() * (poly[i * 3 + 1] + poly[j * 3 + 1])
            mx += poly[i * 3]
            mz += poly[i * 3 + 2]
        }
        val len = hypot(nx, nz)
        if (len < 1e-6) return null
        var ux = (nx / len).toFloat()
        var uz = (nz / len).toFloat()
        if (centre != null && (centre[0] - mx / m) * ux + (centre[1] - mz / m) * uz < 0) {
            ux = -ux
            uz = -uz
        }
        val t = FloatArray(m) { poly[it * 3] * -uz + poly[it * 3 + 2] * ux }
        val y = FloatArray(m) { poly[it * 3 + 1] }
        return WallPlane(ux, uz, (mx / m * ux + mz / m * uz).toFloat(), t, y)
    }

    /** The polygon's extent along axis [axis] is under [MAX_PLANE_M]. */
    private fun narrow(poly: FloatArray, axis: Int): Boolean {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (k in axis until poly.size step 3) {
            lo = min(lo, poly[k])
            hi = max(hi, poly[k])
        }
        return hi - lo <= MAX_PLANE_M
    }

    /** Calls [emit] on a [step] grid inside the polygon ([a], [b]). */
    private inline fun fill(a: FloatArray, b: FloatArray, step: Float, emit: (Float, Float) -> Unit) {
        val a0 = a.min()
        val a1 = a.max()
        val b0 = b.min()
        val b1 = b.max()
        var p = floor(a0 / step) * step + step / 2
        while (p < a1) {
            var q = floor(b0 / step) * step + step / 2
            while (q < b1) {
                if (inside(a, b, p, q)) emit(p, q)
                q += step
            }
            p += step
        }
    }

    private fun inside(a: FloatArray, b: FloatArray, p: Float, q: Float): Boolean {
        var hit = false
        var j = a.size - 1
        for (i in a.indices) {
            if ((b[i] > q) != (b[j] > q) && p < (a[j] - a[i]) * (q - b[i]) / (b[j] - b[i]) + a[i]) hit = !hit
            j = i
        }
        return hit
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount", "LongParameterList")
    private fun readCloud(
        positions: FloatArray,
        normals: FloatArray,
        trail: FloatArray,
        cell: Float,
        minSupport: Int,
        full: Boolean,
    ): RoomStructure? {
        val floor = RoomLevels.floorOf(positions, normals, trail) ?: return null
        val yaw = RoomLevels.yawOf(positions, normals, floor.y)
        val c = cos(yaw)
        val s = sin(yaw)
        val cloud = FrameCloud.of(positions, normals, floor.y, yaw)
        val steps = trail.size / 3
        val trailU = FloatArray(steps) { c * trail[it * 3] + s * trail[it * 3 + 2] }
        val trailV = FloatArray(steps) { -s * trail[it * 3] + c * trail[it * 3 + 2] }
        // The ceiling's surfels, each over a cell of the plan.
        val ceilingH = FloatArray(cloud.count)
        val ceilingCell = IntArray(cloud.count)
        var ceilings = 0
        if (full) {
            for (i in 0 until cloud.count) {
                val h = cloud.h[i]
                if (cloud.ny[i] > DOWN || h < RoomLevels.MIN_CEILING_M || h > RoomLevels.CEILING_MAX_M) continue
                ceilingH[ceilings] = h
                ceilingCell[ceilings] = cellKey(cloud.u[i], cloud.v[i], cell)
                ceilings++
            }
        }
        fun ceiling(inside: (Float, Float) -> Boolean): RoomFigure? = if (!full) {
            null
        } else {
            RoomLevels.ceilingOf(ceilingH, ceilingCell, ceilings, cell * cell, floor) { key ->
                inside(keyU(key, cell), keyV(key, cell))
            }
        }
        val lines = WallLines.of(cloud, cell, minSupport)
        if (lines.isEmpty()) {
            return RoomStructure.empty(RoomGap.NoWalls, floor.y, floor.thickness, ceiling { _, _ -> true })
        }
        WallLines.connect(lines)
        val members = BooleanArray(cloud.count)
        for (line in lines) for (i in line.members) members[i] = true
        val outline = RoomOutline.of(cloud, lines, trailU, trailV, floor.thickness, cell, members)
            ?: return RoomStructure.empty(RoomGap.NoWalls, floor.y, floor.thickness, ceiling { _, _ -> true })
        val raster = outline.raster
        val height = if (full) {
            ceiling { u, v ->
                val k = raster.index(u, v)
                k >= 0 && outline.region[k]
            }
        } else {
            null
        }
        val n = outline.sides.size
        val seenTops = outline.sides.mapNotNull { it.line?.top }.sorted()
        val wallHeight = height?.value ?: if (seenTops.isEmpty()) 0f else seenTops[seenTops.size / 2]
        class Read(
            val length: Float,
            val share: Float,
            val unseen: List<ClosedFloatingPointRange<Float>>,
            val openings: List<RoomOpening>,
        )
        val reads = (0 until n).map { i ->
            val next = (i + 1) % n
            val side = outline.sides[i]
            val au = outline.u[i]
            val av = outline.v[i]
            val bu = outline.u[next]
            val bv = outline.v[next]
            val length = hypot(bu - au, bv - av)
            val line = side.line ?: return@map Read(length, 0f, listOf(0f..length), emptyList())
            val ta = line.tOf(au, av)
            val tb = line.tOf(bu, bv)
            // Openings: their place counted from the wall's start, whichever way the line runs.
            val found = if (full) WallOpenings.of(cloud, line, min(ta, tb), max(ta, tb), wallHeight) else emptyList()
            val openings = found.map { o ->
                val from = if (ta >= tb) ta - o.t1 else o.t0 - ta
                RoomOpening(o.kind, from, o.t1 - o.t0, o.bottom, o.top - o.bottom, o.confidence)
            }
            val samples = max(1, (length / CELL_M + HALF).toInt())
            var hit = 0
            val runs = ArrayList<ClosedFloatingPointRange<Float>>()
            var runFrom = -1
            for (k in 0..samples) {
                val at = (k + HALF) * length / samples
                var seen = false
                if (k < samples) {
                    val t = ta + (tb - ta) * (k + HALF) / samples
                    seen = line.seenAt(t) || openings.any { at >= it.from && at <= it.from + it.width }
                    if (seen) hit++
                    if (!seen && runFrom < 0) runFrom = k
                }
                if ((seen || k == samples) && runFrom >= 0) {
                    if ((k - runFrom) * length / samples >= UNSEEN_RUN_M) {
                        runs += (runFrom * length / samples)..min(length, k * length / samples)
                    }
                    runFrom = -1
                }
            }
            Read(length, hit.toFloat() / samples, runs, openings)
        }
        val seen = BooleanArray(n) { outline.sides[it].line != null && reads[it].share >= RoomStructure.MIN_SEEN_SHARE }
        val drawn = (0 until n).any {
            seen[it] && reads[it].length * reads[it].share >= RoomStructure.MIN_SEEN_SHARE * runOf(outline.u, outline.v, it)
        }
        if (!drawn) {
            // An outline no wall was seen along is only the floor's extent: not drawn. One wall
            // seen along half the straight run of outline it stands on draws the plan — a stub of
            // wall on a floor's edge does not; the figures wait for [RoomStructure.MIN_SEEN_WALLS].
            return RoomStructure.empty(RoomGap.NoWalls, floor.y, floor.thickness, height)
        }
        // No figure is given tighter than the thinnest layer a cloud is read to.
        val spread = FloatArray(n) { if (seen[it]) max(outline.sides[it].line!!.spread, RoomLevels.MIN_SPREAD_M) else 0f }
        val walls = (0 until n).map { i ->
            val next = (i + 1) % n
            val before = (i + n - 1) % n
            val side = outline.sides[i]
            val read = reads[i]
            RoomWall(
                ax = c * outline.u[i] - s * outline.v[i],
                az = s * outline.u[i] + c * outline.v[i],
                bx = c * outline.u[next] - s * outline.v[next],
                bz = s * outline.u[next] + c * outline.v[next],
                nx = c * side.nu - s * side.nv,
                nz = s * side.nu + c * side.nv,
                // A wall runs from one neighbour to the other: it is measured when all three were seen.
                length = RoomFigure.of(
                    read.length, spread[before] + spread[next], !seen[i] || !seen[before] || !seen[next],
                ),
                seenShare = read.share,
                seen = seen[i],
                deduced = side.line == null,
                unseen = if (seen[i]) read.unseen else listOf(0f..read.length),
                height = side.line?.top ?: wallHeight,
                openings = read.openings,
            )
        }
        val rough = seen.any { !it }
        // A side no wall was seen along stands where the floor seen ended: it holds the room only
        // where the walls beside it were seen up to it, each within [CORNER_REACH_M] of the corner
        // it makes with it. A scan that stopped short leaves them short of it.
        val held = BooleanArray(n) { i ->
            val next = (i + 1) % n
            outline.sides[i].line != null ||
                reaches(outline.sides[(i + n - 1) % n].line, outline.u[i], outline.v[i]) &&
                reaches(outline.sides[next].line, outline.u[next], outline.v[next])
        }
        // A side the floor's edge stands for is the room's only where each seen wall beside it
        // ends there too: seen up to the corner and not on past it. A wall seen on past the corner
        // was cut short by a floor seen short of it: the room goes on, and an area read there is
        // wrong, not estimated — the scan has to follow the wall to its end.
        val ended = BooleanArray(n) { i ->
            val side = outline.sides[i]
            val before = (i + n - 1) % n
            val next = (i + 1) % n
            val after = (i + 2) % n
            side.line != null || !side.floorEdge ||
                (!seen[before] || endsAt(outline.sides[before].line, i, before, outline)) &&
                (!seen[next] || endsAt(outline.sides[next].line, next, after, outline))
        }
        // The figures need the room closed by walls: each side seen, or a run of unseen ones
        // between two seen — one side held by both, or two the floor's edge stands for meeting at
        // a corner deduced between them, each wall ending where its run starts — and at least
        // [RoomStructure.MIN_SEEN_WALLS] distinct walls, one wall seen twice counting once.
        val closed = closedBy(outline, seen, held, ended)
        val distinct = distinctWalls(outline.sides, seen)
        val measured = closed && distinct >= RoomStructure.MIN_SEEN_WALLS
        // Two adjacent walls alone, each seen from the corner they make to its other end, give the
        // area, the perimeter and the volume as estimates — the floor seen standing for the other
        // two; two facing ones give none, only the width between them.
        val cornered = !measured && closed && distinct == 2 && (0 until n).any { i ->
            val a = outline.sides[(i + n - 1) % n].line
            val b = outline.sides[i].line
            seen[(i + n - 1) % n] && seen[i] && a != null && b != null &&
                abs(a.nu * b.nu + a.nv * b.nv) < SAME_WALL_FACING &&
                reaches(a, outline.u[i], outline.v[i]) && reaches(b, outline.u[i], outline.v[i])
        }
        val figured = measured || cornered
        val area = abs(RoomOutline.signedArea(outline.u, outline.v))
        var areaSpread = 0f
        for (i in 0 until n) areaSpread += reads[i].length * spread[i]
        val perimeter = RoomFigure.of(
            walls.sumOf { it.length.value.toDouble() }.toFloat(),
            walls.sumOf { it.length.plusMinus.toDouble() }.toFloat(),
            rough,
        )
        val areaFigure = RoomFigure.of(area, areaSpread, rough)
        val alongU = extentOf(outline.u, seen, spread)
        val alongV = extentOf(outline.v, seen, spread)
        val turned = alongV.value > alongU.value
        val total = reads.sumOf { it.length.toDouble() }.toFloat()
        var seenLength = 0f
        for (i in 0 until n) if (outline.sides[i].line != null) seenLength += reads[i].length * reads[i].share
        return RoomStructure(
            walls = walls,
            floorY = floor.y,
            floorThickness = floor.thickness,
            yaw = yaw,
            ceilingHeight = height,
            area = areaFigure.takeIf { figured },
            perimeter = perimeter.takeIf { figured },
            volume = volumeOf(areaFigure, height).takeIf { figured },
            // Between two seen walls a length is measured, closed room or not; an estimate is not given.
            length = (if (turned) alongV else alongU).takeIf { measured || !it.estimate },
            width = (if (turned) alongU else alongV).takeIf { measured || !it.estimate },
            confidence = if (total > 0f) seenLength / total else 0f,
            gaps = listOfNotNull(
                RoomGap.WallEnd.takeIf { !ended.all { it } },
                RoomGap.MissingWall.takeIf { rough },
                RoomGap.Ceiling.takeIf { height == null },
            ),
            reading = RoomReading(outline.traced, closed, distinct, outline.miss, outline.regionArea, outline.tracedArea),
        )
    }

    /**
     * Whether the runs of sides no wall was seen along each lie between two seen sides: a run of
     * one [held] by both, or of two the floor's edge stands for, not parallel, both [ended].
     */
    private fun closedBy(outline: Outline, seen: BooleanArray, held: BooleanArray, ended: BooleanArray): Boolean {
        val n = outline.sides.size
        val first = (0 until n).firstOrNull { seen[it] } ?: return false
        var k = 1
        while (k <= n) {
            val i = (first + k) % n
            if (seen[i]) {
                k++
                continue
            }
            var count = 0
            while (!seen[(i + count) % n]) count++
            val a = outline.sides[i]
            val b = outline.sides[(i + 1) % n]
            val shut = when (count) {
                1 -> held[i] && ended[i]
                2 -> a.floorEdge && b.floorEdge && ended[i] && ended[(i + 1) % n] &&
                    abs(a.nu * b.nu + a.nv * b.nv) < SAME_WALL_FACING
                else -> false
            }
            if (!shut) return false
            k += count
        }
        return true
    }

    /**
     * Whether the wall [line] of side [along] ends at the corner [corner] it makes with an unseen
     * side: seen within [CORNER_REACH_M] of it, and not seen on past it, between [CORNER_REACH_M]
     * and twice that beyond it — away from the side's other corner, [other].
     */
    private fun endsAt(line: WallLine?, corner: Int, other: Int, outline: Outline): Boolean {
        if (line == null || !reaches(line, outline.u[corner], outline.v[corner])) return false
        val t = line.tOf(outline.u[corner], outline.v[corner])
        val away = if (t >= line.tOf(outline.u[other], outline.v[other])) 1f else -1f
        var at = CORNER_REACH_M + line.column * HALF
        while (at <= 2 * CORNER_REACH_M) {
            if (line.seenAt(t + away * at)) return false
            at += line.column * HALF
        }
        return true
    }

    /** Whether [line] was seen within [CORNER_REACH_M] of the corner at ([u], [v]). */
    private fun reaches(line: WallLine?, u: Float, v: Float): Boolean {
        if (line == null) return false
        val t = line.tOf(u, v)
        var at = t - CORNER_REACH_M
        while (at <= t + CORNER_REACH_M) {
            if (line.seenAt(at)) return true
            at += line.column * HALF
        }
        return false
    }

    /** How many distinct walls the [seen] sides stand on: two sides on one line, or on parallel lines closer than [SAME_WALL_M], are one. */
    private fun distinctWalls(sides: List<Side>, seen: BooleanArray): Int {
        val walls = ArrayList<WallLine>()
        for (i in sides.indices) {
            val line = sides[i].line ?: continue
            if (!seen[i]) continue
            val known = walls.any {
                it === line || it.nu * line.nu + it.nv * line.nv >= SAME_WALL_FACING && abs(it.offset - line.offset) < SAME_WALL_M
            }
            if (!known) walls += line
        }
        return walls.size
    }

    /**
     * The length of the straight run of outline side [i] lies on: it and the neighbouring sides
     * that carry on along the same line, within [RUN_SIN] of its direction and [RUN_M] of it.
     */
    private fun runOf(u: FloatArray, v: FloatArray, i: Int): Float {
        val n = u.size
        fun length(k: Int) = hypot(u[(k + 1) % n] - u[k], v[(k + 1) % n] - v[k])
        val len = length(i)
        if (len <= 0f) return 0f
        val tu = (u[(i + 1) % n] - u[i]) / len
        val tv = (v[(i + 1) % n] - v[i]) / len
        fun onLine(k: Int): Boolean {
            val l = length(k)
            if (l <= 0f) return true
            val du = (u[(k + 1) % n] - u[k]) / l
            val dv = (v[(k + 1) % n] - v[k]) / l
            val from = abs((u[k] - u[i]) * tv - (v[k] - v[i]) * tu)
            val to = abs((u[(k + 1) % n] - u[i]) * tv - (v[(k + 1) % n] - v[i]) * tu)
            val far = max(from, to)
            return du * tu + dv * tv > 0f && abs(du * tv - dv * tu) <= RUN_SIN && far <= RUN_M
        }
        var run = len
        var k = (i + 1) % n
        var steps = 0
        while (k != i && steps < n && onLine(k)) {
            run += length(k)
            k = (k + 1) % n
            steps++
        }
        var j = (i + n - 1) % n
        while (j != k && j != i && steps < n && onLine(j)) {
            run += length(j)
            j = (j + n - 1) % n
            steps++
        }
        return run
    }

    /** The volume under the ceiling: the area times the height, each one's scatter carried. */
    fun volumeOf(area: RoomFigure?, height: RoomFigure?): RoomFigure? {
        if (area == null || height == null) return null
        return RoomFigure.of(
            area.value * height.value,
            area.value * height.plusMinus + height.value * area.plusMinus,
            area.estimate || height.estimate,
        )
    }

    /**
     * The outline's extent along one axis of the room's frame ([coords] per corner): measured when
     * every side at its two ends was seen, its scatter the widest of theirs at each end.
     */
    private fun extentOf(coords: FloatArray, seen: BooleanArray, spread: FloatArray): RoomFigure {
        val n = coords.size
        val lo = coords.min()
        val hi = coords.max()
        var estimate = false
        var spreadLo = 0f
        var spreadHi = 0f
        fun at(i: Int, edge: Float) = abs(coords[i] - edge) < EDGE_M
        // The sides that lie on an extreme hold it; where none does — a corner — the two that meet there.
        val lyingLo = (0 until n).any { at(it, lo) && at((it + 1) % n, lo) }
        val lyingHi = (0 until n).any { at(it, hi) && at((it + 1) % n, hi) }
        for (i in 0 until n) {
            val next = (i + 1) % n
            val atLo = if (lyingLo) at(i, lo) && at(next, lo) else at(i, lo) || at(next, lo)
            val atHi = if (lyingHi) at(i, hi) && at(next, hi) else at(i, hi) || at(next, hi)
            if (!atLo && !atHi) continue
            if (!seen[i]) estimate = true
            if (atLo) spreadLo = max(spreadLo, spread[i])
            if (atHi) spreadHi = max(spreadHi, spread[i])
        }
        return RoomFigure.of(hi - lo, spreadLo + spreadHi, estimate)
    }

    private fun cellKey(u: Float, v: Float, cell: Float): Int {
        val i = (floor(u / cell).toInt() + CELL_KEY_SHIFT).coerceIn(0, CELL_KEY_SPAN - 1)
        val j = (floor(v / cell).toInt() + CELL_KEY_SHIFT).coerceIn(0, CELL_KEY_SPAN - 1)
        return i * CELL_KEY_SPAN + j
    }

    private fun keyU(key: Int, cell: Float): Float = (key / CELL_KEY_SPAN - CELL_KEY_SHIFT + HALF) * cell
    private fun keyV(key: Int, cell: Float): Float = (key % CELL_KEY_SPAN - CELL_KEY_SHIFT + HALF) * cell

    private const val EDGE_M = 0.01f
}

/** A wall plane in its own frame: normal ([ux], [uz]) at [o] along it, corners [t] along the wall and [y] up. */
private class WallPlane(val ux: Float, val uz: Float, val o: Float, val t: FloatArray, val y: FloatArray) {
    /** Adds the surfel at [t] along the wall, [y] up, facing the camera. */
    fun emit(t: Float, y: Float, positions: Floats, normals: Floats) {
        positions += o * ux + t * -uz
        positions += y
        positions += o * uz + t * ux
        normals += ux
        normals += 0f
        normals += uz
    }
}

/** A growing float array: the live reading's cloud, unboxed. */
internal class Floats(capacity: Int) {
    private var data = FloatArray(max(capacity, 1))
    var size = 0
        private set

    operator fun plusAssign(value: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun toArray(): FloatArray = data.copyOf(size)

    /** Appends every [stride]th of [source]'s `x, y, z` triples, [kept] of them. */
    fun thinned(source: FloatArray, stride: Int, kept: Int) {
        if (size + kept * 3 > data.size) data = data.copyOf(size + kept * 3)
        for (k in 0 until kept) {
            val from = k * stride * 3
            data[size++] = source[from]
            data[size++] = source[from + 1]
            data[size++] = source[from + 2]
        }
    }
}

