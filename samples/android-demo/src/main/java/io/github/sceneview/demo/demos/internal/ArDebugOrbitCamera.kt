package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.lookAt
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.gesture.zoomedDistanceForPinch
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.verticalFovDegreesForFocalLength
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The third-person camera of the Rerun demo's in-app 3D view (#3950).
 *
 * Two modes, and the switch between them is the whole design:
 *
 * - **Following** (the default, and what a double-tap returns to): the camera frames the session
 *   by itself — it eases towards [home], which the view recomputes as the trail and the planes
 *   grow, and drifts slowly round it. A phone held in one hand, walking a room, gets a view that
 *   keeps up without a finger on it.
 * - **Free**: the first touch takes over. Drag orbits (with inertia), two fingers pan, pinch
 *   zooms. Nothing moves the camera but the user until a double-tap hands it back.
 *
 * Same frame contract as [StudioCameraManipulator]: [update] integrates, [getTransform] converts.
 */
class ArDebugOrbitCamera(
    initialPose: OrbitPose = ArDebugFraming.DEFAULT_POSE,
    /** Slow turntable drift while following. Off in QA, so captures are deterministic. */
    var drift: Boolean = true,
    /**
     * How far above the view's centre the pivot is drawn, as a fraction of the view's height.
     * A full-screen stage with a HUD on top and a filmstrip below has its clear band above the
     * middle: lifting the picture there keeps the room out from under the filmstrip.
     */
    val lift: Float = 0f,
) : CameraGestureDetector.CameraManipulator {

    /** Current pose. Plain field, not Compose state: it changes every frame. */
    var pose: OrbitPose = initialPose
        private set

    /** Where following mode is heading; set by the view from the content bounds. */
    var home: OrbitPose = initialPose

    /** `true` while the camera frames the session by itself. */
    var following: Boolean = true
        private set

    /** Viewport height in pixels, for pan speed and for the view's screen-constant sizes. */
    var viewportHeight: Int = 1
        private set

    /** Viewport width in pixels; with [viewportHeight], the aspect [ArDebugFraming.home] fits. */
    var viewportWidth: Int = 1
        private set

    val aspect: Float get() = viewportWidth.toFloat() / viewportHeight

    /** Set by the view once it has snapped to the first content, so it snaps only once. */
    var hasFramedContent: Boolean = false

    val verticalFovDegrees: Double = verticalFovDegreesForFocalLength(FOCAL_LENGTH_MM)

    private var lastX = 0
    private var lastY = 0
    private var panning = false
    private var grabbing = false
    private var dragAzimuth = 0f
    private var dragElevation = 0f
    private var azimuthVelocity = 0f
    private var elevationVelocity = 0f

    /** Seconds of following, which ramps the drift in instead of starting it at full speed. */
    private var followSeconds = 0f

    /** Where the entrance crane ([playIntro]) started, `null` when none is playing. */
    private var introFrom: OrbitPose? = null
    private var introSeconds = 0f

    /**
     * Holds the crane on its first pose until [releaseIntro]: a scene still hidden behind its
     * loading cover (the GPU warming its shaders) must not spend the entrance where nobody sees it.
     */
    private var introHeld = false

    /** `true` while the entrance crane plays. */
    val introPlaying: Boolean get() = introFrom != null

    /**
     * The map framing: straight down on the room at [ArDebugFraming.MAP_ELEVATION], no drift, so
     * the floor plan reads like a plan. Switching hands the camera back to the automatic framing.
     */
    var overhead: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            recenter()
        }

    /**
     * Keeps the turntable still while something in the view is being read — a picked object's
     * box and its dimensions: a recenter still reframes, it does not start the room turning again.
     */
    var steady: Boolean = false

    /** The elevation the view frames [home] at: the three-quarter view, or the map's. */
    val homeElevation: Float
        get() = if (overhead) ArDebugFraming.MAP_ELEVATION else ArDebugFraming.HOME_ELEVATION

    /**
     * Plays the entrance: the camera jumps to [from] and cranes onto [home] over
     * [ReplayIntro.DURATION_S] — following [home] as it goes, so a view that reframes mid-flight
     * still lands. The first touch cuts it short, like any following. With [held], it waits on its
     * first pose until [releaseIntro].
     */
    fun playIntro(from: OrbitPose, held: Boolean = false) {
        introHeld = held
        following = true
        azimuthVelocity = 0f
        elevationVelocity = 0f
        followSeconds = 0f
        introSeconds = 0f
        introFrom = ArDebugFraming.clamp(from).also { pose = it }
    }

    /** Starts a crane [playIntro] held: the scene is on screen now. */
    fun releaseIntro() {
        introHeld = false
    }

    /** Hands the camera back to the automatic framing — the double-tap and the Recenter button. */
    fun recenter() {
        glide = null
        following = true
        followSeconds = 0f
        azimuthVelocity = 0f
        elevationVelocity = 0f
    }

    /**
     * Stops the automatic framing where it is, as a drag does: a finger put on the view to tap
     * something in it must find that thing still there when the tap is told from a double-tap.
     */
    fun hold() {
        glide = null
        if (following) takeOver()
    }

    /**
     * A finger put down on the view: [hold], unless it is the second tap of the double-tap that
     * has just asked for a recenter — the double-tap is told before its own finger down.
     */
    fun fingerDown() {
        if (!recenteredByTap) hold()
    }

    private var recenteredByTap = false

    /** Where the free camera is being slid to by [glideTo], `null` once there or once touched. */
    private var glide: OrbitPose? = null

    /**
     * Where the camera is once [glideTo]'s slide is over — [pose] itself with none under way. What
     * is written over the view is placed for this pose, so it is there from the slide's first frame.
     */
    val heading: OrbitPose get() = glide ?: pose

    /**
     * `true` while a finger moves the camera — an orbit, a pan, a pinch — and while the spin it
     * let go of dies down: nothing brings the picture back under a finger still at work on it.
     */
    val moving: Boolean
        get() = grabbing || pinching || azimuthVelocity != 0f || elevationVelocity != 0f

    private var pinching = false

    /**
     * Slides the free camera to [target] — a picked object brought out from under the chrome. The
     * next touch stops it where it is. While following, the view frames through [home] instead.
     */
    fun glideTo(target: OrbitPose) {
        if (following) return
        azimuthVelocity = 0f
        elevationVelocity = 0f
        glide = ArDebugFraming.clamp(target).takeIf { it != pose }
    }

    /** Puts the camera at [target] at once — the first frame of a view, where easing would lag. */
    fun snapTo(target: OrbitPose) {
        pose = ArDebugFraming.clamp(target)
    }

    override fun setViewport(width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
    }

    override fun getTransform(): Transform {
        val eye = CameraRig.eye(pose)
        val metresPerPixel = CameraRig.worldPerPixel(pose.distance, verticalFovDegrees, viewportHeight)
        // A pedestal move along the camera's own up axis: the picture slides, the orbit does not.
        val drop = if (lift == 0f || metresPerPixel <= 0f) {
            Float3()
        } else {
            val forward = normalize(pose.target - eye)
            val up = cross(normalize(cross(forward, Float3(0f, 1f, 0f))), forward)
            up * (lift * viewportHeight * metresPerPixel)
        }
        return Transform(lookAt(eye = eye - drop, target = pose.target - drop, up = Float3(0f, 1f, 0f)))
    }

    override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
        takeOver()
        grabbing = true
        panning = strafe
        lastX = x
        lastY = y
    }

    override fun grabUpdate(x: Int, y: Int) {
        val dx = (x - lastX).toFloat()
        val dy = (y - lastY).toFloat()
        lastX = x
        lastY = y
        if (dx == 0f && dy == 0f) return
        if (panning) {
            pose = pose.copy(target = pannedTarget(dx, dy))
            return
        }
        val next = ArDebugFraming.clamp(
            pose.copy(
                azimuthDegrees = pose.azimuthDegrees - dx * DEGREES_PER_PIXEL,
                elevationDegrees = pose.elevationDegrees - dy * DEGREES_PER_PIXEL,
            )
        )
        dragAzimuth += next.azimuthDegrees - pose.azimuthDegrees
        dragElevation += next.elevationDegrees - pose.elevationDegrees
        pose = next
    }

    // The gesture detector ends every gesture here, a pinch included: it never calls scrollEnd.
    override fun grabEnd() {
        grabbing = false
        panning = false
        pinching = false
    }

    override fun scrollBegin(x: Int, y: Int, separation: Float) {
        takeOver()
        pinching = true
    }

    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {
        pose = pose.copy(
            distance = zoomedDistanceForPinch(
                distance = pose.distance,
                prevSeparation = prevSeparation,
                currSeparation = currSeparation,
                minDistance = ArDebugFraming.MIN_DISTANCE,
                maxDistance = ArDebugFraming.MAX_DISTANCE,
            )
        )
    }

    override fun scrollEnd() {
        pinching = false
    }

    override fun doubleTapZoom(x: Int, y: Int, zoomIn: Boolean) {
        recenter()
        recenteredByTap = true
    }

    override fun update(deltaTime: Float) {
        recenteredByTap = false
        val dt = deltaTime.takeIf { it.isFinite() && it > 0f && it < 0.25f } ?: return
        if (grabbing && !panning) {
            azimuthVelocity = (dragAzimuth / dt).coerceIn(-MAX_SPIN, MAX_SPIN)
            elevationVelocity = (dragElevation / dt).coerceIn(-MAX_SPIN, MAX_SPIN)
        }
        dragAzimuth = 0f
        dragElevation = 0f
        if (grabbing) return

        if (following) {
            val from = introFrom
            if (from != null) {
                if (!introHeld) introSeconds += dt
                val progress = introSeconds / ReplayIntro.DURATION_S
                pose = ArDebugFraming.clamp(ReplayIntro.pose(from, home, progress))
                if (progress >= 1f) introFrom = null
                return
            }
            followSeconds += dt
            if (drift && !overhead && !steady) {
                // Ramp in over two seconds so recentering does not lurch into a spin.
                val ramp = (followSeconds / 2f).coerceAtMost(1f)
                home = home.copy(azimuthDegrees = home.azimuthDegrees + DRIFT_DEGREES_PER_SECOND * ramp * dt)
            }
            pose = ArDebugFraming.approach(pose, home, dt)
            return
        }

        glide?.let { to ->
            pose = ArDebugFraming.approach(pose, to, dt)
            if (pose == to) glide = null
            return
        }

        if (azimuthVelocity != 0f || elevationVelocity != 0f) {
            pose = ArDebugFraming.clamp(
                pose.copy(
                    azimuthDegrees = pose.azimuthDegrees + azimuthVelocity * dt,
                    elevationDegrees = pose.elevationDegrees + elevationVelocity * dt,
                )
            )
            azimuthVelocity = CameraRig.decayInertia(azimuthVelocity, dt)
            elevationVelocity = CameraRig.decayInertia(elevationVelocity, dt)
        }
    }

    private fun takeOver() {
        glide = null
        following = false
        introFrom = null
        azimuthVelocity = 0f
        elevationVelocity = 0f
        // Keep following mode's heading continuous: the home angle becomes the current one, so a
        // recenter later swings back from here rather than from where the drift had got to.
        home = home.copy(azimuthDegrees = pose.azimuthDegrees)
    }

    private fun pannedTarget(dx: Float, dy: Float): Position {
        val metresPerPixel = CameraRig.worldPerPixel(pose.distance, verticalFovDegrees, viewportHeight)
        if (metresPerPixel <= 0f) return pose.target
        val forward = normalize(pose.target - CameraRig.eye(pose))
        val right = normalize(cross(forward, Float3(0f, 1f, 0f)))
        val up = cross(right, forward)
        return pose.target - right * (dx * metresPerPixel) - up * (dy * metresPerPixel)
    }

    companion object {
        /** Same lens as every `SceneView` camera by default. */
        const val FOCAL_LENGTH_MM = 28.0
        const val DEGREES_PER_PIXEL = 0.28f
        const val MAX_SPIN = 360f
        const val DRIFT_DEGREES_PER_SECOND = 6f
    }
}

/** Pure framing maths of [ArDebugOrbitCamera]: where "home" is, and how the camera gets there. */
object ArDebugFraming {
    const val MIN_DISTANCE = 0.25f
    const val MAX_DISTANCE = 40f
    const val MIN_ELEVATION = -8f
    const val MAX_ELEVATION = 88f

    /** The three-quarter view from above-behind the Rerun viewer opens a spatial view with. */
    const val HOME_ELEVATION = 32f
    const val HOME_AZIMUTH = 35f

    /** The map's near-vertical view: a floor plan, with just enough tilt to keep depth. */
    const val MAP_ELEVATION = 84f

    /**
     * The fitting distance of the content's bounding sphere, as a multiple of it. Under 1: a room
     * is a flat box, and the sphere around it is much rounder than what the lens actually sees —
     * at 1.15 the room filled barely half of a phone's width.
     */
    const val HOME_MARGIN = 0.92f

    /**
     * The tighter margin of a bundled recording: its bounds are known up front and hold no
     * growth to leave room for, so the room fills the stage between the HUD and the filmstrip.
     * 0.66 ran a recorded room to 8 px of the screen's right edge on a Pixel 7a: 0.72 keeps it
     * inside the 16 dp the chrome keeps from the edges.
     */
    const val REPLAY_MARGIN = 0.72f

    /** A room-sized framing for an empty session. */
    val DEFAULT_POSE = OrbitPose(
        target = Position(0f, -0.4f, -0.5f),
        azimuthDegrees = HOME_AZIMUTH,
        elevationDegrees = HOME_ELEVATION,
        distance = 3.2f,
    )

    /** Rate of the exponential approach to home, per second. ~95 % in one second. */
    const val APPROACH_RATE = 3f

    fun clamp(pose: OrbitPose): OrbitPose = pose.copy(
        elevationDegrees = pose.elevationDegrees.coerceIn(MIN_ELEVATION, MAX_ELEVATION),
        distance = pose.distance.takeIf { it.isFinite() }?.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
            ?: DEFAULT_POSE.distance,
    )

    /**
     * The pose that frames [bounds] (`[minX, minY, minZ, maxX, maxY, maxZ]`, or null for an empty
     * session) at [azimuthDegrees], through a lens of [verticalFovDegrees] on a view of
     * [aspect] (width / height). The whole bounding sphere fits the **narrower** field of view,
     * so a tall phone and a small PiP both see everything.
     */
    fun home(
        bounds: FloatArray?,
        azimuthDegrees: Float,
        verticalFovDegrees: Double,
        aspect: Float,
        elevationDegrees: Float = HOME_ELEVATION,
        margin: Float = HOME_MARGIN,
    ): OrbitPose {
        if (bounds == null) {
            return DEFAULT_POSE.copy(azimuthDegrees = azimuthDegrees, elevationDegrees = elevationDegrees)
        }
        val cx = (bounds[0] + bounds[3]) / 2f
        val cy = (bounds[1] + bounds[4]) / 2f
        val cz = (bounds[2] + bounds[5]) / 2f
        val ex = bounds[3] - bounds[0]
        val ey = bounds[4] - bounds[1]
        val ez = bounds[5] - bounds[2]
        // A small session (the phone barely moved) still gets a room-sized view: 0.8 m radius.
        val radius = max(0.8f, sqrt(ex * ex + ey * ey + ez * ez) / 2f)
        val halfVertical = Math.toRadians(verticalFovDegrees / 2.0)
        val halfHorizontal = kotlin.math.atan(kotlin.math.tan(halfVertical) * aspect.coerceIn(0.2f, 5f))
        val halfFov = minOf(halfVertical, halfHorizontal)
        val distance = (radius / sin(halfFov)).toFloat() * margin
        return clamp(
            OrbitPose(
                target = Position(cx, cy, cz),
                azimuthDegrees = azimuthDegrees,
                elevationDegrees = elevationDegrees,
                distance = distance,
            )
        )
    }

    /**
     * The closest pose at [azimuthDegrees] and [elevationDegrees] that shows the whole of [bounds]
     * (`[minX, minY, minZ, maxX, maxY, maxZ]`) inside what [insets] leave free of a view of
     * [aspect] (width / height), through a lens of [verticalFovDegrees] (#4492).
     *
     * Where [home] fits a sphere around the content and shortens the result by a margin, this
     * projects the box's eight corners: a room is a flat box seen from above at an angle, and the
     * sphere says nothing of how wide it lands on a tall screen — a squarish room opened with a
     * side past the screen's edge. The distance is the least one at which every corner projects
     * inside the free area, on both axes; the target then slides in the picture plane so the
     * projected box is centred there, since perspective draws the near half of a box larger than
     * the far one and chrome is rarely the same height top and bottom.
     */
    fun fit(
        bounds: FloatArray,
        azimuthDegrees: Float,
        elevationDegrees: Float,
        verticalFovDegrees: Double,
        aspect: Float,
        insets: FramingInsets = FramingInsets.None,
    ): OrbitPose {
        val centre = Position(
            (bounds[0] + bounds[3]) / 2f,
            (bounds[1] + bounds[4]) / 2f,
            (bounds[2] + bounds[5]) / 2f,
        )
        val corners = List(BOX_CORNERS) { corner ->
            Position(
                bounds[if (corner and 1 == 0) 0 else 3],
                bounds[if (corner and 2 == 0) 1 else 4],
                bounds[if (corner and 4 == 0) 2 else 5],
            )
        }
        val from = OrbitPose(centre, azimuthDegrees, elevationDegrees.coerceIn(MIN_ELEVATION, MAX_ELEVATION))
        val view = Framed(corners, from, verticalFovDegrees, aspect, insets)
        val framed = clamp(from.copy(distance = view.distance()))
        return framed.copy(
            target = centre + view.right * view.horizontal.slide(framed.distance) +
                view.up * view.vertical.slide(framed.distance),
        )
    }

    /**
     * [pose] moved as little as it takes to show the whole of [corners] — a picked object's box —
     * inside what one of [insets] leaves free of the view: [pose] itself when it already does,
     * else the same angles with the picture slid, and stepped back only for a box the free area
     * is too small for at this distance. Never closer: the room keeps the size the finger left it.
     *
     * Each of [insets] is one way round the chrome — under a card in a corner, or beside it —
     * and the one that moves the camera least wins. [lift] is [ArDebugOrbitCamera.lift]: the
     * picture is drawn that share of the view's height above where the pose alone would put it.
     *
     * The bands are taken as they are, down to [REVEAL_MIN_FREE] of the view: [fit]'s floor of
     * [FramingInsets.MIN_FREE] cut back any pair covering more than four fifths of the height,
     * which on a small phone is the way under the card — the box was then found "clear" of bands
     * thinner than the chrome, and left behind it. A way round that leaves less than the floor is
     * not taken while another does; through a thin band the camera steps back until the box is
     * small enough to be whole in it.
     */
    fun reveal(
        pose: OrbitPose,
        corners: List<Position>,
        verticalFovDegrees: Double,
        aspect: Float,
        insets: List<FramingInsets>,
        lift: Float = 0f,
    ): OrbitPose {
        if (corners.isEmpty()) return pose
        var best = pose
        var bestCost = Float.MAX_VALUE
        val open = insets.filter { it.leaves(REVEAL_MIN_FREE) }.ifEmpty { insets }
        for (free in open) {
            val view = Framed(corners, pose, verticalFovDegrees, aspect, free, REVEAL_MIN_FREE)
            val distance = clamp(pose.copy(distance = max(pose.distance, view.distance()))).distance
            val across = view.horizontal.nudge(distance, raised = 0f)
            val upward = view.vertical.nudge(distance, raised = lift * 2f * view.tanVertical * distance)
            val cost = distance - pose.distance + abs(across) + abs(upward)
            if (cost < bestCost) {
                bestCost = cost
                best = if (cost == 0f) {
                    pose
                } else {
                    pose.copy(target = pose.target + view.right * across + view.up * upward, distance = distance)
                }
            }
        }
        return best
    }

    /**
     * [points] as a camera at [from]'s angles sees them from its target: each one's offset across
     * the picture, up it and away, and the two screen axes [insets] leave them.
     */
    private class Framed(
        points: List<Position>,
        from: OrbitPose,
        verticalFovDegrees: Double,
        aspect: Float,
        insets: FramingInsets,
        minFree: Float = FramingInsets.MIN_FREE,
    ) {
        val right: Float3
        val up: Float3
        val tanVertical = tan(Math.toRadians(verticalFovDegrees / 2.0)).toFloat()
        val horizontal: FitAxis
        val vertical: FitAxis
        private val nearest: Float

        init {
            val azimuth = Math.toRadians(from.azimuthDegrees.toDouble())
            val tilt = Math.toRadians(from.elevationDegrees.toDouble())
            val sinA = sin(azimuth).toFloat()
            val cosA = cos(azimuth).toFloat()
            val sinE = sin(tilt).toFloat()
            val cosE = cos(tilt).toFloat()
            // The camera's own axes at these angles, as CameraRig.eye and lookAt make them.
            right = Float3(cosA, 0f, -sinA)
            up = Float3(-sinA * sinE, cosE, -cosA * sinE)
            val forward = Float3(-cosE * sinA, -sinE, -cosE * cosA)
            val across = FloatArray(points.size) { dot(points[it] - from.target, right) }
            val upward = FloatArray(points.size) { dot(points[it] - from.target, up) }
            val away = FloatArray(points.size) { dot(points[it] - from.target, forward) }
            val tanHorizontal = tanVertical * aspect.coerceIn(0.2f, 5f)
            val free = insets.bounded(minFree)
            horizontal = FitAxis(across, away, tanHorizontal, -1f + 2f * free.left, 1f - 2f * free.right)
            vertical = FitAxis(upward, away, tanVertical, -1f + 2f * free.bottom, 1f - 2f * free.top)
            // Never closer than the nearest point: every one stays in front of the lens.
            nearest = MIN_DISTANCE - away.min()
        }

        /** The least distance at which every point fits the free area, on both axes. */
        fun distance(): Float = maxOf(nearest, horizontal.distance(), vertical.distance())
    }

    /**
     * One screen axis of [fit]: each corner's offset [along] it and its depth [away] past the
     * target, the tangent of half the field of view on this axis ([tanHalf]), and the free band
     * in normalised device coordinates, from [low] to [high] (-1 and 1 are the view's edges).
     */
    private class FitAxis(
        private val along: FloatArray,
        private val away: FloatArray,
        private val tanHalf: Float,
        private val low: Float,
        private val high: Float,
    ) {
        /**
         * The least distance at which the corners fit the band, wherever the target slides to.
         * Corner `i` stays under [high] when the slide is at least `along[i] - high·tan·(d + away[i])`
         * and corner `j` over [low] when it is at most `along[j] - low·tan·(d + away[j])`: a slide
         * exists once every such floor is under every such ceiling, which is linear in `d`.
         */
        fun distance(): Float {
            var distance = 0f
            for (i in along.indices) for (j in along.indices) {
                val needed = (along[i] - along[j] + tanHalf * (low * away[j] - high * away[i])) /
                    ((high - low) * tanHalf)
                distance = max(distance, needed)
            }
            return distance
        }

        /**
         * How far the target moves along this axis to centre the corners in the band at [distance].
         * On the axis that set the distance there is one slide that fits; on the other there is a
         * range, and the one that puts the corners' two extremes the same way from the band's
         * edges is found by halving it — both extremes move the same way as the target slides.
         */
        fun slide(distance: Float): Float {
            var floor = -Float.MAX_VALUE
            var ceiling = Float.MAX_VALUE
            for (i in along.indices) {
                floor = max(floor, along[i] - high * tanHalf * (distance + away[i]))
                ceiling = minOf(ceiling, along[i] - low * tanHalf * (distance + away[i]))
            }
            if (floor >= ceiling) return (floor + ceiling) / 2f
            repeat(CENTRING_STEPS) {
                val middle = (floor + ceiling) / 2f
                var least = Float.MAX_VALUE
                var most = -Float.MAX_VALUE
                for (i in along.indices) {
                    val drawn = (along[i] - middle) / (tanHalf * (distance + away[i]))
                    least = minOf(least, drawn)
                    most = max(most, drawn)
                }
                if (least + most > low + high) floor = middle else ceiling = middle
            }
            return (floor + ceiling) / 2f
        }

        /**
         * The least the target moves along this axis for the corners to be in the band at
         * [distance]: nothing when they already are. [raised] is how far the picture is drawn up
         * this axis from where the target alone would put it, in metres at the target's depth.
         */
        fun nudge(distance: Float, raised: Float): Float {
            var floor = -Float.MAX_VALUE
            var ceiling = Float.MAX_VALUE
            for (i in along.indices) {
                floor = max(floor, along[i] + raised - high * tanHalf * (distance + away[i]))
                ceiling = minOf(ceiling, along[i] + raised - low * tanHalf * (distance + away[i]))
            }
            return if (floor >= ceiling) (floor + ceiling) / 2f else 0f.coerceIn(floor, ceiling)
        }
    }

    private const val BOX_CORNERS = 8
    private const val CENTRING_STEPS = 24

    /** The thinnest band of a view [reveal] brings a box into: a few rows, stepped back for. */
    const val REVEAL_MIN_FREE = 0.04f

    /**
     * One frame of the ease from [pose] to [home]: an exponential approach, which is frame-rate
     * independent and retargets smoothly — home moves every time the trail grows.
     */
    fun approach(pose: OrbitPose, home: OrbitPose, deltaSeconds: Float): OrbitPose {
        val fraction = 1f - exp(-APPROACH_RATE * deltaSeconds)
        val next = CameraRig.lerp(pose, home, fraction)
        // Snap when close, so an idle view reaches a fixed point (render-on-demand can rest).
        val angleSettled = abs(CameraRig.shortestDelta(next.azimuthDegrees, home.azimuthDegrees)) < 0.01f &&
            abs(next.elevationDegrees - home.elevationDegrees) < 0.01f
        val targetOffset = abs(next.target.x - home.target.x) + abs(next.target.y - home.target.y) +
            abs(next.target.z - home.target.z)
        val reachSettled = abs(next.distance - home.distance) < 1e-4f && targetOffset < 1e-4f
        return if (angleSettled && reachSettled) home else next
    }
}

/**
 * What covers a view, as fractions of its width ([left], [right]) and of its height ([top],
 * [bottom]) measured inwards from each edge: the chrome [ArDebugFraming.fit] keeps the content
 * clear of.
 */
data class FramingInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    /**
     * These insets, never negative, and leaving at least [minFree] of each axis free: two bands
     * that add up to more than that are cut back in proportion.
     */
    fun bounded(minFree: Float = MIN_FREE): FramingInsets {
        val (boundedLeft, boundedRight) = boundedPair(left, right, minFree)
        val (boundedTop, boundedBottom) = boundedPair(top, bottom, minFree)
        return FramingInsets(boundedLeft, boundedTop, boundedRight, boundedBottom)
    }

    /** Whether these insets leave [minFree] of each axis free as they are, with nothing cut back. */
    fun leaves(minFree: Float): Boolean = bounded(minFree) == bounded(0f)

    private fun boundedPair(first: Float, second: Float, minFree: Float): Pair<Float, Float> {
        val a = first.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val b = second.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val covered = a + b
        val most = 1f - minFree
        return if (covered <= most) a to b else a * most / covered to b * most / covered
    }

    companion object {
        val None = FramingInsets()

        /** The least share of a view's width or height a framing is given. */
        const val MIN_FREE = 0.2f
    }
}
