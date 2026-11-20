package io.github.sceneview.demo.demos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanCaptureReleaseTest {
    @Test
    fun `close returns while CPU work runs and cleanup waits on a different thread despite scope cancellation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val released = AtomicBoolean(false)
        val caller = Thread.currentThread()
        var cleanupThread: Thread? = null
        val worker = scope.launch {
            entered.countDown()
            // Like Bitmap.compress / dense fusion: cancellation is not a suspension point.
            check(finish.await(10, TimeUnit.SECONDS))
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            scope.cancel()
            val started = System.nanoTime()
            val release = releaseScanCapture(listOf(worker)) {
                cleanupThread = Thread.currentThread()
                released.set(true)
            }
            val detachMs = (System.nanoTime() - started) / 1_000_000.0
            assertFalse(released.get())
            assertFalse(release.isCompleted)
            finish.countDown()
            release.join()
            assertTrue(released.get())
            assertTrue(worker.isCompleted)
            assertNotEquals(caller, cleanupThread)
            println("ScanCapture close scheduling: $detachMs ms; cleanup thread=${cleanupThread?.name}")
        } finally {
            finish.countDown()
            scope.cancel()
        }
    }
}
