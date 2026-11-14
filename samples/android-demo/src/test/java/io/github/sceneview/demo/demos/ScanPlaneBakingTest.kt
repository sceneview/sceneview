package io.github.sceneview.demo.demos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPlaneBakingTest {
    @Test
    fun `only a live room with saved dense geometry can omit painted planes`() {
        assertFalse(shouldBakeScanPlanes(liveRoom = true, hasDenseGeometry = true))
        // No raw depth support, or supported depth that produced no saved points.
        assertTrue(shouldBakeScanPlanes(liveRoom = true, hasDenseGeometry = false))
        assertTrue(shouldBakeScanPlanes(liveRoom = false, hasDenseGeometry = true))
        assertTrue(shouldBakeScanPlanes(liveRoom = false, hasDenseGeometry = false))
    }
}
