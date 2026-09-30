package io.github.sceneview.demo.demos.internal

import com.google.android.filament.Material
import io.github.sceneview.model.ModelInstance

/**
 * Highest mask threshold a loaded model keeps (#4103): one 8-bit step under 1.
 *
 * glTF's `alphaCutoff` means "draw where alpha is at least this". A file that sets it to 1.0
 * asks for its fully opaque texels only, but Filament discards every fragment of a masked
 * material whose threshold is 1.0, the fully opaque ones included. The Fantasy Butterfly is
 * such a file (MASK, cutoff 1.0, an RGB texture with no alpha at all) and the viewer showed
 * an empty stage. Measured on the emulator: the same file with the cutoff at 0.99 or 0.996
 * draws, at 1.0 it does not. One step under 1 still keeps only the texels an 8-bit texture
 * stores as fully opaque, which is what the file asked for.
 */
internal const val MAX_MASK_THRESHOLD: Float = 1f - 1f / 255f

/** The threshold to draw a masked material with, for a file-authored [threshold]. */
internal fun drawableMaskThreshold(threshold: Float): Float =
    if (threshold > MAX_MASK_THRESHOLD) MAX_MASK_THRESHOLD else threshold

/**
 * Lowers every masked material of this instance whose threshold would hide it entirely to
 * [MAX_MASK_THRESHOLD]. Main thread only, like every Filament call.
 */
internal fun ModelInstance.drawFullyOpaqueMaskedMaterials() {
    getMaterialInstances().forEach { materialInstance ->
        if (materialInstance.material.blendingMode != Material.BlendingMode.MASKED) return@forEach
        val threshold = materialInstance.maskThreshold
        val drawable = drawableMaskThreshold(threshold)
        if (drawable != threshold) materialInstance.setMaskThreshold(drawable)
    }
}
