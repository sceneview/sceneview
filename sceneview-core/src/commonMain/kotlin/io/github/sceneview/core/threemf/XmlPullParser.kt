package io.github.sceneview.core.threemf

/**
 * A tiny, allocation-frugal XML pull parser — the multiplatform stand-in for `org.xmlpull` /
 * `XMLParser`, written because Kotlin/JS and Kotlin/Native have no shared XML reader and a 3MF's
 * `3D/3dmodel.model` is the only document this SDK has to read.
 *
 * **Pull, not DOM, on purpose.** A printable model is millions of `<vertex .../>` and
 * `<triangle .../>` elements; materialising one object per element would cost hundreds of megabytes
 * on a phone. The caller walks events and appends straight into primitive arrays.
 *
 * Scope is deliberately the subset 3MF uses: elements, attributes, self-closing tags, comments,
 * CDATA, processing instructions and `<!DOCTYPE>`. Character data between tags is skipped — no 3MF
 * geometry lives in text nodes. Namespace prefixes are stripped from element names (`m:colorgroup`
 * reads as `colorgroup`), which is exactly the matching a single-vocabulary document needs, and
 * makes the parser indifferent to which prefix a writer chose.
 */
internal class XmlPullParser(private val text: String) {

    /** The event produced by the last [next] call. */
    var eventType: Int = START_DOCUMENT
        private set

    /** Local name (prefix stripped) of the element for [START_TAG] / [END_TAG]. */
    var name: String = ""
        private set

    /** Nesting depth after the current event: a [START_TAG] at the root reports 1. */
    var depth: Int = 0
        private set

    private var position = 0
    private var selfClosingPending = false
    private var attributeNames = ArrayList<String>(ATTRIBUTES_HINT)
    private var attributeValues = ArrayList<String>(ATTRIBUTES_HINT)

    /** Value of attribute [attributeName] on the current [START_TAG], or `null` if absent. */
    fun attribute(attributeName: String): String? {
        for (i in attributeNames.indices) {
            if (attributeNames[i] == attributeName) return attributeValues[i]
        }
        return null
    }

    /** Advance to the next event and return it. */
    fun next(): Int {
        if (selfClosingPending) {
            selfClosingPending = false
            depth--
            eventType = END_TAG
            return eventType
        }
        skipToTag()
        if (position >= text.length) {
            eventType = END_DOCUMENT
            return eventType
        }
        return readTag()
    }

    /**
     * Skip the whole subtree of the [START_TAG] just returned, leaving the parser positioned on its
     * matching [END_TAG]. A no-op on any other event.
     */
    fun skipSubtree() {
        if (eventType != START_TAG) return
        val target = depth
        while (eventType != END_DOCUMENT && !(eventType == END_TAG && depth == target - 1)) {
            next()
        }
    }

    private fun skipToTag() {
        while (position < text.length && text[position] != '<') position++
    }

    private fun readTag(): Int {
        position++ // consume '<'
        return when {
            startsWith("!--") -> skipUntil("-->")
            startsWith("![CDATA[") -> skipUntil("]]>")
            position < text.length && (text[position] == '?' || text[position] == '!') ->
                skipUntil(">")
            position < text.length && text[position] == '/' -> readEndTag()
            else -> readStartTag()
        }
    }

    private fun startsWith(token: String): Boolean = text.startsWith(token, position)

    private fun skipUntil(terminator: String): Int {
        val end = text.indexOf(terminator, position)
        position = if (end < 0) text.length else end + terminator.length
        return next()
    }

    private fun readEndTag(): Int {
        position++ // consume '/'
        val end = text.indexOf('>', position)
        if (end < 0) threeMfError("XML: unterminated closing tag")
        name = localName(text.substring(position, end).trim())
        position = end + 1
        depth--
        eventType = END_TAG
        return eventType
    }

    private fun readStartTag(): Int {
        val nameEnd = scanName()
        name = localName(text.substring(position, nameEnd))
        position = nameEnd
        attributeNames.clear()
        attributeValues.clear()
        readAttributes()
        depth++
        eventType = START_TAG
        return eventType
    }

    private fun scanName(): Int {
        var at = position
        while (at < text.length && !text[at].isXmlNameBoundary()) at++
        if (at == position) threeMfError("XML: empty element name")
        return at
    }

    private fun readAttributes() {
        while (position < text.length) {
            skipWhitespace()
            if (position >= text.length) threeMfError("XML: unterminated element <$name>")
            when (text[position]) {
                '>' -> {
                    position++
                    return
                }

                '/' -> {
                    selfClosingPending = true
                    position = text.indexOf('>', position).let {
                        if (it < 0) threeMfError("XML: unterminated element <$name>") else it + 1
                    }
                    return
                }

                else -> readAttribute()
            }
        }
        threeMfError("XML: unterminated element <$name>")
    }

    private fun readAttribute() {
        val nameEnd = scanName()
        val attributeName = localName(text.substring(position, nameEnd))
        position = nameEnd
        skipWhitespace()
        if (position >= text.length || text[position] != '=') {
            threeMfError("XML: attribute \"$attributeName\" of <$name> has no value")
        }
        position++
        skipWhitespace()
        val quote = if (position < text.length) text[position] else ' '
        if (quote != '"' && quote != '\'') {
            threeMfError("XML: attribute \"$attributeName\" of <$name> is not quoted")
        }
        position++
        val end = text.indexOf(quote, position)
        if (end < 0) threeMfError("XML: unterminated value for attribute \"$attributeName\"")
        attributeNames += attributeName
        attributeValues += decodeEntities(text.substring(position, end))
        position = end + 1
    }

    private fun skipWhitespace() {
        while (position < text.length && text[position].isWhitespace()) position++
    }

    companion object {
        const val START_DOCUMENT = 0
        const val START_TAG = 1
        const val END_TAG = 2
        const val END_DOCUMENT = 3

        private const val ATTRIBUTES_HINT = 8
    }
}

private fun Char.isXmlNameBoundary(): Boolean =
    isWhitespace() || this == '=' || this == '>' || this == '/'

/** `m:colorgroup` → `colorgroup`; an unprefixed name is returned unchanged. */
private fun localName(qualified: String): String {
    val colon = qualified.indexOf(':')
    return if (colon < 0) qualified else qualified.substring(colon + 1)
}

/**
 * Expand the five predefined XML entities plus numeric character references. Attribute values in a
 * 3MF are numbers, ids and names, so the fast path — no `&` at all — is the one that runs for
 * essentially every attribute of every vertex and triangle.
 */
private fun decodeEntities(raw: String): String {
    if (!raw.contains('&')) return raw
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val char = raw[i]
        val end = if (char == '&') raw.indexOf(';', i) else -1
        if (end < 0) {
            // Not an entity (or an unterminated one): copy the character through verbatim.
            out.append(char)
            i++
        } else {
            out.append(expandEntity(raw.substring(i + 1, end)))
            i = end + 1
        }
    }
    return out.toString()
}

private fun expandEntity(body: String): String = when {
    body == "amp" -> "&"
    body == "lt" -> "<"
    body == "gt" -> ">"
    body == "quot" -> "\""
    body == "apos" -> "'"
    body.startsWith("#x") || body.startsWith("#X") ->
        body.drop(2).toIntOrNull(radix = 16)?.let { charOf(it) } ?: "&$body;"

    body.startsWith("#") -> body.drop(1).toIntOrNull()?.let { charOf(it) } ?: "&$body;"
    else -> "&$body;" // Unknown entity: leave the source text as-is rather than lose it.
}

private fun charOf(codePoint: Int): String? =
    if (codePoint in 0..MAX_BMP_CODE_POINT) codePoint.toChar().toString() else null

private const val MAX_BMP_CODE_POINT = 0xFFFF
