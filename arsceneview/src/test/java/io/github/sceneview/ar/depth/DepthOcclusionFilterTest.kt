package io.github.sceneview.ar.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DepthOcclusionFilterTest {

    private fun image(vararg mm: Int, padding: Int = 0): ByteBuffer {
        val buffer = ByteBuffer.allocate(mm.size * 2 + padding).order(ByteOrder.LITTLE_ENDIAN)
        mm.forEach { buffer.putShort(it.toShort()) }
        buffer.rewind()
        return buffer
    }

    private fun DepthOcclusionFilter.feed(t: Long, vararg mm: Int): Boolean =
        update(t, image(*mm), width = mm.size, height = 1, rowStrideBytes = mm.size * 2)

    private fun DepthOcclusionFilter.out(i: Int = 0): Int = output.getShort(i * 2).toInt() and 0xFFFF

    @Test
    fun `the first image is taken as is`() {
        val filter = DepthOcclusionFilter()
        assertTrue(filter.feed(1, 1000, 0, 2500))
        assertEquals(1000, filter.out(0))
        assertEquals(DepthOcclusionFilter.NO_DEPTH_MM, filter.out(1))
        assertEquals(2500, filter.out(2))
    }

    @Test
    fun `the same timestamp is not uploaded again and keeps the last depth`() {
        val filter = DepthOcclusionFilter()
        filter.feed(1, 1000)
        assertFalse(filter.feed(1, 0))
        assertEquals(1000, filter.out())
    }

    @Test
    fun `small changes are averaged`() {
        val filter = DepthOcclusionFilter(alpha = 0.5f)
        filter.feed(1, 1000)
        filter.feed(2, 1100)
        assertEquals(1050, filter.out())
    }

    @Test
    fun `a single swing to the background is ignored`() {
        // The white mullion of O1: 400 mm, one image at 5 m, back to 400 mm.
        val filter = DepthOcclusionFilter()
        filter.feed(1, 400)
        filter.feed(2, 5000)
        assertEquals(400, filter.out())
        filter.feed(3, 400)
        assertEquals(400, filter.out())
    }

    @Test
    fun `a swing that alternates every image never reaches the output`() {
        val filter = DepthOcclusionFilter()
        filter.feed(1, 400)
        for (t in 2L..20L) {
            filter.feed(t, if (t % 2 == 0L) 5000 else 400)
            assertEquals("image $t", 400, filter.out())
        }
    }

    @Test
    fun `a confirmed jump lands one image later, in both directions`() {
        val filter = DepthOcclusionFilter()
        filter.feed(1, 3000)
        filter.feed(2, 500)
        assertEquals(3000, filter.out())
        filter.feed(3, 500)
        assertEquals(500, filter.out())
        filter.feed(4, 3000)
        filter.feed(5, 3000)
        assertEquals(3000, filter.out())
    }

    @Test
    fun `a hole keeps the last depth for holdFrames images, then is released`() {
        val filter = DepthOcclusionFilter(holdFrames = 2)
        filter.feed(1, 1200)
        filter.feed(2, 0)
        assertEquals(1200, filter.out())
        filter.feed(3, 0)
        assertEquals(1200, filter.out())
        filter.feed(4, 0)
        assertEquals(DepthOcclusionFilter.NO_DEPTH_MM, filter.out())
        assertEquals(1f, filter.holeFraction, 0f)
    }

    @Test
    fun `depth after a hole is taken as is`() {
        val filter = DepthOcclusionFilter(holdFrames = 0)
        filter.feed(1, 1200)
        filter.feed(2, 0)
        filter.feed(3, 4000)
        assertEquals(4000, filter.out())
    }

    @Test
    fun `row padding is skipped`() {
        val filter = DepthOcclusionFilter()
        // 2x2 image, 6-byte rows (2 bytes of padding per row).
        val buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0, 100).putShort(2, 200).putShort(4, 9999)
        buffer.putShort(6, 300).putShort(8, 400).putShort(10, 9999)
        filter.update(1, buffer, width = 2, height = 2, rowStrideBytes = 6)
        assertEquals(listOf(100, 200, 300, 400), (0..3).map { filter.out(it) })
        assertEquals(0, buffer.position())
    }

    @Test
    fun `depths above 32 m survive the unsigned round trip`() {
        val filter = DepthOcclusionFilter()
        filter.feed(1, 40000)
        assertEquals(40000, filter.out())
    }

    @Test
    fun `a measured 65535 mm stays distinct from no depth only through holeFraction`() {
        val filter = DepthOcclusionFilter()
        filter.feed(1, 65535, 0)
        assertEquals(65534, filter.out(0))
        assertEquals(0.5f, filter.holeFraction, 0f)
    }

    @Test
    fun `a resolution change starts over`() {
        val filter = DepthOcclusionFilter()
        filter.feed(1, 1000)
        filter.feed(2, 3000, 3000)
        assertEquals(3000, filter.out(0))
        assertEquals(2, filter.width)
    }
}
