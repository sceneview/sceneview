package io.github.sceneview.ar.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM sentinel for the [AnchorNode.anchor] setter detach contract (#2043).
 *
 * The bug: `AnchorNode.anchor`'s setter swapped the backing field but never `detach()`ed
 * the replaced [com.google.ar.core.Anchor]. ARCore anchors accrue per-frame tracking cost
 * while attached, so reassigning the property (re-localization, snapping to a new plane,
 * cloud-anchor resolve) leaked one ARCore anchor per reassignment.
 *
 * The fix detaches the previous anchor before replacing it, guarded by `field == value` so
 * re-assigning the *same* instance is a no-op. `Anchor.detach()` is documented as
 * idempotent, so the internal move-gesture path (`onMoveBegin` already detaches) stays
 * safe.
 *
 * [AnchorNode] needs a Filament `Engine` and a real ARCore `Anchor` (both framework-bound,
 * non-mockable in pure JVM). This test pins the exact setter algorithm with a fake anchor
 * that records `detach()` calls — mirroring the production setter line-for-line.
 */
class AnchorNodeDetachContractTest {

    /** Records detach() calls; `detach()` is idempotent, mirroring ARCore's Anchor. */
    private class FakeAnchor(val name: String) {
        var detachCount = 0
            private set

        fun detach() {
            detachCount++ // idempotent in ARCore — repeated calls are harmless
        }
    }

    /** Mirrors `AnchorNode`'s `anchor` property + the setter under test. */
    private class AnchorHolder(initial: FakeAnchor) {
        var anchor: FakeAnchor = initial
            set(value) {
                if (field == value) return
                field.detach() // <- the fix: release the replaced anchor
                field = value
            }
    }

    @Test
    fun `reassigning anchor detaches the previous one exactly once`() {
        val first = FakeAnchor("first")
        val second = FakeAnchor("second")
        val holder = AnchorHolder(first)

        holder.anchor = second

        assertEquals("old anchor must be detached exactly once", 1, first.detachCount)
        assertEquals("the new anchor must not be detached", 0, second.detachCount)
        assertEquals("the new anchor is now held", second, holder.anchor)
    }

    @Test
    fun `assigning the same anchor instance is a no-op and does not detach it`() {
        val anchor = FakeAnchor("only")
        val holder = AnchorHolder(anchor)

        holder.anchor = anchor // self-assignment

        assertEquals("self-assignment must not detach the live anchor", 0, anchor.detachCount)
        assertEquals(anchor, holder.anchor)
    }

    @Test
    fun `chained reassignments detach every superseded anchor`() {
        val a = FakeAnchor("a")
        val b = FakeAnchor("b")
        val c = FakeAnchor("c")
        val holder = AnchorHolder(a)

        holder.anchor = b
        holder.anchor = c

        assertEquals("a superseded by b → detached", 1, a.detachCount)
        assertEquals("b superseded by c → detached", 1, b.detachCount)
        assertEquals("c is current → not detached", 0, c.detachCount)
        assertEquals(c, holder.anchor)
    }

    @Test
    fun `move-gesture path stays safe — detach is idempotent`() {
        // onMoveBegin detaches the current anchor; onMoveEnd then assigns a new one,
        // and the setter detaches the (already-detached) old anchor again. ARCore's
        // Anchor.detach() is idempotent, so this double detach is harmless.
        val old = FakeAnchor("old")
        val fresh = FakeAnchor("fresh")
        val holder = AnchorHolder(old)

        old.detach() // onMoveBegin → detachAnchor()
        holder.anchor = fresh // onMoveEnd → anchor = createAnchor()

        assertTrue("old anchor detached (twice, idempotent)", old.detachCount >= 1)
        assertEquals("fresh anchor is current and attached", fresh, holder.anchor)
        assertEquals(0, fresh.detachCount)
    }
}
