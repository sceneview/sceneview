package io.github.sceneview.demo.demos.internal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Lining a re-scan up on the scan it completes (#4736).
 *
 * A re-scan is a new AR session: its origin is wherever the phone stood when it started, and its
 * heading wherever the phone faced. ARCore keeps +Y up in every session, so between two sessions
 * of the same room there is a turn about +Y and a shift, never a tilt: four unknowns.
 *
 * [RescanRegistrar] finds them in three steps, and refuses rather than guess:
 * 1. **The floor** gives the height: the lowest broad level of upward-facing surfels in each scan.
 * 2. **The wall families** give the turn, up to a quarter turn: the dominant directions of the
 *    vertical surfels' normals, folded onto a quarter turn. Each folded direction of the scan paired
 *    with each of the re-scan, in all four quarter turns, is a candidate turn.
 * 3. **The walls' offsets** give the shift: along each wall direction, the planes the two scans see
 *    (walls, furniture sides) vote for the shift that lays them on one another.
 *
 * Every candidate is then refined by **point-to-plane ICP** on the dense points (four unknowns,
 * the floor and walls of the scan as the planes), and judged on what the re-scan lands on.
 *
 * It refuses ([RescanRefusal]) when the re-scan sees no floor, no wall, too little of what the scan
 * saw, too little to pin it down (one flat wall, or walls too few to hold it still), two places it
 * fits equally well (a bare corner of a symmetric room), or when, once lined up, it puts surfaces
 * where the scan saw empty space outside the parts it may fill in. A wrong merge is worse than none.
 *
 * Frame: metres, +Y up. Pure arithmetic: off the main thread.
 */

/**
 * A rigid move between two scans of a room: a turn of [yaw] radians about +Y — `x' = x·cos − z·sin`,
 * `z' = x·sin + z·cos` — then a shift by ([tx], [ty], [tz]).
 */
class RescanPose(val yaw: Float, val tx: Float, val ty: Float, val tz: Float) {
    private val c = cos(yaw)
    private val s = sin(yaw)

    /** Where a point at ([x], …, [z]) lands, its x. */
    fun x(x: Float, z: Float): Float = x * c - z * s + tx

    /** Where a point at height [y] lands, its y. */
    fun y(y: Float): Float = y + ty

    /** Where a point at (([x], …, [z]) lands, its z. */
    fun z(x: Float, z: Float): Float = x * s + z * c + tz

    /** The move that undoes this one. */
    fun inverse(): RescanPose = RescanPose(-yaw, -(tx * c + tz * s), -ty, -(-tx * s + tz * c))

    /** [cloud] moved: its positions and its normals; colours, confidences and levels as they are. */
    fun moved(cloud: DenseCloud): DenseCloud {
        val p = FloatArray(cloud.positions.size)
        val n = cloud.normals?.let { FloatArray(it.size) }
        for (i in 0 until cloud.count) {
            val x = cloud.positions[i * 3]
            val z = cloud.positions[i * 3 + 2]
            p[i * 3] = x(x, z)
            p[i * 3 + 1] = y(cloud.positions[i * 3 + 1])
            p[i * 3 + 2] = z(x, z)
            if (n != null) {
                val normals = cloud.normals
                val nx = normals[i * 3]
                val nz = normals[i * 3 + 2]
                n[i * 3] = nx * c - nz * s
                n[i * 3 + 1] = normals[i * 3 + 1]
                n[i * 3 + 2] = nx * s + nz * c
            }
        }
        return DenseCloud(p, cloud.colors, n, cloud.confidences, cloud.levels)
    }

    /**
     * The eight corners of [box] (`[minX, minY, minZ, maxX, maxY, maxZ]`) moved, x y z each: the
     * box as it stands in the other frame, turned with it.
     */
    fun corners(box: FloatArray): FloatArray {
        val out = FloatArray(CORNERS * 3)
        for (k in 0 until CORNERS) {
            val x = if (k and 1 == 0) box[0] else box[3]
            val y = if (k and 2 == 0) box[1] else box[4]
            val z = if (k and 4 == 0) box[2] else box[5]
            out[k * 3] = x(x, z)
            out[k * 3 + 1] = y(y)
            out[k * 3 + 2] = z(x, z)
        }
        return out
    }

    /** How far apart this move and [other] put the point ([x], [y], [z]), metres. */
    fun gap(other: RescanPose, x: Float, y: Float, z: Float): Float {
        val dx = x(x, z) - other.x(x, z)
        val dy = y(y) - other.y(y)
        val dz = z(x, z) - other.z(x, z)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** How much more this move turns than [other], degrees, in −180..180. */
    fun turnFrom(other: RescanPose): Float = Math.toDegrees(wrap((yaw - other.yaw).toDouble())).toFloat()

    override fun toString(): String =
        "turn %.2f° shift (%.3f, %.3f, %.3f) m".format(Math.toDegrees(yaw.toDouble()), tx, ty, tz)

    companion object {
        /** Corners of a box. */
        const val CORNERS = 8

        /** No move. */
        val Identity = RescanPose(0f, 0f, 0f, 0f)

        internal fun wrap(angle: Double): Double {
            var a = angle % (2 * PI)
            if (a > PI) a -= 2 * PI
            if (a < -PI) a += 2 * PI
            return a
        }
    }
}

/** Why a re-scan was not lined up: nothing is merged. */
enum class RescanRefusal {
    /** One of the two clouds has no normals: there is nothing to read planes from. */
    NoNormals,

    /** No floor in one of them: the height cannot be set. */
    NoFloor,

    /** No wall, nor any vertical surface: the turn cannot be set. */
    NoWalls,

    /** Too little of the re-scan lands on what the scan saw. */
    TooLittleOverlap,

    /** What it lands on does not pin every direction down: one flat wall slides along itself. */
    Unconstrained,

    /** Two different places fit it about as well: a bare corner of a symmetric room. */
    Ambiguous,

    /** Lined up, it puts surfaces where the scan saw empty space, outside the parts to fill in. */
    Contradicts,
}

/** What lining a re-scan up came to. */
sealed interface RescanAlignment {
    /**
     * Lined up: [pose] takes the re-scan into the scan's frame. [overlap] is the share of the
     * re-scan's samples that land on the scan's surface, [rmsM] how far off it they land.
     */
    class Lined(val pose: RescanPose, val overlap: Float, val rmsM: Float) : RescanAlignment {
        override fun toString(): String = "lined up, $pose, overlap %.2f, rms %.1f mm".format(overlap, rmsM * MM)
    }

    /** Not lined up, and [why]. */
    class Refused(val why: RescanRefusal) : RescanAlignment {
        override fun toString(): String = "refused: $why"
    }

    private companion object {
        const val MM = 1000f
    }
}

/**
 * The scan a re-scan is lined up on, read once: its surfels thinned to [RescanRegistrar.TARGET_M],
 * indexed for nearest-neighbour search, its floor and its wall families. A live re-scan lines up
 * again every few seconds on the same one.
 */
class RescanReference private constructor(
    internal val points: RescanSamples,
    internal val floorY: Float?,
    internal val families: List<Float>,
    /** The scan's footprint, `[minX, minZ, maxX, maxZ]`: the room it saw from inside. */
    internal val footprint: FloatArray,
) {
    private val grid = RescanGrid(points, RescanRegistrar.GRID_M)
    private val occupied = HashSet<Long>().also { set ->
        for (i in 0 until points.count) set.add(rescanCellKey(points.p, i, RescanRegistrar.COARSE_M))
    }

    /** The planes along each family's two axes, [RescanAxis] by [RescanAxis], computed once per family. */
    internal val planes: List<Array<List<RescanPeak>>> = families.map { phi -> RescanRegistrar.planesOf(points, phi) }

    /** The nearest point within [reach] of ([x], [y], [z]), or −1. [reach] at most [RescanRegistrar.GRID_M]. */
    internal fun nearest(x: Float, y: Float, z: Float, reach: Float): Int = grid.nearest(x, y, z, reach)

    /** Whether the scan has a point near ([x], [y], [z]): about a [RescanRegistrar.COARSE_M] cell. */
    internal fun near(x: Float, y: Float, z: Float): Boolean {
        val c = RescanRegistrar.COARSE_M
        val bx = floor(x / c - HALF).toInt()
        val by = floor(y / c - HALF).toInt()
        val bz = floor(z / c - HALF).toInt()
        for (dx in 0..1) for (dy in 0..1) for (dz in 0..1) {
            if (occupied.contains(rescanKey(bx + dx, by + dy, bz + dz))) return true
        }
        return false
    }

    /** Whether the scan has any point within [reach] of ([x], [y], [z]), [reach] at most a grid cell. */
    internal fun anyWithin(x: Float, y: Float, z: Float, reach: Float): Boolean = grid.nearest(x, y, z, reach) >= 0

    companion object {
        /** [cloud] read for lining up; `null` without normals or points. */
        fun of(cloud: DenseCloud): RescanReference? {
            val points = RescanSamples.of(cloud, RescanRegistrar.TARGET_M, Int.MAX_VALUE) ?: return null
            val b = points.bounds()
            return RescanReference(
                points = points,
                floorY = RescanRegistrar.floorOf(points),
                families = RescanRegistrar.familiesOf(points),
                footprint = floatArrayOf(b[0], b[2], b[3], b[5]),
            )
        }

        private const val HALF = 0.5f
    }
}

/** Lines a re-scan up on the scan it completes: see the file's header. */
object RescanRegistrar {
    /** The re-scan's sampling, metres: one surfel per voxel this wide. */
    const val SAMPLE_M = 0.04f

    /** The most samples of a re-scan read; more are thinned evenly. */
    const val MAX_SAMPLES = 6000

    /** The most samples ICP iterates on; the verdict reads them all. */
    const val ICP_SAMPLES = 2000

    /** The most samples a candidate's quick score reads. */
    const val COARSE_SAMPLES = 1500

    /** The scan's sampling as a target, metres. */
    const val TARGET_M = 0.03f

    /** The nearest-neighbour grid's cell, metres: the farthest any search reaches. */
    const val GRID_M = 0.12f

    /** The quick score's cell, metres. */
    const val COARSE_M = 0.05f

    /** How upward a floor surfel's normal is, its y at least. */
    const val UP_NORMAL = 0.9f

    /** How level a wall surfel's normal is, |y| at most. */
    const val LEVEL_NORMAL = 0.3f

    /** The floor histogram's bin, metres. */
    const val FLOOR_BIN_M = 0.02f

    /** How far above the lowest upward surfel the floor is looked for, metres. */
    const val FLOOR_SEARCH_M = 3f

    /** A floor level holds at least this share of the fullest upward level. */
    const val FLOOR_SHARE = 0.25f

    /** The fewest upward samples a floor is read from. */
    const val MIN_FLOOR_SAMPLES = 30

    /** The fewest vertical samples a wall family is read from. */
    const val MIN_WALL_SAMPLES = 50

    /** How far either side of a family's peak its direction is averaged over, degrees. */
    const val FAMILY_WINDOW_DEG = 4

    /** A second family is at least this far from the first, degrees (of a quarter turn). */
    const val FAMILY_GAP_DEG = 15

    /** …and at least this share of it. */
    const val SECOND_FAMILY_SHARE = 0.35f

    /** How closely a plane's surfel faces its axis, the cosine. */
    const val PLANE_NORMAL = 0.97f

    /** The plane histogram's bin, metres. */
    const val PLANE_BIN_M = 0.02f

    /** The fewest samples a plane is read from. */
    const val MIN_PLANE_SAMPLES = 12

    /** The most planes kept on each side of an axis. */
    const val MAX_PLANES = 8

    /** The most shifts kept along an axis. */
    const val MAX_SHIFTS = 5

    /** The candidates refined by ICP, best quick scores first. */
    const val ICP_CANDIDATES = 8

    /** ICP's most iterations. */
    const val ICP_ITERATIONS = 40

    /** ICP's first reach, metres; it shrinks by [ICP_REACH_DECAY] an iteration to [MATCH_M]. */
    const val ICP_FIRST_REACH_M = 0.12f

    /** How ICP's reach shrinks each iteration. */
    const val ICP_REACH_DECAY = 0.8f

    /** Past this distance off the plane, a match weighs less (Huber), metres. */
    const val HUBER_M = 0.01f

    /** A re-scan sample matches the scan when it lies this close to its plane, metres. */
    const val MATCH_M = 0.03f

    /** …its nearest scan point at most this far, metres. */
    const val NEAR_M = 0.08f

    /** …and their normals agree this well, the cosine. */
    const val MATCH_NORMAL = 0.7f

    /** The share of the re-scan that must land on the scan. */
    const val MIN_OVERLAP = 0.3f

    /** The fewest re-scan samples that must land on it. */
    const val MIN_MATCHED = 150

    /**
     * The weakest direction's stiffness, per matched sample: below it, a direction slides. Two
     * sessions of a real room agree on far fewer points than two synthetic ones, so a part that
     * pins the pose only weakly settles on the wrong one: on a corpus of real re-scans, every part
     * below this either was another room or came back 7° to 13° off.
     */
    const val MIN_STIFFNESS = 0.1f

    /** A second fit this close to the best, by samples matched, makes the best ambiguous. */
    const val AMBIGUOUS_SHARE = 0.85f

    /**
     * Two fits are different places when they turn this much apart, degrees… ICP run from nearby
     * candidates on a real re-scan stops a few degrees apart in the same place, as many samples
     * matched: those are one fit, not two. Nearer than this, a fit that scores as well is taken
     * for the same place; on a corpus of real re-scans, every such fit up to 9.3° off sat beside
     * a best within 2° of the truth.
     */
    const val DISTINCT_DEG = 10f

    /**
     * …or move the re-scan's centre this far apart, metres. Those nearby ICP stops sit within
     * 8 cm of each other at the centre; a fit further off that scores as well is another place.
     */
    const val DISTINCT_M = 0.1f

    /** How far around a part to fill in the re-scan may add what the scan lacks, metres. */
    const val OPEN_MARGIN_M = 0.3f

    /** A re-scan point with no scan point this close, inside the scan's room, is in its empty space, metres. */
    const val FREE_M = 0.12f

    /** Contradictions are read from this high above the floor… */
    const val CONTRADICTION_LOW_M = 0.1f

    /** …to this high, metres: a ceiling one scan saw and not the other is no contradiction. */
    const val CONTRADICTION_TOP_M = 2f

    /**
     * The most of the re-scan, outside the parts to fill in, that may land in the scan's empty space.
     * Some always does on a real room: what the scan missed and no part to fill in covers.
     */
    const val MAX_CONTRADICTION = 0.25f

    /**
     * The most of the re-scan's wall samples by the scan's surface, outside the parts to fill in,
     * that may sit off it: a room whose walls stand a few centimetres from the scan's lands its
     * walls beside the scan's, matching none. Two real sessions of one room leave 0.30 to 0.70
     * of them off; one with its walls 4 to 8 cm in leaves 0.75 to 0.85.
     */
    const val MAX_OFF_WALL = 0.75f

    /**
     * The most of those wall samples that may sit further than [NEAR_M] from the scan, yet within
     * [FREE_M]: walls 8 to 12 cm from the scan's are too far to match and too near to be read as
     * open space. Real sessions of one room leave at most 0.23 of them there; such a room, 0.47
     * and more.
     */
    const val MAX_BESIDE_WALL = 0.4f

    /** The fewest re-scan samples, outside the parts to fill in, the scan must have to judge it by. */
    const val MIN_JUDGED = 300

    /**
     * [rescan] lined up on [reference], or refused. [open] are the boxes (`[minX, minY, minZ,
     * maxX, maxY, maxZ]`, the scan's frame) the re-scan is there to fill in: there, and
     * [OPEN_MARGIN_M] around, it may add what the scan lacks; elsewhere it must agree with it.
     */
    fun register(reference: DenseCloud, rescan: DenseCloud, open: List<FloatArray> = emptyList()): RescanAlignment {
        val ref = RescanReference.of(reference) ?: return RescanAlignment.Refused(RescanRefusal.NoNormals)
        return register(ref, rescan, open)
    }

    /** As the other [register], on a [RescanReference] read once. */
    fun register(reference: RescanReference, rescan: DenseCloud, open: List<FloatArray> = emptyList()): RescanAlignment {
        val src = RescanSamples.of(rescan, SAMPLE_M, MAX_SAMPLES) ?: return RescanAlignment.Refused(RescanRefusal.NoNormals)
        val floorA = reference.floorY
        val floorB = floorOf(src)
        if (floorA == null || floorB == null) return RescanAlignment.Refused(RescanRefusal.NoFloor)
        val familiesB = familiesOf(src)
        if (reference.families.isEmpty() || familiesB.isEmpty()) return RescanAlignment.Refused(RescanRefusal.NoWalls)

        val candidates = candidates(reference, src, familiesB, floorA - floorB)
        if (candidates.isEmpty()) return RescanAlignment.Refused(RescanRefusal.Unconstrained)

        val icp = src.thinned(ICP_SAMPLES)
        val fits = candidates.take(ICP_CANDIDATES)
            .mapNotNull { refine(reference, icp, it) }
            .map { fit(reference, src, it, open) }
            .sortedByDescending { it.matched }
        val best = fits.firstOrNull() ?: return RescanAlignment.Refused(RescanRefusal.TooLittleOverlap)
        val overlap = best.matched.toFloat() / src.count
        if (best.matched < MIN_MATCHED || overlap < MIN_OVERLAP) return RescanAlignment.Refused(RescanRefusal.TooLittleOverlap)
        if (stiffness(reference, src, best.pose) < MIN_STIFFNESS) return RescanAlignment.Refused(RescanRefusal.Unconstrained)

        // A rival is another pose that lands enough on the scan to be lined up itself and explains
        // what the best one explains: as many samples, and as many of its own as the best explains
        // that it misses. A sample is explained when it lands on the scan or in a part to fill in,
        // where the scan has nothing to land on: in a room that looks the same turned round, the
        // true pose drops the part's samples into the hole the twin fills with the far corner.
        // One slid along a wall and the floor keeps most of the matches, but loses the few that
        // pin it, and is no rival.
        val centre = src.centre()
        val rival = fits.drop(1).firstOrNull { other ->
            val distinct = abs(other.pose.turnFrom(best.pose)) > DISTINCT_DEG ||
                other.pose.gap(best.pose, centre[0], centre[1], centre[2]) > DISTINCT_M
            distinct && other.matched >= MIN_MATCHED && other.matched >= MIN_OVERLAP * src.count &&
                other.explainedCount >= AMBIGUOUS_SHARE * best.explainedCount &&
                other.beyond(best) >= AMBIGUOUS_SHARE * best.beyond(other)
        }
        if (rival != null) return RescanAlignment.Refused(RescanRefusal.Ambiguous)
        val judgement = judge(reference, src, best.pose, open, floorA)
        if (judgement.judged < MIN_JUDGED) return RescanAlignment.Refused(RescanRefusal.TooLittleOverlap)
        if (judgement.free > MAX_CONTRADICTION || judgement.offWall > MAX_OFF_WALL || judgement.besideWall > MAX_BESIDE_WALL) {
            return RescanAlignment.Refused(RescanRefusal.Contradicts)
        }
        return RescanAlignment.Lined(best.pose, overlap, best.rmsM)
    }

    // ---------------------------------------------------------------- floor and walls

    /** The floor's height: the lowest level holding a fair share of the upward surfels. */
    internal fun floorOf(s: RescanSamples): Float? {
        val ys = (0 until s.count).filter { s.n[it * 3 + 1] >= UP_NORMAL }.map { s.p[it * 3 + 1] }
        if (ys.size < MIN_FLOOR_SAMPLES) return null
        val low = ys.min()
        val bins = IntArray((FLOOR_SEARCH_M / FLOOR_BIN_M).toInt() + 1)
        for (y in ys) {
            val b = ((y - low) / FLOOR_BIN_M).toInt()
            if (b in bins.indices) bins[b]++
        }
        // Two bins together: a floor's scatter straddles a bin edge.
        val pairs = IntArray(bins.size) { bins[it] + if (it + 1 < bins.size) bins[it + 1] else 0 }
        val full = pairs.max()
        val at = pairs.indices.firstOrNull { pairs[it] >= max(MIN_FLOOR_SAMPLES.toFloat(), FLOOR_SHARE * full) } ?: return null
        val from = low + at * FLOOR_BIN_M
        val level = ys.filter { it >= from && it < from + 2 * FLOOR_BIN_M }
        return level.average().toFloat()
    }

    /**
     * The directions the vertical surfels face, folded onto a quarter turn, radians in `[0, π/2)`:
     * the strongest, then a second one far enough from it and strong enough.
     */
    internal fun familiesOf(s: RescanSamples): List<Float> {
        val angles = ArrayList<Float>()
        for (i in 0 until s.count) {
            if (abs(s.n[i * 3 + 1]) > LEVEL_NORMAL) continue
            angles.add(fold(atan2(s.n[i * 3 + 2], s.n[i * 3])))
        }
        if (angles.size < MIN_WALL_SAMPLES) return emptyList()
        val bins = FloatArray(QUARTER_DEG)
        for (a in angles) bins[(Math.toDegrees(a.toDouble()).toInt()).coerceIn(0, QUARTER_DEG - 1)] += 1f
        val smooth = FloatArray(QUARTER_DEG) { b ->
            var sum = 0f
            for (d in -2..2) sum += bins[Math.floorMod(b + d, QUARTER_DEG)] * (SMOOTH_WEIGHT - abs(d))
            sum
        }
        val first = smooth.indices.maxBy { smooth[it] }
        val second = smooth.indices
            .filter { quarterGap(it, first) >= FAMILY_GAP_DEG && smooth[it] >= SECOND_FAMILY_SHARE * smooth[first] }
            .maxByOrNull { smooth[it] }
        return listOfNotNull(first, second).map { peak -> refineFamily(angles, peak) }
    }

    /** The mean direction of [angles] within [FAMILY_WINDOW_DEG] of bin [peak], on the folded circle. */
    private fun refineFamily(angles: List<Float>, peak: Int): Float {
        var sx = 0.0
        var sy = 0.0
        for (a in angles) {
            val deg = Math.toDegrees(a.toDouble())
            if (quarterGap(deg, peak + HALF_BIN) > FAMILY_WINDOW_DEG) continue
            sx += cos(a * QUARTERS.toDouble())
            sy += sin(a * QUARTERS.toDouble())
        }
        return fold((atan2(sy, sx) / QUARTERS).toFloat())
    }

    /**
     * The planes [s] sees along family [phi]'s two axes: for each of `+u`, `−u`, `+v`, `−v`
     * ([RescanAxis]), the offsets along that axis (`u` for the first two, `v` for the others) where
     * enough surfels face that way.
     */
    internal fun planesOf(s: RescanSamples, phi: Float): Array<List<RescanPeak>> {
        val ux = cos(phi)
        val uz = sin(phi)
        return Array(RescanAxis.entries.size) { a ->
            val axis = RescanAxis.entries[a]
            val (ax, az) = if (axis.alongU) ux to uz else -uz to ux
            val sign = if (axis.positive) 1f else -1f
            val offsets = ArrayList<Float>()
            for (i in 0 until s.count) {
                val facing = (s.n[i * 3] * ax + s.n[i * 3 + 2] * az) * sign
                if (facing < PLANE_NORMAL) continue
                offsets.add(s.p[i * 3] * ax + s.p[i * 3 + 2] * az)
            }
            peaks(offsets)
        }
    }

    /** The offsets [values] gather at: local maxima of a [PLANE_BIN_M] histogram, strongest first. */
    private fun peaks(values: List<Float>): List<RescanPeak> {
        if (values.size < MIN_PLANE_SAMPLES) return emptyList()
        val bins = HashMap<Int, Int>()
        for (v in values) bins.merge(floor(v / PLANE_BIN_M).toInt(), 1, Int::plus)
        fun at(b: Int) = bins[b] ?: 0
        fun smooth(b: Int) = at(b - 1) + 2 * at(b) + at(b + 1)
        val found = ArrayList<RescanPeak>()
        for (b in bins.keys.sorted()) {
            val here = smooth(b)
            if (here < 2 * MIN_PLANE_SAMPLES) continue
            if ((1..PEAK_SPREAD_BINS).any { smooth(b - it) > here || smooth(b + it) >= here }) continue
            var sum = 0.0
            var n = 0
            for (v in values) {
                if (abs(v - (b + HALF_BIN) * PLANE_BIN_M) <= PLANE_BIN_M * PEAK_WINDOW_BINS) {
                    sum += v
                    n++
                }
            }
            if (n > 0) found.add(RescanPeak((sum / n).toFloat(), here))
        }
        return found.sortedByDescending { it.weight }.take(MAX_PLANES)
    }

    // ---------------------------------------------------------------- candidates

    /** Every (turn, shift) the floor, the families and the planes suggest, best quick score first. */
    private fun candidates(ref: RescanReference, src: RescanSamples, familiesB: List<Float>, ty: Float): List<RescanPose> {
        val turns = ArrayList<Pair<Float, Int>>() // (yaw, the scan's family it lines up on)
        for ((fa, phiA) in ref.families.withIndex()) {
            for (phiB in familiesB) {
                for (q in 0 until QUARTERS) {
                    val yaw = RescanPose.wrap(phiA - phiB + q * PI / 2).toFloat()
                    if (turns.none { abs(RescanPose.wrap((it.first - yaw).toDouble())) < Math.toRadians(1.0) }) {
                        turns.add(yaw to fa)
                    }
                }
            }
        }
        val coarse = src.thinned(COARSE_SAMPLES)
        val scored = ArrayList<Pair<RescanPose, Int>>()
        for ((yaw, fa) in turns) {
            val phi = ref.families[fa]
            val turned = src.turned(yaw)
            val planesB = planesOf(turned, phi)
            val planesA = ref.planes[fa]
            val du = shifts(planesA, planesB, alongU = true)
            val dv = shifts(planesA, planesB, alongU = false)
            val ux = cos(phi)
            val uz = sin(phi)
            for (a in du) {
                for (b in dv) {
                    val pose = RescanPose(yaw, a * ux - b * uz, ty, a * uz + b * ux)
                    scored.add(pose to quickScore(ref, coarse, pose))
                }
            }
        }
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    /** The shifts along u (or v) that lay the re-scan's planes on the scan's, most voted first. */
    private fun shifts(a: Array<List<RescanPeak>>, b: Array<List<RescanPeak>>, alongU: Boolean): List<Float> {
        val votes = HashMap<Int, Float>()
        val raw = ArrayList<Pair<Float, Float>>()
        for (axis in RescanAxis.entries) {
            if (axis.alongU != alongU) continue
            for (pa in a[axis.ordinal]) for (pb in b[axis.ordinal]) {
                val shift = pa.at - pb.at
                val weight = min(pa.weight, pb.weight).toFloat()
                raw.add(shift to weight)
                votes.merge(floor(shift / PLANE_BIN_M).toInt(), weight, Float::plus)
            }
        }
        fun at(k: Int) = votes[k] ?: 0f
        fun smooth(k: Int) = at(k - 1) + at(k) + at(k + 1)
        val ranked = votes.keys.sortedByDescending { smooth(it) }
        val picked = ArrayList<Int>()
        for (k in ranked) {
            if (picked.size == MAX_SHIFTS) break
            if (picked.any { abs(it - k) <= SHIFT_SPREAD_BINS }) continue
            picked.add(k)
        }
        return picked.map { k ->
            var sum = 0.0
            var w = 0.0
            for ((shift, weight) in raw) {
                if (abs(shift - (k + HALF_BIN) * PLANE_BIN_M) <= PLANE_BIN_M * PEAK_WINDOW_BINS) {
                    sum += shift * weight
                    w += weight
                }
            }
            if (w > 0) (sum / w).toFloat() else (k + HALF_BIN) * PLANE_BIN_M
        }
    }

    /** How many of [s] land next to a point of the scan once moved by [pose]. */
    private fun quickScore(ref: RescanReference, s: RescanSamples, pose: RescanPose): Int {
        var hits = 0
        for (i in 0 until s.count) {
            val x = s.p[i * 3]
            val z = s.p[i * 3 + 2]
            if (ref.near(pose.x(x, z), pose.y(s.p[i * 3 + 1]), pose.z(x, z))) hits++
        }
        return hits
    }

    // ---------------------------------------------------------------- ICP and the verdict

    /** [start] refined by point-to-plane ICP over the turn and the shift; `null` when it loses its matches. */
    private fun refine(ref: RescanReference, s: RescanSamples, start: RescanPose): RescanPose? {
        var yaw = start.yaw.toDouble()
        var tx = start.tx.toDouble()
        var ty = start.ty.toDouble()
        var tz = start.tz.toDouble()
        var reach = ICP_FIRST_REACH_M
        val h = DoubleArray(DOF * DOF)
        val g = DoubleArray(DOF)
        val j = DoubleArray(DOF)
        for (iteration in 0 until ICP_ITERATIONS) {
            h.fill(0.0)
            g.fill(0.0)
            val c = cos(yaw)
            val sn = sin(yaw)
            var matched = 0
            for (i in 0 until s.count) {
                val x = s.p[i * 3]
                val y = s.p[i * 3 + 1]
                val z = s.p[i * 3 + 2]
                val rx = x * c - z * sn
                val rz = x * sn + z * c
                val px = (rx + tx).toFloat()
                val py = (y + ty).toFloat()
                val pz = (rz + tz).toFloat()
                val q = ref.nearest(px, py, pz, reach)
                if (q < 0) continue
                val qp = ref.points.p
                val qn = ref.points.n
                val nx = qn[q * 3].toDouble()
                val ny = qn[q * 3 + 1].toDouble()
                val nz = qn[q * 3 + 2].toDouble()
                val mnx = s.n[i * 3] * c - s.n[i * 3 + 2] * sn
                val mnz = s.n[i * 3] * sn + s.n[i * 3 + 2] * c
                if (nx * mnx + ny * s.n[i * 3 + 1] + nz * mnz < MATCH_NORMAL) continue
                val e = nx * (px - qp[q * 3]) + ny * (py - qp[q * 3 + 1]) + nz * (pz - qp[q * 3 + 2])
                val w = if (abs(e) <= HUBER_M) 1.0 else HUBER_M / abs(e)
                j[0] = -nx * rz + nz * rx
                j[1] = nx
                j[2] = ny
                j[3] = nz
                for (r in 0 until DOF) {
                    g[r] += w * j[r] * e
                    for (k in 0 until DOF) h[r * DOF + k] += w * j[r] * j[k]
                }
                matched++
            }
            if (matched < MIN_MATCHED / 2) return null
            for (r in 0 until DOF) g[r] = -g[r]
            val step = solve(h, g) ?: return null
            yaw += step[0]
            tx += step[1]
            ty += step[2]
            tz += step[3]
            val settled = abs(step[0]) < SETTLED_RAD && abs(step[1]) + abs(step[2]) + abs(step[3]) < SETTLED_M
            if (settled && reach <= MATCH_M) break
            reach = max(MATCH_M, reach * ICP_REACH_DECAY)
        }
        return RescanPose(RescanPose.wrap(yaw).toFloat(), tx.toFloat(), ty.toFloat(), tz.toFloat())
    }

    /**
     * A pose and how well it fits: how many samples it lands on the scan's surface ([matched]) and
     * how far off, and which it explains ([explained]): landed, or dropped in a part to fill in.
     */
    private class Fit(val pose: RescanPose, val explained: BooleanArray, val matched: Int, val rmsM: Float) {
        val explainedCount: Int = explained.count { it }

        /** How many samples this fit explains and [other] does not. */
        fun beyond(other: Fit): Int = explained.indices.count { explained[it] && !other.explained[it] }
    }

    /** How many of [s] land on the scan's surface once moved by [pose], how far off it, and which [open] excuses. */
    private fun fit(ref: RescanReference, s: RescanSamples, pose: RescanPose, open: List<FloatArray>): Fit {
        val explained = BooleanArray(s.count)
        var matched = 0
        var sq = 0.0
        forEachMatch(ref, s, pose) { i, _, e ->
            explained[i] = true
            matched++
            sq += e.toDouble() * e
        }
        if (open.isNotEmpty()) {
            for (i in 0 until s.count) {
                if (explained[i]) continue
                val x = s.p[i * 3]
                val z = s.p[i * 3 + 2]
                val px = pose.x(x, z)
                val py = pose.y(s.p[i * 3 + 1])
                val pz = pose.z(x, z)
                explained[i] = open.any { inside(it, px, py, pz, OPEN_MARGIN_M) }
            }
        }
        return Fit(pose, explained, matched, if (matched == 0) 0f else sqrt(sq / matched).toFloat())
    }

    /** Calls [match] with the sample, the scan point it lands on and how far off its plane, per match. */
    private inline fun forEachMatch(ref: RescanReference, s: RescanSamples, pose: RescanPose, match: (Int, Int, Float) -> Unit) {
        val c = cos(pose.yaw)
        val sn = sin(pose.yaw)
        for (i in 0 until s.count) {
            val x = s.p[i * 3]
            val z = s.p[i * 3 + 2]
            val px = pose.x(x, z)
            val py = pose.y(s.p[i * 3 + 1])
            val pz = pose.z(x, z)
            val q = ref.nearest(px, py, pz, NEAR_M)
            if (q < 0) continue
            val qp = ref.points.p
            val qn = ref.points.n
            val mnx = s.n[i * 3] * c - s.n[i * 3 + 2] * sn
            val mnz = s.n[i * 3] * sn + s.n[i * 3 + 2] * c
            if (qn[q * 3] * mnx + qn[q * 3 + 1] * s.n[i * 3 + 1] + qn[q * 3 + 2] * mnz < MATCH_NORMAL) continue
            val e = qn[q * 3] * (px - qp[q * 3]) + qn[q * 3 + 1] * (py - qp[q * 3 + 1]) + qn[q * 3 + 2] * (pz - qp[q * 3 + 2])
            if (abs(e) > MATCH_M) continue
            match(i, q, e)
        }
    }

    /**
     * The weakest direction's stiffness at [pose], per match: the smallest eigenvalue of ICP's
     * normal matrix, the turn measured as the shift it gives a metre from the matches' centre. A
     * flat wall gives none along itself.
     */
    private fun stiffness(ref: RescanReference, s: RescanSamples, pose: RescanPose): Float {
        val qs = ArrayList<Int>()
        forEachMatch(ref, s, pose) { _, q, _ -> qs.add(q) }
        if (qs.isEmpty()) return 0f
        val p = ref.points.p
        val n = ref.points.n
        val mx = qs.sumOf { p[it * 3].toDouble() } / qs.size
        val mz = qs.sumOf { p[it * 3 + 2].toDouble() } / qs.size
        val h = DoubleArray(DOF * DOF)
        val j = DoubleArray(DOF)
        for (q in qs) {
            val nx = n[q * 3].toDouble()
            val nz = n[q * 3 + 2].toDouble()
            j[0] = -nx * (p[q * 3 + 2] - mz) + nz * (p[q * 3] - mx)
            j[1] = nx
            j[2] = n[q * 3 + 1].toDouble()
            j[3] = nz
            for (r in 0 until DOF) for (k in 0 until DOF) h[r * DOF + k] += j[r] * j[k]
        }
        for (i in h.indices) h[i] /= qs.size.toDouble()
        return smallestEigenvalue(h).toFloat()
    }

    /**
     * What the scan says of the re-scan moved by [pose], outside the [open] boxes and inside the
     * scan's room: [judged] samples, the share [free] out in the room's empty middle where the scan
     * saw nothing, and the share [offWall] of its wall samples by the scan's surface that sit off
     * it. A sample by the scan's surface but off it, or facing another way, is no contradiction on
     * its own: two depth sessions of one room disagree like that on a third to a half of their
     * points (clutter, edges, depth noise), and counting it refused every real re-scan. Walls are
     * the exception that is kept: they are what two sessions agree on best.
     */
    internal class Judgement(val judged: Int, val free: Float, val offWall: Float, val besideWall: Float)

    internal fun judge(ref: RescanReference, s: RescanSamples, pose: RescanPose, open: List<FloatArray>, floorY: Float): Judgement {
        val matched = BooleanArray(s.count)
        forEachMatch(ref, s, pose) { i, _, _ -> matched[i] = true }
        val f = ref.footprint
        var judged = 0
        var against = 0
        var walls = 0
        var offWall = 0
        var beside = 0
        for (i in 0 until s.count) {
            val x0 = s.p[i * 3]
            val z0 = s.p[i * 3 + 2]
            val x = pose.x(x0, z0)
            val y = pose.y(s.p[i * 3 + 1])
            val z = pose.z(x0, z0)
            if (y < floorY + CONTRADICTION_LOW_M || y > floorY + CONTRADICTION_TOP_M) continue
            if (x < f[0] || x > f[2] || z < f[1] || z > f[3]) continue
            if (open.any { inside(it, x, y, z, OPEN_MARGIN_M) }) continue
            judged++
            val near = ref.anyWithin(x, y, z, FREE_M)
            if (!matched[i] && !near) against++
            if (near && abs(s.n[i * 3 + 1]) <= LEVEL_NORMAL) {
                walls++
                if (!matched[i]) {
                    offWall++
                    if (!ref.anyWithin(x, y, z, NEAR_M)) beside++
                }
            }
        }
        return Judgement(
            judged,
            if (judged == 0) 0f else against.toFloat() / judged,
            if (walls == 0) 0f else offWall.toFloat() / walls,
            if (walls == 0) 0f else beside.toFloat() / walls,
        )
    }

    // ---------------------------------------------------------------- arithmetic

    /** [angle] folded onto a quarter turn, `[0, π/2)`. */
    private fun fold(angle: Float): Float {
        val q = (PI / 2).toFloat()
        var a = angle % q
        if (a < 0) a += q
        return if (a >= q) 0f else a
    }

    /** How far apart two directions are on the folded circle, degrees. */
    private fun quarterGap(a: Number, b: Number): Double {
        val d = abs(a.toDouble() - b.toDouble()) % QUARTER_DEG
        return min(d, QUARTER_DEG - d)
    }

    /** [h] x = [g] for a 4 × 4 [h], by Gaussian elimination with partial pivoting; `null` when singular. */
    private fun solve(h: DoubleArray, g: DoubleArray): DoubleArray? {
        val a = h.copyOf()
        val b = g.copyOf()
        for (col in 0 until DOF) {
            val pivot = (col until DOF).maxBy { abs(a[it * DOF + col]) }
            if (abs(a[pivot * DOF + col]) < SINGULAR) return null
            if (pivot != col) {
                for (k in 0 until DOF) {
                    val t = a[col * DOF + k]
                    a[col * DOF + k] = a[pivot * DOF + k]
                    a[pivot * DOF + k] = t
                }
                val t = b[col]
                b[col] = b[pivot]
                b[pivot] = t
            }
            for (r in col + 1 until DOF) {
                val f = a[r * DOF + col] / a[col * DOF + col]
                for (k in col until DOF) a[r * DOF + k] -= f * a[col * DOF + k]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(DOF)
        for (r in DOF - 1 downTo 0) {
            var sum = b[r]
            for (k in r + 1 until DOF) sum -= a[r * DOF + k] * x[k]
            x[r] = sum / a[r * DOF + r]
        }
        return x
    }

    /** The smallest eigenvalue of a symmetric 4 × 4 [m], by Jacobi rotations. */
    internal fun smallestEigenvalue(m: DoubleArray): Double {
        val a = m.copyOf()
        repeat(JACOBI_SWEEPS) {
            for (p in 0 until DOF - 1) for (q in p + 1 until DOF) {
                val apq = a[p * DOF + q]
                if (abs(apq) < JACOBI_EPSILON) continue
                val theta = (a[q * DOF + q] - a[p * DOF + p]) / (2 * apq)
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1)
                val s = t * c
                for (k in 0 until DOF) {
                    val akp = a[k * DOF + p]
                    val akq = a[k * DOF + q]
                    a[k * DOF + p] = c * akp - s * akq
                    a[k * DOF + q] = s * akp + c * akq
                }
                for (k in 0 until DOF) {
                    val apk = a[p * DOF + k]
                    val aqk = a[q * DOF + k]
                    a[p * DOF + k] = c * apk - s * aqk
                    a[q * DOF + k] = s * apk + c * aqk
                }
            }
        }
        return (0 until DOF).minOf { a[it * DOF + it] }
    }

    /** Whether ([x], [y], [z]) lies in [box] grown by [margin]. */
    internal fun inside(box: FloatArray, x: Float, y: Float, z: Float, margin: Float): Boolean =
        x >= box[0] - margin && x <= box[3] + margin &&
            y >= box[1] - margin && y <= box[4] + margin &&
            z >= box[2] - margin && z <= box[5] + margin

    private const val QUARTERS = 4
    private const val QUARTER_DEG = 90
    private const val HALF_BIN = 0.5f
    private const val SMOOTH_WEIGHT = 3
    private const val PEAK_SPREAD_BINS = 3
    private const val PEAK_WINDOW_BINS = 1.5f
    private const val SHIFT_SPREAD_BINS = 3
    private const val DOF = 4
    private const val SETTLED_RAD = 1e-5
    private const val SETTLED_M = 1e-4
    private const val SINGULAR = 1e-12
    private const val JACOBI_SWEEPS = 12
    private const val JACOBI_EPSILON = 1e-15
}

/** The four sides of a wall family's two axes: which axis, and which way a surfel faces along it. */
internal enum class RescanAxis(val alongU: Boolean, val positive: Boolean) { PlusU(true, true), MinusU(true, false), PlusV(false, true), MinusV(false, false) }

/** A plane along an axis: its offset, metres, and how many samples it holds. */
internal class RescanPeak(val at: Float, val weight: Int)

/** A cloud's surfels thinned to one per voxel, with unit normals: what lining up reads. */
internal class RescanSamples(val p: FloatArray, val n: FloatArray) {
    val count: Int get() = p.size / 3

    /** At most [max] of these, evenly. */
    fun thinned(max: Int): RescanSamples {
        if (count <= max) return this
        val stride = (count + max - 1) / max
        val kept = (0 until count step stride).toList()
        return RescanSamples(
            FloatArray(kept.size * 3) { p[kept[it / 3] * 3 + it % 3] },
            FloatArray(kept.size * 3) { n[kept[it / 3] * 3 + it % 3] },
        )
    }

    /** These turned by [yaw] about +Y, positions and normals. */
    fun turned(yaw: Float): RescanSamples {
        val pose = RescanPose(yaw, 0f, 0f, 0f)
        val c = cos(yaw)
        val s = sin(yaw)
        val tp = FloatArray(p.size)
        val tn = FloatArray(n.size)
        for (i in 0 until count) {
            tp[i * 3] = pose.x(p[i * 3], p[i * 3 + 2])
            tp[i * 3 + 1] = p[i * 3 + 1]
            tp[i * 3 + 2] = pose.z(p[i * 3], p[i * 3 + 2])
            tn[i * 3] = n[i * 3] * c - n[i * 3 + 2] * s
            tn[i * 3 + 1] = n[i * 3 + 1]
            tn[i * 3 + 2] = n[i * 3] * s + n[i * 3 + 2] * c
        }
        return RescanSamples(tp, tn)
    }

    /** `[minX, minY, minZ, maxX, maxY, maxZ]`. */
    fun bounds(): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until count) {
            for (a in 0 until 3) {
                b[a] = min(b[a], p[i * 3 + a])
                b[a + 3] = max(b[a + 3], p[i * 3 + a])
            }
        }
        return b
    }

    /** The mean position. */
    fun centre(): FloatArray {
        val m = FloatArray(3)
        for (i in 0 until count) for (a in 0 until 3) m[a] += p[i * 3 + a]
        if (count > 0) for (a in 0 until 3) m[a] /= count
        return m
    }

    companion object {
        /** [cloud]'s first surfel in each [cellM] voxel, at most [max] of them; `null` without normals or points. */
        fun of(cloud: DenseCloud, cellM: Float, max: Int): RescanSamples? {
            val normals = cloud.normals ?: return null
            val seen = HashSet<Long>()
            val kept = ArrayList<Int>()
            for (i in 0 until cloud.count) {
                val nx = normals[i * 3]
                val ny = normals[i * 3 + 1]
                val nz = normals[i * 3 + 2]
                if (nx * nx + ny * ny + nz * nz < MIN_NORMAL_SQ) continue
                if (seen.add(rescanCellKey(cloud.positions, i, cellM))) kept.add(i)
            }
            if (kept.isEmpty()) return null
            val p = FloatArray(kept.size * 3) { cloud.positions[kept[it / 3] * 3 + it % 3] }
            val n = FloatArray(kept.size * 3)
            for ((k, i) in kept.withIndex()) {
                val nx = normals[i * 3]
                val ny = normals[i * 3 + 1]
                val nz = normals[i * 3 + 2]
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                n[k * 3] = nx / len
                n[k * 3 + 1] = ny / len
                n[k * 3 + 2] = nz / len
            }
            return RescanSamples(p, n).thinned(max)
        }

        private const val MIN_NORMAL_SQ = 0.25f
    }
}

/** Points bucketed in [cellM] cells: the nearest one within a cell's reach, fast. */
internal class RescanGrid(private val s: RescanSamples, private val cellM: Float) {
    private val cells: HashMap<Long, IntArray> = run {
        val lists = HashMap<Long, MutableList<Int>>()
        for (i in 0 until s.count) lists.getOrPut(rescanCellKey(s.p, i, cellM)) { ArrayList(CELL_GUESS) }.add(i)
        HashMap<Long, IntArray>(lists.size * 2).also { out -> for ((k, v) in lists) out[k] = v.toIntArray() }
    }

    /** The nearest point within [reach] (at most [cellM]) of ([x], [y], [z]), or −1. */
    fun nearest(x: Float, y: Float, z: Float, reach: Float): Int {
        val cx = floor(x / cellM).toInt()
        val cy = floor(y / cellM).toInt()
        val cz = floor(z / cellM).toInt()
        var best = -1
        var bestSq = reach * reach
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            val bucket = cells[rescanKey(cx + dx, cy + dy, cz + dz)] ?: continue
            for (i in bucket) {
                val ex = s.p[i * 3] - x
                val ey = s.p[i * 3 + 1] - y
                val ez = s.p[i * 3 + 2] - z
                val d = ex * ex + ey * ey + ez * ez
                if (d <= bestSq) {
                    bestSq = d
                    best = i
                }
            }
        }
        return best
    }

    private companion object {
        const val CELL_GUESS = 8
    }
}

/** Point [i] of [p]'s voxel of side [cellM], packed. */
internal fun rescanCellKey(p: FloatArray, i: Int, cellM: Float): Long =
    rescanKey(floor(p[i * 3] / cellM).toInt(), floor(p[i * 3 + 1] / cellM).toInt(), floor(p[i * 3 + 2] / cellM).toInt())

/** A voxel's three indices packed in one key, each within ±2^20. */
internal fun rescanKey(x: Int, y: Int, z: Int): Long =
    ((x + KEY_OFFSET).toLong() shl KEY_X_SHIFT) or ((y + KEY_OFFSET).toLong() shl KEY_Y_SHIFT) or (z + KEY_OFFSET).toLong()

private const val KEY_OFFSET = 1 shl 20
private const val KEY_X_SHIFT = 42
private const val KEY_Y_SHIFT = 21
