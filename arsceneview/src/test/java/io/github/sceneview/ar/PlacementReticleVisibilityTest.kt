package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The placement reticle is a *placing* cue: by default it hides once a model is placed, so the
 * model is seen over the bare camera feed, and returns when the app asks for another placement.
 * Before this, [PlacementScene] composed it whenever the camera tracked — after placement it kept
 * sliding over the floor, on top of the model, for the whole session.
 */
class PlacementReticleVisibilityTest {

    @Test
    fun `auto shows the reticle only while placing`() {
        assertTrue(reticleVisibleFor(ReticleVisibility.AUTO, isPlacing = true))
        assertFalse(reticleVisibleFor(ReticleVisibility.AUTO, isPlacing = false))
    }

    @Test
    fun `always keeps the pre-change cursor and hidden never draws it`() {
        for (placing in listOf(true, false)) {
            assertTrue(reticleVisibleFor(ReticleVisibility.ALWAYS, placing))
            assertFalse(reticleVisibleFor(ReticleVisibility.HIDDEN, placing))
        }
    }

    @Test
    fun `controller places then asks for another placement`() {
        val controller = PlacementController()
        assertTrue("a fresh scene is looking for a surface", controller.isPlacing)

        controller.markPlaced()
        assertFalse("a placed model ends placing, so the reticle fades", controller.isPlacing)

        controller.requestPlacement()
        assertTrue("Place another brings the reticle back", controller.isPlacing)

        controller.markPlaced()
        controller.clear()
        assertTrue("clearing the scene re-enters placing", controller.isPlacing)
    }

    @Test
    fun `target alpha is the phase ramp while visible and zero once hidden`() {
        for (phase in ReticlePhase.entries) {
            assertEquals(reticleAlphaFor(phase), reticleTargetAlpha(phase, visible = true), 0f)
            assertEquals(0f, reticleTargetAlpha(phase, visible = false), 0f)
        }
    }

    @Test
    fun `hidden reticle drops its stale hit phase before returning`() {
        assertEquals(
            ReticlePhase.SEARCHING,
            reticlePhaseForVisibility(ReticlePhase.LOCKED, visible = false),
        )
        assertEquals(
            ReticlePhase.READY,
            reticlePhaseForVisibility(ReticlePhase.READY, visible = true),
        )
    }

    @Test
    fun `nodes stay through the fade then leave the scene`() {
        assertTrue("wanted, even before the fade-in starts", shouldComposeReticle(true, 0f))
        assertTrue("still fading out", shouldComposeReticle(false, 0.01f))
        assertFalse("fully faded: no invisible geometry left", shouldComposeReticle(false, 0f))
    }

    @Test
    fun `plane grid follows placing and returns on requestPlacement`() {
        assertTrue(shouldRenderPlaneGrid(true, true, isPlacing = true))
        assertFalse(shouldRenderPlaneGrid(true, true, isPlacing = false))
        // fadePlaneOnFirstPlacement = false keeps the grid for the whole session, as before.
        assertTrue(shouldRenderPlaneGrid(true, false, isPlacing = false))
        assertFalse(shouldRenderPlaneGrid(false, true, isPlacing = true))
    }

    @Test
    fun `isPlacing and the placement count agree when requestPlacement is never called`() {
        for (planeRenderer in listOf(false, true)) {
            for (fade in listOf(false, true)) {
                for (count in 0..3) {
                    assertEquals(
                        shouldRenderPlaneGrid(planeRenderer, fade, count),
                        shouldRenderPlaneGrid(planeRenderer, fade, isPlacing = count == 0),
                    )
                }
            }
        }
    }

    @Test
    fun `one shadow receiver per plane whatever the placing state`() {
        for (planeRenderer in listOf(false, true)) {
            for (fade in listOf(false, true)) {
                for (shadows in listOf(false, true)) {
                    for (placing in listOf(false, true)) {
                        assertFalse(
                            shouldRenderPlaneGrid(planeRenderer, fade, placing) &&
                                shouldCatchGroundShadows(shadows, planeRenderer, fade, placing),
                        )
                    }
                }
            }
        }
    }
}
