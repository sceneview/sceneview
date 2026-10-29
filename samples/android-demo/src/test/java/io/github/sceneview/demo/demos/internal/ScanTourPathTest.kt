package io.github.sceneview.demo.demos.internal

import io.github.sceneview.camera.CameraShot
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scan result's tour (#4719): a lap with no seam, entered from rest at the framing. */
class ScanTourPathTest {
    private val home = CameraShot(
        target = Position(0.4f, 0.8f, -0.2f),
        azimuthDegrees = 30f,
        elevationDegrees = 35f,
        distance = 6f,
    )
    private val tour = ScanTourPath.of(home, extent = 5f)

    @Test
    fun `the lap ends where it starts, a full turn round`() {
        val start = tour.lap.start!!
        val end = tour.lap.end!!
        assertEquals(start.azimuthDegrees + 360f, end.azimuthDegrees, 1e-3f)
        assertEquals(start.elevationDegrees, end.elevationDegrees, 1e-3f)
        assertEquals(start.distance, end.distance, 1e-3f)
        assertEquals(start.target, end.target)
        assertEquals(ScanTourPath.LAP_SECONDS, tour.lap.durationSeconds, 1e-3f)
        assertTrue(tour.lap.loop)
    }

    @Test
    fun `the lead-in leaves the framing and lands on the lap's first shot`() {
        assertEquals(home, tour.leadIn.start)
        assertEquals(tour.lap.start, tour.leadIn.end)
        assertEquals(ScanTourPath.leadInSeconds, tour.leadIn.durationSeconds, 1e-3f)
    }

    @Test
    fun `the lead-in starts at rest and arrives at full pace`() {
        val e = ScanTourPath::easeIn
        assertEquals(0f, e(0f), 0f)
        assertEquals(1f, e(1f), 1e-6f)
        val h = 1e-3f
        assertEquals("slope at 0", 0f, (e(h) - e(0f)) / h, 0.01f)
        assertEquals("slope at 1", 1f, (e(1f) - e(1f - h)) / h, 0.01f)
    }

    @Test
    fun `the camera never flies under the floor nor straight down`() {
        var t = 0f
        while (t <= ScanTourPath.LAP_SECONDS) {
            val shot = tour.lap.shotAt(t)
            assertTrue("elevation ${shot.elevationDegrees} at $t s", shot.elevationDegrees in 7f..69f)
            assertTrue("distance ${shot.distance} at $t s", shot.distance > 0f)
            t += 0.25f
        }
    }

    @Test
    fun `the azimuth only ever turns one way`() {
        var previous = tour.leadIn.shotAt(0f).azimuthDegrees
        var t = 0.1f
        while (t <= ScanTourPath.leadInSeconds + ScanTourPath.LAP_SECONDS * 2) {
            val shot = if (t < ScanTourPath.leadInSeconds) {
                tour.leadIn.shotAt(t)
            } else {
                tour.lap.shotAt(t - ScanTourPath.leadInSeconds)
            }
            assertTrue("turned back at $t s", shot.azimuthDegrees >= previous - 1e-3f)
            previous = shot.azimuthDegrees
            t += 0.1f
        }
    }
}
