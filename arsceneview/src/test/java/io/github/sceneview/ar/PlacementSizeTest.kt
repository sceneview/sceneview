package io.github.sceneview.ar

import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlacementSize] and the base / placed dimensions [AutoPlacementModel] publishes on
 * [AutoPlacementState]. glTF is authored in metres, so a 1 m cube placed at
 * [PlacementSize.RealWorld] must report 1 m — no hidden rescale.
 */
class PlacementSizeTest {

    private fun assertSize(expected: Size, actual: Size?, delta: Float = 1e-5f) {
        requireNotNull(actual)
        assertEquals("width", expected.x, actual.x, delta)
        assertEquals("height", expected.y, actual.y, delta)
        assertEquals("depth", expected.z, actual.z, delta)
    }

    @Test fun `real world keeps authored units`() {
        assertNull(PlacementSize.RealWorld.scaleToUnits)
    }

    @Test fun `fit into maps to the longest-axis target`() {
        assertEquals(0.3f, PlacementSize.FitInto(0.3f).scaleToUnits!!, 0f)
    }

    @Test fun `fit into rejects unusable targets`() {
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { PlacementSize.FitInto(bad) }
        }
    }

    @Test fun `one metre cube at real world size is one metre`() {
        val base = placementBaseSize(
            halfExtent = Size(0.5f, 0.5f, 0.5f), scale = Scale(1f), assetRotation = Rotation(0f),
        )
        assertSize(Size(1f, 1f, 1f), base)
    }

    @Test fun `fit into scales the longest axis to the target`() {
        // A 140-unit fox-sized asset fitted into 0.3 m: uniform scale 0.3 / 140.
        val scale = Scale(0.3f / 140f)
        val base = placementBaseSize(Size(70f, 35f, 20f), scale, Rotation(0f))
        assertSize(Size(0.3f, 0.15f, 0.3f * 40f / 140f), base)
    }

    @Test fun `asset rotation swaps width and depth`() {
        // 2 x 1 x 0.5 m box turned a quarter turn about +Y: footprint 0.5 wide, 2 deep.
        val base = placementBaseSize(Size(1f, 0.5f, 0.25f), Scale(1f), Rotation(y = 90f))
        assertSize(Size(0.5f, 1f, 2f), base, delta = 1e-4f)
    }

    @Test fun `asset rotation about X turns depth into height`() {
        // A Z-up authoring corrected with -90 deg about X stands its 2 m Z axis upright.
        val base = placementBaseSize(Size(0.5f, 0.25f, 1f), Scale(1f), Rotation(x = -90f))
        assertSize(Size(1f, 2f, 0.5f), base, delta = 1e-4f)
    }

    @Test fun `fit into is fitted before the asset rotation`() {
        // FitInto(1f) on a 1 m cube, then turned 45 deg about Y: the axis-aligned box the
        // state reports is the turned cube's, about 1.41 wide and deep, not 1.
        val base = placementBaseSize(Size(0.5f, 0.5f, 0.5f), Scale(1f), Rotation(y = 45f))
        val diagonal = kotlin.math.sqrt(2f)
        assertSize(Size(diagonal, 1f, diagonal), base, delta = 1e-4f)
    }

    @Test fun `an empty asset has no base size`() {
        assertNull(placementBaseSize(Size(0f, 0f, 0f), Scale(1f), Rotation(0f)))
        assertNull(placementBaseSize(Size(Float.NaN, 1f, 1f), Scale(1f), Rotation(0f)))
        // A flat card still has a size.
        assertSize(Size(1f, 0f, 1f), placementBaseSize(Size(0.5f, 0f, 0.5f), Scale(1f), Rotation(0f)))
        val state = AutoPlacementState()
        state.publishBaseSize(owner = this, size = null, realWorld = true)
        assertNull(state.baseSize)
        assertNull(state.placedSize)
        assertFalse(state.isAtRealWorldSize)
    }

    @Test fun `placed size follows the user scale`() {
        val state = AutoPlacementState()
        assertNull(state.placedSize)
        state.publishBaseSize(owner = this, size = Size(0.5f, 1f, 0.5f), realWorld = true)
        state.scaleFactor = 1.8f
        assertSize(Size(0.9f, 1.8f, 0.9f), state.placedSize)
        assertSize(Size(0.5f, 1f, 0.5f), state.baseSize)
    }

    @Test fun `real world flag needs real world base and 100 percent`() {
        val state = AutoPlacementState()
        assertFalse(state.isAtRealWorldSize)
        state.publishBaseSize(owner = this, size = Size(1f), realWorld = true)
        assertTrue(state.isAtRealWorldSize)
        state.scaleFactor = 1.2f
        assertFalse(state.isAtRealWorldSize)
        state.scaleFactor = 1f
        state.publishBaseSize(owner = this, size = Size(0.3f), realWorld = false)
        assertFalse(state.isAtRealWorldSize)
    }

    @Test fun `only the publishing model clears the base size`() {
        val state = AutoPlacementState()
        val first = Any()
        val second = Any()
        state.publishBaseSize(first, Size(1f), realWorld = true)
        state.publishBaseSize(second, Size(2f), realWorld = true)
        state.clearBaseSize(first)
        assertSize(Size(2f), state.baseSize)
        state.clearBaseSize(second)
        assertNull(state.baseSize)
        assertFalse(state.isAtRealWorldSize)
    }
}
