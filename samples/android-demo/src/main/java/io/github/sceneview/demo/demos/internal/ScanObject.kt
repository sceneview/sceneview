package io.github.sceneview.demo.demos.internal

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What a surfel of a scan is part of, as far as a tap is concerned. */
enum class ScanSurface { Floor, Wall, Ceiling, Object }

/**
 * A box standing upright on [baseY]: [length] × [depth] on the floor (length ≥ depth) and
 * [height] up, its length turned [yaw] radians about +Y from the world's +X towards +Z.
 */
class ScanBox(
    val centerX: Float,
    val centerZ: Float,
    val baseY: Float,
    val yaw: Float,
    val length: Float,
    val depth: Float,
    val height: Float,
) {
    /** The four base corners in order round the floor (0–3), then the top ones above them (4–7). */
    fun corners(): List<Vec3> {
        val ux = cos(yaw) * length / 2
        val uz = sin(yaw) * length / 2
        val vx = -sin(yaw) * depth / 2
        val vz = cos(yaw) * depth / 2
        val base = listOf(
            Vec3(centerX - ux - vx, baseY, centerZ - uz - vz),
            Vec3(centerX + ux - vx, baseY, centerZ + uz - vz),
            Vec3(centerX + ux + vx, baseY, centerZ + uz + vz),
            Vec3(centerX - ux + vx, baseY, centerZ - uz + vz),
        )
        return base + base.map { Vec3(it.x, it.y + height, it.z) }
    }

    /** The middle of the top face: where the dimensions are written. */
    val top: Vec3 get() = Vec3(centerX, baseY + height, centerZ)

    /** [length], [depth] and [height] as the replay writes them: metres to the centimetre, `1.82`. */
    fun sizes(): Triple<String, String, String> = Triple(metres(length), metres(depth), metres(height))

    private fun metres(value: Float) = String.format(Locale.US, "%.2f", value)

    companion object {
        /** The twelve edges, as pairs of [corners] indices: base, top, then the four uprights. */
        val EDGES: List<Pair<Int, Int>> = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 0,
            4 to 5, 5 to 6, 6 to 7, 7 to 4,
            0 to 4, 1 to 5, 2 to 6, 3 to 7,
        )
    }
}

/** What a tap on a scan found. */
sealed interface ScanPick {
    /** The void, the floor, a wall or the ceiling: nothing to box. */
    data object Empty : ScanPick

    /**
     * Something was hit, but what grew from it is no single piece of furniture — it runs into its
     * neighbours or past any plausible size. Said as "tap closer", never drawn as a box.
     */
    data object TapCloser : ScanPick

    /** One object: its [box], and the [surfels] of the cloud it is made of. */
    class Found(val box: ScanBox, val surfels: IntArray) : ScanPick
}

/**
 * The view a tap is cast through: a pinhole at [eye] looking along [forward], world +Y up, with a
 * vertical field of view of 2·atan([tanHalfFov]) over [width] × [height] pixels.
 */
class ScanCamera(val eye: Vec3, forward: Vec3, val tanHalfFov: Float, val width: Int, val height: Int) {
    private val forward = forward.normalized()
    private val right = this.forward.cross(Vec3.Up).normalized()
    private val up = right.cross(this.forward)

    /** The unit direction of the ray through pixel ([x], [y]), origin top-left. */
    fun ray(x: Float, y: Float): Vec3 {
        val sx = (2f * x / width - 1f) * tanHalfFov * width / height
        val sy = (1f - 2f * y / height) * tanHalfFov
        return (forward + right * sx + up * sy).normalized()
    }

    /** The pixel [point] is drawn at, or `null` behind the camera. */
    fun project(point: Vec3): Pair<Float, Float>? {
        val v = point - eye
        val z = v.dot(forward)
        if (z < NEAR_M) return null
        val sx = v.dot(right) / z / (tanHalfFov * width / height)
        val sy = v.dot(up) / z / tanHalfFov
        return (sx + 1f) / 2f * width to (1f - sy) / 2f * height
    }

    private companion object {
        const val NEAR_M = 0.01f
    }
}

/**
 * A scan's dense cloud, sorted once so a tap can answer "which object is this?" — all of it plain
 * geometry on the points the scan stored, no learned model:
 *
 * 1. **Voxel index.** One slot per [voxelM] voxel (the scan keeps one surfel per voxel).
 * 2. **Floor.** A coarse map of where the ground is under every part of the room — phone depth
 *    bends a floor by centimetres across a room, a single level would not hold — and
 *    [floorTolerance], how thick the scan drew it, measured on the floor itself: a voxel on a
 *    clean scan, a hand's width on a noisy one. Anything inside that band is floor.
 * 3. **Ceiling and walls.** The highest level of surfels facing down, and on each side of the
 *    room the outermost large upright plane.
 * 4. **[pick].** A ray finds the first surfel under the finger; if it is none of the above, the
 *    region grows from it through solid voxels only, so the stray points between two pieces of
 *    furniture do not weld them, and gets its box: the smallest rectangle on the floor that holds
 *    it, as high as it stands. A speck of noise or a sliver on the way is gone through.
 * 5. **What a region is not.** A scan that saw only a piece of a wall leaves it unsorted in 3, so
 *    a region is judged again on its own: the sheet standing behind a piece of furniture and
 *    sticking out past it is cut away, a sheet alone answers like a wall, and a box that runs
 *    along most of the room is refused ([ScanPick.TapCloser]) rather than drawn.
 * 6. **A finger is not a ray.** When the ray itself found nothing, a wider cone looks for an
 *    object beside it — a lamp's pole is thinner than a fingertip — and a thing too sparse to
 *    grow through (a plant) is grown again with a looser net.
 * 7. **A slat is looked at twice.** An object shallower than [SLAT_DEPTH_M] is grown again with
 *    that looser net: two passes of a scan leave a seam through a long piece of furniture.
 *
 * Both [of] and [pick] belong off the main thread: [of] walks every surfel a few times, [pick]
 * takes a few milliseconds on a chair and tens of them on a sofa. [pick] is not thread-safe —
 * one tap at a time.
 */
class ScanObjects private constructor(
    private val cloud: DenseCloud,
    private val voxelM: Float,
    private val voxels: VoxelIndex,
    private val kinds: ByteArray,
    private val ground: GroundMap,
    /** How thick the scan drew its floor, in metres: what is this close to the ground is floor. */
    val floorTolerance: Float,
    /** The direction the room's walls face, modulo a quarter turn; `null` with too few of them. */
    private val roomYaw: Float?,
) {
    private val bounds = cloud.bounds()
    private val stamps = IntArray(voxels.slotCount)
    private var stamp = 0

    /** Per voxel, the tap (its first stamp) that cut it away as the wall behind an object. */
    private val cutAway = IntArray(voxels.slotCount)
    private val neighbours = IntArray(LOOSE_NEIGHBOURS)

    /** How thick an upright sheet of this scan is: a wall's noise, as [of] measured the floor's. */
    private val sheetTolerance = floorTolerance.coerceIn(voxelM * 2, MAX_WALL_TOLERANCE_M)

    /**
     * The fewest voxels an object is made of: [MIN_SEEN_AREA_M2] of surface, at one surfel a
     * voxel.
     */
    val minVoxels: Int = ceil(MIN_SEEN_AREA_M2 / (voxelM * voxelM)).toInt()

    /**
     * The longest box drawn, in metres: [ROOM_SHARE] of the scan's longer side — what runs along
     * more of the room than that is a wall or a row of furniture — and never past [MAX_SIDE_M].
     */
    val maxSide: Float = if (cloud.count == 0) {
        MAX_SIDE_M
    } else {
        (max(bounds[3] - bounds[0], bounds[5] - bounds[2]) * ROOM_SHARE).coerceIn(MIN_ROOM_CAP_M, MAX_SIDE_M)
    }

    /** What surfel [index] was sorted as. */
    fun surfaceOf(index: Int): ScanSurface = SURFACES[kinds[index].toInt()]

    /**
     * The first surfel a ray from [origin] along [direction] runs into, among the first [limit]
     * (a replay shows the cloud as it grew), or -1. The ray is a narrow cone — a surfel's own
     * width up close, a finger's imprecision far away — so it does not slip between two points.
     */
    fun hit(origin: Vec3, direction: Vec3, limit: Int = cloud.count): Int {
        val d = direction.normalized()
        val (near, far) = span(origin, d) ?: return -1
        var t = near
        while (t <= far) {
            val surfel = nearest(Cast(origin, d, limit, Int.MAX_VALUE, Int.MAX_VALUE), t, PICK_SPREAD, false)
            if (surfel >= 0) return surfel
            t += voxelM
        }
        return -1
    }

    /**
     * One tap's ray, and what it has been through: [speck], the first thing too small for a box,
     * and [stop], how far along it the floor or a wall ended it.
     */
    private class Cast(val origin: Vec3, val d: Vec3, val limit: Int, val maxVoxels: Int, val id: Int) {
        var speck = -1
        var stop = Float.MAX_VALUE
    }

    /** The stretch of the ray that is inside the cloud's bounds, or `null` when it misses them. */
    private fun span(origin: Vec3, d: Vec3): Pair<Float, Float>? {
        if (cloud.count == 0 || d.length() < 0.5f) return null
        var near = 0f
        var far = MAX_RANGE_M
        val o = floatArrayOf(origin.x, origin.y, origin.z)
        val dir = floatArrayOf(d.x, d.y, d.z)
        for (axis in 0 until 3) {
            val lo = bounds[axis] - voxelM * 2
            val hi = bounds[axis + 3] + voxelM * 2
            if (abs(dir[axis]) < 1e-6f) {
                if (o[axis] < lo || o[axis] > hi) return null
            } else {
                val a = (lo - o[axis]) / dir[axis]
                val b = (hi - o[axis]) / dir[axis]
                near = max(near, min(a, b))
                far = min(far, max(a, b))
            }
        }
        return if (near <= far) near to far else null
    }

    /**
     * Of the surfels round the ray's point at [t], the one closest to the ray inside a cone of
     * half-angle [spread] — objects only with [objectsOnly]; or -1. A surfel of a region grown
     * since [cast] began is not there: the ray has already been through it.
     */
    private fun nearest(cast: Cast, t: Float, spread: Float, objectsOnly: Boolean): Int {
        val radius = max(voxelM * PICK_RADIUS_VOXELS, t * spread)
        val reach = ceil(radius / voxelM).toInt()
        val cx = voxels.cell(cast.origin.x + cast.d.x * t)
        val cy = voxels.cell(cast.origin.y + cast.d.y * t)
        val cz = voxels.cell(cast.origin.z + cast.d.z * t)
        var best = -1
        var bestDistance = radius * radius
        for (ix in cx - reach..cx + reach) for (iy in cy - reach..cy + reach) for (iz in cz - reach..cz + reach) {
            val surfel = voxels.surfelAt(ix, iy, iz)
            val there = surfel in 0 until cast.limit && stamps[voxels.slotOf(surfel)] < cast.id &&
                (!objectsOnly || kinds[surfel] == OBJECT)
            val distance = if (there) offRay(surfel, cast.origin, cast.d) else Float.MAX_VALUE
            if (distance <= bestDistance) {
                best = surfel
                bestDistance = distance
            }
        }
        return best
    }

    /** The squared distance from [surfel] to the ray; out of reach behind its origin. */
    private fun offRay(surfel: Int, origin: Vec3, d: Vec3): Float {
        val vx = cloud.positions[surfel * 3] - origin.x
        val vy = cloud.positions[surfel * 3 + 1] - origin.y
        val vz = cloud.positions[surfel * 3 + 2] - origin.z
        val along = vx * d.x + vy * d.y + vz * d.z
        return if (along < 0f) Float.MAX_VALUE else vx * vx + vy * vy + vz * vz - along * along
    }

    /**
     * What a tap along the ray from [origin] finds among the first [limit] surfels. A region
     * past [maxVoxels], or whose box is longer than [maxSide], is [ScanPick.TapCloser].
     *
     * The ray goes through what is no object: a clump of under [minVoxels] voxels, or one that is
     * not [MIN_SIDE_M] by [MIN_SECOND_SIDE_M] in any two directions, is depth noise or a sliver of
     * it hanging in the room — it gets no box, and does not hide the piece of furniture behind it.
     *
     * A ray that found nothing is cast again as wide as a fingertip, for the objects only; and
     * when that finds nothing either, the first clump it went through is grown with a looser
     * net — it may be a plant, which no dense growth holds together.
     */
    fun pick(
        origin: Vec3,
        direction: Vec3,
        limit: Int = cloud.count,
        maxVoxels: Int = MAX_REGION_VOXELS,
    ): ScanPick {
        val d = direction.normalized()
        val (near, far) = span(origin, d) ?: return ScanPick.Empty
        if (stamp > Int.MAX_VALUE / 2) {
            stamps.fill(0)
            cutAway.fill(0)
            stamp = 0
        }
        // Every region grown from here on is one this ray has been through.
        val cast = Cast(origin, d, limit, maxVoxels, id = stamp + 1)
        val under = along(cast, near, far, PICK_SPREAD, objectsOnly = false)
        if (under != ScanPick.Empty) return under
        // No further than the wall the ray ended on: what stands behind a wall is not under the finger.
        val beside = along(cast, near, min(far, cast.stop), FINGER_SPREAD, objectsOnly = true)
        return beside as? ScanPick.Found ?: sparse(cast) ?: ScanPick.Empty
    }

    /**
     * The first answer along [cast] between [near] and [far]. With [objectsOnly] the floor and the
     * walls are looked past and only an object is an answer: the wide cast adds a box, it never
     * takes away the "nothing" of a tap on the floor.
     */
    private fun along(cast: Cast, near: Float, far: Float, spread: Float, objectsOnly: Boolean): ScanPick {
        var t = near
        while (t <= far) {
            val surfel = nearest(cast, t, spread, objectsOnly)
            val answer = if (surfel < 0) null else answerAt(surfel, cast)
            if (!objectsOnly && answer === ScanPick.Empty) cast.stop = t
            if (answer != null && (!objectsOnly || answer is ScanPick.Found)) return answer
            // Past a speck the ray stays where it is: what it hid may stand at the same step.
            if (surfel < 0) t += voxelM
        }
        return ScanPick.Empty
    }

    /** What a tap whose ray stops on [surfel] answers; `null` on a speck, which it goes through. */
    private fun answerAt(surfel: Int, cast: Cast): ScanPick? {
        if (surfaceOf(surfel) != ScanSurface.Object) return ScanPick.Empty
        val seed = voxels.slotOf(surfel)
        val answer = judge(seed, cast, loose = false)
        if (answer == null && cast.speck < 0) cast.speck = seed
        return answer
    }

    /**
     * The first clump [cast] went through, grown again with the looser net; `null` when that is
     * still no object, or one past [SPARSE_MAX_SIDE_M] — a plant, a lamp or a chair is found this
     * way, a sofa is not: the net has caught the neighbours, and the tap answers nothing.
     */
    private fun sparse(cast: Cast): ScanPick.Found? {
        if (cast.speck < 0) return null
        val found = judge(cast.speck, cast, loose = true) as? ScanPick.Found
        return found?.takeIf { it.box.length <= SPARSE_MAX_SIDE_M }
    }

    /**
     * What the region grown from [seed] is: an object and its box, the wall ([ScanPick.Empty]),
     * too much for one box ([ScanPick.TapCloser]), or — `null` — nothing a box could be drawn
     * round, which the ray goes through.
     *
     * An object under [SLAT_DEPTH_M] deep may be a slat of a larger one — a daybed the scan drew
     * in two passes that do not quite meet (#4505) — so it is grown again with the looser net,
     * which crosses the seam: when that reaches at least an object's worth more and is still one
     * object, that is the answer. A shelf or a lamp's foot, with nothing a seam away, keeps its box.
     *
     * That net crosses the gap to a neighbour as readily as a seam, so what it brings back is held
     * to what the net is trusted with everywhere else ([sparse]): no longer than
     * [SPARSE_MAX_SIDE_M] — or than the slat itself, made [SLAT_DEPTH_M] longer at most, when the
     * slat is already past that. A small object next to larger furniture came back as one box
     * round the lot; past that length it keeps its own.
     *
     * Past that length, what the net brings back is still the answer when it only carries the slat
     * on along its own length ([carriesOn]): a sideboard, a TV unit or a bench against a wall,
     * scanned in two passes, is one long low piece whose halves meet end to end (#4565).
     */
    private fun judge(seed: Int, cast: Cast, loose: Boolean): ScanPick? {
        val answer = regionAt(seed, cast, loose)
        val slat = (answer as? ScanPick.Found)?.takeIf { !loose && it.box.depth < SLAT_DEPTH_M } ?: return answer
        val whole = regionAt(seed, cast, loose = true) as? ScanPick.Found ?: return slat
        val longest = max(SPARSE_MAX_SIDE_M, slat.box.length + SLAT_DEPTH_M)
        val more = whole.surfels.size - slat.surfels.size
        val one = whole.box.length <= longest || carriesOn(slat.box, whole)
        return if (more >= minVoxels && one) whole else slat
    }

    /**
     * Whether [whole] is [slat] carried on along its own length, and nothing else: turned the same
     * way, no deeper nor taller than the slat by more than a seam's slack ([IN_LINE_SLACK_M]), and
     * solid all along — the seam a two-pass scan leaves is a voxel or two of nothing, where two
     * pieces of furniture standing apart leave a gap that only stray points cross.
     */
    private fun carriesOn(slat: ScanBox, whole: ScanPick.Found): Boolean {
        val box = whole.box
        val turn = abs(slat.yaw - box.yaw).mod(PI.toFloat())
        val parallel = min(turn, PI.toFloat() - turn) <= IN_LINE_TURN_RAD
        val fits = box.depth <= slat.depth + IN_LINE_SLACK_M && box.height <= slat.height + IN_LINE_SLACK_M
        return parallel && fits && solidAlong(whole.surfels, box)
    }

    /**
     * Whether [surfels] fill [box] along its length with no gap wider than the looser net's reach:
     * a stretch counts as filled when it holds a [SOLID_SHARE] of what a typical stretch holds,
     * so the thread of stray points across a gap does not fill it.
     */
    private fun solidAlong(surfels: IntArray, box: ScanBox): Boolean {
        val ux = cos(box.yaw)
        val uz = sin(box.yaw)
        val start = -box.length / 2
        val bins = IntArray(ceil(box.length / voxelM).toInt() + 1)
        for (i in surfels) {
            val along = (cloud.positions[i * 3] - box.centerX) * ux + (cloud.positions[i * 3 + 2] - box.centerZ) * uz
            val bin = ((along - start) / voxelM).toInt()
            if (bin in bins.indices) bins[bin]++
        }
        val typical = bins.filter { it > 0 }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
        val least = max(1, (typical * SOLID_SHARE).toInt())
        val first = bins.indexOfFirst { it >= least }
        val last = bins.indexOfLast { it >= least }
        if (first < 0) return false
        var gap = 0
        for (bin in first..last) {
            gap = if (bins[bin] >= least) 0 else gap + 1
            if (gap > LOOSE_REACH) return false
        }
        return true
    }

    /** [judge], of the region one growth from [seed] gives. */
    private fun regionAt(seed: Int, cast: Cast, loose: Boolean): ScanPick? {
        var region = grow(seed, cast, loose) ?: return ScanPick.TapCloser
        if (region.size >= minVoxels && cutBackdrops(region, seed, cast.id)) {
            // The tap was on the sheet itself: a wall, sorted or not.
            if (cutAway[seed] == cast.id) return ScanPick.Empty
            region = grow(seed, cast, loose) ?: return ScanPick.TapCloser
        }
        val box = if (region.size < minVoxels) null else boxOf(region)
        return when {
            box == null || !box.isAnObject() -> null
            box.isASheet() -> ScanPick.Empty
            box.length > maxSide -> ScanPick.TapCloser
            else -> ScanPick.Found(box, region)
        }
    }

    /** Whether a box has two sides an object has: a stick or a shard does not. */
    private fun ScanBox.isAnObject(): Boolean {
        val longest = max(length, height)
        val second = max(min(length, height), depth)
        return longest >= MIN_SIDE_M && second >= MIN_SECOND_SIDE_M
    }

    /** Whether a box is a piece of wall — or a door, a window: long, tall, and no deeper than its noise. */
    private fun ScanBox.isASheet(): Boolean =
        depth <= SHEET_DEPTH_M && length >= SHEET_LENGTH_M && height >= SHEET_HEIGHT_M

    /**
     * The surfels of the object [seed]'s voxel belongs to, or `null` past the cast's budget. Only
     * a voxel with [CORE_NEIGHBOURS] occupied neighbours passes the region on: the thin bridges
     * depth noise throws between two objects join a region, they do not carry it across. With
     * [loose], a voxel without them passes it on [LOOSE_REACH] voxels away instead: across the
     * gaps between a plant's leaves.
     */
    private fun grow(seed: Int, cast: Cast, loose: Boolean): IntArray? {
        stamp++
        var queue = IntArray(INITIAL_QUEUE)
        var size = 0
        queue[size++] = seed
        stamps[seed] = stamp
        var head = 0
        while (head < size) {
            val slot = queue[head++]
            var found = objectNeighbours(slot, cast, 1)
            if (found < CORE_NEIGHBOURS) {
                found = if (loose) objectNeighbours(slot, cast, LOOSE_REACH) else if (slot == seed) found else 0
            }
            for (k in 0 until found) {
                val next = neighbours[k]
                val fresh = stamps[next] != stamp
                if (fresh && size == cast.maxVoxels) return null
                if (fresh) {
                    if (size == queue.size) queue = queue.copyOf(size * 2)
                    stamps[next] = stamp
                    queue[size++] = next
                }
            }
        }
        // In the cloud's own order, so the same object gets the same box whichever point was tapped.
        return IntArray(size) { voxels.surfel(queue[it]) }.also { it.sort() }
    }

    /**
     * Fills [neighbours] with the object voxels within [reach] of [slot] that a replay has drawn
     * and this tap has not cut away; returns how many.
     */
    private fun objectNeighbours(slot: Int, cast: Cast, reach: Int): Int {
        val ix = voxels.x(slot)
        val iy = voxels.y(slot)
        val iz = voxels.z(slot)
        var found = 0
        for (dx in -reach..reach) for (dy in -reach..reach) for (dz in -reach..reach) {
            val surfel = voxels.surfelAt(ix + dx, iy + dy, iz + dz)
            val other = if (surfel in 0 until cast.limit && kinds[surfel] == OBJECT) voxels.slotOf(surfel) else slot
            if (other != slot && cutAway[other] != cast.id) neighbours[found++] = other
        }
        return found
    }

    /**
     * Cuts away of [region] the sheets that stand behind it and are no part of it — the piece of
     * wall a scan saw round a piece of furniture, too small for [of] to have sorted as a wall.
     * Looked for along the faces the region agrees on and along the room's own; `true` when
     * something was cut.
     */
    private fun cutBackdrops(region: IntArray, seed: Int, tap: Int): Boolean {
        val normals = cloud.normals ?: return false
        val floorY = ground.at(cloud.positions[voxels.surfel(seed) * 3], cloud.positions[voxels.surfel(seed) * 3 + 2])
        val yaws = listOfNotNull(faceVote(region).takeIf { it.second >= MIN_FACE_AGREEMENT }?.first, roomYaw)
        var cut = false
        for (yaw in yaws) for (quarter in 0 until QUARTERS) {
            val angle = yaw + quarter * PI / 2
            val sheet = Sheet(cos(angle).toFloat(), sin(angle).toFloat())
            if (sheet.place(region, normals) && sheet.isBackdrop(region, floorY)) {
                sheet.cut(region, normals, tap)
                cut = true
            }
        }
        return cut
    }

    /**
     * An upright sheet of a region facing ([ax], [az]) — the room's side of it — at [offset]
     * along that direction: the first plane heavy enough coming from behind the region.
     */
    private inner class Sheet(val ax: Float, val az: Float) {
        private var offset = 0f

        private fun along(i: Int) = cloud.positions[i * 3] * ax + cloud.positions[i * 3 + 2] * az
        private fun across(i: Int) = -cloud.positions[i * 3] * az + cloud.positions[i * 3 + 2] * ax
        private fun faces(i: Int, normals: FloatArray, least: Float) =
            abs(normals[i * 3 + 1]) <= UPRIGHT_NORMAL_Y && normals[i * 3] * ax + normals[i * 3 + 2] * az > least

        /** Finds the sheet in [region]; `false` when no plane of it faces this way. */
        fun place(region: IntArray, normals: FloatArray): Boolean {
            val facing = region.filter { faces(it, normals, ALONG_AXIS) }
            if (facing.size < MIN_SHEET_VOXELS) return false
            val lo = facing.minOf { along(it) }
            val bins = IntArray(((facing.maxOf { along(it) } - lo) / LEVEL_BIN_M).toInt() + 1)
            fun binOf(i: Int) = ((along(i) - lo) / LEVEL_BIN_M).toInt()
            for (i in facing) bins[binOf(i)]++
            fun weightAt(bin: Int) = (bin until bin + WALL_WINDOW_BINS).sumOf { bins.getOrElse(it) { 0 } }
            val first = bins.indices.firstOrNull { weightAt(it) >= MIN_SHEET_VOXELS } ?: return false
            val on = facing.filter { binOf(it) in first until first + WALL_WINDOW_BINS }
            offset = (on.sumOf { along(it).toDouble() } / on.size).toFloat()
            return true
        }

        /**
         * Whether the sheet is the wall behind what [region] holds, not a side of it: nearly
         * nothing behind it, an object's worth in front, and it sticks out — by [OVERHANG_M] past
         * the object on a side, or above it up to where only a wall goes ([WALL_HEIGHT_M] over
         * [floorY]). A sofa's back, a headboard, a shelf's back panel do neither.
         */
        fun isBackdrop(region: IntArray, floorY: Float): Boolean {
            val on = region.filter { abs(along(it) - offset) <= sheetTolerance }
            val front = region.filter { along(it) - offset > sheetTolerance }
            val behind = region.size - on.size - front.size
            if (on.size < MIN_SHEET_VOXELS || front.size < minVoxels || behind > on.size * MAX_PAST_WALL) return false
            val sheetTop = end(on, high = true) { cloud.positions[it * 3 + 1] }
            val frontTop = end(front, high = true) { cloud.positions[it * 3 + 1] }
            val taller = sheetTop - floorY >= WALL_HEIGHT_M && sheetTop - frontTop >= OVERHANG_M
            val wider = end(on, high = true, ::across) - end(front, high = true, ::across) >= OVERHANG_M ||
                end(front, high = false, ::across) - end(on, high = false, ::across) >= OVERHANG_M
            return taller || wider
        }

        /**
         * Marks the sheet cut away for [tap]: what is behind it, what faces its way within its
         * noise, and its own voxel-thin skin — not the sides of the furniture standing against it.
         */
        fun cut(region: IntArray, normals: FloatArray, tap: Int) {
            for (i in region) {
                val out = along(i) - offset
                val skin = abs(out) <= voxelM || (abs(out) <= sheetTolerance && faces(i, normals, FACING_SHEET))
                if (skin || out < -sheetTolerance) cutAway[voxels.slotOf(i)] = tap
            }
        }

        /** The high or low end of [value] over [surfels], [SHEET_TRIM] of them dropped as strays. */
        private fun end(surfels: List<Int>, high: Boolean, value: (Int) -> Float): Float {
            val sorted = FloatArray(surfels.size) { value(surfels[it]) }.also { it.sort() }
            val drop = (sorted.size * SHEET_TRIM).toInt()
            return if (high) sorted[sorted.size - 1 - drop] else sorted[drop]
        }
    }

    /**
     * The box of [region]: on the floor, the rectangle of least area that holds its points — tried
     * every degree, each extent trimmed of its last half percent so one stray point cannot stretch
     * it; when two orientations are close, the one the object's own upright faces vote for wins.
     * In height, from the ground under it (when it reaches the floor band) to its top.
     */
    private fun boxOf(region: IntArray): ScanBox {
        val n = region.size
        val xs = FloatArray(n) { cloud.positions[region[it] * 3] }
        val zs = FloatArray(n) { cloud.positions[region[it] * 3 + 2] }
        // The orientation is searched on a sample — a hundred fits of a large object would be
        // felt under the finger — and the box then measured on every point.
        val stride = max(1, n / ANGLE_SAMPLES)
        val few = (n + stride - 1) / stride
        val sampleX = FloatArray(few) { xs[it * stride] }
        val sampleZ = FloatArray(few) { zs[it * stride] }
        val angle = footprintAngle(sampleX, sampleZ, faceVote(region))
        val us = FloatArray(n)
        val vs = FloatArray(n)
        turn(xs, zs, angle, us, vs)
        us.sort()
        vs.sort()
        val u0 = trimmed(us, low = true)
        val u1 = trimmed(us, low = false)
        val v0 = trimmed(vs, low = true)
        val v1 = trimmed(vs, low = false)
        val cu = (u0 + u1) / 2
        val cv = (v0 + v1) / 2

        val ys = FloatArray(n) { cloud.positions[region[it] * 3 + 1] }
        ys.sort()
        val grounds = FloatArray(few) { ground.at(sampleX[it], sampleZ[it]) }
        grounds.sort()
        val floorY = grounds[few / 2]
        val lowest = trimmed(ys, low = true)
        // The floor band was cut away under it: an object that reaches the band stands on the floor.
        val standing = lowest - floorY <= floorTolerance + voxelM * REACH_VOXELS
        val baseY = if (standing) min(floorY, lowest) else lowest
        return ScanBox(
            centerX = cu * cos(angle) - cv * sin(angle),
            centerZ = cu * sin(angle) + cv * cos(angle),
            baseY = baseY,
            yaw = if (u1 - u0 >= v1 - v0) angle else angle + (PI / 2).toFloat(),
            length = max(u1 - u0, v1 - v0),
            depth = min(u1 - u0, v1 - v0),
            height = trimmed(ys, low = false) - baseY,
        )
    }

    /**
     * The turn about +Y, within a quarter turn, at which the rectangle holding ([xs], [zs]) is
     * smallest — nudged towards [faces], the orientation the object's upright faces agree on and
     * how much: an L-shaped footprint has two rectangles of the same area, its faces pick one.
     */
    private fun footprintAngle(xs: FloatArray, zs: FloatArray, faces: Pair<Float, Float>): Float {
        val us = FloatArray(xs.size)
        val vs = FloatArray(xs.size)
        var best = 0f
        var bestScore = Float.MAX_VALUE
        fun consider(angle: Float) {
            turn(xs, zs, angle, us, vs)
            val aligned = 0.5f + 0.5f * cos(4f * (angle - faces.first))
            val score = extent(us) * extent(vs) * (1f - FACE_BONUS * faces.second * aligned)
            if (score < bestScore) {
                bestScore = score
                best = angle
            }
        }
        for (degrees in 0 until QUARTER_TURN_DEG) consider(Math.toRadians(degrees.toDouble()).toFloat())
        val coarse = best
        for (step in -REFINE_STEPS..REFINE_STEPS) consider(coarse + step * REFINE_STEP_RAD)
        return best
    }

    /**
     * The orientation [region]'s upright faces agree on, modulo a quarter turn, and how much they
     * agree (0–1): a cupboard's four sides all vote for the same box, a ball's for none.
     */
    private fun faceVote(region: IntArray): Pair<Float, Float> {
        val normals = cloud.normals ?: return 0f to 0f
        var c = 0.0
        var s = 0.0
        for (i in region) {
            if (abs(normals[i * 3 + 1]) <= UPRIGHT_NORMAL_Y) {
                val angle = 4.0 * atan2(normals[i * 3 + 2].toDouble(), normals[i * 3].toDouble())
                c += cos(angle)
                s += sin(angle)
            }
        }
        val agreement = (sqrt(c * c + s * s) / region.size).toFloat().coerceIn(0f, 1f)
        return (atan2(s, c) / 4.0).toFloat() to agreement
    }

    /** ([xs], [zs]) seen from a frame turned [angle] about +Y, into [us] and [vs]. */
    private fun turn(xs: FloatArray, zs: FloatArray, angle: Float, us: FloatArray, vs: FloatArray) {
        val ca = cos(angle)
        val sa = sin(angle)
        for (k in xs.indices) {
            us[k] = xs[k] * ca + zs[k] * sa
            vs[k] = -xs[k] * sa + zs[k] * ca
        }
    }

    /** The trimmed extent of [values], which need not be sorted: [TRIM] dropped at each end. */
    private fun extent(values: FloatArray): Float {
        val sorted = values.copyOf().also { it.sort() }
        return trimmed(sorted, low = false) - trimmed(sorted, low = true)
    }

    private fun trimmed(sorted: FloatArray, low: Boolean): Float {
        val drop = (sorted.size * TRIM).toInt()
        return if (low) sorted[drop] else sorted[sorted.size - 1 - drop]
    }

    companion object {
        /** Longer than this is a wall, a row of furniture or the whole room — not one object. */
        const val MAX_SIDE_M = 3f

        /** A region past this many voxels has run into the rest of the room. */
        const val MAX_REGION_VOXELS = 80_000

        /**
         * The least surface a scan must have seen of an object, in square metres: 0.2 m² is 500
         * surfels at the scan's 2 cm — three faces of a 26 cm cube. On a phone's scans the
         * smallest thing that was furniture stood well over it, and the shards of depth noise that
         * had got a box well under (#4505).
         */
        const val MIN_SEEN_AREA_M2 = 0.2f

        /** Smaller than this in every direction is a speck, however many voxels it has. */
        const val MIN_SIDE_M = 0.3f

        /**
         * And an object has a second side: under 20 cm in two directions it is a stick or a shard
         * of depth noise (#4505), which no tape would be put to.
         */
        const val MIN_SECOND_SIDE_M = 0.2f

        /**
         * Shallower than this, an object is looked at again for the rest of it: a scan in two
         * passes gave one long piece of furniture as two slats, each its own box (#4505).
         */
        const val SLAT_DEPTH_M = 0.3f

        /** A box along more than this share of the scan's longer side is the room's, not an object's. */
        const val ROOM_SHARE = 2f / 3f

        /** A scan of one corner of a room still boxes a table: [ROOM_SHARE] never caps below this. */
        const val MIN_ROOM_CAP_M = 1.5f

        /** A thing found with the looser net is a plant, a lamp, a chair: never longer than this. */
        const val SPARSE_MAX_SIDE_M = 1.5f

        /**
         * A slat carried on along its length by the looser net (#4565): the rest may stand this
         * much deeper or taller than the half that was tapped — two passes do not see one piece
         * of furniture to the centimetre — and be turned this far from it.
         */
        const val IN_LINE_SLACK_M = 0.08f
        private const val IN_LINE_TURN_RAD = 0.1745f

        /** A stretch of a long piece holds at least this share of what a typical stretch of it holds. */
        private const val SOLID_SHARE = 0.25f

        /**
         * A piece of wall seen alone: at least a metre long and 1.2 m tall, and no deeper than
         * 15 cm — a wall's depth noise, and thinner than the slimmest cabinet.
         */
        const val SHEET_DEPTH_M = 0.15f
        const val SHEET_LENGTH_M = 1f
        const val SHEET_HEIGHT_M = 1.2f

        /** The sheet behind an object is the wall when it sticks out this far past it. */
        const val OVERHANG_M = 0.3f

        /** Furniture's own backs stop below this; what stands taller behind an object is the wall. */
        const val WALL_HEIGHT_M = 1.8f

        /** Occupied neighbours (of 26) a voxel needs to pass a region on. */
        const val CORE_NEIGHBOURS = 6

        /** The looser net's reach, in voxels: it crosses one empty voxel, 4 cm at the scan's 2 cm. */
        private const val LOOSE_REACH = 2
        private const val LOOSE_NEIGHBOURS = 124

        /** A sheet: at least this many voxels in one plane — 0.08 m², an [OVERHANG_M] square. */
        private const val MIN_SHEET_VOXELS = 200
        private const val MIN_FACE_AGREEMENT = 0.1f
        private const val FACING_SHEET = 0.5f
        private const val SHEET_TRIM = 0.02f
        private const val QUARTERS = 4
        private const val INITIAL_QUEUE = 1024
        private const val MAX_RANGE_M = 30f
        private const val PICK_RADIUS_VOXELS = 0.9f

        /** The pick cone's half-angle, in radians: 4 dp on a phone, a surfel's width at arm's length. */
        private const val PICK_SPREAD = 0.004f

        /**
         * The second cast's half-angle: 12 dp on a phone (0.37 mrad a pixel at 2.75 px a dp), a
         * quarter of the 48 dp a touch target is given — what a fingertip can be off by.
         */
        private const val FINGER_SPREAD = 0.012f
        private const val ANGLE_SAMPLES = 1024
        private const val TRIM = 0.005f
        private const val FACE_BONUS = 0.2f
        private const val QUARTER_TURN_DEG = 90
        private const val REFINE_STEPS = 4
        private val REFINE_STEP_RAD = Math.toRadians(0.25).toFloat()
        private const val UPRIGHT_NORMAL_Y = 0.35f
        private const val REACH_VOXELS = 3

        private const val FLOOR: Byte = 0
        private const val WALL: Byte = 1
        private const val CEILING: Byte = 2
        private const val OBJECT: Byte = 3
        private val SURFACES = ScanSurface.entries

        /** A surfel faces up (or down) when its normal is this close to the vertical. */
        private const val FACING = 0.7f
        private const val LEVEL_BIN_M = 0.04f

        /** A level counts as the floor (or ceiling) with this share of the busiest level's count. */
        private const val DOMINANT = 0.35f
        private const val TOLERANCE_WINDOW_M = 0.3f
        private const val TOLERANCE_PERCENTILE = 0.95f
        private const val MIN_TOLERANCE_VOXELS = 1.5f
        private const val MAX_TOLERANCE_M = 0.25f

        /** A flat, up-facing surfel this little above the floor band is still the floor's skin. */
        private const val FLOOR_SKIN_M = 0.05f
        private const val FLAT_NORMAL_Y = 0.85f

        /**
         * Down-facing surfels this far above the floor or more are marked as ceiling, so that they
         * are not taken for an object; lower is the underside of a table. It sets nothing of the
         * room: the room's height, and the ceiling the live walls look for, start at the survey's
         * [RoomSurveyor.MIN_CEILING_M] (2 m). Lower here, a low ceiling is still kept out of objects.
         */
        private const val MIN_CEILING_M = 1.8f
        private const val MIN_CEILING_SURFELS = 200

        /** A wall: at least this many surfels in one plane, this long and this tall. */
        private const val MIN_WALL_SURFELS = 1000
        private const val MIN_WALL_LENGTH_M = 1.5f
        private const val MIN_WALL_HEIGHT_M = 1.2f
        private const val WALL_WINDOW_BINS = 3
        private const val MAX_WALL_TOLERANCE_M = 0.1f

        /** Past a wall the scan sees mirrors and noise: this share of the wall's own points at most. */
        private const val MAX_PAST_WALL = 0.25f
        private const val ALONG_AXIS = 0.8f
        private const val YAW_WINDOW_DEG = 3

        /** Sorts [cloud], a scan's dense map of [voxelM] voxels. Off the main thread. */
        fun of(cloud: DenseCloud, voxelM: Float = DenseFusion.VOXEL_M): ScanObjects {
            val voxels = VoxelIndex(cloud.positions, cloud.count, voxelM)
            val ground = GroundMap.of(cloud, voxelM)
            val tolerance = floorTolerance(cloud, ground, voxelM)
            val kinds = ByteArray(cloud.count)
            for (i in 0 until cloud.count) {
                val h = cloud.positions[i * 3 + 1] - ground.at(cloud.positions[i * 3], cloud.positions[i * 3 + 2])
                val flat = normalY(cloud, i) > FLAT_NORMAL_Y
                kinds[i] = if (h < tolerance || (flat && h < tolerance + FLOOR_SKIN_M)) FLOOR else OBJECT
            }
            markCeiling(cloud, kinds, ground.level, max(tolerance, voxelM * 2))
            val yaw = markWalls(cloud, kinds, tolerance.coerceIn(voxelM * 2, MAX_WALL_TOLERANCE_M))
            return ScanObjects(cloud, voxelM, voxels, kinds, ground, tolerance, yaw)
        }

        private fun normalY(cloud: DenseCloud, i: Int): Float = cloud.normals?.get(i * 3 + 1) ?: 1f

        /**
         * How far above the ground its own up-facing surfels reach, [TOLERANCE_PERCENTILE] of
         * them: the floor is as thick as the scan drew it.
         */
        private fun floorTolerance(cloud: DenseCloud, ground: GroundMap, voxelM: Float): Float {
            val least = voxelM * MIN_TOLERANCE_VOXELS
            val heights = FloatArray(cloud.count)
            var n = 0
            for (i in 0 until cloud.count) {
                val x = cloud.positions[i * 3]
                val z = cloud.positions[i * 3 + 2]
                val h = cloud.positions[i * 3 + 1] - ground.at(x, z)
                val onGround = ground.seen(x, z) && abs(h) < TOLERANCE_WINDOW_M
                if (onGround && normalY(cloud, i) >= FACING) heights[n++] = h
            }
            if (n == 0) return least
            heights.sort(0, n)
            return heights[min(n - 1, (n * TOLERANCE_PERCENTILE).toInt())].coerceIn(least, MAX_TOLERANCE_M)
        }

        /** The highest dominant level of down-facing surfels, when it is a room's height up. */
        private fun markCeiling(cloud: DenseCloud, kinds: ByteArray, floorY: Float, tolerance: Float) {
            val normals = cloud.normals ?: return
            val from = floorY + MIN_CEILING_M
            val top = cloud.bounds()[4]
            if (top <= from) return
            val bins = IntArray(((top - from) / LEVEL_BIN_M).toInt() + 1)
            for (i in 0 until cloud.count) {
                val y = cloud.positions[i * 3 + 1]
                if (y >= from && normals[i * 3 + 1] <= -FACING) bins[((y - from) / LEVEL_BIN_M).toInt()]++
            }
            if (bins.sum() < MIN_CEILING_SURFELS) return
            val busiest = bins.max()
            val ceilingY = from + bins.indexOfLast { it >= busiest * DOMINANT } * LEVEL_BIN_M
            for (i in 0 until cloud.count) {
                if (kinds[i] == OBJECT && cloud.positions[i * 3 + 1] >= ceilingY - tolerance) kinds[i] = CEILING
            }
        }

        /**
         * The walls: the room's two directions from its upright surfels' normals, then on each of
         * the four sides the outermost plane of them — large enough to be a wall, or that side has
         * none. A plane with a good part of the scan behind it is a piece of furniture seen from the
         * side, not a wall. Whatever is on a wall, or past it, is wall. Returns the room's
         * direction, `null` when the scan has too few upright surfels to tell.
         */
        private fun markWalls(cloud: DenseCloud, kinds: ByteArray, tolerance: Float): Float? {
            val normals = cloud.normals ?: return null
            fun upright(i: Int) = kinds[i] == OBJECT && abs(normals[i * 3 + 1]) <= UPRIGHT_NORMAL_Y
            val count = (0 until cloud.count).count { upright(it) }
            if (count < MIN_WALL_SURFELS) return null
            val yaw = roomYaw(cloud, normals, ::upright)
            val planes = ArrayList<Wall>()
            for (axis in 0 until 2) {
                val ax = cos(yaw + axis * PI / 2).toFloat()
                val az = sin(yaw + axis * PI / 2).toFloat()
                val facing = (0 until cloud.count).filter { i ->
                    upright(i) && abs(normals[i * 3] * ax + normals[i * 3 + 2] * az) > ALONG_AXIS
                }.toIntArray()
                val sides = if (facing.size >= MIN_WALL_SURFELS) intArrayOf(1, -1) else intArrayOf()
                for (side in sides) {
                    val offset = wallOffset(cloud, facing, facing.size, Wall(ax, az, side, 0f))
                    if (offset != null) planes += Wall(ax, az, side, offset)
                }
            }
            // All four judged on the cloud as it is, before any of them claims its side of it.
            val walls = planes.filter { closesTheScan(cloud, kinds, it, tolerance) }
            for (wall in walls) {
                for (i in 0 until cloud.count) {
                    val beyond = (wall.along(cloud, i) - wall.offset) * wall.side > -tolerance
                    if (beyond && kinds[i] == OBJECT) kinds[i] = WALL
                }
            }
            return yaw.toFloat()
        }

        /** Whether what the scan saw past [wall] is little next to what it saw of the wall itself. */
        private fun closesTheScan(cloud: DenseCloud, kinds: ByteArray, wall: Wall, tolerance: Float): Boolean {
            var on = 0
            var past = 0
            for (i in 0 until cloud.count) {
                val out = (wall.along(cloud, i) - wall.offset) * wall.side
                if (kinds[i] == OBJECT && out > tolerance) past++
                if (kinds[i] == OBJECT && abs(out) <= tolerance) on++
            }
            return past <= on * MAX_PAST_WALL
        }

        /**
         * The direction most [upright] surfels face, modulo a quarter turn, in radians: the busiest
         * degree and its neighbours — a room's walls outvote the furniture standing askew in it.
         */
        private fun roomYaw(cloud: DenseCloud, normals: FloatArray, upright: (Int) -> Boolean): Double {
            val bins = IntArray(QUARTER_TURN_DEG)
            for (i in 0 until cloud.count) {
                if (upright(i)) {
                    val degrees = Math.toDegrees(atan2(normals[i * 3 + 2].toDouble(), normals[i * 3].toDouble()))
                    bins[floor(degrees).toInt().mod(QUARTER_TURN_DEG)]++
                }
            }
            fun around(bin: Int, k: Int) = bins[(bin + k).mod(QUARTER_TURN_DEG)]
            val window = -YAW_WINDOW_DEG..YAW_WINDOW_DEG
            val peak = bins.indices.maxBy { bin -> window.sumOf { around(bin, it) } }
            val mean = window.sumOf { it * around(peak, it).toDouble() } / window.sumOf { around(peak, it) }
            return Math.toRadians(peak + 0.5 + mean)
        }

        /**
         * Coming in from [side] along its axis: the offset of the first plane of [facing] surfels
         * heavy enough to be a wall — `null` when it is too short or too low to be one (a cupboard
         * at the edge of the scan), or when there is none.
         */
        private fun wallOffset(cloud: DenseCloud, facing: IntArray, n: Int, side: Wall): Float? {
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (k in 0 until n) {
                lo = min(lo, side.along(cloud, facing[k]))
                hi = max(hi, side.along(cloud, facing[k]))
            }
            val bins = IntArray(((hi - lo) / LEVEL_BIN_M).toInt() + 1)
            fun binOf(i: Int) = ((side.along(cloud, i) - lo) / LEVEL_BIN_M).toInt()
            for (k in 0 until n) bins[binOf(facing[k])]++
            val outside = side.side > 0
            fun windowAt(bin: Int) =
                if (outside) (bin - WALL_WINDOW_BINS + 1)..bin else bin until bin + WALL_WINDOW_BINS
            fun weightAt(bin: Int) = windowAt(bin).sumOf { bins.getOrElse(it) { 0 } }
            val order = if (outside) bins.indices.reversed() else bins.indices
            val first = order.firstOrNull { weightAt(it) >= MIN_WALL_SURFELS } ?: return null
            val across = ArrayList<Float>()
            val heights = ArrayList<Float>()
            var sum = 0.0
            for (k in 0 until n) {
                val i = facing[k]
                if (binOf(i) in windowAt(first)) {
                    across += -cloud.positions[i * 3] * side.az + cloud.positions[i * 3 + 2] * side.ax
                    heights += cloud.positions[i * 3 + 1]
                    sum += side.along(cloud, i)
                }
            }
            val large = spread(across) >= MIN_WALL_LENGTH_M && spread(heights) >= MIN_WALL_HEIGHT_M
            return if (large) (sum / across.size).toFloat() else null
        }

        /** The span of the middle 90 % of [values]. */
        private fun spread(values: MutableList<Float>): Float {
            values.sort()
            val last = (values.size * 0.95f).toInt().coerceAtMost(values.lastIndex)
            return values[last] - values[(values.size * 0.05f).toInt()]
        }
    }

    /** A wall: the plane at [offset] along the horizontal axis ([ax], [az]), the room on its -[side]. */
    private class Wall(val ax: Float, val az: Float, val side: Int, val offset: Float) {
        fun along(cloud: DenseCloud, i: Int): Float = cloud.positions[i * 3] * ax + cloud.positions[i * 3 + 2] * az
    }

    /**
     * Where the ground is under each cell of the room: the low end of the up-facing surfels there,
     * kept when it joins the room's floor [level] by steps no floor would take between two cells;
     * a cell the scan saw no ground in takes its nearest neighbour's.
     */
    private class GroundMap private constructor(
        private val grid: Grid,
        private val heights: FloatArray,
        private val measured: BooleanArray,
        /** The room's floor level: the lowest level a good share of the cells agree on. */
        val level: Float,
    ) {
        /** The ground's height under ([x], [z]). */
        fun at(x: Float, z: Float): Float = heights[grid.cell(x, z)]

        /** Whether the scan itself saw the ground under ([x], [z]). */
        fun seen(x: Float, z: Float): Boolean = measured[grid.cell(x, z)]

        /** The floor plan cut in square cells of [cellM], [nx] × [nz] of them from ([minX], [minZ]). */
        class Grid(val minX: Float, val minZ: Float, val cellM: Float, val nx: Int, val nz: Int) {
            val size: Int get() = nx * nz

            fun cell(x: Float, z: Float): Int {
                val cx = ((x - minX) / cellM).toInt().coerceIn(0, nx - 1)
                val cz = ((z - minZ) / cellM).toInt().coerceIn(0, nz - 1)
                return cz * nx + cx
            }

            /** The cell on [side] of [cell] — 0–3 share an edge with it, 4–7 a corner — or -1 off the plan. */
            fun neighbour(cell: Int, side: Int): Int {
                val x = cell % nx + DX[side]
                val z = cell / nx + DZ[side]
                return if (x in 0 until nx && z in 0 until nz) z * nx + x else -1
            }

            private companion object {
                val DX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
                val DZ = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
            }
        }

        companion object {
            /** Side of a cell, in voxels: 16 cm at the scan's 2 cm. */
            private const val CELL_VOXELS = 8
            private const val MAX_CELLS = 1 shl 20
            private const val MIN_CELL_SAMPLES = 8

            /** The ground may step this much between two neighbouring cells and still be the ground. */
            private const val STEP_M = 0.05f
            private const val EDGE_SIDES = 4
            private const val ALL_SIDES = 8

            fun of(cloud: DenseCloud, voxelM: Float): GroundMap {
                val b = cloud.bounds()
                var cellM = voxelM * CELL_VOXELS
                fun cells(axis: Int) = ((b[axis + 3] - b[axis]) / cellM).toInt() + 1
                while (cells(0).toLong() * cells(2) > MAX_CELLS) cellM *= 2
                val grid = Grid(b[0], b[2], cellM, cells(0), cells(2))
                val low = lowEnds(cloud, grid)
                val level = levelOf(low)
                    ?: return GroundMap(grid, FloatArray(grid.size) { b[1] }, BooleanArray(grid.size), b[1])
                val seen = grown(grid, low, level)
                return GroundMap(grid, filled(grid, seen), BooleanArray(grid.size) { !seen[it].isNaN() }, level)
            }

            /** Per cell, the low end of its up-facing surfels' heights; NaN with too few of them. */
            private fun lowEnds(cloud: DenseCloud, grid: Grid): FloatArray {
                fun cellOf(i: Int) = grid.cell(cloud.positions[i * 3], cloud.positions[i * 3 + 2])
                // Bucketed by cell, then each bucket sorted in place.
                val start = IntArray(grid.size + 1)
                for (i in 0 until cloud.count) if (normalY(cloud, i) >= FACING) start[cellOf(i) + 1]++
                for (cell in 0 until grid.size) start[cell + 1] += start[cell]
                val cursor = start.copyOf()
                val ups = FloatArray(start[grid.size])
                for (i in 0 until cloud.count) {
                    if (normalY(cloud, i) >= FACING) ups[cursor[cellOf(i)]++] = cloud.positions[i * 3 + 1]
                }
                return FloatArray(grid.size) { cell ->
                    val n = start[cell + 1] - start[cell]
                    if (n < MIN_CELL_SAMPLES) {
                        Float.NaN
                    } else {
                        ups.sort(start[cell], start[cell + 1])
                        ups[start[cell] + n / 5]
                    }
                }
            }

            /** The lowest level [DOMINANT] of the busiest level's cells agree on; `null` with no cell. */
            private fun levelOf(low: FloatArray): Float? {
                val known = low.filter { !it.isNaN() }
                if (known.isEmpty()) return null
                val lowest = known.min()
                fun binOf(v: Float) = ((v - lowest) / LEVEL_BIN_M + 0.5f).toInt()
                val bins = IntArray(binOf(known.max()) + 1)
                val sums = FloatArray(bins.size)
                for (v in known) {
                    bins[binOf(v)]++
                    sums[binOf(v)] += v
                }
                val busiest = bins.max()
                val bin = bins.indexOfFirst { it >= busiest * DOMINANT }
                return sums[bin] / bins[bin]
            }

            /** The ground, from the cells at [level] to every cell that continues it without a step. */
            private fun grown(grid: Grid, low: FloatArray, level: Float): FloatArray {
                val heights = FloatArray(grid.size) { cell ->
                    if (abs(low[cell] - level) <= LEVEL_BIN_M) low[cell] else Float.NaN
                }
                val queue = IntArray(grid.size)
                var size = 0
                for (cell in 0 until grid.size) if (!heights[cell].isNaN()) queue[size++] = cell
                var head = 0
                while (head < size) {
                    val cell = queue[head++]
                    for (side in 0 until ALL_SIDES) {
                        val next = grid.neighbour(cell, side)
                        val open = next >= 0 && heights[next].isNaN()
                        // A cell with no low end is NaN, and fails the comparison.
                        if (open && abs(low[next] - heights[cell]) <= STEP_M) {
                            heights[next] = low[next]
                            queue[size++] = next
                        }
                    }
                }
                return heights
            }

            /** [seen] with every NaN cell given the height of the nearest cell that has one. */
            private fun filled(grid: Grid, seen: FloatArray): FloatArray {
                val heights = seen.copyOf()
                val queue = IntArray(grid.size)
                var size = 0
                for (cell in 0 until grid.size) if (!heights[cell].isNaN()) queue[size++] = cell
                var head = 0
                while (head < size) {
                    val cell = queue[head++]
                    for (side in 0 until EDGE_SIDES) {
                        val next = grid.neighbour(cell, side)
                        if (next >= 0 && heights[next].isNaN()) {
                            heights[next] = heights[cell]
                            queue[size++] = next
                        }
                    }
                }
                return heights
            }
        }
    }

    /**
     * Which surfel sits in which voxel: an open-addressing table from the voxel's three integer
     * coordinates to the first (oldest) surfel found there.
     */
    private class VoxelIndex(positions: FloatArray, count: Int, private val voxelM: Float) {
        private val bits = max(MIN_BITS, Int.SIZE_BITS - Integer.numberOfLeadingZeros(max(1, count * 2 - 1)))
        private val mask = (1 shl bits) - 1
        private val keys = LongArray(1 shl bits) { EMPTY }
        private val first = IntArray(1 shl bits)
        private val slots = IntArray(count)

        val slotCount: Int get() = keys.size

        init {
            for (i in 0 until count) {
                val key = key(cell(positions[i * 3]), cell(positions[i * 3 + 1]), cell(positions[i * 3 + 2]))
                var slot = hash(key)
                while (keys[slot] != EMPTY && keys[slot] != key) slot = (slot + 1) and mask
                if (keys[slot] == EMPTY) {
                    keys[slot] = key
                    first[slot] = i
                }
                slots[i] = slot
            }
        }

        fun cell(v: Float): Int = floor(v / voxelM).toInt()

        /** The surfel of voxel ([ix], [iy], [iz]), or -1 when it is empty. */
        fun surfelAt(ix: Int, iy: Int, iz: Int): Int {
            val key = key(ix, iy, iz)
            var slot = hash(key)
            while (keys[slot] != EMPTY && keys[slot] != key) slot = (slot + 1) and mask
            return if (keys[slot] == EMPTY) -1 else first[slot]
        }

        fun slotOf(surfel: Int): Int = slots[surfel]
        fun surfel(slot: Int): Int = first[slot]
        fun x(slot: Int): Int = (keys[slot] ushr (COORD_BITS * 2)).toInt() - BIAS
        fun y(slot: Int): Int = ((keys[slot] ushr COORD_BITS) and COORD_MASK).toInt() - BIAS
        fun z(slot: Int): Int = (keys[slot] and COORD_MASK).toInt() - BIAS

        private fun hash(key: Long): Int = ((key * HASH_MULTIPLIER) ushr (Long.SIZE_BITS - bits)).toInt() and mask

        private fun key(ix: Int, iy: Int, iz: Int): Long =
            (((ix + BIAS).toLong() and COORD_MASK) shl (COORD_BITS * 2)) or
                (((iy + BIAS).toLong() and COORD_MASK) shl COORD_BITS) or
                ((iz + BIAS).toLong() and COORD_MASK)

        private companion object {
            const val EMPTY = -1L
            const val MIN_BITS = 4
            const val COORD_BITS = 21
            const val COORD_MASK = (1L shl COORD_BITS) - 1
            const val BIAS = 1 shl (COORD_BITS - 1)
            const val HASH_MULTIPLIER = -7046029254386353131L
        }
    }
}
