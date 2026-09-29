package io.github.sceneview.demo.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Model Viewer's opening-lighting rule, as iOS `ViewerLightingTests` pins it. */
class ViewerLightingTest {
    private val garden = ViewerEnvironment("environments/chinese_garden_2k.hdr", "Chinese Garden")
    private val studio = ViewerEnvironment("environments/studio_warm_2k.hdr", "Studio")
    private val sunset = ViewerEnvironment("environments/sky_on_fire_2k.hdr", "Sunset")

    private fun ViewerLighting.select(museum: Boolean) = select(museum, garden, studio)
    private fun ViewerLighting.reset(museum: Boolean) = reset(museum, garden, studio)

    @Test
    fun museumModelOpensUnderStudioFromTheDefault() {
        val lit = ViewerLighting(garden).select(museum = true)
        assertEquals(studio, lit.environment)
        assertTrue(lit.museumApplied)
    }

    @Test
    fun leavingTheShelfGivesTheGardenBackOnlyIfStudioWasOurs() {
        assertEquals(garden, ViewerLighting(garden).select(museum = true).select(museum = false).environment)
        val picked = ViewerLighting(garden).pick(studio).select(museum = true).select(museum = false)
        assertEquals(studio, picked.environment)
    }

    @Test
    fun aPickedLightingIsNeverOverridden() {
        val lit = ViewerLighting(garden).pick(sunset).select(museum = true)
        assertEquals(sunset, lit.environment)
        assertFalse(lit.museumApplied)
    }

    @Test
    fun pickingTheDefaultStillLetsAMuseumScanOpenUnderStudio() {
        // The one pick that is overridden, as on iOS: an explicit garden looks like the start.
        val lit = ViewerLighting(garden).pick(garden).select(museum = true)
        assertEquals(studio, lit.environment)
        assertTrue(lit.museumApplied)
    }

    @Test
    fun resetWithAMuseumModelOnStageReturnsToStudio() {
        val lit = ViewerLighting(garden).select(museum = true).pick(sunset).reset(museum = true)
        assertEquals(studio, lit.environment)
        // Studio came back from the reset, so leaving the shelf gives the garden back.
        assertTrue(lit.museumApplied)
        assertEquals(garden, lit.select(museum = false).environment)
    }

    @Test
    fun resetWithABundledModelOnStageReturnsToTheGarden() {
        val lit = ViewerLighting(garden).pick(sunset).reset(museum = false)
        assertEquals(garden, lit.environment)
        assertFalse(lit.museumApplied)
    }
}
