package io.github.sceneview

import io.github.sceneview.loaders.AsyncLoadSettleGate
import io.github.sceneview.loaders.LOAD_SETTLE_STALL_MS
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

        /** How long a wait for [fence] would keep the caller now, unbounded. */
        fun msUntilReached(fence: Int) = maxOf(0L, doneAt[fence] - now)

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

        /**
         * A janking main thread runs its posted polls late: each one at best [pollGaps] after the
         * previous one, cycling through the list.
         */
        var pollGaps = listOf(BACKEND_IDLE_POLL_MS)
        private var polls = 0

        val schedule: (Long, () -> Unit) -> Boolean = { delay, block ->
            val due = now + maxOf(delay, pollGaps[polls++ % pollGaps.size])
            at(if (due in mainThreadBusy) mainThreadBusy.last else due, block)
            true
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
        pollGaps: List<Long> = listOf(BACKEND_IDLE_POLL_MS),
        recoverAtMs: Long? = null,
        busy: (SimBackend) -> () -> Boolean,
    ): Result = checkNotNull(
        replayOrNull(
            backlogMs, nextScreenAtMs, nextScreenWorkMs, frameWorkMs, mainThreadBusy, pollGaps, recoverAtMs, busy,
        ),
    ) { "the teardown never ran" }

    /**
     * [replay], or null when the teardown is still pending after 20 s. With [probe], the registry
     * owns it, as `Engine.whenBackendIdle` does: its warning also drives the 1 s back-off.
     */
    @Suppress("LongParameterList")
    private fun replayOrNull(
        backlogMs: Long = 200,
        nextScreenAtMs: Long = 20,
        nextScreenWorkMs: Long = 3_000,
        frameWorkMs: Long = 4,
        mainThreadBusy: LongRange = LongRange.EMPTY,
        pollGaps: List<Long> = listOf(BACKEND_IDLE_POLL_MS),
        recoverAtMs: Long? = null,
        busy: ((SimBackend) -> () -> Boolean)? = null,
        probe: ((SimBackend) -> FreshFenceProbe<Int>)? = null,
    ): Result? {
        val backend = SimBackend()
        backend.mainThreadBusy = mainThreadBusy
        backend.pollGaps = pollGaps
        recoverAtMs?.let { at ->
            backend.at(at) { backend.pollGaps = listOf(BACKEND_IDLE_POLL_MS) }
        }
        var result: Result? = null
        if (backlogMs > 0) backend.enqueue(backlogMs)
        val teardowns = probe?.let { BackendIdleTeardowns(probe = it(backend)) } ?: BackendIdleTeardowns()
        teardowns.defer(
            isEngineAlive = { true },
            isBackendBusy = busy?.invoke(backend) ?: teardowns::isBackendBusy,
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
        return result
    }

    /** `Engine.whenBackendIdle` before this change: one fence, created when the scene is disposed. */
    private val disposeTimeFence: (SimBackend) -> () -> Boolean = { backend ->
        val fence = backend.createFence()
        ({ !backend.isReached(fence) })
    }

    /**
     * A renderer and then its engine (`runsLast`, as `destroyWhenBackendIdle` passes it) deferred
     * at t = 0 after rendering stops, sharing the production registry's probe and retirement policy.
     * Models backend command backlog only; native renderer and thread joins are not simulated.
     */
    private fun replayRendererThenEngine(pollEveryMs: Long): Pair<Result?, Result> {
        val backend = SimBackend()
        backend.pollGaps = listOf(pollEveryMs)
        val teardowns = BackendIdleTeardowns(probe = newProbe(backend))
        var renderer: Result? = null
        var engine: Result? = null
        teardowns.defer(
            isEngineAlive = { true },
            schedule = backend.schedule,
            teardown = { deferred -> renderer = Result(backend.now, backend.drainWaitMs(), deferred) },
            reclaimWithEngine = true,
        )
        teardowns.defer(
            isEngineAlive = { true },
            schedule = backend.schedule,
            teardown = { deferred -> engine = Result(backend.now, backend.drainWaitMs(), deferred) },
            runsLast = true,
        )
        backend.runUntil(20_000)
        return renderer to
            checkNotNull(engine) { "the engine never ran" }
    }

    private val framesWithinFreshAge = FRESH_FENCE_MAX_AGE_MS / BACKEND_IDLE_POLL_MS

    private fun newProbe(backend: SimBackend) = FreshFenceProbe(
        createFence = backend::createFence,
        isReached = backend::isReached,
        destroyFence = {},
        now = { backend.now },
    )

    private val freshFence: (SimBackend) -> () -> Boolean = { backend -> newProbe(backend)::isBusy }

    /** Every bounded wait the probe made: when, and how long it kept the main thread. */
    private class BoundedWaits {
        val waits = mutableListOf<Pair<Long, Long>>()
    }

    /** The production probe: zero-timeout checks, then the bounded confirmation after 5 s. */
    private fun boundedProbe(backend: SimBackend, log: BoundedWaits = BoundedWaits()) = FreshFenceProbe(
        createFence = backend::createFence,
        isReached = backend::isReached,
        destroyFence = {},
        now = { backend.now },
        awaitReached = { fence ->
            val until = backend.msUntilReached(fence)
            log.waits += backend.now to minOf(until, FRESH_FENCE_CONFIRM_BUDGET_MS)
            until <= FRESH_FENCE_CONFIRM_BUDGET_MS
        },
    )

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
        // ...and found it fresh before the diagnostic delay elapsed.
        assertTrue("still pending at ${after.ranAtMs} ms", after.ranAtMs < FRESH_FENCE_WARN_AFTER_MS)
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
        assertTrue("still pending at ${after.ranAtMs} ms", after.ranAtMs < FRESH_FENCE_WARN_AFTER_MS)
        assertTrue("blocked ${after.blockedMs} ms", after.blockedMs <= framesWithinFreshAge * 4L)
    }

    @Test
    fun `a fresh fence still cannot bound work queued behind it`() {
        val after = replay(
            backlogMs = 0, nextScreenAtMs = 8, nextScreenWorkMs = 3_000, busy = freshFence,
        )
        assertEquals(BACKEND_IDLE_POLL_MS, after.ranAtMs)
        assertTrue("Freshness is not a native timeout", after.blockedMs >= 2_900)
    }

    @Test
    fun `an idle backend tears down at the next poll without blocking`() {
        val after = replay(backlogMs = 0, nextScreenWorkMs = 0, busy = freshFence)

        // The fence's round trip outlasts the zero-timeout check made when it is armed.
        assertEquals(BACKEND_IDLE_POLL_MS, after.ranAtMs)
        assertEquals(0L, after.blockedMs)
    }

    @Test
    fun `slow polling waits for recovery instead of giving up`() {
        listOf(66L, 100L).forEach { gap ->
            val after = replay(
                backlogMs = 0, nextScreenWorkMs = 0,
                pollGaps = listOf(gap), recoverAtMs = 6_000, busy = freshFence,
            )
            assertTrue(after.ranAtMs in 6_000L..6_200L)
            assertEquals(0L, after.blockedMs)
        }
    }

    private fun assertNoLongBlock(case: String, after: Result) {
        assertTrue("$case: blocked ${after.blockedMs} ms", after.blockedMs <= framesWithinFreshAge * 4L)
    }

    /**
     * A 15 fps main thread: the fence armed at t = 0 is replaced at the 66 ms poll, then a 150 ms
     * pass (71 to 221 ms) composes the next screen, which queues 2 s of shaders 10 ms before it
     * ends. At 221 ms the fence armed at 66 ms is reached and 155 ms old. A window scaled on the
     * usual 66 ms gap (198 ms) took it, and the release blocked on the 2 s of shaders.
     */
    @Test
    fun `a long pass on a janking main thread does not make the fence before it fresh`() {
        val after = replay(
            backlogMs = 0,
            nextScreenAtMs = 211,
            nextScreenWorkMs = 2_000,
            mainThreadBusy = 71L..221L,
            pollGaps = listOf(66L),
            recoverAtMs = 6_000,
            busy = freshFence,
        )
        println("66 ms polls, 150 ms pass: released at ${after.ranAtMs} ms, blocked ${after.blockedMs} ms")

        assertNoLongBlock("66 ms polls, 150 ms pass", after)
    }

    /**
     * A steady 15 fps main thread, and the next screen queues 3 s of shaders at t = 100 ms,
     * between the second and third polls. The fence armed at the 66 ms poll is reached at once and
     * found 66 ms old at the next: a window of three gaps took it, with the shaders right behind.
     */
    @Test
    fun `a steady 15 fps main thread does not release into the next screen's shaders`() {
        val after = replay(
            backlogMs = 0,
            nextScreenAtMs = 100,
            nextScreenWorkMs = 3_000,
            pollGaps = listOf(66L),
            recoverAtMs = 6_000,
            busy = freshFence,
        )
        println("steady 66 ms polls, 3 s at 100 ms: released at ${after.ranAtMs} ms, blocked ${after.blockedMs} ms")

        assertNoLongBlock("steady 66 ms polls", after)
    }

    /**
     * Steady cadences from 60 fps down to 10 fps, the scene's own backlog (0 to 400 ms) times the
     * instant (0 to 500 ms) the next screen queues 2 s of shaders. Polling recovers at 6 s.
     * Every case releases on a fresh fence, except shaders queued inside the
     * fresh window itself, at most [FRESH_FENCE_MAX_AGE_MS] before the release: the renderer's
     * teardown still waits for those. That residual is counted and kept to the window.
     */
    @Test
    fun `no cadence, backlog or shader instant releases into a long block`() {
        val cadences = listOf(16L, 50L, 66L, 83L, 100L)
        val grid = cadences.associateWith { pollEveryMs ->
            listOf(0L, 50L, 100L, 200L, 400L).flatMap { backlogMs ->
                (0L..500L step 10).map { atMs ->
                    Triple("a poll every $pollEveryMs ms, $backlogMs ms backlog, shaders at $atMs ms", atMs, replay(
                        backlogMs = backlogMs,
                        nextScreenAtMs = atMs,
                        nextScreenWorkMs = 2_000,
                        pollGaps = listOf(pollEveryMs),
                        recoverAtMs = 6_000,
                        busy = freshFence,
                    ))
                }
            }
        }
        fun queuedInWindow(atMs: Long, after: Result) = after.ranAtMs - atMs in 0..FRESH_FENCE_MAX_AGE_MS
        grid.forEach { (pollEveryMs, runs) ->
            val longBlocks = runs.count { it.third.blockedMs > 500 }
            val inWindow = runs.count { (_, atMs, after) -> after.blockedMs > 500 && queuedInWindow(atMs, after) }
            val warned = runs.count { it.third.ranAtMs >= FRESH_FENCE_WARN_AFTER_MS }
            println(
                "grid, a poll every $pollEveryMs ms: ${runs.size} cases, " +
                    "$longBlocks blocked over 500 ms (${100 * longBlocks / runs.size} %, $inWindow queued in the " +
                    "fresh window), max blocked ${runs.maxOf { it.third.blockedMs }} ms, " +
                    "$warned after the warning, latest release ${runs.maxOf { it.third.ranAtMs }} ms",
            )
        }

        grid.forEach { (pollEveryMs, runs) ->
            runs.forEach { (case, atMs, after) ->
                if (!queuedInWindow(atMs, after)) assertNoLongBlock(case, after)
                assertTrue(
                    "$case: released at ${after.ranAtMs} ms",
                    after.ranAtMs < 6_000L + 2 * pollEveryMs + BACKEND_IDLE_POLL_MS,
                )
            }
        }
        // At 60 fps the freshness check itself releases, before the warning threshold.
        grid.getValue(16L).forEach { (case, _, after) ->
            assertTrue("$case: still pending at ${after.ranAtMs} ms", after.ranAtMs < FRESH_FENCE_WARN_AFTER_MS)
        }
    }

    /**
     * A cancelled async load whose progress stalls: [AsyncLoadSettleGate] only asks the probe once
     * the load made no progress for [LOAD_SETTLE_STALL_MS]. Right after, a 150 ms main-thread pass
     * queues 2 s of shaders. The former give-up, counted from probe creation, had expired by
     * then and accepted the stale fence. Elapsed time must never authorize that release.
     */
    @Test
    fun `a probe first polled after a stalled load still checks freshness`() {
        val stallEnd = LOAD_SETTLE_STALL_MS
        val settleGate: (SimBackend) -> () -> Boolean = { backend ->
            val probe = freshFence(backend)
            AsyncLoadSettleGate(
                isInFlight = { true },
                pumpedProgress = { 0.5f },
                isBackendBusy = probe,
                now = { backend.now },
            )::isBusy
        }
        val after = replay(
            backlogMs = 0,
            nextScreenAtMs = stallEnd + 150,
            nextScreenWorkMs = 2_000,
            mainThreadBusy = (stallEnd + 10)..(stallEnd + 160),
            busy = settleGate,
        )
        println("stalled load, then a long pass: released at ${after.ranAtMs} ms, blocked ${after.blockedMs} ms")

        assertTrue("released at ${after.ranAtMs} ms", after.ranAtMs >= stallEnd + 2_000)
        assertTrue("blocked ${after.blockedMs} ms", after.blockedMs <= framesWithinFreshAge * 4L)
    }

    /**
     * Ordinary jank: the main thread alternates a 16 ms frame and a 120 ms one, starting with
     * either. A fence armed before a long gap is found too old and replaced; the one armed before
     * the next short gap is taken.
     */
    @Test
    fun `a main thread alternating short and long frames tears down well before the warning threshold`() {
        val cadences = listOf(listOf(16L, 120L), listOf(120L, 16L))
        val idle = cadences.associateWith { gaps ->
            replay(backlogMs = 0, nextScreenWorkMs = 0, pollGaps = gaps, busy = freshFence)
        }
        val backlog = cadences.associateWith { gaps -> replay(pollGaps = gaps, busy = freshFence) }
        idle.forEach { (gaps, after) ->
            println("idle backend, polls $gaps ms apart: released at ${after.ranAtMs} ms")
        }
        backlog.forEach { (gaps, after) ->
            println(
                "3.2 s backlog, polls $gaps ms apart: released at ${after.ranAtMs} ms, " +
                    "blocked ${after.blockedMs} ms",
            )
        }

        idle.forEach { (gaps, after) ->
            assertTrue("polls $gaps: released at ${after.ranAtMs} ms", after.ranAtMs <= 3 * 136L)
            assertTrue("polls $gaps: blocked ${after.blockedMs} ms", after.blockedMs <= 4L)
        }
        backlog.forEach { (gaps, after) ->
            assertTrue("polls $gaps: released after ${after.ranAtMs} ms", after.ranAtMs >= 3_200)
            assertTrue("polls $gaps: still pending at ${after.ranAtMs} ms", after.ranAtMs < FRESH_FENCE_WARN_AFTER_MS)
            assertTrue("polls $gaps: blocked ${after.blockedMs} ms", after.blockedMs <= framesWithinFreshAge * 4L)
        }
    }

    /**
     * A single long main-thread pass, just after the dispose or after a few regular polls: the
     * next screen is composed during it and queues 2 s of shaders near its end. The fence armed
     * before the pass is reached when the next poll finally runs — one gap old, and that gap is the
     * pass itself. A window scaled on that gap took it as fresh; the fixed 48 ms window does not,
     * so the fence is replaced and the release waits for the shaders.
     */
    @Test
    fun `a single long main-thread pass does not make its own fence fresh`() {
        // (pass start, backlog): from the dispose on an idle backend, or after three regular
        // polls on a backend still busy with the scene's last 50 ms.
        val starts = listOf(1L to 0L, 41L to 50L)
        val runs = starts.flatMap { (start, backlogMs) ->
            listOf(100L, 150L, 208L, 240L).map { passMs ->
                "a ${passMs} ms pass from $start ms" to replay(
                    backlogMs = backlogMs,
                    nextScreenAtMs = start + passMs - 10,
                    nextScreenWorkMs = 2_000,
                    mainThreadBusy = start..start + passMs,
                    busy = freshFence,
                )
            }
        }
        runs.forEach { (case, after) ->
            println("$case: released at ${after.ranAtMs} ms, then blocked ${after.blockedMs} ms")
        }

        runs.forEach { (case, after) ->
            assertTrue("$case: blocked ${after.blockedMs} ms", after.blockedMs <= framesWithinFreshAge * 4L)
            assertTrue("$case: still pending at ${after.ranAtMs} ms", after.ranAtMs < FRESH_FENCE_WARN_AFTER_MS)
        }
    }

    /**
     * #4969: a steady main thread slower than the 48 ms window, on a shared engine that stays
     * alive. Zero-timeout checks never see a fresh fence, so the release waited for Engine.destroy;
     * the bounded confirmation finds the idle backend shortly after the warning.
     */
    @Test
    fun `a main thread too slow for freshness still releases once the backend is idle`() {
        for (gap in listOf(66L, 100L, 300L)) {
            val zeroTimeout = replayOrNull(
                backlogMs = 0, nextScreenWorkMs = 0, pollGaps = listOf(gap), busy = freshFence,
            )
            assertEquals("a poll every $gap ms, zero-timeout only", null, zeroTimeout)

            val after = checkNotNull(
                replayOrNull(backlogMs = 0, nextScreenWorkMs = 0, pollGaps = listOf(gap), probe = { boundedProbe(it) }),
            ) { "a poll every $gap ms: never released" }
            println("a poll every $gap ms, bounded check: released at ${after.ranAtMs} ms, blocked ${after.blockedMs} ms")
            assertTrue("released at ${after.ranAtMs} ms", after.ranAtMs >= FRESH_FENCE_WARN_AFTER_MS)
            assertTrue(
                "released at ${after.ranAtMs} ms",
                after.ranAtMs <= FRESH_FENCE_WARN_AFTER_MS + 2 * (BACKEND_IDLE_BACKOFF_MS + gap),
            )
            assertTrue("blocked ${after.blockedMs} ms", after.blockedMs <= FRESH_FENCE_CONFIRM_BUDGET_MS)
        }
    }

    /**
     * The bounded confirmation must not become the old give-up: the next screen queues 2 s of
     * shaders at any instant around the warning, on a main thread too slow for freshness.
     * The release waits for them and never blocks longer than the confirmation budget.
     */
    @Test
    fun `the bounded confirmation never releases into the next screen's shaders`() {
        for (gap in listOf(66L, 100L, 300L)) {
            for (atMs in 3_000L..9_000L step 50) {
                val case = "a poll every $gap ms, shaders at $atMs ms"
                val after = checkNotNull(
                    replayOrNull(
                        backlogMs = 0,
                        nextScreenAtMs = atMs,
                        nextScreenWorkMs = 2_000,
                        pollGaps = listOf(gap),
                        probe = { boundedProbe(it) },
                    ),
                ) { "$case: never released" }
                assertTrue("$case: blocked ${after.blockedMs} ms", after.blockedMs <= FRESH_FENCE_CONFIRM_BUDGET_MS)
                if (after.ranAtMs >= atMs) {
                    assertTrue("$case: released at ${after.ranAtMs} ms", after.ranAtMs >= atMs + 2_000)
                }
            }
        }
    }

    /** A backend that never catches up keeps the release pending, and is never waited for. */
    @Test
    fun `a backend that never idles is neither released nor waited for`() {
        for (gap in listOf(16L, 66L, 300L)) {
            val log = BoundedWaits()
            val after = replayOrNull(
                backlogMs = 60_000,
                nextScreenWorkMs = 0,
                pollGaps = listOf(gap),
                probe = { boundedProbe(it, log) },
            )
            assertEquals("a poll every $gap ms: released behind a busy backend", null, after)
            // An unreached fence is kept: only a stale or errored one is replaced and confirmed.
            assertEquals("a poll every $gap ms", 0, log.waits.size)
        }
    }

    /**
     * The probe's only new main-thread cost: bounded waits, none before the warning, at most
     * [FRESH_FENCE_CONFIRM_BUDGET_MS] each and about two a second. Here the backend spends 15 ms
     * of every 16 on frames, so most confirmations time out before one lands between two frames.
     */
    @Test
    fun `bounded waits start after the warning and stay within budget`() {
        for (gap in listOf(66L, 100L, 300L)) {
            val log = BoundedWaits()
            val after = checkNotNull(
                replayOrNull(
                    backlogMs = 0,
                    nextScreenWorkMs = 0,
                    frameWorkMs = 15,
                    pollGaps = listOf(gap),
                    probe = { boundedProbe(it, log) },
                ),
            ) { "a poll every $gap ms: never released" }
            val seconds = 1 + (after.ranAtMs - FRESH_FENCE_WARN_AFTER_MS) / 1_000
            println(
                "a poll every $gap ms, 15 ms frames: ${log.waits.size} bounded waits " +
                    "(${log.waits.sumOf { it.second }} ms), released at ${after.ranAtMs} ms, blocked ${after.blockedMs} ms",
            )
            assertTrue(log.waits.all { (atMs, _) -> atMs >= FRESH_FENCE_WARN_AFTER_MS })
            assertTrue(log.waits.all { (_, waitedMs) -> waitedMs <= FRESH_FENCE_CONFIRM_BUDGET_MS })
            assertTrue("${log.waits.size} waits in $seconds s", log.waits.size <= 2 * seconds + 1)
            assertTrue("blocked ${after.blockedMs} ms", after.blockedMs <= FRESH_FENCE_CONFIRM_BUDGET_MS)
        }
    }

    @Test
    fun `a retiring engine reclaims its renderer without an individual native destroy`() {
        for (gap in listOf(66L, 300L)) {
            val (renderer, engine) = replayRendererThenEngine(pollEveryMs = gap)
            assertEquals(null, renderer)
            assertEquals(gap, engine.ranAtMs)
            assertEquals(0L, engine.blockedMs)
        }
    }

    @Test
    fun `a probe that cannot create a fence keeps the teardown pending`() {
        val probe = FreshFenceProbe<Int>(
            createFence = { null },
            isReached = { true },
            destroyFence = {},
            now = { 0L },
        )

        assertTrue(probe.isBusy())
    }

    @Test
    fun `a fence found reached long after it was armed is replaced, even at the first poll`() {
        // A caller may skip polls while something else holds its teardown (a model load still
        // advancing): the count of polls says nothing about how much the backend took on since.
        var clock = 0L
        var next = 0
        val armed = mutableListOf<Int>()
        val armedAt = mutableMapOf<Int, Long>()
        val destroyed = mutableListOf<Int>()
        val probe = FreshFenceProbe(
            createFence = { next++.also { armed += it; armedAt[it] = clock } },
            isReached = { clock > armedAt.getValue(it) },
            destroyFence = { destroyed += it },
            now = { clock },
        )
        assertTrue(probe.isBusy())
        clock = 3_000
        assertTrue("stale fence must not count", probe.isBusy())
        assertEquals(listOf(0, 1), armed)
        assertEquals("the stale fence is destroyed", listOf(0), destroyed)
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
