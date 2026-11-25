package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AskIncrementalHandwritingTest {
    @Test fun `partial words stay hidden until whitespace punctuation or done`() {
        val ink = IncrementalHandwriting()
        assertTrue(ink.append("Hel").strokes.isEmpty())
        assertEquals(listOf("Hello,"), ink.append("lo,").lines.flatten())
        assertEquals(listOf("Hello,"), ink.append(" wor").lines.flatten())
        assertEquals(listOf("Hello,"), ink.append("ld").lines.flatten())
        assertEquals(listOf("Hello,", "world"), ink.finish(Doodle.None, "").lines.flatten())
    }

    @Test fun `decimals contractions and hyphens survive arbitrary token splits`() {
        val ink = IncrementalHandwriting()
        ink.append("About *2.")
        assertEquals(listOf("About"), ink.snapshot().lines.flatten())
        ink.append("5 metres*. Don't over-th")
        assertEquals(listOf("About", "2.5", "metres.", "Don't"), ink.snapshot().lines.flatten())
        ink.append("ink it!")
        val final = ink.finish(Doodle.Circle, "2.5 metres")
        assertEquals(listOf("About", "2.5", "metres.", "Don't", "over-think", "it!"), final.lines.flatten())
        assertTrue(final.strokes.any { it.kind == InkKind.Doodle })
    }

    @Test fun `wrapping coordinates stroke identities and timing never change on append`() {
        val style = HandwritingStyle(blockWidth = 0.45f)
        val ink = IncrementalHandwriting(style)
        val first = ink.append("Warm pasta ")
        val later = ink.append("is always a good idea. ", elapsedSeconds = 10f)
        val done = ink.finish(Doodle.Underline, "good idea")
        val expected = HandwritingLayout.wrap(
            listOf("Warm", "pasta", "is", "always", "a", "good", "idea."),
            style.blockWidth / (style.capHeight / HersheyScriptSimplex.CAP_HEIGHT),
        )
        assertEquals(expected, done.lines)
        assertEquals(first.bounds, done.bounds)
        first.strokes.indices.forEach { index ->
            assertSame(first.strokes[index], later.strokes[index])
            assertSame(first.strokes[index], done.strokes[index])
            assertEquals(first.starts[index], done.starts[index], 0f)
        }
        assertTrue(later.starts[first.strokes.size] >= 10f)
        assertEquals(done.strokes.size, done.strokes.map { it.id }.distinct().size)
    }

    @Test fun `early fallback replaces rather than concatenates drawn text`() {
        val ink = AskStreamingInk()
        ink.accept(AnswerChunk.Text("Partial model reply "), 0f)
        ink.accept(AnswerChunk.Failed("Unavailable"), 1f)
        val replacement = ink.accept(AnswerChunk.Text("Scripted answer. "), 1.1f)
        assertEquals(listOf("Scripted", "answer."), replacement.lines.flatten())
        assertTrue(replacement.starts.first() >= 1.1f)
    }

    @Test fun `punctuation arriving later never moves its word`() {
        val ink = IncrementalHandwriting()
        val first = ink.append("Hello!")
        val second = ink.append("! Next ")
        assertEquals(listOf("Hello!!", "Next"), second.lines.flatten())
        assertSame(first.strokes.first(), second.strokes.first())
    }
}
