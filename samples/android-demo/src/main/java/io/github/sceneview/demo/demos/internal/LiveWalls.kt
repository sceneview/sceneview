package io.github.sceneview.demo.demos.internal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The walls 3D Scan shows while it scans (Room and Home): each wall it recognises stands on the
 * floor as a clean translucent quad, the way RoomPlan outlines a room as it is scanned. One wall
 * seen twice is one wall; a wall grows as more of it is seen and never shrinks, and it goes once
 * no plane has held it up for [PlaneWallSource.RELEASE_S]. What counts as a wall is the room
 * survey's rule ([WallRules]).
 */

/**
 * One wall the scan recognised: its floor line from ([ax], [az]) to ([bx], [bz]), and when it was
 * first recognised ([bornAt], seconds of the trace). [id] stays the same as the wall grows.
 */
data class LiveWall(
    val id: Int,
    val ax: Float,
    val az: Float,
    val bx: Float,
    val bz: Float,
    val bornAt: Float,
    /** How sure the wall is, `0..1`: a short piece is a guess, [PlaneWallSource.CONFIDENT_M] of it is a wall. */
    val confidence: Float = 1f,
) {
    val length: Float get() = hypot(bx - ax, bz - az)
}

/**
 * What the scan recognised of the room at one instant: its [walls], standing on the floor at
 * [floorY], [height] metres tall — the ceiling's height when it was seen ([ceilingSeen]), else the
 * one measured in the same session, else [WallRules.DEFAULT_HEIGHT_M]; never the top of a plane.
 */
data class LiveWallSnapshot(
    val walls: List<LiveWall>,
    val floorY: Float,
    val height: Float,
    val ceilingSeen: Boolean = false,
) {
    companion object {
        val Empty = LiveWallSnapshot(emptyList(), 0f, 0f)
    }
}

/**
 * A plane as any AR source gives it, with nothing of 3D Scan's trace: ARCore's [id] for it, which
 * way it faces ([kind]), and its boundary as a **world-space** polygon, flat `[x0,y0,z0, …]` — an
 * ARCore `Plane`'s `polygon` put through its `centerPose`. What Measure (#4732) and a scan module
 * hand [PlaneWallSource].
 */
class LivePlane(val id: Int, val kind: Kind, val polygon: FloatArray) {
    enum class Kind { Floor, Ceiling, Wall, Other }

    val vertexCount: Int get() = polygon.size / 3
}

/**
 * Where the live walls come from. Today, ARCore's vertical planes ([PlaneWallSource]); a source
 * that builds the room's structure from the dense map can replace it without the view changing.
 */
interface LiveWallSource {
    /**
     * The walls recognised from [planes], over a floor at [floorY], at [time] seconds. Called each
     * frame: cheap when nothing changed, and the same snapshot instance while the walls do not.
     * [tracking] is whether ARCore tracks the camera at [time]: `false` while it has lost it, and
     * the walls stay as they are, the time lost not counted towards a release.
     */
    fun update(planes: List<LivePlane>, floorY: Float, time: Float, tracking: Boolean = true): LiveWallSnapshot

    /** Forgets every wall: a new capture. */
    fun reset()
}

/**
 * Walls from ARCore's vertical planes, by the room survey's rules ([WallRules]). ARCore cuts one
 * wall into several planes (a picture, a door frame, a wall seen from two places, a piece fitted
 * askew) and keeps planes of furniture: only a plane rising past standing height and seen over
 * 0.40 m of its height (or over the furniture before it) can be a wall; pieces on one line,
 * and fits of one wall a little apart, are joined into one wall; a wall is shown once seen long
 * enough along one of the room's directions and when it is not furniture before another wall; and
 * the walls are squared to the room's directions and joined at the corners they nearly meet at. A
 * wall no plane has held up for [RELEASE_S] goes, counted while ARCore tracks: through a tracking
 * loss the walls keep their ids. A plane ARCore refits a few centimetres across one of these
 * thresholds keeps the verdict it had ([WallRules.HOLD_M]): a wall does not flicker. The verdict
 * belongs to the wall, not to ARCore's id for the plane: a plane ARCore merges into another, under
 * a new id, keeps it.
 */
class PlaneWallSource : LiveWallSource {
    private val tracks = ArrayList<Track>()
    private var nextId = 0
    private var key = LongArray(0)

    /** The floor [key]'s planes were worked out over: the same planes over another floor stand otherwise. */
    private var keyFloorY = Float.NaN
    private var last = LiveWallSnapshot.Empty

    /** The snapshot must be worked out again, whatever the planes: new, reset, or the height changed. */
    private var stale = true

    /** A wall no plane holds up is waiting to go: the next update works it out even with the planes unchanged. */
    private var releasing = false

    /** The time of the last update while ARCore tracked, seconds; `NaN` before one. */
    private var trackedAt = Float.NaN

    /** ARCore lost tracking since [trackedAt]: the time since does not count towards a wall's release. */
    private var lost = false

    /**
     * The room's height, floor to ceiling in metres, measured some other way in the same session
     * (Measure's Room mode): the walls are drawn to it when no ceiling plane was seen; a ceiling
     * seen wins. `null` when nothing was measured. It belongs to the session, so [reset] keeps it.
     */
    var measuredHeight: Float? = null
        set(value) {
            if (value != field) stale = true
            field = value
        }

    override fun update(planes: List<LivePlane>, floorY: Float, time: Float, tracking: Boolean): LiveWallSnapshot {
        if (!tracking) {
            // ARCore holds its planes up again once it finds the room: until then the walls stay.
            lost = true
            return last
        }
        if (lost) {
            // The release timers resume where they stopped: a wall waits for its plane as long as before.
            val gap = time - trackedAt
            if (gap > 0f) tracks.forEach { it.heldAt = min(time, it.heldAt + gap) }
            lost = false
        }
        trackedAt = time
        val newKey = keyOf(planes)
        if (newKey.contentEquals(key) && floorY == keyFloorY && !stale && !releasing) {
            // The same planes hold up every wall still: none of them has started waiting to go.
            tracks.forEach { it.heldAt = time }
            return last
        }
        key = newKey
        keyFloorY = floorY
        stale = false
        merge(clusters(laidOnHosts(pieces(planes, floorY, ::onKnownWall))), time)
        val ceiling = ceilingOf(planes, floorY)
        val height = WallRules.height(ceiling?.minus(floorY), measuredHeight).coerceIn(MIN_HEIGHT_M, MAX_HEIGHT_M)
        val next = LiveWallSnapshot(
            walls = shaped(shown(floorY + height)),
            floorY = floorY,
            height = height,
            ceilingSeen = ceiling != null,
        )
        // A plane refit that moves no wall keeps the snapshot: whoever draws it has nothing to redo.
        if (next != last) last = next
        return last
    }

    override fun reset() {
        tracks.clear()
        nextId = 0
        key = LongArray(0)
        keyFloorY = Float.NaN
        last = LiveWallSnapshot.Empty
        stale = true
        releasing = false
        trackedAt = Float.NaN
        lost = false
    }

    /**
     * Folds this update's [found] walls into the walls already known. Where a wall stands and which
     * way it runs is what ARCore's planes say now — a refit corrects its first rough fit — and what
     * was seen of it before keeps only its ends, so it never shrinks. A wall none of [found] holds
     * up goes once none has for [RELEASE_S] — its plane was refit to the furniture it was, or
     * dropped — and keeps its id if one holds it up again before: ARCore drops a plane for a few
     * frames as it refits it.
     */
    private fun merge(found: List<Segment>, time: Float) {
        val held = HashSet<Track>()
        for (segment in found.sortedByDescending { it.length }) {
            // A second fit of a wall already held up, a little apart or askew: that wall, seen again.
            val twin = tracks.firstOrNull { it in held && oneWall(it.segment, segment) }
            if (twin != null) {
                twin.segment = twin.segment.spanning(segment)
                continue
            }
            val matches = tracks.filter { it !in held && oneWall(it.segment, segment) }
            if (matches.isEmpty()) {
                tracks += Track(nextId++, time, segment).also { held += it }
                continue
            }
            // The oldest keeps its name; the others were the same wall seen in pieces.
            val keep = matches.minBy { it.id }
            var grown = segment
            matches.forEach { grown = grown.spanning(it.segment) }
            keep.segment = grown
            tracks.removeAll { it in matches && it !== keep }
            held += keep
        }
        held.forEach { it.heldAt = time }
        // Time running backwards is a replay sought back: what is not held up now goes at once.
        tracks.removeAll { it !in held && (time - it.heldAt >= RELEASE_S || time < it.heldAt) }
        releasing = tracks.size > held.size
    }

    /**
     * Whether [piece] lies along a wall already known, one this update folds it into: that wall
     * stood, so the plane keeps standing a little past the thresholds ([WallRules.standsAsWall]'s
     * `held`), whatever id ARCore gives it now. A wall waiting to go ([RELEASE_S]) counts.
     */
    private fun onKnownWall(piece: Segment): Boolean = tracks.any {
        oneWall(it.segment, piece) && it.segment.overlapAlong(piece) >= WallRules.OVERLAP_SHARE * piece.length
    }

    /**
     * The walls shown, each with the room's direction it runs along: seen long enough along one of
     * the room's directions, and neither furniture before another wall nor the side of such
     * furniture. [ceilingY] is the ceiling's height, which only a wall reaches.
     */
    private fun shown(ceilingY: Float): List<Pair<Track, WallFamily>> {
        val candidates = tracks.filter { it.length >= WallRules.MIN_SEEN_M - WallRules.TOLERANCE_M }
        val families = WallRules.families(
            FloatArray(candidates.size) { candidates[it].segment.angle },
            FloatArray(candidates.size) { candidates[it].length },
            candidates.map { it.square },
        )
        val fronts = candidates.mapNotNull { front ->
            candidates.firstOrNull { it !== front && WallRules.standsBefore(front.segment, it.segment, ceilingY, front.furniture) }
                ?.let { front to it }
        }
        return candidates.mapNotNull { track ->
            val family = WallRules.familyOf(track.segment.angle, families) ?: return@mapNotNull null
            val hidden = fronts.any { (front, behind) ->
                front === track ||
                    (track !== behind && WallRules.isSideOf(track.segment, front.segment, behind.segment, track.furniture))
            }
            // Each verdict is kept for the next update, which holds it against a refit across its threshold.
            track.square = family.square
            track.furniture = hidden
            (track to family).takeIf { !hidden && WallRules.seenEnough(track.length, family.square) }
        }
    }

    /** The walls as drawn: squared to the room's direction each runs along, and joined at their corners. */
    private fun shaped(shown: List<Pair<Track, WallFamily>>): List<LiveWall> {
        val squared = shown.map { (track, family) -> track.segment.rotatedTo(family.direction) }
        val ends = squared.map { floatArrayOf(it.ax, it.az, it.bx, it.bz) }
        for (i in squared.indices) {
            for (j in i + 1 until squared.size) joinCorner(squared[i], squared[j], ends[i], ends[j])
        }
        return shown.mapIndexed { i, (track, _) ->
            val e = ends[i]
            LiveWall(track.id, e[0], e[1], e[2], e[3], track.bornAt, (track.length / CONFIDENT_M).coerceAtMost(1f))
        }
    }

    private class Track(val id: Int, val bornAt: Float, var segment: Segment) {
        val length: Float get() = segment.length

        /** When a plane last held it up, seconds. */
        var heldAt: Float = bornAt

        /** Whether its direction was on the room's square the last time, `null` before it was judged. */
        var square: Boolean? = null

        /** Whether it was furniture before another wall, or the side of such, the last time; `null` before. */
        var furniture: Boolean? = null
    }

    companion object {
        /** A wall seen along this much, in metres, is sure: [LiveWall.confidence] is 1. */
        const val CONFIDENT_M = 1.5f

        /** A vertical piece shorter than this along the floor is left out altogether. */
        const val MIN_PIECE_M = 0.25f

        /** Two pieces this close in direction can be one wall. */
        const val SAME_WALL_DEGREES = 12f

        /** Two pieces this far apart across their line can be one wall: a wall is not a mirror. */
        const val SAME_WALL_OFFSET_M = 0.2f

        /** Two pieces on one line this far apart along it are one wall with a gap not yet seen. */
        const val SAME_WALL_GAP_M = 0.6f

        /** Two walls whose lines cross this close to their ends meet there: a corner. */
        const val CORNER_REACH_M = 0.7f

        /** How far from square two walls may be and still be joined at a corner. */
        const val CORNER_DEGREES = 20f

        const val MIN_HEIGHT_M = 0.6f
        const val MAX_HEIGHT_M = 4f

        /**
         * A ceiling lower than this over the floor is a table seen from below or a high shelf, not
         * the ceiling: the survey's [RoomSurveyor.MIN_CEILING_M].
         */
        const val MIN_CEILING_M = RoomSurveyor.MIN_CEILING_M

        /** How long a wall no plane holds up any more stays, in seconds, waiting for its plane to come back. */
        const val RELEASE_S = 0.5f

        private val counted = Any()
        private var countedKey = LongArray(0)
        private var countedFloorY = Float.NaN
        private var count = 0

        /**
         * The walls in [planes], each counted once, with no tracking — one call, one answer: a
         * finished recording's planes, which no live [PlaneWallSource] followed. A live scan reads
         * its source's snapshot instead, the count the HUD shows. A replay asks every frame: the
         * count is worked out again only when the planes or the floor change.
         */
        fun wallCount(planes: List<LivePlane>, floorY: Float): Int {
            val key = keyOf(planes)
            synchronized(counted) {
                if (floorY == countedFloorY && key.contentEquals(countedKey)) return count
            }
            val walls = PlaneWallSource().update(planes, floorY, time = 0f).walls.size
            synchronized(counted) {
                countedKey = key
                countedFloorY = floorY
                count = walls
            }
            return walls
        }

        /** The planes as they are: a plane is the same while its id and outline are, as ARCore hands new objects every frame. */
        private fun keyOf(planes: List<LivePlane>) = LongArray(planes.size) { i ->
            (planes[i].id.toLong() shl Int.SIZE_BITS) or (planes[i].polygon.contentHashCode().toLong() and MASK)
        }

        /**
         * Each vertical plane that stands as a wall ([WallRules.standsAsWall]) as a floor segment:
         * its line, its extent along it, its top. Seen over less than [WallRules.MIN_SPAN_M] of its
         * height, it may stand on the furniture before it ([WallRules.furnitureTop]), which hides the rest.
         * A plane [onWall] says lies along a wall that stood keeps standing a little past the thresholds.
         */
        internal fun pieces(
            planes: List<LivePlane>,
            floorY: Float,
            onWall: (Segment) -> Boolean = { false },
        ): List<Segment> {
            return planes.mapNotNull { plane ->
                if (plane.kind != LivePlane.Kind.Wall || plane.vertexCount < 3) return@mapNotNull null
                var bottom = Float.MAX_VALUE
                var top = -Float.MAX_VALUE
                for (i in 0 until plane.vertexCount) {
                    bottom = min(bottom, plane.polygon[i * 3 + 1])
                    top = max(top, plane.polygon[i * 3 + 1])
                }
                val segment = Segment.ofPolygon(plane.polygon) ?: return@mapNotNull null
                val held = onWall(segment)
                // Furniture is looked for only when what was seen of the plane alone does not hold it up.
                val stands = WallRules.standsAsWall(bottom, top, floorY, held) ||
                    WallRules.standsAsWall(bottom, top, floorY, held, underY = furnitureUnder(plane, segment, planes, floorY))
                if (!stands) return@mapNotNull null
                segment.takeIf { it.length >= MIN_PIECE_M }
            }
        }

        /** The top of the highest furniture before [wall], the plane [plane], among [planes]: [floorY] for none. */
        private fun furnitureUnder(plane: LivePlane, wall: Segment, planes: List<LivePlane>, floorY: Float): Float {
            var under = floorY
            for (other in planes) {
                if (other === plane || other.kind == LivePlane.Kind.Ceiling || other.vertexCount < 3) continue
                WallRules.furnitureTop(wall, other.polygon)?.let { under = max(under, it) }
            }
            return under
        }

        /** Each piece fitted askew along a longer one ([WallRules.shardOf]) laid onto that one's line. */
        internal fun laidOnHosts(pieces: List<Segment>): List<Segment> = pieces.map { piece ->
            pieces.filter { it !== piece && WallRules.shardOf(piece, it) }
                .maxByOrNull { it.length }
                ?.let { piece.projectedOn(it) }
                ?: piece
        }

        /** Pieces on one line joined into walls (single linkage: A–B and B–C make one wall). */
        internal fun clusters(pieces: List<Segment>): List<Segment> {
            val parent = IntArray(pieces.size) { it }
            fun root(i: Int): Int {
                var r = i
                while (parent[r] != r) r = parent[r]
                return r
            }
            for (i in pieces.indices) {
                for (j in i + 1 until pieces.size) {
                    if (sameWall(pieces[i], pieces[j])) parent[root(i)] = root(j)
                }
            }
            return pieces.indices.groupBy { root(it) }.values.map { group ->
                group.map { pieces[it] }.reduce { a, b -> a.union(b) }
            }
        }

        /** Whether [a] and [b] are one wall: pieces on one line ([sameWall]) or one wall fitted twice ([WallRules.duplicates]). */
        private fun oneWall(a: Segment, b: Segment) = sameWall(a, b) || WallRules.duplicates(a, b)

        internal fun sameWall(a: Segment, b: Segment): Boolean {
            if (abs(degrees(WallMesh.wrapHalfTurn(a.angle - b.angle))) > SAME_WALL_DEGREES) return false
            if (a.distanceAcross(b.midX, b.midZ) > SAME_WALL_OFFSET_M) return false
            if (b.distanceAcross(a.midX, a.midZ) > SAME_WALL_OFFSET_M) return false
            return a.gapAlong(b) <= SAME_WALL_GAP_M
        }

        /** The ceiling's height, from the lowest downward-facing plane high enough to be one. */
        private fun ceilingOf(planes: List<LivePlane>, floorY: Float): Float? = planes
            .filter { it.kind == LivePlane.Kind.Ceiling && it.vertexCount >= 3 }
            .map { plane -> (0 until plane.vertexCount).map { plane.polygon[it * 3 + 1] }.average().toFloat() }
            .filter { it - floorY >= MIN_CEILING_M }
            .minOrNull()

        /**
         * Joins [a] and [b] at the point their lines cross when it lies within [CORNER_REACH_M] of
         * an end of each — extended to it or trimmed back to it — and they are near square.
         */
        private fun joinCorner(a: Segment, b: Segment, ea: FloatArray, eb: FloatArray) {
            val off = abs(degrees(WallMesh.wrapHalfTurn(a.angle - b.angle)))
            if (abs(off - RIGHT_ANGLE) > CORNER_DEGREES) return
            val cross = intersection(ea, eb) ?: return
            val endA = nearestEnd(ea, cross) ?: return
            val endB = nearestEnd(eb, cross) ?: return
            ea[endA * 2] = cross.first
            ea[endA * 2 + 1] = cross.second
            eb[endB * 2] = cross.first
            eb[endB * 2 + 1] = cross.second
        }

        /** Which end of [e] (0: a, 1: b) lies within reach of [p], `null` for neither. */
        private fun nearestEnd(e: FloatArray, p: Pair<Float, Float>): Int? {
            val da = hypot(e[0] - p.first, e[1] - p.second)
            val db = hypot(e[2] - p.first, e[3] - p.second)
            val length = hypot(e[2] - e[0], e[3] - e[1])
            val end = if (da <= db) 0 else 1
            val reach = min(da, db)
            // Trimming more than half a wall away is not a corner, it is another wall crossing it.
            return end.takeIf { reach <= CORNER_REACH_M && reach < length / 2f }
        }

        private fun intersection(a: FloatArray, b: FloatArray): Pair<Float, Float>? {
            val rx = a[2] - a[0]
            val rz = a[3] - a[1]
            val sx = b[2] - b[0]
            val sz = b[3] - b[1]
            val denominator = rx * sz - rz * sx
            if (abs(denominator) < EPSILON) return null
            val t = ((b[0] - a[0]) * sz - (b[1] - a[1]) * sx) / denominator
            return (a[0] + t * rx) to (a[1] + t * rz)
        }

        private const val RIGHT_ANGLE = 90f
        private const val MASK = 0xffffffffL
        private const val EPSILON = 1e-6f
    }
}

/**
 * A wall's floor line: through ([midX], [midZ]) along [angle] (radians on the floor, `[0, π)`),
 * from [from] to [to] metres along it, [top] the highest point seen on it. [weight] is how much of
 * it was seen, which weighs its say in a joined wall's direction.
 */
class Segment(
    val angle: Float,
    val midX: Float,
    val midZ: Float,
    val from: Float,
    val to: Float,
    val top: Float,
    val weight: Float = to - from,
) {
    private val dx = cos(angle)
    private val dz = sin(angle)

    val length: Float get() = to - from
    val ax: Float get() = midX + dx * from
    val az: Float get() = midZ + dz * from
    val bx: Float get() = midX + dx * to
    val bz: Float get() = midZ + dz * to

    /** How far ([x], [z]) lies from this line, across it. */
    fun distanceAcross(x: Float, z: Float): Float = abs(-dz * (x - midX) + dx * (z - midZ))

    /** Where ([x], [z]) projects on this line, in metres along it from ([midX], [midZ]). */
    fun along(x: Float, z: Float): Float = dx * (x - midX) + dz * (z - midZ)

    /** This span projected onto [line]: on its line and direction, with this one's top and weight. */
    fun projectedOn(line: Segment): Segment {
        val s0 = line.along(ax, az)
        val s1 = line.along(bx, bz)
        return Segment(line.angle, line.midX, line.midZ, min(s0, s1), max(s0, s1), top, weight)
    }

    /** The gap between this and [other] along this line, `0` or less where they overlap. */
    fun gapAlong(other: Segment): Float {
        val s0 = along(other.ax, other.az)
        val s1 = along(other.bx, other.bz)
        return max(min(s0, s1) - to, from - max(s0, s1))
    }

    /**
     * This and [other] as one wall: the direction they agree on, weighted by how much of each was
     * seen, through their weighted middle, spanning both.
     */
    fun union(other: Segment): Segment {
        val w = weight + other.weight
        // Directions have no sign: averaged as doubled angles.
        val c = cos(angle * 2) * weight + cos(other.angle * 2) * other.weight
        val s = sin(angle * 2) * weight + sin(other.angle * 2) * other.weight
        val joined = positive(atan2(s, c) / 2f)
        val mx = (midX * weight + other.midX * other.weight) / w
        val mz = (midZ * weight + other.midZ * other.weight) / w
        val ux = cos(joined)
        val uz = sin(joined)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for ((x, z) in listOf(ax to az, bx to bz, other.ax to other.az, other.bx to other.bz)) {
            val t = ux * (x - mx) + uz * (z - mz)
            lo = min(lo, t)
            hi = max(hi, t)
        }
        return Segment(joined, mx, mz, lo, hi, max(top, other.top), w)
    }

    /**
     * This line as it is — its direction, its position, its weight — stretched to reach [other]'s
     * ends: what a wall seen before keeps when ARCore refits it. Unlike [union], [other] has no say
     * in where the line runs, so a wall seen again is not merged with itself.
     */
    fun spanning(other: Segment): Segment {
        var lo = from
        var hi = to
        for ((x, z) in listOf(other.ax to other.az, other.bx to other.bz)) {
            val t = along(x, z)
            lo = min(lo, t)
            hi = max(hi, t)
        }
        return Segment(angle, midX, midZ, lo, hi, max(top, other.top), weight)
    }

    /** The same span turned onto [angle] round its centre. */
    fun rotatedTo(angle: Float): Segment {
        val centre = (from + to) / 2f
        val cx = midX + dx * centre
        val cz = midZ + dz * centre
        val half = length / 2f
        return Segment(angle, cx, cz, -half, half, top, weight)
    }

    companion object {
        /**
         * A vertical plane's [polygon] (world xyz, flat) seen from above: the line its points lie
         * along (their principal axis on the floor) and their extent on it. `null` when the
         * polygon is a point.
         */
        fun ofPolygon(polygon: FloatArray): Segment? {
            val n = polygon.size / 3
            if (n < 2) return null
            var mx = 0f
            var mz = 0f
            var top = -Float.MAX_VALUE
            for (i in 0 until n) {
                mx += polygon[i * 3]
                mz += polygon[i * 3 + 2]
                top = max(top, polygon[i * 3 + 1])
            }
            mx /= n
            mz /= n
            var sxx = 0f
            var sxz = 0f
            var szz = 0f
            for (i in 0 until n) {
                val x = polygon[i * 3] - mx
                val z = polygon[i * 3 + 2] - mz
                sxx += x * x
                sxz += x * z
                szz += z * z
            }
            if (sxx + szz < TINY) return null
            val angle = positive(atan2(2f * sxz, sxx - szz) / 2f)
            val ux = cos(angle)
            val uz = sin(angle)
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in 0 until n) {
                val t = ux * (polygon[i * 3] - mx) + uz * (polygon[i * 3 + 2] - mz)
                lo = min(lo, t)
                hi = max(hi, t)
            }
            return Segment(angle, mx, mz, lo, hi, top)
        }

        /** A line's direction in `[0, π)`: taken before projecting onto it, so the span matches. */
        private fun positive(angle: Float): Float {
            val a = WallMesh.wrapHalfTurn(angle)
            return if (a < 0f) a + PI.toFloat() else a
        }

        private const val TINY = 1e-8f
    }
}

/**
 * The walls as they are drawn: each eases towards what was recognised, so a new wall rises from
 * the floor and a growing one stretches, calmly — no wall jumps or flashes when ARCore refits a plane.
 */
class LiveWallMotion(
    /** How long a wall takes to rise, in seconds, to within 5 % of its height. */
    private val riseSeconds: Float = RISE_SECONDS,
    /** How long a wall's ends take to follow a refit, to within 5 %. */
    private val stretchSeconds: Float = STRETCH_SECONDS,
) {
    /** One wall as drawn now: its floor line, and how far it has risen, `0` to `1`. */
    class Shown(val id: Int, var ax: Float, var az: Float, var bx: Float, var bz: Float, var rise: Float)

    val shown = ArrayList<Shown>()

    /** The height drawn, eased like the walls. */
    var height = 0f
        private set

    var floorY = 0f
        private set

    /**
     * Steps the walls drawn [dt] seconds towards [target]. Returns `true` when anything drawn
     * moved — when the meshes need rebuilding. A step of no time (`dt = 0`: ARCore handed the same
     * camera image again, as it does on every other vsync and for good once a recording has ended)
     * moves nothing still easing, so it reports no move: the walls are only rebuilt when they changed.
     */
    fun step(target: LiveWallSnapshot, dt: Float): Boolean {
        var moved = false
        // Nothing drawn and nothing to draw (no floor yet, or just reset): following the empty
        // snapshot's floor is no move, or the layer would rebuild its meshes every frame.
        val drawn = target.height != 0f || shown.isNotEmpty()
        val ease = settle(dt, stretchSeconds)
        val rise = settle(dt, riseSeconds)
        val ids = target.walls.mapTo(HashSet()) { it.id }
        // A wall merged into another goes with no fade: the one it joined grows over it.
        if (shown.removeAll { it.id !in ids }) moved = true
        for (wall in target.walls) {
            val s = shown.firstOrNull { it.id == wall.id }
            if (s == null) {
                // A new wall stands on its whole floor line at once and rises.
                shown += Shown(wall.id, wall.ax, wall.az, wall.bx, wall.bz, 0f)
                moved = true
                continue
            }
            moved = s.approach(wall, ease) or moved
            if (s.rise < 1f) {
                val next = if (1f - s.rise < DONE) 1f else s.rise + (1f - s.rise) * rise
                if (next != s.rise) moved = true
                s.rise = next
            }
        }
        if (floorY != target.floorY || height == 0f) {
            val was = floorY
            if (height == 0f || abs(floorY - target.floorY) > JUMP_M) floorY = target.floorY else {
                floorY += (target.floorY - floorY) * ease
                if (abs(floorY - target.floorY) < DONE) floorY = target.floorY
            }
            if (drawn && floorY != was) moved = true
        }
        if (height != target.height) {
            val was = height
            height = if (height == 0f || abs(height - target.height) < DONE) {
                target.height
            } else {
                height + (target.height - height) * ease
            }
            if (height != was) moved = true
        }
        return moved
    }

    fun reset() {
        shown.clear()
        height = 0f
        floorY = 0f
    }

    private fun Shown.approach(wall: LiveWall, ease: Float): Boolean {
        val far = abs(ax - wall.ax) + abs(az - wall.az) + abs(bx - wall.bx) + abs(bz - wall.bz)
        if (far == 0f) return false
        if (far < DONE) {
            ax = wall.ax
            az = wall.az
            bx = wall.bx
            bz = wall.bz
        } else {
            // No time, no step: the ends stay where they are drawn.
            if (ease == 0f) return false
            ax += (wall.ax - ax) * ease
            az += (wall.az - az) * ease
            bx += (wall.bx - bx) * ease
            bz += (wall.bz - bz) * ease
        }
        return true
    }

    companion object {
        /** DESIGN.md `duration-long`: a wall rises as the eye can follow it, not as a flash. */
        const val RISE_SECONDS = 0.7f
        const val STRETCH_SECONDS = 0.35f

        /** Closer than this (metres, or of a rise), the wall is where it is going. */
        private const val DONE = 1e-3f

        /** A floor that moves more than this was refound, not refined: it is taken at once. */
        private const val JUMP_M = 0.3f

        /** ln(20): the step that brings a value within 5 % of its target in the given time. */
        private const val LN_20 = 2.9957323f

        private fun settle(dt: Float, seconds: Float): Float =
            if (seconds <= 0f) 1f else 1f - exp(-dt.coerceIn(0f, MAX_STEP_S) * LN_20 / seconds)

        /** A stalled frame does not make the walls jump: at most this much time per step. */
        private const val MAX_STEP_S = 0.1f
    }
}

/**
 * The edges of the floor seen so far ([extent], a polygon on the floor) that no recognised wall
 * stands on: where the room still opens onto what was not scanned. Each is a pair of floor points.
 */
fun openFloorEdges(extent: List<Pair<Float, Float>>, walls: List<LiveWall>): List<FloatArray> {
    if (extent.size < 3) return emptyList()
    val open = ArrayList<FloatArray>()
    for (i in extent.indices) {
        val (ax, az) = extent[i]
        val (bx, bz) = extent[(i + 1) % extent.size]
        val length = hypot(bx - ax, bz - az)
        if (length < MIN_OPEN_EDGE_M) continue
        // Cut into steps, so the part of a long edge a wall covers is not drawn as open.
        val steps = max(1, (length / OPEN_STEP_M).toInt())
        var runStart = -1
        for (k in 0..steps) {
            val t = k.toFloat() / steps
            val x = ax + (bx - ax) * t
            val z = az + (bz - az) * t
            val covered = walls.any { distanceToSegment(x, z, it) <= WALL_COVERS_M }
            val last = k == steps
            if (!covered && runStart < 0) runStart = k
            if (runStart >= 0 && (covered || last)) {
                val end = if (covered) k - 1 else k
                if (end > runStart) {
                    val t0 = runStart.toFloat() / steps
                    val t1 = end.toFloat() / steps
                    open += floatArrayOf(ax + (bx - ax) * t0, az + (bz - az) * t0, ax + (bx - ax) * t1, az + (bz - az) * t1)
                }
                runStart = -1
            }
        }
    }
    return open
}

private fun distanceToSegment(x: Float, z: Float, wall: LiveWall): Float {
    val vx = wall.bx - wall.ax
    val vz = wall.bz - wall.az
    val l2 = vx * vx + vz * vz
    val t = if (l2 < 1e-9f) 0f else (((x - wall.ax) * vx + (z - wall.az) * vz) / l2).coerceIn(0f, 1f)
    return hypot(x - (wall.ax + vx * t), z - (wall.az + vz * t))
}

private fun hypot(x: Float, z: Float): Float = sqrt(x * x + z * z)

/** [radians] in degrees. */
private fun degrees(radians: Float): Float = radians * (HALF_TURN_DEGREES / PI.toFloat())

private const val HALF_TURN_DEGREES = 180f

/** An open floor edge shorter than this is the grid's own raggedness, not an opening. */
private const val MIN_OPEN_EDGE_M = 0.3f

/** The steps an open edge is cut into to leave out the part a wall stands on. */
private const val OPEN_STEP_M = 0.1f

/** A floor point this close to a wall's line is the wall's foot. */
private const val WALL_COVERS_M = 0.35f
