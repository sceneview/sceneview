package io.github.sceneview.demo.demos.internal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.lookAt
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.gesture.zoomedDistanceForPinch
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.verticalFovDegreesForFocalLength
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

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
     * The recorded camera pose the view steps into ([stepInside]), `null` in the orbit. The flight
     * blends the orbit's view into it over [MemoryPalace.FLIGHT_S]: there, the photo the phone
     * took from that pose lines up with the scan and fills the screen.
     */
    var visit: DebugPose? = null
        private set

    /** `true` from [stepInside] to [stepOut]: the chrome's "Step out". Compose state. */
    var inside: Boolean by mutableStateOf(false)
        private set

    /** 0 in the orbit, 1 at [visit]'s pose; eased by [getTransform]. */
    var visitAmount: Float = 0f
        private set

    /** Flies into [pose], or, already inside, moves there — scrubbing walks the recorded path. */
    fun stepInside(pose: DebugPose) {
        visit = pose
        inside = true
        azimuthVelocity = 0f
        elevationVelocity = 0f
    }

    /** Flies back out to the orbit, which kept its place. */
    fun stepOut() {
        inside = false
    }

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
        following = true
        followSeconds = 0f
        azimuthVelocity = 0f
        elevationVelocity = 0f
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
        val visiting = visit
        if (visiting == null || visitAmount <= 0f) {
            return Transform(lookAt(eye = eye - drop, target = pose.target - drop, up = Float3(0f, 1f, 0f)))
        }
        val from = eye - drop
        val to = pose.target - drop
        val orbitPose = MemoryPalace.lookPose(Vec3(from.x, from.y, from.z), Vec3(to.x, to.y, to.z))
        val m = MemoryPalace.matrix(MemoryPalace.blend(orbitPose, visiting, MemoryPalace.ease(visitAmount)))
        return Mat4(
            Float4(m[0], m[1], m[2], m[3]),
            Float4(m[4], m[5], m[6], m[7]),
            Float4(m[8], m[9], m[10], m[11]),
            Float4(m[12], m[13], m[14], m[15]),
        )
    }

    override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
        // A finger inside a photo steps back out to the model, where it can turn it.
        stepOut()
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

    override fun grabEnd() {
        grabbing = false
        panning = false
    }

    override fun scrollBegin(x: Int, y: Int, separation: Float) {
        takeOver()
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

    override fun scrollEnd() = Unit

    override fun doubleTapZoom(x: Int, y: Int, zoomIn: Boolean) = recenter()

    override fun update(deltaTime: Float) {
        val dt = deltaTime.takeIf { it.isFinite() && it > 0f && it < 0.25f } ?: return
        if (visit != null) {
            val goal = if (inside) 1f else 0f
            val step = dt / MemoryPalace.FLIGHT_S
            visitAmount = if (goal > visitAmount) {
                minOf(goal, visitAmount + step)
            } else {
                maxOf(goal, visitAmount - step)
            }
            if (visitAmount <= 0f && !inside) visit = null
        }
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
            if (drift && !overhead) {
                // Ramp in over two seconds so recentering does not lurch into a spin.
                val ramp = (followSeconds / 2f).coerceAtMost(1f)
                home = home.copy(azimuthDegrees = home.azimuthDegrees + DRIFT_DEGREES_PER_SECOND * ramp * dt)
            }
            pose = ArDebugFraming.approach(pose, home, dt)
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
