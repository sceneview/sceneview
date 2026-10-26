package io.github.sceneview.snapshot

import android.graphics.Bitmap
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.render.RenderTestCapabilities
import io.github.sceneview.render.RenderTestHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ModelSnapshot]'s queue and lifetime on a real engine: destroy while a model loads, queued
 * requests after destroy, a caller that gives up mid-render, and the cache.
 *
 * Every test renders through an `ImageReader` swap chain. Ungated on CI's x86_64 SwiftShader
 * emulator (API 30), the instrumentation process died within a second of the class's first test,
 * with no Java stack trace and no logcat in the run's output; the same six tests pass on an arm64
 * API 36 emulator with `-gpu swiftshader_indirect`. The cause is not diagnosed, so like the other
 * render classes the whole class runs only on a runner declaring GPU readback (see
 * [RenderTestCapabilities]) and skips elsewhere. The queue and lifetime rules themselves are
 * covered on the JVM by `SnapshotQueueTest`.
 */
@OptIn(ExperimentalSceneViewApi::class)
@RunWith(AndroidJUnit4::class)
class ModelSnapshotTest {

    companion object {
        private const val MODEL = "khronos_fox.glb"
        private const val SIZE = 64
        private const val TIMEOUT_MS = 60_000L

        private lateinit var harness: RenderTestHarness

        @JvmStatic
        @BeforeClass
        fun setupClass() {
            harness = RenderTestHarness(width = 64, height = 64)
        }

        @JvmStatic
        @AfterClass
        fun teardownClass() {
            harness.destroy()
        }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var snapshot: ModelSnapshot

    @Before
    fun setUp() {
        // runBlocking below waits for work the main thread does: never run these tests on it.
        assertFalse(Looper.myLooper() == Looper.getMainLooper())
        RenderTestCapabilities.assumeGpuReadbackAvailable()
        harness.runOnMain { snapshot = ModelSnapshot(harness.engine, context) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        if (!::snapshot.isInitialized) return
        harness.runOnMain { snapshot.destroy() }
        // Lets the deferred release run, then proves the engine still renders.
        Thread.sleep(200)
        harness.runOnMain { harness.renderFrames(2) }
    }

    private fun request(): Deferred<Bitmap?> = scope.async { snapshot.snapshot(MODEL, SIZE) }

    private fun <T> await(deferred: Deferred<T>): T = runBlocking { withTimeout(TIMEOUT_MS) { deferred.await() } }

    @Test
    fun destroy_while_the_model_loads_returns_null() {
        val inFlight = request()
        // The file is read off the main thread: destroy lands while loadModel is suspended.
        harness.runOnMain { snapshot.destroy() }

        assertNull(await(inFlight))
    }

    @Test
    fun destroy_mid_render_returns_null() {
        val inFlight = request()
        Thread.sleep(150)
        harness.runOnMain { snapshot.destroy() }

        assertNull(await(inFlight))
    }

    @Test
    fun queued_requests_return_null_after_destroy() {
        val requests = List(3) { request() }
        harness.runOnMain { snapshot.destroy() }

        requests.forEach { assertNull(await(it)) }
        assertNull("a request after destroy", await(request()))
    }

    @Test
    fun a_cancelled_caller_lets_the_next_request_through() {
        val abandoned = request()
        Thread.sleep(100)
        abandoned.cancel()
        val next = request()

        // Finishes rather than waiting behind the abandoned render forever.
        val bitmap = await(next)
        assertTrue(abandoned.isCancelled)
        assertNotNull(bitmap)
    }

    @Test
    fun a_rendered_thumbnail_is_cached_immutable_and_reused() {
        val first = await(request())
        assertNotNull(first)
        first!!
        assertEquals(SIZE, first.width)
        assertFalse("handed-out bitmaps are shared: immutable", first.isMutable)
        assertSame(first, snapshot.cached(MODEL, SIZE))
        assertSame(first, await(request()))
        // Another size is another image.
        assertNull(snapshot.cached(MODEL, SIZE * 2))

        harness.runOnMain { snapshot.destroy() }
        assertNull("destroy empties the cache", snapshot.cached(MODEL, SIZE))
    }

    @Test
    fun a_model_that_cannot_be_loaded_is_not_cached() {
        val missing = scope.async { snapshot.snapshot("missing.glb", SIZE) }

        assertNull(await(missing))
        assertNull(snapshot.cached("missing.glb", SIZE))
        // The queue is free again.
        runBlocking { delay(10) }
        await(request())
    }
}
