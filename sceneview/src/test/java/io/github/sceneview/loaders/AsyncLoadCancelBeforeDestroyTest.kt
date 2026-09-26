package io.github.sceneview.loaders

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Regression contract for #3868: destroying a model whose textures gltfio is still decoding must
 * cancel that async load *before* the asset is freed, or the next `asyncUpdateLoad` (run every
 * frame) writes into the freed textures — a native crash on AR open in production.
 *
 * A real `ModelLoader` needs a Filament `Engine`, so what runs here is the Filament-free pair
 * `ModelLoader.destroyModel` is built on, [destroyAfterCancellingLoad] + [AsyncLoadSlot], with a
 * log standing in for `asyncCancelLoad` and `destroyAsset`. The native crash itself is reproduced
 * by the instrumented `ModelDestroyDuringAsyncLoadTest`.
 */
class AsyncLoadCancelBeforeDestroyTest {

    private val log = Collections.synchronizedList(mutableListOf<String>())
    private val registry = Collections.synchronizedList(mutableListOf<String>())
    private val asyncLoad = AsyncLoadSlot<String>(beginLoad = {}, cancelLoad = { log += "cancel" })

    private fun load(model: String) {
        registry += model
        asyncLoad.begin(model)
    }

    private fun destroy(model: String) =
        destroyAfterCancellingLoad(registry, model, asyncLoad) { log += "destroy($it)" }

    @Test
    fun `destroying the model still decoding cancels its load before freeing it`() {
        load("a")
        destroy("a")
        assertEquals(listOf("cancel", "destroy(a)"), log)
        assertNull(asyncLoad.inFlight)
    }

    @Test
    fun `remount mid-load - the old model is cancelled then freed, the new one loads untouched`() {
        load("a")
        destroy("a")
        load("b")
        assertEquals(listOf("cancel", "destroy(a)"), log)
        assertSame("b", asyncLoad.inFlight)
    }

    @Test
    fun `destroying an older model does not cancel the newer model's load`() {
        // asyncBeginLoad(b) drains a's decoder jobs first, so a has nothing left to cancel, and a
        // cancel here would abort b's decoding and leave it untextured.
        load("a")
        load("b")
        destroy("a")
        assertEquals(listOf("destroy(a)"), log)
        assertSame("b", asyncLoad.inFlight)
    }

    @Test
    fun `destroying the same model twice cancels and frees it once`() {
        load("a")
        destroy("a")
        destroy("a")
        assertEquals(listOf("cancel", "destroy(a)"), log)
    }

    @Test
    fun `destroy after the load completed - the cancel is a no-op on an idle loader`() {
        // Completion is not observable from the slot, so the model is still "in flight": the
        // cancel runs, and on a loader with nothing decoding it only drops the stale asset pointer.
        load("a")
        destroy("a")
        assertEquals(listOf("cancel", "destroy(a)"), log)
        load("b")
        destroy("b")
        assertEquals(listOf("cancel", "destroy(a)", "cancel", "destroy(b)"), log)
    }

    @Test
    fun `a destroy after clear cancelled everything cancels nothing more`() {
        load("a")
        asyncLoad.cancel()
        registry.toList().forEach { destroy(it) }
        assertEquals(listOf("cancel", "destroy(a)"), log)
    }

    @Test
    fun `a model never loaded is freed without a cancel`() {
        registry += "a"
        destroy("a")
        assertEquals(listOf("destroy(a)"), log)
    }

    @Test
    fun `begin fills the slot before starting the native load`() {
        // The native begin stores the asset pointer first thing: a destroy racing a begin that
        // fails midway must still see the model as in flight.
        var seenInFlight: String? = null
        lateinit var slot: AsyncLoadSlot<String>
        slot = AsyncLoadSlot(beginLoad = { seenInFlight = slot.inFlight }, cancelLoad = {})
        slot.begin("a")
        assertEquals("a", seenInFlight)
    }

    @Test
    fun `the cancel callback learns whether a load was in flight`() {
        // ModelLoader only swaps its ResourceLoader when a real load was interrupted.
        val seen = mutableListOf<String?>()
        val slot = AsyncLoadSlot<String>(beginLoad = {}, cancelLoad = { seen += it })
        slot.cancel()
        slot.begin("a")
        slot.cancel()
        assertEquals(listOf(null, "a"), seen)
    }

    @Test
    fun `a load cancelled after asyncBeginLoad cancels before its cleanup destroy`() = runBlocking {
        // loadModel's own cleanup path: the coroutine is cancelled once the begin already ran, and
        // destroyOnCancel frees the model on the "main" thread — through the same ordering.
        val executor = Executors.newSingleThreadExecutor()
        try {
            val main = executor.asCoroutineDispatcher()
            val begun = CountDownLatch(1)
            val job = launch(Dispatchers.Default) {
                destroyOnCancel("a", destroy = ::destroy, dispatcher = main) {
                    withContext(main) { load("a") }
                    begun.countDown()
                    delay(10_000)
                }
            }
            assertTrue(begun.await(5, TimeUnit.SECONDS))
            job.cancel()
            job.join()
            assertEquals(listOf("cancel", "destroy(a)"), log)
        } finally {
            executor.shutdownNow()
        }
    }
}
