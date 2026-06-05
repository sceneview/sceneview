package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shared plane-primitive selection used by [PlaneVisualizer] (V1) and [PlaneVisualizerV2]
 * (#2328 / #2402).
 *
 * `updateRenderable()` ran every frame the plane updated and built its primitive list with a fresh
 * `buildList { }` each call — a per-plane, per-frame allocation. [selectPlanePrimitives] replaces
 * that with a single reused list cleared and refilled from the live arguments each call. This test
 * proves, with no Filament, that:
 *  - the selection logic is byte-for-byte identical to the old `buildList { }` gating (plane added
 *    only when visible + non-null, shadow only when receiver + non-null), in plane-then-shadow
 *    draw order;
 *  - the SAME list instance is reused across calls (the allocation is genuinely removed);
 *  - the list is cleared between calls, so a previous selection never leaks into the next — there
 *    is no stale-state path (the cache-invalidation risk for this kind of reuse).
 *
 * `String` stands in for the Filament `MaterialInstance` — the helper is generic and the selection
 * depends only on the two flags and each material's nullness.
 */
class PlaneVisualizerPrimitivesTest {

    private val plane = "plane"
    private val shadow = "shadow"

    @Test
    fun `visible plus shadow-receiver selects both in draw order`() {
        val out = selectPlanePrimitives(
            isVisible = true,
            planeMaterial = plane,
            isShadowReceiver = true,
            shadowMaterial = shadow,
            out = ArrayList(),
        )
        assertEquals(listOf(plane, shadow), out)
    }

    @Test
    fun `visible only selects the plane material`() {
        val out = selectPlanePrimitives(
            isVisible = true,
            planeMaterial = plane,
            isShadowReceiver = false,
            shadowMaterial = shadow,
            out = ArrayList(),
        )
        assertEquals(listOf(plane), out)
    }

    @Test
    fun `shadow-receiver only selects the shadow material`() {
        val out = selectPlanePrimitives(
            isVisible = false,
            planeMaterial = plane,
            isShadowReceiver = true,
            shadowMaterial = shadow,
            out = ArrayList(),
        )
        assertEquals(listOf(shadow), out)
    }

    @Test
    fun `null materials are skipped even when the flags are on`() {
        val out = selectPlanePrimitives<String>(
            isVisible = true,
            planeMaterial = null,
            isShadowReceiver = true,
            shadowMaterial = null,
            out = ArrayList(),
        )
        assertTrue("No materials set yet → empty selection", out.isEmpty())
    }

    @Test
    fun `nothing visible and not a receiver selects nothing`() {
        val out = selectPlanePrimitives(
            isVisible = false,
            planeMaterial = plane,
            isShadowReceiver = false,
            shadowMaterial = shadow,
            out = ArrayList(),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `reuses the same list instance and clears stale state between calls`() {
        val scratch = ArrayList<String>(2)

        val first = selectPlanePrimitives(
            isVisible = true,
            planeMaterial = plane,
            isShadowReceiver = true,
            shadowMaterial = shadow,
            out = scratch,
        )
        assertSame("Helper must return the caller's reused list, not a fresh one", scratch, first)
        assertEquals(listOf(plane, shadow), first)

        // A later frame where the plane is no longer a shadow receiver must NOT leak the stale
        // shadow primitive — the list is cleared and rebuilt from the live flags.
        val second = selectPlanePrimitives(
            isVisible = true,
            planeMaterial = plane,
            isShadowReceiver = false,
            shadowMaterial = shadow,
            out = scratch,
        )
        assertSame(scratch, second)
        assertEquals(listOf(plane), second)
    }
}
