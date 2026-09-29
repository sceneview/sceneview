package io.github.sceneview.demo.ui.viewer

/**
 * The Model Viewer's lighting as a value, so its sequences are unit-tested rather than spread
 * across composable state. The twin of iOS `ViewerLighting` (`ModelViewerDemo.swift`).
 *
 * A Museum & Space model opens under [museum] only while the stage is still on [default]; leaving
 * the shelf gives [default] back only if the app put [museum] there itself. A lighting the user
 * picks is never overridden, except the default itself: picking [default] and then opening a
 * museum scan still gives [museum], as on iOS — the value cannot tell that pick from the start.
 */
data class ViewerLighting(
    val environment: ViewerEnvironment,
    /** `true` while [environment] is the museum lighting because the app put it there. */
    val museumApplied: Boolean = false,
) {
    /** A model goes on stage. */
    fun select(isMuseumModel: Boolean, default: ViewerEnvironment, museum: ViewerEnvironment): ViewerLighting =
        when {
            isMuseumModel && environment == default -> ViewerLighting(museum, museumApplied = true)
            !isMuseumModel && museumApplied -> ViewerLighting(default)
            else -> this
        }

    /** The user picks a lighting in the sheet: it sticks across models. */
    fun pick(picked: ViewerEnvironment): ViewerLighting = ViewerLighting(picked)

    /**
     * "Reset lighting": back to the lighting the model on stage opens under — [museum] for a
     * Museum & Space scan, [default] for everything else.
     */
    fun reset(isMuseumModel: Boolean, default: ViewerEnvironment, museum: ViewerEnvironment): ViewerLighting =
        ViewerLighting(default).select(isMuseumModel, default, museum)
}
