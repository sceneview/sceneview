package io.github.sceneview.ar

import io.github.sceneview.math.Position
import io.github.sceneview.node.ContactShadowContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementContactShadowTest {

    private val floor = ContactShadowContext.Floor

    /** The eight corners of a box standing on y = 0, centred on (cx, cz). */
    private fun box(cx: Float, cz: Float, halfX: Float, height: Float, halfZ: Float) = buildList {
        for (x in listOf(-1f, 1f)) for (y in listOf(0f, height)) for (z in listOf(-1f, 1f)) {
            add(Position(cx + x * halfX, y, cz + z * halfZ))
        }
    }

    /** Half-width of the fully dark core on X, metres. */
    private fun coreX(q: PlacementContactShadow.Quad) = q.sizeX * floor.radiusU

    /** Half-width where the pool has faded out on X, metres. */
    private fun fadeEndX(q: PlacementContactShadow.Quad) = q.sizeX * floor.radiusU * (1f + floor.softness)

    @Test fun `the shadow is on by default`() {
        assertTrue(AutoPlacementState().showsContactShadow)
    }

    @Test fun `the pool is centred under the footprint`() {
        val q = PlacementContactShadow.quad(box(0.2f, -0.1f, 0.3f, 0.8f, 0.4f))!!
        assertEquals(0.2f, q.centerX, 1e-6f)
        assertEquals(-0.1f, q.centerZ, 1e-6f)
    }

    @Test fun `the dark core stays inside the outline and the fade ends just past it`() {
        // A 0.6 m × 0.8 m armchair.
        val q = PlacementContactShadow.quad(box(0f, 0f, 0.3f, 0.8f, 0.4f))!!
        assertEquals(0.3f * PlacementContactShadow.CORE_FRACTION, coreX(q), 1e-5f)
        val beyondEdge = fadeEndX(q) - 0.3f
        assertTrue("fade ends ${beyondEdge}m past the edge", beyondEdge > 0.03f && beyondEdge < 0.15f)
        // The pool fades out before the quad's border, so no rectangular seam is drawn.
        assertTrue(fadeEndX(q) < q.sizeX / 2f)
    }

    @Test fun `each axis follows its own extent`() {
        val q = PlacementContactShadow.quad(box(0f, 0f, 0.9f, 0.8f, 0.4f))!!
        assertEquals(0.9f / 0.4f, q.sizeX / q.sizeZ, 1e-4f)
    }

    @Test fun `a pencil-thin object keeps a readable pool`() {
        val q = PlacementContactShadow.quad(box(0f, 0f, 0.2f, 0.01f, 0.002f))!!
        assertEquals(PlacementContactShadow.MIN_ASPECT, q.sizeZ / q.sizeX, 1e-4f)
    }

    @Test fun `a tiny object keeps a few millimetres of pool`() {
        val q = PlacementContactShadow.quad(box(0f, 0f, 0.001f, 0.001f, 0.001f))!!
        assertEquals(PlacementContactShadow.MIN_HALF_EXTENT * PlacementContactShadow.CORE_FRACTION / floor.radiusU, q.sizeX, 1e-6f)
    }

    @Test fun `no corners or non-finite bounds give no shadow`() {
        assertNull(PlacementContactShadow.quad(emptyList()))
        assertNull(PlacementContactShadow.quad(listOf(Position(Float.NaN, 0f, 0f), Position(1f, 0f, 1f))))
        assertNotNull(PlacementContactShadow.quad(listOf(Position(0f, 0f, 0f))))
    }
}
