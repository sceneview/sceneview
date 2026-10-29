package io.github.sceneview.loaders

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guard `awaitModelInstance` uses so a texture load that stops progressing cannot hang it. */
class ProgressStallTest {

    private var now = 0L
    private val stall = ProgressStall(timeoutMs = 10_000, now = { now })

    @Test
    fun `a value that does not move for the timeout is a stall`() {
        assertFalse(stall.stalled(0.5f))
        now = 9_999
        assertFalse(stall.stalled(0.5f))
        now = 10_000
        assertTrue(stall.stalled(0.5f))
    }

    @Test
    fun `any progress restarts the clock`() {
        assertFalse(stall.stalled(0.1f))
        now = 9_000
        assertFalse(stall.stalled(0.2f))
        now = 18_000
        assertFalse(stall.stalled(0.2f))
        now = 19_000
        assertTrue(stall.stalled(0.2f))
    }
}
