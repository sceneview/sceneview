package io.github.sceneview.demo.demos.internal

import java.nio.ByteBuffer

/**
 * Pure-Kotlin helpers for the ARCore Scene Semantics label-overlay demo (#1730 / #1868).
 *
 * ARCore's [com.google.ar.core.Frame.acquireSemanticImage] returns an `R8` image — one byte
 * per pixel, the byte being a [com.google.ar.core.SemanticLabel] ordinal (`0..11`). The
 * `ARSceneSemanticsDemo` uploads that raster into a Filament `R8` [com.google.android.filament.Texture]
 * and renders it through the `semantics_overlay.filamat` material, which maps each ordinal to
 * one of 12 fixed outdoor-class colours.
 *
 * Two things need a non-Filament, JVM-testable home:
 *
 *  1. [stripRowStride] — ARCore hands back a **row-strided** buffer (`rowStride >= width`);
 *     Filament's `PixelBufferDescriptor` upload expects a tightly-packed `width * height`
 *     buffer. This compacts it.
 *  2. [PALETTE_ARGB] — the 12-class colour palette used to draw the demo's on-screen colour
 *     legend. It mirrors the `const vec3 SEMANTIC_PALETTE[12]` baked into
 *     `semantics_overlay.mat` (the `.mat` shader is the source of truth); keep the two in
 *     sync so the legend swatches read the same as the GPU overlay.
 *
 * Extracted as an `internal object` so the logic is unit-testable without ARCore /
 * Filament on the classpath — `com.google.ar.core.Image` is not mockable on the JVM, but a
 * plain [ByteBuffer] is. See `SemanticsOverlayTest`.
 *
 * Closes part of [#1868](https://github.com/sceneview/sceneview/issues/1868).
 */
internal object SemanticsOverlay {

    /** Number of ARCore Scene Semantics outdoor classes (`SemanticLabel` enum size). */
    const val LABEL_COUNT: Int = 12

    /** Ordinal of the `UNLABELED` class — rendered fully transparent by the overlay shader. */
    const val UNLABELED_ORDINAL: Int = 11

    /**
     * Per-class `0xFFRRGGBB` colours for the 12 ARCore semantic labels, indexed by ordinal:
     *
     * `0 SKY · 1 BUILDING · 2 TREE · 3 ROAD · 4 SIDEWALK · 5 TERRAIN · 6 STRUCTURE · 7 OBJECT ·
     * 8 VEHICLE · 9 PERSON · 10 WATER · 11 UNLABELED`.
     *
     * Mirrors `const vec3 SEMANTIC_PALETTE[12]` in
     * `sceneview/src/main/materials/semantics_overlay.mat`. The `.mat` shader is the source of
     * truth; this table only backs the demo's on-screen colour legend — keep the two in sync.
     */
    val PALETTE_ARGB: IntArray = intArrayOf(
        0xFF66B0F5.toInt(), // SKY        — light blue
        0xFFD95F57.toInt(), // BUILDING   — terracotta
        0xFF4CB050.toInt(), // TREE       — green
        0xFF757575.toInt(), // ROAD       — grey
        0xFFBDBD8C.toInt(), // SIDEWALK   — khaki
        0xFFCC9E6B.toInt(), // TERRAIN    — tan
        0xFF9E75BD.toInt(), // STRUCTURE  — purple
        0xFFFFC207.toInt(), // OBJECT     — amber
        0xFF2196F2.toInt(), // VEHICLE    — strong blue
        0xFFF54290.toInt(), // PERSON     — pink
        0xFF00BDD4.toInt(), // WATER      — cyan
        0xFF000000.toInt()  // UNLABELED  — black
    )

    /** Human-readable label names indexed by ordinal — matches `SemanticLabel.values()`. */
    val LABEL_NAMES: Array<String> = arrayOf(
        "Sky", "Building", "Tree", "Road", "Sidewalk", "Terrain",
        "Structure", "Object", "Vehicle", "Person", "Water", "Unlabeled"
    )

    /**
     * Compacts a row-strided single-channel (`R8`) image buffer into a tightly-packed
     * `width * height` [ByteBuffer] suitable for a Filament `PixelBufferDescriptor` upload.
     *
     * ARCore returns the semantic raster with `rowStrideBytes >= width`; Filament expects no
     * inter-row padding. When the stride already equals the width this still returns a fresh,
     * rewound, position-0 buffer so the caller can hand it straight to Filament.
     *
     * @param source         direct buffer with ARCore's `R8` semantic data; not consumed.
     * @param width          pixel width of the semantic image.
     * @param height         pixel height of the semantic image.
     * @param rowStrideBytes bytes between consecutive rows in [source] (`>= width`).
     * @return a packed buffer of exactly `width * height` bytes, rewound to position 0.
     */
    fun stripRowStride(
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
        val packed = ByteBuffer.allocateDirect(width * height)
        for (y in 0 until height) {
            val rowStart = y * rowStrideBytes
            for (x in 0 until width) {
                packed.put(source.get(rowStart + x))
            }
        }
        packed.rewind()
        return packed
    }

    /**
     * Clamp a UI slider value to the `[0, 1]` Compose / shader-opacity contract.
     */
    fun clampUnit(value: Float): Float = when {
        value < 0f -> 0f
        value > 1f -> 1f
        else -> value
    }
}
