package io.github.sceneview.demo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationTest {

    @Test
    fun `a line ending in an ellipsis is narrated without it`() {
        assertEquals("Searching Sketchfab", narrationBase("Searching Sketchfab…"))
        assertEquals("Loading", narrationBase("Loading..."))
    }

    @Test
    fun `a settled sentence is not animated`() {
        assertNull(narrationBase("Ready — tap Drop here"))
        assertNull(narrationBase("…"))
    }

    @Test
    fun `the lit dot travels left to right and never vanishes`() {
        assertEquals(1f, narrationDotAlpha(0, 0.5f), 1e-4f)
        assertEquals(1f, narrationDotAlpha(1, 1.5f), 1e-4f)
        assertEquals(1f, narrationDotAlpha(2, 2.5f), 1e-4f)
        for (step in 0..30) {
            val phase = step / 10f
            for (dot in 0..2) assertTrue(narrationDotAlpha(dot, phase) >= 0.25f)
        }
    }
}
