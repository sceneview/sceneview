package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DimensionMarksGeometryTest {
    private val g = DimensionMarksGeometry
    private val halfTag = g.TAG_WIDTH / 2f

    @Test
    fun `with the height line on the corner the depth tag keeps its place`() {
        val right = 0.45f + g.OFFSET
        // AR: heightOffset = 0, the height line stands on the depth line.
        assertEquals(right + g.DEPTH_TAG_GAP + halfTag, g.depthTagX(right, right, halfTag), 1e-6f)
    }

    @Test
    fun `a height line moved out never crosses the depth tag`() {
        val right = 0.45f + g.OFFSET
        for (offset in listOf(0.05f, 0.12f, 0.2f, 0.35f)) {
            val heightX = right + offset
            val centre = g.depthTagX(right, heightX, halfTag)
            // The tag spans [centre - halfTag, centre + halfTag]: the post stays left of it.
            assertTrue("offset $offset", centre - halfTag >= heightX + g.DEPTH_TAG_GAP - 1e-6f)
        }
    }

    @Test
    fun `the depth line never crosses the depth tag`() {
        val right = 0.3f
        assertTrue(g.depthTagX(right, right - 0.1f, halfTag) - halfTag >= right)
    }
}
