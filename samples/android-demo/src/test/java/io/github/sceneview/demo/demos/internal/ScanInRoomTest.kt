package io.github.sceneview.demo.demos.internal

import io.github.sceneview.ar.PlacementPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the `ar-splat-room` status pill (#4023, #4075). The demo itself needs ARCore, which the
 * emulator cannot run (#2754), so what the pill says is checked here instead.
 */
class ScanInRoomTest {

    @Test
    fun `the scale readout is a percentage of real size`() {
        assertEquals(100, realSizePercent(1f))
        assertEquals(25, realSizePercent(0.25f))
        assertEquals(400, realSizePercent(4f))
        assertEquals(133, realSizePercent(1.333f))
    }

    private fun status(
        phase: PlacementPhase,
        scanReady: Boolean = true,
        cardShown: Boolean = false,
        coaching: Boolean = false,
        invalidMove: Boolean = false,
        showGestureHint: Boolean = false,
        lowLight: Boolean = false,
    ) = scanRoomStatus(phase, scanReady, cardShown, coaching, invalidMove, showGestureHint, lowLight)

    @Test
    fun `an unopened scan says so before anything else`() {
        for (phase in PlacementPhase.values()) {
            assertEquals(
                ScanRoomStatus.OpeningScan,
                status(phase, scanReady = false, coaching = true, cardShown = true),
            )
        }
    }

    @Test
    fun `an action card or the SDK coaching keep the pill quiet`() {
        assertNull(status(PlacementPhase.NO_SURFACE, cardShown = true))
        assertNull(status(PlacementPhase.SCANNING, coaching = true))
        assertNull(status(PlacementPhase.TRACKING_LOST, coaching = true))
    }

    @Test
    fun `darkness waits for the coaching too - its sentence already asks for light`() {
        assertNull(status(PlacementPhase.TRACKING_LOST, coaching = true, lowLight = true))
        assertEquals(
            ScanRoomStatus.TrackingPausedLowLight,
            status(PlacementPhase.TRACKING_LOST, lowLight = true),
        )
    }

    @Test
    fun `a rejected move beats the phase message`() {
        assertEquals(ScanRoomStatus.KeepOnSurface, status(PlacementPhase.ADJUSTING, invalidMove = true))
    }

    @Test
    fun `each phase maps to its message`() {
        assertEquals(ScanRoomStatus.MoveSlowly, status(PlacementPhase.SCANNING))
        assertEquals(ScanRoomStatus.TrackingPaused, status(PlacementPhase.TRACKING_LOST))
        assertEquals(ScanRoomStatus.FindingPlacement, status(PlacementPhase.RECOVERING))
        assertEquals(ScanRoomStatus.Scale, status(PlacementPhase.ADJUSTING))
        assertEquals(ScanRoomStatus.GestureHint, status(PlacementPhase.PLACED, showGestureHint = true))
        assertNull(status(PlacementPhase.PLACED))
        assertNull(status(PlacementPhase.INITIALIZING))
        assertNull(status(PlacementPhase.CAMERA_ERROR))
    }
}
