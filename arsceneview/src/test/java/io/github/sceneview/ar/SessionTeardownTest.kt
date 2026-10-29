package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The background half of closing an ARCore session (#4771): it runs off the caller's thread,
 * reports on the main thread, and a session that resumes can wait for it.
 */
class SessionTeardownTest {

    private val posted = ArrayDeque<() -> Unit>()
    private val executor = Executors.newSingleThreadExecutor()
    private val teardown = SessionTeardown(executor) { block -> synchronized(posted) { posted.addLast(block) } }

    @After
    fun shutDown() {
        executor.shutdownNow()
    }

    @Test
    fun `the teardown runs on its own thread and reports through the main thread`() {
        val caller = Thread.currentThread()
        var ranOn: Thread? = null
        var done = false

        teardown.run(teardown = { ranOn = Thread.currentThread() }, onDone = { done = true })

        assertTrue(teardown.awaitIdle(timeoutMs = 5_000))
        assertTrue(ranOn != null && ranOn != caller)
        assertFalse("onDone is posted, not called from the teardown thread", done)
        runPosted()
        assertTrue(done)
    }

    @Test
    fun `awaitIdle waits for a teardown still running`() {
        val release = CountDownLatch(1)
        val order = mutableListOf<String>()
        teardown.run(teardown = {
            release.await(5, TimeUnit.SECONDS)
            synchronized(order) { order += "closed" }
        }, onDone = {})

        assertEquals(1, teardown.pendingCount)
        assertFalse(teardown.awaitIdle(timeoutMs = 50))

        Thread { Thread.sleep(50); release.countDown() }.start()
        assertTrue(teardown.awaitIdle(timeoutMs = 5_000))
        synchronized(order) { order += "resumed" }

        assertEquals(listOf("closed", "resumed"), order)
        assertEquals(0, teardown.pendingCount)
    }

    @Test
    fun `awaitIdle gives up past its timeout and the late teardown still reports`() {
        val release = CountDownLatch(1)
        var done = false
        teardown.run(teardown = { release.await(5, TimeUnit.SECONDS) }, onDone = { done = true })

        val start = System.nanoTime()
        assertFalse(teardown.awaitIdle(timeoutMs = 100))
        assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(100))
        assertEquals(1, teardown.pendingCount)

        release.countDown()
        assertTrue(teardown.awaitIdle(timeoutMs = 5_000))
        runPosted()
        assertTrue("the caller that gave up is still told when the camera is free", done)
    }

    @Test
    fun `awaitIdle returns at once when nothing is closing`() {
        assertTrue(teardown.awaitIdle(timeoutMs = 0))
    }

    private fun runPosted() {
        while (true) {
            val next = synchronized(posted) { posted.removeFirstOrNull() } ?: return
            next()
        }
    }
}
