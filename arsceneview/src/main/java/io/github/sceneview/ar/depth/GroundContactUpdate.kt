package io.github.sceneview.ar.depth

/** Keeps retrying ground contact updates, reporting only the first failure per scene. */
internal class GroundContactUpdate(private val onFailure: (Exception) -> Unit) {

    private var hasReportedFailure = false

    fun update(block: () -> Unit) {
        try {
            block()
        } catch (exception: Exception) {
            if (!hasReportedFailure) {
                hasReportedFailure = true
                onFailure(exception)
            }
        }
    }
}
