package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class AskLiveFrameCaptureTest {
    @Test fun `plane copy respects row padding and pixel stride`() {
        val bytes = byteArrayOf(1, 99, 2, 99, 3, 99, 88, 88, 4, 99, 5, 99, 6)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), copyAskPlane(ByteBuffer.wrap(bytes), 3, 2, 8, 2))
    }

    @Test fun `plane copy respects crop and buffer position without mutating source`() {
        val buffer = ByteBuffer.wrap(byteArrayOf(99, 0, 1, 2, 88, 3, 4, 5, 88, 6, 7, 8))
        buffer.position(1)
        assertArrayEquals(byteArrayOf(4, 5, 7, 8), copyAskPlane(buffer, 2, 2, 4, 1, 1, 1))
        assertEquals(1, buffer.position())
    }

    @Test fun `NV21 contains packed luma followed by VU chroma`() {
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 21, 11, 22, 12),
            askNv21(AskYuvFrame(4, 2, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), byteArrayOf(11, 12), byteArrayOf(21, 22))),
        )
    }

    @Test fun `display rotation produces the right dimensions in each orientation`() {
        assertEquals(640 to 480, askRotatedDimensions(640, 480, 0))
        assertEquals(480 to 640, askRotatedDimensions(640, 480, 90))
        assertEquals(640 to 480, askRotatedDimensions(640, 480, 180))
        assertEquals(480 to 640, askRotatedDimensions(640, 480, -90))
        assertEquals(0, askCameraRotationDegrees(90, 90))
        assertEquals(180, askCameraRotationDegrees(90, 270))
    }

    @Test fun `scaling caps the long side without upscaling`() {
        assertEquals(768 to 432, askScaledDimensions(1920, 1080))
        assertEquals(432 to 768, askScaledDimensions(1080, 1920))
        assertEquals(320 to 240, askScaledDimensions(320, 240))
        assertEquals(768 to 768, askScaledDimensions(1024, 1024))
        assertEquals(1 to 768, askScaledDimensions(1, 4000))
    }
}
