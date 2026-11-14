package io.github.sceneview.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

/** The live badge must use the continuous delta, not the wrapped absolute heading. */
class NodeEditingFeedbackBadgeTest {
    @Test
    fun `live badge crosses the absolute heading seam with a signed delta`() {
        assertEquals("+2°", rotationBadgeText(rotating = true, deltaDegrees = 2f, absoluteDegrees = -179f))
        assertEquals("-179°", rotationBadgeText(rotating = false, deltaDegrees = 2f, absoluteDegrees = -179f))
    }

    @Test
    fun `negative multi-turn delta is not folded and a new gesture starts at zero`() {
        assertEquals("-400°", rotationBadgeText(rotating = true, deltaDegrees = -400f, absoluteDegrees = 139f))
        assertEquals("0°", rotationBadgeText(rotating = true, deltaDegrees = 0f, absoluteDegrees = 139f))
    }
}
