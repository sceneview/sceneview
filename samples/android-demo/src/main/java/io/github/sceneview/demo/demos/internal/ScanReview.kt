package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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
 *
 * A sparse patch is judged against the room ([RescanZones.LOW_SHARE] of its [median] density,
 * read from [floorY] up into a room [height] high, [RescanSources.coverage]): a fix of one is
 * measured the same way ([ScanReview.fillOf]). [setAside] counts the parts found again where a
 * fix was already tried ([ScanReview.untried]): said, never offered again.
 */
@Suppress("LongParameterList") // what one review read, kept for its fixes
class ScanReviewResult(
    val zones: List<RescanZone>,
    val centreX: Float,
    val centreZ: Float,
    val millis: Long,
    val floorY: Float = 0f,
    val height: Float = RescanZones.DEFAULT_HEIGHT_M,
    val median: Float = 0f,
    val setAside: Int = 0,
) {
    /** `true` when nothing is left to fix. */
    val clear: Boolean get() = zones.isEmpty()

    /** This review without the parts a fix was already tried on ([tried]). */
    fun without(tried: List<RescanZone>): ScanReviewResult {
        val kept = ScanReview.untried(zones, tried)
        if (kept.size == zones.size) return this
        return ScanReviewResult(kept, centreX, centreZ, millis, floorY, height, median, setAside + zones.size - kept.size)
    }

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
        // One read: the finder's own input, whose median its sparse patches are judged against —
        // and a fix of one with them.
        val input = RescanSources.input(cloud, ground, RescanSources.surveyOf(cloud))
        val zones = input?.let(RescanZones::find).orEmpty()
        val b = cloud.bounds()
        return ScanReviewResult(
            zones = zones.take(MAX_ZONES),
            centreX = (b[0] + b[3]) / 2f,
            centreZ = (b[2] + b[5]) / 2f,
            millis = (nanoTime() - started) / NS_PER_MS,
            floorY = input?.floorY ?: b[1],
            height = input?.height ?: RescanZones.DEFAULT_HEIGHT_M,
            median = input?.let(RescanZones::median) ?: 0f,
        )
    }

    /**
     * How much of [zone] [cloud] has filled in, 0…1, measured as the review found it: a sparse
     * patch by the share of its [RescanSources.COVERAGE_CELL_M] cells now at [RescanZones.LOW_SHARE]
     * of [review]'s median density or more — a sparse patch has a point in most small cells from
     * the start, so a point count says it is full when it is not; a wall or a hole by the share of
     * [RescanGuidance]'s cells with a point in them.
     */
    fun fillOf(zone: RescanZone, cloud: DenseCloud, review: ScanReviewResult?): Float =
        if (zone.kind == RescanKind.LowDensity && review != null && review.median > 0f) {
            densityFill(zone, cloud, review)
        } else {
            RescanGuidance.fill(zone.kind, zone.box, cloud)
        }

    private fun densityFill(zone: RescanZone, cloud: DenseCloud, review: ScanReviewResult): Float {
        val cell = RescanSources.COVERAGE_CELL_M
        val box = zone.box
        val columns = max(1, ceil((box[3] - box[0]) / cell).toInt())
        val rows = max(1, ceil((box[5] - box[2]) / cell).toInt())
        val density = FloatArray(columns * rows)
        val bottom = review.floorY - RescanSources.COVERAGE_FLOOR_BAND_M
        val top = review.floorY + min(RescanSources.COVERAGE_TOP_M, RescanSources.COVERAGE_TOP_SHARE * review.height)
        val perM2 = 1f / (cell * cell)
        val p = cloud.positions
        for (i in 0 until cloud.count) {
            val x = p[i * 3]
            val y = p[i * 3 + 1]
            val z = p[i * 3 + 2]
            if (y !in bottom..top || x < box[0] || x >= box[3] || z < box[2] || z >= box[5]) continue
            val col = ((x - box[0]) / cell).toInt().coerceIn(0, columns - 1)
            val row = ((z - box[2]) / cell).toInt().coerceIn(0, rows - 1)
            density[row * columns + col] += (1 shl (2 * cloud.levelOf(i))) * perM2
        }
        val low = RescanZones.LOW_SHARE * review.median
        return density.count { it >= low }.toFloat() / density.size
    }

    /**
     * [zones] without the sparse patches found again where a fix of a sparse patch was [tried]
     * (scanned for a while, the caller's to judge): overlapping one by [TRIED_OVERLAP] of the
     * smaller footprint, it is as dense as this capture gets there. The review says so, and never
     * offers it again as it was. A missing wall or a hole is never set aside: it is still missing,
     * whatever was tried — and a sparse patch tried by a wall never hides the wall.
     */
    fun untried(zones: List<RescanZone>, tried: List<RescanZone>): List<RescanZone> = zones.filter { zone ->
        zone.kind != RescanKind.LowDensity || tried.none { done ->
            val smaller = min(zone.footprintM2, done.footprintM2)
            done.kind == RescanKind.LowDensity && smaller > 0f && zone.overlapM2(done.box) >= TRIED_OVERLAP * smaller
        }
    }

    /** The share of the smaller footprint two parts share to be the same part. */
    const val TRIED_OVERLAP = 0.5f

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
 * "Fix now" on one part: the capture scans again, the part framed in amber, until it is filled in
 * ([RescanGuidance.covered], measured by [ScanReview.fillOf]) — read on the live map — and the
 * review runs again. Every fix gains [MIN_GAIN] of its part at least and scans [MIN_SECONDS] at
 * least: the review never comes straight back with the same part. A part that cannot gain that
 * much is never done here: Stop and review sets it aside ([ScanReview.untried]). Started at
 * [startedNanos] with [baseline] filled.
 */
class ScanFixWatch(val zone: RescanZone, val baseline: Float, private val startedNanos: Long) {
    /** Whether [fill], read at [nowNanos], is enough to review again. */
    fun done(fill: Float, nowNanos: Long): Boolean {
        if ((nowNanos - startedNanos) / NS_PER_S < MIN_SECONDS) return false
        return RescanGuidance.covered(fill) && fill - baseline >= MIN_GAIN
    }

    companion object {
        /** The least a fix scans before the review comes back, seconds. */
        const val MIN_SECONDS = 3f

        /** The share of a part a fix fills in, at least. */
        const val MIN_GAIN = 0.1f

        private const val NS_PER_S = 1e9f
    }
}

/** The review's words, English as the capture's other lines ([ScanCopy]). */
object ScanReviewCopy {
    const val CHECKING = "Checking for parts you missed…"
    const val CLEAR_TITLE = "Nothing left to fix"
    const val CLEAR_DETAIL = "Walls and floor are covered. Tap ${CaptureChrome.BUILD_MODEL}, or keep scanning to add more."
    const val NEXT_PART = "Next part"

    /** Parts a fix was tried on and that came back: as good as this capture gets there. */
    fun setAside(count: Int): String = if (count == 1) {
        "1 part you fixed still looks thin: this is as far as it goes."
    } else {
        "$count parts you fixed still look thin: this is as far as they go."
    }

    /** Fix now's spoken label: "Fix part 1 of 2, Missing wall". */
    fun fixLabel(zone: RescanZone, index: Int, count: Int): String =
        "Fix part ${index + 1} of $count, ${kindOf(zone.kind)}"

    private fun kindOf(kind: RescanKind) = when (kind) {
        RescanKind.MissingWall -> "Missing wall"
        RescanKind.FloorHole -> "Hole in the floor"
        RescanKind.LowDensity -> "Sparse area"
    }
    const val FIX_NOW = "Fix now"
    const val BUILD_ANYWAY = "Build anyway"

    /** "2 parts to fix before you leave". */
    fun title(count: Int): String =
        if (count == 1) "1 part to fix before you leave" else "$count parts to fix before you leave"

    /** What [zone] is, short, as the result's list names it: "Missing wall · 1.2 m". */
    fun part(zone: RescanZone): String =
        "${kindOf(zone.kind)} · ${metres(zone.size)} ${if (zone.kind == RescanKind.MissingWall) "m" else "m²"}"

    /** "Part 1 of 2 · Missing wall · 1.2 m". */
    fun focus(zone: RescanZone, index: Int, count: Int): String =
        if (count == 1) part(zone) else "Part ${index + 1} of $count · ${part(zone)}"

    private fun metres(value: Float): String = String.format(Locale.ROOT, "%.1f", (value * TENTHS).roundToInt() / TENTHS)

    private const val TENTHS = 10f
}
