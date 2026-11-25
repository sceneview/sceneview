package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingLayoutTest {

    @Test fun `the font draws every letter, digit and basic punctuation`() {
        val wanted = ('A'..'Z') + ('a'..'z') + ('0'..'9') + ".,!?'\"-:;()&".toList()
        val missing = wanted.filterNot { HersheyFont.covers(it) }
        assertTrue("no glyph for $missing", missing.isEmpty())
    }

    @Test fun `a glyph decodes to strokes on the font's grid`() {
        val a = HersheyFont.glyph('A')!!
        assertTrue(a.strokes.isNotEmpty())
        val points = a.strokes.flatten()
        assertEquals(0f, points.minOf { it.y }, 0f)
        assertEquals(HersheyScriptSimplex.CAP_HEIGHT.toFloat(), points.maxOf { it.y }, 0f)
        assertEquals(points.maxOf { it.x } + HersheyFont.LETTER_GAP, a.advance, 0f)
        // The i is a body and a dot: two strokes.
        assertEquals(2, HersheyFont.glyph('i')!!.strokes.size)
    }

    @Test fun `a space and an unknown character have no glyph`() {
        assertNull(HersheyFont.glyph(' '))
        assertNull(HersheyFont.glyph('€'))
        assertFalse(HersheyFont.covers('\n'))
    }

    @Test fun `typographic quotes and dashes fall back on the font's own`() {
        assertNotNull(HersheyFont.glyph('’'))
        assertEquals(HersheyFont.glyph('\''), HersheyFont.glyph('’'))
        assertEquals(HersheyFont.glyph('-'), HersheyFont.glyph('—'))
    }

    @Test fun `asterisks mark the emphasised letters and are not written`() {
        val words = HandwritingLayout.parse("Always *pasta*.")
        assertEquals(listOf("Always", "pasta."), words.map { it.text })
        assertTrue(words[0].emphasised.none { it })
        assertEquals(listOf(true, true, true, true, true, false), words[1].emphasised.toList())
    }

    @Test fun `words wrap greedily to the line width`() {
        val width = HandwritingLayout.wordWidth("pasta") * 2 + HersheyFont.SPACE_ADVANCE
        val lines = HandwritingLayout.wrap(listOf("pasta", "pasta", "pasta"), width)
        assertEquals(listOf(listOf("pasta", "pasta"), listOf("pasta")), lines)
    }

    @Test fun `a word wider than the line gets a line of its own`() {
        val lines = HandwritingLayout.wrap(listOf("a", "extraordinarily", "b"), HandwritingLayout.wordWidth("abc"))
        assertEquals(listOf(listOf("a"), listOf("extraordinarily"), listOf("b")), lines)
    }

    @Test fun `every scripted answer fits the block in two to four lines`() {
        val style = HandwritingStyle()
        val answers = ScriptedAnswerProvider.SCRIPTS.map { it.answer(AskContext(distanceAheadMeters = 2.4f)) } +
            ScriptedAnswerProvider.FALLBACK +
            ScriptedAnswerProvider.wallAnswer(null)
        for (answer in answers) {
            val writing = HandwritingLayout.layout(answer.text, answer.doodle, style)
            assertTrue("${answer.text}: ${writing.lines}", writing.lines.size in 2..4)
            val text = writing.strokes.filter { it.kind == InkKind.Text }.flatMap { it.points }
            val half = style.blockWidth / 2f + style.capHeight
            assertTrue("${answer.text} is wider than the block", text.all { it.x in -half..half })
            assertNotNull("${answer.text} marks nothing for its doodle", writing.emphasis)
            assertTrue(writing.strokes.any { it.kind == InkKind.Doodle })
        }
    }

    @Test fun `the block is centred on its anchor`() {
        val writing = HandwritingLayout.layout("Pasta. The answer is always *pasta*.", Doodle.None)
        val box = writing.bounds
        assertEquals(0f, box.centerX, 0.1f)
        assertEquals(0f, box.centerY, 0.12f)
    }

    @Test fun `a whole answer takes about three to six seconds`() {
        for (text in listOf("Yes!", "Pasta. The answer is always *pasta*.", "The open-source kit *writing this* right now.")) {
            val writing = HandwritingLayout.layout(text, Doodle.Circle)
            assertTrue("$text takes ${writing.totalSeconds} s", writing.totalSeconds in 2.5f..6f)
        }
    }

    @Test fun `strokes are written one after the other, in order`() {
        val writing = HandwritingLayout.layout("Take a *jacket* anyway.", Doodle.Underline)
        for (i in 1 until writing.strokes.size) {
            val previousEnd = writing.starts[i - 1] + writing.strokes[i - 1].duration
            assertTrue("stroke $i starts before $i-1 ends", writing.starts[i] >= previousEnd - 1e-4f)
        }
        assertEquals(InkKind.Doodle, writing.strokes.last().kind)
    }

    @Test fun `letters of a word join into one stroke and the dots come after`() {
        val writing = HandwritingLayout.layout("minimum", Doodle.None)
        // The body of the word in one go, then the two dots of its i's.
        assertEquals(3, writing.strokes.size)
        assertTrue(HandwritingLayout.polylineLength(writing.strokes[0].points) > 0.5f)
        assertTrue(writing.strokes.drop(1).all { HandwritingLayout.polylineLength(it.points) < 0.05f })
    }

    @Test fun `progress grows from nothing to the whole stroke`() {
        val writing = HandwritingLayout.layout("Pasta", Doodle.None)
        val stroke = writing.strokes[0]
        assertEquals(0, writing.progressOf(0, -1f).count)
        val mid = writing.progressOf(0, writing.starts[0] + stroke.duration / 2f)
        assertTrue(mid.count in 2 until stroke.points.size)
        assertTrue(mid.tipFraction in 0f..1f)
        assertEquals(stroke.points.size, writing.progressOf(0, writing.totalSeconds + 1f).count)
        var last = 0
        var t = 0f
        while (t < stroke.duration) {
            val count = writing.progressOf(0, t).count
            assertTrue(count >= last)
            last = count
            t += 1f / 60f
        }
    }

    @Test fun `the pen presses lighter at both ends of a stroke`() {
        val stroke = HandwritingLayout.layout("Pasta", Doodle.None).strokes[0]
        val middle = stroke.radii[stroke.radii.size / 2]
        assertTrue(stroke.radii.first() < middle * 0.6f)
        assertTrue(stroke.radii.last() < middle * 0.6f)
    }

    @Test fun `the block shrinks to fit an upright phone and never grows`() {
        val block = InkBox(-0.6f, -0.2f, 0.6f, 0.2f)
        // Upright at 1.5 m a phone shows about 0.8 m across: the block is shrunk to fit.
        val upright = HandwritingLayout.fitScale(block, visibleWidth = 0.8f, visibleHeight = 1.8f)
        assertEquals(HandwritingLayout.FIT_WIDTH * 0.8f / 1.2f, upright, 1e-5f)
        // Sideways it shows 1.8 m across: full size, not blown up.
        assertEquals(1f, HandwritingLayout.fitScale(block, visibleWidth = 1.8f, visibleHeight = 0.8f), 0f)
        // A tall block is held to the height between the top bar and the mic.
        val tall = InkBox(-0.3f, -0.6f, 0.3f, 0.6f)
        assertEquals(HandwritingLayout.FIT_HEIGHT * 1.8f / 1.2f, HandwritingLayout.fitScale(tall, 2f, 1.8f), 1e-5f)
    }

    @Test fun `resampling keeps both ends and spaces points evenly`() {
        val line = listOf(InkPoint(0f, 0f), InkPoint(0.1f, 0f), InkPoint(0.1f, 0.1f))
        val even = HandwritingLayout.resample(line, 0.01f)
        assertEquals(line.first(), even.first())
        assertEquals(line.last(), even.last())
        assertEquals(21, even.size)
        for (i in 1 until even.size) assertEquals(0.01f, even[i - 1].distanceTo(even[i]), 1e-4f)
    }
}
