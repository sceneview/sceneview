package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The estimated floor placement, settling, recentering and the placement ring's state, pinned on
 * the JVM: an object stands within [PlacementFeel.ESTIMATED_FALLBACK_MS] of tracking even with no
 * plane, a surface always wins, and the ring knows when the object lands again.
 */
class AutoPlacementFallbackTest {

    private fun searching(now: Long, surface: Boolean = false, estimated: Boolean = true, settled: Boolean? = null) =
        FrameInput(nowMillis = now, tracking = true, surfaceAvailable = surface,
            estimatedAvailable = estimated, anchorSettled = settled)

    private fun placedEstimated(): AutoPlacementState = AutoPlacementState().apply {
        requestPlacement()
        onFrame(searching(0L))
        onFrame(searching(PlacementFeel.ESTIMATED_FALLBACK_MS, settled = false))
    }

    @Test
    fun `with no surface the object stands on the estimated floor after 600 ms, not a frame before`() {
        val c = AutoPlacementState()
        c.requestPlacement()
        val asked = mutableListOf<PlacementSource>()
        var t = 0L
        while (t < PlacementFeel.ESTIMATED_FALLBACK_MS) {
            assertEquals(FrameEffect.NONE, c.onFrame(searching(t)) { asked += it; true })
            t += 16L
        }
        assertTrue("no estimate before the delay", asked.isEmpty())
        assertEquals(FrameEffect.PLACE, c.onFrame(searching(PlacementFeel.ESTIMATED_FALLBACK_MS)) { asked += it; true })
        assertEquals(listOf(PlacementSource.ESTIMATED), asked)
        assertEquals(PlacementSource.ESTIMATED, c.placementSource)
        assertFalse("an instant-placement point is not tracked yet", c.isSettled)
        assertEquals(PlacementPhase.PLACED, c.phase)
        assertEquals(1, c.placementsCreated)
    }

    @Test
    fun `a surface always wins over the estimate`() {
        val c = AutoPlacementState()
        c.requestPlacement()
        c.onFrame(searching(0L))
        val asked = mutableListOf<PlacementSource>()
        c.onFrame(searching(5_000L, surface = true)) { asked += it; true }
        assertEquals(listOf(PlacementSource.SURFACE), asked)
        assertEquals(PlacementSource.SURFACE, c.placementSource)
        assertTrue(c.isSettled)
    }

    @Test
    fun `a surface anchor that fails falls back on the estimate once due`() {
        val c = AutoPlacementState()
        c.requestPlacement()
        c.onFrame(searching(0L))
        val effect = c.onFrame(searching(700L, surface = true)) { it == PlacementSource.ESTIMATED }
        assertEquals(FrameEffect.PLACE, effect)
        assertEquals(PlacementSource.ESTIMATED, c.placementSource)
    }

    @Test
    fun `a null fallback waits for a surface however long it takes`() {
        val c = AutoPlacementState()
        c.estimatedFallbackMillis = null
        c.requestPlacement()
        repeat(300) { i -> assertEquals(FrameEffect.NONE, c.onFrame(searching(i * 33L))) }
        assertEquals(0, c.placementsCreated)
        assertEquals(PlacementPhase.SCANNING, c.phase)
    }

    @Test
    fun `no estimate where the scene offers none, a wall flow among them`() {
        val c = AutoPlacementState()
        c.requestPlacement()
        repeat(100) { i -> c.onFrame(searching(i * 33L, estimated = false)) }
        assertEquals(0, c.placementsCreated)
    }

    @Test
    fun `an estimate ARCore refuses is asked again on the next frame`() {
        val c = AutoPlacementState()
        c.requestPlacement()
        c.onFrame(searching(0L))
        assertEquals(FrameEffect.NONE, c.onFrame(searching(600L)) { false })
        assertFalse(c.hasPlacement)
        assertEquals(FrameEffect.PLACE, c.onFrame(searching(616L)) { true })
    }

    @Test
    fun `the fallback clock does not run while tracking is lost`() {
        val c = AutoPlacementState()
        c.requestPlacement()
        c.onFrame(searching(0L))
        c.onFrame(searching(400L))
        c.onFrame(FrameInput(nowMillis = 500L, tracking = false, surfaceAvailable = false))
        c.onFrame(searching(2_000L))
        assertEquals(FrameEffect.NONE, c.onFrame(searching(2_500L)))
        assertEquals(FrameEffect.PLACE, c.onFrame(searching(2_600L)))
    }

    @Test
    fun `an estimated placement settles once ARCore tracks its point, and never unsettles`() {
        val c = placedEstimated()
        c.onFrame(FrameInput(700L, true, false, anchorTracking = true, anchorSettled = false))
        assertFalse(c.isSettled)
        c.onFrame(FrameInput(800L, true, false, anchorTracking = true, anchorSettled = true))
        assertTrue(c.isSettled)
        c.onFrame(FrameInput(900L, true, false, anchorTracking = true, anchorSettled = false))
        assertTrue(c.isSettled)
        assertEquals("settling is not a new landing", 1, c.placementRevision)
    }

    @Test
    fun `the revision counts each landing`() {
        val c = placedEstimated()
        assertEquals(1, c.placementRevision)
        c.relocated(PlacementSource.SURFACE, settled = true)
        assertEquals(2, c.placementRevision)
        assertEquals(PlacementSource.SURFACE, c.placementSource)
        assertTrue(c.isSettled)
    }

    @Test
    fun `nothing relocates before anything stands`() {
        val c = AutoPlacementState()
        c.relocated(PlacementSource.SURFACE, settled = true)
        assertEquals(0, c.placementRevision)
        assertNull(c.placementSource)
    }

    @Test
    fun `reset forgets the source and the settling`() {
        val c = placedEstimated()
        c.resetPlacement(1_000L)
        assertNull(c.placementSource)
        assertFalse(c.isSettled)
        assertTrue(c.placementRequested)
    }

    @Test
    fun `recenter needs a placed object and a scene that accepts it`() {
        val c = AutoPlacementState()
        var asked = 0
        c.recenterAction = { asked++; true }
        assertFalse("nothing stands yet", c.recenter())
        assertEquals(0, asked)

        val placed = placedEstimated()
        assertFalse("no scene attached", placed.recenter())
        placed.recenterAction = { asked++; true }
        assertTrue(placed.recenter())
        assertEquals(1, asked)
        placed.recenterAction = { false }
        assertFalse("the scene refused: camera not tracking, or a wall flow", placed.recenter())
    }

    @Test
    fun `recenter waits while the object is out of tracking`() {
        val c = placedEstimated()
        c.recenterAction = { true }
        c.onFrame(FrameInput(700L, tracking = false, surfaceAvailable = false))
        assertEquals(PlacementPhase.TRACKING_LOST, c.phase)
        assertFalse(c.recenter())
    }

    @Test
    fun `a touch stops whatever coasts`() {
        val c = AutoPlacementState()
        var stopped = 0
        c.touchDown()
        c.touchDownAction = { stopped++ }
        c.touchDown()
        assertEquals(1, stopped)
    }

    @Test
    fun `dismiss drops the placed object's hooks but keeps the scene's recenter`() {
        val c = placedEstimated()
        val recenter = { true }
        c.recenterAction = recenter
        c.touchDownAction = {}
        c.relocateAction = { true }
        c.dismiss()
        assertNull(c.touchDownAction)
        assertNull(c.relocateAction)
        assertNull(c.screenGestures)
        assertEquals(recenter, c.recenterAction)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `the selection ring flag is an alias of the placement ring`() {
        val c = AutoPlacementState()
        assertTrue(c.showsPlacementRing)
        c.showsSelectionRing = false
        assertFalse(c.showsPlacementRing)
        c.showsPlacementRing = true
        assertTrue(c.showsSelectionRing)
    }

    @Test
    fun `the default distance suits a half-metre object`() {
        assertEquals(1.3f, AutoPlacementState().placementDistance, 1e-6f)
    }
}
