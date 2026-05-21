package io.github.sceneview.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for [AssetResourceTracker] — the per-logical-model GPU
 * asset tracker behind `SceneView.loadModel`'s leak-free `FilamentAsset`
 * lifecycle.
 *
 * Pins issue **#1597**: before the fix `loadModel` appended every loaded asset
 * to the `models` list and never destroyed a prior asset when the *same
 * logical model* (same URL) was reloaded — so a demo hot-swapping the
 * displayed model orphaned every previous `FilamentAsset` on the GPU until
 * `destroy()` finally ran.
 *
 * Mirrors [EnvironmentResourceTrackerTest] (#1496): the tracker isolates the
 * "destroy the old handle before adopting a new one, destroy whatever is held
 * on teardown" state machine so it is unit-testable without a WebGL context.
 * Handles here are plain `String`s standing in for opaque `FilamentAsset`s and
 * the `destroyer` records every release.
 */
class AssetResourceTrackerTest {

    @Test
    fun freshTrackerHoldsNothing() {
        val tracker = AssetResourceTracker<String> {}
        assertNull(tracker.current("models/a.glb"), "a fresh tracker holds no asset")
    }

    @Test
    fun replaceWithAdoptsTheHandle() {
        val tracker = AssetResourceTracker<String> {}
        tracker.replaceWith("models/a.glb", "asset-a")
        assertEquals(
            "asset-a",
            tracker.current("models/a.glb"),
            "replaceWith must adopt the new asset for that key",
        )
    }

    @Test
    fun firstReplaceWithDestroysNothing() {
        val destroyed = mutableListOf<String>()
        val tracker = AssetResourceTracker<String> { destroyed += it }
        tracker.replaceWith("models/a.glb", "asset-a")
        assertTrue(destroyed.isEmpty(), "the first load of a URL has no prior asset to destroy")
    }

    /**
     * #1597: a 2nd `loadModel` of the same URL must release the prior asset
     * before adopting the replacement — otherwise the previous GPU asset leaks.
     */
    @Test
    fun reloadingTheSameUrlReleasesThePriorAsset() {
        val destroyed = mutableListOf<String>()
        val tracker = AssetResourceTracker<String> { destroyed += it }

        tracker.replaceWith("models/helmet.glb", "asset-v1")
        tracker.replaceWith("models/helmet.glb", "asset-v2")

        assertEquals(
            listOf("asset-v1"),
            destroyed,
            "#1597: reloading the same URL must release the prior asset exactly once",
        )
        assertEquals(
            "asset-v2",
            tracker.current("models/helmet.glb"),
            "the new asset becomes current for that URL",
        )
    }

    /**
     * #1597: distinct logical models (different URLs) coexist — loading a 2nd
     * URL must not destroy the 1st URL's asset.
     */
    @Test
    fun distinctUrlsCoexistWithoutDestroyingEachOther() {
        val destroyed = mutableListOf<String>()
        val tracker = AssetResourceTracker<String> { destroyed += it }

        tracker.replaceWith("models/a.glb", "asset-a")
        tracker.replaceWith("models/b.glb", "asset-b")

        assertTrue(destroyed.isEmpty(), "two distinct models are both live — nothing destroyed")
        assertEquals("asset-a", tracker.current("models/a.glb"))
        assertEquals("asset-b", tracker.current("models/b.glb"))
    }

    /**
     * #1597: `destroy()` must release every tracked asset — the original leak.
     */
    @Test
    fun releaseDestroysEveryHeldAsset() {
        val destroyed = mutableListOf<String>()
        val tracker = AssetResourceTracker<String> { destroyed += it }

        tracker.replaceWith("models/a.glb", "asset-a")
        tracker.replaceWith("models/b.glb", "asset-b")
        tracker.release()

        assertEquals(
            setOf("asset-a", "asset-b"),
            destroyed.toSet(),
            "#1597: destroy() must release every live GPU asset",
        )
        assertNull(tracker.current("models/a.glb"), "release() clears every slot")
        assertNull(tracker.current("models/b.glb"))
    }

    @Test
    fun releaseWithoutAssetsIsANoOp() {
        val destroyed = mutableListOf<String>()
        val tracker = AssetResourceTracker<String> { destroyed += it }
        tracker.release()
        assertTrue(destroyed.isEmpty(), "releasing an empty tracker destroys nothing")
    }

    @Test
    fun releaseIsIdempotent() {
        val destroyed = mutableListOf<String>()
        val tracker = AssetResourceTracker<String> { destroyed += it }

        tracker.replaceWith("models/a.glb", "asset-a")
        tracker.release()
        tracker.release()

        assertEquals(
            listOf("asset-a"),
            destroyed,
            "a 2nd destroy() must not double-free a handle",
        )
    }

    /**
     * End-to-end mirror of a `SceneView` session: load model A, hot-swap A
     * twice, load a procedural geometry (synthetic key), then `destroy()`.
     * Every asset ever adopted must be released exactly once.
     */
    @Test
    fun fullSessionLeavesNoLeakedAssets() {
        val destroyed = mutableListOf<String>()
        val tracker = AssetResourceTracker<String> { destroyed += it }

        tracker.replaceWith("models/a.glb", "a-v1")          // loadModel
        tracker.replaceWith("models/a.glb", "a-v2")          // reload same URL
        tracker.replaceWith("models/a.glb", "a-v3")          // reload again
        tracker.replaceWith("geometry#0", "cube")            // addGeometry
        tracker.release()                                    // destroy()

        assertEquals(
            setOf("a-v1", "a-v2", "a-v3", "cube"),
            destroyed.toSet(),
            "#1597: every FilamentAsset created during the session is destroyed once",
        )
        assertEquals(4, destroyed.size, "no asset is double-freed or leaked")
        assertNull(tracker.current("models/a.glb"))
        assertNull(tracker.current("geometry#0"))
    }
}
