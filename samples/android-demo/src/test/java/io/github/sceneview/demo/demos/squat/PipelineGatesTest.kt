package io.github.sceneview.demo.demos.squat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two gates in front of the pose model, checked without a camera: the ticketed
 * inference slot (a lost result must not freeze the count, nor clear a newer request) and
 * the timestamp clock (a restarted session must not be skipped).
 */
class PipelineGatesTest {

    private val stuck = 1_000L

    @Test
    fun slotTakesOneRequestAtATime() {
        val slot = InferenceSlot(stuckAfterNs = stuck)
        assertTrue(slot.tryAcquire(ticket = 1, nowNs = 0))
        assertFalse(slot.tryAcquire(ticket = 2, nowNs = 10))
        assertTrue(slot.release(1))
        assertTrue(slot.tryAcquire(ticket = 3, nowNs = 20))
    }

    @Test
    fun stuckSlotIsReclaimedByTheNextFrame() {
        val slot = InferenceSlot(stuckAfterNs = stuck)
        assertTrue(slot.tryAcquire(ticket = 1, nowNs = 0))
        // Request 1 never comes back. Within the window frames are dropped…
        assertFalse(slot.tryAcquire(ticket = 2, nowNs = stuck))
        // …past it, the very next frame takes the slot instead of being dropped too.
        assertTrue(slot.tryAcquire(ticket = 3, nowNs = stuck + 1))
        assertTrue(slot.isHeld)
    }

    @Test
    fun lateResultOfAReclaimedRequestIsIgnored() {
        val slot = InferenceSlot(stuckAfterNs = stuck)
        slot.tryAcquire(ticket = 1, nowNs = 0)
        slot.tryAcquire(ticket = 2, nowNs = stuck + 1)
        // Request 1's result finally arrives: it must not free request 2's slot (two
        // inferences in flight on shared buffers) and must not reach the counter.
        assertFalse(slot.release(1))
        assertTrue(slot.isHeld)
        assertFalse(slot.tryAcquire(ticket = 3, nowNs = stuck + 2))
        assertTrue(slot.release(2))
        assertFalse(slot.isHeld)
    }

    @Test
    fun failureWithoutATicketFreesTheSlot() {
        val slot = InferenceSlot(stuckAfterNs = stuck)
        slot.tryAcquire(ticket = 1, nowNs = 0)
        slot.releaseAny()
        assertTrue(slot.tryAcquire(ticket = 2, nowNs = 1))
    }

    @Test
    fun redrawnFrameIsSkippedAndNewFramesKeepTheirSpacing() {
        val clock = ModelTimestamps()
        assertEquals(1_000L, clock.next(1_000))
        assertNull(clock.next(1_000))
        assertEquals(1_033L, clock.next(1_033))
        assertEquals(1_066L, clock.next(1_066))
    }

    @Test
    fun restartedSessionIsRebasedInsteadOfSkipped() {
        val clock = ModelTimestamps()
        val before = listOf(50_000L, 50_033L, 50_066L).map { clock.next(it)!! }
        // Camera flip / dataset loop: the new session's clock starts far below.
        val after = listOf(12L, 45L, 78L).map { clock.next(it) }
        // Not one frame of the new session is dropped, and the model still sees strictly
        // increasing timestamps, spaced like the camera's.
        assertTrue(after.all { it != null })
        val all = before + after.map { it!! }
        assertTrue(all.zipWithNext().all { (a, b) -> b > a })
        assertEquals(33L, after[2]!! - after[1]!!)
    }

    @Test
    fun counterKeepsCountingAcrossARestart() {
        // The end-to-end symptom of the old guard: after a restart nothing reached the
        // counter. Feed one squat per session through the clock gate.
        val clock = ModelTimestamps()
        val counter = SquatRepCounter()
        val squat = (0..10).map { 175f - 9f * it } + List(3) { 85f } + (10 downTo 0).map { 175f - 9f * it }
        fun session(startMs: Long) = squat.forEachIndexed { i, angle ->
            if (clock.next(startMs + i * 33L) != null) counter.onSample(angle)
        }
        session(startMs = 90_000L)
        session(startMs = 0L)
        assertEquals(2, counter.state.reps)
    }
}
