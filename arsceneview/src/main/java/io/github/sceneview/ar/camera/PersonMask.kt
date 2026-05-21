package io.github.sceneview.ar.camera

import java.nio.ByteBuffer

/**
 * Pure-Kotlin helper that turns an ARCore Scene Semantics raster into a binary **PERSON
 * occlusion mask** for the people-occlusion camera material (#1761).
 *
 * ## Why a separate mask
 *
 * ARCore's [com.google.ar.core.Frame.acquireSemanticImage] returns an `R8` image — one byte
 * per pixel, the byte being a [com.google.ar.core.SemanticLabel] ordinal (`0..11`). The
 * people-occlusion shader (`camera_stream_person_occlusion.mat`) only cares about a single
 * class — `PERSON` — and wants the cheapest possible per-pixel test: a value it can compare
 * against `0.5` after the GPU's `R8 → [0,1]` normalisation.
 *
 * Rather than upload the raw 12-class ordinal raster and re-derive "is this PERSON?" in the
 * shader (a multiply-by-255 + integer compare per fragment), this helper bakes the test
 * **once on the CPU** into a binary mask: `255` for PERSON pixels, `0` for everything else.
 * The shader then samples a value that is either `1.0` or `0.0` — a branchless `step()`.
 *
 * ## Row-stride compaction
 *
 * ARCore hands back a **row-strided** buffer (`rowStrideBytes >= width`); Filament's
 * `PixelBufferDescriptor` upload expects a tightly-packed `width * height` buffer. This
 * helper compacts and binarises in a single pass, so the per-frame cost is one allocation
 * plus one `width * height` scan — the same order of magnitude as the depth-image upload.
 *
 * Extracted as an `internal object` so the logic is unit-testable without ARCore / Filament
 * on the classpath — [com.google.ar.core.Image] is not mockable on the JVM, but a plain
 * [ByteBuffer] is. See `PersonMaskTest`.
 *
 * Closes part of [#1761](https://github.com/sceneview/sceneview/issues/1761).
 */
internal object PersonMask {

    /**
     * Ordinal of [com.google.ar.core.SemanticLabel.PERSON] in the ARCore Scene Semantics
     * class set (`0 SKY · 1 BUILDING · 2 TREE · 3 ROAD · 4 SIDEWALK · 5 TERRAIN ·
     * 6 STRUCTURE · 7 OBJECT · 8 VEHICLE · 9 PERSON · 10 WATER · 11 UNLABELED`).
     *
     * Hard-coded rather than read via `SemanticLabel.PERSON.ordinal` so the conversion is
     * JVM-unit-testable without the ARCore runtime on the classpath. `FrameSemanticsTest`
     * pins the live ARCore enum so a future SDK reorder is caught loudly.
     */
    const val PERSON_ORDINAL: Int = 9

    /** Mask byte written for a PERSON pixel — `255` → `1.0` after the shader's `R8` normalise. */
    const val PERSON_BYTE: Byte = -1 // 0xFF

    /** Mask byte written for a non-PERSON pixel — `0` → `0.0` after the shader's `R8` normalise. */
    const val BACKGROUND_BYTE: Byte = 0

    /**
     * Builds a tightly-packed binary PERSON mask from an ARCore semantic `R8` raster.
     *
     * Each output byte is [PERSON_BYTE] (`0xFF`) when the matching input ordinal equals
     * [PERSON_ORDINAL], and [BACKGROUND_BYTE] (`0x00`) otherwise. Row stride padding present
     * in [source] is dropped — the result is exactly `width * height` bytes, rewound to
     * position 0, ready for a Filament `PixelBufferDescriptor` upload.
     *
     * @param source         direct buffer with ARCore's `R8` semantic ordinals; not consumed
     *                       (read by absolute index, [source]'s position is untouched).
     * @param width          pixel width of the semantic image.
     * @param height         pixel height of the semantic image.
     * @param rowStrideBytes bytes between consecutive rows in [source] (`>= width`).
     * @return a packed buffer of exactly `width * height` bytes, rewound to position 0.
     */
    fun build(
        source: ByteBuffer,
        width: Int,
        height: Int,
        rowStrideBytes: Int
    ): ByteBuffer {
        require(width > 0 && height > 0) {
            "semantic image must be non-empty (got $width x $height)"
        }
        require(rowStrideBytes >= width) {
            "rowStrideBytes ($rowStrideBytes) must be >= width ($width)"
        }
        val mask = ByteBuffer.allocateDirect(width * height)
        for (y in 0 until height) {
            val rowStart = y * rowStrideBytes
            for (x in 0 until width) {
                val ordinal = source.get(rowStart + x).toInt() and 0xFF
                mask.put(if (ordinal == PERSON_ORDINAL) PERSON_BYTE else BACKGROUND_BYTE)
            }
        }
        mask.rewind()
        return mask
    }

    /**
     * Returns the fraction of PERSON pixels in [mask] — a value in `[0f, 1f]`.
     *
     * Intended for demo HUDs and "is a person actually in frame?" gating. The argument must
     * be a packed binary mask as produced by [build]; each byte is either [PERSON_BYTE] or
     * [BACKGROUND_BYTE]. The buffer's position is left untouched (read by absolute index).
     *
     * @param mask  packed binary PERSON mask.
     * @param count number of valid mask bytes (`width * height`); must be `> 0`.
     * @return PERSON-pixel fraction in `[0f, 1f]`.
     */
    fun personFraction(mask: ByteBuffer, count: Int): Float {
        require(count > 0) { "count must be > 0 (got $count)" }
        var person = 0
        for (i in 0 until count) {
            if (mask.get(i) == PERSON_BYTE) person++
        }
        return person.toFloat() / count.toFloat()
    }
}
