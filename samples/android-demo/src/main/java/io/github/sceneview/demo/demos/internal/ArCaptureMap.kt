package io.github.sceneview.demo.demos.internal

import kotlin.math.hypot

/*
 * The live top-down map of an AR take (#4083): where the phone went, the keyframes along the
 * way, and the surfaces it found — what the session is capturing, drawn as it happens, instead
 * of four numbers. Pure Kotlin so the accumulation rules are unit-tested without ARCore; the
 * demo feeds it from `onSessionUpdated` and the overlay draws its snapshots.
 */

/** A point on the floor plan, in world metres: x to the right, z towards the viewer. */
data class MapPoint(val x: Float, val z: Float)

/** A surface's footprint on the floor plan. A wall projects to a thin sliver, drawn as a line. */
data class MapSurface(val id: Int, val outline: List<MapPoint>, val vertical: Boolean)

/**
 * One immutable frame of the map, safe to hand to Compose: the decimated path, its keyframes,
 * the surfaces, and where the phone is now and which way it faces ([headingRadians], 0 = world
 * -z, growing clockwise seen from above).
 */
data class CaptureMapSnapshot(
    val path: List<MapPoint>,
    val keyframes: List<MapPoint>,
    val surfaces: List<MapSurface>,
    val current: MapPoint?,
    val headingRadians: Float,
) {
    val isEmpty: Boolean get() = path.isEmpty() && surfaces.isEmpty()

    companion object {
        val EMPTY = CaptureMapSnapshot(emptyList(), emptyList(), emptyList(), null, 0f)
    }
}

/**
 * Accumulates a take's floor plan. Not thread-safe: fed and read from the AR frame callback.
 *
 * - The path keeps a point every [pathStepMeters] of travel, so a phone held still adds nothing
 *   and the list grows with distance, not with time. Past [maxPathPoints] it drops every other
 *   point, keeping the shape and the most recent end.
 * - A keyframe is marked every [keyframeStepMeters] of tracked travel — the spacing a
 *   reconstruction wants between views.
 * - A surface is replaced wholesale on each update (ARCore grows and merges planes), and dropped
 *   when [forgetSurface] says it was merged into another.
 * - Untracked samples are ignored: a lost phone has no position worth drawing.
 */
class CaptureMapRecorder(
    private val pathStepMeters: Float = PATH_STEP_METERS,
    private val keyframeStepMeters: Float = KEYFRAME_STEP_METERS,
    private val maxPathPoints: Int = MAX_PATH_POINTS,
) {
    private val path = ArrayList<MapPoint>()
    private val keyframes = ArrayList<MapPoint>()
    private val surfaces = LinkedHashMap<Int, MapSurface>()
    private var current: MapPoint? = null
    private var heading = 0f
    private var sinceKeyframe = 0f

    fun addCamera(x: Float, z: Float, headingRadians: Float, tracking: Boolean) {
        if (!tracking) return
        val point = MapPoint(x, z)
        current = point
        heading = headingRadians
        val last = path.lastOrNull()
        if (last == null) {
            path += point
            keyframes += point
            return
        }
        val step = hypot(point.x - last.x, point.z - last.z)
        if (step < pathStepMeters) return
        path += point
        sinceKeyframe += step
        if (sinceKeyframe >= keyframeStepMeters) {
            keyframes += point
            sinceKeyframe = 0f
        }
        if (path.size > maxPathPoints) thin()
    }

    fun updateSurface(surface: MapSurface) {
        if (surface.outline.size < 2) return
        surfaces[surface.id] = surface
    }

    fun forgetSurface(id: Int) {
        surfaces.remove(id)
    }

    fun snapshot(): CaptureMapSnapshot = CaptureMapSnapshot(
        path = path.toList(),
        keyframes = keyframes.toList(),
        surfaces = surfaces.values.toList(),
        current = current,
        headingRadians = heading,
    )

    fun reset() {
        path.clear()
        keyframes.clear()
        surfaces.clear()
        current = null
        heading = 0f
        sinceKeyframe = 0f
    }

    /** Halves the path, always keeping its first and last points. */
    private fun thin() {
        val kept = path.filterIndexed { index, _ -> index % 2 == 0 || index == path.lastIndex }
        path.clear()
        path += kept
    }
}

/** Heading of a camera whose forward axis (its -z) points along ([forwardX], [forwardZ]). */
fun headingOf(forwardX: Float, forwardZ: Float): Float = kotlin.math.atan2(forwardX, -forwardZ)

/**
 * The fixed map the QA "recording" state shows (the emulator has no AR session): a walk
 * around a table, with the floor, the table top and one wall found.
 */
val QA_CAPTURE_MAP: CaptureMapSnapshot = run {
    val recorder = CaptureMapRecorder()
    val steps = 90
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val angle = -0.4f + t * 4.2f
        val radius = 1.1f - 0.25f * t
        val x = kotlin.math.sin(angle) * radius
        val z = kotlin.math.cos(angle) * radius
        recorder.addCamera(x, z, headingOf(-x, -z), tracking = true)
    }
    recorder.updateSurface(
        MapSurface(
            id = 1,
            outline = listOf(
                MapPoint(-1.6f, -1.3f), MapPoint(1.4f, -1.5f), MapPoint(1.7f, 0.9f),
                MapPoint(0.4f, 1.6f), MapPoint(-1.5f, 1.2f),
            ),
            vertical = false,
        ),
    )
    recorder.updateSurface(
        MapSurface(
            id = 2,
            outline = listOf(
                MapPoint(-0.45f, -0.3f), MapPoint(0.45f, -0.3f),
                MapPoint(0.45f, 0.3f), MapPoint(-0.45f, 0.3f),
            ),
            vertical = false,
        ),
    )
    recorder.updateSurface(
        MapSurface(
            id = 3,
            outline = listOf(MapPoint(-1.7f, -1.75f), MapPoint(1.5f, -1.95f)),
            vertical = true,
        ),
    )
    recorder.snapshot()
}

/** A path point every 3 cm of travel: smooth at walking pace, nothing added while still. */
const val PATH_STEP_METERS = 0.03f

/** A keyframe every 25 cm of tracked travel. */
const val KEYFRAME_STEP_METERS = 0.25f

/** Path points kept before thinning — a long take stays a light polyline. */
const val MAX_PATH_POINTS = 600
