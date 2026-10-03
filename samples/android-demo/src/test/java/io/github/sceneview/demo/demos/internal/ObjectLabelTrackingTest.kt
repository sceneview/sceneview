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

    private fun observation(id: Int?, x: Float) = ObjectLabelObservation(
        trackingId = id,
        label = "Home good",
        confidence = 0.8f,
        centerX = x,
        centerY = 100f,
    )
}
