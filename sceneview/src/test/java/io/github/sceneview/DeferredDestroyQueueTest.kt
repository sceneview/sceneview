package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM pins for the frame-counting and drain-ordering contract of [DeferredDestroyQueue] —
 * the Filament-free core of [EngineDestroyQueue] (sceneview/sceneview#874).
 *
 * These guarantees are what make `ImageNode.destroy()` / `ViewNode.destroy()` safe:
 * a texture must outlive its `MaterialInstance` by exactly the grace period — destroyed neither
 * too early (native SIGABRT) nor never (GPU-memory leak).
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
}
