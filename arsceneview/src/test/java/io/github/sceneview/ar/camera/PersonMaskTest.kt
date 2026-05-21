package io.github.sceneview.ar.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Pure-JVM tests for [PersonMask] — the helper that turns an ARCore Scene Semantics raster
 * into the binary PERSON occlusion mask consumed by `camera_stream_person_occlusion.mat`
 * (#1761).
 *
 * ARCore's [com.google.ar.core.Image] is framework-bound and not mockable in pure JVM, so
 * the conversion logic lives in this `ByteBuffer`-only helper and is pinned here. The full
 * GPU pipeline (mask upload → shader → depth write) is exercised on-device via
 * `ARPeopleOcclusionDemo`.
 */
class PersonMaskTest {

    /** Builds a row-strided semantic R8 buffer of [ordinals] (length `width * height`). */
    private fun semanticBuffer(
        ordinals: IntArray,
        width: Int,
        height: Int,
        rowStrideBytes: Int = width
    ): ByteBuffer {
        require(ordinals.size == width * height)
        val buffer = ByteBuffer.allocateDirect(rowStrideBytes * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                buffer.put(y * rowStrideBytes + x, ordinals[y * width + x].toByte())
            }
        }
        return buffer
    }

    @Test
    fun `PERSON ordinal is 9 — the ARCore Scene Semantics class index`() {
        // Pinned against the documented ARCore SemanticLabel set
        // (0 SKY .. 9 PERSON .. 11 UNLABELED). FrameSemanticsTest pins the live enum so a
        // future SDK reorder is caught loudly elsewhere.
        assertEquals(9, PersonMask.PERSON_ORDINAL)
    }

    @Test
    fun `build maps PERSON pixels to 0xFF and everything else to 0x00`() {
        // 2x2 raster: top-left PERSON, the other three non-PERSON classes.
        val source = semanticBuffer(
            intArrayOf(
                PersonMask.PERSON_ORDINAL, 0, // PERSON, SKY
                3, 11                          // ROAD, UNLABELED
            ),
            width = 2,
            height = 2
        )
        val mask = PersonMask.build(source, width = 2, height = 2, rowStrideBytes = 2)

        assertEquals("packed mask must be width*height bytes", 4, mask.remaining())
        assertEquals("position 0 PERSON → 0xFF", PersonMask.PERSON_BYTE, mask.get(0))
        assertEquals("position 1 SKY → 0x00", PersonMask.BACKGROUND_BYTE, mask.get(1))
        assertEquals("position 2 ROAD → 0x00", PersonMask.BACKGROUND_BYTE, mask.get(2))
        assertEquals("position 3 UNLABELED → 0x00", PersonMask.BACKGROUND_BYTE, mask.get(3))
    }

    @Test
    fun `build drops row-stride padding`() {
        // 3-wide raster with a stride of 5 (2 padding bytes per row). The padding bytes are
        // set to PERSON_ORDINAL on purpose — if `build` failed to skip them, the packed mask
        // would carry stray 0xFF bytes and this test would fail.
        val width = 3
        val height = 2
        val rowStride = 5
        val buffer = ByteBuffer.allocateDirect(rowStride * height)
        for (i in 0 until rowStride * height) {
            buffer.put(i, PersonMask.PERSON_ORDINAL.toByte()) // fill everything, incl. padding
        }
        // Overwrite the real (non-padding) pixels with a non-PERSON class.
        for (y in 0 until height) {
            for (x in 0 until width) {
                buffer.put(y * rowStride + x, 0) // SKY
            }
        }

        val mask = PersonMask.build(buffer, width, height, rowStride)

        assertEquals(width * height, mask.remaining())
        for (i in 0 until width * height) {
            assertEquals(
                "stride padding must not leak into the packed mask at index $i",
                PersonMask.BACKGROUND_BYTE,
                mask.get(i)
            )
        }
    }

    @Test
    fun `build returns a buffer rewound to position 0`() {
        val source = semanticBuffer(intArrayOf(PersonMask.PERSON_ORDINAL), width = 1, height = 1)
        val mask = PersonMask.build(source, width = 1, height = 1, rowStrideBytes = 1)
        assertEquals("result must be rewound for a direct Filament upload", 0, mask.position())
    }

    @Test
    fun `build does not consume the source buffer position`() {
        val source = semanticBuffer(
            intArrayOf(PersonMask.PERSON_ORDINAL, 0),
            width = 2,
            height = 1
        )
        val positionBefore = source.position()
        PersonMask.build(source, width = 2, height = 1, rowStrideBytes = 2)
        assertEquals(
            "build must read by absolute index and leave source.position() untouched",
            positionBefore,
            source.position()
        )
    }

    @Test
    fun `build rejects a non-positive image size`() {
        val source = ByteBuffer.allocateDirect(1)
        assertThrows(IllegalArgumentException::class.java) {
            PersonMask.build(source, width = 0, height = 4, rowStrideBytes = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PersonMask.build(source, width = 4, height = -1, rowStrideBytes = 4)
        }
    }

    @Test
    fun `build rejects a row stride smaller than the width`() {
        val source = ByteBuffer.allocateDirect(16)
        assertThrows(IllegalArgumentException::class.java) {
            PersonMask.build(source, width = 4, height = 2, rowStrideBytes = 3)
        }
    }

    @Test
    fun `personFraction counts PERSON pixels over the total`() {
        // 4-pixel mask: 1 PERSON, 3 background → 0.25.
        val mask = ByteBuffer.allocateDirect(4)
        mask.put(0, PersonMask.PERSON_BYTE)
        mask.put(1, PersonMask.BACKGROUND_BYTE)
        mask.put(2, PersonMask.BACKGROUND_BYTE)
        mask.put(3, PersonMask.BACKGROUND_BYTE)

        assertEquals(0.25f, PersonMask.personFraction(mask, count = 4), 1e-6f)
    }

    @Test
    fun `personFraction is 0 for an all-background mask and 1 for an all-person mask`() {
        val empty = ByteBuffer.allocateDirect(8)
        for (i in 0 until 8) empty.put(i, PersonMask.BACKGROUND_BYTE)
        assertEquals(0f, PersonMask.personFraction(empty, count = 8), 1e-6f)

        val full = ByteBuffer.allocateDirect(8)
        for (i in 0 until 8) full.put(i, PersonMask.PERSON_BYTE)
        assertEquals(1f, PersonMask.personFraction(full, count = 8), 1e-6f)
    }

    @Test
    fun `personFraction rejects a non-positive count`() {
        val mask = ByteBuffer.allocateDirect(4)
        assertThrows(IllegalArgumentException::class.java) {
            PersonMask.personFraction(mask, count = 0)
        }
    }

    @Test
    fun `build then personFraction round-trips a known raster`() {
        // 4x2 raster, 8 pixels, exactly 2 of them PERSON → fraction 0.25.
        val ordinals = intArrayOf(
            PersonMask.PERSON_ORDINAL, 0, 1, 2,
            3, PersonMask.PERSON_ORDINAL, 5, 6
        )
        val source = semanticBuffer(ordinals, width = 4, height = 2)
        val mask = PersonMask.build(source, width = 4, height = 2, rowStrideBytes = 4)
        assertEquals(0.25f, PersonMask.personFraction(mask, count = 8), 1e-6f)
        assertTrue("mask must stay readable after personFraction", mask.remaining() == 8)
    }
}
