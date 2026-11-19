package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Virtual-time coverage of engine ownership and the single scheduled retry. */
class BackendTeardownPolicyTest {
    private class Clock {
        var time = 0L
        var minimumGap = 66L
        val callbacks = ArrayDeque<Pair<Long, () -> Unit>>()
        val schedule: (Long, () -> Unit) -> Boolean = { delay, callback ->
            callbacks.addLast(delay to callback)
            true
        }

        fun poll() {
            val (delay, callback) = callbacks.removeFirst()
            time += maxOf(delay, minimumGap)
            callback()
        }
    }

    @Test
    fun retirementDrainsNonRendererReleasesDespiteSteadilyStaleFences() {
        for (gap in listOf(66L, 100L, 300L)) {
            val clock = Clock().apply { minimumGap = gap }
            var alive = true
            val probe = FreshFenceProbe(
                createFence = { clock.time },
                // An armed fence cannot reach in the same poll; every later poll is stale.
                isReached = { clock.time > it },
                destroyFence = {},
                now = { clock.time },
            )
            val teardowns = BackendIdleTeardowns(probe)
            val order = mutableListOf<String>()
            teardowns.defer(
                isEngineAlive = { alive }, schedule = clock.schedule,
                teardown = { error("Engine.destroy must reclaim this renderer") },
                reclaimWithEngine = true,
            )
            teardowns.defer(
                isEngineAlive = { alive }, schedule = clock.schedule,
                teardown = { order += "resource" },
            )
            repeat(100) {
                assertEquals(1, clock.callbacks.size)
                clock.poll()
            }
            assertTrue("a shared engine still requires freshness", order.isEmpty())
            teardowns.defer(
                isEngineAlive = { alive }, schedule = clock.schedule,
                teardown = { order += "engine"; alive = false }, runsLast = true,
            )
            assertEquals("only the renderer is dropped at retirement", 2, teardowns.size)
            clock.poll()
            assertEquals("poll gap $gap", listOf("resource"), order)
            assertTrue("the release invalidated the engine fence", alive)
            repeat(10) { if (clock.callbacks.isNotEmpty()) clock.poll() }
            assertEquals("poll gap $gap", listOf("resource", "engine"), order)
            assertFalse(alive)
            assertEquals(0, teardowns.size)
            assertTrue(clock.callbacks.isEmpty())
        }
    }

    @Test
    fun retirementDoesNotAccumulateCallbacksForLateEngineOwnedResources() {
        val clock = Clock()
        val teardowns = BackendIdleTeardowns()
        var busy = true
        var destroys = 0
        teardowns.defer(
            isEngineAlive = { true }, isBackendBusy = { busy }, schedule = clock.schedule,
            teardown = { destroys++ }, runsLast = true,
        )
        repeat(10_000) {
            teardowns.defer(
                isEngineAlive = { true }, isBackendBusy = { true }, schedule = clock.schedule,
                teardown = { error("native lifetime belongs to Engine.destroy") },
                reclaimWithEngine = true,
            )
        }
        assertEquals(1, teardowns.size)
        assertEquals(1, clock.callbacks.size)
        busy = false
        clock.poll()
        assertEquals(1, destroys)
        assertEquals(0, teardowns.size)
        assertTrue(clock.callbacks.isEmpty())
    }

    @Test
    fun explicitEngineCleanupAlsoLeavesEngineOwnedResourcesToFilament() {
        val clock = Clock()
        val teardowns = BackendIdleTeardowns()
        val order = mutableListOf<String>()
        for (owned in listOf(true, false)) {
            teardowns.defer(
                isEngineAlive = { true }, isBackendBusy = { true }, schedule = clock.schedule,
                teardown = { if (owned) error("must not destroy a pending renderer") else order += "context" },
                reclaimWithEngine = owned,
            )
        }
        teardowns.runPending()
        order += "engine"
        clock.poll()
        assertEquals(listOf("context", "engine"), order)
        assertEquals(0, teardowns.size)
        assertTrue(clock.callbacks.isEmpty())
    }
}
