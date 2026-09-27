package io.github.sceneview.demo.demos.internal

import io.github.sceneview.ar.PlacementPhase
import io.github.sceneview.core.splat.SplatCloud
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `ar-splat-room` placement arithmetic (#4023). The demo itself needs ARCore, which the
 * emulator cannot run (#2754), so the scan's footing, its measured size and the status pill are
 * checked here instead.
 */
class ScanInRoomTest {

    private fun cloud(points: List<Triple<Float, Float, Float>>): SplatCloud {
        val n = points.size
        return SplatCloud(
            count = n,
            positions = FloatArray(n * 3) { i ->
                val p = points[i / 3]
                when (i % 3) { 0 -> p.first; 1 -> p.second; else -> p.third }
            },
            scales = FloatArray(n * 3) { 0.01f },
            rotations = FloatArray(n * 4) { if (it % 4 == 3) 1f else 0f },
            colors = FloatArray(n * 3) { 0.5f },
            opacities = FloatArray(n) { 1f },
        )
    }

    /**
     * A 0.6 m column standing on a 0.8 m square of ground at y = -0.2, off-centre at (0.1, -0.05),
     * with a handful of floaters far below and above: the shape of a real phone capture.
     */
    private fun capture(): SplatCloud {
        val points = mutableListOf<Triple<Float, Float, Float>>()
        // Ground: a 21 x 21 grid, 0.8 m wide, centred on the subject.
        for (i in 0..20) for (k in 0..20) {
            points += Triple(0.1f - 0.4f + i * 0.04f, -0.2f, -0.05f - 0.4f + k * 0.04f)
        }
        // Subject: a 0.2 m wide column from the ground up to y = 0.4.
        for (j in 0..60) for (i in 0..4) for (k in 0..4) {
            points += Triple(0.1f - 0.1f + i * 0.05f, -0.2f + j * 0.01f, -0.05f - 0.1f + k * 0.05f)
        }
        // Floaters: 1 % of the points, well away from the subject.
        repeat(20) { points += Triple(0.1f, -1.5f, -0.05f) }
        repeat(20) { points += Triple(0.1f, 2.0f, -0.05f) }
        return cloud(points)
    }

    @Test
    fun `the ground point is the subject's centre on the captured ground, not the lowest floater`() {
        val footprint = scanFootprint(capture())
        assertEquals(0.1f, footprint.groundPoint.x, 1e-3f)
        assertEquals(-0.05f, footprint.groundPoint.z, 1e-3f)
        assertEquals(-0.2f, footprint.groundPoint.y, 1e-3f)
    }

    @Test
    fun `the height runs from the ground to the top of the subject, ignoring floaters above`() {
        val footprint = scanFootprint(capture())
        // The column tops out at 0.4, 0.6 m above the ground; the 98th percentile lands a few
        // centimetres under it, and nowhere near the floaters at y = 2.
        assertEquals(0.6f, footprint.height, 0.05f)
    }

    @Test
    fun `the spread covers more than the subject and less than the whole ground patch`() {
        val footprint = scanFootprint(capture())
        // The 5 % tails of x and z are the far edges of the grass, trimmed away.
        assertTrue("width ${footprint.width}", footprint.width > 0.2f && footprint.width < 0.8f)
        assertEquals(footprint.width, footprint.depth, 1e-3f)
    }

    @Test
    fun `placing the cloud at minus the ground point stands it on the anchor`() {
        val footprint = scanFootprint(capture())
        val offset = -footprint.groundPoint
        // The ground ends up at the anchor's height and the subject straight above it.
        assertEquals(0f, -0.2f + offset.y, 1e-3f)
        assertEquals(0f, 0.1f + offset.x, 1e-3f)
        assertEquals(0f, -0.05f + offset.z, 1e-3f)
    }

    @Test
    fun `a single point is its own footprint`() {
        val footprint = scanFootprint(cloud(listOf(Triple(1f, 2f, 3f))))
        assertEquals(1f, footprint.groundPoint.x, 0f)
        assertEquals(2f, footprint.groundPoint.y, 0f)
        assertEquals(3f, footprint.groundPoint.z, 0f)
        assertEquals(0f, footprint.height, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty scan is rejected`() {
        scanFootprint(cloud(emptyList()))
    }

    @Test
    fun `the height label rounds to 5 cm and never says zero`() {
        fun cm(height: Float) = scanHeightCentimetres(ScanFootprint(Position(), height, 0f, 0f))
        assertEquals(60, cm(0.61f))
        assertEquals(65, cm(0.63f))
        assertEquals(5, cm(0.001f))
        assertEquals(150, cm(1.49f))
    }

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
    fun `darkness is still worth saying over the coaching`() {
        assertEquals(
            ScanRoomStatus.TrackingPausedLowLight,
            status(PlacementPhase.TRACKING_LOST, coaching = true, lowLight = true),
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
