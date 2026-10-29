package io.github.sceneview.demo.demos.internal

/**
 * The dollhouse's 3D framing ([ArDebugFraming.fit]), worked out again only when what it depends on
 * changes: the heading, the view's shape, the chrome over it. A room at rest asks for the same
 * pose every frame, and the fit projects eight corners through a few dozen allocations.
 *
 * @property bounds the room as the view stands it, `[minX, minY, minZ, maxX, maxY, maxZ]`.
 */
internal class DollhouseFraming(
    private val bounds: FloatArray,
    private val elevationDegrees: Float,
    private val verticalFovDegrees: Double,
) {
    private var azimuth = Float.NaN
    private var aspect = Float.NaN
    private var insets: FramingInsets? = null
    private var pose: OrbitPose? = null

    /** How many times the fit was worked out: one per change, not one per frame. */
    var fits = 0
        private set

    fun home(azimuthDegrees: Float, aspect: Float, insets: FramingInsets): OrbitPose {
        val known = pose
        val sameView = aspect == this.aspect && insets == this.insets
        if (known != null && sameView && azimuthDegrees == azimuth) return known
        azimuth = azimuthDegrees
        this.aspect = aspect
        this.insets = insets
        fits++
        return ArDebugFraming.fit(
            bounds = bounds,
            azimuthDegrees = azimuthDegrees,
            elevationDegrees = elevationDegrees,
            verticalFovDegrees = verticalFovDegrees,
            aspect = aspect,
            insets = insets,
        ).also { pose = it }
    }
}

/**
 * The opening of the dollhouse's 3D view, one per room shown (#4492): what stands between a view
 * that has just been composed and the first picture the user is let to see.
 *
 * `SceneView` draws a frame with the pose its manipulator holds, then calls `onFrame`. A camera
 * aimed from `onFrame` is therefore one frame late, and the first frame of a view is drawn from
 * wherever the camera was made: for an orbit camera, a default pose inside the room's cloud. So
 * the camera is put on the framing **before** a frame is drawn ([aim]), from the composition and
 * again after each frame, and the view is called shown only by [drawn]: once frames have reached
 * the surface with the camera on the room's framing, that framing unchanged in between. The chrome
 * is measured over the first frames; a framing made for bands that then move is not the one to
 * show, and the count starts again.
 */
internal class DollhouseOpening(private val steadyFrames: Int = STEADY_FRAMES) {

    /** `true` once the view may be seen. Never goes back: another room has another opening. */
    var shown = false
        private set

    private var steady = 0
    private var framedFor: OrbitPose? = null

    /**
     * Aims [orbit] at [home] for the frame to come. Until the view is shown the camera is put
     * there, not eased: an ease from a pose the view never showed would be the first thing seen.
     */
    fun aim(orbit: ArDebugOrbitCamera, home: OrbitPose) {
        if (!orbit.following) return
        // The pose the camera can hold: what [drawn] compares the frame's pose with.
        val target = ArDebugFraming.clamp(home)
        orbit.home = target
        if (!shown) orbit.snapTo(target)
    }

    /**
     * A frame reached the surface, drawn with [orbit]'s pose; [home] is the room's framing now.
     * Returns `true` once, on the frame that makes the view shown.
     */
    fun drawn(orbit: ArDebugOrbitCamera, home: OrbitPose): Boolean {
        if (shown) return false
        // A camera the user took is theirs: nothing to wait for.
        val target = ArDebugFraming.clamp(home)
        // A framing that moved starts the count again: frames each on their own framing, one
        // after the other, are a picture that jumps.
        if (target != framedFor) {
            framedFor = target
            steady = 0
        }
        val framed = !orbit.following || orbit.pose == target
        steady = if (framed) steady + 1 else 0
        shown = steady >= steadyFrames
        return shown
    }

    companion object {
        /** Frames on the framing before the view is shown: the photos are uploaded by then. */
        const val STEADY_FRAMES = 3
    }
}
