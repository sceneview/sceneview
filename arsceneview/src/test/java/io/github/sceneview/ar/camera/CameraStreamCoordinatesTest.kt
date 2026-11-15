package io.github.sceneview.ar.camera

import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Coordinates3d
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer

class CameraStreamCoordinatesTest {
    private val vertices = floatArrayOf(-1f, 1f, 1f, -1f, -3f, 1f, 3f, 1f, 1f)
    private val ndc = floatArrayOf(-1f, 1f, -1f, -3f, 3f, 1f)

    @Test
    fun `OFF is bit identical to main and never requests EIS coordinates`() {
        val coordinates = CameraStreamCoordinates(vertices)
        val viewUvs = floatArrayOf(0f, 0f, 0f, 2f, 2f, 0f)
        // Include values whose second Y flip would lose precision.
        val transformed = floatArrayOf(0.125f, 0.00000001f, -0.2f, 0.3f, 1.2f, 1.0000001f)
        val mainUvs = transformed.copyOf().apply {
            for (i in 1 until size step 2) this[i] = 1f - this[i]
        }
        assertTrue(coordinates.update(
            mode = Config.ImageStabilizationMode.OFF,
            displayGeometryChanged = false,
            transform3d = { _, _, _ -> error("OFF must not request EIS coordinates") },
            transform2d = { type, input, output ->
                assertEquals(Coordinates2d.VIEW_NORMALIZED, type)
                assertArrayEquals(viewUvs, input.values(), 0f)
                output.put(transformed)
            },
        ))
        assertArrayEquals(vertices.map { it.toRawBits() }.toIntArray(),
            coordinates.positions.values().map { it.toRawBits() }.toIntArray())
        assertArrayEquals(mainUvs.map { it.toRawBits() }.toIntArray(),
            coordinates.imageUvs.values().map { it.toRawBits() }.toIntArray())

        assertFalse(coordinates.update(
            mode = Config.ImageStabilizationMode.OFF,
            displayGeometryChanged = false,
            transform3d = { _, _, _ -> error("Unexpected 3D transform") },
            transform2d = { _, _, _ -> error("OFF must cache unchanged display geometry") },
        ))
    }

    @Test
    fun `OFF recomputes legacy UVs on rotation and restores the triangle after EIS`() {
        val coordinates = CameraStreamCoordinates(vertices)
        update(coordinates, shift = 0.2f)
        var transforms = 0
        fun off(displayChanged: Boolean): Boolean = coordinates.update(
            mode = Config.ImageStabilizationMode.OFF,
            displayGeometryChanged = displayChanged,
            transform3d = { _, _, _ -> error("OFF must not request EIS coordinates") },
            transform2d = { type, input, output ->
                assertEquals(Coordinates2d.VIEW_NORMALIZED, type)
                transforms++
                output.put(input)
            },
        )
        assertTrue(off(false))
        assertArrayEquals(vertices, coordinates.positions.values(), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f, -1f, 2f, 1f), coordinates.imageUvs.values(), 0f)
        assertFalse(off(false))
        assertTrue(off(true))
        assertEquals(2, transforms)
        update(coordinates, shift = 0.3f)
        assertEquals(-0.7f, coordinates.positions[0], 0f)
        assertTrue(off(false))
        assertArrayEquals(vertices, coordinates.positions.values(), 0f)
    }

    @Test
    fun `EIS screen Z never moves the uploaded camera background off the far plane`() {
        val coordinates = CameraStreamCoordinates(vertices)
        for (screenZ in listOf(0f, 0.25f, -0.5f, 0f)) {
            coordinates.update(
                mode = Config.ImageStabilizationMode.EIS,
                displayGeometryChanged = false,
                transform3d = { _, type, output ->
                    if (type == Coordinates3d.EIS_NORMALIZED_DEVICE_COORDINATES) {
                        output.put(floatArrayOf(-0.8f, 0.9f, screenZ, -0.9f, -2.8f, screenZ, 3.1f, 1.2f, screenZ))
                    } else {
                        output.put(floatArrayOf(0.4f, 0.3f, 2f, 0.8f, 1.4f, 3f, 1.8f, 0.6f, 4f))
                    }
                },
                transform2d = { _, _, output -> output.put(FloatArray(6)) },
            )
            val upload = requireNotNull(CameraStreamUploadRing(3).acquire()).apply { copyFrom(coordinates) }
            // Device-domain Filament shaders take raster depth directly from POSITION.
            // Assert the actual upload: XY must retain EIS compensation, Z must stay at 1.
            assertArrayEquals(
                floatArrayOf(-0.8f, 0.9f, 1f, -0.9f, -2.8f, 1f, 3.1f, 1.2f, 1f),
                upload.positions.values(),
                0f,
            )
            // The independent homogeneous texture denominator must retain its ARCore value.
            assertArrayEquals(
                floatArrayOf(0.4f, 0.3f, 2f, 0.8f, 1.4f, 3f, 1.8f, 0.6f, 4f),
                upload.cameraUvs.values(),
                0f,
            )
        }
    }

    @Test
    fun `successive EIS frames replace both transforms without a display change`() {
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
    fun `ring never overwrites an in flight upload and waits for all three callbacks`() {
        val coordinates = CameraStreamCoordinates(vertices)
        val ring = CameraStreamUploadRing(3)
        update(coordinates)
        val first = requireNotNull(ring.acquire()).apply { copyFrom(coordinates) }
        val second = requireNotNull(ring.acquire()).apply { copyFrom(coordinates) }
        val third = requireNotNull(ring.acquire()).apply { copyFrom(coordinates) }
        assertNull(ring.acquire())
        update(coordinates, shift = 0.2f)
        first.onConsumed.run()
        first.onConsumed.run()
        assertNull(ring.acquire())
        assertArrayEquals(ndc, first.positions.xy(), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 1f, 0f, -1f, 1f, 2f, 1f, 1f), first.cameraUvs.values(), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 2f, 2f, 0f), first.imageUvs.values(), 0f)
        first.onConsumed.run()
        assertSame(first, ring.acquire())
        first.copyFrom(coordinates)
        assertEquals(-0.8f, first.positions[0], 0f)
        assertArrayEquals(ndc, second.positions.xy(), 0f)
        assertArrayEquals(ndc, third.positions.xy(), 0f)
        for (buffer in listOf(first.positions, first.cameraUvs, first.imageUvs)) {
            assertTrue(buffer.isDirect)
            assertEquals(0, buffer.position())
        }
    }

    @Test
    fun `ring reuses the same direct buffers over hundreds of completed frames`() {
        val ring = CameraStreamUploadRing(3)
        val coordinates = CameraStreamCoordinates(vertices)
        val slots = List(3) { requireNotNull(ring.acquire()) }
        val buffers = slots.map { listOf(it.positions, it.imageUvs, it.cameraUvs) }
        slots.forEach { it.cancel() }
        repeat(300) { frame ->
            update(coordinates, shift = frame / 1000f)
            val slot = requireNotNull(ring.acquire())
            val index = slots.indexOfFirst { it === slot }
            assertTrue(index >= 0)
            assertSame(buffers[index][0], slot.positions)
            assertSame(buffers[index][1], slot.imageUvs)
            assertSame(buffers[index][2], slot.cameraUvs)
            slot.copyFrom(coordinates)
            assertArrayEquals(coordinates.positions.values(), slot.positions.values(), 0f)
            repeat(3) { slot.onConsumed.run() }
        }
    }

    @Test
    fun `camera UVs retain their GPU orientation and perspective denominator`() {
        val coordinates = CameraStreamCoordinates(vertices)
        coordinates.update(
            mode = Config.ImageStabilizationMode.EIS,
            displayGeometryChanged = false,
            transform3d = { _, type, output ->
                output.put(if (type == Coordinates3d.EIS_TEXTURE_NORMALIZED) {
                    floatArrayOf(0.4f, 0.3f, 2f, 0.8f, 1.4f, 3f, 1.8f, 0.6f, 4f)
                } else vertices)
            },
            transform2d = { _, _, output -> output.put(FloatArray(6)) },
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
            mode = Config.ImageStabilizationMode.EIS,
            displayGeometryChanged = false,
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
            transform2d = { type, input, output ->
                assertEquals(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, type)
                for (i in 0 until 3) {
                    output.put((input.get() + 1f) / 2f).put((input.get() + 1f) / 2f)
                }
            },
        )
    }

    private fun FloatBuffer.values() = FloatArray(capacity()) { get(it) }
    private fun FloatBuffer.xy() = FloatArray(6) { get(it / 2 * 3 + it % 2) }
}
