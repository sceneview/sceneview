package io.github.sceneview.core.model

import io.github.sceneview.core.ply.PlyLoader
import io.github.sceneview.core.splat.Inflate
import io.github.sceneview.core.stl.StlLoader
import io.github.sceneview.core.threemf.ThreeMfLoader

/** A recognised 3D file format. Splats require a splat renderer, rather than mesh conversion. */
enum class ModelFormat {
    GLB, GLTF, THREE_MF, STL, OBJ, PLY_MESH, PLY_SPLAT, SPZ;

    companion object {
        /**
         * Identify [head] by content, then fall back to [fileName]'s case-insensitive extension.
         * Recognised content wins over a misleading extension; `.ply` needs its header to tell
         * meshes from Gaussian splats. Pass the complete file for binary STL's size/count check
         * and 3MF's ZIP directory; a short prefix can only identify signatures present in it.
         *
         * @throws UnsupportedModelFormat when neither content nor extension is recognised.
         */
        fun sniff(fileName: String?, head: ByteArray): ModelFormat {
            val extension = modelExtension(fileName)
            val byExtension = when (extension) {
                "glb" -> GLB
                "gltf" -> GLTF
                "3mf" -> THREE_MF
                "stl" -> STL
                "obj" -> OBJ
                "ply" -> PLY_MESH
                "spz" -> SPZ
                else -> null
            }
            return sniffContent(head) ?: byExtension ?: throw UnsupportedModelFormat(extension)
        }

        private fun sniffContent(bytes: ByteArray): ModelFormat? = when {
            bytes.hasPrefix("glTF") -> GLB
            bytes.hasPrefix("NGSP") || bytes.isGzipSpz() -> SPZ
            bytes.hasPrefix("PK\u0003\u0004") && ThreeMfLoader.isThreeMf(bytes) -> THREE_MF
            PlyLoader.isPly(bytes) -> if (isSplatPly(bytes)) PLY_SPLAT else PLY_MESH
            StlLoader.isStl(bytes) -> STL
            isGltfJson(bytes) -> GLTF
            isObj(bytes) -> OBJ
            else -> null
        }

        /** Legacy SPZ is gzip around an `NGSP` header: any other gzip is not a splat. */
        private fun ByteArray.isGzipSpz(): Boolean =
            hasPrefix("\u001f\u008b") && Inflate.gunzipHead(this, 4)?.hasPrefix("NGSP") == true

        private fun isSplatPly(bytes: ByteArray): Boolean {
            val properties = mutableSetOf<String>()
            var at = 0
            while (at < bytes.size) {
                var end = at
                while (end < bytes.size && bytes[end] != 10.toByte()) end++
                val line = bytes.decodeToString(at, minOf(end, at + 4096)).trim()
                if (line == "end_header") break
                val words = line.split(WHITESPACE)
                if (words.size == 3 && words[0] == "property") properties += words[2]
                at = end + 1
            }
            return properties.containsAll(setOf("f_dc_0", "opacity", "scale_0", "rot_0"))
        }

        /** OBJ has no magic: a finite vertex line is evidence even before a large vertex table's first face. */
        private fun isObj(bytes: ByteArray): Boolean {
            var at = 0
            while (at < bytes.size) {
                var end = at
                while (end < bytes.size && bytes[end] != 10.toByte() && bytes[end] != 13.toByte()) end++
                val words = bytes.decodeToString(at, minOf(end, at + 4096))
                    .trimStart('\uFEFF').substringBefore('#').trim().split(WHITESPACE)
                when (words[0]) {
                    "v" -> return words.size >= 4 && (1..3).all { words[it].toFloatOrNull()?.isFinite() == true }
                    "", "vn", "vt", "o", "g", "mtllib", "usemtl", "s", "l", "p" -> Unit
                    else -> return false
                }
                at = end + 1
            }
            return false
        }

        private fun isGltfJson(bytes: ByteArray): Boolean {
            var at = if (bytes.hasPrefix("\u00ef\u00bb\u00bf")) 3 else 0
            while (at < bytes.size && bytes[at].toInt().toChar() in " \t\r\n") at++
            if (at == bytes.size || bytes[at] != '{'.code.toByte()) return false
            var depth = 0
            while (at < bytes.size) {
                when (bytes[at].toInt().toChar()) {
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                    '"' -> {
                        val start = at++
                        while (at < bytes.size && bytes[at] != '"'.code.toByte()) {
                            at += if (bytes[at] == '\\'.code.toByte()) 2 else 1
                        }
                        if (at >= bytes.size) return false
                        val isAsset = depth == 1 && at - start == 6 &&
                            "asset".indices.all { bytes[start + 1 + it] == "asset"[it].code.toByte() }
                        if (isAsset) {
                            at++
                            while (at < bytes.size && bytes[at].toInt().toChar() in " \t\r\n") at++
                            if (at < bytes.size && bytes[at++] == ':'.code.toByte()) {
                                while (at < bytes.size && bytes[at].toInt().toChar() in " \t\r\n") at++
                                if (at < bytes.size && bytes[at] == '{'.code.toByte()) return true
                            }
                            continue
                        }
                    }
                }
                at++
            }
            return false
        }

    }
}

/**
 * Refused before reaching a native renderer; [extension] is lowercase, without a dot, or null.
 * `ply` and `spz` mean a Gaussian splat, which loads with `SplatNode` rather than as a mesh.
 */
class UnsupportedModelFormat(val extension: String?) : IllegalArgumentException(
    when (extension) {
        "ply", "spz" -> "Unsupported model format: Gaussian-splat $extension; load it with SplatNode"
        else -> "Unsupported model format: ${extension ?: "unknown"}"
    },
)

private val WHITESPACE = Regex("\\s+")

internal fun modelExtension(fileName: String?): String? = fileName
    ?.substringBefore('?')?.substringBefore('#')?.substringAfterLast('/')
    ?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }

private fun ByteArray.hasPrefix(text: String): Boolean =
    size >= text.length && text.indices.all { this[it] == text[it].code.toByte() }
