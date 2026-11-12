package io.github.sceneview.demo.demos.internal

import io.github.sceneview.camera.CameraShot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A result that opens on its Photo layer is entered from inside the place, and ends on its framing. */
class ScanOpeningTest {
    private val home = CameraShot(azimuthDegrees = 30f, elevationDegrees = 38f, distance = 8f)

    @Test
    fun `it starts low, close and to the side, and lands on the framing`() {
        val path = ScanOpening.path(home)
        val start = path.keyframes.first().shot
        assertTrue("starts at ${start.elevationDegrees}°", start.elevationDegrees < 15f)
        assertTrue("starts ${start.distance} m away", start.distance < home.distance * 0.7f)
        assertTrue(start.azimuthDegrees != home.azimuthDegrees)
        assertEquals(home.target, start.target)
        assertEquals(home, path.keyframes.last().shot)
        assertEquals(home, path.shotAt(path.durationSeconds))
    }

    @Test
    fun `it rises all the way, never dipping under where it started`() {
        val path = ScanOpening.path(home)
        var last = path.shotAt(0f).elevationDegrees
        for (step in 1..STEPS) {
            val elevation = path.shotAt(path.durationSeconds * step / STEPS).elevationDegrees
            assertTrue("dips at step $step: $elevation after $last", elevation >= last - 0.01f)
            last = elevation
        }
    }

    @Test
    fun `it outlasts the Photo layer's growth, and never starts above a low framing`() {
        assertTrue(ScanOpening.SECONDS * 1_000f > io.github.sceneview.demo.demos.GROW_MS)
        val low = home.copy(elevationDegrees = 5f)
        assertEquals(5f, ScanOpening.path(low).keyframes.first().shot.elevationDegrees, 0f)
    }

    private companion object {
        const val STEPS = 50
    }
}
