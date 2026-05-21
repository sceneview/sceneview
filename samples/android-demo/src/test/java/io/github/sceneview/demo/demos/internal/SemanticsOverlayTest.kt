package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * JVM unit tests for [SemanticsOverlay]. ARCore's `Image` is not mockable on the JVM, so the
 * row-stride compaction is fed a plain [ByteBuffer] shaped like an ARCore `R8` semantic image
 * (one byte per pixel, a label ordinal, row-strided) instead.
 *
 * Pins the contract that backs the `ARSceneSemanticsDemo` GPU overlay (#1868): the packed
 * buffer must be tightly-packed `width * height` bytes regardless of input stride, and the
 * 12-class colour palette / label tables must stay the right size.
 */
class SemanticsOverlayTest {

    // ── stripRowStride ─────────────────────────────────────────────────────

    @Test
    fun `stripRowStride compacts a strided buffer to width times height bytes`() {
        val width = 3
        val height = 2
        val rowStride = 5 // 2 padding bytes per row
        val source = ByteBuffer.allocateDirect(rowStride * height)
        // Row 0: ordinals 0,1,2 then padding; Row 1: ordinals 3,4,5 then padding.
        for (y in 0 until height) {
            for (x in 0 until width) {
                source.put(y * rowStride + x, (y * width + x).toByte())
            }
        }
        val packed = SemanticsOverlay.stripRowStride(source, width, height, rowStride)

        assertEquals(width * height, packed.remaining())
        for (i in 0 until width * height) {
            assertEquals("packed[$i]", i.toByte(), packed.get(i))
        }
    }

    @Test
    fun `stripRowStride handles a zero-padding buffer (stride equals width)`() {
        val width = 4
        val height = 3
        val source = ByteBuffer.allocateDirect(width * height)
        for (i in 0 until width * height) source.put(i, (i % 12).toByte())

        val packed = SemanticsOverlay.stripRowStride(source, width, height, width)

        assertEquals(0, packed.position())
        assertEquals(width * height, packed.remaining())
        assertEquals(7.toByte(), packed.get(7))
    }

    @Test
    fun `stripRowStride rejects a stride smaller than the width`() {
        val source = ByteBuffer.allocateDirect(16)
        assertThrows(IllegalArgumentException::class.java) {
            SemanticsOverlay.stripRowStride(source, width = 8, height = 2, rowStrideBytes = 4)
        }
    }

    @Test
    fun `stripRowStride rejects an empty image`() {
        val source = ByteBuffer.allocateDirect(4)
        assertThrows(IllegalArgumentException::class.java) {
            SemanticsOverlay.stripRowStride(source, width = 0, height = 2, rowStrideBytes = 4)
        }
    }

    // ── palette / label tables ─────────────────────────────────────────────

    @Test
    fun `palette has exactly one colour per ARCore semantic label`() {
        assertEquals(SemanticsOverlay.LABEL_COUNT, SemanticsOverlay.PALETTE_ARGB.size)
        assertEquals(SemanticsOverlay.LABEL_COUNT, SemanticsOverlay.LABEL_NAMES.size)
    }

    @Test
    fun `every palette entry is fully opaque`() {
        // The legend swatches draw with these ARGB ints; a non-0xFF alpha would render
        // a half-transparent swatch that mis-reads against the dark legend background.
        for ((ordinal, argb) in SemanticsOverlay.PALETTE_ARGB.withIndex()) {
            val alpha = (argb ushr 24) and 0xFF
            assertEquals("alpha of palette[$ordinal]", 0xFF, alpha)
        }
    }

    @Test
    fun `unlabeled is the last ordinal and renders black`() {
        assertEquals(
            SemanticsOverlay.LABEL_COUNT - 1,
            SemanticsOverlay.UNLABELED_ORDINAL
        )
        // UNLABELED is painted transparent by the shader; its legend colour is black.
        assertEquals(
            0xFF000000.toInt(),
            SemanticsOverlay.PALETTE_ARGB[SemanticsOverlay.UNLABELED_ORDINAL]
        )
    }

    // ── clampUnit ──────────────────────────────────────────────────────────

    @Test
    fun `clampUnit clamps below 0 and above 1`() {
        assertEquals(0f, SemanticsOverlay.clampUnit(-0.4f), 1e-6f)
        assertEquals(1f, SemanticsOverlay.clampUnit(1.7f), 1e-6f)
    }

    @Test
    fun `clampUnit passes through in-range values`() {
        assertEquals(0.5f, SemanticsOverlay.clampUnit(0.5f), 1e-6f)
        assertTrue(SemanticsOverlay.clampUnit(0f) == 0f)
        assertTrue(SemanticsOverlay.clampUnit(1f) == 1f)
    }
}
