package io.github.sceneview.demo.demos.internal

import com.google.ar.core.SemanticLabel
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
 * palette / label tables stay the right size and in ARCore's own order (#4036).
 */
class SemanticsOverlayTest {

    // ── labelBufferToArgb ──────────────────────────────────────────────────

    @Test
    fun `labelBufferToArgb colours each pixel from the palette, ignoring row padding`() {
        val width = 3
        val height = 2
        val rowStride = 5 // 2 padding bytes per row
        val source = ByteBuffer.allocateDirect(rowStride * height)
        // Row 0: ordinals 1(SKY),2(BUILDING),3(TREE) then padding.
        // Row 1: ordinals 4(ROAD),5(SIDEWALK),6(TERRAIN) then padding.
        val ordinals = intArrayOf(1, 2, 3, 4, 5, 6)
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
        source.put(1, 1) // SKY

        val pixels = SemanticsOverlay.labelBufferToArgb(source, width, height, width)

        assertEquals(0x00000000, pixels[0])
        assertEquals(SemanticsOverlay.PALETTE_ARGB[1], pixels[1])
    }

    @Test
    fun `labelBufferToArgb leaves a byte past the last class transparent`() {
        val source = ByteBuffer.allocateDirect(2)
        source.put(0, 200.toByte()) // way past LABEL_COUNT - 1
        source.put(1, SemanticsOverlay.LABEL_COUNT.toByte()) // the first value past it

        val pixels = SemanticsOverlay.labelBufferToArgb(source, width = 2, height = 1, rowStrideBytes = 2)

        // A corrupt or unrecognised byte should show the live camera through, never a solid
        // colour that looks like a deliberate classification — and never the last class's one.
        assertEquals(0x00000000, pixels[0])
        assertEquals(0x00000000, pixels[1])
    }

    @Test
    fun `labelBufferToArgb paints each class in the colour the legend gives its name`() {
        // The byte ARCore writes is the SemanticLabel ordinal. Read against a table that starts
        // at SKY, a BUILDING wall came out in the legend's "Tree" green and a chair ARCore called
        // OBJECT in its "Vehicle" blue (#4036).
        val labels = SemanticLabel.values().filter { it != SemanticLabel.UNLABELED }
        val source = ByteBuffer.allocateDirect(labels.size)
        labels.forEachIndexed { i, label -> source.put(i, label.ordinal.toByte()) }

        val pixels = SemanticsOverlay.labelBufferToArgb(
            source,
            width = labels.size,
            height = 1,
            rowStrideBytes = labels.size,
        )

        val byName = SemanticsOverlay.LABEL_NAMES.map { it.uppercase() }
        labels.forEachIndexed { i, label ->
            assertEquals(label.name, SemanticsOverlay.PALETTE_ARGB[byName.indexOf(label.name)], pixels[i])
        }
        assertEquals("BUILDING is terracotta", 0xFFD95F57.toInt(), pixels[labels.indexOf(SemanticLabel.BUILDING)])
        assertEquals("OBJECT is amber", 0xFFFFC207.toInt(), pixels[labels.indexOf(SemanticLabel.OBJECT)])
        assertEquals("WATER is cyan, not left transparent", 0xFF00BDD4.toInt(), pixels[labels.indexOf(SemanticLabel.WATER)])
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
        // 2x1 source: [SKY(1), BUILDING(2)] -> rotated 90 becomes a 1x2 column,
        // reading top-to-bottom as [SKY, BUILDING] (matches DepthVisualization's indexing).
        val width = 2
        val height = 1
        val source = ByteBuffer.allocateDirect(width * height)
        source.put(0, 1) // SKY
        source.put(1, 2) // BUILDING

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
        assertEquals(SemanticsOverlay.PALETTE_ARGB[1], pixels[0])
        assertEquals(SemanticsOverlay.PALETTE_ARGB[2], pixels[1])
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
    fun `label names and the unlabeled ordinal follow ARCore's own SemanticLabel order`() {
        // The pin that was missing: both tables are indexed by the byte ARCore writes, so they
        // have to list the classes in the order the SDK declares them (UNLABELED first).
        assertEquals(
            SemanticLabel.values().map { it.name },
            SemanticsOverlay.LABEL_NAMES.map { it.uppercase() },
        )
        assertEquals(SemanticLabel.UNLABELED.ordinal, SemanticsOverlay.UNLABELED_ORDINAL)
    }

    @Test
    fun `unlabeled is ARCore's first ordinal and renders black`() {
        assertEquals(0, SemanticsOverlay.UNLABELED_ORDINAL)
        // UNLABELED is painted transparent by the overlay; its table colour is black.
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
                topOrdinal = 1, // SKY
                topFraction = 1.0f,
            )
        )
        // WATER is the last ordinal, where this table used to keep UNLABELED: a lake filling
        // the frame is a classified scene, not "nothing classified yet".
        assertFalse(
            SemanticsOverlay.isOutdoorSceneUnclassified(
                topOrdinal = SemanticLabel.WATER.ordinal,
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
