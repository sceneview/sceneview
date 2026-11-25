package io.github.sceneview.demo.demos.internal

import kotlin.math.max

/**
 * Append-only ink, with a fixed four-line framing box so the existing AnswerInk renderer
 * neither recentres nor rescales earlier strokes. Extremely long answers can overflow it.
 * Call on one thread (the UI thread in the demo). Complete words alone reach the layout.
 * [HandwritingLayout] still owns glyphs, joins, smoothing, pressure, wrapping and doodles.
 */
internal class IncrementalHandwriting(
    private val style: HandwritingStyle = HandwritingStyle(),
) {
    private val unit = style.capHeight / HersheyScriptSimplex.CAP_HEIGHT
    private val lineHeight = style.capHeight * style.lineSpacing
    private val left = -style.blockWidth / 2f
    private val firstBaseline = 1.5f * lineHeight - style.capHeight / 2f
    private val wordStyle = style.copy(minSeconds = 0f, maxSeconds = Float.MAX_VALUE)
    private val pending = StringBuilder()
    private val words = mutableListOf<String>()
    private var lines: List<List<String>> = emptyList()
    private val strokes = mutableListOf<InkStroke>()
    private val starts = mutableListOf<Float>()
    private var clock = 0f
    private var separated = true
    private var pendingSeparated = true
    private var complete = false
    private var emphasis: InkBox? = null

    val frameBounds = InkBox(
        left - style.capHeight, -2f * lineHeight - style.capHeight,
        -left + style.capHeight, 2f * lineHeight + style.capHeight,
    )

    fun append(delta: String, elapsedSeconds: Float = 0f): Handwriting {
        check(!complete) { "This answer is already complete" }
        for (raw in delta) {
            val char = HersheyFont.normalize(raw)
            if (char == '*') continue
            // A period after a digit may be a decimal point split across network deltas.
            if (pending.endsWith(".") && pending.length > 1 &&
                pending[pending.lastIndex - 1].isDigit() && !char.isDigit()
            ) release(elapsedSeconds)
            when {
                char.isWhitespace() -> {
                    release(elapsedSeconds)
                    separated = true
                }
                HersheyFont.covers(char) -> {
                    if (pending.isEmpty()) pendingSeparated = separated
                    pending.append(char)
                    separated = false
                    if (char in ",!?;:.)" && !(char == '.' &&
                            pending.length > 1 && pending[pending.lastIndex - 1].isDigit())
                    ) release(elapsedSeconds)
                }
            }
        }
        return snapshot()
    }

    /** Done is the only event that releases the final unterminated word and adds doodles. */
    fun finish(doodle: Doodle, markedPhrase: String, elapsedSeconds: Float = 0f): Handwriting {
        if (complete) return snapshot()
        release(elapsedSeconds)
        complete = true
        val text = words.joinToString(" ")
        val phrase = markedPhrase.replace("*", "").trim().split(Regex("\\s+")).joinToString(" ")
        val from = if (phrase.isEmpty()) -1 else text.indexOf(phrase)
        if (from >= 0 && doodle != Doodle.None) {
            var offset = 0
            lines.forEachIndexed { index, line ->
                val lineText = line.joinToString(" ")
                val start = maxOf(from, offset) - offset
                val end = minOf(from + phrase.length, offset + lineText.length) - offset
                if (start < end) {
                    val marked = lineText.substring(0, start) + "*" +
                        lineText.substring(start, end) + "*" + lineText.substring(end)
                    val width = lineWidth(line) * unit
                    val layout = HandwritingLayout.layout(
                        marked, doodle,
                        wordStyle.copy(blockWidth = max(style.blockWidth, width + unit)),
                    )
                    val dx = left + width / 2f
                    val dy = firstBaseline - index * lineHeight + style.capHeight / 2f
                    layout.emphasis?.let { box ->
                        val moved = InkBox(box.minX + dx, box.minY + dy, box.maxX + dx, box.maxY + dy)
                        emphasis = emphasis?.union(moved) ?: moved
                    }
                    val first = layout.strokes.indexOfFirst { it.kind == InkKind.Doodle }
                    if (first >= 0) {
                        val begins = max(clock, elapsedSeconds) + style.doodlePause
                        layout.strokes.indices.filter { layout.strokes[it].kind == InkKind.Doodle }.forEach { i ->
                            addStroke(layout.strokes[i], dx, dy, begins + layout.starts[i] - layout.starts[first])
                        }
                    }
                }
                offset += lineText.length + 1
            }
        }
        return snapshot()
    }

    fun snapshot(): Handwriting = Handwriting(
        lines.map { it.toList() }, strokes.toList(), starts.toFloatArray(), frameBounds, emphasis,
    )

    private fun release(elapsed: Float) {
        if (pending.isEmpty()) return
        val word = pending.toString()
        pending.clear()
        val previousLine = lines.lastIndex
        val continuation = !pendingSeparated && words.isNotEmpty()
        val x: Float
        val lineIndex: Int
        if (continuation) {
            // Punctuation may release before whitespace arrives. Further punctuation or letters
            // attach to that same word without moving it, even if it overflows the line.
            x = lineWidth(lines.last()) + HersheyFont.LETTER_GAP
            lineIndex = lines.lastIndex
            words[words.lastIndex] += word
            lines = lines.dropLast(1) + listOf(lines.last().dropLast(1) + words.last())
        } else {
            words += word
            // Ask the existing wrapper whether the word fits, but never rewrap old words
            // (a punctuation continuation may have made their line overflow).
            val tail = lines.lastOrNull().orEmpty() + word
            val fits = HandwritingLayout.wrap(tail, style.blockWidth / unit).size == 1
            lines = if (lines.isNotEmpty() && fits) lines.dropLast(1) + listOf(tail)
            else lines + listOf(listOf(word))
            lineIndex = lines.lastIndex
            val before = lines.last().dropLast(1)
            x = if (before.isEmpty()) 0f else lineWidth(before) + HersheyFont.SPACE_ADVANCE
        }
        val layout = HandwritingLayout.layout(word, style = wordStyle)
        val dx = left + x * unit + HandwritingLayout.wordWidth(word) * unit / 2f
        val dy = firstBaseline - lineIndex * lineHeight + style.capHeight / 2f
        val gap = when {
            strokes.isEmpty() || continuation -> 0f
            lineIndex > previousLine -> style.lineGap
            else -> style.wordGap
        }
        val begins = max(clock, elapsed) + gap
        layout.strokes.forEachIndexed { index, stroke ->
            addStroke(stroke, dx, dy, begins + layout.starts[index])
        }
    }

    private fun addStroke(stroke: InkStroke, dx: Float, dy: Float, start: Float) {
        strokes += InkStroke(
            strokes.size, stroke.kind, stroke.points.map { InkPoint(it.x + dx, it.y + dy) },
            stroke.radii, stroke.times,
        )
        starts += start
        clock = start + stroke.duration
    }

    private fun lineWidth(line: List<String>): Float =
        line.sumOf { HandwritingLayout.wordWidth(it).toDouble() }.toFloat() +
            (line.size - 1).coerceAtLeast(0) * HersheyFont.SPACE_ADVANCE
}

/** UI-thread collector state: a replacement after Failed gets fresh ink, never mixed text. */
internal class AskStreamingInk(private val style: HandwritingStyle = HandwritingStyle()) {
    private var writing = IncrementalHandwriting(style)
    private var failed = false
    var failureReason: String? = null
        private set

    fun accept(chunk: AnswerChunk, elapsedSeconds: Float): Handwriting = when (chunk) {
        is AnswerChunk.Text -> {
            if (failed) writing = IncrementalHandwriting(style)
            failed = false
            failureReason = null
            writing.append(chunk.delta, elapsedSeconds)
        }
        is AnswerChunk.Done -> writing.finish(chunk.doodle, chunk.emphasis, elapsedSeconds)
        is AnswerChunk.Failed -> {
            failed = true
            failureReason = chunk.reason
            writing.snapshot()
        }
    }

    fun snapshot(): Handwriting = writing.snapshot()
}
