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

    @Test
    fun `a rotation held near a step boundary keeps its announcement`() {
        val announced = mutableListOf<Int>()
        var previous: Int? = null
        for (degrees in listOf(14f, 16f, 14f, 15f, 16f, 14f, 17f, 15f, 13f, 16f, 14f, 13f, 12f)) {
            previous = rotationAnnouncementDegrees(degrees, previous)
            announced += previous
        }
        // One move up once 2° past +15°, one move down once 2° below it: no flapping.
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 15, 15, 15, 15, 15, 15, 0), announced)
    }

    @Test
    fun `a negative twist uses the same hysteresis`() {
        assertEquals(0, rotationAnnouncementDegrees(-16f, previous = 0))
        assertEquals(-15, rotationAnnouncementDegrees(-17f, previous = 0))
        assertEquals(-15, rotationAnnouncementDegrees(-13f, previous = -15))
        assertEquals(0, rotationAnnouncementDegrees(-12f, previous = -15))
    }

    @Test
    fun `a pinch held near a step or off a limit keeps its announcement`() {
        assertEquals(100, scaleAnnouncementPercent(111f, atLimit = false, previous = 100))
        assertEquals(110, scaleAnnouncementPercent(112f, atLimit = false, previous = 100))
        assertEquals(110, scaleAnnouncementPercent(108f, atLimit = false, previous = 110))
        assertEquals(100, scaleAnnouncementPercent(107f, atLimit = false, previous = 110))
        // Pressed against a 25 % minimum, then easing off it: the exact limit holds.
        assertEquals(25, scaleAnnouncementPercent(25f, atLimit = true, previous = 40))
        assertEquals(25, scaleAnnouncementPercent(27f, atLimit = false, previous = 25))
        assertEquals(25, scaleAnnouncementPercent(31f, atLimit = false, previous = 25))
        assertEquals(40, scaleAnnouncementPercent(33f, atLimit = false, previous = 25))
        // Pressed against a 300 % maximum, then easing off it.
        assertEquals(300, scaleAnnouncementPercent(299f, atLimit = false, previous = 300))
        assertEquals(290, scaleAnnouncementPercent(297f, atLimit = false, previous = 300))
    }
}
