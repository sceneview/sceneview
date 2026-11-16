package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.lookAt
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.verticalFovDegreesForFocalLength
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the scan result opens: [pose] about the middle of [box] (its centre on the floor plan, at
 * mid-height of what is drawn), and where on the screen that middle stands. [shiftX] and [shiftY]
 * are the shares of the view's width and height the eye stands moved by across and up the
 * picture, so the box sits whole in the zone the chrome leaves free; the camera still turns about
 * the box's middle, which stays on that one point of the screen at every angle and distance.
 */
internal class ScanResultStart(
    val pose: OrbitPose,
    val shiftX: Float,
    val shiftY: Float,
    val box: ScanResultFrame,
) {
    /** The furthest a pinch takes the eye: [ScanResultCamera.MAX_ZOOM_OUT] times the start's distance. */
    val maxDistance: Float get() = pose.distance * ScanResultCamera.MAX_ZOOM_OUT

    /** The start seen from above: the same centre, heading and distance. */
    val plan: OrbitPose get() = pose.copy(elevationDegrees = ScanResultCamera.PLAN_ELEVATION)

    /**
     * The least distance at [azimuthDegrees] and [elevationDegrees]: the box's own edge in the
     * direction the eye stands, plus [ScanResultCamera.NEAR_CLEARANCE_M]. The eye never enters it.
     */
    fun minDistance(azimuthDegrees: Float, elevationDegrees: Float): Float {
        val towardsEye = normalize(CameraRig.eye(OrbitPose(Position(), azimuthDegrees, elevationDegrees, 1f)))
        return box.exitDistance(towardsEye) + ScanResultCamera.NEAR_CLEARANCE_M
    }

    companion object {
        /**
         * The start for [frame]: a three-quarter on its front ([ScanResultFrame.frontDegrees], the
         * side the scan was taken from) at [ScanResultCamera.START_ELEVATION], at the distance that
         * shows the whole box inside what [insets] leave free of a view of [aspect] (width / height)
         * through [verticalFovDegrees]. The fit's slide in the picture plane is kept as [shiftX] and
         * [shiftY], so the box is placed the way the fit placed it without moving the pivot.
         */
        fun of(frame: ScanResultFrame, verticalFovDegrees: Double, aspect: Float, insets: FramingInsets): ScanResultStart {
            val fitted = frame.pose(
                ScanResultCamera.START_ELEVATION,
                ScanResultFraming.THREE_QUARTER,
                verticalFovDegrees,
                aspect,
                insets,
            )
            val pose = fitted.copy(target = frame.worldMiddle)
            val axes = PictureAxes.of(pose)
            val slide = fitted.target - pose.target
            val height = ScanResultCamera.viewHeight(pose.distance, verticalFovDegrees)
            val width = height * aspect.coerceAtLeast(MIN_ASPECT)
            return ScanResultStart(
                pose = pose,
                shiftX = dot(slide, axes.right) / width,
                shiftY = dot(slide, axes.up) / height,
                box = frame,
            )
        }

        private const val MIN_ASPECT = 0.1f
    }
}

/** The camera's right and up, for a pose at [OrbitPose.target]: the picture plane's two axes. */
internal class PictureAxes(val right: Float3, val up: Float3) {
    companion object {
        fun of(pose: OrbitPose): PictureAxes {
            val forward = normalize(pose.target - CameraRig.eye(pose))
            val right = normalize(cross(forward, WORLD_UP))
            return PictureAxes(right, cross(right, forward))
        }

        val WORLD_UP = Float3(0f, 1f, 0f)
    }
}

/**
 * The scan result's one camera (`camera.md`, 2026-11-13): the scanned place stands still at the
 * middle of the free zone, and only the finger moves the camera.
 *
 * - The centre is the middle of the box the result opens on, worked out once ([frameDrawn]) and
 *   never moved again: not when the model arrives, not by a double tap, not by a pinch.
 * - One finger turns about it, all the way round, and tilts between [MIN_ELEVATION] and
 *   [PLAN_ELEVATION]. Two fingers zoom along the line of sight, and nothing else: no pan.
 * - The zoom stops dead [NEAR_CLEARANCE_M] outside the box and at [MAX_ZOOM_OUT] times the start.
 * - A fling coasts for less than half a second; a touch stops it at once. The zoom has no coast.
 * - It moves by itself only when asked: a double tap flies back to the start ([recenter]), the
 *   plan flies to the look from above and back ([showPlan]), the film sweeps ([startFilm]); each
 *   in [FLY_SECONDS] but the film, and a touch cuts any of them short.
 *
 * Pure but for [getTransform]'s matrix: the tests drive it as the gesture detector does.
 */
internal class ScanResultCamera(
    val verticalFovDegrees: Double = verticalFovDegreesForFocalLength(ArDebugOrbitCamera.FOCAL_LENGTH_MM),
) : CameraGestureDetector.CameraManipulator {

    /** The start, once the view is shown: fixed from then on. */
    var start: ScanResultStart? = null
        private set

    /** Where the camera is now, about the start's centre. */
    var pose: OrbitPose = OrbitPose()
        private set

    /** `true` while the camera is on the look from above, or flying to it. */
    var plan: Boolean = false
        private set

    /** `true` while a fly or the film moves the camera. */
    val moving: Boolean get() = move != null

    /** `true` while the film plays: its approach onto the start, then its sweep. */
    val filming: Boolean get() = move is Move.Sweep || (move as? Move.Fly)?.then is Move.Sweep

    /** `true` while the film's sweep plays: what a recording films. */
    val sweeping: Boolean get() = move is Move.Sweep

    /** How many films reached their end, a touch not having cut them short. */
    var filmsLanded: Int = 0
        private set

    /** `true` once the view may be seen: the start no longer changes. */
    val shown: Boolean get() = start != null

    var viewportWidth: Int = 1
        private set
    var viewportHeight: Int = 1
        private set
    val aspect: Float get() = viewportWidth.toFloat() / viewportHeight

    /** The eye, as drawn: on the orbit, moved across the picture by the start's shift. */
    val eye: Position get() = CameraRig.eye(pose) + shift(pose)

    private var candidate: ScanResultStart? = null
    private var steady = 0
    private var beforePlan: OrbitPose? = null
    private var move: Move? = null

    /** A double tap asked for a fly since the last frame: its own finger down must not cut it. */
    private var flewByTap = false
    private var grabbing = false
    private var turning = false
    private var lastX = 0
    private var lastY = 0
    private var dragAzimuth = 0f
    private var dragElevation = 0f
    private var azimuthVelocity = 0f
    private var elevationVelocity = 0f

    /**
     * Puts the camera on [next], the start the result would open on now, until the view is shown:
     * from the composition, so the first frame is drawn there, and after each frame.
     */
    fun aim(next: ScanResultStart?) {
        if (shown || next == null) return
        if (next !== candidate) {
            candidate = next
            steady = 0
        }
        pose = next.pose
    }

    /**
     * A frame was drawn; [next] is the start the result would open on now and [ready] whether
     * there is something to show. Returns `true` once, on the frame after which the view may be
     * seen: [STEADY_FRAMES] frames drawn on the same start, which is then the start for good.
     */
    fun frameDrawn(next: ScanResultStart?, ready: Boolean): Boolean {
        if (shown) return false
        aim(next)
        if (next == null || !ready) {
            steady = 0
            return false
        }
        steady++
        if (steady < STEADY_FRAMES) return false
        start = next
        pose = next.pose
        return true
    }

    /** Flies back to the start in [FLY_SECONDS]: seen from above while [plan] is on. The centre stays. */
    fun recenter() {
        val from = start ?: return
        flyTo(if (plan) from.plan else from.pose)
    }

    /**
     * The look from above, or back from it: [on] flies to [PLAN_ELEVATION] at the same centre,
     * heading and distance; off, back where the camera was before. Each in [FLY_SECONDS].
     */
    fun showPlan(on: Boolean) {
        if (start == null || on == plan) return
        plan = on
        if (on) {
            beforePlan = pose
            flyTo(pose.copy(elevationDegrees = PLAN_ELEVATION))
        } else {
            val back = beforePlan ?: start?.pose ?: return
            beforePlan = null
            flyTo(back)
        }
    }

    /**
     * The film ([ResultFilm]): back on the start in [FLY_SECONDS] where the camera is not on it,
     * then the sweep round it. The plan is left: the film is shot on the start.
     */
    fun startFilm() {
        val from = start ?: return
        plan = false
        beforePlan = null
        stopCoast()
        val sweep = Move.Sweep(from.pose)
        move = if (pose == from.pose) sweep else Move.Fly(pose, from.pose, then = sweep)
    }

    private fun flyTo(to: OrbitPose) {
        stopCoast()
        move = Move.Fly(pose, to)
    }

    override fun setViewport(width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
    }

    override fun getTransform(): Transform {
        val moved = shift(pose)
        return Transform(lookAt(eye = CameraRig.eye(pose) + moved, target = pose.target + moved, up = PictureAxes.WORLD_UP))
    }

    /**
     * A touch-down: whatever moved the camera stops where it is, a coast or a fly — but the fly a
     * double tap has just asked for, which is told before its own finger down.
     */
    override fun stopInertia() {
        stopCoast()
        if (!flewByTap) move = null
    }

    override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
        // A drag takes the camera from anything, a double tap's fly included.
        stopCoast()
        move = null
        if (!shown) return
        grabbing = true
        // Two fingers dragged together would pan: the result has no pan, the scan cannot leave the screen.
        turning = !strafe
        lastX = x
        lastY = y
    }

    override fun grabUpdate(x: Int, y: Int) {
        val dx = (x - lastX).toFloat()
        val dy = (y - lastY).toFloat()
        lastX = x
        lastY = y
        if (!grabbing || !turning || (dx == 0f && dy == 0f)) return
        val next = turned(pose, -dx * DEGREES_PER_PIXEL, -dy * DEGREES_PER_PIXEL)
        dragAzimuth += next.azimuthDegrees - pose.azimuthDegrees
        dragElevation += next.elevationDegrees - pose.elevationDegrees
        pose = next
    }

    // The gesture detector ends every gesture here, a pinch included.
    override fun grabEnd() {
        grabbing = false
        turning = false
    }

    override fun scrollBegin(x: Int, y: Int, separation: Float) {
        stopCoast()
        move = null
    }

    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {
        if (!shown || prevSeparation <= 0f || currSeparation <= 0f) return
        pose = bounded(pose.copy(distance = pose.distance * prevSeparation / currSeparation))
    }

    override fun scrollEnd() = Unit

    /** A double tap flies back to the start; a two-finger tap does nothing. */
    override fun doubleTapZoom(x: Int, y: Int, zoomIn: Boolean) {
        if (!zoomIn) return
        recenter()
        flewByTap = moving
    }

    override val isFrameActive: Boolean
        get() = !shown || move != null || azimuthVelocity != 0f || elevationVelocity != 0f

    override fun update(deltaTime: Float) {
        flewByTap = false
        val dt = deltaTime.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(MAX_STEP_SECONDS) ?: return
        if (grabbing) {
            // The finger's speed, smoothed over a few frames: a finger held still before it lifts throws nothing.
            azimuthVelocity += (dragAzimuth / dt - azimuthVelocity) * VELOCITY_SMOOTHING
            elevationVelocity += (dragElevation / dt - elevationVelocity) * VELOCITY_SMOOTHING
            azimuthVelocity = azimuthVelocity.coerceIn(-MAX_SPIN, MAX_SPIN)
            elevationVelocity = elevationVelocity.coerceIn(-MAX_SPIN, MAX_SPIN)
            dragAzimuth = 0f
            dragElevation = 0f
            return
        }
        move?.let { advance(it, dt) }
        if (azimuthVelocity != 0f || elevationVelocity != 0f) coast(dt)
    }

    private fun advance(playing: Move, dt: Float) {
        playing.seconds += dt
        when (playing) {
            is Move.Fly -> {
                val t = (playing.seconds / FLY_SECONDS).coerceAtMost(1f)
                pose = CameraRig.lerp(playing.from, playing.to, easeInOut(t))
                if (t >= 1f) {
                    pose = playing.to
                    move = playing.then
                }
            }
            is Move.Sweep -> {
                val t = playing.seconds
                pose = playing.around.copy(azimuthDegrees = playing.around.azimuthDegrees + ResultFilm.azimuthOffsetAt(t))
                if (t >= ResultFilm.SECONDS) {
                    pose = playing.around
                    move = null
                    filmsLanded++
                }
            }
        }
    }

    private fun coast(dt: Float) {
        val decay = exp(-dt / COAST_SECONDS)
        azimuthVelocity *= decay
        elevationVelocity *= decay
        if (abs(azimuthVelocity) < STOP_DEGREES_PER_SECOND) azimuthVelocity = 0f
        if (abs(elevationVelocity) < STOP_DEGREES_PER_SECOND) elevationVelocity = 0f
        pose = turned(pose, azimuthVelocity * dt, elevationVelocity * dt)
        // A coast that runs into the pitch's end stops on it, with no bounce.
        if (pose.elevationDegrees <= MIN_ELEVATION || pose.elevationDegrees >= PLAN_ELEVATION) elevationVelocity = 0f
    }

    private fun stopCoast() {
        azimuthVelocity = 0f
        elevationVelocity = 0f
        dragAzimuth = 0f
        dragElevation = 0f
    }

    private fun turned(from: OrbitPose, azimuth: Float, elevation: Float): OrbitPose = bounded(
        from.copy(
            azimuthDegrees = from.azimuthDegrees + azimuth,
            elevationDegrees = (from.elevationDegrees + elevation).coerceIn(MIN_ELEVATION, PLAN_ELEVATION),
        ),
    )

    /** [candidate] at a distance the start allows at its angles: out of the box, within the zoom out. */
    private fun bounded(candidate: OrbitPose): OrbitPose {
        val from = start ?: return candidate
        val near = from.minDistance(candidate.azimuthDegrees, candidate.elevationDegrees)
        val far = maxOf(from.maxDistance, near)
        return candidate.copy(distance = candidate.distance.coerceIn(near, far))
    }

    /** How far the eye and its target stand moved across and up the picture at [at]: the start's shift. */
    private fun shift(at: OrbitPose): Float3 {
        val from = start ?: candidate ?: return Float3()
        if (from.shiftX == 0f && from.shiftY == 0f) return Float3()
        val axes = PictureAxes.of(at)
        val height = viewHeight(at.distance, verticalFovDegrees)
        return axes.right * (from.shiftX * height * aspect) + axes.up * (from.shiftY * height)
    }

    private sealed class Move {
        var seconds = 0f

        class Fly(val from: OrbitPose, val to: OrbitPose, val then: Move? = null) : Move()

        class Sweep(val around: OrbitPose) : Move()
    }

    companion object {
        /** The start's height of view, degrees: a three-quarter from above, its top and its front both seen. */
        const val START_ELEVATION = 35f

        /** The lowest look, degrees: almost at eye height, never level with the floor. */
        const val MIN_ELEVATION = 10f

        /** The look from above, degrees: straight down but for the degree that keeps the screen's up. */
        const val PLAN_ELEVATION = 89f

        /** How far outside the box the eye stops, metres: a detail is seen, the model never entered. */
        const val NEAR_CLEARANCE_M = 0.15f

        /** The furthest zoom out, times the start's distance: the scan stays an object in the middle. */
        const val MAX_ZOOM_OUT = 3f

        /** A double tap's, the plan's and the film's approach: brisk, under a finger. */
        const val FLY_SECONDS = 0.4f

        /** The coast's time constant: under half a second from a fling to rest. */
        const val COAST_SECONDS = 0.1f

        /** Under this the coast is over, degrees per second. */
        const val STOP_DEGREES_PER_SECOND = 2f

        /** The fastest a fling throws, degrees per second: at most a quarter turn's coast of [COAST_SECONDS]. */
        const val MAX_SPIN = 240f

        /** What one pixel of drag turns, degrees: the result's turn as before. */
        const val DEGREES_PER_PIXEL = ArDebugOrbitCamera.DEGREES_PER_PIXEL

        /** Frames drawn on one start before the view is seen: the chrome is measured over the first ones. */
        const val STEADY_FRAMES = DollhouseOpening.STEADY_FRAMES

        private const val VELOCITY_SMOOTHING = 0.5f
        private const val MAX_STEP_SECONDS = 0.05f

        /** The world height a view of [verticalFovDegrees] shows at [distance]. */
        fun viewHeight(distance: Float, verticalFovDegrees: Double): Float =
            (2.0 * distance * tan(Math.toRadians(verticalFovDegrees / 2.0))).toFloat()

        /** Out of rest and into rest: a move that starts and lands without a jolt. */
        fun easeInOut(t: Float): Float = t * t * (3f - 2f * t)
    }
}

/** The middle of the box in the world: its centre on the floor plan, at mid-height. */
internal val ScanResultFrame.worldMiddle: Position
    get() {
        val (x, z) = worldCentre
        return Position(x, (local[1] + local[4]) / 2f, z)
    }

/**
 * How far a ray from the box's middle along [direction] (a unit world vector) runs before it
 * leaves the box, metres: measured in the box's own turned frame, so a room square to its walls
 * is left by its walls, not by the world box that holds it.
 */
internal fun ScanResultFrame.exitDistance(direction: Float3): Float {
    val yaw = Math.toRadians(yawDegrees.toDouble())
    val c = kotlin.math.cos(yaw).toFloat()
    val s = sin(yaw).toFloat()
    // The world's x and z in the box's a and b: the inverse of ScanResultFrame's turn.
    val along = abs(direction.x * c + direction.z * s)
    val across = abs(-direction.x * s + direction.z * c)
    val up = abs(direction.y)
    val halfA = (local[3] - local[0]) / 2f
    val halfY = (local[4] - local[1]) / 2f
    val halfB = (local[5] - local[2]) / 2f
    var exit = Float.MAX_VALUE
    if (along > EPSILON) exit = min(exit, halfA / along)
    if (up > EPSILON) exit = min(exit, halfY / up)
    if (across > EPSILON) exit = min(exit, halfB / across)
    return if (exit == Float.MAX_VALUE) 0f else exit
}

private const val EPSILON = 1e-6f
