package io.github.sceneview.gesture

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class NodeEditingOverlayRingTest {
    @Test
    fun `orthographic circle keeps its width and height through a half turn`() {
        val pitch = Math.toRadians(65.0)
        for (yaw in 0..180 step 15) {
            val (radiusX, radiusY) = radii(yaw, pitch, perspective = false)

            assertEquals("width at yaw $yaw", SCREEN_SCALE, radiusX, SCREEN_SCALE * 0.05f)
            val expectedY = SCREEN_SCALE * sin(pitch).toFloat()
            assertEquals("height at yaw $yaw", expectedY, radiusY, expectedY * 0.05f)
        }
    }

    @Test
    fun `Hero camera ring never falls below half its rest width`() {
        val pitch = Math.toRadians(25.0)
        val restWidth = radii(-22, pitch, perspective = true).first * 2f
        for (yaw in 0..180 step 15) {
            val width = radii(yaw, pitch, perspective = true).first * 2f

            assertTrue("width $width at yaw $yaw, rest $restWidth", width >= restWidth * 0.5f)
        }
    }

    @Test
    fun `extent combines both chords even when one screen span is zero`() {
        assertEquals(5f, projectedRingRadius(13f, 7f, 24f, 16f), 0.0001f)
        assertEquals(4f, projectedRingRadius(10f, 10f, 24f, 16f), 0.0001f)
        assertEquals(0f, projectedRingRadius(10f, 10f, 20f, 20f), 0f)
    }

    private fun radii(yaw: Int, pitch: Double, perspective: Boolean): Pair<Float, Float> {
        val angle = Math.toRadians(yaw.toDouble())
        // Rotate the unit footprint about Y, then project from a camera at azimuth 32°.
        val xPos = project(cos(angle), -sin(angle), pitch, perspective)
        val xNeg = project(-cos(angle), sin(angle), pitch, perspective)
        val zPos = project(sin(angle), cos(angle), pitch, perspective)
        val zNeg = project(-sin(angle), -cos(angle), pitch, perspective)
        return projectedRingRadius(xPos.x, xNeg.x, zPos.x, zNeg.x) to
            projectedRingRadius(xPos.y, xNeg.y, zPos.y, zNeg.y)
    }

    private fun project(x: Double, z: Double, pitch: Double, perspective: Boolean): Offset {
        val azimuth = Math.toRadians(32.0)
        val horizontal = cos(azimuth) * x - sin(azimuth) * z
        val depth = sin(azimuth) * x + cos(azimuth) * z
        val vertical = -sin(pitch) * depth
        // Pinhole camera looking at the footprint centre from four footprint radii away.
        val scale = if (perspective) 4.0 / (4.0 - cos(pitch) * depth) else 1.0
        return Offset(
            320f + (SCREEN_SCALE * horizontal * scale).toFloat(),
            240f - (SCREEN_SCALE * vertical * scale).toFloat(),
        )
    }

    private companion object {
        const val SCREEN_SCALE = 100f
    }
}
