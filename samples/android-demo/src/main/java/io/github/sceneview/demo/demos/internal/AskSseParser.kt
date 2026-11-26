package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Incremental SSE framing, independent of socket reads (including split CRLF delimiters). */
internal class AskSseParser {
    private val line = StringBuilder()
    private val data = mutableListOf<String>()
    private var eventSize = 0
    private var afterCr = false
    var terminal: Boolean = false
        private set

    fun feed(chars: CharSequence): List<AnswerChunk> = buildList {
        for (char in chars) {
            if (terminal) break
            if (afterCr && char == '\n') {
                afterCr = false
                continue
            }
            afterCr = char == '\r'
            if (char == '\r' || char == '\n') {
                consumeLine()?.let(::add)
                line.clear()
            } else {
                require(line.length < MAX_EVENT_CHARS) { "SSE line too long" }
                line.append(char)
            }
        }
    }

    private fun consumeLine(): AnswerChunk? {
        if (line.isEmpty()) {
            if (data.isEmpty()) return null
            val payload = data.joinToString("\n")
            data.clear()
            eventSize = 0
            return decode(payload).also { terminal = it !is AnswerChunk.Text }
        }
        if (line.startsWith(":")) return null
        val separator = line.indexOf(":")
        val field = if (separator < 0) line.toString() else line.substring(0, separator)
        if (field == "data") {
            val value = if (separator < 0) "" else line.substring(separator + 1).removePrefix(" ")
            eventSize += value.length + 1
            require(eventSize <= MAX_EVENT_CHARS) { "SSE event too long" }
            data += value
        }
        return null
    }

    private fun decode(payload: String): AnswerChunk {
        val json = Json.parseToJsonElement(payload) as? JsonObject ?: error("Invalid SSE object")
        fun string(key: String): String? = (json[key] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        string("error")?.let { return AnswerChunk.Failed(it) }
        if ((json["done"] as? JsonPrimitive)?.booleanOrNull == true) {
            val doodle = when (string("doodle")) {
                "underline" -> Doodle.Underline
                "circle" -> Doodle.Circle
                "arrow" -> Doodle.Arrow
                "none" -> Doodle.None
                else -> error("Invalid doodle")
            }
            return AnswerChunk.Done(doodle, string("emphasis") ?: error("Missing emphasis"))
        }
        return AnswerChunk.Text(string("t") ?: error("Missing text delta"))
    }

    private companion object {
        const val MAX_EVENT_CHARS = 16_384
    }
}
