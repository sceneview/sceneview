package io.github.sceneview.ar

import com.google.ar.core.TrackingFailureReason
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ARFrameCallbacksTest {

    private val calls = mutableListOf<String>()
    private val thrown = mutableListOf<Pair<String, Exception>>()
    private val previous = AtomicReference<TrackingFailureReason?>(null)

    private fun frame(
        reason: TrackingFailureReason?,
        onTrackingFailureChanged: (TrackingFailureReason?) -> Unit = { calls += "tracking:$it" },
        onSessionUpdated: () -> Unit = { calls += "updated" },
    ) = dispatchHostFrameCallbacks(
        newTrackingFailure = reason,
        prevTrackingFailureRef = previous,
        onTrackingFailureChanged = onTrackingFailureChanged,
        onSessionUpdated = onSessionUpdated,
        onThrow = { name, exception -> thrown += name to exception },
    )

    @Test
    fun `every frame reaches onSessionUpdated, a reason only when it changes`() {
        frame(null)
        frame(TrackingFailureReason.INSUFFICIENT_LIGHT)
        frame(TrackingFailureReason.INSUFFICIENT_LIGHT)
        frame(null)

        assertEquals(
            listOf(
                "updated",
                "tracking:INSUFFICIENT_LIGHT", "updated",
                "updated",
                "tracking:null", "updated",
            ),
            calls,
        )
        assertTrue(thrown.isEmpty())
    }

    @Test
    fun `a throwing onTrackingFailureChanged is named and does not skip onSessionUpdated`() {
        val failure = IllegalStateException("host bug")

        frame(TrackingFailureReason.EXCESSIVE_MOTION, onTrackingFailureChanged = { throw failure })

        assertEquals(listOf("updated"), calls)
        assertEquals(1, thrown.size)
        assertEquals("onTrackingFailureChanged", thrown[0].first)
        assertSame(failure, thrown[0].second)
    }

    @Test
    fun `a throwing onTrackingFailureChanged is not called again for the same reason`() {
        frame(TrackingFailureReason.EXCESSIVE_MOTION, onTrackingFailureChanged = { throw IllegalStateException() })
        frame(TrackingFailureReason.EXCESSIVE_MOTION)

        assertEquals(listOf("updated", "updated"), calls)
        assertEquals(1, thrown.size)
    }

    @Test
    fun `a throwing onSessionUpdated is named and stays inside the frame`() {
        val failure = IllegalArgumentException("host bug")

        repeat(3) { frame(null, onSessionUpdated = { throw failure }) }

        assertEquals(List(3) { "onSessionUpdated" }, thrown.map { it.first })
        assertSame(failure, thrown[0].second)
    }

    @Test
    fun `both callbacks throwing on one frame are both named`() {
        frame(
            TrackingFailureReason.BAD_STATE,
            onTrackingFailureChanged = { throw IllegalStateException() },
            onSessionUpdated = { throw IllegalArgumentException() },
        )

        assertEquals(listOf("onTrackingFailureChanged", "onSessionUpdated"), thrown.map { it.first })
    }

    @Test
    fun `an error is not swallowed`() {
        val error = OutOfMemoryError("not ours to hide")

        val caught = runCatching { frame(null, onSessionUpdated = { throw error }) }.exceptionOrNull()

        assertSame(error, caught)
        assertTrue(thrown.isEmpty())
    }
}
