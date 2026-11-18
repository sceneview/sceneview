package io.github.sceneview.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

/** The live badge uses the continuous gesture delta, never the wrapped absolute heading. */
class NodeEditingFeedbackBadgeTest {
    @Test
    fun `positive delta is signed and zero is not`() {
        assertEquals("+2°", rotationBadgeText(deltaDegrees = 2f))
        assertEquals("0°", rotationBadgeText(deltaDegrees = 0f))
        assertEquals("0°", rotationBadgeText(deltaDegrees = 0.4f))
    }

    @Test
    fun `negative multi-turn delta is not folded`() {
        assertEquals("-400°", rotationBadgeText(deltaDegrees = -400f))
        assertEquals("+540°", rotationBadgeText(deltaDegrees = 540f))
    }

    @Test
    fun `announced rotation is the last fifteen degree step crossed, toward zero`() {
        assertEquals(0, rotationAnnouncementDegrees(0f))
        assertEquals(0, rotationAnnouncementDegrees(14.4f))
        assertEquals(15, rotationAnnouncementDegrees(15f))
        assertEquals(15, rotationAnnouncementDegrees(29f))
        assertEquals(0, rotationAnnouncementDegrees(-14f))
        assertEquals(-15, rotationAnnouncementDegrees(-16f))
        assertEquals(-390, rotationAnnouncementDegrees(-400f))
    }

    @Test
    fun `a full twist is announced a handful of times, not once per degree`() {
        val announced = (0..90).map { rotationAnnouncementDegrees(it.toFloat()) }.distinct()
        assertEquals(listOf(0, 15, 30, 45, 60, 75, 90), announced)
    }

    @Test
    fun `announced scale moves in ten percent steps away from the baseline, exact at a limit`() {
        assertEquals(100, scaleAnnouncementPercent(100f, atLimit = false))
        assertEquals(100, scaleAnnouncementPercent(109.4f, atLimit = false))
        assertEquals(110, scaleAnnouncementPercent(110f, atLimit = false))
        assertEquals(100, scaleAnnouncementPercent(97f, atLimit = false))
        assertEquals(90, scaleAnnouncementPercent(89f, atLimit = false))
        assertEquals(40, scaleAnnouncementPercent(31f, atLimit = false))
        assertEquals(25, scaleAnnouncementPercent(25f, atLimit = true))
        assertEquals(
            listOf(100, 110, 120, 130, 140, 150),
            (100..150).map { scaleAnnouncementPercent(it.toFloat(), atLimit = false) }.distinct(),
        )
    }
}
