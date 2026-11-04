package io.github.sceneview.ar

import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlacementGroundContactTest {

    @Test
    fun `the disc covers the ring's outer edge`() {
        val footprint = SelectionRing.Footprint(0f, 0f, 0.3f)
        val quad = PlacementContactShadow.Quad(0f, 0f, sizeX = 0.2f, sizeZ = 0.2f)
        val radius = PlacementGroundContact.radius(footprint, quad)
        assertEquals(0.3f + SelectionRing.tubeRadius(0.3f), radius, 1e-6f)
    }

    @Test
    fun `the disc covers the contact shadow when it reaches farther`() {
        val footprint = SelectionRing.Footprint(0f, 0f, 0.2f)
        val quad = PlacementContactShadow.Quad(0f, 0f, sizeX = 0.4f, sizeZ = 0.9f)
        assertEquals(0.45f, PlacementGroundContact.radius(footprint, quad), 1e-6f)
        assertEquals(0f, PlacementGroundContact.radius(SelectionRing.Footprint(0f, 0f, 0f), null), 0f)
    }

    @Test
    fun `the contact follows the surface's world transform`() {
        // A surface frame scaled by 2 and moved to (1, 0.5, -3): the disc centre moves with it,
        // the radius scales, and the normal comes out unit length.
        val surface = Mat4(
            Float4(2f, 0f, 0f, 0f),
            Float4(0f, 2f, 0f, 0f),
            Float4(0f, 0f, 2f, 0f),
            Float4(1f, 0.5f, -3f, 1f),
        )
        val world = PlacementGroundContact.world(surface, 0.1f, -0.2f, 0.25f)
        assertNotNull(world)
        val contact = world!!
        assertEquals(1.2f, contact.center.x, 1e-6f)
        assertEquals(0.5f, contact.center.y, 1e-6f)
        assertEquals(-3.4f, contact.center.z, 1e-6f)
        assertEquals(0f, contact.normal.x, 1e-6f)
        assertEquals(1f, contact.normal.y, 1e-6f)
        assertEquals(0f, contact.normal.z, 1e-6f)
        assertEquals(0.5f, contact.radius, 1e-6f)
    }

    @Test
    fun `a degenerate surface or an empty disc grounds nothing`() {
        val flat = Mat4(
            Float4(1f, 0f, 0f, 0f),
            Float4(0f, 0f, 0f, 0f),
            Float4(0f, 0f, 1f, 0f),
            Float4(0f, 0f, 0f, 1f),
        )
        assertNull(PlacementGroundContact.world(flat, 0f, 0f, 0.3f))
        assertNull(PlacementGroundContact.world(Mat4(), 0f, 0f, 0f))
    }
}
