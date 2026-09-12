package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * JVM unit tests for [SemanticsOverlay]. ARCore's `Image` is not mockable on the JVM, so
 * [labelBufferToArgb][SemanticsOverlay.labelBufferToArgb] is fed a plain [ByteBuffer] shaped like
 * an ARCore `R8` semantic image (one byte per pixel, a label ordinal, row-strided) instead.
 *
 * Pins the contract that backs the `ARSceneSemanticsDemo` Compose bitmap overlay (#1868,
 * #3396, #3527): row-strided input compacts and colours correctly regardless of stride, rotation
 * re-indexes without resampling, `UNLABELED` stays transparent, and the 12-class colour
 * palette / label tables stay the right size.
 */
class SemanticsOverlayTest {

    // ── labelBufferToArgb ──────────────────────────────────────────────────

    @Test
    fun `labelBufferToArgb colours each pixel from the palette, ignoring row padding`() {
        val width = 3
        val height = 2
        val rowStride = 5 // 2 padding bytes per row
        val source = ByteBuffer.allocateDirect(rowStride * height)
        // Row 0: ordinals 0(SKY),1(BUILDING),2(TREE) then padding.
        // Row 1: ordinals 3(ROAD),4(SIDEWALK),5(TERRAIN) then padding.
        val ordinals = intArrayOf(0, 1, 2, 3, 4, 5)
        for (y in 0 until height) {
            for (x in 0 until width) {
                source.put(y * rowStride + x, ordinals[y * width + x].toByte())
            }
        }

        val pixels = SemanticsOverlay.labelBufferToArgb(source, width, height, rowStride)

        assertEquals(width * height, pixels.size)
        for (i in ordinals.indices) {
            assertEquals("pixel[$i]", SemanticsOverlay.PALETTE_ARGB[ordinals[i]], pixels[i])
        }
    }

    @Test
    fun `labelBufferToArgb paints UNLABELED fully transparent`() {
        val width = 2
        val height = 1
        val source = ByteBuffer.allocateDirect(width * height)
        source.put(0, SemanticsOverlay.UNLABELED_ORDINAL.toByte())
        source.put(1, 0) // SKY

        val pixels = SemanticsOverlay.labelBufferToArgb(source, width, height, width)

        assertEquals(0x00000000, pixels[0])
        assertEquals(SemanticsOverlay.PALETTE_ARGB[0], pixels[1])
    }

    @Test
    fun `labelBufferToArgb clamps an out-of-range byte into the palette`() {
        val source = ByteBuffer.allocateDirect(1)
        source.put(0, 200.toByte()) // way past LABEL_COUNT - 1

        val pixels = SemanticsOverlay.labelBufferToArgb(source, width = 1, height = 1, rowStrideBytes = 1)

        assertEquals(SemanticsOverlay.PALETTE_ARGB[SemanticsOverlay.LABEL_COUNT - 1], pixels[0])
    }

    @Test
    fun `labelBufferToArgb rejects a stride smaller than the width`() {
        val source = ByteBuffer.allocateDirect(16)
        assertThrows(IllegalArgumentException::class.java) {
            SemanticsOverlay.labelBufferToArgb(source, width = 8, height = 2, rowStrideBytes = 4)
        }
    }

    @Test
    fun `labelBufferToArgb rejects an empty image`() {
        val source = ByteBuffer.allocateDirect(4)
        assertThrows(IllegalArgumentException::class.java) {
            SemanticsOverlay.labelBufferToArgb(source, width = 0, height = 2, rowStrideBytes = 4)
        }
    }

    @Test
    fun `labelBufferToArgb rejects a rotation that is not a multiple of 90`() {
        val source = ByteBuffer.allocateDirect(4)
        assertThrows(IllegalArgumentException::class.java) {
            SemanticsOverlay.labelBufferToArgb(
                source,
                width = 2,
                height = 2,
                rowStrideBytes = 2,
                rotationDegrees = 45,
            )
        }
    }

    @Test
    fun `labelBufferToArgb rotates 90 degrees clockwise without resampling`() {
        // 2x1 source: [SKY(0), BUILDING(1)] -> rotated 90 becomes a 1x2 column,
        // reading top-to-bottom as [SKY, BUILDING] (matches DepthVisualization's indexing).
        val width = 2
        val height = 1
        val source = ByteBuffer.allocateDirect(width * height)
        source.put(0, 0) // SKY
        source.put(1, 1) // BUILDING

        val pixels = SemanticsOverlay.labelBufferToArgb(
            source,
            width,
            height,
            rowStrideBytes = width,
            rotationDegrees = 90,
        )
        val outWidth = SemanticsOverlay.rotatedWidth(width, height, 90)
        val outHeight = SemanticsOverlay.rotatedHeight(width, height, 90)

        assertEquals(1, outWidth)
        assertEquals(2, outHeight)
        assertEquals(SemanticsOverlay.PALETTE_ARGB[0], pixels[0])
        assertEquals(SemanticsOverlay.PALETTE_ARGB[1], pixels[1])
    }

    @Test
    fun `displayRotationToDegrees matches the depth-visualization portrait mapping`() {
        assertEquals(90, SemanticsOverlay.displayRotationToDegrees(0))
        assertEquals(0, SemanticsOverlay.displayRotationToDegrees(1))
        assertEquals(270, SemanticsOverlay.displayRotationToDegrees(2))
        assertEquals(180, SemanticsOverlay.displayRotationToDegrees(3))
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

    // ── isOutdoorSceneUnclassified (#3274) ────────────────────────────────

    @Test
    fun `dominant unlabeled fraction is reported as unclassified`() {
        assertTrue(
            SemanticsOverlay.isOutdoorSceneUnclassified(
                topOrdinal = SemanticsOverlay.UNLABELED_ORDINAL,
                topFraction = 0.97f,
            )
        )
    }

    @Test
    fun `unlabeled fraction under the threshold is not reported as unclassified`() {
        assertFalse(
            SemanticsOverlay.isOutdoorSceneUnclassified(
                topOrdinal = SemanticsOverlay.UNLABELED_ORDINAL,
                topFraction = 0.5f,
            )
        )
    }

    @Test
    fun `a real top label is never reported as unclassified, however high its fraction`() {
        assertFalse(
            SemanticsOverlay.isOutdoorSceneUnclassified(
                topOrdinal = 0, // SKY
                topFraction = 1.0f,
            )
        )
    }

    @Test
    fun `threshold is exact at the boundary`() {
        assertTrue(
            SemanticsOverlay.isOutdoorSceneUnclassified(
                topOrdinal = SemanticsOverlay.UNLABELED_ORDINAL,
                topFraction = 0.9f,
                threshold = 0.9f,
            )
        )
        assertFalse(
            SemanticsOverlay.isOutdoorSceneUnclassified(
                topOrdinal = SemanticsOverlay.UNLABELED_ORDINAL,
                topFraction = 0.899f,
                threshold = 0.9f,
            )
        )
    }
}
