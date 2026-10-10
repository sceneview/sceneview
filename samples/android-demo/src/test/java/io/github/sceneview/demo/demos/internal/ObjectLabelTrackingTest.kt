package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectLabelTrackingTest {

    @Test
    fun `tracking id crosses three cells as one label then expires and detaches`() {
        data class FakeAnchor(var detached: Boolean = false)

        val anchors = mutableListOf<FakeAnchor>()
        val tracker = ObjectLabelTracker<FakeAnchor>(expiryPasses = 3) { it.detached = true }
        listOf(32f, 96f, 160f).forEach { x ->
            val anchor = FakeAnchor().also(anchors::add)
            val tracks = tracker.reconcile(listOf(observation(id = 7, x = x))) { _, _ -> anchor }
            assertEquals(1, tracks.size)
            assertEquals("tracking:7", tracks.single().key)
            assertTrue(anchors.dropLast(1).all { it.detached })
            assertFalse(anchor.detached)
        }

        assertEquals(1, tracker.reconcile(emptyList()) { _, _ -> error("no observation") }.size)
        assertEquals(1, tracker.reconcile(emptyList()) { _, _ -> error("no observation") }.size)
        assertEquals(0, tracker.reconcile(emptyList()) { _, _ -> error("no observation") }.size)
        assertTrue(anchors.all { it.detached })
    }

    @Test
    fun `fallback associates a nearby same-label object without a tracking id`() {
        val tracker = ObjectLabelTracker<String>(dispose = {})
        val first = tracker.reconcile(listOf(observation(id = null, x = 20f))) { _, _ -> "first" }
            .single()
        val second = tracker.reconcile(listOf(observation(id = null, x = 140f))) { _, _ -> "second" }
            .single()

        assertEquals(first.key, second.key)
        assertEquals("second", second.payload)
    }

    @Test
    fun `returning the previous payload keeps it without disposing it`() {
        val disposed = mutableListOf<String>()
        val tracker = ObjectLabelTracker<String> { disposed += it }
        tracker.reconcile(listOf(observation(id = 7, x = 32f))) { _, _ -> "anchor" }

        val kept = tracker.reconcile(listOf(observation(id = 7, x = 40f))) { _, previous ->
            assertEquals("anchor", previous)
            previous
        }.single()

        assertEquals("anchor", kept.payload)
        assertEquals(40f, kept.observation.centerX, 0f)
        assertTrue(disposed.isEmpty())
    }

    @Test
    fun `a pass without a usable payload keeps the previous one`() {
        val disposed = mutableListOf<String>()
        val tracker = ObjectLabelTracker<String> { disposed += it }
        tracker.reconcile(listOf(observation(id = 7, x = 32f))) { _, _ -> "anchor" }

        val kept = tracker.reconcile(listOf(observation(id = 7, x = 40f))) { _, _ -> null }.single()

        assertEquals("anchor", kept.payload)
        assertTrue(disposed.isEmpty())
    }

    @Test
    fun `an object that can no longer be placed loses its label after the expiry passes`() {
        // The object was moved to where no surface is known: it is still detected, but every
        // pass returns no payload. Its label must not stay at the old place.
        val disposed = mutableListOf<String>()
        val tracker = ObjectLabelTracker<String>(expiryPasses = 3) { disposed += it }
        tracker.reconcile(listOf(observation(id = 7, x = 32f))) { _, _ -> "old place" }

        val unplaced = { tracker.reconcile(listOf(observation(id = 7, x = 300f))) { _, _ -> null } }
        assertEquals("old place", unplaced().single().payload)
        assertEquals("old place", unplaced().single().payload)
        val expired = unplaced().single()

        assertEquals(null, expired.payload)
        assertEquals("tracking:7", expired.key)
        assertEquals(listOf("old place"), disposed)

        // The track itself survives: the next pass that can place the object labels it again.
        val placed = tracker.reconcile(listOf(observation(id = 7, x = 300f))) { _, previous ->
            assertEquals(null, previous)
            "new place"
        }.single()
        assertEquals("new place", placed.payload)
        assertEquals(listOf("old place"), disposed)
    }

    @Test
    fun `a confirmed payload restarts the expiry count`() {
        val disposed = mutableListOf<String>()
        val tracker = ObjectLabelTracker<String>(expiryPasses = 3) { disposed += it }
        val seen = listOf(observation(id = 7, x = 32f))
        tracker.reconcile(seen) { _, _ -> "anchor" }

        // Two passes without a hit, one with: the hit-test of a still object flickers.
        repeat(3) {
            tracker.reconcile(seen) { _, _ -> null }
            tracker.reconcile(seen) { _, _ -> null }
            assertEquals("anchor", tracker.reconcile(seen) { _, previous -> previous }.single().payload)
        }
        assertTrue(disposed.isEmpty())
    }

    @Test
    fun `unplaced and undetected passes add up toward expiry`() {
        val disposed = mutableListOf<String>()
        val tracker = ObjectLabelTracker<String>(expiryPasses = 3) { disposed += it }
        val seen = listOf(observation(id = 7, x = 32f))
        tracker.reconcile(seen) { _, _ -> "anchor" }

        tracker.reconcile(seen) { _, _ -> null }
        tracker.reconcile(emptyList()) { _, _ -> error("no observation") }
        assertTrue(disposed.isEmpty())
        assertEquals(0, tracker.reconcile(emptyList()) { _, _ -> error("no observation") }.size)
        assertEquals(listOf("anchor"), disposed)
    }

    private fun observation(id: Int?, x: Float) = ObjectLabelObservation(
        trackingId = id,
        label = "Home good",
        confidence = 0.8f,
        centerX = x,
        centerY = 100f,
    )
}
