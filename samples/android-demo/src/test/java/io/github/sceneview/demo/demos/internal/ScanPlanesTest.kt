package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanPlanesTest {
    @Test
    fun `a plane ARCore drops while it tracks others leaves the scan`() {
        assertEquals(setOf("floor"), ScanPlanes.removed(live = setOf("floor", "wall"), seen = setOf("wall")))
    }

    @Test
    fun `a plane merged into another leaves the scan`() {
        val removed = ScanPlanes.removed(live = setOf("a", "b"), seen = setOf("b"), merged = setOf("a"))
        assertEquals(setOf("a"), removed)
    }

    @Test
    fun `a tracking reset that drops every plane keeps them all`() {
        val live = (1..7).map { "plane$it" }.toSet()
        assertEquals(emptySet<String>(), ScanPlanes.removed(live = live, seen = emptySet()))
    }

    @Test
    fun `a reset that already found a new plane still keeps the old ones`() {
        // The Pixel 9 scan: 7 surfaces, then 1 new one half a second later.
        val live = (1..7).map { "plane$it" }.toSet()
        assertEquals(emptySet<String>(), ScanPlanes.removed(live = live, seen = setOf("new")))
    }

    @Test
    fun `a plane merged at a reset still leaves`() {
        val removed = ScanPlanes.removed(live = setOf("a", "b"), seen = setOf("c"), merged = setOf("a"))
        assertEquals(setOf("a"), removed)
    }

    @Test
    fun `nothing gone, nothing removed`() {
        assertEquals(emptySet<String>(), ScanPlanes.removed(live = setOf("a"), seen = setOf("a", "b")))
        assertEquals(emptySet<String>(), ScanPlanes.removed(live = emptySet<String>(), seen = emptySet()))
    }
}
