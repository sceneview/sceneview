package io.github.sceneview.ar.node

import com.google.ar.core.Pose

/** Holds the drop pose until depth is known, then blends to the live anchor over 300 ms. */
internal class InstantPlacementPose(private val dropPose: Pose) {
    private var refinementStartedNs: Long? = null
    var isComplete = false
        private set

    fun update(fullTracking: Boolean, anchorPose: Pose, timestampNs: Long): Pose {
        if (!fullTracking) return dropPose
        val started = refinementStartedNs ?: timestampNs.also { refinementStartedNs = it }
        val fraction = ((timestampNs - started) / REFINEMENT_DURATION_NS.toFloat()).coerceIn(0f, 1f)
        isComplete = fraction == 1f
        return Pose.makeInterpolated(dropPose, anchorPose, fraction)
    }

    private companion object {
        const val REFINEMENT_DURATION_NS = 300_000_000L
    }
}
