package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What 3D Scan's capture draws over the camera, with and without raw depth. */
class ScanLivePointsTest {

    @Test
    fun `with raw depth the points wait for the dense map`() {
        assertFalse(ScanLivePoints.visible(lensReady = true, hasDenseMap = true, denseReady = false))
        assertTrue(ScanLivePoints.visible(lensReady = true, hasDenseMap = true, denseReady = true))
    }

    @Test
    fun `without raw depth the sparse points are drawn`() {
        // Codex's review of u30: a phone without raw depth showed neither points nor a model.
        assertTrue(ScanLivePoints.visible(lensReady = true, hasDenseMap = false, denseReady = false))
    }

    @Test
    fun `nothing is drawn before the lens is ready`() {
        assertFalse(ScanLivePoints.visible(lensReady = false, hasDenseMap = false, denseReady = false))
        assertFalse(ScanLivePoints.visible(lensReady = false, hasDenseMap = true, denseReady = true))
    }
}
