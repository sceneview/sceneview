package io.github.sceneview.demo.demos.internal.room

/*
 * A room's structure, read from a scan (#4540): its floor and ceiling, the walls that bound it —
 * each a straight line in plan, whatever its angle — the doors and windows cut in them, and the
 * figures a floor plan gives: area, perimeter, height, volume and each wall's length, each with
 * how far it can be trusted.
 *
 * Pure Kotlin, on purpose: no Android type and no renderer, so the engine can leave this demo as
 * a library. Positions are metres in the scan's frame, +Y up; a plan is drawn on X and Z.
 *
 * What the scan did not see is not invented. A wall the scan never saw is "deduced": the outline
 * is closed along where the floor was seen to end, the wall marked as such, and every figure it
 * bounds an estimate. A figure is given only when the walls close the room — each side seen, or
 * deduced between two seen ones — on at least [RoomStructure.MIN_SEEN_WALLS] distinct walls (one
 * wall seen twice counts once). Two adjacent walls seen up to their corner give the area, the
 * perimeter and the volume as estimates, the floor seen standing for the other two; otherwise
 * what is left is an indicative plan.
 */

/** A scan as the engine reads it: the dense cloud's surfels (a normal per point), the camera's path, ARCore's planes. */
class RoomScan(
    val positions: FloatArray,
    val normals: FloatArray?,
    val trail: FloatArray = FloatArray(0),
    val planes: List<RoomPlane> = emptyList(),
)

/** What an ARCore plane is to the room. */
enum class RoomPlaneKind { Floor, Ceiling, Wall, Other }

/** An ARCore plane: its [kind] and its outline, `x, y, z` per vertex in the scan's frame. */
class RoomPlane(val kind: RoomPlaneKind, val polygon: FloatArray)

/**
 * One figure: its [value], the scatter of the surfels it was read from ([plusMinus] — not an
 * accuracy), and whether part of it was not seen. An [estimate] has no scatter: [plusMinus] is 0.
 */
data class RoomFigure(val value: Float, val plusMinus: Float, val estimate: Boolean = false) {
    companion object {
        /** A figure read with [spread] — which an [estimate], not being measured, does not carry. */
        fun of(value: Float, spread: Float, estimate: Boolean) =
            RoomFigure(value, if (estimate) 0f else spread, estimate)
    }
}

/** A door reaches the floor; a window has a sill. */
enum class OpeningKind { Door, Window }

/**
 * A hole in a wall where the wall was seen all around it: [from] metres along the wall from its
 * start, [width] wide, its bottom [sill] above the floor (0 for a door) and [height] tall.
 * [confidence] is 0..1: how much of the hole was seen empty, more when the scan saw through it.
 */
class RoomOpening(
    val kind: OpeningKind,
    val from: Float,
    val width: Float,
    val sill: Float,
    val height: Float,
    val confidence: Float,
)

/**
 * One wall of the outline, from ([ax], [az]) to ([bx], [bz]) in the scan's frame, the room on
 * the side its normal ([nx], [nz]) points to.
 *
 * @property seenShare the part of its length the scan saw a wall on (or a door or a window in one), 0..1.
 * @property seen `false` under [RoomStructure.MIN_SEEN_SHARE]: its length an estimate.
 * @property deduced no wall at all was seen there: the outline was closed where the floor ends.
 * @property unseen the stretches the scan saw no wall on, in metres from the wall's start.
 * @property height how high the wall was seen to stand, metres above the floor.
 */
@Suppress("LongParameterList")
class RoomWall(
    val ax: Float,
    val az: Float,
    val bx: Float,
    val bz: Float,
    val nx: Float,
    val nz: Float,
    val length: RoomFigure,
    val seenShare: Float,
    val seen: Boolean,
    val deduced: Boolean,
    val unseen: List<ClosedFloatingPointRange<Float>>,
    val height: Float,
    val openings: List<RoomOpening>,
) {
    /** How much the wall is trusted, 0..1: what was seen of it, nothing when it was deduced. */
    val confidence: Float get() = if (deduced) 0f else seenShare
}

/** What a scan lacks for a full reading, each a thing its owner can go and scan. */
enum class RoomGap {
    /** No dense cloud with normals: a scan made without depth. */
    NoDepth,

    /** Not one wall was seen high enough to be told from furniture, or the outline could not be closed. */
    NoWalls,

    /**
     * A wall seen was not followed to its end: the floor seen stops short of where it ends, or it
     * of where the floor does. No area is given until it is.
     */
    WallEnd,

    /** A wall of the outline was not seen: the figures it bounds are estimates, or none is given. */
    MissingWall,

    /** Too little ceiling seen for a height. */
    Ceiling,
}

/**
 * How a room's outline was read: whether its sides were [traced] off the walls (`false`: it is the
 * floor's own edge, [miss] saying why), whether its walls [closed] it — each side seen, or held
 * between two seen ones — and on how many [distinctWalls], one wall seen twice counting once.
 * [floorArea] is the area of the floor the outline was read around, [tracedArea] the area the
 * traced sides held when they closed but were not kept.
 */
@Suppress("LongParameterList")
class RoomReading(
    val traced: Boolean,
    val closed: Boolean,
    val distinctWalls: Int,
    val miss: TraceMiss? = null,
    val floorArea: Float = 0f,
    val tracedArea: Float? = null,
)

/** Why a room's sides could not be traced off its walls, its outline then being the floor's own edge. */
enum class TraceMiss {
    /** No stretch of the floor's edge runs along a wall. */
    NoWallAlong,

    /** The floor's edge reads as fewer than three sides. */
    TooFewSides,

    /** Two neighbouring sides are parallel, so never meet. */
    SidesApart,

    /** The sides cross each other. */
    Crossing,

    /** The sides close around an area too far from the floor's. */
    AreaApart,
}

/**
 * The structure of one room. [walls] is its outline in order, the room on each wall's normal
 * side — empty when no wall was seen. Every figure is `null` unless the walls close the room —
 * each side seen, or deduced between two seen ones — on at least [MIN_SEEN_WALLS] distinct walls
 * ([gaps] says what is missing), but a [length] or [width] measured between two seen walls, and
 * the [area], [perimeter] and [volume] of two adjacent walls seen up to their corner, as estimates;
 * [ceilingHeight] is `null` when the ceiling was not seen.
 *
 * @property floorY the floor's height in the scan's frame, [floorThickness] how thick its layer of surfels is.
 * @property yaw the room's turn about +Y from the scan's axes: the turn that squares its main walls.
 * @property confidence 0..1: the share of the outline's length a wall was seen along.
 * @property reading how its outline was read; `null` when no wall was seen.
 */
@Suppress("LongParameterList")
class RoomStructure(
    val walls: List<RoomWall>,
    val floorY: Float,
    val floorThickness: Float,
    val yaw: Float,
    val ceilingHeight: RoomFigure?,
    val area: RoomFigure?,
    val perimeter: RoomFigure?,
    val volume: RoomFigure?,
    val length: RoomFigure?,
    val width: RoomFigure?,
    val confidence: Float,
    val gaps: List<RoomGap>,
    val reading: RoomReading? = null,
) {
    /** How many walls of the outline were seen. */
    val seenWalls: Int get() = walls.count { it.seen }

    /** The room's area is given: its walls close it, or two adjacent walls bound it (an estimate). */
    val measured: Boolean get() = area != null

    /** The doors and windows of every wall. */
    val openings: List<RoomOpening> get() = walls.flatMap { it.openings }

    /** A clean, closed mesh of the room: floor, walls and ceiling, each flat, doors and windows cut out. */
    fun mesh(): StructureMesh = StructureMesh.of(this)

    companion object {
        /**
         * Under this many distinct seen walls, the walls do not close the room: two adjacent ones
         * give its figures only as estimates, two facing ones only the width between them — they
         * leave its length to the floor's edge.
         */
        const val MIN_SEEN_WALLS = 3

        /** A wall seen on less than this share of its length is not seen: its length is an estimate. */
        const val MIN_SEEN_SHARE = 0.5f

        /** A room with nothing read: [gap] says why. */
        fun empty(gap: RoomGap, floorY: Float = 0f, floorThickness: Float = 0f, ceiling: RoomFigure? = null) =
            RoomStructure(
                emptyList(), floorY, floorThickness, 0f, ceiling, null, null, null, null, null, 0f,
                listOfNotNull(gap, RoomGap.Ceiling.takeIf { ceiling == null }),
            )

        /** Reads a whole scan: the full engine, a few hundred milliseconds for a room. */
        fun read(scan: RoomScan): RoomStructure = RoomReader.read(scan)

        /**
         * The cheap reading for a scan in progress: ARCore's planes and the cloud fused so far,
         * read coarse. A few milliseconds; call it off the main thread, about once a second.
         */
        fun live(scan: RoomScan): LiveRoom = RoomReader.live(scan)
    }
}

/** A wall as the live reading sees it: a segment in the scan's frame, how high it stands and how sure the reading is. */
class LiveRoomWall(
    val ax: Float,
    val az: Float,
    val bx: Float,
    val bz: Float,
    val height: Float,
    val confidence: Float,
)

/**
 * What [RoomStructure.live] reads: the walls seen so far and the outline they suggest
 * ([outline], `x, z` per corner in the scan's frame; empty while there is none), with its area
 * once the walls seen close the room, as [RoomStructure.area] — marked as an estimate where the
 * floor seen stands for walls not seen. [millis] is how long the reading took.
 */
class LiveRoom(
    val walls: List<LiveRoomWall>,
    val outline: FloatArray,
    val area: RoomFigure?,
    val floorY: Float?,
    val millis: Float,
)
