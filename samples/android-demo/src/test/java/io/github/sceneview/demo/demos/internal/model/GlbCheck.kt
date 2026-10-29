package io.github.sceneview.demo.demos.internal.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The glTF 2.0 binary rules a strict viewer enforces, checked on a written model: the header and
 * chunk lengths, every buffer view inside the buffer, every accessor inside its view, index
 * values within the vertex count, texture coordinates in the atlas, and an embedded image that
 * decodes. Returns what breaks them, empty when none does.
 */
internal object GlbCheck {
    /** [glb]'s JSON chunk. */
    fun json(glb: ByteArray): JsonObject {
        val length = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
        return Json.parseToJsonElement(String(glb, 20, length, Charsets.UTF_8)).jsonObject
    }

    fun problems(glb: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val bytes = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.getInt(0) != MAGIC) out += "not glTF binary"
        if (bytes.getInt(4) != 2) out += "version ${bytes.getInt(4)}"
        if (bytes.getInt(8) != glb.size) out += "length ${bytes.getInt(8)} != ${glb.size}"
        val jsonLength = bytes.getInt(12)
        if (bytes.getInt(16) != JSON_CHUNK || jsonLength % 4 != 0) out += "bad JSON chunk"
        val binHeader = 20 + jsonLength
        val binLength = bytes.getInt(binHeader)
        if (bytes.getInt(binHeader + 4) != BIN_CHUNK || binLength % 4 != 0) out += "bad BIN chunk"
        val bin = binHeader + 8
        val json = Json.parseToJsonElement(String(glb, 20, jsonLength, Charsets.UTF_8)).jsonObject
        val bufferLength = json.list("buffers")[0].jsonObject.int("byteLength")
        if (bufferLength > binLength) out += "buffer $bufferLength > BIN $binLength"
        val views = json.list("bufferViews").map { it.jsonObject }
        views.forEachIndexed { i, v ->
            val end = v.int("byteOffset") + v.int("byteLength")
            if (end > bufferLength) out += "bufferView $i ends at $end > $bufferLength"
        }
        val accessors = json.list("accessors").map { it.jsonObject }
        accessors.forEachIndexed { i, a ->
            val view = views[a.int("bufferView")]
            val size = COMPONENT[a.int("componentType")]!! * TYPE[a.str("type")]!!
            val stride = view["byteStride"]?.jsonPrimitive?.int ?: size
            val need = a.int("byteOffset") + (a.int("count") - 1) * stride + size
            if (need > view.int("byteLength")) out += "accessor $i needs $need > ${view.int("byteLength")}"
        }
        val primitives = json.list("meshes").flatMap { it.jsonObject.list("primitives") }.map { it.jsonObject }
        for (p in primitives) {
            val attributes = p["attributes"]!!.jsonObject
            val vertices = accessors[attributes.int("POSITION")].int("count")
            for ((name, index) in attributes) {
                if (accessors[index.jsonPrimitive.int].int("count") != vertices) out += "$name count != POSITION count"
            }
            val indices = accessors[p.int("indices")]
            val view = views[indices.int("bufferView")]
            val at = bin + view.int("byteOffset") + indices.int("byteOffset")
            val short = indices.int("componentType") == UNSIGNED_SHORT
            for (k in 0 until indices.int("count")) {
                val value = if (short) bytes.getShort(at + k * 2).toInt() and 0xFFFF else bytes.getInt(at + k * 4)
                if (value !in 0 until vertices) {
                    out += "index $value outside $vertices vertices"
                    break
                }
            }
            attributes["TEXCOORD_0"]?.let { uvIndex ->
                val uv = accessors[uvIndex.jsonPrimitive.int]
                val uvAt = bin + views[uv.int("bufferView")].int("byteOffset")
                for (k in 0 until uv.int("count") * 2) {
                    val value = bytes.getFloat(uvAt + k * 4)
                    if (value !in 0f..1f) {
                        out += "uv $value outside 0..1"
                        break
                    }
                }
            }
        }
        json["images"]?.jsonArray?.forEach { image ->
            val view = views[image.jsonObject.int("bufferView")]
            val data = glb.copyOfRange(bin + view.int("byteOffset"), bin + view.int("byteOffset") + view.int("byteLength"))
            if (JvmImages.decode(data) == null) out += "image does not decode"
        }
        return out
    }

    private fun JsonObject.list(key: String): JsonArray = this[key]?.jsonArray ?: JsonArray(emptyList())

    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.int ?: 0

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    private const val MAGIC = 0x46546C67
    private const val JSON_CHUNK = 0x4E4F534A
    private const val BIN_CHUNK = 0x004E4942
    private const val UNSIGNED_SHORT = 5123
    private val COMPONENT = mapOf(5120 to 1, 5121 to 1, 5122 to 2, 5123 to 2, 5125 to 4, 5126 to 4)
    private val TYPE = mapOf("SCALAR" to 1, "VEC2" to 2, "VEC3" to 3, "VEC4" to 4)
}
