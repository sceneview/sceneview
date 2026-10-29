package io.github.sceneview.demo.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Collects one streamed Point & Ask answer from [stream], reporting through [onEvent]: a
 * non-null failure, else a non-null accumulated answer for each delta, else `(null, null)`
 * for "the stream completed". Exactly one terminal event (failure or completion) is sent,
 * unless the caller cancels the round.
 *
 * A hung inference used to leave "thinking" up forever, with every later tap swallowed by
 * the busy guard (#2648). The [firstTokenTimeoutMs] budget covers the first token only —
 * once text flows, a long answer may take as long as it needs.
 *
 * The watchdog never throws: an exception thrown from a child coroutine reaches the parent
 * only as a generic cancellation, so a `catch` for it never fires and the failure escapes
 * into the screen's scope (the review finding on #4602). It sets a flag and cancels the
 * collection instead, and the timeout is reported from here, as an ordinary event.
 *
 * Engine errors are classified rather than swallowed (#3343) — `Throwable`, not
 * `Exception`: a minified build missing the ML Kit classes raises a `NoClassDefFoundError`,
 * and letting it escape would leave the demo wedged in `Thinking` (cf. #3188).
 */
suspend fun runAskRound(
    stream: Flow<String>,
    firstTokenTimeoutMs: Long = ASK_FIRST_TOKEN_TIMEOUT_MS,
    log: (message: String, error: Throwable?) -> Unit = { _, _ -> },
    onEvent: (failure: AskFailure?, text: String?) -> Unit,
) {
    var text = ""
    var firstToken = false
    var timedOut = false
    try {
        coroutineScope {
            val collection = launch {
                stream.collect { delta ->
                    if (!firstToken) {
                        firstToken = true
                        log("engine: first token received", null)
                    }
                    text += delta
                    onEvent(null, text)
                }
            }
            val watchdog = launch {
                delay(firstTokenTimeoutMs)
                if (!firstToken) {
                    timedOut = true
                    collection.cancel()
                }
            }
            collection.join()
            watchdog.cancel()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        val failure = AskFailure.of(e)
        log("engine: inference failed, GenAI error code=${AskFailure.errorCodeOf(e)} -> $failure", e)
        onEvent(failure, null)
        return
    }
    if (timedOut) {
        log("engine: no token within ${firstTokenTimeoutMs}ms, round timed out", null)
        onEvent(AskFailure.TimedOut, null)
        return
    }
    log("engine: stream completed, ${text.length} chars", null)
    onEvent(null, null)
}
