package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The inputs of [RescanZones] read off what a saved scan has today (#4736): its dense cloud, the
 * room survey drawn from it, and the ground under its model.
 *
 * - **Walls** come from [RoomSurvey]: a wall it did not see is inferred, and a seen wall keeps its
 *   unseen stretches. The survey draws its walls on the page (longer side along x, top-left corner
 *   at the origin); [walls] lays them back into the scan frame by fitting the page's wall lines to
 *   the cloud's wall surfels, along the survey's own axes.
 * - **Holes** are the connected pieces of the floor [ScanGround] found missing, each as its
 *   bounding rectangle with its true area.
 * - **Coverage** is the cloud's surface over each [COVERAGE_CELL_M] cell, from just under the
 *   floor to [COVERAGE_TOP_M] (or [COVERAGE_TOP_SHARE] of the room's height), so a ceiling never
 *   counts as floor seen. A surfel weighs its area: `4^level` finest voxels.
 *
 * Every function is pure arithmetic: off the main thread.
 */
object RescanSources {
    /** A coverage cell's side, metres. */
    const val COVERAGE_CELL_M = 0.25f

    /** How far under the floor the coverage starts, metres: the floor's own scatter. */
    const val COVERAGE_FLOOR_BAND_M = 0.1f

    /** The highest the coverage reads above the floor, metres. */
    const val COVERAGE_TOP_M = 2.0f

    /** …and at most this share of the room's height, so a low ceiling stays out. */
    const val COVERAGE_TOP_SHARE = 0.8f

    /** The most cells a coverage grid has on a side: a stray point does not make it huge. */
    const val MAX_COVERAGE_CELLS = 400

    /** How closely a surfel's normal follows a survey axis to be read as a wall's, |cos|. */
    const val WALL_NORMAL = 0.9f

    /** The page fit's histogram bin, metres. */
    const val FIT_BIN_M = 0.01f

    /** The page fit's histogram smoothing, bins on each side. */
    const val FIT_SMOOTH_BINS = 2

    /** How far from a wall a wall surfel may lie to count as seeing it, metres. */
    const val WALL_REACH_M = 0.1f

    /** The step along a wall the page fit compares sightings on, metres. */
    const val WALL_STEP_M = 0.1f

    /** The band of a room's height wall surfels are read in, from the floor, metres. */
    const val WALL_LOW_M = 0.2f

    /** The zones of a scan whose dense points are [cloud] and whose model stands on [ground]. */
    fun zones(cloud: DenseCloud, ground: ScanGround?): List<RescanZone> {
        val normals = cloud.normals
        val survey = normals?.let { RoomSurveyor.survey(RoomSurveyInput(cloud.positions, it)) }
            ?.takeIf { it.walls.isNotEmpty() }
        val floorY = survey?.floorY ?: ground?.floorY ?: lowest(cloud) ?: return emptyList()
        val height = survey?.ceilingHeight?.value ?: RescanZones.DEFAULT_HEIGHT_M
        val walls = if (survey != null && normals != null) walls(survey, cloud.positions, normals, cloud.count) else emptyList()
        return RescanZones.find(
            RescanInput(
                floorY = floorY,
                height = height,
                walls = walls,
                holes = ground?.let { holes(it) } ?: emptyList(),
                coverage = coverage(cloud, floorY, height),
            ),
        )
    }

    private fun lowest(cloud: DenseCloud): Float? = if (cloud.count == 0) null else cloud.bounds()[1]

    /**
     * [survey]'s walls in the scan frame. The page is the survey's (u, v) frame — `u = x·cos(yaw) +
     * z·sin(yaw)`, `v = −x·sin(yaw) + z·cos(yaw)` — turned a quarter when the room is longer along
     * v, and shifted to its corner: two rigid moves, the turn and the shift unknown. Each turn is
     * tried; its shift along u and along v is the one that lays the page's wall lines on the most
     * wall surfels (1 cm bins); of the two, the one whose walls are seen where the survey says they
     * were seen, and unseen where it says they were not, is kept.
     */
    @Suppress("LongMethod")
    fun walls(survey: RoomSurvey, positions: FloatArray, normals: FloatArray, count: Int): List<RescanWall> {
        val page = survey.walls
        if (page.isEmpty()) return emptyList()
        val c = cos(survey.yaw)
        val s = sin(survey.yaw)
        val n = min(count, min(positions.size, normals.size) / 3)
        val alongU = ArrayList<Float>()
        val alongV = ArrayList<Float>()
        // The wall surfels, each with its (u, v) and which axis its normal follows.
        val wallU = ArrayList<Float>()
        val wallV = ArrayList<Float>()
        val wallAxis = ArrayList<Boolean>()
        for (i in 0 until n) {
            val y = positions[i * 3 + 1]
            if (y < survey.floorY + WALL_LOW_M || y > survey.floorY + COVERAGE_TOP_M) continue
            val nx = normals[i * 3]
            val nz = normals[i * 3 + 2]
            val nu = nx * c + nz * s
            val nv = -nx * s + nz * c
            val x = positions[i * 3]
            val z = positions[i * 3 + 2]
            val u = x * c + z * s
            val v = -x * s + z * c
            if (abs(nu) >= WALL_NORMAL) {
                alongU += u
                wallU += u
                wallV += v
                wallAxis += true
            } else if (abs(nv) >= WALL_NORMAL) {
                alongV += v
                wallU += u
                wallV += v
                wallAxis += false
            }
        }
        if (alongU.isEmpty() || alongV.isEmpty()) return emptyList()
        val histU = Histogram.of(alongU)
        val histV = Histogram.of(alongV)
        val pxLines = page.filter { abs(it.ax - it.bx) < SAME_M }.map { it.ax }.distinct()
        val pyLines = page.filter { abs(it.ay - it.by) < SAME_M }.map { it.ay }.distinct()
        var best: List<RescanWall> = emptyList()
        var bestScore = Int.MIN_VALUE
        for (turned in listOf(false, true)) {
            // Not turned: u = px + minU, v = py + minV. Turned: u = py + minU, v = minV + length − px.
            val uLines = if (turned) pyLines else pxLines
            val vLines = if (turned) pxLines.map { survey.planLength - it } else pyLines
            val minU = histU.bestShift(uLines) ?: continue
            val minV = histV.bestShift(vLines) ?: continue
            fun toScan(px: Float, py: Float): FloatArray {
                val u = if (turned) py + minU else px + minU
                val v = if (turned) minV + survey.planLength - px else py + minV
                return floatArrayOf(u * c - v * s, u * s + v * c)
            }
            val walls = page.map { wall ->
                val a = toScan(wall.ax, wall.ay)
                val b = toScan(wall.bx, wall.by)
                RescanWall(a[0], a[1], b[0], b[1], inferred = !wall.seen, unseen = wall.unseen)
            }
            val score = agreement(page, walls, wallU, wallV, wallAxis, c, s)
            if (score > bestScore) {
                bestScore = score
                best = walls
            }
        }
        return best
    }

    /**
     * How well [walls] agree with the cloud: every [WALL_STEP_M] along each, +1 when the cloud and
     * the survey's [page] both have it seen, or both unseen.
     */
    @Suppress("LongParameterList")
    private fun agreement(
        page: List<SurveyWall>,
        walls: List<RescanWall>,
        wallU: List<Float>,
        wallV: List<Float>,
        wallAxis: List<Boolean>,
        c: Float,
        s: Float,
    ): Int {
        var score = 0
        for ((k, wall) in walls.withIndex()) {
            val length = wall.length
            if (length < WALL_STEP_M) continue
            val steps = ceil(length / WALL_STEP_M).toInt()
            val hit = BooleanArray(steps)
            val ux = (wall.bx - wall.ax) / length
            val uz = (wall.bz - wall.az) / length
            // A wall along x in the page faces along the other survey axis.
            val wallFacesU = abs(ux * c + uz * s) < abs(-ux * s + uz * c)
            for (i in wallU.indices) {
                if (wallAxis[i] != wallFacesU) continue
                val x = wallU[i] * c - wallV[i] * s
                val z = wallU[i] * s + wallV[i] * c
                val dx = x - wall.ax
                val dz = z - wall.az
                val along = dx * ux + dz * uz
                val across = abs(-dx * uz + dz * ux)
                if (across > WALL_REACH_M || along < 0f || along >= length) continue
                hit[min(steps - 1, (along / WALL_STEP_M).toInt())] = true
            }
            val surveyed = page[k]
            for (j in 0 until steps) {
                val mid = (j + HALF) * WALL_STEP_M
                val seen = surveyed.seen && surveyed.unseen.none { mid in it }
                if (seen == hit[j]) score++
            }
        }
        return score
    }

    /**
     * The holes in [ground]'s floor: each connected piece (side by side) of its missing cells, as
     * its bounding rectangle with the piece's own area.
     */
    fun holes(ground: ScanGround): List<RescanHole> {
        val columns = ground.columns
        val rows = ground.rows
        val seen = BooleanArray(columns * rows)
        val holes = ArrayList<RescanHole>()
        val stack = ArrayDeque<Int>()
        for (start in 0 until columns * rows) {
            if (seen[start] || ground.at(start % columns, start / columns) != ScanGround.MISSING) continue
            seen[start] = true
            stack.addLast(start)
            var cells = 0
            var c0 = Int.MAX_VALUE
            var r0 = Int.MAX_VALUE
            var c1 = Int.MIN_VALUE
            var r1 = Int.MIN_VALUE
            while (stack.isNotEmpty()) {
                val at = stack.removeLast()
                val col = at % columns
                val row = at / columns
                cells++
                c0 = min(c0, col)
                c1 = max(c1, col)
                r0 = min(r0, row)
                r1 = max(r1, row)
                for ((dc, dr) in NEIGHBOURS) {
                    val nc = col + dc
                    val nr = row + dr
                    if (nc !in 0 until columns || nr !in 0 until rows) continue
                    val next = nr * columns + nc
                    if (!seen[next] && ground.at(nc, nr) == ScanGround.MISSING) {
                        seen[next] = true
                        stack.addLast(next)
                    }
                }
            }
            val m = ground.cellM
            val x0 = ground.originX + c0 * m
            val z0 = ground.originZ + r0 * m
            val x1 = ground.originX + (c1 + 1) * m
            val z1 = ground.originZ + (r1 + 1) * m
            holes += RescanHole(floatArrayOf(x0, z0, x1, z0, x1, z1, x0, z1), area = cells * m * m)
        }
        return holes
    }

    /** How densely [cloud] covers the floor plane, read from [floorY] up into a room [height] high. */
    fun coverage(cloud: DenseCloud, floorY: Float, height: Float): RescanCoverage? {
        val bottom = floorY - COVERAGE_FLOOR_BAND_M
        val top = floorY + min(COVERAGE_TOP_M, COVERAGE_TOP_SHARE * height)
        var minX = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        val p = cloud.positions
        for (i in 0 until cloud.count) {
            val y = p[i * 3 + 1]
            if (y !in bottom..top || !p[i * 3].isFinite() || !p[i * 3 + 2].isFinite()) continue
            minX = min(minX, p[i * 3])
            maxX = max(maxX, p[i * 3])
            minZ = min(minZ, p[i * 3 + 2])
            maxZ = max(maxZ, p[i * 3 + 2])
        }
        if (!minX.isFinite()) return null
        val cell = max(COVERAGE_CELL_M, max(maxX - minX, maxZ - minZ) / MAX_COVERAGE_CELLS)
        val originX = floor(minX / cell) * cell
        val originZ = floor(minZ / cell) * cell
        val columns = max(1, ceil((maxX - originX) / cell).toInt() + 1)
        val rows = max(1, ceil((maxZ - originZ) / cell).toInt() + 1)
        val density = FloatArray(columns * rows)
        val perM2 = 1f / (cell * cell)
        for (i in 0 until cloud.count) {
            val y = p[i * 3 + 1]
            if (y !in bottom..top || !p[i * 3].isFinite() || !p[i * 3 + 2].isFinite()) continue
            val col = ((p[i * 3] - originX) / cell).toInt().coerceIn(0, columns - 1)
            val row = ((p[i * 3 + 2] - originZ) / cell).toInt().coerceIn(0, rows - 1)
            density[row * columns + col] += (1 shl (2 * cloud.levelOf(i))) * perM2
        }
        return RescanCoverage(cell, originX, originZ, columns, rows, density)
    }

    /** A 1-D histogram of positions, [FIT_BIN_M] bins, smoothed over [FIT_SMOOTH_BINS] on each side. */
    private class Histogram(val min: Float, val bins: FloatArray) {
        fun at(position: Float): Float {
            val i = floor((position - min) / FIT_BIN_M).toInt()
            return if (i in bins.indices) bins[i] else 0f
        }

        /** The shift that lays [lines] (relative positions) on the most mass; `null` without lines. */
        fun bestShift(lines: List<Float>): Float? {
            if (lines.isEmpty()) return null
            val low = min - lines.max()
            val high = min + bins.size * FIT_BIN_M - lines.min()
            val steps = ceil((high - low) / FIT_BIN_M).toInt()
            var best = low
            var bestMass = -1f
            for (k in 0..steps) {
                val shift = low + k * FIT_BIN_M
                val mass = lines.sumOf { at(shift + it).toDouble() }.toFloat()
                if (mass > bestMass) {
                    bestMass = mass
                    best = shift
                }
            }
            // The bin's middle, so the shift is off by half a bin at most.
            return best + FIT_BIN_M * HALF
        }

        companion object {
            fun of(values: List<Float>): Histogram {
                val low = values.min()
                val high = values.max()
                val raw = FloatArray(max(1, ceil((high - low) / FIT_BIN_M).toInt() + 1))
                for (v in values) raw[((v - low) / FIT_BIN_M).toInt().coerceIn(0, raw.size - 1)]++
                val smooth = FloatArray(raw.size) { i ->
                    var sum = 0f
                    for (d in -FIT_SMOOTH_BINS..FIT_SMOOTH_BINS) sum += raw.getOrElse(i + d) { 0f }
                    sum
                }
                return Histogram(low, smooth)
            }
        }
    }

    /** The distance under which two page coordinates are the same line, metres. */
    private const val SAME_M = 1e-4f
    private const val HALF = 0.5f
    private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
}
