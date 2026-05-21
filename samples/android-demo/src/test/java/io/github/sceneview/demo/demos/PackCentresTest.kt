package io.github.sceneview.demo.demos

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for [packCentres] — the slot-skipping helper that translates the demo's
 * `Array<SphereNode?>` slot store into the flat `(x, y, z)*` payload expected by
 * `DepthCollider.setBodiesRegion`.
 *
 * Pins three properties that together close the #1842 regression:
 *  - empty / all-null slots return `null` so `setBodiesRegion` sees the "no bodies" sentinel
 *    rather than an empty array;
 *  - populated slots produce a flat `FloatArray` sized exactly `populated * 3` — no stale-ref
 *    bloat across recompositions (the array length cannot exceed the actual ball count);
 *  - the helper preserves position ordering across slot indices.
 */
class PackCentresTest {

    @Test
    fun `empty slot store returns null sentinel`() {
        val out = packCentres(slotCount = 0, slotAt = { error("not called") })
        assertNull(out)
    }

    @Test
    fun `all-null slots return null sentinel`() {
        val out = packCentres(slotCount = 5, slotAt = { null })
        assertNull(out)
    }

    @Test
    fun `populated slots flatten to a FloatArray sized populated × 3`() {
        // Two populated, three null — output length must be 2 × 3 = 6, NOT 5 × 3 = 15.
        val slots: Array<FloatArray?> = arrayOf(
            floatArrayOf(1f, 2f, 3f),
            null,
            null,
            floatArrayOf(4f, 5f, 6f),
            null,
        )
        val out = packCentres(slotCount = slots.size, slotAt = { slots[it] })
        assertNotNull(out)
        assertEquals(6, out!!.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), out, 0f)
    }

    @Test
    fun `ordering across slot indices is preserved (regression — region AABB sanity)`() {
        // The collider's region cull would skew if positions got shuffled. Pin the ordering
        // explicitly with non-trivial values.
        val slots: Array<FloatArray?> = arrayOf(
            floatArrayOf(0.1f, -1.0f, 0f),
            floatArrayOf(0.2f, -0.95f, 0f),
            floatArrayOf(0.3f, -0.9f, 0f),
        )
        val out = packCentres(slotCount = slots.size, slotAt = { slots[it] })!!
        assertEquals(9, out.size)
        assertEquals(0.1f, out[0], 1e-6f)
        assertEquals(0.2f, out[3], 1e-6f)
        assertEquals(0.3f, out[6], 1e-6f)
    }

    @Test
    fun `slot store length larger than populated count does not bleed stale entries (#1842)`() {
        // Simulates the bug class: a long slot store (e.g. left over from a previous higher
        // ballCount) with a smaller populated subset. The output FloatArray length must reflect
        // ONLY the populated slots — never the whole store.
        val slots: Array<FloatArray?> = arrayOfNulls(100)
        slots[7] = floatArrayOf(7f, 7f, 7f)
        slots[42] = floatArrayOf(42f, 42f, 42f)
        val out = packCentres(slotCount = slots.size, slotAt = { slots[it] })!!
        assertEquals("ouptut length must equal populated × 3 = 6", 6, out.size)
        assertArrayEquals(floatArrayOf(7f, 7f, 7f, 42f, 42f, 42f), out, 0f)
    }

    @Test
    fun `a single populated slot returns a 3-float array`() {
        val out = packCentres(
            slotCount = 1,
            slotAt = { i -> if (i == 0) floatArrayOf(-0.05f, 0.5f, -0.3f) else null },
        )
        assertNotNull(out)
        assertEquals(3, out!!.size)
        assertArrayEquals(floatArrayOf(-0.05f, 0.5f, -0.3f), out, 0f)
    }
}
