package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules behind Cosmos's 2D layer (#4601). */
class CosmosChromeTest {

    @Test
    fun `the scene switch offers the four scenes, once each`() {
        assertEquals(
            listOf(CosmosScene.Galaxy, CosmosScene.Star, CosmosScene.Burst, CosmosScene.Flow),
            CosmosChrome.switchOrder,
        )
    }

    @Test
    fun `autoplay shows as playing only while the voyage holds the camera`() {
        assertTrue(CosmosChrome.autoplayPlaying(voyageOn = true, voyageCaption = "Into the galaxy"))
        // On, but the user has the camera: it offers to play again at once.
        assertFalse(CosmosChrome.autoplayPlaying(voyageOn = true, voyageCaption = null))
        assertFalse(CosmosChrome.autoplayPlaying(voyageOn = false, voyageCaption = null))
        // Stopped, while the last shot's caption has not been cleared yet.
        assertFalse(CosmosChrome.autoplayPlaying(voyageOn = false, voyageCaption = "Into the galaxy"))
    }

    @Test
    fun `picking the scene on screen changes nothing`() {
        CosmosScene.entries.forEach { scene ->
            assertFalse(CosmosChrome.pickChangesScene(scene, scene))
            CosmosScene.entries.filter { it != scene }.forEach { other ->
                assertTrue(CosmosChrome.pickChangesScene(other, scene))
            }
        }
    }

    @Test
    fun `the status line follows the voyage, then the scene`() {
        val legend = "Spacetime: every mass bends the fabric"
        assertEquals(
            "A shot",
            CosmosChrome.status(CosmosScene.Star, "A shot", spacetime = true, CosmosFocus.Planet, legend),
        )
        assertEquals(
            legend,
            CosmosChrome.status(CosmosScene.Star, null, spacetime = true, CosmosFocus.System, legend),
        )
        assertEquals(
            CosmosFocus.Planet.caption,
            CosmosChrome.status(CosmosScene.Star, null, spacetime = false, CosmosFocus.Planet, legend),
        )
        // Spacetime belongs to the Star scene: elsewhere the scene speaks for itself.
        assertEquals(
            CosmosScene.Flow.caption,
            CosmosChrome.status(CosmosScene.Flow, null, spacetime = true, CosmosFocus.System, legend),
        )
    }

    @Test
    fun `every scene has its own switch tag`() {
        val tags = CosmosScene.entries.map(CosmosChrome::switchTestTag)
        assertEquals(
            listOf("cosmos_scene_galaxy", "cosmos_scene_star", "cosmos_scene_burst", "cosmos_scene_flow"),
            tags,
        )
    }
}
