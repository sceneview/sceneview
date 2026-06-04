package io.github.sceneview.node

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic companion to the engine-backed [NodeParentTransformCacheTest], pinning the
 * validity-flag cache contract behind the `Node.parentEntity` (#2403) / `Node.parentInstance`
 * (#2404) JNI caches.
 *
 * A real [Node] needs a Filament Engine (its parent caches are populated by `getParentOrNull()` /
 * `getInstance()` JNI calls), so the rigorous equivalence-after-mutation proof lives in the
 * instrumented `androidTest`. This JVM test models the EXACT cache state machine `Node` uses — a
 * `valid` flag guarding a cached value, invalidated on the single reparent write path — and pins the
 * three properties that make it correct, mirroring how [NodeTransformDriftTest] pins the local-TRS
 * fix in isolation:
 *
 *  1. **It actually caches** — repeated reads consult the backing store exactly once.
 *  2. **`null` is a legitimate cached value** — a detached node's `null` parent is cached and NOT
 *     re-fetched, which is precisely why a validity FLAG is required rather than a null sentinel.
 *  3. **Invalidation refreshes** — after a reparent (backing store mutated + cache invalidated) the
 *     next read returns the new value. A MISSED invalidation would return a silently-stale parent.
 */
class NodeParentCacheContractTest {

    /**
     * Mirrors `Node`'s parent-cache control flow exactly: a `valid` flag, a cached value, a lazy
     * fetch from a "Filament" backing store on a miss, and invalidation on reparent. [fetchCount]
     * counts backing-store reads so the test can prove the cache elides JNI.
     */
    private class ParentCacheModel(private var backingParent: Int?) {
        private var valid = false
        private var cached: Int? = null
        var fetchCount = 0
            private set

        /** Mirrors `Node.parentEntity.get()`. */
        fun read(): Int? {
            if (!valid) {
                cached = backingParent       // stands in for transformManager.getParentOrNull(...)
                fetchCount++
                valid = true
            }
            return cached
        }

        /** Mirrors `Node.parentInstance.set()`: write Filament, then invalidate the cache. */
        fun reparent(newParent: Int?) {
            backingParent = newParent        // stands in for transformManager.setParent(...)
            valid = false
        }
    }

    @Test
    fun `repeated reads fetch from the backing store exactly once`() {
        val cache = ParentCacheModel(backingParent = 42)
        repeat(50) { assertEquals(42, cache.read()) }
        assertEquals("cache must elide all but the first backing-store read", 1, cache.fetchCount)
    }

    @Test
    fun `a null parent is cached and not re-fetched (validity flag, not null sentinel)`() {
        val cache = ParentCacheModel(backingParent = null)
        repeat(50) { assertEquals(null, cache.read()) }
        // A null-sentinel cache would re-fetch every read because the value IS null. The validity
        // flag caches the null after one fetch — this is the property the flag exists to provide.
        assertEquals("a cached null must not be re-fetched", 1, cache.fetchCount)
    }

    @Test
    fun `reparent invalidates the cache so the next read reflects the new parent`() {
        val cache = ParentCacheModel(backingParent = 1)
        assertEquals(1, cache.read())            // prime

        cache.reparent(2)
        assertEquals("read after reparent must reflect the new parent", 2, cache.read())

        cache.reparent(null)
        assertEquals("read after detach must reflect the cleared parent", null, cache.read())

        cache.reparent(3)
        assertEquals("read after re-attach must reflect the new parent", 3, cache.read())

        // primed(1) + reparent(2) + reparent(null) + reparent(3) → 4 distinct fetches, never stale.
        assertEquals(4, cache.fetchCount)
    }
}
