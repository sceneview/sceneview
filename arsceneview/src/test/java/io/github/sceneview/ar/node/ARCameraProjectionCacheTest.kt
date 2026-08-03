package io.github.sceneview.ar.node

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the AR5 (#2263) projection-cache invalidation logic of [ARCameraNode].
 *
 * `ARCameraNode.onCameraUpdated` previously rebuilt the projection [io.github.sceneview.math.Transform]
 * on **every** tracked frame — allocating a `FloatArray(16)` + a `Transform` and firing two
 * redundant JNI calls for an identical result. The projection is now cached and only recomputed
 * when an input actually changes; [shouldRecomputeProjection] is the pure predicate that decides.
 *
 * The behaviour-preservation contract proven here:
 *  - the FIRST frame always computes (nothing cached yet);
 *  - a near/far change always recomputes (so a clip-plane edit is never frozen);
 *  - a display-geometry change always recomputes (so a device rotation/resize is never frozen);
 *  - a steady-state frame with identical inputs reuses the cache (the allocation win);
 *  - a projection rewritten from outside the ARCore path always recomputes (#2950) — the four
 *    original terms describe only the inputs ARCore owns and are all steady while another
 *    component overwrites the Filament camera underneath the cache.
 *
 * No ARCore `Camera` / Filament JNI is touched — the predicate is pure Kotlin, matching the
 * extract-and-pin pattern used by `buildPointCloudPositions` and `computePointCloudAabb`.
 */
class ARCameraProjectionCacheTest {

    private val near = 0.01f
    private val far = 1000.0f

    @Test
    fun `first frame recomputes — nothing cached yet`() {
        assertTrue(
            "With no cached projection the matrix must be computed",
            shouldRecomputeProjection(
                cached = false,
                displayGeometryChanged = false,
                projectionInvalidated = false,
                near = near,
                far = far,
                cachedNear = Float.NaN,
                cachedFar = Float.NaN,
            ),
        )
    }

    @Test
    fun `steady-state frame with identical inputs reuses cache`() {
        assertFalse(
            "Identical near/far and no geometry change must reuse the cached projection",
            shouldRecomputeProjection(
                cached = true,
                displayGeometryChanged = false,
                projectionInvalidated = false,
                near = near,
                far = far,
                cachedNear = near,
                cachedFar = far,
            ),
        )
    }

    @Test
    fun `near change recomputes`() {
        assertTrue(
            "A near-clip change must invalidate the cache",
            shouldRecomputeProjection(
                cached = true,
                displayGeometryChanged = false,
                projectionInvalidated = false,
                near = 0.05f,
                far = far,
                cachedNear = near,
                cachedFar = far,
            ),
        )
    }

    @Test
    fun `far change recomputes`() {
        assertTrue(
            "A far-clip change must invalidate the cache",
            shouldRecomputeProjection(
                cached = true,
                displayGeometryChanged = false,
                projectionInvalidated = false,
                near = near,
                far = 500.0f,
                cachedNear = near,
                cachedFar = far,
            ),
        )
    }

    @Test
    fun `display geometry change recomputes even when near far unchanged`() {
        assertTrue(
            "A device rotation/resize (hasDisplayGeometryChanged) must invalidate the cache " +
                "even though near/far are identical — otherwise a stale projection survives the rotation",
            shouldRecomputeProjection(
                cached = true,
                displayGeometryChanged = true,
                projectionInvalidated = false,
                near = near,
                far = far,
                cachedNear = near,
                cachedFar = far,
            ),
        )
    }

    @Test
    fun `projection rewritten outside the ARCore path recomputes — every other input is steady`() {
        assertTrue(
            "A projection rebuilt from a generic focal length (ARSceneView's surface-resize " +
                "callback calls CameraNode.updateProjection) leaves the Filament camera holding a " +
                "matrix the cache did not compute. near/far and the display geometry are all " +
                "unchanged at that point — this is the ONLY term that can see it, and without it " +
                "nothing ever re-derives ARCore's projection again (#2950)",
            shouldRecomputeProjection(
                cached = true,
                displayGeometryChanged = false,
                projectionInvalidated = true,
                near = near,
                far = far,
                cachedNear = near,
                cachedFar = far,
            ),
        )
    }

    @Test
    fun `a clobbered projection is not masked by an otherwise-recomputing frame`() {
        // Guards the inverse mistake: `projectionInvalidated` must widen the predicate, never
        // narrow it. Pinning both polarities means neither `||` term can be flipped to `&&`
        // without a red test.
        assertTrue(
            "A clip-plane change must still recompute while the projection is also clobbered",
            shouldRecomputeProjection(
                cached = true,
                displayGeometryChanged = false,
                projectionInvalidated = true,
                near = 0.05f,
                far = far,
                cachedNear = near,
                cachedFar = far,
            ),
        )
        assertFalse(
            "A steady-state frame with an intact projection must still reuse the cache — the new " +
                "term must not defeat the AR5 allocation win it was added alongside",
            shouldRecomputeProjection(
                cached = true,
                displayGeometryChanged = false,
                projectionInvalidated = false,
                near = near,
                far = far,
                cachedNear = near,
                cachedFar = far,
            ),
        )
    }
}
