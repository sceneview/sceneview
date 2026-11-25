package io.github.sceneview.demo.demos.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface AnswerChunk {
    data class Text(val delta: String) : AnswerChunk
    data class Done(val doodle: Doodle, val emphasis: String) : AnswerChunk
    data class Failed(val reason: String) : AnswerChunk
}

internal fun interface StreamingAnswerProvider {
    fun stream(question: String, context: AskContext): Flow<AnswerChunk>

    fun stream(question: String, context: AskLiveContext): Flow<AnswerChunk> =
        stream(question, AskContext(distanceAheadMeters = context.distanceAheadMeters))

    /** Default providers have no upload; the proxy signals only after writing its body. */
    fun streamAfterUpload(
        question: String,
        context: AskLiveContext,
        onBodyWritten: () -> Unit,
    ): Flow<AnswerChunk> = flow {
        onBodyWritten()
        emitAll(stream(question, context))
    }
}

/** Keeps the offline/default answer on exactly the same rendering path as a live answer. */
internal class ScriptedStreamingAnswerProvider(
    private val provider: AnswerProvider = ScriptedAnswerProvider,
    private val wordDelayMillis: Long = 180L,
) : StreamingAnswerProvider {
    override fun stream(question: String, context: AskContext): Flow<AnswerChunk> = flow {
        val answer = provider.answer(question, context)
        val words = answer.text.split(Regex("\\s+")).filter(String::isNotEmpty)
        words.forEachIndexed { index, word ->
            if (index > 0) delay(wordDelayMillis)
            emit(AnswerChunk.Text(word + if (index < words.lastIndex) " " else ""))
        }
        val emphasis = Regex("\\*([^*]+)\\*").find(answer.text)?.groupValues?.get(1).orEmpty()
        emit(AnswerChunk.Done(answer.doodle, emphasis))
    }
}

/**
 * Waits at most [startupTimeoutMillis] after upload for nonempty text. A failure before any text, or
 * during that initial window, switches to the script. A later failure terminates the reply.
 *
 * Failed ends an attempt; Text after Failed begins a replacement answer. Renderers must
 * clear the old ink on that transition (see [AskStreamingInk]), never concatenate replies.
 * Parent cancellation always propagates and never starts the script.
 */
internal class FallbackStreamingAnswerProvider(
    private val primary: StreamingAnswerProvider,
    private val fallback: StreamingAnswerProvider = ScriptedStreamingAnswerProvider(),
    private val startupTimeoutMillis: Long = 2_500L,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : StreamingAnswerProvider {
    init {
        require(startupTimeoutMillis > 0)
    }

    override fun stream(question: String, context: AskContext): Flow<AnswerChunk> =
        stream(question, AskLiveContext(distanceAheadMeters = context.distanceAheadMeters))

    override fun stream(question: String, context: AskLiveContext): Flow<AnswerChunk> = flow {
        var useFallback = false
        coroutineScope {
            val events = Channel<StartupEvent>(Channel.BUFFERED)
            var started: Long? = null
            val producer = launch {
                try {
                    primary.streamAfterUpload(question, context, onBodyWritten = {
                        events.trySend(StartupEvent.Uploaded(nowMillis()))
                    }).collect { events.send(StartupEvent.Chunk(it)) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    events.send(StartupEvent.Chunk(AnswerChunk.Failed("Live answer unavailable")))
                } finally {
                    events.close()
                }
            }
            var hasText = false
            try {
                while (true) {
                    val event = if (hasText || started == null) {
                        events.receiveCatching().getOrNull()
                    } else {
                        val remaining = startupTimeoutMillis - (nowMillis() - checkNotNull(started))
                        if (remaining <= 0) null else withTimeoutOrNull(remaining) {
                            events.receiveCatching().getOrNull()
                        }
                    } ?: StartupEvent.Chunk(AnswerChunk.Failed("Live answer timed out or ended early"))
                    if (event is StartupEvent.Uploaded) {
                        if (started == null) started = event.atMillis
                        continue
                    }
                    val chunk = (event as StartupEvent.Chunk).value
                    when (chunk) {
                        is AnswerChunk.Text -> if (chunk.delta.isNotEmpty()) {
                            hasText = true
                            emit(chunk)
                        }
                        is AnswerChunk.Done -> {
                            if (hasText) emit(chunk) else {
                                emit(AnswerChunk.Failed("Live answer was empty"))
                                useFallback = true
                            }
                            break
                        }
                        is AnswerChunk.Failed -> {
                            emit(chunk)
                            useFallback = !hasText || (started?.let { nowMillis() - it } ?: 0L) <= startupTimeoutMillis
                            break
                        }
                    }
                }
            } finally {
                producer.cancelAndJoin()
                events.cancel()
            }
        }
        if (useFallback) emitAll(fallback.stream(question, context))
    }
}

/** Upload timing is transport metadata and never reaches handwriting renderers. */
private sealed interface StartupEvent {
    data class Uploaded(val atMillis: Long) : StartupEvent
    data class Chunk(val value: AnswerChunk) : StartupEvent
}
