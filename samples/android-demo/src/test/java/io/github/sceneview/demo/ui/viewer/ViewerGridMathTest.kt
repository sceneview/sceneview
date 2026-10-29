package io.github.sceneview.demo.ui.viewer

import io.github.sceneview.demo.theme.SceneViewTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerGridMathTest {

    @Test
    fun `cells follow the zoom in powers of ten`() {
        // An earbud framed at 0.15 m, a chair at 1.4 m, a car at 8 m.
        assertEquals(0.01f, ViewerGridMath.minorCell(0.15f), 1e-6f)
        assertEquals(0.1f, ViewerGridMath.minorCell(1.4f), 1e-6f)
        assertEquals(1f, ViewerGridMath.majorCell(1.4f), 1e-5f)
        assertEquals(1f, ViewerGridMath.minorCell(8f), 1e-5f)
        assertEquals(10f, ViewerGridMath.majorCell(8f), 1e-4f)
    }

    @Test
    fun `the finest level fades out as the eye moves away`() {
        val near = ViewerGridMath.finestCell(3.1f).second
        val far = ViewerGridMath.finestCell(25f).second
        assertTrue(near < far)
        assertTrue(far < 1f)
        // A degenerate distance still yields a finite cell.
        assertTrue(ViewerGridMath.finestCell(Float.NaN).first.isFinite())
    }

    @Test
    fun `the grid fades from full to nothing, with no edge`() {
        assertEquals(1f, ViewerGridMath.fade(0f, 2f), 0f)
        assertEquals(1f, ViewerGridMath.fade(ViewerGridMath.FADE_START * 2f, 2f), 0f)
        assertEquals(0f, ViewerGridMath.fade(ViewerGridMath.FADE_END * 2f, 2f), 0f)
        val mid = ViewerGridMath.fade((ViewerGridMath.FADE_START + ViewerGridMath.FADE_END), 2f)
        assertTrue(mid > 0f && mid < 1f)
    }

    @Test
    fun `line colours keep the token alpha and stay displayable`() {
        val token = SceneViewTokens.ViewerGrid.LightMajor
        val color = ViewerGridMath.lineColor(token)
        assertEquals(token.alpha, color[3], 1e-6f)
        // A mid grey-blue: linear, below one, never blown to white by an exposure division.
        for (i in 0..2) assertTrue("channel $i: ${color[i]}", color[i] > 0f && color[i] < 0.5f)
    }

    @Test
    fun `each theme has its own pair of tokens, majors stronger than minors`() {
        val (lightMinor, lightMajor) = ViewerGridMath.tokens(isDark = false)
        val (darkMinor, darkMajor) = ViewerGridMath.tokens(isDark = true)
        assertNotEquals(lightMinor, darkMinor)
        assertTrue(lightMajor.alpha > lightMinor.alpha)
        assertTrue(darkMajor.alpha > darkMinor.alpha)
    }
}
