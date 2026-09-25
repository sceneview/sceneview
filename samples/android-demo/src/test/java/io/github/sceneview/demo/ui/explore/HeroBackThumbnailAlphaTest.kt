package io.github.sceneview.demo.ui.explore

import org.junit.Assert.assertEquals
import org.junit.Test

class HeroBackThumbnailAlphaTest {

    @Test
    fun `thumbnail stays hidden through the first stretch of the gesture`() {
        assertEquals(0f, heroBackThumbnailAlpha(0f), 0f)
        assertEquals(0f, heroBackThumbnailAlpha(0.3f), 0f)
        assertEquals(0f, heroBackThumbnailAlpha(0.6f), 0f)
    }

    @Test
    fun `thumbnail fades in linearly to fully opaque at the release`() {
        assertEquals(0.5f, heroBackThumbnailAlpha(0.8f), 1e-5f)
        assertEquals(1f, heroBackThumbnailAlpha(1f), 1e-5f)
    }

    @Test
    fun `spring overshoot past either end is clamped`() {
        assertEquals(0f, heroBackThumbnailAlpha(-0.1f), 0f)
        assertEquals(1f, heroBackThumbnailAlpha(1.1f), 0f)
    }
}
