package io.github.sceneview.demo

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loading cover asks the backend "have you executed those frames?" by polling a fence,
 * never by blocking the main thread on it (#3799). Before, `FirstFrameState` and the model
 * viewer called `Engine.flushAndWait()` from `onFrame`. On a software GL that held the main
 * thread for the whole material-link time, and a BACK press queued behind it raised an
 * input-dispatch ANR.
 */
class BackendDrainWaitTest {

    /** A fence stand-in: busy until [drained] flips, counts its checks and releases. */
    private class FakeProbe(var drained: Boolean = false) : DrainProbe {
        var checks = 0
        var releases = 0
        override fun isDrained(): Boolean {
            checks++
            return drained
        }
        override fun release() {
            releases++
        }
    }

    /** Collects scheduled polls instead of posting them; [runNext] plays one back. */
    private class ManualScheduler {
        val pending = ArrayDeque<Pair<Long, () -> Unit>>()
        var cancelled = 0
        fun schedule(delayMs: Long, block: () -> Unit) {
            pending.addLast(delayMs to block)
        }
        fun runNext() = pending.removeFirst().second()
    }

    private fun drainWait(probe: FakeProbe?, scheduler: ManualScheduler) = BackendDrainWait(
        newProbe = { probe },
        schedule = scheduler::schedule,
        cancelScheduled = { scheduler.cancelled++; scheduler.pending.clear() },
    )

    @Test
    fun `an idle backend answers on the spot`() {
        val probe = FakeProbe(drained = true)
        val scheduler = ManualScheduler()
        var drained = 0

        drainWait(probe, scheduler).start { drained++ }

        assertEquals(1, drained)
        assertEquals("the fence is released as soon as it has answered", 1, probe.releases)
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test
    fun `a busy backend is polled one frame apart, never awaited`() {
        val probe = FakeProbe(drained = false)
        val scheduler = ManualScheduler()
        var drained = 0
        val wait = drainWait(probe, scheduler)

        wait.start { drained++ }

        // start() returned while the backend was still busy: nothing blocked, a poll is queued.
        assertEquals(0, drained)
        assertTrue(wait.isWaiting)
        assertEquals(DRAIN_POLL_MS, scheduler.pending.single().first)

        scheduler.runNext() // still busy → re-queued
        assertEquals(0, drained)
        assertEquals(1, scheduler.pending.size)

        probe.drained = true
        scheduler.runNext()
        assertEquals(1, drained)
        assertFalse(wait.isWaiting)
        assertEquals(1, probe.releases)
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test
    fun `start is one-shot, so onFrame can call it every frame`() {
        val probe = FakeProbe(drained = false)
        val scheduler = ManualScheduler()
        var probesMade = 0
        var drained = 0
        val wait = BackendDrainWait(
            newProbe = { probesMade++; probe },
            schedule = scheduler::schedule,
        )

        repeat(5) { wait.start { drained++ } }
        probe.drained = true
        scheduler.runNext()

        assertEquals("one fence for the whole wait", 1, probesMade)
        assertEquals(1, drained)
        assertEquals(1, probe.releases)
    }

    @Test
    fun `leaving the composition releases the fence and drops the callback`() {
        // The owner is remembered after `rememberEngine()`, so it is forgotten first: the fence
        // must be gone before the engine is destroyed, and a late poll must not fire.
        val probe = FakeProbe(drained = false)
        val scheduler = ManualScheduler()
        var drained = 0
        val wait = drainWait(probe, scheduler)
        wait.start { drained++ }
        val latePoll = scheduler.pending.first().second

        wait.onForgotten()
        latePoll() // a poll that was already on the looper when the demo left
        probe.drained = true
        wait.start { drained++ }

        assertEquals(0, drained)
        assertEquals("released exactly once", 1, probe.releases)
        assertEquals(1, scheduler.cancelled)
        assertFalse(wait.isWaiting)
    }

    @Test
    fun `an engine that cannot make a fence does not hold the cover up`() {
        var drained = 0
        drainWait(null, ManualScheduler()).start { drained++ }
        assertEquals(1, drained)
    }

    @Test
    fun `the first-frame cover lifts only once the backend has drained`() {
        val probe = FakeProbe(drained = false)
        val scheduler = ManualScheduler()
        val rendered = mutableStateOf(false)
        val state = FirstFrameState(rendered, drainWait(probe, scheduler))
        val base = 5_000L * 1_000_000L

        state.onFrame(base)
        state.onFrame(base + 16_000_000L)
        assertFalse("two frames submitted, backend still linking: cover stays", rendered.value)

        // The scene parks: no more frames. The poll alone must finish the job (#3108).
        probe.drained = true
        scheduler.runNext()
        assertTrue(rendered.value)
    }
}
