package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pure-JVM pins for the frame-counting and drain-ordering contract of [DeferredDestroyQueue] —
 * the Filament-free core of [EngineDestroyQueue] (sceneview/sceneview#874).
 *
 * These guarantees are what make `ImageNode.destroy()` / `ViewNode.destroy()` safe: a texture is
 * kept for the full grace period — destroyed neither too early (native SIGABRT if a material
 * instance still samples it) nor never (GPU-memory leak) — and that period is counted in frames of
 * the engine, however many renderers share it (sceneview/sceneview#4359).
 */
class DeferredDestroyQueueTest {

    @Test
    fun actionRunsExactlyOnTheGraceFrame() {
        val queue = DeferredDestroyQueue(graceFrames = 3)
        val log = mutableListOf<String>()
        queue.enqueue { log.add("a") }

        // Not before the grace period elapses.
        queue.drain() // frame 1
        queue.drain() // frame 2
        assertTrue("action ran before its grace period elapsed", log.isEmpty())

        // On the 3rd drain it runs.
        queue.drain() // frame 3
        assertEquals(listOf("a"), log)
    }

    // ── drainFrame: one engine frame per pass of the renderers, not per renderer (#4359) ────────

    @Test
    fun twoRenderersOnOneEngineKeepTheFullGracePeriod() {
        // sceneview/sceneview#4359: every SceneView has its own SceneRenderer, and each one drained
        // the engine's queue on its own frame. Two views on one engine made three drains out of a
        // frame and a half, so this resource died during frame 2.
        val queue = DeferredDestroyQueue(graceFrames = 3)
        val main = Any()
        val pip = Any()
        val log = mutableListOf<String>()
        queue.enqueue { log.add("texture") }

        repeat(2) { // engine frames 1 and 2
            queue.drainFrame(main)
            queue.drainFrame(pip)
        }
        assertTrue("two renderers on one engine shortened the grace period", log.isEmpty())

        queue.drainFrame(main) // engine frame 3 starts when a renderer comes back
        assertEquals(listOf("texture"), log)
    }

    @Test
    fun aResourceEnqueuedBetweenTwoRenderersOfAFrameStillGetsThreeFrames() {
        // Released by the second view of a frame, after the first one already counted it: the
        // three frames run from there, so it is still alive when frame 3 has fully rendered.
        val queue = DeferredDestroyQueue(graceFrames = 3)
        val main = Any()
        val pip = Any()
        val log = mutableListOf<String>()

        queue.drainFrame(main)
        queue.enqueue { log.add("texture") }
        queue.drainFrame(pip)
        repeat(2) {
            queue.drainFrame(main)
            queue.drainFrame(pip)
        }
        assertTrue("the resource did not outlive three frames of the engine", log.isEmpty())

        queue.drainFrame(main)
        assertEquals(listOf("texture"), log)
    }

    @Test
    fun threeRenderersOnOneEngineAdvanceOncePerFrame() {
        // With three views the old count was one drain per third of a frame: a grace period of
        // exactly one frame.
        val queue = DeferredDestroyQueue(graceFrames = 0)
        val renderers = List(3) { Any() }

        repeat(10) { frame ->
            val advances = renderers.count { queue.advancesOn(it) }
            assertEquals("engine frame $frame was counted $advances times", 1, advances)
        }
    }

    @Test
    fun aSingleRendererAdvancesOnEveryFrameLikeDrain() {
        // One view on its engine is the common case and must not change: every frame it reports
        // is a frame of the engine. Nothing here depends on a frame time, so a caller that hands
        // `renderFrame` a constant one cannot freeze the queue either.
        val queue = DeferredDestroyQueue(graceFrames = 3)
        val only = Any()
        val log = mutableListOf<String>()
        queue.enqueue { log.add("a") }

        queue.drainFrame(only) // frame 1
        queue.drainFrame(only) // frame 2
        assertTrue("action ran before its grace period elapsed", log.isEmpty())
        queue.drainFrame(only) // frame 3
        assertEquals(listOf("a"), log)

        val probe = DeferredDestroyQueue(graceFrames = 0)
        repeat(50) { assertTrue("a lone renderer skipped a frame", probe.advancesOn(only)) }
    }

    @Test
    fun theOrderRenderersReportInDoesNotMatter() {
        // Compose resumes the frame awaiters in the order they registered, which is not a
        // contract. Whatever the order, and even if it changes every frame, a frame counts once.
        val queue = DeferredDestroyQueue(graceFrames = 0)
        val a = Any()
        val b = Any()
        val c = Any()
        val orders = listOf(
            listOf(a, b, c), listOf(c, a, b), listOf(b, c, a),
            listOf(c, b, a), listOf(a, c, b), listOf(a, b, c),
        )

        orders.forEachIndexed { frame, order ->
            val advances = order.count { queue.advancesOn(it) }
            assertEquals("engine frame $frame was counted $advances times", 1, advances)
        }
    }

    @Test
    fun aRendererThatStopsReportingDoesNotStallTheOthers() {
        // One view paused, parked by FrameRatePolicy.OnDemand or disposed while the other keeps
        // rendering: the queue must go on at the pace of the one that is left, whether or not the
        // one that left was the one advancing the counter.
        val leaver = Any()
        val stayer = Any()
        val histories = mapOf(
            "the leaver reported first" to List(3) { listOf(leaver, stayer) }.flatten(),
            "the stayer reported first" to List(3) { listOf(stayer, leaver) }.flatten(),
            // The leaver was alone, then the stayer joined ahead of it in each frame: the leaver
            // is the one advancing the counter, and the stayer is never the one it finds there.
            "the stayer joined ahead of it" to
                listOf(leaver) + List(3) { listOf(stayer, leaver) }.flatten(),
        )

        for ((history, reports) in histories) {
            val queue = DeferredDestroyQueue(graceFrames = 0)
            reports.forEach { queue.drainFrame(it) }

            // The frame in progress when the other one left may already be counted; from the
            // second report on, every frame of the renderer that is left is a frame of the engine.
            queue.drainFrame(stayer)
            repeat(20) {
                assertTrue(
                    "the queue stalled after a renderer left ($history)",
                    queue.advancesOn(stayer)
                )
            }
        }
    }

    @Test
    fun aRendererJoiningDoesNotAdvanceTheFrameItJoins() {
        val queue = DeferredDestroyQueue(graceFrames = 0)
        val first = Any()
        val second = Any()
        repeat(3) { assertTrue(queue.advancesOn(first)) }

        // A second view starts rendering on the same engine, after the first one in each frame.
        repeat(5) { frame ->
            assertTrue("frame $frame was not counted", queue.advancesOn(first))
            assertFalse("frame $frame was counted twice", queue.advancesOn(second))
        }
    }

    @Test
    fun renderersComingAndGoingNeverCountAFrameTwiceNorStallTheQueue() {
        // The two bounds drainFrame documents, over frames where a random subset of five
        // renderers reports in a random order — views pausing, parking, being added and removed.
        // Seeded: a failure reproduces.
        val random = Random(4359)
        val queue = DeferredDestroyQueue(graceFrames = 0)
        val renderers = List(5) { Any() }
        val advancesSinceLastReport = IntArray(renderers.size) { -1 } // -1: has not reported yet

        repeat(2_000) { frame ->
            val reporting = renderers.indices.filter { random.nextInt(3) != 0 }.shuffled(random)
            var advancesThisFrame = 0
            for (index in reporting) {
                val advanced = queue.advancesOn(renderers[index])
                if (advanced) {
                    advancesThisFrame++
                    for (other in renderers.indices) {
                        if (advancesSinceLastReport[other] >= 0) advancesSinceLastReport[other]++
                    }
                }
                // Upper bound on the lag: a renderer that comes back has seen the counter advance
                // since its previous report, this report included.
                assertTrue(
                    "frame $frame: renderer $index rendered two frames, the counter did not move",
                    advancesSinceLastReport[index] != 0
                )
                advancesSinceLastReport[index] = 0
            }
            // Upper bound on the count: each renderer reported at most once in this frame.
            assertTrue(
                "frame $frame was counted $advancesThisFrame times",
                advancesThisFrame <= 1
            )
        }
    }

    @Test
    fun drainStillAdvancesUnconditionally() {
        // The public no-source drain() is for code that owns the engine's frames itself: every
        // call is a frame, whatever renderers reported around it.
        val queue = DeferredDestroyQueue(graceFrames = 0)
        val renderer = Any()
        queue.drainFrame(renderer)
        queue.drainFrame(Any()) // a second renderer joins the frame

        repeat(3) {
            var advanced = false
            queue.enqueue { advanced = true }
            queue.drain()
            assertTrue("drain() did not advance the frame counter", advanced)
        }
    }

    @Test
    fun graceZeroRunsOnTheNextDrain() {
        val queue = DeferredDestroyQueue(graceFrames = 0)
        val log = mutableListOf<String>()
        queue.enqueue { log.add("a") }
        assertEquals(1, queue.size)

        queue.drain()
        assertEquals(listOf("a"), log)
        assertEquals(0, queue.size)
    }

    @Test
    fun actionsDrainInFifoOrder() {
        val queue = DeferredDestroyQueue(graceFrames = 1)
        val log = mutableListOf<String>()
        // Enqueued on the same frame — texture-before-stream FIFO ordering must be preserved.
        queue.enqueue { log.add("texture") }
        queue.enqueue { log.add("stream") }

        queue.drain()
        assertEquals(listOf("texture", "stream"), log)
    }

    @Test
    fun itemsEnqueuedOnLaterFramesDrainLater() {
        val queue = DeferredDestroyQueue(graceFrames = 2)
        val log = mutableListOf<String>()

        queue.enqueue { log.add("first") } // due at frame 2
        queue.drain()                       // frame 1 — nothing
        queue.enqueue { log.add("second") } // due at frame 3
        queue.drain()                       // frame 2 — only "first"
        assertEquals(listOf("first"), log)
        queue.drain()                       // frame 3 — "second"
        assertEquals(listOf("first", "second"), log)
        assertEquals(0, queue.size)
    }

    @Test
    fun drainAllRunsEveryPendingActionImmediatelyInFifoOrder() {
        val queue = DeferredDestroyQueue(graceFrames = 100)
        val log = mutableListOf<String>()
        queue.enqueue { log.add("a") }
        queue.enqueue { log.add("b") }
        queue.enqueue { log.add("c") }
        assertEquals(3, queue.size)

        // Engine teardown: everything runs now, grace period ignored.
        queue.drainAll()
        assertEquals(listOf("a", "b", "c"), log)
        assertEquals(0, queue.size)
    }

    @Test
    fun highChurnDoesNotLeakAndStaysBounded() {
        // Models issue #874's acceptance scenario: 200 textures enqueued in a tight loop must all
        // be destroyed within a bounded number of frames, and never more than (grace + 1) worth of
        // a single frame's enqueues stay pending at once.
        val grace = 3
        val queue = DeferredDestroyQueue(graceFrames = grace)
        var destroyed = 0

        repeat(200) { frame ->
            queue.enqueue { destroyed++ }
            queue.drain()
            // At most `grace` previously-enqueued items can still be pending.
            assertTrue("queue grew unbounded at frame $frame", queue.size <= grace)
        }
        // Drain the tail.
        repeat(grace) { queue.drain() }
        assertEquals("every enqueued texture must eventually be destroyed", 200, destroyed)
        assertEquals(0, queue.size)
    }

    @Test
    fun enqueueAfterDrainAllRunsImmediately() {
        // sceneview/sceneview#1630: once the engine is torn down (drainAll) there is no render
        // loop left to advance drain(), so a destroy arriving late must NOT be queued onto the
        // dead engine — it must run immediately, or the resource leaks forever.
        val queue = DeferredDestroyQueue(graceFrames = 3)
        val log = mutableListOf<String>()

        queue.drainAll() // engine teardown

        queue.enqueue { log.add("late") }
        assertEquals("late destroy must run immediately, not queue", listOf("late"), log)
        assertEquals("nothing must stay pending on a torn-down queue", 0, queue.size)
    }

    @Test
    fun enqueueAfterDrainAllNeverNeedsAFrameToRun() {
        // A late enqueue must not depend on a drain() that will never come.
        val queue = DeferredDestroyQueue(graceFrames = 3)
        var destroyed = false
        queue.drainAll()

        queue.enqueue { destroyed = true }
        // No drain() call here on purpose — the action must already have run.
        assertTrue("queued action stranded after engine teardown", destroyed)
    }

    @Test
    fun negativeGraceFramesIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DeferredDestroyQueue(graceFrames = -1)
        }
    }

    /**
     * Reports one frame of [source] and tells whether the frame counter advanced, without reading
     * the queue's internals: on a queue built with `graceFrames = 0`, an action enqueued now runs
     * on the very next advance. A probe left behind by a report that did not advance runs later
     * and only sets its own, already-read flag.
     */
    private fun DeferredDestroyQueue.advancesOn(source: Any): Boolean {
        var advanced = false
        enqueue { advanced = true }
        drainFrame(source)
        return advanced
    }
}
