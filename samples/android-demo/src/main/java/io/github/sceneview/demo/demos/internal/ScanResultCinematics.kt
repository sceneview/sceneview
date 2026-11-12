package io.github.sceneview.demo.demos.internal

import io.github.sceneview.camera.CameraAnimator
import io.github.sceneview.camera.CameraPath
import io.github.sceneview.camera.CameraShot
import io.github.sceneview.camera.CinematicCameraManipulator

/** The demo's orbit pose as a library [CameraShot]: the same four numbers, the same convention. */
internal fun OrbitPose.toShot(): CameraShot = CameraShot(
    target = target,
    azimuthDegrees = azimuthDegrees,
    elevationDegrees = elevationDegrees,
    distance = distance,
)

/** A library [CameraShot] as the demo's orbit pose. */
internal fun CameraShot.toOrbitPose(): OrbitPose = OrbitPose(
    target = target,
    azimuthDegrees = azimuthDegrees,
    elevationDegrees = elevationDegrees,
    distance = distance,
)

/**
 * The scan result's camera moves (#4631), over [orbit] through [manipulator]: the reveal once
 * the model is drawn on its framing, the fly to a new framing (3D ↔ Plan, Recenter) and the
 * tour. Every move is the library's — time-based, exact at its end — and the first touch hands
 * the orbit camera the shot and the speed the move had.
 *
 * Driven from `onFrame`, after each drawn frame, by [frameDrawn]; the buttons only [request].
 *
 * Without [reveals] the camera opens where the framing is and stays there: no crane down onto it
 * (Scan redesign, PR 1: « pas de mouvement de caméra imposé »). With them, [entrance] is the
 * path onto the framing: the library's crane by default, [ScanOpening] for a result that opens on
 * its Photo layer.
 */
internal class ScanResultCinematics(
    val orbit: ArDebugOrbitCamera,
    val animator: CameraAnimator = CameraAnimator(CameraShot()),
    private val reveals: Boolean = true,
    private val entrance: (CameraShot) -> CameraPath = { CameraPath.reveal(it) },
) {
    /** The `cameraManipulator` for the `SceneView`: the animator while it plays, [orbit] otherwise. */
    val manipulator = CinematicCameraManipulator(animator, orbit)

    enum class Request { Recenter, ToggleTour }

    /** `true` once the model is drawn on its framing: the reveal has started, if there is one. */
    var revealed = false
        private set

    /** `true` while the tour plays. A touch, a request or a reframe ends it. */
    var touring = false
        private set

    /** The scan's larger horizontal side, metres: how far the tour's dolly and fly-through push in. */
    var extent = 1f

    private var shown = false
    private var pending: Request? = null

    /** What the next frame should do, from a button; one request at a time, the last one wins. */
    fun request(request: Request) {
        pending = request
    }

    /**
     * A frame was drawn with the camera on [manipulator]; [home] is the framing of the moment
     * and [ready] whether there is a model to show. Returns `true` on the one frame after which
     * the view may be seen: the first frame of the reveal, not the still frame before it.
     */
    fun frameDrawn(opening: DollhouseOpening, home: OrbitPose, ready: Boolean): Boolean {
        if (touring && !animator.isPlaying) touring = false
        if (!revealed) {
            val framed = ready && opening.drawn(orbit, home)
            opening.aim(orbit, home)
            if (framed) {
                revealed = true
                if (reveals) animator.play(entrance(ArDebugFraming.clamp(home).toShot()))
            }
            return false
        }
        opening.aim(orbit, home)
        when (pending) {
            Request.Recenter -> recenter(home)
            Request.ToggleTour -> if (touring) stopTour() else tour(home)
            null -> Unit
        }
        pending = null
        if (!shown && (animator.elapsed > 0f || !animator.isPlaying)) {
            shown = true
            return true
        }
        return false
    }

    /** Flies to [home] — the framing of the moment — and leaves the orbit camera there, following. */
    private fun recenter(home: OrbitPose) {
        touring = false
        animator.flyTo(handOff(home).toShot(), flyOver = 0f)
    }

    /**
     * The tour, from the framing: played at once when the camera is on it, after a fly onto it
     * when the user had taken the camera somewhere else — a tour never starts with a cut.
     */
    private fun tour(home: OrbitPose) {
        val atHome = orbit.following && orbit.pose == ArDebugFraming.clamp(home)
        val target = handOff(home)
        // Crane, dolly, fly-through, pull-out (#4719): from rest into the lap, then the lap loops.
        val path = ScanTourPath.of(target.toShot(), extent)
        val play = { animator.play(path.leadIn, onFinished = { animator.play(path.lap) }) }
        if (atHome && !animator.isPlaying) {
            play()
        } else {
            animator.flyTo(target.toShot(), flyOver = 0f, onFinished = play)
        }
        touring = true
    }

    /**
     * Starts the animator from the picture on screen and hands the orbit camera [home], following:
     * where every move of the animator lands, so its end is seamless. Returns the clamped [home].
     */
    private fun handOff(home: OrbitPose): OrbitPose {
        val target = ArDebugFraming.clamp(home)
        // A camera the user moved is where the move starts, not where the last move stopped.
        if (!animator.isPlaying) animator.snapTo(orbit.pose.toShot())
        orbit.recenter()
        orbit.snapTo(target)
        return target
    }

    private fun stopTour() {
        manipulator.interrupt()
        touring = false
    }
}

/**
 * How a result that opens on its Photo layer is entered: from inside the place, low — where
 * whoever scanned it stood — and a turn to the side, then up and out to the framing while the
 * place grows. The first second reads as the place itself, a garden seen from its path, before
 * the whole of it is seen from above; the first touch takes the camera, as on every move.
 */
internal object ScanOpening {
    fun path(home: CameraShot): CameraPath = CameraPath.reveal(
        home = home,
        durationSeconds = SECONDS,
        turnDegrees = TURN_DEGREES,
        startElevationDegrees = minOf(START_ELEVATION, home.elevationDegrees),
        startDistanceFactor = START_DISTANCE_FACTOR,
    )

    /** Longer than the Photo layer takes to grow (`GROW_MS`): the camera lands on a whole place. */
    const val SECONDS = 5f

    /** A quarter turn short of the framing: the place turns into its best side as it rises. */
    const val TURN_DEGREES = -70f

    /** Just above the ground: an eye's height over a place framed from a few metres. */
    const val START_ELEVATION = 9f

    /** Half the framing's distance: inside the place's box, its far side filling the frame. */
    const val START_DISTANCE_FACTOR = 0.5f
}
