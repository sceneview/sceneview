package io.github.sceneview.demo.demos.internal

/**
 * Whether 3D Scan's capture draws the points it has taken over the camera.
 *
 * With raw depth the scan fuses a dense map, and its points wait for that map's first snapshot:
 * ARCore's sparse feature points are never drawn in its place. Without raw depth there is no
 * dense map and never will be: the sparse points, in the colours the camera saw, are the only
 * sign that the scan takes anything, and they are drawn as soon as the lens is ready (Codex's
 * review of u30: such a phone showed neither points nor a model).
 */
object ScanLivePoints {
    fun visible(lensReady: Boolean, hasDenseMap: Boolean, denseReady: Boolean): Boolean =
        lensReady && (!hasDenseMap || denseReady)
}
