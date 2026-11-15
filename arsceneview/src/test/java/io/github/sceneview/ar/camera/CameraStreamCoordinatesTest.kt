package io.github.sceneview.ar.camera

import com.google.ar.core.Coordinates3d
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer

class CameraStreamCoordinatesTest {
    private val vertices = floatArrayOf(-1f, 1f, 1f, -1f, -3f, 1f, 3f, 1f, 1f)
    private val ndc = floatArrayOf(-1f, 1f, -1f, -3f, 3f, 1f)

    @Test
    fun `off uses the existing full screen triangle and vertically flipped UVs`() {
        val coordinates = CameraStreamCoordinates(vertices)
        update(coordinates)

        assertArrayEquals(vertices, coordinates.positions.values(), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 2f, 2f, 0f), coordinates.imageUvs.values(), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 1f, 0f, -1f, 1f, 2f, 1f, 1f), coordinates.cameraUvs.values(), 0f)
    }

    @Test
    fun `EIS screen Z never moves the uploaded camera background off the far plane`() {
        val coordinates = CameraStreamCoordinates(vertices)
        for (screenZ in listOf(0f, 0.25f, -0.5f, 0f)) {
            coordinates.update(
                transform3d = { _, type, output ->
                    if (type == Coordinates3d.EIS_NORMALIZED_DEVICE_COORDINATES) {
                        output.put(floatArrayOf(-0.8f, 0.9f, screenZ, -0.9f, -2.8f, screenZ, 3.1f, 1.2f, screenZ))
                    } else {
                        output.put(floatArrayOf(0.4f, 0.3f, 2f, 0.8f, 1.4f, 3f, 1.8f, 0.6f, 4f))
                    }
                },
                transform2d = { _, output -> output.put(FloatArray(6)) },
            )
            // Device-domain Filament shaders take raster depth directly from POSITION.
            // Assert the actual upload: XY must retain EIS compensation, Z must stay at 1.
            assertArrayEquals(
                floatArrayOf(-0.8f, 0.9f, 1f, -0.9f, -2.8f, 1f, 3.1f, 1.2f, 1f),
                coordinates.positions.cameraStreamUploadBuffer().values(),
                0f,
            )
            // The independent homogeneous texture denominator must retain its ARCore value.
            assertArrayEquals(
                floatArrayOf(0.4f, 0.3f, 2f, 0.8f, 1.4f, 3f, 1.8f, 0.6f, 4f),
                coordinates.cameraUvs.cameraStreamUploadBuffer().values(),
                0f,
            )
        }
    }

    @Test
    fun `successive frames and on off toggles replace both transforms without a display change`() {
        val coordinates = CameraStreamCoordinates(vertices)
        for (shift in listOf(0f, 0.1f, 0.2f, 0f)) {
            val calls = mutableListOf<Coordinates3d>()
            update(coordinates, shift, calls)
            assertEquals(
                listOf(Coordinates3d.EIS_NORMALIZED_DEVICE_COORDINATES, Coordinates3d.EIS_TEXTURE_NORMALIZED),
                calls,
            )
            assertEquals(-1f + shift, coordinates.positions[0], 0f)
            assertEquals(shift, coordinates.cameraUvs[0], 0f)
            // Unstabilized image UVs must follow the warped screen position, not the input mesh.
            assertEquals(shift / 2f, coordinates.imageUvs[0], 0.000001f)
            for (buffer in listOf(coordinates.positions, coordinates.cameraUvs, coordinates.imageUvs)) {
                assertTrue(buffer.isDirect)
                assertEquals(0, buffer.position())
            }
        }
    }

    @Test
    fun `an in flight upload remains unchanged when the next frame overwrites the coordinates`() {
        val coordinates = CameraStreamCoordinates(vertices)
        update(coordinates)
        val positions = coordinates.positions.cameraStreamUploadBuffer()
        val cameraUvs = coordinates.cameraUvs.cameraStreamUploadBuffer()
        val imageUvs = coordinates.imageUvs.cameraStreamUploadBuffer()
        update(coordinates, shift = 0.2f)

        assertArrayEquals(ndc, positions.xy(), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 1f, 0f, -1f, 1f, 2f, 1f, 1f), cameraUvs.values(), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 2f, 2f, 0f), imageUvs.values(), 0f)
        for (buffer in listOf(positions, cameraUvs, imageUvs)) {
            assertTrue(buffer.isDirect)
            assertEquals(0, buffer.position())
        }
    }

    @Test
    fun `camera UVs retain their GPU orientation and perspective denominator`() {
        val coordinates = CameraStreamCoordinates(vertices)
        coordinates.update(
            transform3d = { _, type, output ->
                output.put(if (type == Coordinates3d.EIS_TEXTURE_NORMALIZED) {
                    floatArrayOf(0.4f, 0.3f, 2f, 0.8f, 1.4f, 3f, 1.8f, 0.6f, 4f)
                } else vertices)
            },
            transform2d = { _, output -> output.put(FloatArray(6)) },
        )
        assertArrayEquals(
            floatArrayOf(0.4f, 0.3f, 2f, 0.8f, 1.4f, 3f, 1.8f, 0.6f, 4f),
            coordinates.cameraUvs.values(),
            0.000001f,
        )
        // At the midpoint, division after interpolation differs from interpolating divided UVs.
        val u = (coordinates.cameraUvs[0] + coordinates.cameraUvs[3]) /
            (coordinates.cameraUvs[2] + coordinates.cameraUvs[5])
        assertEquals(0.24f, u, 0.000001f)
    }

    private fun update(
        coordinates: CameraStreamCoordinates,
        shift: Float = 0f,
        calls: MutableList<Coordinates3d> = mutableListOf(),
    ) {
        coordinates.update(
            transform3d = { input, type, output ->
                assertTrue(input.isDirect)
                assertTrue(output.isDirect)
                assertArrayEquals(ndc, input.values(), 0f)
                calls += type
                for (i in 0 until 3) {
                    val x = input.get()
                    val y = input.get()
                    if (type == Coordinates3d.EIS_NORMALIZED_DEVICE_COORDINATES) {
                        output.put(x + shift).put(y).put(0f)
                    } else {
                        output.put((x + 1f) / 2f + shift).put((y + 1f) / 2f).put(1f)
                    }
                }
            },
            transform2d = { input, output ->
                for (i in 0 until 3) {
                    output.put((input.get() + 1f) / 2f).put((input.get() + 1f) / 2f)
                }
            },
        )
    }

    private fun FloatBuffer.values() = FloatArray(capacity()) { get(it) }
    private fun FloatBuffer.xy() = FloatArray(6) { get(it / 2 * 3 + it % 2) }
}
