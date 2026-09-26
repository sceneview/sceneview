package io.github.sceneview.demo.common.placement

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the extent the "View in 3D" preview frames a loaded model by (#3884): the size
 * `ModelNode(scaleToUnits = …)` draws it at, not the asset's authored size.
 */
class PlacementPreviewFramingTest {

    @Test
    fun `the longest side is fitted to the requested size`() {
        // A 2 x 1 x 0.5 box fitted into 0.3 m.
        val extent = scaledToUnitsExtent(floatArrayOf(1f, 0.5f, 0.25f), 0.3f)
        assertEquals(0.3f, extent.x, 1e-6f)
        assertEquals(0.15f, extent.y, 1e-6f)
        assertEquals(0.075f, extent.z, 1e-6f)
    }

    @Test
    fun `the longest side can be any axis`() {
        val extent = scaledToUnitsExtent(floatArrayOf(0.1f, 0.4f, 0.2f), 0.3f)
        assertEquals(0.3f, extent.y, 1e-6f)
        assertEquals(0.075f, extent.x, 1e-6f)
        assertEquals(0.15f, extent.z, 1e-6f)
    }

    @Test
    fun `an empty box stays empty instead of dividing by zero`() {
        val extent = scaledToUnitsExtent(floatArrayOf(0f, 0f, 0f), 0.3f)
        assertEquals(0f, extent.x, 0f)
        assertEquals(0f, extent.y, 0f)
        assertEquals(0f, extent.z, 0f)
    }
}
