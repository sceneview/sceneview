package io.github.sceneview.demo.ai

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [runAskRound] — the first-token watchdog that keeps a hung Gemini Nano
 * inference from wedging the demo in "thinking" (#2648). Virtual time: no real 60 s wait.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AskRoundTest {

    private data class Event(val failure: AskFailure?, val text: String?)

    @Test
    fun `an engine that never emits times out as a normal event`() = runTest {
        val events = mutableListOf<Event>()
        // Launched like the screen does: a failure escaping the round would fail this scope.
        val round = launch {
            runAskRound(flow<String> { awaitCancellation() }) { f, t -> events += Event(f, t) }
        }
        advanceTimeBy(ASK_FIRST_TOKEN_TIMEOUT_MS - 1)
        runCurrent()
        assertTrue("no event before the budget runs out", events.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(Event(AskFailure.TimedOut, null)), events)
        assertTrue(round.isCompleted && !round.isCancelled)
    }

    @Test
    fun `a normal stream reports each delta then completes`() = runTest {
        val events = mutableListOf<Event>()
        runAskRound(flow { emit("A "); emit("mug") }) { f, t -> events += Event(f, t) }
        assertEquals(
            listOf(Event(null, "A "), Event(null, "A mug"), Event(null, null)),
            events,
        )
    }

    @Test
    fun `a slow answer is not cut once the first token arrived`() = runTest {
        val events = mutableListOf<Event>()
        runAskRound(
            flow {
                emit("first")
                delay(ASK_FIRST_TOKEN_TIMEOUT_MS * 3)
                emit(" last")
            },
        ) { f, t -> events += Event(f, t) }
        assertEquals(Event(null, "first last"), events[events.size - 2])
        assertEquals(Event(null, null), events.last())
        assertTrue(currentTime >= ASK_FIRST_TOKEN_TIMEOUT_MS * 3)
    }

    @Test
    fun `an engine error is classified, not thrown`() = runTest {
        val events = mutableListOf<Event>()
        runAskRound(flow<String> { error("inference crashed") }) { f, t -> events += Event(f, t) }
        assertEquals(listOf(Event(AskFailure.Unknown, null)), events)
    }

    @Test
    fun `an error after partial text still ends with the failure`() = runTest {
        val events = mutableListOf<Event>()
        runAskRound(
            flow {
                emit("half")
                throw NoClassDefFoundError("com/google/mlkit/genai")
            },
        ) { f, t -> events += Event(f, t) }
        assertEquals(listOf(Event(null, "half"), Event(AskFailure.Unknown, null)), events)
    }
}
