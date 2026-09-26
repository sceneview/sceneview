package io.github.sceneview.demo.demos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins #3795: [PickingAndCollisionDemo] never published "Scene ready" in
 * `DemoSettings.qaMode`, because its ViewNode warmup gate counted raw `SceneView` frames —
 * which the card's frozen (QA-deterministic) auto-rotate no longer kept flowing under
 * render-on-demand — instead of requesting the frames it needed. Pure JVM, no Compose or
 * Robolectric: [ViewNodeWarmupGate] takes its render-invalidation callback as a lambda.
 */
class ViewNodeWarmupGateTest {

    @Test
    fun `frames during warmup are swallowed, not forwarded`() {
        var forwarded = 0
        val gate = ViewNodeWarmupGate(
            warmupFrames = 3,
            isRendered = { false },
            forward = { forwarded++ },
            requestRender = {},
        )

        gate.onFrame(1L)
        gate.onFrame(2L)
        gate.onFrame(3L)

        assertEquals(0, forwarded)
        assertEquals(3, gate.framesSeen)
    }

    @Test
    fun `frames after warmup are forwarded with their timestamp`() {
        val forwardedTimestamps = mutableListOf<Long>()
        val gate = ViewNodeWarmupGate(
            warmupFrames = 2,
            isRendered = { false },
            forward = { forwardedTimestamps.add(it) },
            requestRender = {},
        )

        gate.onFrame(1L) // swallowed (1/2)
        gate.onFrame(2L) // swallowed (2/2)
        gate.onFrame(3L) // forwarded
        gate.onFrame(4L) // forwarded

        assertEquals(listOf(3L, 4L), forwardedTimestamps)
        // The counter latches at `warmupFrames` rather than growing forever.
        assertEquals(2, gate.framesSeen)
    }

    @Test
    fun `a render is requested on every tick while not yet rendered`() {
        // This is the #3795 fix: interactively the card's own auto-rotate keeps the
        // render-on-demand gate open for free, but `DemoSettings.qaMode` freezes it, so
        // nothing else would ask for the frames the warmup needs. The gate must ask itself.
        var requests = 0
        val gate = ViewNodeWarmupGate(
            warmupFrames = 18,
            isRendered = { false },
            forward = {},
            requestRender = { requests++ },
        )

        repeat(18) { gate.onFrame(it.toLong()) }

        assertEquals(18, requests)
    }

    @Test
    fun `no render is requested once the scene has latched as rendered`() {
        var rendered = false
        var requests = 0
        val gate = ViewNodeWarmupGate(
            warmupFrames = 1,
            isRendered = { rendered },
            forward = { rendered = true }, // simulates FirstFrameState latching on forward
            requestRender = { requests++ },
        )

        gate.onFrame(1L) // warmup frame: swallowed, not yet rendered -> requests render
        assertFalse(rendered)
        assertEquals(1, requests)

        gate.onFrame(2L) // forwarded, latches `rendered` -> no further request this tick
        assertTrue(rendered)
        assertEquals(1, requests)

        gate.onFrame(3L) // already rendered: still no request
        assertEquals(1, requests)
    }
}
