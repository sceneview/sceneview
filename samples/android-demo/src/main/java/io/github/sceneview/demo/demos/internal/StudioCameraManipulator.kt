package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.lookAt
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.CinematicCameraProfile
import io.github.sceneview.cinematicAzimuth
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.gesture.zoomedDistanceForPinch
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.verticalFovDegreesForFocalLength
import kotlin.math.abs

/**
 * The camera rig behind the `camera-gestures` demo: an orbit / pan / zoom manipulator that
 * **coasts** when you let go, **flies** to a named pose when you ask it to, and can drive itself as
 * a cinematic turntable — while reporting, every frame, exactly where it is.
 *
 * ### Why this is not `DefaultCameraManipulator`
 *
 * The stock manipulator wraps Filament's `Manipulator`, which is a black box: it takes touch
 * deltas and yields a look-at pair. That is everything a viewer needs and nothing this screen
 * needs. Three of the demo's four claims are impossible through it:
 *
 * - **A readout.** `getLookAt` gives an eye and a target; azimuth, elevation and distance have to
 *   be re-derived every frame, and the numbers then belong to the demo rather than to the camera.
 *   Here the spherical [OrbitPose] *is* the state and the matrix is derived from it, so the HUD
 *   prints the same value the camera is using.
 * - **Inertia.** Filament's manipulator stops the instant the finger does. A camera that coasts is
 *   the single biggest difference between "a 3D view" and "a viewer", and it needs the angular
 *   velocity the gesture produced — a quantity the black box never exposes.
 * - **Flying somewhere.** Presets and tap-to-focus need to *animate* between two poses. With an
 *   opaque manipulator the only way there is to rebuild it at the destination, which teleports; the
 *   demo this replaces rebuilt its manipulator on every distance change for exactly that reason,
 *   and every change snapped.
 *
 * Owning the spherical state costs about a hundred lines and buys all three, plus clamps that are
 * expressed in the units a reader thinks in (`MIN_ELEVATION_DEGREES`, not a polar-angle guard
 * bolted on after the fact — see [CameraRig]).
 *
 * ### Frame contract
 *
 * `SceneView`'s frame loop calls [update] with the frame delta and then [getTransform], in that
 * order, on the main thread. Everything time-based — the flight, the coast, the turntable — is
 * integrated in [update]; [getTransform] only converts. Both are cheap and allocation-light enough
 * to run at 120 Hz.
 *
 * @param initialPose  Resting framing the screen opens on.
 * @param fitDistance  Auto-fit distance of whatever currently has focus. Read every frame, so the
 *                     zoom clamps follow the focus without the rig being rebuilt.
 * @param sensitivity  Multiplier on the orbit rate, driven by the demo's sensitivity slider.
 * @param inertiaEnabled Whether a release coasts. `false` parks the camera the instant the finger
 *                     lifts — the comparison that makes inertia legible as a feature.
 */
class StudioCameraManipulator(
    initialPose: OrbitPose,
    private val fitDistance: () -> Float,
    private val sensitivity: () -> Float = { CameraRig.DEFAULT_SENSITIVITY },
    private val inertiaEnabled: () -> Boolean = { true },
) : CameraGestureDetector.CameraManipulator {

    /**
     * Live camera state. A plain field, deliberately **not** Compose state: it changes every frame,
     * and a snapshot write per frame would recompose the whole overlay tree 60 times a second. The
     * HUD polls this from a `withFrameNanos` loop and only publishes when a *displayed* value
     * changes — the same shape as the transform readout the old screen used, for the same reason.
     */
    var pose: OrbitPose = initialPose
        private set

    /** The gesture in flight, or `null` when the camera is idle. Polled like [pose]. */
    var gesture: RigGesture? = null
        private set

    /** `true` while a preset / focus flight is playing. */
    val isFlying: Boolean get() = flightTo != null

    /** Turntable switch. Enabling it restarts the eased ramp from rest. */
    var cinematic: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                cinematicSeconds = 0f
            }
        }

    private var viewportWidth = 1
    private var viewportHeight = 1

    private var lastX = 0
    private var lastY = 0
    private var panning = false

    private var azimuthVelocity = 0f
    private var elevationVelocity = 0f

    /** Orbit degrees banked by [grabUpdate] since the last [update], per axis. */
    private var dragAzimuth = 0f
    private var dragElevation = 0f

    private var flightFrom: OrbitPose? = null
    private var flightTo: OrbitPose? = null
    private var flightSeconds = 0f
    private var flightDuration = 0f

    /** Turntable clock. Paused — not reset — while the user has the camera. */
    private var cinematicSeconds = 0f

    private val verticalFov = verticalFovDegreesForFocalLength(DEFAULT_FOCAL_LENGTH_MM)

    /**
     * Flies the camera to [destination] over [durationMillis].
     *
     * The flight starts from wherever the camera is *now*, including mid-flight: retargeting during
     * a flight bends the path instead of restarting it, so tapping two subjects in quick succession
     * reads as one continuous move.
     */
    fun flyTo(destination: OrbitPose, durationMillis: Int = CameraRig.FLIGHT_MILLIS) {
        stopCoast()
        flightFrom = pose
        flightTo = CameraRig.clamp(destination, fitDistance())
        flightSeconds = 0f
        flightDuration = (durationMillis.coerceAtLeast(1)) / 1000f
        gesture = RigGesture.Fly
    }

    /**
     * Re-places the camera at [distance] from its current pivot, without touching the angles.
     *
     * This is the slider's path into the rig, and the reason the slider and the pinch cannot
     * disagree: both publish a distance, neither moves the eye directly.
     */
    fun setDistance(distance: Float) {
        cancelFlight()
        pose = CameraRig.clamp(pose.copy(distance = distance), fitDistance())
    }

    override fun setViewport(width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
    }

    override fun getTransform(): Transform = Transform(
        lookAt(
            eye = CameraRig.eye(pose),
            target = pose.target,
            up = Float3(0f, 1f, 0f),
        )
    )

    override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
        cancelFlight()
        stopCoast()
        panning = strafe
        lastX = x
        lastY = y
        gesture = if (strafe) RigGesture.Pan else RigGesture.Orbit
    }

    override fun grabUpdate(x: Int, y: Int) {
        val dx = (x - lastX).toFloat()
        // `CameraGestureDetector` hands over y already flipped to screen-up-positive.
        val dy = (y - lastY).toFloat()
        lastX = x
        lastY = y
        if (dx == 0f && dy == 0f) return

        if (panning) {
            pose = CameraRig.clamp(pose.copy(target = pannedTarget(dx, dy)), fitDistance())
            return
        }

        val step = CameraRig.ORBIT_DEGREES_PER_PIXEL * safeSensitivity()
        // Drag right turns the subject right, which means the camera goes left; drag down lifts
        // the camera so the top of the subject comes into view. Both are the viewer convention
        // (Sketchfab, Polycam), and both are the negation of the raw delta.
        val deltaAzimuth = -dx * step
        val deltaElevation = -dy * step
        val next = CameraRig.clamp(
            pose.copy(
                azimuthDegrees = pose.azimuthDegrees + deltaAzimuth,
                elevationDegrees = pose.elevationDegrees + deltaElevation,
            ),
            fitDistance(),
        )
        // Bank the change that SURVIVED the clamp — a drag pinned against the elevation limit must
        // not build up a coast that fires the moment the finger lifts. Accumulated rather than
        // divided here: touch is sampled faster than the display, so several `grabUpdate` calls
        // land between two frames and each one alone covers a fraction of the frame's movement.
        // `update` turns the accumulated total into a velocity, at the very rate the coast is
        // integrated at.
        dragAzimuth += next.azimuthDegrees - pose.azimuthDegrees
        dragElevation += next.elevationDegrees - pose.elevationDegrees
        pose = next
    }

    override fun grabEnd() {
        gesture = null
        panning = false
        if (!inertiaEnabled()) stopCoast()
    }

    override fun scrollBegin(x: Int, y: Int, separation: Float) {
        cancelFlight()
        stopCoast()
        gesture = RigGesture.Zoom
    }

    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {
        val fit = fitDistance()
        pose = pose.copy(
            distance = zoomedDistanceForPinch(
                distance = pose.distance,
                prevSeparation = prevSeparation,
                currSeparation = currSeparation,
                minDistance = fit * CameraRig.MIN_DISTANCE_SCALE,
                maxDistance = fit * CameraRig.MAX_DISTANCE_SCALE,
            )
        )
    }

    override fun scrollEnd() {
        gesture = null
    }

    override fun update(deltaTime: Float) {
        val dt = deltaTime.takeIf { it.isFinite() && it > 0f && it < MAX_FRAME_SECONDS } ?: 0f

        // Turn the drag banked since the last frame into the angular velocity a release will coast
        // on. Done here, and only while a drag is live, so the number is a rate over the same
        // interval `update` integrates — see `dragAzimuth`.
        if (dt > 0f && gesture == RigGesture.Orbit) {
            azimuthVelocity = (dragAzimuth / dt).coerceIn(
                -CameraRig.MAX_INERTIA_DEGREES_PER_SECOND,
                CameraRig.MAX_INERTIA_DEGREES_PER_SECOND,
            )
            elevationVelocity = (dragElevation / dt).coerceIn(
                -CameraRig.MAX_INERTIA_DEGREES_PER_SECOND,
                CameraRig.MAX_INERTIA_DEGREES_PER_SECOND,
            )
        }
        dragAzimuth = 0f
        dragElevation = 0f

        val destination = flightTo
        if (destination != null) {
            flightSeconds += dt
            val raw = (flightSeconds / flightDuration).coerceIn(0f, 1f)
            val eased = SceneViewTokens.Ease.expressive.transform(raw)
            pose = CameraRig.lerp(flightFrom ?: pose, destination, eased)
            if (raw >= 1f) {
                pose = destination
                cancelFlight()
                // A flight is a re-framing, so the turntable resumes from rest at the new pose
                // rather than snapping back up to cruising speed.
                cinematicSeconds = 0f
            }
            return
        }

        if (gesture != null || dt == 0f) return

        val coasting = azimuthVelocity != 0f || elevationVelocity != 0f
        if (coasting) {
            pose = CameraRig.clamp(
                pose.copy(
                    azimuthDegrees = pose.azimuthDegrees + azimuthVelocity * dt,
                    elevationDegrees = pose.elevationDegrees + elevationVelocity * dt,
                ),
                fitDistance(),
            )
            azimuthVelocity = CameraRig.decayInertia(azimuthVelocity, dt)
            elevationVelocity = CameraRig.decayInertia(elevationVelocity, dt)
            // Elevation pinned by the clamp would coast forever against the limit.
            if (abs(pose.elevationDegrees - CameraRig.MAX_ELEVATION_DEGREES) < 1e-3f ||
                abs(pose.elevationDegrees - CameraRig.MIN_ELEVATION_DEGREES) < 1e-3f
            ) {
                elevationVelocity = 0f
            }
            return
        }

        if (cinematic) {
            // The SDK's own eased ramp (`cinematicAzimuth`), integrated as a delta so it spins the
            // user's current framing instead of teleporting to the profile's canonical orbit.
            val before = cinematicAzimuth(cinematicSeconds, TURNTABLE)
            cinematicSeconds += dt
            val after = cinematicAzimuth(cinematicSeconds, TURNTABLE)
            pose = pose.copy(
                azimuthDegrees = pose.azimuthDegrees + Math.toDegrees((after - before).toDouble()).toFloat()
            )
        }
    }

    /** Parks the coast and drops any drag banked for it. */
    private fun stopCoast() {
        azimuthVelocity = 0f
        elevationVelocity = 0f
        dragAzimuth = 0f
        dragElevation = 0f
    }

    /** Ends a flight without moving the camera — a touch during a flight takes it over in place. */
    private fun cancelFlight() {
        if (flightTo != null) {
            flightFrom = null
            flightTo = null
            flightSeconds = 0f
            gesture = null
        }
    }

    /** World-space pivot after a [dx], [dy] two-finger drag, in the camera's own screen plane. */
    private fun pannedTarget(dx: Float, dy: Float): Position {
        val metresPerPixel = CameraRig.worldPerPixel(pose.distance, verticalFov, viewportHeight)
        if (metresPerPixel <= 0f) return pose.target
        val forward = normalize(pose.target - CameraRig.eye(pose))
        val right = normalize(cross(forward, Float3(0f, 1f, 0f)))
        val up = cross(right, forward)
        // Dragging right drags the SCENE right, so the pivot travels left — the sign that makes
        // the point under the fingers stay under the fingers.
        return pose.target - right * (dx * metresPerPixel) - up * (dy * metresPerPixel)
    }

    private fun safeSensitivity(): Float = sensitivity()
        .takeIf { it.isFinite() && it > 0f }
        ?.coerceIn(CameraRig.MIN_SENSITIVITY, CameraRig.MAX_SENSITIVITY)
        ?: CameraRig.DEFAULT_SENSITIVITY

    private companion object {
        /** SceneView's default lens. Only used to convert pan pixels into metres. */
        const val DEFAULT_FOCAL_LENGTH_MM = 28.0

        /** A frame delta longer than this is a stall (or a resumed app); ignore it. */
        const val MAX_FRAME_SECONDS = 0.25f

        /**
         * Turntable feel: [CinematicCameraProfile.SlowCinematic]'s revolution time and ease-in.
         * Only the azimuth ramp is used — elevation, lens and framing stay the user's.
         */
        val TURNTABLE = CinematicCameraProfile.SlowCinematic
    }
}
