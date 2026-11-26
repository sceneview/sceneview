package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskSseParserTest {
    @Test fun `every socket split yields the same events including split CRLF`() {
        val wire = ": keep-alive\r\n\r\nevent: answer\r\ndata: {\r\ndata: \"t\":\"Hello \\\"room\\\"!\"}\r\n\r\n" +
            "data: {\"done\":true,\"doodle\":\"arrow\",\"emphasis\":\"room\"}\n\n"
        val expected = listOf(
            AnswerChunk.Text("Hello \"room\"!"), AnswerChunk.Done(Doodle.Arrow, "room"),
        )
        for (split in 0..wire.length) {
            val parser = AskSseParser()
            assertEquals("split=$split", expected, parser.feed(wire.take(split)) + parser.feed(wire.drop(split)))
            assertTrue(parser.terminal)
        }
        val parser = AskSseParser()
        assertEquals(expected, wire.flatMap { parser.feed(it.toString()) })
    }

    @Test fun `incomplete event is held and terminal error stops parsing`() {
        val parser = AskSseParser()
        assertTrue(parser.feed("data: {\"t\":\"Hi\"}\n").isEmpty())
        assertFalse(parser.terminal)
        assertEquals(listOf(AnswerChunk.Text("Hi")), parser.feed("\n"))
        assertEquals(
            listOf(AnswerChunk.Failed("Unavailable")),
            parser.feed("data: {\"error\":\"Unavailable\"}\n\ndata: {\"t\":\"ignored\"}\n\n"),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `invalid payload is not silently treated as text`() {
        AskSseParser().feed("data: {\"t\":12}\n\n")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unbounded SSE lines are rejected`() {
        AskSseParser().feed("data: " + "x".repeat(16_384))
    }
}
