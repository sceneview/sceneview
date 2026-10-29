package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScanLabels]: the recognised parts' labels over a scan's 3D view never cover one another — two
 * beds and a chair a metre apart piled their cards into one unreadable stack on a Pixel 4a.
 */
class ScanLabelsTest {

    @Test
    fun `labels apart stay where they want`() {
        val wanted = listOf(label(100, 400), label(600, 400))
        assertEquals(wanted, ScanLabels.declutter(wanted, AREA, GAP, MAX_SHIFT))
    }

    @Test
    fun `the surest keeps its place and the others slide clear of it`() {
        // Two beds and a chair whose boxes' tops land within a few pixels of each other.
        val wanted = listOf(label(300, 800), label(320, 810), label(310, 790))
        val placed = ScanLabels.declutter(wanted, AREA, GAP, MAX_SHIFT)

        assertEquals(wanted[0], placed[0])
        val shown = placed.filterNotNull()
        assertEquals(3, shown.size)
        for (i in shown.indices) for (j in i + 1 until shown.size) {
            assertFalse("${shown[i]} covers ${shown[j]}", shown[i].overlaps(shown[j], GAP))
        }
    }

    @Test
    fun `a label slides to the nearest free place`() {
        val wanted = listOf(label(300, 800), label(300, 800))
        val second = ScanLabels.declutter(wanted, AREA, GAP, MAX_SHIFT)[1]!!
        // Straight over or under the first, a label's height and a gap away, not across the view.
        assertEquals(300, second.left)
        assertEquals(H + GAP, kotlin.math.abs(second.top - 800))
    }

    @Test
    fun `labels never leave the area nor cover the corner card`() {
        val card = LabelRect(AREA.right - 300, 0, 300, 500)
        val wanted = listOf(label(AREA.right - W, AREA.top), label(AREA.right - W - 10, AREA.top + 5))
        val placed = ScanLabels.declutter(wanted, AREA, GAP, MAX_SHIFT, obstacles = listOf(card))
        placed.filterNotNull().forEach {
            assertTrue("$it outside", it.inside(AREA))
            assertFalse("$it under the card", it.overlaps(card, GAP))
        }
    }

    @Test
    fun `a label with no free place within reach is left out`() {
        // A crowd of ten on one spot: the reach lets only a few in.
        val wanted = List(10) { label(300, 800) }
        val placed = ScanLabels.declutter(wanted, AREA, GAP, maxShift = H + GAP)
        assertNotNull(placed[0])
        assertTrue(placed.count { it == null } > 0)
        val shown = placed.filterNotNull()
        for (i in shown.indices) for (j in i + 1 until shown.size) assertFalse(shown[i].overlaps(shown[j], GAP))
    }

    @Test
    fun `a label without a place this frame stays unwritten and takes no room`() {
        val wanted = listOf(null, label(300, 800))
        assertEquals(listOf(null, label(300, 800)), ScanLabels.declutter(wanted, AREA, GAP, MAX_SHIFT))
    }

    @Test
    fun `the leader line runs from the box to the label's nearest edge`() {
        val rect = label(300, 600)
        assertEquals(350f to (600f + H), ScanLabels.leaderEnd(350f, 900f, rect))
        assertNull(ScanLabels.leaderEnd(350f, 610f, rect))
    }

    private fun label(x: Int, y: Int) = LabelRect(x, y, W, H)

    private companion object {
        const val W = 220
        const val H = 110
        const val GAP = 16
        const val MAX_SHIFT = 400
        val AREA = LabelRect(40, 300, 1000, 1600)
    }
}
