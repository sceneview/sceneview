package io.github.sceneview.demo.demos.internal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptedAnswerProviderTest {

    private fun matched(question: String) = ScriptedAnswerProvider.match(question)?.id

    @Test fun `each topic is recognised in plain words`() {
        assertEquals("cook", matched("What should I cook tonight?"))
        assertEquals("wall", matched("How far is that wall?"))
        assertEquals("best-sdk", matched("What's the best AR SDK?"))
        assertEquals("sceneview", matched("What is SceneView?"))
        assertEquals("weather", matched("Will it rain tomorrow?"))
        assertEquals("paris", matched("What's the tallest building in Paris?"))
        assertEquals("weather", matched("What's the weather like?"))
        assertEquals("foosball", matched("Who's winning at foosball?"))
        assertEquals("lawn", matched("Should I mow the lawn?"))
        assertEquals("keys", matched("Where did I leave my keys?"))
        assertEquals("hang", matched("Where should I hang this picture?"))
        assertEquals("hang", matched("Where do I put this painting?"))
        assertEquals("hang", matched("How high should this frame go?"))
        assertEquals("sofa", matched("Will a 2 m sofa fit here?"))
        assertEquals("sofa", matched("Does the couch fit?"))
        assertEquals("radiator", matched("How do I bleed this radiator?"))
        assertEquals("radiator", matched("Comment purger ce radiateur ?"))
    }

    @Test fun `the example questions each land on their own script`() {
        val ids = ScriptedAnswerProvider.EXAMPLES.map { matched(it) }
        assertFalse("an example falls back: $ids", ids.contains(null))
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun `a transcript slightly off still matches`() {
        assertEquals("sceneview", matched("what is scene view"))
        assertEquals("weather", matched("whats the wether like today"))
        assertEquals("cook", matched("what shoud I cook tonite"))
        assertEquals("best-sdk", matched("which is the best augmented reality framework"))
        assertEquals("wall", matched("How many metres away is the door?"))
    }

    @Test fun `a script needs its topic, not just its small words`() {
        assertEquals("weather", matched("Should I take an umbrella?"))
        assertNull(matched("Have you seen my cat?"))
        assertNull(matched("What should I wear?"))
    }

    @Test fun `an unrelated question gets the fallback`() {
        assertNull(matched("Who won the match last night?"))
        assertNull(matched(""))
        val answer = runBlocking { ScriptedAnswerProvider.answer("Sing me a song", AskContext()) }
        assertEquals(ScriptedAnswerProvider.FALLBACK, answer)
    }

    @Test fun `the wall answer uses the measured distance, to the half metre`() {
        val measured = runBlocking { ScriptedAnswerProvider.answer("how far is that wall", AskContext(2.37f)) }
        assertTrue(measured.text, measured.text.contains("*2.5m*"))
        // Measured, it points at the spot it measured.
        assertEquals(Doodle.Pointer, measured.doodle)
        assertTrue(ScriptedAnswerProvider.wallAnswer(3.1f).text.contains("*3m*"))
        // Nothing measured, or nonsense: no number at all, it asks to see the wall.
        val unmeasured = ScriptedAnswerProvider.wallAnswer(null)
        assertTrue(unmeasured.text, unmeasured.text.none { it.isDigit() })
        assertTrue(unmeasured.reply, unmeasured.reply.none { it.isDigit() })
        assertEquals(unmeasured, ScriptedAnswerProvider.wallAnswer(120f))
    }

    @Test fun `short words must match exactly, long ones forgive a typo or two`() {
        assertFalse(ScriptedAnswerProvider.fuzzyEquals("car", "far"))
        assertTrue(ScriptedAnswerProvider.fuzzyEquals("tonigt", "tonight"))
        assertTrue(ScriptedAnswerProvider.fuzzyEquals("sceneveiw", "sceneview"))
        assertFalse(ScriptedAnswerProvider.fuzzyEquals("building", "tonight"))
    }

    @Test fun `edit distance`() {
        assertEquals(0, ScriptedAnswerProvider.editDistance("pasta", "pasta"))
        assertEquals(1, ScriptedAnswerProvider.editDistance("pasta", "pastas"))
        assertEquals(3, ScriptedAnswerProvider.editDistance("kitten", "sitting"))
    }

    @Test fun `every answer marks what its doodle draws`() {
        val answers = ScriptedAnswerProvider.SCRIPTS.map { it.answer(AskContext()) } + ScriptedAnswerProvider.FALLBACK
        for (answer in answers) {
            assertEquals(answer.text, 2, answer.text.count { it == '*' })
            assertTrue(answer.doodle != Doodle.None)
        }
    }
}
