package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/*
 * A space measured from its scan: a shelf, a niche, the cupboard under a sink, a car boot. What
 * someone needs to know to put something in it: how wide, how high and how deep it is inside,
 * what is in the way, and the biggest box that still slides in. Read off the scan's dense points
 * and their normals only. Pure Kotlin, off the main thread.
 *
 * Everything is in the space's own frame, the one its answer is written in ([SpaceAnswer]):
 * right-handed, Y up, metres, the origin at the back-left-bottom corner, +X to the right as seen
 * from the opening, +Z from the back towards the opening.
 */

/** A face of a space, named as seen from its opening. */
enum class SpaceFace(val key: String) {
    Front("front"),
    Back("back"),
    Left("left"),
    Right("right"),
    Bottom("bottom"),
    Top("top"),
}

/** A box in a space's frame: its lowest corner and its [width] (X), [height] (Y) and [depth] (Z), metres. */
data class SpaceBox(val x: Float, val y: Float, val z: Float, val width: Float, val height: Float, val depth: Float) {
    val volume: Float get() = width * height * depth
}

/**
 * A space as measured: its inside [width], [height] and [depth]; the faces no surface closes
 * ([open], the front always); what stands inside ([obstacles], the biggest first); and the biggest
 * box that rests on the bottom and slides in from the opening clear of all of it ([fits], `null`
 * when nothing of a useful size does, or when no bottom was seen for it to rest on).
 *
 * [originX], [originY], [originZ] and [yawDegrees] place the frame in the scan's world: the
 * back-left-bottom corner, and the turn about Y that brings world +Z onto the frame's +Z.
 * [resolution] is the grid the measure was read on, in metres: the scan's voxel, or the cell
 * its inside was sorted into when that is coarser. [share] is how much of the scan's surface
 * the space holds, on its faces and inside, from 0 to 1: the scan of one cupboard is mostly
 * that cupboard, a walk round a room is not ([SpaceFitter.isOneSpace]).
 */
class SpaceFit(
    val width: Float,
    val height: Float,
    val depth: Float,
    val open: Set<SpaceFace>,
    val obstacles: List<SpaceBox>,
    val fits: SpaceBox?,
    val originX: Float,
    val originY: Float,
    val originZ: Float,
    val yawDegrees: Float,
    val resolution: Float = SpaceFitter.DEFAULT_VOXEL_M,
    val share: Float = 1f,
) {
    /** The world position of the frame's point ([x], [y], [z]). */
    fun world(x: Float, y: Float, z: Float): Vec3 {
        val yaw = Math.toRadians(yawDegrees.toDouble())
        val c = cos(yaw).toFloat()
        val s = sin(yaw).toFloat()
        return Vec3(originX + x * c + z * s, originY + y, originZ - x * s + z * c)
    }
}

/** Measures a [SpaceFit] off a scan's dense points. */
object SpaceFitter {
    /** The dense cloud's own voxel ([DenseFusion]): a level-0 point stands for a square this wide. */
    const val DEFAULT_VOXEL_M = 0.02f

    /** The cell obstacles and the fitting box are worked out on. */
    const val CELL_M = 0.02f

    /** What a fitting box keeps from the walls and the top. From what stands inside it keeps a cell. */
    const val CLEARANCE_M = 0.01f

    /** Obstacles reported, the biggest; smaller ones still keep the fitting box away. */
    const val MAX_OBSTACLES = 8

    /**
     * The least of a scan's surface a space holds for the scan to be of that space
     * ([SpaceFit.share]). Hand-built cupboards hold 53 to 67 % of theirs, what frames them
     * included; three hand-held scans of rooms held 6, 9 and 27 % (2026-11-14).
     */
    const val ONE_SPACE_SHARE = 0.33f

    /** The widest and the highest a space filmed from one spot is: a wardrobe, not a wall of a room. */
    const val ONE_SPOT_SIDE_M = 2.6f

    /**
     * Whether [fit]'s scan is of that one space, not a walk round more than it: the space holds
     * most of the scan, it is no wider or higher than one spot films, and its opening was found
     * short of how far a space is looked for. A room walked round fails one of the three, and
     * its figures would mean nothing.
     */
    fun isOneSpace(fit: SpaceFit): Boolean =
        fit.share >= ONE_SPACE_SHARE && fit.width <= ONE_SPOT_SIDE_M && fit.height <= ONE_SPOT_SIDE_M &&
            fit.depth < MAX_DEPTH_M - WALL_MARGIN_M

    /** A surface smaller than this (about 12 cm × 12 cm) closes no face. */
    private const val MIN_WALL_M2 = 0.015f

    /** A normal within about 37° of a direction faces it. */
    private const val FACING = 0.8f

    /** A normal this far from the vertical belongs to a wall. */
    private const val WALL_MAX_NY = 0.35f

    /** Among the surfaces facing the opening, the back is the deepest worth this share of the largest. */
    private const val BACK_SHARE = 0.25f

    /** A face is closed by the nearest surface worth this share of the largest on its side. */
    private const val STRONG_SHARE = 0.35f

    /** Points this close to a face's plane lie on it. */
    private const val ON_PLANE_M = 0.015f

    /** Points this close to a face are that face's noise, not an obstacle. */
    private const val WALL_MARGIN_M = 0.03f

    /** No face is looked for nearer the point aimed at than this. */
    private const val CENTRE_GAP_M = 0.03f

    /** How far in front of the back a space is looked for: past the arm's-length capture. */
    private const val MAX_DEPTH_M = 1.5f
    private const val MIN_SIDE_M = 0.05f
    private const val MAX_SIDE_M = 4f
    private const val MIN_POINTS = 200
    private const val MIN_OBSTACLE_CELLS = 6
    private const val MAX_CELLS = 4_000_000
    private const val BIN_M = 0.01f

    /** The share of a face's area its far end is read at, before [edge] carries it on. */
    private const val REACH_SHARE = 0.97f

    /** The same for the two ends of what was seen of a face left open. */
    private const val EXTENT_SHARE = 0.98f
    private const val YAW_BINS = 90
    private const val YAW_SMOOTH = 2
    private const val YAW_REFINE_DEGREES = 5f
    private const val QUARTER_TURN = 90f

    /**
     * Where the phone stood while [trace] was recorded, on average, `null` with no pose: the
     * side a space was filmed from, which is its opening.
     */
    fun eyeOf(trace: ArDebugTrace): Vec3? {
        val count = trace.poseCount
        if (count == 0) return null
        var x = 0f
        var y = 0f
        var z = 0f
        for (i in 0 until count) {
            val pose = trace.pose(i)
            x += pose.x
            y += pose.y
            z += pose.z
        }
        return Vec3(x / count, y / count, z / count)
    }

    /**
     * The space [cloud] shows, `null` when it shows none: no normals, too few points, no wall to
     * square it on, or no surface behind what was aimed at with a bottom or a side to give it a
     * depth. [eye] is where the phone stood, on average: the opening faces it. Without it the
     * opening is the side most surfaces face.
     */
    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod") // one early return per thing a space needs
    fun of(cloud: DenseCloud, voxelM: Float = DEFAULT_VOXEL_M, eye: Vec3? = null): SpaceFit? {
        val normals = cloud.normals ?: return null
        val n = cloud.count
        if (n < MIN_POINTS) return null
        val p = cloud.positions
        // What each point stands for: a square of its voxel, wider at a coarser level.
        val area = FloatArray(n) {
            val side = voxelM * (1 shl cloud.levelOf(it))
            side * side
        }
        val squared = wallYaw(normals, area, n) ?: return null
        val yaw = opening(p, normals, area, n, squared, eye)
        val c = cos(yaw)
        val s = sin(yaw)
        // The frame's axes before its origin is known: u to the right, v up, w towards the opening.
        val u = FloatArray(n) { p[it * 3] * c - p[it * 3 + 2] * s }
        val v = FloatArray(n) { p[it * 3 + 1] }
        val w = FloatArray(n) { p[it * 3] * s + p[it * 3 + 2] * c }
        val nu = FloatArray(n) { normals[it * 3] * c - normals[it * 3 + 2] * s }
        val nv = FloatArray(n) { normals[it * 3 + 1] }
        val nw = FloatArray(n) { normals[it * 3] * s + normals[it * 3 + 2] * c }

        // The back: of the surfaces facing the opening, the deepest that is a wall.
        val facing = peaks(n, area, { w[it] }) { nw[it] > FACING }
        val largest = facing.maxOfOrNull { it.area } ?: return null
        val wBack = facing.filter { it.area >= maxOf(MIN_WALL_M2, largest * BACK_SHARE) }.minByOrNull { it.at }?.at
            ?: return null
        // What was aimed at: the middle of what was seen of the back.
        val uc = quantile(n, area, 0.5f, { u[it] }) { nw[it] > FACING && abs(w[it] - wBack) <= ON_PLANE_M } ?: return null
        val vc = quantile(n, area, 0.5f, { v[it] }) { nw[it] > FACING && abs(w[it] - wBack) <= ON_PLANE_M } ?: return null

        // The sides: the first wall each way from there, in front of the back.
        val left = strong(peaks(n, area, { u[it] }) { nu[it] > FACING && inFront(w[it], wBack) && u[it] < uc - CENTRE_GAP_M })
            .maxByOrNull { it.at }
        val right = strong(peaks(n, area, { u[it] }) { nu[it] < -FACING && inFront(w[it], wBack) && u[it] > uc + CENTRE_GAP_M })
            .minByOrNull { it.at }
        val uLeft = left?.at
            ?: edge(n, area, EXTENT_SHARE, far = false, { u[it] }) { nw[it] > FACING && abs(w[it] - wBack) <= ON_PLANE_M } ?: return null
        val uRight = right?.at
            ?: edge(n, area, EXTENT_SHARE, far = true, { u[it] }) { nw[it] > FACING && abs(w[it] - wBack) <= ON_PLANE_M } ?: return null
        // How far the sides come forward bounds where a bottom and a top are looked for.
        val sideReach = listOfNotNull(
            left?.let {
                edge(n, area, REACH_SHARE, far = true, { w[it] }) { nu[it] > FACING && abs(u[it] - uLeft) <= ON_PLANE_M && inFront(w[it], wBack) }
            },
            right?.let {
                edge(n, area, REACH_SHARE, far = true, { w[it] }) { nu[it] < -FACING && abs(u[it] - uRight) <= ON_PLANE_M && inFront(w[it], wBack) }
            },
        ).maxOrNull() ?: (wBack + MAX_DEPTH_M)
        val bottom = strong(
            peaks(n, area, { v[it] }) {
                nv[it] > FACING && v[it] < vc - CENTRE_GAP_M && between(u[it], uLeft, uRight) && between(w[it], wBack, sideReach)
            },
        ).maxByOrNull { it.at }
        val top = strong(
            peaks(n, area, { v[it] }) {
                nv[it] < -FACING && v[it] > vc + CENTRE_GAP_M && between(u[it], uLeft, uRight) && between(w[it], wBack, sideReach)
            },
        ).minByOrNull { it.at }
        val vBottom = bottom?.at
            ?: edge(n, area, EXTENT_SHARE, far = false, { v[it] }) {
                nw[it] > FACING && abs(w[it] - wBack) <= ON_PLANE_M && between(u[it], uLeft, uRight)
            }
            ?: return null
        val vTop = top?.at
            ?: edge(n, area, EXTENT_SHARE, far = true, { v[it] }) {
                nw[it] > FACING && abs(w[it] - wBack) <= ON_PLANE_M && between(u[it], uLeft, uRight)
            }
            ?: return null

        // The opening: where the first of the bottom and the sides stops, inside the space's own
        // height and width. An open door carries a side on, a floor carries a bottom on; the
        // other stops at the edge.
        val wFront = listOfNotNull(
            bottom?.let {
                edge(n, area, REACH_SHARE, far = true, { w[it] }) {
                    nv[it] > FACING && abs(v[it] - vBottom) <= ON_PLANE_M && between(u[it], uLeft, uRight) && inFront(w[it], wBack)
                }
            },
            left?.let {
                edge(n, area, REACH_SHARE, far = true, { w[it] }) {
                    nu[it] > FACING && abs(u[it] - uLeft) <= ON_PLANE_M && between(v[it], vBottom, vTop) && inFront(w[it], wBack)
                }
            },
            right?.let {
                edge(n, area, REACH_SHARE, far = true, { w[it] }) {
                    nu[it] < -FACING && abs(u[it] - uRight) <= ON_PLANE_M && between(v[it], vBottom, vTop) && inFront(w[it], wBack)
                }
            },
        ).minOrNull() ?: return null

        val width = uRight - uLeft
        val height = vTop - vBottom
        val depth = wFront - wBack
        if (listOf(width, height, depth).any { it < MIN_SIDE_M || it > MAX_SIDE_M }) return null
        val open = buildSet {
            add(SpaceFace.Front)
            if (left == null) add(SpaceFace.Left)
            if (right == null) add(SpaceFace.Right)
            if (bottom == null) add(SpaceFace.Bottom)
            if (top == null) add(SpaceFace.Top)
        }

        // What stands inside, on a grid of the space.
        var cell = CELL_M
        while (cells(width, cell).toLong() * cells(height, cell) * cells(depth, cell) > MAX_CELLS) cell *= 2f
        val grid = SpaceGrid(cells(width, cell), cells(height, cell), cells(depth, cell), cell)
        var held = 0f
        var whole = 0f
        for (i in 0 until n) {
            val x = u[i] - uLeft
            val y = v[i] - vBottom
            val z = w[i] - wBack
            whole += area[i]
            if (between(x, 0f, width) && between(y, 0f, height) && between(z, 0f, depth)) held += area[i]
            // A face that is closed has its own points near it; an open one has none to mistake.
            val inside = x > WALL_MARGIN_M && x < width - WALL_MARGIN_M && y > WALL_MARGIN_M && y < height - WALL_MARGIN_M &&
                z > WALL_MARGIN_M && z < depth
            if (inside) grid.fill(x, y, z)
        }
        val obstacles = grid.obstacles(MIN_OBSTACLE_CELLS)
        // A box rests on a bottom that was seen: a height read off the back alone carries nothing.
        val fits = if (bottom != null) grid.biggestBox(width, height, depth, topOpen = top == null) else null
        // The frame's origin, back in the scan's world.
        return SpaceFit(
            width = width,
            height = height,
            depth = depth,
            open = open,
            obstacles = obstacles.sortedByDescending { it.volume }.take(MAX_OBSTACLES),
            fits = fits,
            originX = uLeft * c + wBack * s,
            originY = vBottom,
            originZ = -uLeft * s + wBack * c,
            yawDegrees = Math.toDegrees(yaw.toDouble()).toFloat(),
            resolution = maxOf(voxelM, cell),
            share = if (whole > 0f) held / whole else 0f,
        )
    }

    private fun cells(sizeM: Float, cell: Float): Int = ceil(sizeM / cell).toInt().coerceAtLeast(1)

    private fun inFront(w: Float, wBack: Float) = w >= wBack - ON_PLANE_M && w <= wBack + MAX_DEPTH_M

    private fun between(value: Float, from: Float, to: Float) = value >= from - ON_PLANE_M && value <= to + ON_PLANE_M

    /** The surfaces of [found] that close a face: worth a wall, and [STRONG_SHARE] of the largest. */
    private fun strong(found: List<Peak>): List<Peak> {
        val largest = found.maxOfOrNull { it.area } ?: return emptyList()
        return found.filter { it.area >= maxOf(MIN_WALL_M2, largest * STRONG_SHARE) }
    }

    /**
     * The turn about Y, in `[0, 90°)` as radians, that squares the walls with the axes: where most
     * of the walls' normals point, folded on a quarter turn. `null` with no wall to speak of.
     */
    private fun wallYaw(normals: FloatArray, area: FloatArray, n: Int): Float? {
        val bins = FloatArray(YAW_BINS)
        var walls = 0f
        for (i in 0 until n) {
            if (abs(normals[i * 3 + 1]) > WALL_MAX_NY) continue
            bins[folded(normals, i).toInt().coerceIn(0, YAW_BINS - 1)] += area[i]
            walls += area[i]
        }
        if (walls < MIN_WALL_M2) return null
        var best = 0
        var bestSum = -1f
        for (b in 0 until YAW_BINS) {
            var sum = 0f
            for (d in -YAW_SMOOTH..YAW_SMOOTH) sum += bins[(b + d + YAW_BINS) % YAW_BINS]
            if (sum > bestSum) {
                bestSum = sum
                best = b
            }
        }
        // The mean about that peak, to a fraction of a degree.
        val centre = best + 0.5f
        var offsets = 0f
        var weight = 0f
        for (i in 0 until n) {
            if (abs(normals[i * 3 + 1]) > WALL_MAX_NY) continue
            val d = ((folded(normals, i) - centre + QUARTER_TURN * 1.5f) % QUARTER_TURN) - QUARTER_TURN / 2f
            if (abs(d) <= YAW_REFINE_DEGREES) {
                offsets += d * area[i]
                weight += area[i]
            }
        }
        val degrees = ((centre + if (weight > 0f) offsets / weight else 0f) + QUARTER_TURN) % QUARTER_TURN
        return Math.toRadians(degrees.toDouble()).toFloat()
    }

    /** The direction of point [i]'s normal about Y, in degrees folded into `[0, 90)`. */
    private fun folded(normals: FloatArray, i: Int): Float {
        val degrees = Math.toDegrees(atan2(normals[i * 3], normals[i * 3 + 2]).toDouble()).toFloat()
        return ((degrees % QUARTER_TURN) + QUARTER_TURN) % QUARTER_TURN
    }

    /**
     * The turn about Y, in radians, of the side the space opens on, among the four [squared]
     * gives: the one facing [eye], or without it the one whose walls face it most, less those
     * that face away. A space shows its back and what frames its opening, never their other side.
     */
    @Suppress("LongParameterList")
    private fun opening(p: FloatArray, normals: FloatArray, area: FloatArray, n: Int, squared: Float, eye: Vec3?): Float {
        val turns = FloatArray(QUARTERS) { squared + it * (Math.PI / 2).toFloat() }
        val scores = FloatArray(QUARTERS)
        if (eye != null) {
            var cx = 0f
            var cz = 0f
            var total = 0f
            for (i in 0 until n) {
                cx += p[i * 3] * area[i]
                cz += p[i * 3 + 2] * area[i]
                total += area[i]
            }
            for (k in 0 until QUARTERS) scores[k] = (eye.x - cx / total) * sin(turns[k]) + (eye.z - cz / total) * cos(turns[k])
        } else {
            val faced = FloatArray(QUARTERS)
            for (i in 0 until n) {
                if (abs(normals[i * 3 + 1]) > WALL_MAX_NY) continue
                for (k in 0 until QUARTERS) {
                    if (normals[i * 3] * sin(turns[k]) + normals[i * 3 + 2] * cos(turns[k]) > FACING) faced[k] += area[i]
                }
            }
            for (k in 0 until QUARTERS) scores[k] = faced[k] - faced[(k + 2) % QUARTERS]
        }
        return turns[scores.indices.maxByOrNull { scores[it] } ?: 0]
    }

    private const val QUARTERS = 4

    /** A surface found along an axis: where it is, and the area of the points on it. */
    private class Peak(val at: Float, val area: Float)

    /**
     * The surfaces along an axis: each point [keep] holds adds its area at [at]; a surface is a
     * place more of it gathers within [ON_PLANE_M] than just beside.
     */
    private inline fun peaks(n: Int, area: FloatArray, at: (Int) -> Float, keep: (Int) -> Boolean): List<Peak> {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (i in 0 until n) {
            if (!keep(i)) continue
            val value = at(i)
            if (value < min) min = value
            if (value > max) max = value
        }
        if (min > max) return emptyList()
        val sums = FloatArray(((max - min) / BIN_M).toInt() + 1)
        val moments = FloatArray(sums.size)
        for (i in 0 until n) {
            if (!keep(i)) continue
            val value = at(i)
            val bin = ((value - min) / BIN_M).toInt().coerceIn(0, sums.size - 1)
            sums[bin] += area[i]
            moments[bin] += area[i] * value
        }
        return peaksOf(sums, moments)
    }

    /** The local maxima of [sums] taken three bins at a time, each at the mean of what it holds. */
    private fun peaksOf(sums: FloatArray, moments: FloatArray): List<Peak> {
        fun around(of: FloatArray, b: Int) = of.getOrElse(b - 1) { 0f } + of[b] + of.getOrElse(b + 1) { 0f }
        val found = ArrayList<Peak>()
        for (b in sums.indices) {
            val here = around(sums, b)
            if (here <= 0f) continue
            val before = if (b > 0) around(sums, b - 1) else 0f
            val after = if (b < sums.size - 1) around(sums, b + 1) else 0f
            if (here > before && here >= after) found += Peak(around(moments, b) / here, here)
        }
        return found
    }

    /**
     * Where the points kept stop along [at], at their [far] end or their near one: their [share]
     * quantile, carried on by as much as that quantile leaves out of a face seen evenly. The
     * quantile alone is deaf to a few stray points and stops short by its own share, 3 % of an
     * 80 cm niche's depth.
     */
    @Suppress("LongParameterList")
    private inline fun edge(n: Int, area: FloatArray, share: Float, far: Boolean, at: (Int) -> Float, keep: (Int) -> Boolean): Float? {
        val low = quantile(n, area, 1f - share, at, keep) ?: return null
        val high = quantile(n, area, share, at, keep) ?: return null
        val beyond = (high - low) * (1f - share) / (2f * share - 1f)
        return if (far) high + beyond else low - beyond
    }

    /** The value under which [share] of the area of the points [keep] holds lies, to a centimetre. */
    private inline fun quantile(n: Int, area: FloatArray, share: Float, at: (Int) -> Float, keep: (Int) -> Boolean): Float? {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        var total = 0f
        for (i in 0 until n) {
            if (!keep(i)) continue
            val value = at(i)
            if (value < min) min = value
            if (value > max) max = value
            total += area[i]
        }
        if (min > max || total <= 0f) return null
        val sums = FloatArray(((max - min) / BIN_M).toInt() + 1)
        for (i in 0 until n) {
            if (!keep(i)) continue
            sums[((at(i) - min) / BIN_M).toInt().coerceIn(0, sums.size - 1)] += area[i]
        }
        var running = 0f
        for (b in sums.indices) {
            running += sums[b]
            if (running >= total * share) return (min + (b + 0.5f) * BIN_M).coerceAtMost(max)
        }
        return max
    }
}

/**
 * The inside of a space as cells of [cell] metres, [nx] × [ny] × [nz] of them from the
 * back-left-bottom corner: which hold a scanned point.
 */
internal class SpaceGrid(private val nx: Int, private val ny: Int, private val nz: Int, private val cell: Float) {
    private val filled = BooleanArray(nx * ny * nz)

    private fun index(x: Int, y: Int, z: Int) = (z * ny + y) * nx + x

    fun fill(x: Float, y: Float, z: Float) {
        val ix = floor(x / cell).toInt()
        val iy = floor(y / cell).toInt()
        val iz = floor(z / cell).toInt()
        if (ix in 0 until nx && iy in 0 until ny && iz in 0 until nz) filled[index(ix, iy, iz)] = true
    }

    /**
     * What stands in the space: each group of touching cells of at least [minCells], as its box.
     * Smaller groups are the depth's noise: they are emptied, and keep nothing away.
     */
    fun obstacles(minCells: Int): List<SpaceBox> {
        val seen = BooleanArray(filled.size)
        val found = ArrayList<SpaceBox>()
        val queue = IntArray(filled.size)
        for (start in filled.indices) {
            if (!filled[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            val low = intArrayOf(nx, ny, nz)
            val high = intArrayOf(-1, -1, -1)
            while (head < tail) {
                val at = queue[head++]
                val x = at % nx
                val y = at / nx % ny
                val z = at / (nx * ny)
                val here = intArrayOf(x, y, z)
                for (a in 0 until AXES) {
                    if (here[a] < low[a]) low[a] = here[a]
                    if (here[a] > high[a]) high[a] = here[a]
                }
                for (dz in -1..1) for (dy in -1..1) for (dx in -1..1) {
                    val tx = x + dx
                    val ty = y + dy
                    val tz = z + dz
                    if (tx !in 0 until nx || ty !in 0 until ny || tz !in 0 until nz) continue
                    val next = index(tx, ty, tz)
                    if (filled[next] && !seen[next]) {
                        seen[next] = true
                        queue[tail++] = next
                    }
                }
            }
            if (tail < minCells) {
                for (i in 0 until tail) filled[queue[i]] = false
            } else {
                found += SpaceBox(
                    x = low[0] * cell,
                    y = low[1] * cell,
                    z = low[2] * cell,
                    width = (high[0] - low[0] + 1) * cell,
                    height = (high[1] - low[1] + 1) * cell,
                    depth = (high[2] - low[2] + 1) * cell,
                )
            }
        }
        return found
    }

    /**
     * The biggest box, by volume, that rests on the bottom of a space [width] × [height] × [depth]
     * and slides in from the opening: nothing over it, beside it within a cell, or in its way to
     * the front; [SpaceFitter.CLEARANCE_M] from the walls and, unless [topOpen], the top. `null`
     * when no box of [MIN_BOX_M] a side does. Call after [obstacles], which clears the noise.
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth") // a grid walked a few ways
    fun biggestBox(width: Float, height: Float, depth: Float, topOpen: Boolean): SpaceBox? {
        // How high each column is free from the bottom, in cells.
        val clear = IntArray(nx * nz) { ny }
        for (z in 0 until nz) for (y in 0 until ny) for (x in 0 until nx) {
            if (filled[index(x, y, z)] && y < clear[z * nx + x]) clear[z * nx + x] = y
        }
        // …keeping a cell away from whatever stands beside…
        val apart = IntArray(nx * nz) { at ->
            val x = at % nx
            val z = at / nx
            var least = clear[at]
            for (dz in -1..1) for (dx in -1..1) {
                val tx = x + dx
                val tz = z + dz
                if (tx in 0 until nx && tz in 0 until nz) least = minOf(least, clear[tz * nx + tx])
            }
            least
        }
        // …and on the way in from the opening, the last row.
        for (z in nz - 2 downTo 0) for (x in 0 until nx) {
            apart[z * nx + x] = minOf(apart[z * nx + x], apart[(z + 1) * nx + x])
        }
        var best: SpaceBox? = null
        val heights = IntArray(nx)
        val stack = IntArray(nx + 1)
        for (cellsHigh in apart.toSet().sortedDescending()) {
            if (cellsHigh <= 0) continue
            val high = minOf(cellsHigh * cell, height - if (topOpen) 0f else SpaceFitter.CLEARANCE_M)
            heights.fill(0)
            // The largest rectangle of columns free that high: a histogram per row, back to front.
            for (z in 0 until nz) {
                for (x in 0 until nx) heights[x] = if (apart[z * nx + x] >= cellsHigh) heights[x] + 1 else 0
                var top = 0
                for (x in 0..nx) {
                    val h = if (x < nx) heights[x] else 0
                    while (top > 0 && heights[stack[top - 1]] >= h) {
                        val column = stack[--top]
                        val rows = heights[column]
                        val from = if (top == 0) 0 else stack[top - 1] + 1
                        val box = boxOf(from, x, z - rows + 1, z + 1, high, width, depth)
                        if (box != null && box.volume > (best?.volume ?: 0f)) best = box
                    }
                    if (x < nx) stack[top++] = x
                }
            }
        }
        return best
    }

    /** The box over columns `[x0, x1)` and rows `[z0, z1)`, [high] metres, clear of the walls; `null` when too small. */
    @Suppress("LongParameterList")
    private fun boxOf(x0: Int, x1: Int, z0: Int, z1: Int, high: Float, width: Float, depth: Float): SpaceBox? {
        val left = maxOf(x0 * cell, SpaceFitter.CLEARANCE_M)
        val right = minOf(x1 * cell, width - SpaceFitter.CLEARANCE_M)
        val back = maxOf(z0 * cell, SpaceFitter.CLEARANCE_M)
        val front = minOf(z1 * cell, depth)
        if (right - left < MIN_BOX_M || front - back < MIN_BOX_M || high < MIN_BOX_M) return null
        return SpaceBox(left, 0f, back, right - left, high, front - back)
    }

    private companion object {
        const val AXES = 3

        /** A box under 5 cm a side holds nothing worth fitting. */
        const val MIN_BOX_M = 0.05f
    }
}
