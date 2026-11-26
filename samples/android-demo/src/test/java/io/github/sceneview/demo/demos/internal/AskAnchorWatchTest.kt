package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.AskAnchorWatch.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskAnchorWatchTest {
    @Test fun `tracked all along does nothing`() {
        val watch = AskAnchorWatch(patienceFrames = 3)
        repeat(10) { assertEquals(Action.None, watch.step(cameraTracking = true, anchorTracking = true)) }
        assertFalse(watch.holding)
    }

    @Test fun `lost then found with the anchor back holds once and releases once`() {
        val watch = AskAnchorWatch(patienceFrames = 3)
        assertEquals(Action.Hold, watch.step(cameraTracking = false, anchorTracking = false))
        repeat(20) { assertEquals(Action.None, watch.step(cameraTracking = false, anchorTracking = false)) }
        assertTrue(watch.holding)
        assertEquals(Action.Release, watch.step(cameraTracking = true, anchorTracking = true))
        assertFalse(watch.holding)
        assertEquals(Action.None, watch.step(cameraTracking = true, anchorTracking = true))
    }

    @Test fun `lost then found with the anchor stuck paused re-anchors and stays held until tracked`() {
        val watch = AskAnchorWatch(patienceFrames = 3)
        assertEquals(Action.Hold, watch.step(cameraTracking = false, anchorTracking = false))
        // Camera back, old anchor still paused: wait, then ask for a new anchor.
        assertEquals(Action.None, watch.step(cameraTracking = true, anchorTracking = false))
        assertEquals(Action.None, watch.step(cameraTracking = true, anchorTracking = false))
        assertEquals(Action.Reanchor, watch.step(cameraTracking = true, anchorTracking = false))
        assertTrue("still held while the new anchor is not tracked", watch.holding)
        // The new anchor is tracked: the answer goes back on it.
        assertEquals(Action.Release, watch.step(cameraTracking = true, anchorTracking = true))
        assertFalse(watch.holding)
    }

    @Test fun `a failed re-anchor asks again after the same patience`() {
        val watch = AskAnchorWatch(patienceFrames = 2)
        watch.step(cameraTracking = false, anchorTracking = false)
        assertEquals(Action.None, watch.step(cameraTracking = true, anchorTracking = false))
        assertEquals(Action.Reanchor, watch.step(cameraTracking = true, anchorTracking = false))
        assertEquals(Action.None, watch.step(cameraTracking = true, anchorTracking = false))
        assertEquals(Action.Reanchor, watch.step(cameraTracking = true, anchorTracking = false))
    }

    @Test fun `losing tracking again mid re-anchor wait restarts the patience`() {
        val watch = AskAnchorWatch(patienceFrames = 3)
        watch.step(cameraTracking = false, anchorTracking = false)
        watch.step(cameraTracking = true, anchorTracking = false)
        watch.step(cameraTracking = true, anchorTracking = false)
        assertEquals(Action.None, watch.step(cameraTracking = false, anchorTracking = false))
        assertEquals(Action.None, watch.step(cameraTracking = true, anchorTracking = false))
        assertEquals(Action.None, watch.step(cameraTracking = true, anchorTracking = false))
        assertEquals(Action.Reanchor, watch.step(cameraTracking = true, anchorTracking = false))
    }

    @Test fun `reset forgets a hold`() {
        val watch = AskAnchorWatch(patienceFrames = 3)
        watch.step(cameraTracking = false, anchorTracking = false)
        watch.reset()
        assertFalse(watch.holding)
        assertEquals(Action.None, watch.step(cameraTracking = true, anchorTracking = true))
    }
}
