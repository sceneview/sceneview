package io.github.sceneview.ar.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the grouped scene-understanding flags (#1767).
 *
 * The data class itself is trivial — these tests pin:
 *  1. Default values match the RealityKit parity contract spelled out on #1767
 *    (everything on except [SceneUnderstanding.physics]).
 *  2. Named-constant variants ([SceneUnderstanding.Full], [Minimal], [None])
 *    have the expected flag matrix.
 *  3. `copy()` and `equals` behave as a data class is expected to.
 *
 * The on-device wiring (the new `sceneUnderstanding` parameter on `ARSceneView`
 * fanning the four flags out to [io.github.sceneview.ar.camera.ARCameraStream],
 * [io.github.sceneview.ar.light.LightEstimator], and
 * [io.github.sceneview.ar.scene.PlaneRenderer]) requires a Filament engine and
 * an ARCore session — not unit-testable in pure JVM. Coverage there comes from
 * the AR demo + manual QA per #1767 acceptance.
 */
class SceneUnderstandingTest {

    @Test
    fun `default constructor is Full-equivalent`() {
        val default = SceneUnderstanding()
        assertTrue("default occlusion on", default.occlusion)
        assertTrue("default lighting on", default.lighting)
        assertFalse("default physics off (reserved, unwired)", default.physics)
        assertTrue("default planeVisualization on", default.planeVisualization)

        assertEquals(SceneUnderstanding.Full, default)
    }

    @Test
    fun `Minimal disables occlusion and planeVisualization`() {
        val minimal = SceneUnderstanding.Minimal
        assertFalse("Minimal disables occlusion (depth sensor not required)", minimal.occlusion)
        assertTrue("Minimal keeps lighting (low CPU cost)", minimal.lighting)
        assertFalse("Minimal keeps physics off", minimal.physics)
        assertFalse("Minimal disables the plane grid overlay", minimal.planeVisualization)
    }

    @Test
    fun `None disables every flag`() {
        val none = SceneUnderstanding.None
        assertFalse(none.occlusion)
        assertFalse(none.lighting)
        assertFalse(none.physics)
        assertFalse(none.planeVisualization)
    }

    @Test
    fun `copy preserves unchanged fields`() {
        val full = SceneUnderstanding.Full
        val noOcclusion = full.copy(occlusion = false)

        assertFalse(noOcclusion.occlusion)
        assertEquals(full.lighting, noOcclusion.lighting)
        assertEquals(full.physics, noOcclusion.physics)
        assertEquals(full.planeVisualization, noOcclusion.planeVisualization)

        assertNotEquals(full, noOcclusion)
    }

    @Test
    fun `equality is component-wise`() {
        val a = SceneUnderstanding(
            occlusion = true,
            lighting = false,
            physics = true,
            planeVisualization = false
        )
        val b = SceneUnderstanding(
            occlusion = true,
            lighting = false,
            physics = true,
            planeVisualization = false
        )
        val c = b.copy(planeVisualization = true)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `named constants are referentially singletons`() {
        // The companion object exposes the three named constants as `val`s, so
        // referencing them from app code never re-allocates. Pin this contract
        // — a future change to `val` -> `get()` would silently churn allocations.
        assertEquals(SceneUnderstanding.Full, SceneUnderstanding.Full)
        // identity check via referential equality
        assertTrue(
            "SceneUnderstanding.Full is a singleton constant",
            SceneUnderstanding.Full === SceneUnderstanding.Full
        )
        assertTrue(
            "SceneUnderstanding.Minimal is a singleton constant",
            SceneUnderstanding.Minimal === SceneUnderstanding.Minimal
        )
        assertTrue(
            "SceneUnderstanding.None is a singleton constant",
            SceneUnderstanding.None === SceneUnderstanding.None
        )
    }
}
