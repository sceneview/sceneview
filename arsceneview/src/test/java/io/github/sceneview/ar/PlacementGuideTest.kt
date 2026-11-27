package io.github.sceneview.ar

import io.github.sceneview.ar.PlacementGuidePolicy.DIMENSIONS_MS
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
    fun `placement immediately starts fading the planes and leaves no footprint ring`() {
        for (time in listOf<Long?>(null, 0, 100, 800, 1500, 60000)) {
            val g = guide(PlacementPhase.PLACED, sincePlaced = time)
            assertFalse(g.planes)
            assertFalse(g.ring)
        }
        assertTrue(guide(PlacementPhase.PLACED, sincePlaced = 0).dimensions)
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
    fun `selection shows dimensions without restoring planes or the footprint ring`() {
        val g = guide(PlacementPhase.PLACED, selected = true, sincePlaced = 60_000)
        assertFalse(g.planes)
        assertFalse(g.ring)
        assertTrue(g.dimensions)
    }

    @Test
    fun `a drag restores the guides and release starts fading immediately`() {
        val drag = guide(PlacementPhase.ADJUSTING, selected = true, sincePlaced = 60000)
        assertTrue(drag.planes && drag.ring && drag.dimensions && drag.held)
        for (time in listOf(0L, 100L, 1000L)) {
            val released = guide(PlacementPhase.PLACED, sincePlaced = 60000, sinceRelease = time)
            assertFalse(released.planes)
            assertFalse(released.ring)
            assertFalse(released.held)
        }
    }

    @Test
    fun `the clock only wakes for dimensions after landing`() {
        assertEquals(DIMENSIONS_MS, PlacementGuidePolicy.nextChangeMs(0, null))
        assertEquals(200L, PlacementGuidePolicy.nextChangeMs(DIMENSIONS_MS - 200, null))
        assertNull(PlacementGuidePolicy.nextChangeMs(60000, 10))
        assertNull(PlacementGuidePolicy.nextChangeMs(null, null))
    }

    @Test
    fun `the manipulation ring has only a light stroke and no black edging`() {
        val footprint = SelectionRing.Footprint(0f, 0f, 0.5f)
        val idle = SelectionRing.bands(footprint, held = false).single()
        val held = SelectionRing.bands(footprint, held = true).single()
        assertTrue(idle.isStroke && held.isStroke)
        assertEquals(0.5f, idle.radius, 0f)
        assertEquals(idle.tube * SelectionRing.HELD_WIDTH, held.tube, 1e-7f)
    }

    @Test
    fun `default placement uses light driven shadows without an artificial bounds pool`() {
        assertFalse(AutoPlacementState().showsContactShadow)
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
