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
}
