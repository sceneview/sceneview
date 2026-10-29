package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Which way to turn to the next part of the room no wall was seen on yet (#4540 live walls):
 * "turn left, this wall is missing". [Ahead] is already in view.
 */
enum class ScanTurn { Left, Right, Behind, Ahead }

/** The open floor [edge] (`ax, az, bx, bz`) the scan should go to next, and the [turn] to it. */
class ScanCue(val turn: ScanTurn, val edge: FloatArray)

/**
 * Where a room's scan goes next, and how far along it is: pure functions of the walls found, the
 * floor seen and the phone's pose, so the HUD's arrow, its line and its gauge run in a JVM test.
 */
object ScanGuide {

    /**
     * The next open edge to scan from [pose] among [openEdges] ([openFloorEdges]), and the turn to
     * it: the longest gap, a nearer one winning a tie of length — the wall most worth finding. The
     * turn is the edge's middle against the phone's heading on the floor: within [AHEAD_DEGREES]
     * either side it is [ScanTurn.Ahead], past [BEHIND_DEGREES] [ScanTurn.Behind]. `null` without
     * an edge or a pose.
     */
    fun cue(pose: DebugPose?, openEdges: List<FloatArray>): ScanCue? {
        if (pose == null || openEdges.isEmpty()) return null
        val edge = openEdges.maxBy { e ->
            val length = hypot(e[2] - e[0], e[3] - e[1])
            val mx = (e[0] + e[2]) / 2f - pose.x
            val mz = (e[1] + e[3]) / 2f - pose.z
            length / (1f + NEARNESS * hypot(mx, mz))
        }
        return ScanCue(turnTo(pose, (edge[0] + edge[2]) / 2f, (edge[1] + edge[3]) / 2f), edge)
    }

    /** The turn from [pose]'s heading to the floor point ([x], [z]). */
    fun turnTo(pose: DebugPose, x: Float, z: Float): ScanTurn {
        val forward = pose.rotate(0f, 0f, -1f)
        val right = pose.rotate(1f, 0f, 0f)
        val dx = x - pose.x
        val dz = z - pose.z
        // The phone held pitched down still faces where its forward points on the floor.
        val ahead = dx * forward.x + dz * forward.z
        val side = dx * right.x + dz * right.z
        if (abs(ahead) < EPSILON && abs(side) < EPSILON) return ScanTurn.Ahead
        val degrees = Math.toDegrees(atan2(side, ahead).toDouble())
        return when {
            abs(degrees) <= AHEAD_DEGREES -> ScanTurn.Ahead
            abs(degrees) >= BEHIND_DEGREES -> ScanTurn.Behind
            degrees > 0 -> ScanTurn.Right
            else -> ScanTurn.Left
        }
    }

    /**
     * Whether the walls close the room: [ROOM_WALLS] or more found, and what is left open of the
     * floor seen ([openEdges]) under [CLOSED_GAP_M] in all — a doorway's width.
     */
    fun closed(walls: Int, openEdges: List<FloatArray>): Boolean =
        walls >= ROOM_WALLS && openEdges.sumOf { hypot(it[2] - it[0], it[3] - it[1]).toDouble() } < CLOSED_GAP_M

    /**
     * The room's scan done so far, `0..1`, the one gauge the HUD shows: the walls found (up to
     * [ROOM_WALLS]) for [WALLS_SHARE] of it, the floor seen covered ([floorShare],
     * [CoverageGrid.coveredShareOf]) for the rest, weighed by the walls found: the floor seen early is
     * a corner of the room, so covering it all is not most of the room. A room whose walls [closed] it is done.
     */
    fun completion(walls: Int, floorShare: Float, closed: Boolean): Float {
        if (closed) return 1f
        val wallsPart = min(walls, ROOM_WALLS).toFloat() / ROOM_WALLS
        val floorPart = floorShare.coerceIn(0f, 1f) * wallsPart
        return max(0f, WALLS_SHARE * wallsPart + (1f - WALLS_SHARE) * floorPart).coerceAtMost(1f)
    }

    /** A room has four walls more often than not. */
    const val ROOM_WALLS = 4

    /** What the walls weigh in [completion]: a room is its walls first, its floor second. */
    const val WALLS_SHARE = 0.6f

    /** Less open than this, in metres, the room is closed: a door is left open. */
    const val CLOSED_GAP_M = 1f

    /** Within this of straight ahead (degrees), the gap is already in view. */
    const val AHEAD_DEGREES = 30.0

    /** Past this, it is behind the phone. */
    const val BEHIND_DEGREES = 135.0

    /** How much a metre's distance shortens a gap in [cue]'s choice. */
    private const val NEARNESS = 0.25f
    private const val EPSILON = 1e-4f

    private fun hypot(x: Float, z: Float): Float = sqrt(x * x + z * z)
}
