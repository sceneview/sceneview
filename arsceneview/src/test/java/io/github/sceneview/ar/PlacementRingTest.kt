package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The placement ring's look, timing and mesh, pinned on the JVM. */
class PlacementRingTest {

    @Test
    fun `the ring is held under the fingers, dashed while estimated, closed once tracked`() {
        assertEquals(PlacementRingLook.Held, placementRingLookFor(touching = true, settled = false))
        assertEquals(PlacementRingLook.Held, placementRingLookFor(touching = true, settled = true))
        assertEquals(PlacementRingLook.Estimated, placementRingLookFor(touching = false, settled = false))
        assertEquals(PlacementRingLook.Anchored, placementRingLookFor(touching = false, settled = true))
        assertTrue(PlacementRingLook.Estimated.dashed)
        assertFalse(PlacementRingLook.Anchored.dashed)
        assertTrue(PlacementRingLook.Held.halfStroke > PlacementRingLook.Anchored.halfStroke)
    }

    @Test
    fun `the ring stays longer on an estimated spot`() {
        assertEquals(1_200L, PlacementRingVisibility.holdFor(settled = true))
        assertEquals(3_000L, PlacementRingVisibility.holdFor(settled = false))
        assertEquals(180, PlacementRingVisibility.FADE_MS)
    }

    @Test
    fun `the ring shows under the fingers and until its hold runs out`() {
        assertTrue(PlacementRingVisibility.isVisible(placed = true, touching = true, holdElapsed = true))
        assertTrue(PlacementRingVisibility.isVisible(placed = true, touching = false, holdElapsed = false))
        assertFalse(PlacementRingVisibility.isVisible(placed = true, touching = false, holdElapsed = true))
        assertFalse(PlacementRingVisibility.isVisible(placed = false, touching = true, holdElapsed = false))
    }

    @Test
    fun `the ring pulses as it lands and as its line closes, never under the fingers`() {
        val anchored = RingState(visible = true, look = PlacementRingLook.Anchored)
        val estimated = RingState(visible = true, look = PlacementRingLook.Estimated)
        val held = RingState(visible = true, look = PlacementRingLook.Held)
        assertTrue(ringPulses(null, estimated))
        assertTrue(ringPulses(RingState(false, PlacementRingLook.Anchored), anchored))
        assertTrue(ringPulses(estimated, anchored))
        assertFalse(ringPulses(anchored, anchored))
        assertFalse(ringPulses(estimated, held))
        assertFalse(ringPulses(anchored, RingState(false, PlacementRingLook.Anchored)))
    }

    @Test
    fun `the stroke keeps a readable width from a mug to a sofa`() {
        assertEquals(0.0025f, ringStrokeFor(0.05f), 1e-7f)
        assertEquals(0.010f, ringStrokeFor(3f), 1e-7f)
        assertEquals(0.0072f, ringStrokeFor(0.3f), 1e-6f)
    }

    @Test
    fun `the band is a closed loop around the radius`() {
        val radius = 0.4f
        val v = ringVertices(radius)
        assertEquals((RING_SEGMENTS + 1) * 2 * RING_VERTEX_FLOATS, v.size)
        for (i in v.indices step RING_VERTEX_FLOATS) {
            val r = kotlin.math.hypot(v[i], v[i + 2])
            assertTrue("vertex $i at $r", kotlin.math.abs(r - radius) <= ringStrokeFor(radius) * 2f)
            assertEquals(0f, v[i + 1])
        }
        val idx = ringIndices()
        assertEquals(RING_SEGMENTS * 6, idx.size)
        assertTrue(idx.all { it >= 0 && it < (RING_SEGMENTS + 1) * 2 })
    }

    @Test
    fun `fade steps cover the fade from hidden to full`() {
        assertEquals(0, ringFadeStep(0f))
        assertEquals(RING_FADE_STEPS, ringFadeStep(1f))
        assertEquals(RING_FADE_STEPS, ringFadeStep(2f))
        assertTrue(ringFadeStep(0.01f) in 0..1)
    }

    @Test
    fun `every look has a texture at every fade step`() {
        for (look in PlacementRingLook.values()) for (step in 1..RING_FADE_STEPS) {
            assertTrue(ringTexels(look, step).isNotEmpty())
        }
    }
}
