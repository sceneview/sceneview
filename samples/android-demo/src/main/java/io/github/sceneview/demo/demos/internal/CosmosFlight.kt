package io.github.sceneview.demo.demos.internal

/**
 * The Star scene's camera between two looks: the pose it left from, and how far along the
 * [CosmosSystem.FLY_SECONDS] flight it is. The target is re-evaluated every frame, so a flight
 * to the planet lands on it wherever its orbit has carried it meanwhile.
 *
 * Allocation-free: it copies poses into buffers it owns, and eases through [rig]'s.
 */
internal class CosmosFlight(private val rig: CosmosRig) {
    /** The last pose drawn and the scene time it was drawn at: what a tap is tested against. */
    val lastPose = FloatArray(CosmosSystem.POSE_FLOATS)
    var lastTime = 0f
        private set

    /** Whether the user took the camera: the tour then leaves it alone. */
    var userSteered = false

    private val from = FloatArray(CosmosSystem.POSE_FLOATS)
    private var hasFrom = false
    private var drawn = false
    private var progress = 1f
    private var lastNanos = 0L

    /** Takes off from the pose on screen now. */
    fun start() {
        // Nothing drawn yet: there is no pose to leave from, so the first frame lands.
        hasFrom = drawn
        if (drawn) lastPose.copyInto(from)
        progress = 0f
        lastNanos = 0L
    }

    fun reset() {
        hasFrom = false
        progress = 1f
        lastNanos = 0L
        userSteered = false
    }

    fun record(pose: FloatArray, time: Float) {
        pose.copyInto(lastPose)
        lastTime = time
        drawn = true
    }

    /**
     * The pose for this frame: eased from the take-off pose toward [target], or [target] once
     * landed. [instant] lands at once: motion off, or QA captures that must show the end pose.
     */
    fun advance(nanos: Long, target: FloatArray, instant: Boolean): FloatArray {
        if (!hasFrom) return target
        if (instant) {
            progress = 1f
        } else if (lastNanos != 0L) {
            // Clamp a hitch, so a stall does not skip the flight.
            progress += ((nanos - lastNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_STEP_SECONDS) /
                CosmosSystem.FLY_SECONDS
        }
        lastNanos = nanos
        if (progress >= 1f) {
            hasFrom = false
            return target
        }
        return rig.blend(from, target, CosmosSystem.easeExpressive(progress))
    }

    private companion object {
        const val NANOS_PER_SECOND = 1e9f
        const val MAX_STEP_SECONDS = 0.1f
    }
}
