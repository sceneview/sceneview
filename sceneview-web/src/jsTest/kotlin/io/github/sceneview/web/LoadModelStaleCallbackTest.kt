package io.github.sceneview.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the **#1597 Tier-2** use-after-free race in
 * `SceneView.loadModel`.
 *
 * `loadModel` issues `asset.loadResources(onDone = { ... })` asynchronously.
 * The `onDone` callback captures both the `FilamentAsset` and its `LoadedModel`
 * and, when it fires, calls `asset.releaseSourceData()` and mutates
 * `loadedModel.loaded` / `autoCenterGate`. If a 2nd `loadModel(url)` of the
 * same URL — or `destroy()` — runs *before* that callback fires, the prior
 * `FilamentAsset` is destroyed synchronously inside `AssetResourceTracker`. The
 * still-pending stale `onDone` then touches a freed handle: a use-after-free on
 * the WASM heap.
 *
 * The fix tags each `LoadedModel` with a `superseded` flag, set the instant its
 * asset is freed (by the replace path or by `destroy()`), and the `onDone`
 * callback bails out at entry when the flag is set.
 *
 * `SceneView`/`LoadedModel` need a live WebGL context, so this test models the
 * exact production sequence with plain stand-ins — the same approach
 * [AssetResourceTrackerTest] takes for the leak-free-swap state machine. A
 * `FakeAsset` records whether it was destroyed and whether its source data was
 * released; `runLoadResources` reproduces the guarded `onDone` body verbatim.
 */
class LoadModelStaleCallbackTest {

    /** Stand-in for a Filament.js `FilamentAsset`. */
    private class FakeAsset(val name: String) {
        var destroyed = false
        var sourceDataReleased = false

        /** Reproduces `asset.releaseSourceData()` — illegal once destroyed. */
        fun releaseSourceData() {
            check(!destroyed) { "use-after-free: releaseSourceData() on destroyed asset '$name'" }
            sourceDataReleased = true
        }
    }

    /** Stand-in for `SceneView.LoadedModel` carrying the new `superseded` flag. */
    private class FakeLoadedModel(val asset: FakeAsset) {
        var loaded = false
        var superseded = false
    }

    /** Records every `autoCenterGate.reset()` the (guarded) callback would issue. */
    private class FakeGate {
        var resetCount = 0
        fun reset() {
            resetCount++
        }
    }

    /**
     * Reproduces the guarded `onDone` body of `SceneView.loadModel`'s
     * `asset.loadResources(...)` call: bail out for a superseded model,
     * otherwise release source data, flip `loaded`, re-arm the gate, and
     * invoke `onLoaded`.
     */
    private fun runLoadResources(
        model: FakeLoadedModel,
        gate: FakeGate,
        onLoaded: (FakeAsset) -> Unit,
    ) {
        if (model.superseded) return
        model.asset.releaseSourceData()
        model.loaded = true
        gate.reset()
        onLoaded(model.asset)
    }

    /**
     * #1597 Tier-2: a 2nd `loadModel(url)` of the same URL before the 1st
     * load's resources finish must NOT let the stale `onDone` touch the freed
     * asset.
     */
    @Test
    fun reloadingSameUrlBeforeResourcesFinishDropsStaleCallback() {
        val gate = FakeGate()
        val destroyed = mutableListOf<String>()
        val tracker = AssetResourceTracker<FakeAsset> { destroyed += it.name; it.destroyed = true }

        // --- 1st loadModel(url): asset created, loadResources still in flight ---
        val first = FakeAsset("helmet-v1")
        val firstModel = FakeLoadedModel(first)
        tracker.replaceWith("models/helmet.glb", first)

        // --- 2nd loadModel(url) BEFORE the 1st onDone fires ---
        // Production marks the prior model superseded, then replaceWith frees it.
        tracker.current("models/helmet.glb")?.let { prior ->
            if (prior === firstModel.asset) firstModel.superseded = true
        }
        val second = FakeAsset("helmet-v2")
        val secondModel = FakeLoadedModel(second)
        tracker.replaceWith("models/helmet.glb", second)

        assertEquals(listOf("helmet-v1"), destroyed, "the prior asset is destroyed exactly once")
        assertTrue(first.destroyed, "1st asset is freed")
        assertTrue(firstModel.superseded, "1st model is flagged superseded before its asset is freed")

        // --- the STALE 1st onDone finally fires ---
        // Must be a no-op: no releaseSourceData on the freed handle, no state mutation.
        runLoadResources(firstModel, gate) { error("stale onLoaded must not fire for a superseded model") }

        assertFalse(first.sourceDataReleased, "no releaseSourceData() on the freed 1st asset")
        assertFalse(firstModel.loaded, "stale callback must not flip `loaded` on the dropped model")
        assertEquals(0, gate.resetCount, "stale callback must not re-arm the auto-center gate")

        // --- the 2nd (live) onDone fires normally ---
        var liveOnLoaded: FakeAsset? = null
        runLoadResources(secondModel, gate) { liveOnLoaded = it }

        assertTrue(second.sourceDataReleased, "the live 2nd asset releases its source data")
        assertTrue(secondModel.loaded, "the live 2nd model is marked loaded")
        assertEquals(1, gate.resetCount, "the live callback re-arms the gate once")
        assertEquals(second, liveOnLoaded, "onLoaded fires for the live asset only")
    }

    /**
     * #1597 Tier-2: `destroy()` while a load's resources are still in flight
     * must inert the pending callback — no use-after-free on teardown.
     */
    @Test
    fun destroyBeforeResourcesFinishDropsStaleCallback() {
        val gate = FakeGate()
        val tracker = AssetResourceTracker<FakeAsset> { it.destroyed = true }

        val asset = FakeAsset("model")
        val model = FakeLoadedModel(asset)
        tracker.replaceWith("models/model.glb", asset)

        // --- destroy(): mark every model superseded BEFORE releasing assets ---
        val models = listOf(model)
        models.forEach { it.superseded = true }
        tracker.release()

        assertTrue(asset.destroyed, "destroy() frees the asset")
        assertTrue(model.superseded, "destroy() flags the model superseded before freeing")

        // --- the stale onDone fires after teardown ---
        runLoadResources(model, gate) { error("onLoaded must not fire after destroy()") }

        assertFalse(asset.sourceDataReleased, "no releaseSourceData() on a destroyed asset after teardown")
        assertFalse(model.loaded, "teardown's stale callback must not mutate model state")
        assertEquals(0, gate.resetCount, "teardown's stale callback must not touch the auto-center gate")
    }

    /**
     * A load that finishes normally — no replace, no destroy — must still run
     * its `onDone` fully. Guards against the `superseded` check becoming an
     * over-eager bail-out that breaks the happy path.
     */
    @Test
    fun uninterruptedLoadRunsItsCallbackFully() {
        val gate = FakeGate()
        val tracker = AssetResourceTracker<FakeAsset> { it.destroyed = true }

        val asset = FakeAsset("solo")
        val model = FakeLoadedModel(asset)
        tracker.replaceWith("models/solo.glb", asset)

        var onLoadedFired = false
        runLoadResources(model, gate) { onLoadedFired = true }

        assertTrue(asset.sourceDataReleased, "an uninterrupted load releases its source data")
        assertTrue(model.loaded, "an uninterrupted load marks the model loaded")
        assertEquals(1, gate.resetCount, "an uninterrupted load re-arms the gate once")
        assertTrue(onLoadedFired, "an uninterrupted load fires onLoaded")
        assertFalse(model.superseded, "an uninterrupted model is never superseded")
        assertFalse(asset.destroyed, "an uninterrupted asset stays live")
    }
}
