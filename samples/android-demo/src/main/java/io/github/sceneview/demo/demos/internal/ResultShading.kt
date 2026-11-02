package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.model.ModelGlb
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * How the scan result draws its model, QA only: [AsBuilt] — the public default, the textured
 * model unlit in its photos' own light, the vertex-coloured one lit — against every material
 * [Lit] or [Unlit], for an A/B on the same scan before any default changes.
 */
internal enum class ResultShading(val qaState: String?) {
    AsBuilt(null),
    Lit("scan_lit"),
    Unlit("scan_unlit"),
    ;

    /** The next one in More's QA item. */
    val next: ResultShading get() = entries[(ordinal + 1) % entries.size]

    /**
     * [model]'s `.glb` in a native buffer for the renderer, drawn this way; `null` when the heap
     * has no room for the copy, or the `.glb` is not one this rewrites.
     */
    fun buffer(model: RerunScanModel): ByteBuffer? {
        if (this == AsBuilt) return model.glbBuffer()
        val glb = ModelGlb.withShading(model.glb, lit = this == Lit) ?: return model.glbBuffer()
        return try {
            ByteBuffer.allocateDirect(glb.size).order(ByteOrder.nativeOrder()).put(glb).also { it.rewind() }
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    companion object {
        /** The shading a QA state names (`scan_lit`, `scan_unlit`), [AsBuilt] for any other. */
        fun of(qaState: String?): ResultShading = entries.firstOrNull { it.qaState != null && it.qaState == qaState } ?: AsBuilt
    }
}
