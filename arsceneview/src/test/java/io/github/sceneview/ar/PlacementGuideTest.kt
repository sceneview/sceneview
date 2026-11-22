package io.github.sceneview.ar

import io.github.sceneview.ar.PlacementGuidePolicy.DIMENSIONS_MS
import io.github.sceneview.ar.PlacementGuidePolicy.LAND_MS
import io.github.sceneview.ar.PlacementGuidePolicy.RELEASE_MS
import io.github.sceneview.ar.PlacementGuidePolicy.RING_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import org.junit.Test

/** The rule of #5209: what an automatic placement shows, and when. */
class PlacementGuideTest {

    private fun guide(
        phase: PlacementPhase,
        selected: Boolean = false,
        sincePlaced: Long? = null,
        sinceRelease: Long? = null,
    ) = PlacementGuidePolicy.guide(phase, selected, sincePlaced, sinceRelease)

    @Test
    fun `looking for a surface shows every surface and nothing else`() {
        for (phase in listOf(PlacementPhase.SCANNING, PlacementPhase.NO_SURFACE)) {
            val g = guide(phase)
            assertTrue(g.planes)
            assertFalse(g.ring)
            assertFalse(g.dimensions)
        }
    }

    @Test
    fun `nothing before the camera tracks or behind a failure card`() {
        for (phase in listOf(PlacementPhase.INITIALIZING, PlacementPhase.RECOVERY_FAILED, PlacementPhase.CAMERA_ERROR)) {
            assertEquals(PlacementGuide.Nothing, guide(phase))
        }
    }

    @Test
    fun `a landing keeps the surfaces, then the ring, then the dimensions a moment`() {
        val landing = guide(PlacementPhase.PLACED, sincePlaced = 0)
        assertTrue(landing.planes && landing.ring && landing.dimensions)
        val afterLand = guide(PlacementPhase.PLACED, sincePlaced = LAND_MS)
        assertFalse(afterLand.planes)
        assertTrue(afterLand.ring)
        val afterRing = guide(PlacementPhase.PLACED, sincePlaced = RING_MS)
        assertFalse(afterRing.ring)
        assertTrue(afterRing.dimensions)
        assertEquals(PlacementGuide.Nothing, guide(PlacementPhase.PLACED, sincePlaced = DIMENSIONS_MS))
    }

    @Test
    fun `a placed object waiting for tracking keeps still`() {
        for (phase in listOf(PlacementPhase.TRACKING_LOST, PlacementPhase.RECOVERING)) {
            assertEquals(PlacementGuide.Nothing, PlacementGuidePolicy.guide(phase, false, 60_000, null, hasPlacement = true))
            assertEquals(PlacementGuide.Searching, PlacementGuidePolicy.guide(phase, false, null, null, hasPlacement = false))
        }
    }

    @Test
    fun `a placed object left alone shows only its contact shadow`() {
        val g = guide(PlacementPhase.PLACED, sincePlaced = 60_000)
        assertEquals(PlacementGuide.Nothing, g)
    }

    @Test
    fun `an app whose answer is the size keeps the dimensions on`() {
        val g = PlacementGuidePolicy.guide(PlacementPhase.PLACED, false, 60_000, null, alwaysShowsDimensions = true)
        assertTrue(g.dimensions)
        assertFalse(g.planes)
        assertFalse(g.ring)
        val searching = PlacementGuidePolicy.guide(PlacementPhase.SCANNING, false, null, null, alwaysShowsDimensions = true)
        assertFalse(searching.dimensions)
    }

    @Test
    fun `selection shows the ring and the dimensions, not the surfaces`() {
        val g = guide(PlacementPhase.PLACED, selected = true, sincePlaced = 60_000)
        assertFalse(g.planes)
        assertTrue(g.ring)
        assertTrue(g.dimensions)
    }

    @Test
    fun `a drag brings the surfaces back under a wider ring until a second after release`() {
        val drag = guide(PlacementPhase.ADJUSTING, selected = true, sincePlaced = 60_000)
        assertTrue(drag.planes && drag.ring && drag.dimensions && drag.held)
        val released = guide(PlacementPhase.PLACED, sincePlaced = 60_000, sinceRelease = RELEASE_MS - 1)
        assertTrue(released.planes)
        assertTrue(released.ring)
        assertFalse(released.held)
        assertFalse(guide(PlacementPhase.PLACED, sincePlaced = 60_000, sinceRelease = RELEASE_MS).planes)
    }

    @Test
    fun `the clock wakes at the next change and not after the last one`() {
        assertEquals(LAND_MS, PlacementGuidePolicy.nextChangeMs(0, null))
        assertEquals(RING_MS - 900, PlacementGuidePolicy.nextChangeMs(900, null))
        assertEquals(200L, PlacementGuidePolicy.nextChangeMs(DIMENSIONS_MS - 200, null))
        assertEquals(RELEASE_MS - 10, PlacementGuidePolicy.nextChangeMs(60_000, 10))
        assertNull(PlacementGuidePolicy.nextChangeMs(60_000, RELEASE_MS))
        assertNull(PlacementGuidePolicy.nextChangeMs(null, null))
    }

    @Test
    fun `the ring's edgings hug its stroke on both sides, wider when held`() {
        val footprint = SelectionRing.Footprint(0f, 0f, 0.5f)
        val idle = SelectionRing.bands(footprint, held = false)
        val held = SelectionRing.bands(footprint, held = true)
        val stroke = idle.single { it.isStroke }
        assertEquals(0.5f, stroke.radius, 0f)
        assertEquals(SelectionRing.tubeRadius(0.5f), stroke.tube, 1e-7f)
        val (inner, outer) = idle.filterNot { it.isStroke }.sortedBy { it.radius }
        // Each edging touches the stroke, one on each side.
        assertEquals(stroke.radius - stroke.tube, inner.radius + inner.tube, 1e-6f)
        assertEquals(stroke.radius + stroke.tube, outer.radius - outer.tube, 1e-6f)
        assertEquals(stroke.tube * SelectionRing.HELD_WIDTH, held.single { it.isStroke }.tube, 1e-7f)
    }

    @Test
    fun `sized content stands on the floor, or lies along the wall`() {
        val floor = sizedContentCorners(Size(0.9f, 0.85f, 0.6f), Rotation())
        assertEquals(0f, floor.minOf { it.y }, 1e-6f)
        assertEquals(0.85f, floor.maxOf { it.y }, 1e-6f)
        assertEquals(0.9f, floor.maxOf { it.x } - floor.minOf { it.x }, 1e-6f)
        // A framed print 0.6 wide, 0.4 high, 0.03 deep: its depth becomes the wall normal (+Y).
        val wall = sizedContentCorners(Size(0.6f, 0.4f, 0.03f), Rotation(x = -90f))
        assertEquals(0.03f, wall.maxOf { it.y } - wall.minOf { it.y }, 1e-5f)
        assertEquals(0.4f, wall.maxOf { it.z } - wall.minOf { it.z }, 1e-5f)
    }
}
