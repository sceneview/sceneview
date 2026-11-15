package io.github.sceneview.core.model

import io.github.sceneview.core.obj.ObjLoader
import io.github.sceneview.core.ply.PlyLoader
import io.github.sceneview.core.stl.StlLoader
import io.github.sceneview.core.threemf.ThreeMfLoader
import io.github.sceneview.core.threemf.ThreeMfUnit

/** Mesh formats accepted by the web loader. PLY here requires faces; splats use their own loader. */
enum class ModelFormat {
    GLB, GLTF, THREE_MF, STL, OBJ, PLY;

    /** Whether callers must choose a length unit (millimetres by default). */
    val isUnitless: Boolean get() = this == STL || this == OBJ || this == PLY

    /** Convert to Y-up metres. glTF/GLB retain the original bytes and their resource base URL. */
    fun toGlb(bytes: ByteArray, unit: ThreeMfUnit = ThreeMfUnit.Default): ByteArray = when (this) {
        GLB, GLTF -> bytes
        THREE_MF -> ThreeMfLoader.toGlb(bytes)
        STL -> StlLoader.toGlb(bytes, unit)
        OBJ -> ObjLoader.toGlb(bytes, unit)
        PLY -> PlyLoader.toGlb(bytes, unit)
    }

    companion object {
        /**
         * Sniff bytes before using [fileName] as a hint for unitless meshes with long headers.
         * Accepts a URL as the hint, ignoring query/fragment. Unknown inputs throw a typed error.
         * Example: `ModelFormat.sniff(bytes, "part.stl?download=1").toGlb(bytes)`.
         */
        fun sniff(bytes: ByteArray, fileName: String? = null): ModelFormat {
            if (bytes.size >= 4 && bytes[0] == 0x67.toByte() && bytes[1] == 0x6c.toByte() &&
                bytes[2] == 0x54.toByte() && bytes[3] == 0x46.toByte()
            ) return GLB
            val prefix = bytes.decodeToString(0, minOf(bytes.size, 4096)).trimStart()
            if (prefix.startsWith("{") && Regex("\"asset\"\\s*:").containsMatchIn(prefix)) return GLTF
            if (ThreeMfLoader.isThreeMf(bytes)) return THREE_MF
            if (StlLoader.isStl(bytes)) return STL
            if (PlyLoader.isPly(bytes)) return PLY
            if (ObjLoader.isObj(bytes)) return OBJ
            return when (fileName?.substringBefore('?')?.substringBefore('#')?.substringAfterLast('.')?.lowercase()) {
                "3mf" -> THREE_MF
                "stl" -> STL
                "obj" -> OBJ
                "ply" -> PLY
                else -> throw UnsupportedModelFormatException(fileName)
            }
        }
    }
}

/** Unsupported input, distinct from a recognised format whose parser reports corrupt geometry. */
class UnsupportedModelFormatException(fileName: String?) : IllegalArgumentException(
    "Unsupported model format${fileName?.let { ": $it" } ?: ""}. Use GLB, glTF, 3MF, STL, OBJ or PLY."
)
