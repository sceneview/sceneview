package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the words `ARDepthOcclusionDemo` shows (#3340).
 *
 * The demo itself needs ARCore and a camera, so none of it runs on an emulator — which is
 * exactly why the copy and the visibility rules are extracted into [DepthOcclusionCopy].
 * What these tests defend is the property the issue was filed about: **the screen has to
 * say what the toggle does, not what it is called.**
 */
class DepthOcclusionCopyTest {

    @Test
    fun `state title names the effect, not the ARCore setting`() {
        assertEquals("Occlusion ON", DepthOcclusionCopy.stateTitle(true))
        assertEquals("Occlusion OFF", DepthOcclusionCopy.stateTitle(false))
    }

    @Test
    fun `each state states its visible consequence`() {
        // The regression this guards: a pill that reads `DEPTH ON` and stops there. A
        // still frame can only carry a before/after contrast in words, so both states
        // must describe what the user should be seeing.
        val on = DepthOcclusionCopy.stateConsequence(true)
        val off = DepthOcclusionCopy.stateConsequence(false)
        assertNotEquals("The two states must not read the same", on, off)
        assertTrue("ON must say real objects win: was \"$on\"", on.contains("hide"))
        assertTrue("OFF must say the model wins: was \"$off\"", off.contains("draws over"))
    }

    @Test
    fun `toggle button names the action, not the current state`() {
        // A button labelled the same as the pill above it leaves the user unsure whether
        // it reports or acts.
        assertEquals("Turn occlusion off", DepthOcclusionCopy.toggleAction(true))
        assertEquals("Turn occlusion on", DepthOcclusionCopy.toggleAction(false))
        assertNotEquals(
            DepthOcclusionCopy.toggleAction(true),
            DepthOcclusionCopy.stateTitle(true),
        )
    }

    @Test
    fun `no coaching line while tracking is lost`() {
        // The scaffold's tracking banner is louder and more urgent; stacking a second
        // sentence on it is noise.
        assertNull(
            DepthOcclusionCopy.coachingHint(
                isTracking = false,
                hasPlacedModel = false,
                occlusionOn = true,
            )
        )
        assertNull(
            DepthOcclusionCopy.coachingHint(
                isTracking = false,
                hasPlacedModel = true,
                occlusionOn = false,
            )
        )
    }

    @Test
    fun `tracking with nothing placed asks for the tap`() {
        val hint = DepthOcclusionCopy.coachingHint(
            isTracking = true,
            hasPlacedModel = false,
            occlusionOn = true,
        )
        assertEquals("Tap a flat surface to place the helmet", hint)
    }

    @Test
    fun `once placed, the hint names the gesture that reveals the effect`() {
        val on = DepthOcclusionCopy.coachingHint(
            isTracking = true,
            hasPlacedModel = true,
            occlusionOn = true,
        )
        val off = DepthOcclusionCopy.coachingHint(
            isTracking = true,
            hasPlacedModel = true,
            occlusionOn = false,
        )
        assertNotNullAndContains(on, "hand")
        assertNotNullAndContains(off, "hand")
        assertNotEquals(
            "The placed hint must differ by state — that difference IS the demo",
            on,
            off,
        )
        // The OFF hint has to point back at the other state, otherwise a user who lands
        // on OFF first has no reason to ever flip it.
        assertNotNullAndContains(off, "occlusion on")
    }

    @Test
    fun `the unsupported-device sentence states both the limit and its cause`() {
        val text = DepthOcclusionCopy.UNSUPPORTED
        assertTrue("must name the cause: \"$text\"", text.contains("Depth API"))
        assertTrue("must state the consequence: \"$text\"", text.contains("occlusion"))
    }

    private fun assertNotNullAndContains(actual: String?, needle: String) {
        assertTrue(
            "Expected \"$actual\" to contain \"$needle\"",
            actual != null && actual.contains(needle, ignoreCase = true),
        )
    }
}
