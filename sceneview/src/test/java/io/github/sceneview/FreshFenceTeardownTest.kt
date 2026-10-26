package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue

/**
 * The ANR left after #3799 deferred `Engine.destroyRenderer`: field reports show the main thread
 * parked in `FRenderer::terminate` from a `Handler` callback, which is the deferred teardown
 * itself.
 *
 * `destroyRenderer` waits for *everything queued when it runs*, not for what was queued when the
 * scene was disposed. On a shared engine the next screen queues its shader compilations in between,
 * so a teardown released as soon as the dispose-time fence is reached still blocks on them.
 *
 * This replays that sequence on a simulated backend: one FIFO command queue, a virtual clock, the
 * real [BackendIdleTeardowns] polling every [BACKEND_IDLE_POLL_MS]. "Blocked" is how long the
 * release's own wait-for-everything-queued would keep the main thread once it runs.
 *
 * A fence is a command like any other: even on an idle backend it is reached one round trip after
 * it was created ([FENCE_ROUND_TRIP_MS]), not by the zero-timeout check made right after.
 */
class FreshFenceTeardownTest {

    private companion object {
        const val FENCE_ROUND_TRIP_MS = 1L
    }

    /** A single-threaded backend executing queued commands in order, against a virtual clock. */
    private class SimBackend {
        var now = 0L
        private var freeAt = 0L
        private val doneAt = mutableListOf<Long>()
        private class Event(val atMs: Long, val order: Long, val block: () -> Unit)

        // Same-time events run in the order they were scheduled.
        private val events = PriorityQueue<Event>(compareBy<Event> { it.atMs }.thenBy { it.order })
        private var sequence = 0L

        fun enqueue(durationMs: Long): Int {
            freeAt = maxOf(now, freeAt) + durationMs
            doneAt += freeAt
            return doneAt.lastIndex
        }

        fun createFence(): Int = enqueue(FENCE_ROUND_TRIP_MS)
        fun isReached(fence: Int) = doneAt[fence] <= now

        /** How long a wait for everything queued so far would block the caller now. */
        fun drainWaitMs() = maxOf(0L, freeAt - now)

        fun at(timeMs: Long, block: () -> Unit) {
            events.add(Event(timeMs, sequence++, block))
        }

        fun runUntil(endMs: Long) {
            while (events.isNotEmpty() && events.peek()!!.atMs <= endMs) {
                val next = events.poll()!!
                now = next.atMs
                next.block()
            }
            now = endMs
        }

        /** While the main thread is busy, nothing it posts runs: polls wait, frames are dropped. */
        var mainThreadBusy: LongRange = LongRange.EMPTY

        val schedule: (Long, () -> Unit) -> Unit = { delay, block ->
            val due = now + delay
            at(if (due in mainThreadBusy) mainThreadBusy.last else due, block)
        }
    }

    private class Result(val ranAtMs: Long, val blockedMs: Long, val deferred: Boolean)

    /**
     * Disposes a scene at t = 0 while [backlogMs] of its own work is still queued; from
     * [nextScreenAtMs] the next screen on the same engine queues [nextScreenWorkMs] of shader
     * compilation, then renders a frame of [frameWorkMs] every 16 ms.
     */
    private fun replay(
        backlogMs: Long = 200,
        nextScreenAtMs: Long = 20,
        nextScreenWorkMs: Long = 3_000,
        frameWorkMs: Long = 4,
        mainThreadBusy: LongRange = LongRange.EMPTY,
        busy: (SimBackend) -> () -> Boolean,
    ): Result {
        val backend = SimBackend()
        backend.mainThreadBusy = mainThreadBusy
        var result: Result? = null
        if (backlogMs > 0) backend.enqueue(backlogMs)
        BackendIdleTeardowns().defer(
            isEngineAlive = { true },
            isBackendBusy = busy(backend),
            schedule = backend.schedule,
            teardown = { deferred -> result = Result(backend.now, backend.drainWaitMs(), deferred) },
        )
        if (nextScreenWorkMs > 0) backend.at(nextScreenAtMs) { backend.enqueue(nextScreenWorkMs) }
        // Frames land between polls (polls are on multiples of 16 ms from t = 0).
        var frame = 8L
        while (frame < 20_000) {
            if (frame !in mainThreadBusy) backend.at(frame) { backend.enqueue(frameWorkMs) }
            frame += 16
        }
        backend.runUntil(20_000)
        return checkNotNull(result) { "the teardown never ran" }
    }

    /** `Engine.whenBackendIdle` before this change: one fence, created when the scene is disposed. */
    private val disposeTimeFence: (SimBackend) -> () -> Boolean = { backend ->
        val fence = backend.createFence()
        ({ !backend.isReached(fence) })
    }

    private val framesWithinFreshAge = FRESH_FENCE_MAX_AGE_MS / BACKEND_IDLE_POLL_MS

    private val freshFence: (SimBackend) -> () -> Boolean = { backend ->
        FreshFenceProbe(
            createFence = backend::createFence,
            isReached = backend::isReached,
            destroyFence = {},
            now = { backend.now },
        )::isBusy
    }

    @Test
    fun `a dispose-time fence releases into the next screen's backlog`() {
        val before = replay(busy = disposeTimeFence)
        println("dispose-time fence: released at ${before.ranAtMs} ms, then blocked ${before.blockedMs} ms")

        // Released at the first poll after its own 200 ms backlog, then blocks for the next
        // screen's 3 s of shader compilation: an ANR from the Handler callback.
        assertTrue("released after ${before.ranAtMs} ms", before.ranAtMs in 200L..240L)
        assertTrue("blocked ${before.blockedMs} ms", before.blockedMs >= 2_900)
    }

    @Test
    fun `a fresh fence waits for the backlog instead of blocking on it`() {
        val after = replay(busy = freshFence)
        println("fresh fence: released at ${after.ranAtMs} ms, then blocked ${after.blockedMs} ms")

        assertTrue("deferred", after.deferred)
        // Released once the backend caught up with the next screen's compilation...
        assertTrue("released after ${after.ranAtMs} ms", after.ranAtMs >= 3_200)
        // ...so the release's own wait only covers what was queued within the freshness window.
        assertTrue("blocked ${after.blockedMs} ms", after.blockedMs <= framesWithinFreshAge * 4L)
    }

    /**
     * The poll after the fence was armed comes 3 s late: the main thread spent them composing the
     * next screen, which queued 1 s of shaders near the end of that pass. The fence, queued before
     * them, is reached by then, at the very next poll — one poll after it was armed, but 3 s old.
     */
    @Test
    fun `a fence found reached seconds after it was armed is not fresh`() {
        val stalled = 8L..3_008L
        val before = replay(
            nextScreenAtMs = 2_900, nextScreenWorkMs = 1_000, mainThreadBusy = stalled, busy = disposeTimeFence,
        )
        val after = replay(
            nextScreenAtMs = 2_900, nextScreenWorkMs = 1_000, mainThreadBusy = stalled, busy = freshFence,
        )
        println("busy main thread, dispose-time fence: ${before.ranAtMs} ms, blocked ${before.blockedMs} ms")
        println("busy main thread, fresh fence: ${after.ranAtMs} ms, blocked ${after.blockedMs} ms")

        assertTrue("blocked ${before.blockedMs} ms", before.blockedMs >= 800)
        // Released once the backend caught up with the next screen's 1 s of shaders.
        assertTrue("released after ${after.ranAtMs} ms", after.ranAtMs >= 3_900)
        assertTrue("blocked ${after.blockedMs} ms", after.blockedMs <= framesWithinFreshAge * 4L)
    }

    @Test
    fun `an idle backend tears down at the next poll without blocking`() {
        val after = replay(backlogMs = 0, nextScreenWorkMs = 0, busy = freshFence)

        // The fence's round trip outlasts the zero-timeout check made when it is armed.
        assertEquals(BACKEND_IDLE_POLL_MS, after.ranAtMs)
        assertEquals(0L, after.blockedMs)
    }

    @Test
    fun `a backend that never catches up gets the old behaviour after the give-up delay`() {
        // Every frame takes 20 ms of backend time but one is queued every 16 ms: the backlog
        // only grows, so no fence is ever reached within a few polls.
        val after = replay(nextScreenWorkMs = 0, frameWorkMs = 20, busy = freshFence)

        assertTrue("released after ${after.ranAtMs} ms", after.ranAtMs >= FRESH_FENCE_GIVE_UP_MS)
        assertTrue("released after ${after.ranAtMs} ms", after.ranAtMs <= FRESH_FENCE_GIVE_UP_MS * 3)
    }

    @Test
    fun `a probe that cannot create a fence does not hold the teardown`() {
        val probe = FreshFenceProbe<Int>(
            createFence = { null },
            isReached = { true },
            destroyFence = {},
            now = { 0L },
        )

        assertEquals(false, probe.isBusy())
    }

    @Test
    fun `a fence found reached long after it was armed is replaced, even at the first poll`() {
        // A caller may skip polls while something else holds its teardown (a model load still
        // advancing): the count of polls says nothing about how much the backend took on since.
        var clock = 0L
        var next = 0
        val armed = mutableListOf<Int>()
        val probe = FreshFenceProbe(
            createFence = { next++.also { armed += it } },
            isReached = { clock >= 1 },
            destroyFence = {},
            now = { clock },
        )
        assertTrue(probe.isBusy())
        clock = 3_000
        assertTrue("stale fence must not count", probe.isBusy())
        assertEquals(listOf(0, 1), armed)
        clock += BACKEND_IDLE_POLL_MS
        assertEquals(false, probe.isBusy())
    }

    @Test
    fun `release destroys the fence still held, once`() {
        val destroyed = mutableListOf<Int>()
        var next = 0
        val probe = FreshFenceProbe(
            createFence = { next++ },
            isReached = { false },
            destroyFence = { destroyed += it },
            now = { 0L },
        )
        probe.isBusy()
        probe.release()
        probe.release()

        assertEquals(listOf(0), destroyed)
    }
}
