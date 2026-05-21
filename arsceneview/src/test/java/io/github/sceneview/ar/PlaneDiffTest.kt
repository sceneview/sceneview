package io.github.sceneview.ar

import io.github.sceneview.ar.arcore.diffTrackedSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM regression test for the plane-lifecycle diff landed by #1774 on
 * `io.github.sceneview.ar.arcore.rememberDetectedPlanes` (the SceneView equivalent of AR
 * Foundation's `ARPlaneManager.planesChanged`).
 *
 * The composable runs on the Compose frame callback and the underlying `com.google.ar.core.Plane`
 * is JNI-only — its constructor takes a native handle and is unmockable under pure-JVM tests. So
 * the add / update / remove classification is split into the generic [diffTrackedSet] core, which
 * operates on plain reference objects. This test pins that contract — the same logic that runs
 * on-device, only without the ARCore handles.
 *
 * What we pin:
 *  - `added`   = planes in `current` but not `previous`.
 *  - `updated` = planes in both sets.
 *  - `removed` = planes in `previous` but not `current`.
 *  - The three lists always partition `previous ∪ current` (no plane lost, none double-counted).
 *  - Identity is by reference (ARCore reuses the same `Plane` instance across frames).
 */
class PlaneDiffTest {

    /** Stand-in for a `Plane` — diffing is reference-identity, the field is just for labels. */
    private class FakePlane(val label: String) {
        override fun toString() = label
    }

    @Test
    fun `first frame — every plane is added`() {
        val a = FakePlane("a")
        val b = FakePlane("b")
        val (added, updated, removed) = diffTrackedSet(emptySet(), setOf(a, b))
        assertEquals(setOf(a, b), added.toSet())
        assertTrue("nothing updated on the first frame", updated.isEmpty())
        assertTrue("nothing removed on the first frame", removed.isEmpty())
    }

    @Test
    fun `stable frame — every plane is updated`() {
        val a = FakePlane("a")
        val b = FakePlane("b")
        val set = setOf(a, b)
        val (added, updated, removed) = diffTrackedSet(set, set)
        assertTrue("no plane appeared", added.isEmpty())
        assertEquals(set, updated.toSet())
        assertTrue("no plane disappeared", removed.isEmpty())
    }

    @Test
    fun `mixed frame — partitions added, updated and removed`() {
        val kept = FakePlane("kept")
        val gone = FakePlane("gone")     // subsumed / lost
        val fresh = FakePlane("fresh")   // newly detected

        val (added, updated, removed) = diffTrackedSet(
            previous = setOf(kept, gone),
            current = setOf(kept, fresh)
        )

        assertEquals(listOf(fresh), added)
        assertEquals(listOf(kept), updated)
        assertEquals(listOf(gone), removed)
    }

    @Test
    fun `empty current — every previous plane is removed`() {
        val a = FakePlane("a")
        val b = FakePlane("b")
        val (added, updated, removed) = diffTrackedSet(setOf(a, b), emptySet())
        assertTrue(added.isEmpty())
        assertTrue(updated.isEmpty())
        assertEquals(setOf(a, b), removed.toSet())
    }

    @Test
    fun `two empty sets produce no deltas`() {
        val (added, updated, removed) = diffTrackedSet(emptySet<FakePlane>(), emptySet())
        assertTrue(added.isEmpty())
        assertTrue(updated.isEmpty())
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `three lists always partition the union of previous and current`() {
        val shared = FakePlane("shared")
        val onlyPrev = FakePlane("onlyPrev")
        val onlyCurr = FakePlane("onlyCurr")
        val previous = setOf(shared, onlyPrev)
        val current = setOf(shared, onlyCurr)

        val (added, updated, removed) = diffTrackedSet(previous, current)

        // No plane is dropped and none is counted twice.
        assertEquals(
            (previous + current),
            (added + updated + removed).toSet()
        )
        assertEquals(
            "partition lists must be disjoint",
            added.size + updated.size + removed.size,
            (added + updated + removed).toSet().size
        )
    }

    @Test
    fun `diff is identity-based, not equality-based`() {
        // Two distinct instances with the same label are different planes.
        val first = FakePlane("plane")
        val second = FakePlane("plane")
        val (added, updated, removed) = diffTrackedSet(setOf(first), setOf(second))
        assertEquals(listOf(second), added)
        assertTrue(updated.isEmpty())
        assertEquals(listOf(first), removed)
    }
}
