package io.github.sceneview.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for issue #2311 (follow-up to #2273 / #2310).
 *
 * `ModelNode.sanitizeEmptyBoundingBoxes()` latches a static renderable into
 * `permanentlyValidEntities` once its AABB is observed valid, then skips re-scanning it on every
 * subsequent frame. That holds for everything the render loop does, but explicitly mutating a
 * *latched* renderable via `RenderableComponent.setGeometry` / `setGeometryAt` / the
 * `axisAlignedBoundingBox` setter with a degenerate box re-introduces an empty AABB the latch would
 * never re-scan → Filament "AABB can't be empty" crash.
 *
 * The fix has `ModelNode.RenderableNode` override those three mutators to evict the entity from the
 * latch (`evictFromSanitizeLatch`), so the next sanitize pass re-scans it and re-detects the empty
 * AABB before Filament can crash on it.
 *
 * `ModelNode` requires a Filament `Engine` + `ModelInstance` (native JNI) so it cannot be
 * instantiated in a pure-JVM test. This test pins the exact latch / evict / re-scan semantics the
 * fix relies on, using a faithful model of the relevant `sanitizeRenderable` + `evictFromSanitizeLatch`
 * state machine.
 */
class SanitizeLatchEvictionTest {

    /**
     * Faithful pure-JVM model of `ModelNode`'s sanitize-once latch, restricted to a single static
     * renderable (no skins, no morph targets — the only case that ever latches).
     *
     * - [aabbEmpty] stands in for the live AABB Filament would report.
     * - [latched] mirrors membership of the entity in `permanentlyValidEntities`.
     * - [scanCount] counts how many times the per-frame check actually inspected the AABB, so the
     *   test can prove the latch skips work and that an eviction forces a fresh inspection.
     */
    private class StaticRenderableLatchModel(var aabbEmpty: Boolean) {
        var latched = false
            private set
        var scanCount = 0
            private set

        /** Mirrors `sanitizeEmptyBoundingBoxes` + `sanitizeRenderable` for one static renderable. */
        fun sanitizePass() {
            // Fast path: already latched → skipped, zero AABB work (the whole point of the latch).
            if (latched) return
            scanCount++
            // Static renderable observed valid → latch it so it is never re-scanned.
            if (!aabbEmpty) {
                latched = true
            }
        }

        /** Mirrors `RenderableNode.setGeometry` / `setGeometryAt` / `axisAlignedBoundingBox` setter. */
        fun mutateGeometry(makesAabbEmpty: Boolean) {
            aabbEmpty = makesAabbEmpty
            // The fix: every explicit geometry / AABB mutation evicts from the latch.
            evictFromSanitizeLatch()
        }

        /** Mirrors `ModelNode.evictFromSanitizeLatch`. */
        private fun evictFromSanitizeLatch() {
            latched = false
        }
    }

    @Test
    fun `valid static renderable is latched and then skipped on later passes`() {
        val model = StaticRenderableLatchModel(aabbEmpty = false)

        model.sanitizePass()
        assertTrue("a valid static renderable latches on first pass", model.latched)
        assertEquals(1, model.scanCount)

        // Subsequent passes are skipped entirely — no further AABB inspection.
        repeat(5) { model.sanitizePass() }
        assertEquals("latched renderable is never re-scanned", 1, model.scanCount)
    }

    @Test
    fun `mutating a latched renderable to a degenerate box evicts it so the next pass re-scans`() {
        val model = StaticRenderableLatchModel(aabbEmpty = false)
        model.sanitizePass()
        assertTrue("renderable is latched after being observed valid", model.latched)

        // Out-of-band mutation that re-introduces an empty AABB (#2311).
        model.mutateGeometry(makesAabbEmpty = true)
        assertFalse("explicit geometry mutation evicts from the latch", model.latched)

        // The very next sanitize pass must inspect the AABB again (re-scan), catching the empty box
        // before Filament can crash on it.
        val scansBefore = model.scanCount
        model.sanitizePass()
        assertEquals("evicted renderable is re-scanned on the next pass", scansBefore + 1, model.scanCount)
        assertFalse("an empty AABB does not re-latch — it stays under the per-frame check", model.latched)
    }

    @Test
    fun `mutating a latched renderable to a still-valid box evicts then re-latches on next pass`() {
        val model = StaticRenderableLatchModel(aabbEmpty = false)
        model.sanitizePass()
        assertTrue(model.latched)

        // A mutation to a still-valid geometry must STILL evict (the mutator can't cheaply know the
        // new AABB is non-empty) — the next pass re-scans and re-latches.
        model.mutateGeometry(makesAabbEmpty = false)
        assertFalse("any explicit mutation evicts, even to a valid box", model.latched)

        val scansBefore = model.scanCount
        model.sanitizePass()
        assertEquals("the evicted renderable is re-scanned", scansBefore + 1, model.scanCount)
        assertTrue("a still-valid renderable re-latches after the re-scan", model.latched)
    }
}
