package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `ARCore` does about sharing the one camera: who is paused, who is resumed, and what
 * happens when a resume fails. `ARCore.resume`, `pause` and `destroy` delegate here, with the
 * ARCore `Session` behind [CameraClient].
 */
class CameraArbiterTest {

    private val calls = mutableListOf<String>()
    private val posted = ArrayDeque<() -> Unit>()
    private val arbiter = CameraArbiter { posted.addLast(it) }

    private val a = FakeSession("A")
    private val b = FakeSession("B")

    @Test
    fun `a session resuming over a running one pauses it first`() {
        arbiter.resume(a)
        arbiter.resume(b)

        assertEquals(listOf("A start", "A stop", "B start"), calls)
        assertTrue(b.isRunning && !a.isRunning)
    }

    @Test
    fun `a session that was never created takes nothing`() {
        // Camera permission still pending: there is no session to resume yet.
        arbiter.resume(a)
        b.canRun = false

        arbiter.resume(b)

        assertEquals(listOf("A start"), calls)
        assertTrue(a.isRunning)
    }

    @Test
    fun `a failed resume is reported to its host and gives the camera back`() {
        // AR screen B opens over AR screen A and ARCore refuses B the camera. The exception
        // must not escape into the lifecycle observer, and A must not stay frozen.
        arbiter.resume(a)
        b.failure = IllegalStateException("camera not available")

        arbiter.resume(b)

        assertEquals(listOf("A start", "A stop", "B start", "B failed", "A start"), calls)
        assertTrue(a.isRunning && !b.isRunning)

        // B gave up its turn: when A is closed there is nobody to hand the camera to.
        arbiter.destroy(a) { a.close() }
        assertEquals("A close", calls.last())
    }

    @Test
    fun `a failed resume with nobody underneath is only reported`() {
        a.failure = IllegalStateException("camera not available")

        arbiter.resume(a)

        assertEquals(listOf("A start", "A failed"), calls)
        // It left the queue: a later session finds the camera free.
        arbiter.resume(b)
        assertEquals(listOf("A start", "A failed", "B start"), calls)
    }

    @Test
    fun `closing the running session hands the camera back`() {
        // Predictive back from B previews A, then the gesture is cancelled: A is disposed.
        arbiter.resume(b)
        arbiter.resume(a)

        arbiter.destroy(a) { a.close() }

        assertEquals(listOf("B start", "B stop", "A start", "A close", "B start"), calls)
        assertTrue(b.isRunning)
    }

    @Test
    fun `closing the outgoing screen leaves the incoming one alone`() {
        arbiter.resume(a)
        arbiter.resume(b)
        calls.clear()

        arbiter.destroy(a) { a.close() }

        assertEquals(listOf("A close"), calls)
        assertTrue(b.isRunning)
    }

    @Test
    fun `a hand back that fails goes to that session's host and on to the next one`() {
        val c = FakeSession("C")
        arbiter.resume(a)
        arbiter.resume(b)
        arbiter.resume(c)
        calls.clear()
        b.failure = IllegalStateException("camera not available")

        arbiter.destroy(c) { c.close() }

        assertEquals(listOf("C close", "B start", "B failed", "A start"), calls)
        assertTrue(a.isRunning)
    }

    @Test
    fun `a session closed behind the arbiter's back is skipped on hand back`() {
        arbiter.resume(a)
        arbiter.resume(b)
        calls.clear()
        a.canRun = false

        arbiter.destroy(b) { b.close() }

        assertEquals(listOf("B close"), calls)
    }

    @Test
    fun `an activity pause does not reopen the camera between two sessions`() {
        arbiter.resume(a)
        arbiter.resume(b)
        calls.clear()

        // Lifecycle observers are paused in reverse order of registration, in one dispatch.
        arbiter.pause(b)
        arbiter.pause(a)
        runPosted()

        assertEquals(listOf("B stop"), calls)
        assertTrue(!a.isRunning && !b.isRunning)
    }

    @Test
    fun `a top screen paused on its own gives the camera back after the dispatch`() {
        // B follows a narrower lifecycle and is paused while the activity, and A, stay resumed.
        arbiter.resume(a)
        arbiter.resume(b)
        calls.clear()

        arbiter.pause(b)
        assertEquals(listOf("B stop"), calls)

        runPosted()
        assertEquals(listOf("B stop", "A start"), calls)
        assertTrue(a.isRunning)
    }

    @Test
    fun `a session resumed before the posted hand back is not resumed twice`() {
        arbiter.resume(a)
        arbiter.resume(b)
        arbiter.pause(b)
        calls.clear()

        arbiter.resume(a)
        runPosted()

        assertEquals(listOf("A start"), calls)
    }

    @Test
    fun `pausing the only session posts nothing`() {
        arbiter.resume(a)

        arbiter.pause(a)

        assertEquals(listOf("A start", "A stop"), calls)
        assertTrue(posted.isEmpty())
    }

    private fun runPosted() {
        while (posted.isNotEmpty()) posted.removeFirst().invoke()
    }

    private inner class FakeSession(private val name: String) : CameraClient {
        override var isRunning = false
        override var canRun = true
        var failure: Exception? = null

        override fun start() {
            calls += "$name start"
            failure?.let { throw it }
            isRunning = true
        }

        override fun stop() {
            calls += "$name stop"
            isRunning = false
        }

        override fun onStartFailed(exception: Exception) {
            calls += "$name failed"
        }

        fun close() {
            calls += "$name close"
            isRunning = false
            canRun = false
        }
    }
}
