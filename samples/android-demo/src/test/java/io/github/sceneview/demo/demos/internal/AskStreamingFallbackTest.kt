package io.github.sceneview.demo.demos.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AskStreamingFallbackTest {
    private val context = AskContext()
    private val fallback = StreamingAnswerProvider { _, _ -> flow {
        emit(AnswerChunk.Text("Script. "))
        emit(AnswerChunk.Done(Doodle.None, ""))
    } }

    @Test fun `startup timeout cancels primary and streams the fallback at 2500 ms`() = runTest {
        var closed = false
        val primary = StreamingAnswerProvider { _, _ -> flow<AnswerChunk> {
            try { awaitCancellation() } finally { closed = true }
        } }
        val chunks = mutableListOf<AnswerChunk>()
        val job = launch {
            FallbackStreamingAnswerProvider(primary, fallback, nowMillis = { testScheduler.currentTime })
                .stream("Hello", context).toList(chunks)
        }
        advanceTimeBy(2_499)
        assertTrue(chunks.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        job.join()
        assertTrue(closed)
        assertTrue(chunks.first() is AnswerChunk.Failed)
        assertEquals(AnswerChunk.Text("Script. "), chunks[1])
        assertEquals(2_500L, testScheduler.currentTime)
    }

    @Test fun `early partial failure starts a replacement but late failure stays terminal`() = runTest {
        for (failureTime in listOf(1_000L, 3_000L)) {
            val primary = StreamingAnswerProvider { _, _ -> flow {
                emit(AnswerChunk.Text("Partial "))
                delay(failureTime)
                emit(AnswerChunk.Failed("Unavailable"))
            } }
            val chunks = FallbackStreamingAnswerProvider(primary, fallback, nowMillis = { testScheduler.currentTime })
                .stream("Hello", context).toList()
            assertEquals(failureTime < 2_500, chunks.contains(AnswerChunk.Text("Script. ")))
            assertEquals(AnswerChunk.Failed("Unavailable"), chunks[1])
        }
    }

    @Test fun `successful stream can continue beyond startup window`() = runTest {
        val primary = StreamingAnswerProvider { _, _ -> flow {
            delay(100)
            emit(AnswerChunk.Text("Live "))
            delay(3_000)
            emit(AnswerChunk.Done(Doodle.Arrow, "Live"))
        } }
        val chunks = FallbackStreamingAnswerProvider(primary, fallback, nowMillis = { testScheduler.currentTime })
            .stream("Hello", context).toList()
        assertEquals(listOf(AnswerChunk.Text("Live "), AnswerChunk.Done(Doodle.Arrow, "Live")), chunks)
    }

    @Test fun `parent cancellation never triggers fallback`() = runTest {
        var called = false
        val primary = StreamingAnswerProvider { _, _ -> flow<AnswerChunk> { awaitCancellation() } }
        val script = StreamingAnswerProvider { _, _ -> called = true; flow { } }
        val job = launch {
            FallbackStreamingAnswerProvider(primary, script).stream("Hello", context).collect { }
        }
        runCurrent()
        job.cancelAndJoin()
        assertFalse(called)
    }

    @Test fun `exceptions empty streams and missing done all fail over before first text`() = runTest {
        val streams: List<Flow<AnswerChunk>> = listOf(
            flow { throw IllegalStateException("Offline") }, flow { }, flow { emit(AnswerChunk.Text("")) },
        )
        for (stream in streams) {
            val chunks = FallbackStreamingAnswerProvider(StreamingAnswerProvider { _, _ -> stream }, fallback)
                .stream("Hello", context).toList()
            assertEquals(AnswerChunk.Text("Script. "), chunks[1])
        }
    }

    @Test fun `script preserves text emphasis and human pacing`() = runTest {
        val chunks = ScriptedStreamingAnswerProvider(
            AnswerProvider { _, _ -> AskAnswer("Try *warm pasta*.", Doodle.Underline) },
        ).stream("Food?", context).toList()
        assertEquals("Try *warm pasta*.", chunks.filterIsInstance<AnswerChunk.Text>().joinToString("") { it.delta })
        assertEquals(AnswerChunk.Done(Doodle.Underline, "warm pasta"), chunks.last())
        assertEquals(360L, testScheduler.currentTime)
    }

    @Test fun `playback draws before done and keeps ticking through a network pause`() = runTest {
        var modelDone = false
        var drewBeforeDone = false
        var finalTime = 0f
        var finalInk: Handwriting? = null
        val provider = StreamingAnswerProvider { _, _ -> flow {
            delay(100)
            emit(AnswerChunk.Text("Hello "))
            delay(600)
            emit(AnswerChunk.Text("room!"))
            modelDone = true
            emit(AnswerChunk.Done(Doodle.Arrow, "room"))
        } }
        val failure = playAskStream(provider, "Hello", context, onFrame = { ink, elapsed ->
            if (!modelDone && ink.strokes.isNotEmpty() && ink.progressOf(0, elapsed).count > 0) {
                drewBeforeDone = true
            }
            finalTime = elapsed
            finalInk = ink
        }, nextFrameNanos = {
            delay(16)
            testScheduler.currentTime * 1_000_000L
        })
        assertEquals(null, failure)
        assertTrue(drewBeforeDone)
        assertTrue(finalTime >= finalInk!!.totalSeconds)
    }
}
