package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bookkeeping behind the Lighting demo's environments: what is on screen, what loads next,
 * and what may be released — with the Sun clock's three skies as the case that shaped it.
 */
class ResidentResourcesTest {

    private val released = mutableListOf<String>()
    private val resident = ResidentResources<Sky> { released += it.file }

    private class Sky(val file: String)

    private fun load(file: String): Sky = Sky(file).also { resident.onLoaded(file, it) }

    @Test
    fun `nothing is presented until the first wanted file is loaded`() {
        resident.request("studio")

        assertNull(resident.presented)
        assertEquals("studio", resident.nextToLoad())

        val studio = load("studio")
        assertSame(studio, resident.presented?.resource)
        assertEquals("studio", resident.presented?.file)
        assertNull(resident.nextToLoad())
    }

    @Test
    fun `the presented pair stays while the next wanted file loads`() {
        resident.request("studio")
        val studio = load("studio")

        resident.request("rooftop")

        assertSame(studio, resident.presented?.resource)
        assertEquals("studio", resident.presented?.file)
        assertEquals("rooftop", resident.nextToLoad())
        assertTrue(released.isEmpty())
    }

    @Test
    fun `the wanted file loads first, then the warm ones in order`() {
        val skies = listOf("night", "golden", "day")
        resident.request("day", warm = skies)

        assertEquals("day", resident.nextToLoad())
        load("day")
        assertEquals("night", resident.nextToLoad())
        load("night")
        assertEquals("golden", resident.nextToLoad())
        load("golden")
        assertNull(resident.nextToLoad())
    }

    @Test
    fun `a warm file is presented with no load`() {
        val skies = listOf("night", "golden", "day")
        resident.request("day", warm = skies)
        skies.forEach(::load)

        resident.request("golden", warm = skies)

        assertEquals("golden", resident.presented?.file)
        assertNull(resident.nextToLoad())
        resident.releaseUnused()
        assertTrue(released.isEmpty())
    }

    @Test
    fun `a slice shorter than its load still gets its sky kept for the next day`() {
        val skies = listOf("night", "golden", "day")
        resident.request("day", warm = skies)
        load("day")
        // The clock reaches night while nothing but day is loaded, and leaves it for golden
        // before the night sky lands.
        resident.request("night", warm = skies)
        assertEquals("night", resident.nextToLoad())
        resident.request("golden", warm = skies)
        load("night")

        assertEquals("day", resident.presented?.file)
        assertEquals("golden", resident.nextToLoad())
        assertTrue(released.isEmpty())

        load("golden")
        assertEquals("golden", resident.presented?.file)
        resident.request("night", warm = skies)
        assertEquals("night", resident.presented?.file)
    }

    @Test
    fun `a result nobody wants any more is released on arrival and never presented`() {
        resident.request("studio")
        val studio = load("studio")
        resident.request("rooftop")
        resident.request("meadow")

        load("rooftop")

        assertEquals(listOf("rooftop"), released)
        assertSame(studio, resident.presented?.resource)
        assertEquals("meadow", resident.nextToLoad())
    }

    @Test
    fun `releaseUnused spares what is on screen while its replacement loads`() {
        val skies = listOf("night", "golden", "day")
        resident.request("day", warm = skies)
        skies.forEach(::load)

        // The clock stops: the rig moves to the studio, whose HDR is not loaded yet.
        resident.request("studio")
        resident.releaseUnused()

        assertEquals("day", resident.presented?.file)
        assertEquals(setOf("night", "golden"), released.toSet())

        load("studio")
        assertEquals("studio", resident.presented?.file)
        resident.releaseUnused()
        assertEquals(setOf("night", "golden", "day"), released.toSet())
        assertEquals(3, released.size)
    }

    @Test
    fun `a failed load keeps the presented pair and is not retried`() {
        resident.request("studio")
        val studio = load("studio")
        resident.request("missing")

        resident.onLoaded("missing", null)

        assertSame(studio, resident.presented?.resource)
        assertNull(resident.nextToLoad())
        resident.releaseUnused()
        assertTrue(released.isEmpty())
    }

    @Test
    fun `clear releases every resource exactly once`() {
        val skies = listOf("night", "golden", "day")
        resident.request("day", warm = skies)
        skies.forEach(::load)

        resident.clear()
        resident.clear()

        assertNull(resident.presented)
        assertEquals(setOf("night", "golden", "day"), released.toSet())
        assertEquals(3, released.size)
    }
}
