package io.github.sceneview.core.obj

/** Decode one logical line at a time, never a whole multi-megabyte OBJ or an array of its lines. */
internal inline fun objLines(bytes: ByteArray, consume: (ObjTokens, Int) -> Unit) {
    var at = objStart(bytes)
    var line = 0
    var continued: StringBuilder? = null
    while (at < bytes.size) {
        val start = at
        while (at < bytes.size && bytes[at] != 10.toByte() && bytes[at] != 13.toByte()) at++
        val text = bytes.decodeToString(start, at).trimEnd()
        if (at < bytes.size) {
            val carriageReturn = bytes[at++] == 13.toByte()
            if (carriageReturn && at < bytes.size && bytes[at] == 10.toByte()) at++
        }
        line++
        if (text.endsWith('\\')) {
            val pending = continued ?: StringBuilder().also { continued = it }
            pending.append(text.dropLast(1)).append(' ')
        } else {
            consume(ObjTokens(continued?.append(text)?.toString() ?: text), line)
            continued = null
        }
    }
    if (continued != null) objError("OBJ line $line: unfinished line continuation")
}

internal fun objStart(bytes: ByteArray): Int {
    val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
    val hasBom = bytes.size >= bom.size && bom.indices.all { bytes[it] == bom[it] }
    return if (hasBom) bom.size else 0
}

/** Whitespace tokens with inline comments; quoting is opt-in for library paths only. */
internal class ObjTokens(private val line: String) {
    private var at = 0

    fun next(quoted: Boolean = false): String? {
        while (at < line.length && line[at].isWhitespace()) at++
        if (at == line.length || line[at] == '#') return null
        if (quoted && (line[at] == '"' || line[at] == '\'')) {
            val quote = line[at++]
            val start = at
            while (at < line.length && line[at] != quote) at++
            if (at == line.length) objError("OBJ/MTL: unterminated quoted path")
            return line.substring(start, at++)
        }
        val start = at
        while (at < line.length && !line[at].isWhitespace() && line[at] != '#') at++
        return line.substring(start, at)
    }

    fun rest(): String = line.substring(at).substringBefore('#').trim()

    fun number(lineNumber: Int): Float = number(next(), lineNumber)
}

internal fun number(token: String?, line: Int): Float =
    token?.toFloatOrNull()?.takeIf { it.isFinite() }
        ?: objError("OBJ/MTL line $line: expected a finite number, got '$token'")

/** Primitive storage with indexed reads for resolving relative indices without boxing floats. */
internal class ObjFloats {
    private var values = FloatArray(256)
    var size = 0
        private set

    fun add(value: Float) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    operator fun get(index: Int): Float = values[index]
}
