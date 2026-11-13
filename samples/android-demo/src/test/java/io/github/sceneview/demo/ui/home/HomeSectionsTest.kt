package io.github.sceneview.demo.ui.home

import io.github.sceneview.demo.ALL_DEMOS
import io.github.sceneview.demo.isArDemo
import org.junit.Assert.*
import org.junit.Test

class HomeSectionsTest {
    @Test fun `every registered demo is reachable exactly once including device specific and fallback demos`() {
        val ids = HomeSection.entries.flatMap { it.demoIds }
        assertEquals(ALL_DEMOS.map { it.id }.toSet(), ids.toSet())
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ids, HomeSection.entries.flatMap { it.demos(ALL_DEMOS) }.map { it.id })
    }

    @Test fun `audit sections and leading demos stay in priority order`() {
        assertEquals(listOf("Models", "AR", "Scan", "Materials", "Play", "Tools"), HomeSection.entries.map { it.name })
        assertEquals(listOf("model-viewer", "ar-placement", "ar-rerun", "matter", "cosmos", "ar-surfaces"),
            HomeSection.entries.map { it.demoIds.first() })
    }

    @Test fun `moving demos between home sections preserves AR permission classification`() {
        for (id in listOf("ar-pool", "ar-geospatial-anchors", "ar-scene-semantics", "ar-cloud-anchor")) {
            assertTrue(id, ALL_DEMOS.single { it.id == id }.isArDemo)
        }
    }
}
