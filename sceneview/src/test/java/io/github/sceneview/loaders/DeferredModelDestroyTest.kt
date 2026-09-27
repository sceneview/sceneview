package io.github.sceneview.loaders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * Pure-JVM pins for #3981: leaving a scene whose model is still loading must never park the main
 * thread in gltfio's `asyncCancelLoad`, which joins the running texture decoders and then
 * `flushAndWait`s the engine — an ANR on an emulator.
 *
 * `ModelLoader.destroyModel` hands the cancel-then-destroy of the model in flight to a deferral
 * driven by [AsyncLoadSettleGate]; what runs here is that gate and [destroyAfterCancellingLoad]
 * with a deferral that only records the pending work, standing in for the main-looper poll.
 */
class DeferredModelDestroyTest {

    private val log = Collections.synchronizedList(mutableListOf<String>())
    private val registry = Collections.synchronizedList(mutableListOf<String>())
    private val asyncLoad = AsyncLoadSlot<String>(
        beginLoad = {},
        cancelLoad = { log += "cancel($it)" },
        finishLoad = { log += "detach" },
    )
    private val settling = mutableListOf<() -> Unit>()
    private var loadFinished = false

    private fun load(model: String) {
        registry += model
        asyncLoad.begin(model)
    }

    private fun destroy(model: String) = destroyAfterCancellingLoad(
        registry,
        model,
        asyncLoad,
        isLoadFinished = { loadFinished },
        whenLoadSettles = { _, run -> settling += run },
    ) { log += "destroy($it)" }

    private fun settle() {
        val pending = settling.toList()
        settling.clear()
        pending.forEach { it() }
    }

    @Test
    fun `the model in flight is neither cancelled nor freed until its load settles`() {
        load("a")
        destroy("a")
        assertTrue("nothing may run on the disposing thread", log.isEmpty())
        assertFalse("claimed at once: no other path may free it meanwhile", "a" in registry)

        settle()
        assertEquals(listOf("cancel(a)", "destroy(a)"), log)
        assertNull(asyncLoad.inFlight)
    }

    @Test
    fun `the model in flight whose load finished is detached and freed at once, without a cancel`() {
        // The usual case: the model is on screen, fully textured. asyncCancelLoad would only
        // flushAndWait the engine; the loader is retired instead, and nothing is deferred.
        load("a")
        loadFinished = true
        destroy("a")
        assertEquals(listOf("detach", "destroy(a)"), log)
        assertTrue(settling.isEmpty())
        assertNull(asyncLoad.inFlight)
    }

    @Test
    fun `a load that finishes while its destroy is deferred is detached, not cancelled`() {
        load("a")
        destroy("a")
        loadFinished = true
        settle()
        assertEquals(listOf("detach", "destroy(a)"), log)
        assertNull(asyncLoad.inFlight)
    }

    @Test
    fun `a model no longer in flight is freed at once, without a cancel`() {
        load("a")
        load("b")
        destroy("a")
        assertEquals(listOf("destroy(a)"), log)
        assertTrue(settling.isEmpty())
        assertSame("b", asyncLoad.inFlight)
    }

    @Test
    fun `a remount before the deferred destroy runs keeps the new load untouched`() {
        load("a")
        destroy("a")
        load("b")
        settle()
        // asyncBeginLoad(b) already joined a's decoders: a has nothing left to cancel, and a
        // cancel now would abort b's decoding.
        assertEquals(listOf("destroy(a)"), log)
        assertSame("b", asyncLoad.inFlight)
    }

    @Test
    fun `a second destroy while the first is deferred does nothing`() {
        load("a")
        destroy("a")
        destroy("a")
        assertEquals(1, settling.size)
        settle()
        assertEquals(listOf("cancel(a)", "destroy(a)"), log)
    }

    /** A gate over a scripted load, with the clock and the calls it made recorded. */
    private class Load(var inFlight: Boolean = true) {
        var progress = 0f
        var backendBusy = false
        var now = 0L
        var pumps = 0
        var backendChecks = 0
        val gate = AsyncLoadSettleGate(
            isInFlight = { inFlight },
            pumpedProgress = {
                pumps++
                progress
            },
            isBackendBusy = {
                backendChecks++
                backendBusy
            },
            now = { now },
            stallTimeoutMs = 1_000,
        )
    }

    @Test
    fun `busy while textures are still decoding, and the load is pumped on every poll`() {
        val load = Load()
        load.progress = 0.25f
        assertTrue(load.gate.isBusy())
        load.progress = 0.75f
        assertTrue(load.gate.isBusy())
        assertEquals(2, load.pumps)
        assertEquals("the fence is not polled before decoding is done", 0, load.backendChecks)
    }

    @Test
    fun `once decoding is done it settles without waiting for the backend`() {
        // A finished load is detached, not cancelled: no flushAndWait, so no fence to wait on.
        val load = Load()
        load.progress = 1f
        load.backendBusy = true
        assertFalse(load.gate.isBusy())
        assertEquals(0, load.backendChecks)
    }

    @Test
    fun `a model another load replaced is settled without touching the loader`() {
        val load = Load(inFlight = false)
        load.backendBusy = true
        assertFalse(load.gate.isBusy())
        assertEquals(0, load.pumps)
        assertEquals(0, load.backendChecks)
    }

    @Test
    fun `a load that stops advancing stops holding the destroy`() {
        // A texture whose external file never arrived keeps progress below 1 forever, with no
        // decoder job behind it: the cancel would not block, so waiting on it would leak.
        val load = Load()
        load.progress = 0.5f
        assertTrue(load.gate.isBusy())
        load.now = 999
        assertTrue(load.gate.isBusy())
        load.now = 1_000
        assertFalse(load.gate.isBusy())
    }

    @Test
    fun `a stalled load is cancelled only once the backend has drained`() {
        // The stalled load is cancelled for real, and the cancel's flushAndWait must be instant.
        val load = Load()
        load.progress = 0.5f
        assertTrue(load.gate.isBusy())
        load.now = 1_000
        load.backendBusy = true
        assertTrue(load.gate.isBusy())
        load.backendBusy = false
        assertFalse(load.gate.isBusy())
    }

    @Test
    fun `progress restarts the stall clock`() {
        val load = Load()
        load.progress = 0.25f
        assertTrue(load.gate.isBusy())
        load.now = 900
        load.progress = 0.5f
        assertTrue(load.gate.isBusy())
        load.now = 1_800
        assertTrue("advanced 900 ms ago", load.gate.isBusy())
        load.now = 1_900
        assertFalse(load.gate.isBusy())
    }
}
