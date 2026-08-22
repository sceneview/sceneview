package io.github.sceneview.loaders

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Regression contract for #3051 item 1 — a model created on the main thread but never
 * returned to a caller cancelled mid-flight must still be destroyed.
 *
 * A real `ModelLoader` needs a Filament `Engine`, so what is exercised here is the helper
 * `loadModel` / `loadInstancedModel` route their creation through, on a single-thread
 * dispatcher standing in for `Dispatchers.Main`. The first test is the premise, measured:
 * `withContext` discards its block's result when the caller is cancelled while the block
 * runs. Without that fact the helper would be dead code.
 */
class ModelLoaderCancellationTest {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = executor.asCoroutineDispatcher()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun `premise - a bare withContext drops the value created before cancellation`() = runBlocking {
        val blockEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var created: String? = null
        var returned: String? = null

        val job = launch(Dispatchers.Default) {
            returned = withContext(main) {
                blockEntered.countDown()
                release.await(5, TimeUnit.SECONDS)
                "model".also { created = it }
            }
        }
        assertTrue(blockEntered.await(5, TimeUnit.SECONDS))
        job.cancel()
        release.countDown()
        job.join()

        assertEquals("The block did finish and did build the value", "model", created)
        assertNull("The caller never sees the value — this is the leak window", returned)
    }

    @Test
    fun `createOrDestroyOnCancel destroys the value the cancelled caller never received`() =
        runBlocking {
            val destroyed = mutableListOf<String>()
            val blockEntered = CountDownLatch(1)
            val release = CountDownLatch(1)
            var returned: String? = null
            var cancelled = false

            val job = launch(Dispatchers.Default) {
                try {
                    returned = createOrDestroyOnCancel(destroy = { destroyed += it }, dispatcher = main) {
                        blockEntered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        "model"
                    }
                } catch (e: CancellationException) {
                    cancelled = true
                    throw e
                }
            }
            assertTrue(blockEntered.await(5, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            job.join()

            assertTrue("CancellationException must still propagate", cancelled)
            assertNull(returned)
            assertEquals(listOf("model"), destroyed)
        }

    @Test
    fun `createOrDestroyOnCancel returns the value and destroys nothing when not cancelled`() =
        runBlocking {
            val destroyed = mutableListOf<String>()
            val returned = createOrDestroyOnCancel(destroy = { destroyed += it }, dispatcher = main) {
                "model"
            }
            assertEquals("model", returned)
            assertTrue(destroyed.isEmpty())
        }

    @Test
    fun `createOrDestroyOnCancel passes a null result through untouched`() = runBlocking {
        val destroyed = mutableListOf<String>()
        val returned = createOrDestroyOnCancel<String>(destroy = { destroyed += it }, dispatcher = main) {
            null
        }
        assertNull(returned)
        assertTrue(destroyed.isEmpty())
    }

    @Test
    fun `createOrDestroyOnCancel destroys nothing when cancelled before the block ran`() =
        runBlocking {
            val destroyed = mutableListOf<String>()
            var blockRan = false
            val job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                createOrDestroyOnCancel(destroy = { destroyed += it }, dispatcher = main) {
                    blockRan = true
                    "model"
                }
            }
            job.cancel()
            job.join()
            assertFalse(blockRan)
            assertTrue(destroyed.isEmpty())
        }

    @Test
    fun `destroyOnCancel frees a created model when resource loading is cancelled`() =
        runBlocking {
            val destroyed = mutableListOf<String>()
            val entered = CountDownLatch(1)
            val job = launch(Dispatchers.Default) {
                destroyOnCancel("model", destroy = { destroyed += it }, dispatcher = main) {
                    entered.countDown()
                    delay(10_000)
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            job.join()
            assertEquals(listOf("model"), destroyed)
        }

    @Test
    fun `destroyOnCancel leaves a model alone when resource loading completes`() = runBlocking {
        val destroyed = mutableListOf<String>()
        destroyOnCancel("model", destroy = { destroyed += it }, dispatcher = main) { }
        assertTrue(destroyed.isEmpty())
    }
}
