package com.google.ar.core

/** No native handle: only the ARCore facts used by the placement adapter are overridden. */
internal class FakeInstantPlacementPoint : InstantPlacementPoint(0L, null) {
    var method = TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE
    var state = TrackingState.TRACKING
    var pointPose = Pose.makeTranslation(0f, -1.3f, -1.14f)
    var anchorFactory: (Pose) -> Anchor = { FakePlacementAnchor(it) }

    override fun getTrackingMethod() = method
    override fun getTrackingState() = state
    override fun getPose() = pointPose
    override fun createAnchor(pose: Pose) = anchorFactory(pose)
}

internal class FakePlacementAnchor(private val location: Pose) : Anchor() {
    var state = TrackingState.TRACKING
    var detached = false
    override fun getPose() = location
    override fun getTrackingState() = state
    override fun detach() { detached = true }
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}
