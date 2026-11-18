package io.github.sceneview.core.model

import io.github.sceneview.core.obj.ObjLoader
import io.github.sceneview.core.ply.PlyLoader
import io.github.sceneview.core.stl.StlLoader
import io.github.sceneview.core.threemf.ThreeMfLoader

/** Shared mesh conversion for Android, web and KMP consumers, with output in metres. */
object ModelConverter {
    /** Formats convertible to glTF; Gaussian splats need `SplatParser` and a splat renderer. */
    val supportedFormats: Set<ModelFormat> = setOf(
        ModelFormat.GLB, ModelFormat.GLTF, ModelFormat.THREE_MF,
        ModelFormat.STL, ModelFormat.OBJ, ModelFormat.PLY_MESH,
    )

    /**
     * Convert to GLB. Unitless STL/OBJ/PLY assume millimetres unless [unit] is given. 3MF's
     * declared unit and glTF's metres always win. GLB is returned without copying; JSON glTF is
     * wrapped in a GLB JSON chunk, keeping external resource URIs for the caller to resolve.
     * Automatic OBJ conversion loads grey geometry; use `ObjLoader` with an MTL resolver for
     * material colours. OBJ textures and 3MF textures are not supported by those converters.
     *
     * @throws UnsupportedModelFormat for splats, which cannot be converted into triangle meshes.
     */
    fun toGlb(bytes: ByteArray, format: ModelFormat, unit: ModelUnit? = null): ByteArray {
        val meshUnit = (unit ?: ModelUnit.Millimeters).toThreeMfUnit()
        return when (format) {
            ModelFormat.GLB -> bytes
            ModelFormat.GLTF -> wrapGltf(bytes)
            ModelFormat.THREE_MF -> ThreeMfLoader.toGlb(bytes)
            ModelFormat.STL -> StlLoader.toGlb(bytes, meshUnit)
            ModelFormat.OBJ -> ObjLoader.toGlb(bytes, meshUnit)
            ModelFormat.PLY_MESH -> PlyLoader.toGlb(bytes, meshUnit)
            ModelFormat.PLY_SPLAT -> throw UnsupportedModelFormat("ply")
            ModelFormat.SPZ -> throw UnsupportedModelFormat("spz")
        }
    }

    private fun wrapGltf(json: ByteArray): ByteArray {
        val paddedSize = (json.size + 3) and -4
        return ByteArray(20 + paddedSize).apply {
            putInt(0, 0x46546c67)
            putInt(4, 2)
            putInt(8, size)
            putInt(12, paddedSize)
            putInt(16, 0x4e4f534a)
            json.copyInto(this, 20)
            fill(32, 20 + json.size)
        }
    }

    private fun ByteArray.putInt(at: Int, value: Int) {
        repeat(4) { this[at + it] = (value ushr (it * 8)).toByte() }
    }
}
