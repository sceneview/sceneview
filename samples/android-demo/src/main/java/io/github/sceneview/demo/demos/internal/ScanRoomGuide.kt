package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * The Live Room's guide: a room is scanned in first person, the camera image full screen and the
 * capture painted on it — the walls recognised so far standing on their real walls. Over that, one
 * chevron at the screen's edge towards the largest gap ([GapChevron]), a small north-up model of the
 * room with the phone where it really is, and one short message at a time ([LiveRoomMessage]).
 * Pure, so the priority of the messages and the chevron's place run in a JVM test.
 */

/**
 * The one short message a room's capture shows, by priority: the first that holds wins, and
 * nothing else is said with it. Each reads as its [blocker]'s short word, and plays its haptic.
 */
enum class LiveRoomMessage(val blocker: ScanBlocker) {
    TrackingLost(ScanBlocker.TrackingLost),
    TooDark(ScanBlocker.TooDark),

    /** The depth sensor gives nothing usable, before any point was captured. */
    NoDepth(ScanBlocker.NoDepth),

    /** The same once points exist: the scan stopped growing for lack of depth. */
    DepthLost(ScanBlocker.DepthLost),
    SlowDown(ScanBlocker.TooFast),
    MoveCloser(ScanBlocker.LowTexture),

    /** The scan cannot take more: not a way to scan better, so it comes after the four. */
    Full(ScanBlocker.Full),
}

/** Where the [GapChevron] stands and points. */
data class GapChevronPlace(
    /** Across the screen from its centre, `-1` at the left edge to `1` at the right one. */
    val x: Float,
    /** Down the screen from its centre, `-1` at the top edge to `1` at the bottom one. */
    val y: Float,
    /** The way it points on screen, degrees clockwise from pointing right. */
    val degrees: Float,
)

object LiveRoomGuide {

    /**
     * The message to show, or `null` when the scan goes well: Tracking lost, then Too dark, then
     * the depth lost ([depth], [LiveRoomMessage.NoDepth] or [LiveRoomMessage.DepthLost]: nothing is
     * captured, so it goes before how to move), then Slow down, then Move closer. A track lost for lack of light ([dark], ARCore's own reason)
     * reads Too dark, which says what to do about it; any other loss reads Tracking lost, over
     * everything else.
     */
    fun message(
        trackingLost: Boolean,
        dark: Boolean,
        tooFast: Boolean,
        moveCloser: Boolean,
        full: Boolean = false,
        depth: LiveRoomMessage? = null,
    ): LiveRoomMessage? = when {
        trackingLost && !dark -> LiveRoomMessage.TrackingLost
        dark -> LiveRoomMessage.TooDark
        depth != null -> depth
        tooFast -> LiveRoomMessage.SlowDown
        moveCloser -> LiveRoomMessage.MoveCloser
        full -> LiveRoomMessage.Full
        else -> null
    }

    /**
     * The capture's one cue for a room in [phase]: the [message] as one short line; else, once the
     * room is [done], the line that says to stop ([CaptureChrome.roomLine]); else nothing — the
     * chevron and the walls on the camera say where to go. Paused, how to resume.
     */
    fun cue(phase: ScanPhase, message: LiveRoomMessage?, done: RoomDone?): CaptureCue? = when (phase) {
        ScanPhase.Paused -> CaptureCue.Next(CaptureChrome.PAUSED_NEXT)
        ScanPhase.Scanning -> when {
            message != null -> CaptureCue.Blocked(message.blocker, brief = true)
            done != null -> CaptureCue.Next(requireNotNull(CaptureChrome.roomLine(ScanCopy.ROOM_DONE, done)))
            else -> null
        }
        else -> null
    }
}

/**
 * The chevron at the screen's edge towards the largest gap of a room's scan ([ScanGuide.cue]):
 * shown only while that gap is off screen, on the edge the gap lies past, pointing at it. A gap
 * behind the phone puts it on the side to turn to.
 */
object GapChevron {

    /**
     * Where the chevron goes for the world point ([tx], [ty], [tz]) seen by the camera at
     * [cameraToWorld] through [projection] (column-major, ARCore's), or `null` while the point is
     * on screen. Once [shown], it stays until the point is well inside ([HIDE_INSIDE]), so a gap
     * at the edge of the picture does not blink. [maxY] keeps it off the top and bottom bands the
     * status card and the controls take, as a share of the half-height.
     */
    @Suppress("LongParameterList") // the eye, the lens, the point and the two bounds
    fun place(
        cameraToWorld: FloatArray,
        projection: FloatArray,
        tx: Float,
        ty: Float,
        tz: Float,
        shown: Boolean = false,
        maxY: Float = MAX_Y,
    ): GapChevronPlace? {
        // Into the camera's frame: the transpose of its rotation, about its position.
        val px = tx - cameraToWorld[12]
        val py = ty - cameraToWorld[13]
        val pz = tz - cameraToWorld[14]
        val lx = cameraToWorld[0] * px + cameraToWorld[1] * py + cameraToWorld[2] * pz
        val ly = cameraToWorld[4] * px + cameraToWorld[5] * py + cameraToWorld[6] * pz
        val lz = cameraToWorld[8] * px + cameraToWorld[9] * py + cameraToWorld[10] * pz
        val cx = projection[0] * lx + projection[4] * ly + projection[8] * lz + projection[12]
        val cy = projection[1] * lx + projection[5] * ly + projection[9] * lz + projection[13]
        val w = projection[3] * lx + projection[7] * ly + projection[11] * lz + projection[15]
        var dx: Float
        var dy: Float
        if (w > EPSILON) {
            dx = cx / w
            dy = cy / w
            val limit = if (shown) HIDE_INSIDE else 1f
            if (abs(dx) <= limit && abs(dy) <= limit) return null
        } else {
            // Behind the phone: the side to turn to is the side it lies on. Never up or down: a
            // phone held tilted would send a gap straight behind to the top or the bottom edge.
            dx = if (lx < 0f) -1f else 1f
            dy = 0f
        }
        val length = sqrt(dx * dx + dy * dy)
        if (length < EPSILON) {
            dx = 1f
            dy = 0f
        } else {
            dx /= length
            dy /= length
        }
        // On the edge of the screen's box, the y band cut to [maxY]; screen y runs down.
        val k = min(if (abs(dx) > EPSILON) 1f / abs(dx) else Float.MAX_VALUE, if (abs(dy) > EPSILON) maxY / abs(dy) else Float.MAX_VALUE)
        val x = (dx * k).coerceIn(-1f, 1f)
        val y = (-dy * k).coerceIn(-maxY, maxY)
        val degrees = Math.toDegrees(atan2(-dy, dx).toDouble()).toFloat()
        return GapChevronPlace(x, y, degrees)
    }

    /** The point the chevron aims at for a floor [edge] (`ax, az, bx, bz`): its middle, at the eye's [height]. */
    fun target(edge: FloatArray, height: Float): Triple<Float, Float, Float> =
        Triple((edge[0] + edge[2]) / 2f, height, (edge[1] + edge[3]) / 2f)

    /** A gap shown goes once it is this far inside the picture, in normalised device units. */
    const val HIDE_INSIDE = 0.7f

    /** The chevron's band across the screen's height: clear of the status card and the controls. */
    const val MAX_Y = 0.55f

    private const val EPSILON = 1e-4f
}

/**
 * The north-up framing of the small room model: world x to the right, world z down the page, the
 * box of what was seen and of the phone fitted with a margin. Not heading-up: the room stands still
 * and the phone moves on it, as on a floor plan.
 */
data class MiniatureFrame(val centreX: Float, val centreZ: Float, val pxPerMetre: Float) {
    companion object {
        /**
         * The frame that fits [xs] × [zs] (world metres, paired) in a [width] × [height] canvas
         * with [margin] pixels of air, never closer than [MIN_HALF_RANGE_M] round its centre nor
         * further than [MAX_HALF_RANGE_M].
         */
        fun of(xs: FloatArray, zs: FloatArray, width: Float, height: Float, margin: Float): MiniatureFrame? {
            if (xs.isEmpty() || xs.size != zs.size || width <= 0f || height <= 0f) return null
            val minX = xs.min()
            val maxX = xs.max()
            val minZ = zs.min()
            val maxZ = zs.max()
            val half = min(width, height) / 2f
            val clearW = max(width - 2f * margin, 1f)
            val clearH = max(height - 2f * margin, 1f)
            val spanX = max(maxX - minX, EPSILON)
            val spanZ = max(maxZ - minZ, EPSILON)
            val fit = min(clearW / spanX, clearH / spanZ)
            val scale = fit.coerceIn(half / MAX_HALF_RANGE_M, half / MIN_HALF_RANGE_M)
            return MiniatureFrame((minX + maxX) / 2f, (minZ + maxZ) / 2f, scale)
        }

        /** The model never zooms closer than a metre and a half either side of its centre. */
        const val MIN_HALF_RANGE_M = 1.5f

        /** Nor further than a hall's width. */
        const val MAX_HALF_RANGE_M = 15f

        private const val EPSILON = 1e-3f
    }
}
