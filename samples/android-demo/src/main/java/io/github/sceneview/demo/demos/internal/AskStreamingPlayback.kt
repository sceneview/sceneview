package io.github.sceneview.demo.demos.internal

import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Collects and animates concurrently, including pauses between network deltas. Invoke from
 * the demo's main-thread coroutine after placement; cancellation stops both jobs. Returns
 * a terminal failure for the screen to present, or null on success (including fallback).
 */
internal suspend fun playAskStream(
    provider: StreamingAnswerProvider,
    question: String,
    context: AskContext,
    onFrame: (Handwriting, Float) -> Unit,
    nextFrameNanos: suspend () -> Long = { withFrameNanos { it } },
): String? = coroutineScope {
    val ink = AskStreamingInk()
    var elapsed = 0f
    var handwriting = ink.snapshot()
    val started = nextFrameNanos()
    val receiver = launch {
        provider.stream(question, context).collect { chunk ->
            handwriting = ink.accept(chunk, elapsed)
            onFrame(handwriting, elapsed)
        }
    }
    while (receiver.isActive || elapsed < handwriting.totalSeconds) {
        elapsed = (nextFrameNanos() - started) / 1_000_000_000f
        onFrame(handwriting, elapsed)
    }
    receiver.join()
    ink.failureReason
}
