package io.github.sceneview.demo.demos.internal

/** The displayed center-ray result, replaced by camera frames and consumed only by Add point. */
internal class MeasureCandidateControl<T> {
    var current: T? = null
        private set

    fun update(candidate: T?) { current = candidate }

    fun <R> consume(commit: (T) -> R?): R? {
        val displayed = current ?: return null
        // A double activation cannot reuse a candidate. The next camera frame rearms it.
        current = null
        return commit(displayed)
    }
}
