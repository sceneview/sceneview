package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The plain JSON tree the `.glb` writer edits — `Map<String, Any?>`, `List<Any?>`, `String`,
 * `Boolean`, `Int` / `Long` / `Double`, `null` — and its serialisation with sorted keys and
 * unescaped slashes, as `JSONSerialization(.sortedKeys, .withoutEscapingSlashes)` writes it.
 */
internal object RerunJson {
    private val parser = Json { isLenient = false }

    /** [text] as a plain tree; throws on malformed JSON. */
    fun parse(text: String): Any? = plain(parser.parseToJsonElement(text))

    /** [value] as compact JSON, object keys sorted. */
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun plain(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> element.mapValuesTo(LinkedHashMap()) { plain(it.value) }
        is JsonArray -> element.mapTo(ArrayList()) { plain(it) }
        is JsonPrimitive -> primitive(element)
    }

    private fun primitive(element: JsonPrimitive): Any? {
        if (element.isString) return element.content
        element.booleanOrNull?.let { return it }
        val text = element.content
        val integral = text.none { it == '.' || it == 'e' || it == 'E' }
        if (integral) {
            text.toLongOrNull()?.let { long ->
                return if (long in Int.MIN_VALUE..Int.MAX_VALUE) long.toInt() else long
            }
        }
        return text.toDouble()
    }

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> appendString(out, value)
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value)
            is Float -> appendDouble(out, value.toDouble())
            is Double -> appendDouble(out, value)
            is Map<*, *> -> appendObject(out, value)
            is Iterable<*> -> appendArray(out, value)
            else -> error("not a JSON value: ${value::class.simpleName}")
        }
    }

    private fun appendDouble(out: StringBuilder, value: Double) {
        require(value.isFinite()) { "JSON has no $value" }
        val long = value.toLong()
        // Integral values are written as integers, as Foundation does.
        if (long.toDouble() == value && kotlin.math.abs(value) < INTEGRAL_LIMIT) out.append(long) else out.append(value)
    }

    private fun appendObject(out: StringBuilder, value: Map<*, *>) {
        out.append('{')
        value.keys.map { it as String }.sorted().forEachIndexed { index, key ->
            if (index > 0) out.append(',')
            appendString(out, key)
            out.append(':')
            append(out, value[key])
        }
        out.append('}')
    }

    private fun appendArray(out: StringBuilder, value: Iterable<*>) {
        out.append('[')
        value.forEachIndexed { index, item ->
            if (index > 0) out.append(',')
            append(out, item)
        }
        out.append(']')
    }

    private fun appendString(out: StringBuilder, value: String) {
        out.append('"')
        for (char in value) {
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char < ' ' -> out.append("\\u%04x".format(char.code))
                else -> out.append(char)
            }
        }
        out.append('"')
    }

    private const val INTEGRAL_LIMIT = 1e15
}

/** A mutable JSON object of the plain tree [RerunJson] reads and writes. */
internal typealias JsonMap = MutableMap<String, Any?>

/** A [JsonMap] of [pairs], in order. */
internal fun jsonOf(vararg pairs: Pair<String, Any?>): JsonMap = linkedMapOf(*pairs)

/** `this[key]` as a JSON object, or `null`. */
@Suppress("UNCHECKED_CAST") // RerunJson's trees only hold String keys
internal fun Map<String, Any?>.obj(key: String): JsonMap? = this[key] as? JsonMap

/** `this[key]` as a list of JSON objects (non-objects dropped), empty when absent. */
@Suppress("UNCHECKED_CAST") // RerunJson's trees only hold String keys
internal fun Map<String, Any?>.objects(key: String): List<JsonMap> =
    (this[key] as? List<*>)?.mapNotNull { it as? JsonMap } ?: emptyList()

/** `this[key]` as a list of integers, or `null` when it is not one. */
internal fun Map<String, Any?>.ints(key: String): List<Int>? =
    (this[key] as? List<*>)?.let { list -> list.map { it as? Int ?: return null } }
