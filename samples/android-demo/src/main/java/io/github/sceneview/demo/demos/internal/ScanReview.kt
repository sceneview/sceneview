package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tan

/*
 * Review before you leave: at "Stop and review" the capture's dense map is read once for the
 * parts still to scan ([RescanZones]), while the scan is still open and in the same ARCore frame,
 * so "Fix now" resumes the same capture with the part already in place — nothing to line up.
 *
 * Pure: [ScanReview.of] reads the whole map, off the main thread; the rest is arithmetic.
 */

/**
 * What one review found: the [zones] to fix, largest first and at most [ScanReview.MAX_ZONES],
 * the room's middle on the floor plane ([centreX], [centreZ]) the camera looks at them from, and
 * how long the read took ([millis]).
 */
class ScanReviewResult(
    val zones: List<RescanZone>,
    val centreX: Float,
    val centreZ: Float,
    val millis: Long,
) {
    /** `true` when nothing is left to fix. */
    val clear: Boolean get() = zones.isEmpty()

    companion object {
        /** A capture with no dense map to read: nothing to say, the review is the plain one. */
        val Empty = ScanReviewResult(emptyList(), 0f, 0f, 0L)
    }
}

object ScanReview {
    /** The most parts a review lists: the largest, so the list stays a job that can be done before leaving. */
    const val MAX_ZONES = 5

    /** How long the 3D view rests on each part before it flies to the next, seconds. */
    const val DWELL_S = 3f

    /** The camera's pitch over a floor part, degrees: steep enough to see into it past furniture. */
    const val FLOOR_ELEVATION = 58f

    /** …and over a wall part: a look across the room at the wall. */
    const val WALL_ELEVATION = 28f

    /** How much room a part keeps round it in the picture: its extent times this fills the view's height. */
    const val FRAME_MARGIN = 1.8f

    /** The nearest and furthest the camera stands from a part, metres. */
    const val MIN_DISTANCE_M = 1.4f
    const val MAX_DISTANCE_M = 6f

    /** The height a wall part is looked at, metres above the floor, at most half the room's. */
    const val WALL_LOOK_M = 1.1f

    /** The band of a wall part the picture frames, metres: the band a fix fills ([RescanGuidance.WALL_HIGH_M]). */
    const val WALL_FRAME_M = 1.6f

    /**
     * How far the camera may stand back from a part, as a share of the part's distance to the
     * room's middle: short of the far side of the room, so it never looks through the opposite wall.
     */
    const val INSIDE_SHARE = 1.8f

    /**
     * Within this of the room's middle, a part has no side to be looked at from: the view keeps the
     * room's front ([ArDebugFraming.HOME_AZIMUTH]), metres.
     */
    const val CENTRED_M = 0.3f

    /**
     * The parts of [cloud] — the capture's dense map as it stands at Stop, what Save would write —
     * still to scan: the same finder the result's "Parts to scan again" runs on the saved scan, its
     * floor read off the map itself. [nanoTime] times it.
     */
    fun of(cloud: DenseCloud?, nanoTime: () -> Long = System::nanoTime): ScanReviewResult {
        if (cloud == null || cloud.count == 0) return ScanReviewResult.Empty
        val started = nanoTime()
        val ground = ScanGround.of(cloud.positions, cloud.normals, cloud.count)
        val zones = RescanSources.zones(cloud, ground, RescanSources.surveyOf(cloud))
        val b = cloud.bounds()
        return ScanReviewResult(
            zones = zones.take(MAX_ZONES),
            centreX = (b[0] + b[3]) / 2f,
            centreZ = (b[2] + b[5]) / 2f,
            millis = (nanoTime() - started) / NS_PER_MS,
        )
    }

    /** Which part the view shows [seconds] into the review, of [count]: each [DWELL_S], round and round. */
    fun focusAt(seconds: Float, count: Int): Int =
        if (count <= 0) 0 else (max(0f, seconds) / DWELL_S).toInt() % count

    /**
     * Where the 3D view looks at [zone] from: from the room's middle ([result]'s centre) side, so
     * a wall part is seen from inside the room and a floor part past the room's middle, framed in
     * a view [verticalFovDegrees] high.
     */
    fun poseFor(zone: RescanZone, result: ScanReviewResult, verticalFovDegrees: Double): OrbitPose {
        val box = zone.box
        val c = zone.centre
        val wall = zone.kind == RescanKind.MissingWall
        val floorY = box[1]
        val lookY = if (wall) floorY + minOf(WALL_LOOK_M, (box[4] - box[1]) / 2f) else floorY
        val dx = result.centreX - c[0]
        val dz = result.centreZ - c[1]
        val azimuth = if (abs(dx) < CENTRED_M && abs(dz) < CENTRED_M) {
            ArDebugFraming.HOME_AZIMUTH
        } else {
            Math.toDegrees(atan2(dx, dz).toDouble()).toFloat()
        }
        val extent = maxOf(box[3] - box[0], box[5] - box[2], if (wall) WALL_FRAME_M else 0f)
        val tanHalf = tan(Math.toRadians(verticalFovDegrees / 2.0)).toFloat()
        val elevation = if (wall) WALL_ELEVATION else FLOOR_ELEVATION
        val reach = hypot(dx, dz)
        val inside = if (reach < CENTRED_M) MAX_DISTANCE_M else INSIDE_SHARE * reach / cos(Math.toRadians(elevation.toDouble())).toFloat()
        val distance = minOf(FRAME_MARGIN * extent / 2f / tanHalf, inside).coerceIn(MIN_DISTANCE_M, MAX_DISTANCE_M)
        return OrbitPose(
            target = Position(c[0], lookY, c[1]),
            azimuthDegrees = azimuth,
            elevationDegrees = elevation,
            distance = distance,
        )
    }

    private const val NS_PER_MS = 1_000_000L
}

/**
 * "Fix now" on one part: the capture scans again, the part framed in amber, until it holds enough
 * points ([RescanGuidance.covered]) — read on the live map — and the review runs again. A part
 * already that full when the fix starts (a sparse patch, whose cells each hold a point or two)
 * has to gain [MIN_GAIN] of its cells, and every fix scans [MIN_SECONDS] at least: the review never
 * comes straight back with the same part. Started at [startedNanos] with [baseline] filled.
 */
class ScanFixWatch(val zone: RescanZone, val baseline: Float, private val startedNanos: Long) {
    /** Whether [fill], read at [nowNanos], is enough to review again. */
    fun done(fill: Float, nowNanos: Long): Boolean {
        if ((nowNanos - startedNanos) / NS_PER_S < MIN_SECONDS) return false
        if (!RescanGuidance.covered(fill)) return false
        return fill - baseline >= MIN_GAIN || fill >= FULL
    }

    companion object {
        /** The least a fix scans before the review comes back, seconds. */
        const val MIN_SECONDS = 3f

        /** The share of a part's cells a fix fills in, at least, when it started covered. */
        const val MIN_GAIN = 0.1f

        /** Every cell filled: done, whatever it started at. */
        const val FULL = 0.98f

        private const val NS_PER_S = 1e9f
    }
}

/** The review's words, English as the capture's other lines ([ScanCopy]). */
object ScanReviewCopy {
    const val CHECKING = "Checking for parts you missed…"
    const val CLEAR_TITLE = "Nothing left to fix"
    const val CLEAR_DETAIL = "Walls and floor are covered. Build the 3D model, or keep scanning to add more."
    const val FIX_NOW = "Fix now"
    const val BUILD_ANYWAY = "Build anyway"

    /** "2 parts to fix before you leave". */
    fun title(count: Int): String =
        if (count == 1) "1 part to fix before you leave" else "$count parts to fix before you leave"

    /** What [zone] is, short, as the result's list names it: "Missing wall · 1.2 m". */
    fun part(zone: RescanZone): String = when (zone.kind) {
        RescanKind.MissingWall -> "Missing wall · ${metres(zone.size)} m"
        RescanKind.FloorHole -> "Hole in the floor · ${metres(zone.size)} m²"
        RescanKind.LowDensity -> "Sparse area · ${metres(zone.size)} m²"
    }

    /** "Part 1 of 2 · Missing wall · 1.2 m". */
    fun focus(zone: RescanZone, index: Int, count: Int): String =
        if (count == 1) part(zone) else "Part ${index + 1} of $count · ${part(zone)}"

    private fun metres(value: Float): String = String.format(Locale.ROOT, "%.1f", (value * TENTHS).roundToInt() / TENTHS)

    private const val TENTHS = 10f
}
