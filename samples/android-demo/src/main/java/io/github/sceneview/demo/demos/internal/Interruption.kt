package io.github.sceneview.demo.demos.internal

/**
 * Ends a long computation whose thread was interrupted. A room's measure nobody waits for any
 * more is interrupted (`runInterruptible`): its loops call this so it gives the one measuring
 * thread back instead of running to its end. Clears the interrupt, as [Thread.interrupted] does.
 */
internal fun stopIfInterrupted() {
    if (Thread.interrupted()) throw InterruptedException("room measure dropped")
}
