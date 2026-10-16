package io.github.sceneview.demo.demos.internal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * The final model of a scan ([RerunMesh]) as one glTF 2.0 binary: a single `room` mesh, indexed
 * triangles with `POSITION`, `NORMAL` and `COLOR_0` (normalised `UNSIGNED_BYTE` RGBA, linear, as
 * glTF requires) and one matte, lit, single-sided material that multiplies the vertex colours.
 * Metres, Y up; shared exports rest at Y=0 and are centred in X/Z, without rescaling.
 */
object RerunMeshGlb {
    const val MIME_TYPE = "model/gltf-binary"

    /** The full mesh in the session's world space: what the replay's own 3D view draws. */
    fun write(mesh: RerunMesh, generator: String = "SceneView Rerun"): ByteArray =
        writeShared(mesh, generator, fullResolution = true, floorOrigin = false)

    /**
     * The model a share sends: [mesh] simplified to [targetTriangles] ([RerunMeshSimplifier]) unless
     * [fullResolution], and with [floorOrigin] resting on Y=0 and centred in X/Z — what a viewer
     * that drops a model on a table expects. The floor is the mesh's lowest point, not a fitted
     * plane. Blocking CPU work: run it on a worker; [onReduced] is told how many triangles are
     * written; [progress] follows the simplification and stops it by throwing. [mesh] is not modified.
     */
    fun writeShared(
        mesh: RerunMesh,
        generator: String = "SceneView Rerun",
        fullResolution: Boolean = false,
        targetTriangles: Int = RerunMeshSimplifier.DEFAULT_TARGET_TRIANGLES,
        floorOrigin: Boolean = true,
        onReduced: (triangles: Int) -> Unit = {},
        progress: (Float) -> Unit = {},
    ): ByteArray {
        require(mesh.triangleCount > 0) { "a GLB mesh must contain triangles" }
        val reduced = if (fullResolution) mesh else RerunMeshSimplifier.simplify(mesh, targetTriangles, progress)
        require(reduced.triangleCount > 0) { "simplification must retain a surface" }
        onReduced(reduced.triangleCount)
        val exported = if (floorOrigin) {
            val bounds = reduced.bounds()
            val origin = floatArrayOf((bounds[0] + bounds[3]) / 2, bounds[1], (bounds[2] + bounds[5]) / 2)
            val positions = FloatArray(reduced.positions.size) { reduced.positions[it] - origin[it % 3] }
            RerunMesh(positions, reduced.normals, reduced.colors, reduced.indices)
        } else {
            reduced
        }
        return encode(exported, generator)
    }

    private fun encode(mesh: RerunMesh, generator: String): ByteArray {
        val n = mesh.vertexCount
        // 65535 is the reserved primitive-restart value and is forbidden in glTF indices.
        val shortIndices = n <= 65535
        val positionBytes = n * 12
        val normalBytes = n * 12
        val colorBytes = n * 4
        val indexBytes = mesh.indices.size * if (shortIndices) 2 else 4
        val bin = binary(mesh, positionBytes + normalBytes + colorBytes + indexBytes, shortIndices)
        val views = listOf(
            view(0, positionBytes, ARRAY_BUFFER, stride = 12),
            view(positionBytes, normalBytes, ARRAY_BUFFER, stride = 12),
            view(positionBytes + normalBytes, colorBytes, ARRAY_BUFFER, stride = 4),
            view(positionBytes + normalBytes + colorBytes, indexBytes, ELEMENT_ARRAY_BUFFER, stride = null),
        )
        val accessors = accessors(mesh, shortIndices)
        val json = jsonOf(
            "asset" to jsonOf("version" to "2.0", "generator" to generator),
            "scene" to 0,
            "scenes" to listOf(jsonOf("nodes" to listOf(0))),
            "nodes" to listOf(jsonOf("name" to "room", "mesh" to 0)),
            "meshes" to listOf(
                jsonOf(
                    "name" to "room",
                    "primitives" to listOf(
                        jsonOf(
                            "attributes" to jsonOf("POSITION" to 0, "NORMAL" to 1, "COLOR_0" to 2),
                            "indices" to 3,
                            "material" to 0,
                            "mode" to TRIANGLES,
                        ),
                    ),
                ),
            ),
            "materials" to listOf(
                jsonOf(
                    "name" to "room",
                    "pbrMetallicRoughness" to jsonOf(
                        "baseColorFactor" to listOf(1.0, 1.0, 1.0, 1.0),
                        "metallicFactor" to 0.0,
                        "roughnessFactor" to ROUGHNESS,
                    ),
                ),
            ),
            "buffers" to listOf(jsonOf("byteLength" to bin.capacity())),
            "bufferViews" to views,
            "accessors" to accessors,
        )
        return glb(RerunJson.write(json).toByteArray(Charsets.UTF_8), bin.array())
    }

    private fun accessors(mesh: RerunMesh, shortIndices: Boolean): List<JsonMap> {
        val n = mesh.vertexCount
        val b = mesh.bounds()
        return listOf(
            jsonOf(
                "bufferView" to 0, "componentType" to FLOAT, "count" to n, "type" to "VEC3",
                "min" to listOf(b[0], b[1], b[2]).map(Float::toDouble),
                "max" to listOf(b[3], b[4], b[5]).map(Float::toDouble),
            ),
            jsonOf("bufferView" to 1, "componentType" to FLOAT, "count" to n, "type" to "VEC3"),
            jsonOf(
                "bufferView" to 2, "componentType" to UNSIGNED_BYTE, "normalized" to true,
                "count" to n, "type" to "VEC4",
            ),
            jsonOf(
                "bufferView" to 3, "componentType" to if (shortIndices) UNSIGNED_SHORT else UNSIGNED_INT,
                "count" to mesh.indices.size, "type" to "SCALAR",
            ),
        )
    }

    private fun binary(mesh: RerunMesh, size: Int, shortIndices: Boolean): ByteBuffer {
        val bin = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        mesh.positions.forEach { bin.putFloat(it) }
        mesh.normals.forEach { bin.putFloat(it) }
        for (c in mesh.colors) {
            bin.put(LINEAR[(c shr 16) and 0xFF])
            bin.put(LINEAR[(c shr 8) and 0xFF])
            bin.put(LINEAR[c and 0xFF])
            bin.put(0xFF.toByte())
        }
        mesh.indices.forEach { if (shortIndices) bin.putShort(it.toShort()) else bin.putInt(it) }
        return bin
    }

    private fun view(offset: Int, length: Int, target: Int, stride: Int?): JsonMap =
        jsonOf("buffer" to 0, "byteOffset" to offset, "byteLength" to length, "target" to target).also {
            if (stride != null) it["byteStride"] = stride
        }

    private fun glb(json: ByteArray, bin: ByteArray): ByteArray {
        val jsonPadded = pad(json.size)
        val binPadded = pad(bin.size)
        val total = HEADER + CHUNK_HEADER + jsonPadded + CHUNK_HEADER + binPadded
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(GLB_MAGIC).putInt(2).putInt(total)
        out.putInt(jsonPadded).putInt(CHUNK_JSON).put(json)
        repeat(jsonPadded - json.size) { out.put(' '.code.toByte()) }
        out.putInt(binPadded).putInt(CHUNK_BIN).put(bin)
        repeat(binPadded - bin.size) { out.put(0) }
        return out.array()
    }

    private fun pad(size: Int) = (size + 3) and 3.inv()

    /** sRGB byte → linear byte, glTF's colour space for `COLOR_0`. */
    private val LINEAR = ByteArray(256) { i ->
        val c = i / 255.0
        val linear = if (c <= SRGB_KNEE) {
            c / SRGB_LINEAR_SLOPE
        } else {
            ((c + SRGB_OFFSET) / (1 + SRGB_OFFSET)).pow(SRGB_GAMMA)
        }
        (linear * 255 + 0.5).toInt().coerceIn(0, 255).toByte()
    }

    private const val ROUGHNESS = 0.9
    private const val SRGB_KNEE = 0.04045
    private const val SRGB_LINEAR_SLOPE = 12.92
    private const val SRGB_OFFSET = 0.055
    private const val SRGB_GAMMA = 2.4

    private const val HEADER = 12
    private const val CHUNK_HEADER = 8
    private const val GLB_MAGIC = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942
    private const val ARRAY_BUFFER = 34962
    private const val ELEMENT_ARRAY_BUFFER = 34963
    private const val FLOAT = 5126
    private const val UNSIGNED_BYTE = 5121
    private const val UNSIGNED_SHORT = 5123
    private const val UNSIGNED_INT = 5125
    private const val TRIANGLES = 4
}
