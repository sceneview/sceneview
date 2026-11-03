package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The dollhouse cut of a room's result: every triangle whose middle stands above a horizontal
 * plane is dropped from the picture, so the room is seen into from outside and above over walls
 * cut at a fixed height — no wall that comes and goes as the camera turns, and nothing between
 * the eye and the floor. The model itself is never touched: only the renderer's copy of its
 * `.glb` ([RerunScanModel.glbBuffer]), in place, its JSON and its vertices as they were.
 *
 * A dropped triangle is written degenerate, its three corners on its first vertex: drawn, it
 * covers no pixel. Any `.glb` of indexed triangles is read, the meshes [RerunMeshGlb] and the
 * textured model write alike; anything else is left whole.
 */
internal object ScanResultCut {
    /** The height above a room's floor its walls are cut at: under most windows, over most furniture. */
    const val HEIGHT_M = 1.2f

    /**
     * Cuts the `.glb` in [glb] — from its position 0, little-endian — at world height [cutY].
     * Returns the triangles dropped, 0 for a file this does not read.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth")
    fun cut(glb: ByteBuffer, cutY: Float): Int = runCatching {
        val buffer = glb.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.limit() < HEADER + CHUNK_HEADER || buffer.getInt(0) != GLB_MAGIC) return 0
        val jsonLength = buffer.getInt(HEADER)
        if (buffer.getInt(HEADER + 4) != CHUNK_JSON) return 0
        val jsonBytes = ByteArray(jsonLength)
        buffer.position(HEADER + CHUNK_HEADER)
        buffer.get(jsonBytes)
        val binStart = HEADER + CHUNK_HEADER + jsonLength + CHUNK_HEADER
        val root = lenient.parseToJsonElement(String(jsonBytes, Charsets.UTF_8)) as JsonObject
        val accessors = root["accessors"] as? JsonArray ?: return 0
        val views = root["bufferViews"] as? JsonArray ?: return 0
        val meshes = root["meshes"] as? JsonArray ?: return 0
        var dropped = 0
        for (mesh in meshes) {
            val primitives = (mesh as JsonObject)["primitives"] as? JsonArray ?: continue
            for (primitive in primitives) {
                val p = primitive as JsonObject
                if ((p.int("mode") ?: TRIANGLES) != TRIANGLES) continue
                val positionIndex = (p["attributes"] as? JsonObject)?.int("POSITION") ?: continue
                val indicesIndex = p.int("indices") ?: continue
                val position = accessors[positionIndex] as JsonObject
                val indices = accessors[indicesIndex] as JsonObject
                if (position.int("componentType") != FLOAT) continue
                val positionView = views[position.int("bufferView") ?: continue] as JsonObject
                val indicesView = views[indices.int("bufferView") ?: continue] as JsonObject
                val positionAt = binStart + (positionView.int("byteOffset") ?: 0) + (position.int("byteOffset") ?: 0)
                val stride = positionView.int("byteStride") ?: (3 * Float.SIZE_BYTES)
                val indexAt = binStart + (indicesView.int("byteOffset") ?: 0) + (indices.int("byteOffset") ?: 0)
                val indexType = indices.int("componentType")
                val indexSize = when (indexType) {
                    UNSIGNED_BYTE -> 1
                    UNSIGNED_SHORT -> 2
                    UNSIGNED_INT -> 4
                    else -> continue
                }
                val vertices = position.int("count") ?: continue
                val count = indices.int("count") ?: continue
                fun index(i: Int): Int = when (indexSize) {
                    1 -> buffer.get(indexAt + i).toInt() and BYTE_MASK
                    2 -> buffer.getShort(indexAt + i * 2).toInt() and SHORT_MASK
                    else -> buffer.getInt(indexAt + i * 4)
                }
                fun write(i: Int, value: Int) {
                    when (indexSize) {
                        1 -> buffer.put(indexAt + i, value.toByte())
                        2 -> buffer.putShort(indexAt + i * 2, value.toShort())
                        else -> buffer.putInt(indexAt + i * 4, value)
                    }
                }
                fun y(v: Int): Float = buffer.getFloat(positionAt + v * stride + Float.SIZE_BYTES)
                for (t in 0 until count / 3) {
                    val a = index(t * 3)
                    val b = index(t * 3 + 1)
                    val c = index(t * 3 + 2)
                    if (a !in 0 until vertices || b !in 0 until vertices || c !in 0 until vertices) continue
                    if ((y(a) + y(b) + y(c)) / 3f <= cutY) continue
                    write(t * 3 + 1, a)
                    write(t * 3 + 2, a)
                    dropped++
                }
            }
        }
        dropped
    }.getOrDefault(0)

    /** [indices] less the triangles [cut] drops at [cutY] from over [positions]: the reveal's wire, cut the same. */
    fun kept(positions: FloatArray, indices: IntArray, cutY: Float): IntArray {
        val out = IntArray(indices.size)
        var n = 0
        for (t in 0 until indices.size / 3) {
            val a = indices[t * 3]
            val b = indices[t * 3 + 1]
            val c = indices[t * 3 + 2]
            if ((positions[a * 3 + 1] + positions[b * 3 + 1] + positions[c * 3 + 1]) / 3f > cutY) continue
            out[n++] = a
            out[n++] = b
            out[n++] = c
        }
        return if (n == out.size) indices else out.copyOf(n)
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val HEADER = 12
    private const val CHUNK_HEADER = 8
    private const val GLB_MAGIC = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A
    private const val TRIANGLES = 4
    private const val FLOAT = 5126
    private const val UNSIGNED_BYTE = 5121
    private const val UNSIGNED_SHORT = 5123
    private const val UNSIGNED_INT = 5125
    private const val BYTE_MASK = 0xFF
    private const val SHORT_MASK = 0xFFFF
}
