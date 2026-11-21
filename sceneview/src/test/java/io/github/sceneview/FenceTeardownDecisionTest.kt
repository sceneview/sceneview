package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure decisions and virtual-time probes: no Android looper or Filament JNI. */
class FenceTeardownDecisionTest {
    @Test
    fun onlyAReachedFreshFenceAllowsASharedEngineRelease() {
        val cases = listOf(
            Triple(true, -1L, false),
            Triple(true, 0L, true),
            Triple(true, FRESH_FENCE_MAX_AGE_MS, true),
            Triple(true, FRESH_FENCE_MAX_AGE_MS + 1, false),
            Triple(true, 5_000L, false),
            Triple(true, 60_000L, false),
            Triple(false, 0L, false),
            Triple(false, 60_000L, false),
            Triple(null, 0L, false),
            Triple(null, 60_000L, false),
        )
        for ((reached, age, expected) in cases) {
            assertEquals("reached=$reached, age=$age", expected,
                fenceAllowsTeardown(reached, age, FRESH_FENCE_MAX_AGE_MS))
        }
    }

    @Test
    fun retiringEngineReleasesStillRequireAReachedFence() {
        for (reached in listOf(false, null, true)) {
            assertEquals(reached == true, fenceAllowsTeardown(
                reached, 60_000L, FRESH_FENCE_MAX_AGE_MS, requireFresh = false,
            ))
        }
    }

    @Test
    fun aReachedOldFenceDoesNotAuthorizeDestroyingIntoNewBackendWork() {
        var clock = 0L
        var next = 0
        var warnings = 0
        var idleNow = false
        val released = mutableListOf<Int>()
        val probe = FreshFenceProbe(
            createFence = { ++next },
            // Fence 1 precedes the new screen's work. Only a replacement can cover that work.
            isReached = { (it == 1 && clock > 0) || idleNow },
            destroyFence = { released += it },
            now = { clock },
            onStillBusy = { warnings++ },
        )
        assertTrue(probe.isBusy())
        clock = 6_000
        assertTrue(probe.isBusy())
        assertEquals(listOf(1), released)
        clock = 3_600_000
        assertTrue("one hour cannot authorize destruction", probe.isBusy())
        assertEquals(1, warnings)

        idleNow = true
        assertFalse("a replacement reached in this poll proves current readiness", probe.isBusy())
        assertEquals(listOf(1, 2), released)
        probe.release()
        probe.release()
        assertEquals(listOf(1, 2, 3), released)
    }

    @Test
    fun missingOrErroredFencesNeverAuthorizeDestroyingAtADeadline() {
        for (missing in listOf(false, true)) {
            var clock = 0L
            var created = 0
            var destroyed = 0
            var warnings = 0
            val probe = FreshFenceProbe(
                createFence = { if (missing) null else ++created },
                isReached = { null },
                destroyFence = { destroyed++ },
                now = { clock },
                onStillBusy = { warnings++ },
            )
            repeat(100) {
                assertTrue(probe.isBusy())
                clock += 1_000
            }
            assertEquals(1, warnings)
            probe.release()
            assertEquals(created, destroyed)
        }
    }
}
