package io.github.sceneview.ar

import io.github.sceneview.ar.arcore.SessionCloseGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The steps `ARCore.destroy()` splits a close into (#4771): what runs on the main thread before
 * the session counts as closed, and where the native pause + close run.
 */
class SessionCloseStepsTest {

    private val calls = mutableListOf<String>()
    private val gate = SessionCloseGate()

    private fun begin(isResumed: Boolean, onPaused: () -> Unit = { calls += "onPaused closed=${gate.isClosed}" }) =
        beginSessionClose(
            gate = gate,
            isResumed = isResumed,
            markPaused = { calls += "markPaused" },
            onPaused = onPaused,
            dropFrame = { calls += "dropFrame" },
            pause = { calls += "pause" },
            close = { calls += "close" },
            onPausedFailed = { calls += "onPaused failed" },
        )

    @Test
    fun `a running session reports its pause while still open, then counts as closed`() {
        val native = begin(isResumed = true)

        assertEquals(listOf("markPaused", "onPaused closed=false", "dropFrame"), calls)
        assertTrue(gate.isClosed)

        calls.clear()
        native!!.invoke()
        assertEquals(listOf("pause", "close"), calls)
    }

    @Test
    fun `a paused session is only closed, without a second onPaused`() {
        val native = begin(isResumed = false)

        assertEquals(listOf("dropFrame"), calls)
        native!!.invoke()
        assertEquals(listOf("dropFrame", "close"), calls)
    }

    @Test
    fun `a throwing onPaused still closes the session`() {
        val native = begin(isResumed = true, onPaused = { throw IllegalStateException("host bug") })

        assertEquals(listOf("markPaused", "onPaused failed", "dropFrame"), calls)
        assertTrue(gate.isClosed)
        native!!.invoke()
        assertEquals("close", calls.last())
    }

    @Test
    fun `a session already closed has nothing left to close`() {
        gate.tryClose()

        assertNull(begin(isResumed = true))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a session closed by its own onPaused is not closed twice`() {
        val native = begin(isResumed = true, onPaused = { gate.tryClose() })

        assertNull(native)
        assertEquals(listOf("markPaused"), calls)
    }

    @Test
    fun `a closing session runs in the background and reports when done`() {
        var background: Pair<() -> Unit, () -> Unit>? = null

        runSessionClose(
            teardown = { calls += "teardown" },
            isRecording = false,
            runInBackground = { teardown, onDone -> background = teardown to onDone },
            onClosed = { calls += "onClosed" },
        )

        assertTrue(calls.isEmpty())
        background!!.first()
        background!!.second()
        assertEquals(listOf("teardown", "onClosed"), calls)
    }

    @Test
    fun `a recording session closes inline so its file is final`() {
        runSessionClose(
            teardown = { calls += "teardown" },
            isRecording = true,
            runInBackground = { _, _ -> calls += "background" },
            onClosed = { calls += "onClosed" },
        )

        assertEquals(listOf("teardown", "onClosed"), calls)
    }

    @Test
    fun `nothing to close still hands the camera back`() {
        runSessionClose(
            teardown = null,
            isRecording = false,
            runInBackground = { _, _ -> calls += "background" },
            onClosed = { calls += "onClosed" },
        )

        assertEquals(listOf("onClosed"), calls)
    }

    @Test
    fun `a resume waits for the closing session, and gives up past the timeout`() {
        resumeOnceCameraFree(awaitTeardowns = { true }, resume = { calls += "resume" }, onStillClosing = { calls += "later" })
        resumeOnceCameraFree(awaitTeardowns = { false }, resume = { calls += "resume" }, onStillClosing = { calls += "later" })

        assertEquals(listOf("resume", "later"), calls)
        assertFalse(calls.count { it == "resume" } > 1)
    }
}
