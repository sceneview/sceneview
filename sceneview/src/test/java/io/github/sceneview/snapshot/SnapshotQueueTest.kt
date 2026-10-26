package io.github.sceneview.snapshot

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SnapshotQueue] and [SnapshotLifetime] with fake renders: what is cached and what is not, what
 * a request gets when the snapshotter is destroyed mid-render, and what a caller that gives up
 * gets — the lifetime rules [ModelSnapshot] relies on, without an engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SnapshotQueueTest {

    private val fox = SnapshotKey("models/fox.glb", 64, 64, ModelSnapshotOptions())
    private val chair = SnapshotKey("models/chair.glb", 64, 64, ModelSnapshotOptions())
    private val image: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val cache = LruCache<SnapshotKey, Bitmap>(1024 * 1024)

    private var alive = true
    private val renders = mutableListOf<SnapshotKey>()

    private fun TestScope.queue(render: suspend (SnapshotKey) -> SnapshotResult) = SnapshotQueue(
        cache = cache,
        isAlive = { alive },
        dispatcher = StandardTestDispatcher(testScheduler),
        render = { key -> renders += key; render(key) },
    )

    // ── The cache ───────────────────────────────────────────────────────────────────

    @Test
    fun a_complete_image_is_cached_and_served_without_rendering_again() = runTest {
        val queue = queue { SnapshotResult.Rendered(image, complete = true) }

        val first = queue.request(fox)
        val second = queue.request(fox)

        assertSame(image, (first as SnapshotResult.Rendered).bitmap)
        assertSame(image, (second as SnapshotResult.Rendered).bitmap)
        assertSame(image, cache.get(fox))
        assertEquals(listOf(fox), renders)
    }

    @Test
    fun an_image_captured_after_the_texture_timeout_is_handed_out_but_not_cached() = runTest {
        val queue = queue { SnapshotResult.Rendered(image, complete = false) }

        val first = queue.request(fox)
        queue.request(fox)

        assertSame(image, (first as SnapshotResult.Rendered).bitmap)
        assertNull(cache.get(fox))
        assertEquals("rendered again, not served from the cache", listOf(fox, fox), renders)
    }

    @Test
    fun a_complete_image_finished_after_destroy_is_not_cached() = runTest {
        // destroy() lands while the render runs; the render still returns its image.
        val queue = queue {
            alive = false
            SnapshotResult.Rendered(image, complete = true)
        }

        val result = queue.request(fox)

        assertSame(image, (result as SnapshotResult.Rendered).bitmap)
        assertNull(cache.get(fox))
    }

    @Test
    fun a_failed_render_is_not_cached() = runTest {
        val queue = queue { SnapshotResult.Failed("could not load the model") }

        val first = queue.request(fox)
        queue.request(fox)

        assertEquals("could not load the model", (first as SnapshotResult.Failed).reason)
        assertNull(cache.get(fox))
        assertEquals(listOf(fox, fox), renders)
    }

    @Test
    fun a_render_that_throws_fails_its_request_only() = runTest {
        val queue = queue { key ->
            if (key == fox) error("boom")
            SnapshotResult.Rendered(image, complete = true)
        }

        val failed = queue.request(fox)
        val next = queue.request(chair)

        assertTrue((failed as SnapshotResult.Failed).reason.startsWith("render failed:"))
        assertTrue(next is SnapshotResult.Rendered)
    }

    // ── Destroy ─────────────────────────────────────────────────────────────────────

    @Test
    fun destroy_mid_render_fails_the_request_and_the_queued_ones_once_the_render_let_go() =
        runTest {
            val started = CompletableDeferred<Unit>()
            var released = false
            val queue = queue {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    released = true
                }
            }
            val rendering = async { queue.request(fox) }
            val queued = async { queue.request(chair) }
            runCurrent()
            assertTrue(started.isCompleted)
            assertTrue(queue.isBusy)

            // What ModelSnapshot.destroy() does.
            alive = false
            queue.cancel()
            advanceUntilIdle()

            assertEquals(SNAPSHOT_DESTROYED, (rendering.await() as SnapshotResult.Failed).reason)
            assertEquals(SNAPSHOT_DESTROYED, (queued.await() as SnapshotResult.Failed).reason)
            assertTrue("the render's model is released before the requests return", released)
            assertEquals("the queued request never renders", listOf(fox), renders)
            assertFalse(queue.isBusy)
        }

    @Test
    fun a_request_after_destroy_fails_without_rendering() = runTest {
        val queue = queue { SnapshotResult.Rendered(image, complete = true) }
        alive = false
        queue.cancel()

        val result = queue.request(fox)

        assertEquals(SNAPSHOT_DESTROYED, (result as SnapshotResult.Failed).reason)
        assertTrue(renders.isEmpty())
    }

    // ── A caller that gives up ──────────────────────────────────────────────────────

    @Test
    fun a_cancelled_caller_is_cancelled_and_the_next_request_renders_after_the_release() =
        runTest {
            val events = mutableListOf<String>()
            val queue = queue { key ->
                if (key == fox) {
                    try {
                        awaitCancellation()
                    } finally {
                        events += "fox released"
                    }
                }
                events += "chair rendered"
                SnapshotResult.Rendered(image, complete = true)
            }
            val gaveUp = async { queue.request(fox) }
            val next = async { queue.request(chair) }
            runCurrent()

            gaveUp.cancel()
            advanceUntilIdle()

            assertTrue("the caller's cancellation is rethrown, not turned into a result", gaveUp.isCancelled)
            assertTrue(next.await() is SnapshotResult.Rendered)
            assertEquals(listOf("fox released", "chair rendered"), events)
            assertNull("an abandoned render caches nothing", cache.get(fox))
        }

    @Test
    fun one_render_at_a_time_in_the_order_asked() = runTest {
        var running = 0
        var mostAtOnce = 0
        val queue = queue {
            running++
            mostAtOnce = maxOf(mostAtOnce, running)
            delay(100)
            running--
            SnapshotResult.Rendered(image, complete = false)
        }

        val requests = listOf(fox, chair, fox).map { key -> async { queue.request(key) } }
        requests.forEach { it.await() }

        assertEquals(1, mostAtOnce)
        assertEquals(listOf(fox, chair, fox), renders)
    }

    // ── Lifetime ────────────────────────────────────────────────────────────────────

    @Test
    fun lifetime_is_alive_until_destroyed_released_or_the_engine_goes() {
        var engineValid = true
        val lifetime = SnapshotLifetime { engineValid }
        assertTrue(lifetime.isAlive)

        engineValid = false
        assertFalse(lifetime.isAlive)
        engineValid = true

        assertTrue(lifetime.destroy())
        assertFalse("a second destroy is a no-op", lifetime.destroy())
        assertFalse(lifetime.isAlive)
        assertFalse(lifetime.isReleased)

        assertTrue(lifetime.release())
        assertFalse("a second release is a no-op", lifetime.release())
        assertTrue(lifetime.isReleased)
    }

    @Test
    fun only_a_complete_image_of_a_live_snapshotter_is_cached() {
        val complete = SnapshotResult.Rendered(image, complete = true)
        val partial = SnapshotResult.Rendered(image, complete = false)
        assertTrue(shouldCache(complete, isAlive = true))
        assertFalse(shouldCache(complete, isAlive = false))
        assertFalse(shouldCache(partial, isAlive = true))
        assertFalse(shouldCache(partial, isAlive = false))
    }
}
