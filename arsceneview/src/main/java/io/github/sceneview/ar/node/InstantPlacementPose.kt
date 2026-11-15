package io.github.sceneview.ar.node

import com.google.ar.core.Pose

/** Holds a world anchor until depth is known, then blends between current-frame poses over 300 ms. */
internal class InstantPlacementPose(
    private val getHeldPose: () -> Pose,
    private val detachHeldAnchor: () -> Unit,
) : AutoCloseable {
    private var refinementStartedNs: Long? = null
    private var isClosed = false
    var isComplete = false
        private set

    val heldPose: Pose get() = getHeldPose()

    fun update(fullTracking: Boolean, anchorPose: Pose, timestampNs: Long): Pose {
        if (!fullTracking) return heldPose
        val started = refinementStartedNs ?: timestampNs.also { refinementStartedNs = it }
        val fraction = ((timestampNs - started) / REFINEMENT_DURATION_NS.toFloat()).coerceIn(0f, 1f)
        isComplete = fraction == 1f
        if (isComplete) {
            close()
            return anchorPose
        }
        return Pose.makeInterpolated(heldPose, anchorPose, fraction)
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        detachHeldAnchor()
    }

    private companion object {
        const val REFINEMENT_DURATION_NS = 300_000_000L
    }
}
