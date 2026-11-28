package io.github.sceneview.ar.scene

import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import io.github.sceneview.ar.camera.CameraStreamCoordinates
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaneDepthUvMappingTest {
    private val off = Config.ImageStabilizationMode.OFF
    private val eis = Config.ImageStabilizationMode.EIS

    @Test fun `display rotation maps plane depth to the same cropped texel as the camera`() {
        val coordinates = coordinates()
        for (turns in 0..3) {
            fun rotate(u: Float, v: Float): Pair<Float, Float> = when (turns) {
                0 -> u to v
                1 -> v to 1f - u
                2 -> 1f - u to 1f - v
                else -> 1f - v to u
            }
            coordinates.update(off, displayGeometryChanged = true,
                transform3d = { _, _, _ -> error("EIS is off") },
                transform2d = { type, input, output ->
                    assertEquals(Coordinates2d.VIEW_NORMALIZED, type)
                    repeat(3) {
                        val (u, v) = rotate(input.get(), input.get())
                        output.put(.1f + .8f * u).put(.2f + .6f * v)
                    }
                },
            )
            // Noncentral screen pixel, converted to Android view coordinates before rotation.
            val (u, v) = rotate(.7f, .25f)
            assertUv(coordinates, off, .4f, .5f, .1f + .8f * u, .2f + .6f * v)
        }
    }

    @Test fun `unrotated mapping has no extra vertical flip`() {
        val coordinates = coordinates()
        coordinates.imageUvs.put(floatArrayOf(0f, 1f, 2f, 1f, 0f, -1f)).rewind()
        assertUv(coordinates, off, -1f, -1f, 0f, 0f)
        assertUv(coordinates, off, 0.2f, -0.6f, 0.6f, 0.2f)
    }

    @Test fun `rotated mapping retains display crop and orientation`() {
        val coordinates = coordinates()
        // Texture coordinates before Filament's UV0 flip: u = .5 - .4*y, v = .5 + .3*x.
        coordinates.imageUvs.put(floatArrayOf(.9f, .8f, .9f, -.4f, -.7f, .8f)).rewind()
        assertUv(coordinates, off, 0.4f, -0.5f, .7f, .62f)
    }

    @Test fun `EIS uses transformed geometry and divides after interpolation`() {
        val coordinates = coordinates()
        coordinates.positions.put(floatArrayOf(-.8f, -.9f, 1f, 3.2f, -.7f, 1f, -.6f, 3.1f, 1f)).rewind()
        coordinates.cameraUvs.put(floatArrayOf(0f, 0f, 1f, 2f, 0f, 2f, 0f, 4f, 1f)).rewind()
        // Barycentric weights (.2, .3, .5): interpolate numerator AND denominator first.
        assertUv(coordinates, eis, .5f, 1.16f, .6f / 1.3f, 2f / 1.3f)
    }

    @Test fun `switching EIS off uses physical image UVs again`() {
        val coordinates = coordinates()
        coordinates.cameraUvs.put(floatArrayOf(1f, 1f, 2f, 3f, 1f, 2f, 1f, 3f, 2f)).rewind()
        coordinates.imageUvs.put(floatArrayOf(0f, 1f, 2f, 1f, 0f, -1f)).rewind()
        assertUv(coordinates, eis, -1f, -1f, .5f, .5f)
        assertUv(coordinates, off, -1f, -1f, 0f, 0f)
    }

    private fun coordinates() = CameraStreamCoordinates(floatArrayOf(
        -1f, -1f, 1f, 3f, -1f, 1f, -1f, 3f, 1f,
    ))

    private fun assertUv(
        coordinates: CameraStreamCoordinates, mode: Config.ImageStabilizationMode,
        x: Float, y: Float, u: Float, v: Float,
    ) {
        val rows = FloatArray(9)
        planeDepthUvRows(coordinates, mode, rows)
        fun row(i: Int) = rows[i * 3] * x + rows[i * 3 + 1] * y + rows[i * 3 + 2]
        assertEquals(u, row(0) / row(2), 0.00001f)
        assertEquals(v, row(1) / row(2), 0.00001f)
    }
}
