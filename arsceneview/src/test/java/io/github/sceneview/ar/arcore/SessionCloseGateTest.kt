package io.github.sceneview.ar.arcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * An ARCore session is closed once and never read afterwards (#4026).
 *
 * `Session.close()` leaves the Java wrapper's native handle set, so ARCore itself does not
 * stop a call made after it: the call runs on freed native memory and the process dies with a
 * signal that no `catch` sees. [SessionCloseGate] and [readUnlessClosed] are what
 * [ARSession.close], `rememberARPlaybackStatus` and `ARRecorder` rely on to keep out.
 */
class SessionCloseGateTest {

    @Test
    fun `the first close is granted, a second one is not`() {
        val gate = SessionCloseGate()

        assertTrue(gate.tryClose())
        assertFalse(gate.tryClose())

        assertTrue(gate.isClosed)
    }

    @Test
    fun `the session already reads as closed once the close is granted`() {
        val gate = SessionCloseGate()

        assertFalse(gate.isClosed)
        gate.tryClose()

        // ARSession.close() runs the native close only after this, so a reader that checks
        // isClosed never enters a session that is being closed.
        assertTrue(gate.isClosed)
    }

    @Test
    fun `concurrent closes grant the native close once`() {
        val gate = SessionCloseGate()
        val granted = AtomicInteger()
        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                if (gate.tryClose()) granted.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))

        assertEquals(1, granted.get())
    }

    @Test
    fun `a closed session is never read`() {
        var reads = 0

        val value = readUnlessClosed(isClosed = true, whenClosed = "NONE") {
            reads++
            "OK"
        }

        assertEquals("NONE", value)
        assertEquals(0, reads)
    }

    @Test
    fun `an open session is read`() {
        assertEquals("FINISHED", readUnlessClosed(isClosed = false, whenClosed = "NONE") { "FINISHED" })
    }

    @Test
    fun `a runtime failure of an open session reads as the closed value`() {
        val value = readUnlessClosed(isClosed = false, whenClosed = "NONE") {
            throw IllegalStateException("session paused")
        }

        assertEquals("NONE", value)
    }

    @Test(expected = OutOfMemoryError::class)
    fun `a JVM error is not swallowed`() {
        readUnlessClosed(isClosed = false, whenClosed = "NONE") { throw OutOfMemoryError() }
    }
}
