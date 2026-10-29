package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a finished one-finger drag does to an automatic placement (#4731): a placed room drifted
 * away from where it was set down, a few centimetres per pinch, because the first finger's slide
 * before the second landed was committed as a move.
 */
class DragEndTest {

    @Test
    fun `a drag the finger lifts from is committed`() {
        assertEquals(DragEnd.COMMIT, DragEnd.of(began = true, pointerCount = 1, elapsedMs = 40, travelM = 0.01f))
        assertEquals(DragEnd.COMMIT, DragEnd.of(began = true, pointerCount = 1, elapsedMs = 900, travelM = 0.3f))
    }

    @Test
    fun `a slip a second finger interrupts goes back on its anchor`() {
        assertEquals(DragEnd.REVERT, DragEnd.of(began = true, pointerCount = 2, elapsedMs = 60, travelM = 0.02f))
        assertEquals(
            DragEnd.REVERT,
            DragEnd.of(true, 3, DragEnd.SLIP_MAX_MS, DragEnd.SLIP_MAX_M),
        )
    }

    @Test
    fun `a real move followed by a two-finger twist is kept`() {
        // 30 cm over a second, then a twist: a move, not a slip.
        assertEquals(DragEnd.COMMIT, DragEnd.of(began = true, pointerCount = 2, elapsedMs = 1000, travelM = 0.3f))
        // Long, even if it ends where it started.
        assertEquals(DragEnd.COMMIT, DragEnd.of(true, 2, DragEnd.SLIP_MAX_MS + 1, 0.01f))
        // Quick but far.
        assertEquals(DragEnd.COMMIT, DragEnd.of(true, 2, 100, DragEnd.SLIP_MAX_M + 0.01f))
    }

    @Test
    fun `a drag that never began neither moves the room nor closes an adjustment`() {
        // The detector ends a drag still being detected when a second finger lands: that end
        // must not close the adjustment the pinch is opening.
        assertEquals(DragEnd.NONE, DragEnd.of(began = false, pointerCount = 2, elapsedMs = 0, travelM = 0f))
        assertEquals(DragEnd.NONE, DragEnd.of(began = false, pointerCount = 1, elapsedMs = 0, travelM = 0f))
    }
}
