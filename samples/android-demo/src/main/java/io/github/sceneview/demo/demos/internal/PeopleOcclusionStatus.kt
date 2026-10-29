package io.github.sceneview.demo.demos.internal

/**
 * What People Occlusion (`ar-people-occlusion`, #4649) is doing right now, said over the camera
 * so the screen never silently draws the helmet over a person it did not see.
 */
enum class PeopleOcclusionStatus {
    /** The user switched occlusion off: the helmet draws over everything. */
    Off,

    /** Occlusion on, ARCore has not produced its first semantic frame yet. */
    Starting,

    /** Occlusion on and running, but no person is in view. */
    Searching,

    /** A person is in view, and hides the helmet where they stand in front of it. */
    Detected,
}

object PeopleOcclusion {
    /**
     * Share of the semantic frame labelled `PERSON` above which a person counts as in view.
     * One percent of a 256x144 raster is ~370 pixels, a person a few metres away; below it the
     * label is the classifier's speckle on an empty scene.
     */
    const val PERSON_FRACTION_THRESHOLD = 0.01f

    /**
     * [personFraction] is ARCore's share of `PERSON` pixels in the last frame, or `null` when
     * semantics are not available yet.
     */
    fun status(enabled: Boolean, personFraction: Float?): PeopleOcclusionStatus = when {
        !enabled -> PeopleOcclusionStatus.Off
        personFraction == null -> PeopleOcclusionStatus.Starting
        personFraction >= PERSON_FRACTION_THRESHOLD -> PeopleOcclusionStatus.Detected
        else -> PeopleOcclusionStatus.Searching
    }
}
