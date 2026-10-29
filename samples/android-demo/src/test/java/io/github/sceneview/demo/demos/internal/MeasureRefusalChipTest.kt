package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #4765: the chip under the reticle while the walls rise. "Aim at the wall to read its height"
 * says it before a height reads, and goes once one does — the card shows the height then, and
 * the chip would say the opposite of what the screen does.
 */
class MeasureRefusalChipTest {
    private fun chip(
        tapped: MeasureRefusal? = null,
        live: MeasureRefusal? = null,
        coaching: Boolean = false,
        reading: Boolean = false,
        read: Boolean = false,
    ) = measureRefusalChip(tapped, live, coaching, reading, read)

    @Test
    fun `before a height reads, aiming off the wall says so`() {
        assertEquals(MeasureRefusal.OffWall, chip(live = MeasureRefusal.OffWall))
        // The coaching overlay says what to do instead.
        assertNull(chip(live = MeasureRefusal.OffWall, coaching = true))
    }

    @Test
    fun `once a height reads, the chip goes`() {
        assertNull(chip(reading = true, read = true))
        // The aim slips past the wall's edge for a frame while the card still shows the height read.
        assertNull(chip(live = MeasureRefusal.OffWall, read = true))
    }

    @Test
    fun `a refused Set height says why until a height reads again`() {
        assertEquals(MeasureRefusal.OffWall, chip(tapped = MeasureRefusal.OffWall, live = MeasureRefusal.OffWall, read = true))
        assertNull(chip(tapped = MeasureRefusal.OffWall, reading = true, read = true))
    }

    @Test
    fun `the other refusals say what they said`() {
        assertEquals(MeasureRefusal.OpeningOffWall, chip(live = MeasureRefusal.OpeningOffWall, read = true))
        assertEquals(MeasureRefusal.SameCorner, chip(tapped = MeasureRefusal.SameCorner, reading = true, read = true))
        assertEquals(MeasureRefusal.PlanNeedsClose, chip(tapped = MeasureRefusal.PlanNeedsClose))
        // Nothing under the reticle is the hint's to say, not the chip's, until a tap is refused.
        assertNull(chip(live = MeasureRefusal.NoSurface))
    }
}
