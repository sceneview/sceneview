package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Portrait focus (#4649): what stays sharp, and what the status says. */
class PortraitFocusTest {

    @Test
    fun `distance is the lens to subject length`() {
        val d = PortraitFocus.distance(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, -0.3f, -1f))
        assertEquals(1.044f, d, 0.001f)
    }

    @Test
    fun `follow ignores sub-centimetre jitter and adopts real moves`() {
        assertEquals(1.0f, PortraitFocus.follow(null, 1.0f), 0f)
        assertEquals(1.0f, PortraitFocus.follow(1.0f, 1.004f), 0f)
        assertEquals(1.2f, PortraitFocus.follow(1.0f, 1.2f), 0f)
    }

    @Test
    fun `focus is clamped to the slider range`() {
        assertEquals(PortraitFocus.MIN_FOCUS_METRES, PortraitFocus.follow(null, 0.02f), 0f)
        assertEquals(PortraitFocus.MAX_FOCUS_METRES, PortraitFocus.follow(null, 9f), 0f)
        assertEquals(
            PortraitFocus.MAX_FOCUS_METRES,
            PortraitFocus.focusDistance(PortraitFocusTarget.Point(12f), null)!!,
            0f,
        )
    }

    @Test
    fun `subject focus waits for a tracked frame, a tapped point does not`() {
        assertNull(PortraitFocus.focusDistance(PortraitFocusTarget.Subject, null))
        assertEquals(1.5f, PortraitFocus.focusDistance(PortraitFocusTarget.Subject, 1.5f)!!, 0f)
        assertEquals(2.3f, PortraitFocus.focusDistance(PortraitFocusTarget.Point(2.3f), 1.5f)!!, 0f)
    }

    @Test
    fun `status follows the switch first, then depth support, then the target`() {
        val subject = PortraitFocusTarget.Subject
        assertEquals(PortraitFocusStatus.Original, PortraitFocus.status(false, false, subject, 1f))
        assertEquals(PortraitFocusStatus.Unsupported, PortraitFocus.status(true, false, subject, 1f))
        assertEquals(PortraitFocusStatus.Waiting, PortraitFocus.status(true, null, subject, null))
        assertEquals(PortraitFocusStatus.Subject, PortraitFocus.status(true, true, subject, 1f))
        assertEquals(
            PortraitFocusStatus.Point,
            PortraitFocus.status(true, true, PortraitFocusTarget.Point(2f), null),
        )
    }
}
