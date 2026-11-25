package io.github.sceneview.demo.demos.internal

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A point on the writing block, in metres: x to the right, y up, origin at the block's centre. */
internal data class InkPoint(val x: Float, val y: Float) {
    fun distanceTo(other: InkPoint): Float {
        val dx = other.x - x
        val dy = other.y - y
        return sqrt(dx * dx + dy * dy)
    }
}

/** An axis-aligned box on the writing block, in metres. */
internal data class InkBox(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY
    val centerX: Float get() = (minX + maxX) / 2f
    val centerY: Float get() = (minY + maxY) / 2f

    fun union(other: InkBox) = InkBox(
        min(minX, other.minX), min(minY, other.minY), max(maxX, other.maxX), max(maxY, other.maxY),
    )
}

/**
 * One character of [HersheyScriptSimplex], decoded.
 *
 * @param strokes Pen strokes in grid units, baseline at y = 0.
 * @param advance How far the pen moves right after the character, in grid units.
 */
internal class HersheyGlyph(val strokes: List<List<InkPoint>>, val advance: Float)

/** Lookup into the vendored Hershey Script Simplex data. */
internal object HersheyFont {

    /** Pen travel between two letters, in grid units: close enough for script letters to join. */
    const val LETTER_GAP = 2f

    /** Width of a space, in grid units (the font's own word spacing). */
    const val SPACE_ADVANCE = 15.75f

    private val cache = HashMap<Char, HersheyGlyph?>()

    /**
     * Typographic characters an answer may carry, mapped onto what the font has. Anything
     * else outside the font is dropped by [glyph].
     */
    fun normalize(c: Char): Char = when (c) {
        '‘', '’', 'ʼ' -> '\''
        '“', '”' -> '"'
        '–', '—', '−' -> '-'
        ' ' -> ' '
        else -> c
    }

    /** The glyph of [c], or `null` for a space or a character the font does not draw. */
    fun glyph(c: Char): HersheyGlyph? {
        val key = normalize(c)
        return cache.getOrPut(key) { decode(key) }
    }

    /** Whether [c] is drawn — letters, digits and basic punctuation are. */
    fun covers(c: Char): Boolean = glyph(c) != null

    private fun decode(c: Char): HersheyGlyph? {
        val index = c - HersheyScriptSimplex.FIRST
        if (index !in HersheyScriptSimplex.GLYPHS.indices) return null
        val data = HersheyScriptSimplex.GLYPHS[index]
        if (data.isEmpty()) return null
        val strokes = data.split(' ').map { stroke ->
            List(stroke.length / 2) { i ->
                InkPoint(
                    (stroke[2 * i].code - HersheyScriptSimplex.ZERO).toFloat(),
                    (stroke[2 * i + 1].code - HersheyScriptSimplex.ZERO).toFloat(),
                )
            }
        }
        val maxX = strokes.maxOf { s -> s.maxOf { it.x } }
        return HersheyGlyph(strokes, advance = maxX + LETTER_GAP)
    }
}

/** What a stroke of ink is for: [Text] is the answer, [Doodle] the mark drawn after it. */
internal enum class InkKind { Text, Doodle }

/**
 * One pen-down to pen-up line, ready for a tube: evenly spaced points and the radius at each.
 *
 * @param times Seconds from the stroke's first point to each point, at the stroke's pace —
 *              slower where it turns, the way a hand slows in a curve.
 */
internal class InkStroke(
    val id: Int,
    val kind: InkKind,
    val points: List<InkPoint>,
    val radii: List<Float>,
    val times: FloatArray,
) {
    val duration: Float get() = times.last()
}

/** A hand-drawn mark around, under or toward the emphasised words of an answer. */
internal enum class Doodle { None, Underline, Circle, Arrow }

/** How large and how fast the answer is written. Lengths in metres, times in seconds. */
internal data class HandwritingStyle(
    val blockWidth: Float = 1.2f,
    val capHeight: Float = 0.1f,
    val lineSpacing: Float = 1.85f,
    val inkRadius: Float = 0.0055f,
    val pointSpacing: Float = 0.005f,
    val taperLength: Float = 0.03f,
    val minSeconds: Float = 2.8f,
    val maxSeconds: Float = 4.5f,
    val nominalSpeed: Float = 1.4f,
    val strokeGap: Float = 0.03f,
    val wordGap: Float = 0.1f,
    val lineGap: Float = 0.18f,
    val doodlePause: Float = 0.3f,
    val doodleSpeed: Float = 2.6f,
    val maxDoodleSeconds: Float = 0.8f,
)

/**
 * An answer laid out on the writing block and timed: [strokes] in writing order, each with a
 * [starts] time.
 *
 * @param lines   The words of each line, as written.
 * @param bounds  Every stroke's extent, doodle included.
 * @param emphasis The extent of the emphasised words, if the answer marked any.
 */
internal class Handwriting(
    val lines: List<List<String>>,
    val strokes: List<InkStroke>,
    val starts: FloatArray,
    val bounds: InkBox,
    val emphasis: InkBox?,
) {
    /** Seconds until the last stroke is finished. */
    val totalSeconds: Float
        get() = if (strokes.isEmpty()) 0f else strokes.indices.maxOf { starts[it] + strokes[it].duration }

    /**
     * How many points of stroke [index] are drawn [elapsed] seconds into the writing, and where
     * the pen tip is between the last one and the next. `0` before the stroke starts.
     */
    fun progressOf(index: Int, elapsed: Float): StrokeProgress {
        val stroke = strokes[index]
        val local = elapsed - starts[index]
        if (local <= 0f) return StrokeProgress(0, 0f)
        if (local >= stroke.duration) return StrokeProgress(stroke.points.size, 0f)
        val times = stroke.times
        // The last point already passed, by binary search: a word can be hundreds of points.
        var lo = 0
        var hi = times.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (times[mid] <= local) lo = mid else hi = mid - 1
        }
        val span = times[lo + 1] - times[lo]
        val fraction = if (span > 0f) (local - times[lo]) / span else 0f
        return StrokeProgress(lo + 1, fraction.coerceIn(0f, 1f))
    }
}

/**
 * The drawn part of a stroke: its first [count] points, then a tip [tipFraction] of the way to
 * the next one. `count == points.size` means finished.
 */
internal data class StrokeProgress(val count: Int, val tipFraction: Float)

/**
 * Lays an answer out as handwriting on a block of [HandwritingStyle.blockWidth] metres, then
 * times it at a believable pace.
 *
 * The answer text may wrap words in `*asterisks*` to name what the doodle marks.
 *
 * Letters of one word are joined into one continuous stroke where the script lets the pen
 * stay down; the dot of an `i`, the bar of a `t` and other short marks are made once the word
 * is written, as a hand does.
 */
internal object HandwritingLayout {

    /** A mark this short (in grid units) is made after its word, not in the flow of it. */
    private const val LATE_MARK_LENGTH = 9f

    /** Two strokes whose ends are this close (grid units) are written without lifting the pen. */
    private const val JOIN_DISTANCE = 5f

    /** Lowest a late mark sits, in grid units: above the small letters. */
    private const val LATE_MARK_FLOOR = 11f

    /**
     * The dot of an `i` or a `j`, the bar of a `t`: a short stroke, high above the baseline,
     * in a character that has others. A hand makes it once the word is written.
     */
    private fun isLateMark(glyph: HersheyGlyph, stroke: List<InkPoint>) =
        glyph.strokes.size > 1 && polylineLength(stroke) < LATE_MARK_LENGTH &&
            stroke.minOf { it.y } >= LATE_MARK_FLOOR

    /** Words with their emphasis flags, the `*` markers taken out. */
    internal data class Word(val text: String, val emphasised: BooleanArray)

    fun parse(text: String): List<Word> {
        var emphasised = false
        val words = mutableListOf<Word>()
        val chars = StringBuilder()
        val flags = mutableListOf<Boolean>()
        fun flush() {
            if (chars.isNotEmpty()) words += Word(chars.toString(), flags.toBooleanArray())
            chars.clear()
            flags.clear()
        }
        for (raw in text) {
            val c = HersheyFont.normalize(raw)
            when {
                c == '*' -> emphasised = !emphasised
                c.isWhitespace() -> flush()
                HersheyFont.covers(c) -> {
                    chars.append(c)
                    flags += emphasised
                }
            }
        }
        flush()
        return words
    }

    /** Width of [word] in grid units, without the gap after its last letter. */
    fun wordWidth(word: String): Float =
        word.sumOf { (HersheyFont.glyph(it)?.advance ?: 0f).toDouble() }.toFloat() - HersheyFont.LETTER_GAP

    /**
     * Greedy word wrap: as many words per line as fit in [maxWidth] grid units. A word wider
     * than a line gets a line of its own rather than being cut.
     */
    fun wrap(words: List<String>, maxWidth: Float): List<List<String>> {
        val lines = mutableListOf<MutableList<String>>()
        var width = 0f
        for (word in words) {
            val w = wordWidth(word)
            val current = lines.lastOrNull()
            if (current != null && width + HersheyFont.SPACE_ADVANCE + w <= maxWidth) {
                current += word
                width += HersheyFont.SPACE_ADVANCE + w
            } else {
                lines += mutableListOf(word)
                width = w
            }
        }
        return lines
    }

    fun layout(
        text: String,
        doodle: Doodle = Doodle.None,
        style: HandwritingStyle = HandwritingStyle(),
        seed: Int = text.hashCode(),
    ): Handwriting {
        val unit = style.capHeight / HersheyScriptSimplex.CAP_HEIGHT
        val words = parse(text)
        val lines = wrap(words.map { it.text }, style.blockWidth / unit)
        val lineHeight = style.capHeight * style.lineSpacing
        val blockWidth = lines.maxOfOrNull { line -> lineWidth(line) } ?: 0f
        // Centre the block on its anchor: half the widest line to the left, the middle line
        // at the anchor's height.
        val left = -blockWidth * unit / 2f
        val firstBaseline = (lines.size - 1) * lineHeight / 2f - style.capHeight / 2f

        val raw = mutableListOf<Pair<List<InkPoint>, Float>>() // stroke, pause before it (s)
        var emphasis: InkBox? = null
        // The extent of each line's ink, and the line the emphasis sits on: a doodle keeps
        // clear of the other words.
        val lineBoxes = MutableList<InkBox?>(lines.size) { null }
        var emphasisLine = 0
        var wordIndex = 0
        lines.forEachIndexed { lineIndex, line ->
            var penX = 0f
            val baseline = firstBaseline - lineIndex * lineHeight
            line.forEachIndexed { indexInLine, _ ->
                val word = words[wordIndex++]
                val flow = mutableListOf<MutableList<InkPoint>>()
                val late = mutableListOf<List<InkPoint>>()
                var x = penX
                // Which character the stroke being written belongs to: letters run into the
                // next letter, punctuation stands apart.
                var tailOwner = -1
                word.text.forEachIndexed { charIndex, c ->
                    val glyph = HersheyFont.glyph(c) ?: return@forEachIndexed
                    for (stroke in glyph.strokes) {
                        val placed = stroke.map { InkPoint(left + (x + it.x) * unit, baseline + it.y * unit) }
                        val tail = flow.lastOrNull()?.last()
                        val joinable = tailOwner == charIndex ||
                            (tailOwner >= 0 && c.isLetterOrDigit() && word.text[tailOwner].isLetterOrDigit())
                        when {
                            tail != null && joinable && tail.distanceTo(placed.first()) <= JOIN_DISTANCE * unit -> {
                                flow.last().addAll(placed.drop(1))
                                tailOwner = charIndex
                            }
                            isLateMark(glyph, stroke) -> late += placed
                            else -> {
                                flow += placed.toMutableList()
                                tailOwner = charIndex
                            }
                        }
                        val box = boxOf(placed)
                        lineBoxes[lineIndex] = lineBoxes[lineIndex]?.union(box) ?: box
                        if (word.emphasised[charIndex]) {
                            emphasis = emphasis?.union(box) ?: box
                            emphasisLine = lineIndex
                        }
                    }
                    x += glyph.advance
                }
                val pauseBefore = when {
                    indexInLine == 0 && lineIndex > 0 -> style.lineGap
                    indexInLine == 0 -> 0f
                    else -> style.wordGap
                }
                (flow + late).forEachIndexed { i, s -> raw += s to if (i == 0) pauseBefore else style.strokeGap }
                penX = x - HersheyFont.LETTER_GAP + HersheyFont.SPACE_ADVANCE
            }
        }

        val textLines = raw.map { (points, _) -> resample(smooth(points), style.pointSpacing) }
        val doodleLines = emphasis
            ?.let { doodleStrokes(doodle, it, lineBoxes[emphasisLine] ?: it, Random(seed), style) }.orEmpty()
            .map { resample(it, style.pointSpacing) }

        val strokes = mutableListOf<InkStroke>()
        val starts = mutableListOf<Float>()
        val textEffort = textLines.sumOf { effort(it).toDouble() }.toFloat()
        val (secondsPerMetre, pauseScale) = paceFor(textEffort, raw.sumOf { it.second.toDouble() }.toFloat(), style)
        var clock = 0f
        textLines.forEachIndexed { i, line ->
            clock += raw[i].second * pauseScale
            val stroke = toStroke(strokes.size, InkKind.Text, line, secondsPerMetre, style.inkRadius, style)
            strokes += stroke
            starts += clock
            clock += stroke.duration
        }
        clock += style.doodlePause
        // A doodle is one quick gesture: however big the words it rings, it is done in a beat.
        val doodleEffort = doodleLines.sumOf { effort(it).toDouble() }.toFloat()
        val doodlePace = if (doodleEffort > 0f) min(1f / style.doodleSpeed, style.maxDoodleSeconds / doodleEffort) else 0f
        doodleLines.forEach { line ->
            val stroke = toStroke(
                strokes.size, InkKind.Doodle, line, doodlePace, style.inkRadius * DOODLE_RADIUS, style,
            )
            strokes += stroke
            starts += clock
            clock += stroke.duration + style.strokeGap
        }
        val bounds = strokes.map { boxOf(it.points) }.reduceOrNull(InkBox::union) ?: InkBox(0f, 0f, 0f, 0f)
        return Handwriting(lines, strokes, starts.toFloatArray(), bounds, emphasis)
    }

    /**
     * The scale that fits [bounds] in [FIT_WIDTH] of [visibleWidth] and [FIT_HEIGHT] of
     * [visibleHeight] (metres the screen shows at the block's distance), never above 1.
     *
     * A phone held upright shows about 0.8 m across at 1.5 m, so the 1.2 m block shrinks to
     * fit it there; held sideways, or on a wall further away, it is written full size. The
     * height share leaves room for the caption and the mic.
     */
    fun fitScale(bounds: InkBox, visibleWidth: Float, visibleHeight: Float): Float {
        if (bounds.width <= 0f || bounds.height <= 0f) return 1f
        return minOf(1f, FIT_WIDTH * visibleWidth / bounds.width, FIT_HEIGHT * visibleHeight / bounds.height)
    }

    /** Share of the screen's width the block may take. */
    const val FIT_WIDTH = 0.8f

    /** Share of the screen's height the block may take, between the top bar and the mic. */
    const val FIT_HEIGHT = 0.5f

    private fun lineWidth(line: List<String>): Float =
        line.sumOf { wordWidth(it).toDouble() }.toFloat() + (line.size - 1) * HersheyFont.SPACE_ADVANCE

    /**
     * Seconds per metre of [effort], and the share of each pause kept, that write the text in
     * [HandwritingStyle.minSeconds] to [HandwritingStyle.maxSeconds]: a short answer is not
     * rushed, a long one does not drag. The pen lifts never take more than
     * [MAX_PAUSE_SHARE] of the time, however many there are.
     */
    internal fun paceFor(effort: Float, pauses: Float, style: HandwritingStyle): Pair<Float, Float> {
        if (effort <= 0f) return 0f to 1f
        val seconds = (effort / style.nominalSpeed + pauses).coerceIn(style.minSeconds, style.maxSeconds)
        val pauseScale = if (pauses > 0f) min(1f, seconds * MAX_PAUSE_SHARE / pauses) else 1f
        return (seconds - pauses * pauseScale) / effort to pauseScale
    }

    /**
     * How much writing a line is, in metres: its length, plus [CURVE_EFFORT] per radian it
     * turns. A hand slows down in a curve; timing on effort rather than length does too.
     */
    internal fun effort(line: List<InkPoint>): Float {
        var total = 0f
        for (i in 1 until line.size) total += stepEffort(line, i)
        return total
    }

    private fun stepEffort(line: List<InkPoint>, i: Int): Float {
        val turn = if (i + 1 < line.size) turnAngle(line[i - 1], line[i], line[i + 1]) else 0f
        return line[i - 1].distanceTo(line[i]) + CURVE_EFFORT * turn
    }

    private fun toStroke(
        id: Int,
        kind: InkKind,
        line: List<InkPoint>,
        secondsPerMetre: Float,
        radius: Float,
        style: HandwritingStyle,
    ): InkStroke {
        val distances = FloatArray(line.size)
        val times = FloatArray(line.size)
        for (i in 1 until line.size) {
            distances[i] = distances[i - 1] + line[i - 1].distanceTo(line[i])
            times[i] = times[i - 1] + stepEffort(line, i) * secondsPerMetre
        }
        // A dot is a stroke of no length: give it the time a pen takes to tap.
        if (times.last() <= 0f) times[times.lastIndex] = DOT_SECONDS
        val length = distances.last()
        val radii = List(line.size) { i -> radius * pressure(distances[i], length, style.taperLength, i) }
        return InkStroke(id, kind, line, radii, times)
    }

    /**
     * Pen pressure along a stroke: a light touch down and lift off at the ends, and a slow
     * wobble in between, so the line looks drawn and not extruded.
     */
    internal fun pressure(distance: Float, length: Float, taper: Float, index: Int): Float {
        val fromEnd = min(distance, length - distance)
        val t = (fromEnd / max(taper, 1e-6f)).coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t)
        val wobble = 1f + 0.06f * sin(index * 0.37f)
        return (0.38f + 0.62f * eased) * wobble
    }

    private fun turnAngle(a: InkPoint, b: InkPoint, c: InkPoint): Float {
        val ux = b.x - a.x
        val uy = b.y - a.y
        val vx = c.x - b.x
        val vy = c.y - b.y
        val lu = sqrt(ux * ux + uy * uy)
        val lv = sqrt(vx * vx + vy * vy)
        if (lu < 1e-7f || lv < 1e-7f) return 0f
        return acos(((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1f, 1f))
    }

    /** One pass of Chaikin corner cutting, ends kept: rounds the font's straight segments. */
    internal fun smooth(points: List<InkPoint>): List<InkPoint> {
        if (points.size < 3) return points
        val out = ArrayList<InkPoint>(points.size * 2)
        out += points.first()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            out += InkPoint(0.75f * a.x + 0.25f * b.x, 0.75f * a.y + 0.25f * b.y)
            out += InkPoint(0.25f * a.x + 0.75f * b.x, 0.25f * a.y + 0.75f * b.y)
        }
        out += points.last()
        return out
    }

    /** Evenly spaced points along [points], [spacing] apart, both ends kept. */
    internal fun resample(points: List<InkPoint>, spacing: Float): List<InkPoint> {
        if (points.size < 2) return listOf(points.first(), points.first())
        val length = polylineLength(points)
        if (length < spacing) return listOf(points.first(), points.last())
        val count = ceil(length / spacing).toInt()
        val step = length / count
        val out = ArrayList<InkPoint>(count + 1)
        out += points.first()
        var segment = 0
        var segmentStart = 0f
        var segmentLength = points[0].distanceTo(points[1])
        for (k in 1 until count) {
            val target = k * step
            while (segmentStart + segmentLength < target && segment < points.size - 2) {
                segmentStart += segmentLength
                segment++
                segmentLength = points[segment].distanceTo(points[segment + 1])
            }
            val t = if (segmentLength > 0f) ((target - segmentStart) / segmentLength).coerceIn(0f, 1f) else 0f
            val a = points[segment]
            val b = points[segment + 1]
            out += InkPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }
        out += points.last()
        return out
    }

    internal fun polylineLength(points: List<InkPoint>): Float {
        var length = 0f
        for (i in 1 until points.size) length += points[i - 1].distanceTo(points[i])
        return length
    }

    private fun boxOf(points: List<InkPoint>) = InkBox(
        points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y },
    )

    // ── Doodles ──────────────────────────────────────────────────────────────────────────

    /**
     * The strokes of [doodle] around [target], the emphasised words, on a line whose ink spans
     * [line]. An arrow comes in from the side of the line [target] ends, and is an underline
     * when other words sit on both sides of it.
     */
    internal fun doodleStrokes(
        doodle: Doodle,
        target: InkBox,
        line: InkBox,
        random: Random,
        style: HandwritingStyle,
    ): List<List<InkPoint>> {
        val reach = style.capHeight * ARROW_CLEARANCE
        return when (doodle) {
            Doodle.None -> emptyList()
            Doodle.Underline -> listOf(underline(target, random, style))
            Doodle.Circle -> listOf(loop(target, random, style))
            Doodle.Arrow -> when {
                target.minX - line.minX < reach -> arrow(target, line, fromLeft = true, random, style)
                line.maxX - target.maxX < reach -> arrow(target, line, fromLeft = false, random, style)
                else -> listOf(underline(target, random, style))
            }
        }
    }

    /** A quick line under the words, rising a touch, with a small flick where the pen lifts. */
    private fun underline(target: InkBox, random: Random, style: HandwritingStyle): List<InkPoint> {
        val y = target.minY - style.capHeight * 0.3f
        val start = target.minX - style.capHeight * 0.25f
        val end = target.maxX + style.capHeight * 0.35f
        val rise = style.capHeight * (0.08f + 0.06f * random.nextFloat())
        val phase = random.nextFloat() * PI.toFloat()
        val n = 24
        val line = List(n + 1) { i ->
            val t = i / n.toFloat()
            InkPoint(
                start + (end - start) * t,
                y + rise * t + style.capHeight * 0.035f * sin(phase + t * 2.6f * PI.toFloat()),
            )
        }
        val last = line.last()
        return line + InkPoint(last.x - style.capHeight * 0.12f, last.y + style.capHeight * 0.16f)
    }

    /** A loop around the words: a little more than one turn, wobbling, not quite closing on itself. */
    private fun loop(target: InkBox, random: Random, style: HandwritingStyle): List<InkPoint> {
        val rx = target.width / 2f + style.capHeight * 0.28f
        val ry = target.height / 2f + style.capHeight * 0.38f
        val start = (0.62f + 0.08f * random.nextFloat()) * PI.toFloat()
        val turns = 1.14f
        val tilt = (random.nextFloat() - 0.5f) * 0.12f
        val n = 72
        return List(n + 1) { i ->
            val t = i / n.toFloat()
            val a = start + t * turns * 2f * PI.toFloat()
            val grow = 1f + 0.08f * t + 0.035f * sin(a * 3f + start)
            val x = rx * grow * cos(a)
            val y = ry * grow * sin(a)
            InkPoint(
                target.centerX + x * cos(tilt) - y * sin(tilt),
                target.centerY + x * sin(tilt) + y * cos(tilt),
            )
        }
    }

    /**
     * A curved arrow from the margin beside the line, below it, up toward the words, then its
     * two-stroke head.
     */
    private fun arrow(
        target: InkBox,
        line: InkBox,
        fromLeft: Boolean,
        random: Random,
        style: HandwritingStyle,
    ): List<List<InkPoint>> {
        val cap = style.capHeight
        val side = if (fromLeft) -1f else 1f
        val tip = InkPoint(
            (if (fromLeft) target.minX else target.maxX) + side * cap * 0.3f,
            target.centerY - cap * 0.05f,
        )
        val edge = if (fromLeft) line.minX else line.maxX
        val tail = InkPoint(edge + side * cap * (2.2f + 0.3f * random.nextFloat()), tip.y - cap * 1.5f)
        val bend = InkPoint(tail.x + side * cap * 0.1f, tip.y - cap * 0.1f)
        val n = 28
        val shaft = List(n + 1) { i ->
            val t = i / n.toFloat()
            val u = 1f - t
            InkPoint(
                u * u * tail.x + 2f * u * t * bend.x + t * t * tip.x,
                u * u * tail.y + 2f * u * t * bend.y + t * t * tip.y,
            )
        }
        val before = shaft[n - 2]
        val heading = atan2(tip.y - before.y, tip.x - before.x)
        val barb = cap * 0.5f
        val spread = 0.5f
        fun barbEnd(sign: Float) = InkPoint(
            tip.x - barb * cos(heading + sign * spread),
            tip.y - barb * sin(heading + sign * spread),
        )
        return listOf(shaft, listOf(barbEnd(1f), tip, barbEnd(-1f)))
    }

    /** How far (in capital heights) other words must be for an arrow to come in from that side. */
    private const val ARROW_CLEARANCE = 0.6f

    private const val DOT_SECONDS = 0.06f

    /** Extra metres of effort per radian of turn; see [effort]. */
    private const val CURVE_EFFORT = 0.004f

    /** The pen lifts take at most this share of the writing time. */
    private const val MAX_PAUSE_SHARE = 0.3f

    /** A doodle is drawn a touch finer than the words. */
    private const val DOODLE_RADIUS = 0.9f
}
