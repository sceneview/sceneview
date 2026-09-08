package io.github.sceneview.loaders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression contract for #3523 — a native SIGSEGV in `libgltfio-jni` seen when a cancelled
 * `ModelLoader.loadModel` coroutine's `destroyOnCancel` cleanup called `destroyModel` on an
 * asset `clear()` had already destroyed (or the reverse). `runCatching` cannot turn a JNI
 * double-free into a catchable exception — it is a process-level `SIGSEGV` — so the fix has to
 * make it structurally impossible to reach `AssetLoader.safeDestroyModel` twice for the same
 * asset, not catch the fallout.
 *
 * [claimAndDestroy] is the extracted, Filament-free core of that guard: `ModelLoader.destroyModel`
 * is every one of `destroyModel` (direct calls), `destroyOnCancel`'s cleanup and `clear()`'s
 * sweep routed through it against the same `models` registry. These tests exercise the guard
 * directly — a fake "native" resource and a `destroy` callback that counts and records calls —
 * without needing a real Filament `Engine`/`AssetLoader`, which this module's JVM unit tests
 * cannot construct (see `ModelLoaderCancellationTest`'s use of the same extraction strategy for
 * #3051).
 */
class ModelDestroyIdempotenceTest {

    @Test
    fun `first claim destroys and removes the item from the registry`() {
        val registry = Collections.synchronizedList(mutableListOf("model"))
        val destroyed = mutableListOf<String>()

        claimAndDestroy(registry, "model") { destroyed += it }

        assertEquals(listOf("model"), destroyed)
        assertTrue("the claimed item must leave the registry", registry.isEmpty())
    }

    @Test
    fun `a second claim on the same item is a no-op`() {
        val registry = Collections.synchronizedList(mutableListOf("model"))
        val destroyed = mutableListOf<String>()

        claimAndDestroy(registry, "model") { destroyed += it }
        // This is the exact shape of the crash: destroyOnCancel's cleanup calling destroyModel
        // on a model clear() (or another destroyOnCancel) already tore down.
        claimAndDestroy(registry, "model") { destroyed += it }

        assertEquals(
            "the native call must run exactly once for this asset",
            listOf("model"),
            destroyed
        )
    }

    @Test
    fun `claiming an item never registered does not destroy it`() {
        val registry = Collections.synchronizedList(mutableListOf<String>())
        val destroyed = mutableListOf<String>()

        claimAndDestroy(registry, "never-registered") { destroyed += it }

        assertTrue(destroyed.isEmpty())
    }

    @Test
    fun `claiming one item leaves an unrelated registered item alone`() {
        val registry = Collections.synchronizedList(mutableListOf("model-a", "model-b"))
        val destroyed = mutableListOf<String>()

        claimAndDestroy(registry, "model-a") { destroyed += it }

        assertEquals(listOf("model-a"), destroyed)
        assertEquals(listOf("model-b"), registry)
    }

    /**
     * Simulates the exact race from the tombstone: `clear()`'s synchronous sweep over a
     * `models.toList()` snapshot racing a cancelled coroutine's `destroyOnCancel` cleanup for
     * the very same model, on two different threads, with no synchronization between the two
     * call sites beyond the shared registry. Runs many trials because the original bug was
     * timing-dependent (~2 crashes per 10 QA runs) — a single trial passing would not be
     * convincing.
     */
    @Test
    fun `two threads racing to destroy the same model destroy it exactly once`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(2_000) {
                val registry = Collections.synchronizedList(mutableListOf("model"))
                val destroyCount = AtomicInteger(0)
                val barrier = CyclicBarrier(2)

                val destroy: (String) -> Unit = { destroyCount.incrementAndGet() }
                val racer = Runnable {
                    barrier.await(5, TimeUnit.SECONDS)
                    claimAndDestroy(registry, "model", destroy)
                }

                val f1 = executor.submit(racer)
                val f2 = executor.submit(racer)
                f1.get(5, TimeUnit.SECONDS)
                f2.get(5, TimeUnit.SECONDS)

                assertEquals(
                    "exactly one of the two concurrent callers must destroy the model",
                    1,
                    destroyCount.get()
                )
                assertTrue("the model must not remain claimable afterwards", registry.isEmpty())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * `clear()` destroys whatever is still in `models` and returns without waiting for any
     * coroutine cancellation to finish propagating (#3523's second half: "clear() races its own
     * cancellation"). This reproduces that ordering — clear's sweep runs to completion first —
     * and asserts the later, delayed cancellation cleanup for the same model is inert rather
     * than reaching into a native asset `clear()` (and, in production, `ModelLoader.destroy()`
     * right after it) already tore down.
     */
    @Test
    fun `a cleanup that arrives after clear already destroyed the model is inert`() {
        val registry = Collections.synchronizedList(mutableListOf("model"))
        val destroyed = mutableListOf<String>()
        fun destroyModel(model: String) = claimAndDestroy(registry, model) { destroyed += it }

        // clear(): synchronous sweep over a snapshot of the registry.
        registry.toList().forEach(::destroyModel)
        // The cancelled coroutine's destroyOnCancel cleanup, queued on the main dispatcher
        // behind clear(), finally gets to run.
        destroyModel("model")

        assertEquals(listOf("model"), destroyed)
    }

    /** Same race, opposite arrival order: the coroutine cleanup wins, clear's sweep is inert. */
    @Test
    fun `clear sweeping after a coroutine cleanup already destroyed the model is inert`() {
        val registry = Collections.synchronizedList(mutableListOf("model"))
        val destroyed = mutableListOf<String>()
        fun destroyModel(model: String) = claimAndDestroy(registry, model) { destroyed += it }

        // The cancelled coroutine's cleanup runs first this time.
        destroyModel("model")
        // clear()'s sweep now finds an empty snapshot for this model.
        registry.toList().forEach(::destroyModel)

        assertEquals(listOf("model"), destroyed)
    }

    /**
     * A model registered mid-way through `clear()`'s cancellation fan-out (e.g. a load whose
     * asset creation had not yet run when `coroutineScope.cancel()` fired) must still be
     * destroyable by a later, explicit `destroyModel` call — the guard must never leave a
     * legitimately live model permanently unclaimable.
     */
    @Test
    fun `a model added after a sweep is still destroyable`() {
        val registry = Collections.synchronizedList(mutableListOf("model-a"))
        val destroyed = mutableListOf<String>()
        fun destroyModel(model: String) = claimAndDestroy(registry, model) { destroyed += it }

        registry.toList().forEach(::destroyModel)
        registry += "model-b"
        destroyModel("model-b")

        assertEquals(listOf("model-a", "model-b"), destroyed)
    }
}
