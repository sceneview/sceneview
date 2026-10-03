package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Surfaces demo's rules once the car is down (#4307): the surfaces fade, the car stays
 * movable, the controls stay, and the banner stops spinning as soon as a surface is found.
 */
class SurfaceShowcaseTest {

    @Test
    fun `scanning shows the surfaces and has nothing to remove`() {
        val state = SurfaceShowcaseState()

        assertTrue(state.planesVisible)
        assertFalse(state.removeEnabled)
    }

    @Test
    fun `placing fades the surfaces and enables remove`() {
        val state = SurfaceShowcaseState().onPlaced()

        assertTrue(state.placed)
        assertFalse(state.planesVisible)
        assertTrue(state.removeEnabled)
    }

    @Test
    fun `dragging the placed car brings the surfaces back for the length of the drag`() {
        val placed = SurfaceShowcaseState().onPlaced()

        val dragging = placed.onMoveBegin()
        assertTrue(dragging.planesVisible)
        // The toggle itself did not move: the drag only borrows the surfaces.
        assertFalse(dragging.surfacesShown)

        assertEquals(placed, dragging.onMoveEnd())
    }

    @Test
    fun `a drag before anything is placed changes nothing`() {
        val state = SurfaceShowcaseState()

        assertEquals(state, state.onMoveBegin())
    }

    @Test
    fun `the toggle shows the surfaces again without unplacing the car`() {
        val state = SurfaceShowcaseState().onPlaced().onSurfacesToggled()

        assertTrue(state.planesVisible)
        assertTrue(state.placed)
        assertTrue(state.removeEnabled)
    }

    @Test
    fun `re-placing keeps what the toggle says`() {
        val hidden = SurfaceShowcaseState().onPlaced()
        val shown = hidden.onSurfacesToggled()

        assertEquals(hidden, hidden.onRePlaced())
        assertEquals(shown, shown.onRePlaced())
    }

    @Test
    fun `a drag ending with the toggle on leaves the surfaces on`() {
        val state = SurfaceShowcaseState().onPlaced().onSurfacesToggled().onMoveBegin().onMoveEnd()

        assertTrue(state.planesVisible)
    }

    @Test
    fun `removing goes back to the scan with the surfaces on`() {
        val state = SurfaceShowcaseState().onPlaced().onMoveBegin().onRemoved()

        assertEquals(SurfaceShowcaseState(), state)
        assertTrue(state.planesVisible)
    }

    @Test
    fun `only the scanning banner spins`() {
        assertEquals(
            listOf(SurfaceShowcaseBanner.SCANNING),
            SurfaceShowcaseBanner.entries.filter { it.spinner },
        )
    }

    @Test
    fun `the banner stops spinning as soon as a surface is found`() {
        val scanning = banner(surfaceFound = false)
        val found = banner(surfaceFound = true)

        assertEquals(SurfaceShowcaseBanner.SCANNING, scanning)
        assertEquals(SurfaceShowcaseBanner.FOUND, found)
        assertFalse(found!!.spinner)
    }

    @Test
    fun `a placed car is what the banner talks about, surface in view or not`() {
        assertEquals(SurfaceShowcaseBanner.PLACED, banner(surfaceFound = true, placed = true))
        assertEquals(SurfaceShowcaseBanner.PLACED, banner(surfaceFound = false, placed = true))
    }

    @Test
    fun `lost tracking wins over every other sentence`() {
        assertEquals(
            SurfaceShowcaseBanner.MOVE_PHONE,
            banner(trackingLost = true, surfaceFound = true, placed = true),
        )
    }

    @Test
    fun `the banner steps aside for the unavailable card and the coaching card`() {
        assertNull(banner(arUnavailable = true, surfaceFound = true, placed = true))
        assertNull(banner(coaching = true, trackingLost = true))
    }

    private fun banner(
        arUnavailable: Boolean = false,
        coaching: Boolean = false,
        trackingLost: Boolean = false,
        surfaceFound: Boolean = false,
        placed: Boolean = false,
    ) = surfaceShowcaseBanner(
        arUnavailable = arUnavailable,
        coaching = coaching,
        trackingLost = trackingLost,
        surfaceFound = surfaceFound,
        placed = placed,
    )
}
