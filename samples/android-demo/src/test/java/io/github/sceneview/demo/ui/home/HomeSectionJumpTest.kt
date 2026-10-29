package io.github.sceneview.demo.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM tests for [homeSectionStarts] — where each section chip scrolls to. */
class HomeSectionJumpTest {

    @Test
    fun `with section headers a chip lands on its header`() {
        // head: 3 items, then [hdr A, a1, a2, hdr B, b1, hdr C, c1, c2, c3]
        val starts = homeSectionStarts(
            headItemCount = 3,
            rowCategories = listOf("A", "A", "B", "C", "C", "C"),
            showSections = true,
        )
        assertEquals(mapOf("A" to 3, "B" to 6, "C" to 8), starts)
    }

    @Test
    fun `without headers a chip lands on the section's first row`() {
        val starts = homeSectionStarts(
            headItemCount = 2,
            rowCategories = listOf("A", "A", "B"),
            showSections = false,
        )
        assertEquals(mapOf("A" to 2, "B" to 4), starts)
    }

    @Test
    fun `sections keep catalogue order and a missing category has no start`() {
        val starts = homeSectionStarts(
            headItemCount = 0,
            rowCategories = listOf("C", "A"),
            showSections = true,
        )
        assertEquals(listOf("C", "A"), starts.keys.toList())
        assertNull(starts["B"])
    }

    @Test
    fun `an empty catalogue has no section`() {
        assertEquals(emptyMap<String, Int>(), homeSectionStarts(5, emptyList(), showSections = true))
    }
}
