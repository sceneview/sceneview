package io.github.sceneview.demo.demos.internal

/** Cancelling a native future may race its callback; reset/dismiss also invalidate its result. */
internal class CloudRequestGeneration {
    var current: Int = 0
        private set
    private var closedThrough: Int = -1
    fun closeSession() {
        closedThrough = current
        invalidate()
    }
    fun canRelease(generation: Int): Boolean = generation > closedThrough
    fun invalidate() { current++ }
    fun accepts(generation: Int): Boolean = current == generation
}
