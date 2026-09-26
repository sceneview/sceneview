package io.github.sceneview.node

import io.github.sceneview.math.Position
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [cameraMovedSinceApplied] is the whole of [BillboardNode]'s render-on-demand behaviour (#3718).
 *
 * Before it existed, the node re-oriented from the public `onFrame` slot and wrote its transform on
 * every tick whether or not the camera had moved. Both halves were defects, and they hid each other:
 * the unconditional write requested a frame every tick (the transform setter is a push source), and
 * `Node.isFrameActive` read the non-null `onFrame` as a standing request for frames anyway. A demo
 * screen with two `TextNode`s — `BillboardNode` subclasses — held 842 frames per 15 s on a picture
 * that changed 0.001 %.
 *
 * Run these against the old behaviour ("always re-orient") and the first two fail: a still camera
 * reported movement, forever.
 */
class BillboardNodeFrameActivityTest {

    private val somewhere = Position(1f, 2f, 3f)

    @Test
    fun `a camera that has not moved since the orientation was applied is idle`() {
        assertFalse(
            "a still camera must not ask for a frame — this is the 57 fps idle leak",
            cameraMovedSinceApplied(camPos = somewhere, applied = somewhere)
        )
    }

    @Test
    fun `sub-millimetre jitter does not count as movement`() {
        // A camera position published through a Compose state can wobble in the last float bits
        // without anything being visible. Under the epsilon it must read as idle, or the scene
        // never parks.
        val jittered = Position(
            somewhere.x + 1e-5f,
            somewhere.y - 1e-5f,
            somewhere.z + 1e-5f
        )
        assertFalse(
            "jitter below 1 mm is not movement",
            cameraMovedSinceApplied(camPos = jittered, applied = somewhere)
        )
    }

    @Test
    fun `a camera that moved visibly is active`() {
        assertTrue(
            "the node must keep asking for frames until it has turned to face the new position",
            cameraMovedSinceApplied(camPos = Position(1f, 2f, 3.5f), applied = somewhere)
        )
    }

    @Test
    fun `a node that has never been oriented is active`() {
        assertTrue(
            "the first orientation must happen even if the camera never moves again",
            cameraMovedSinceApplied(camPos = somewhere, applied = null)
        )
    }

    @Test
    fun `no camera provider means the node never needs a frame of its own`() {
        assertFalse(
            "a BillboardNode built without a cameraPositionProvider does not re-orient at all",
            cameraMovedSinceApplied(camPos = null, applied = null)
        )
        assertFalse(
            cameraMovedSinceApplied(camPos = null, applied = somewhere)
        )
    }

    @Test
    fun `applying the reported position is what ends the activity`() {
        // The convergence argument the `isFrameActive` term rests on: whatever the camera did, one
        // application of the position it reports makes the node idle again. Without this the pull
        // term would be a second way of never parking.
        var applied: Position? = null
        val reported = Position(4f, 5f, 6f)

        assertTrue(cameraMovedSinceApplied(reported, applied))
        applied = reported
        assertFalse(
            "one application settles it — the term cannot latch on",
            cameraMovedSinceApplied(reported, applied)
        )
    }
}
