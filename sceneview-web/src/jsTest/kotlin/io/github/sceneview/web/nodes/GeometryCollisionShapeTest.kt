package io.github.sceneview.web.nodes

import io.github.sceneview.collision.Box
import io.github.sceneview.collision.Sphere
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #2024 P5c — the analytic local picking bounds derived from a
 * [GeometryConfig] (no engine, no post-load gate).
 */
class GeometryCollisionShapeTest {

    private val eps = 1e-4f

    private fun assertClose(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) < eps, "$message: expected $expected, was $actual")
    }

    @Test
    fun cubeBoundsMatchSizeAndBakedPosition() {
        val config = GeometryConfig().apply {
            cube(); size(2.0, 3.0, 4.0); position(1.0, 2.0, 3.0)
        }
        val box = config.localCollisionShape() as Box
        assertClose(2f, box.getSize().x, "size.x")
        assertClose(3f, box.getSize().y, "size.y")
        assertClose(4f, box.getSize().z, "size.z")
        assertClose(1f, box.getCenter().x, "center.x")
        assertClose(3f, box.getCenter().z, "center.z")
    }

    @Test
    fun bakedScaleGrowsTheBox() {
        val config = GeometryConfig().apply { cube(); size(1.0); scale(2.0, 3.0, 4.0) }
        val box = config.localCollisionShape() as Box
        assertClose(2f, box.getSize().x, "size.x")
        assertClose(3f, box.getSize().y, "size.y")
        assertClose(4f, box.getSize().z, "size.z")
    }

    @Test
    fun sphereBoundsAreASphereShape() {
        val config = GeometryConfig().apply {
            sphere(); radius(1.5); position(0.0, 1.0, 0.0); scale(2.0, 1.0, 1.0)
        }
        val sphere = config.localCollisionShape() as Sphere
        assertClose(3f, sphere.getRadius(), "radius scaled by the max axis")
        assertClose(1f, sphere.getCenter().y, "center.y")
    }

    @Test
    fun cylinderBoundsSpanDiameterAndHeight() {
        val config = GeometryConfig().apply { cylinder(); radius(0.5); height(2.0) }
        val box = config.localCollisionShape() as Box
        assertClose(1f, box.getSize().x, "size.x = diameter")
        assertClose(2f, box.getSize().y, "size.y = height")
        assertClose(1f, box.getSize().z, "size.z = diameter")
    }

    @Test
    fun planeBoundsAreThinButHittable() {
        val config = GeometryConfig().apply { plane(); size(4.0, 1.0, 6.0) }
        val box = config.localCollisionShape() as Box
        assertClose(4f, box.getSize().x, "size.x")
        assertTrue(box.getSize().y in 1e-4f..0.1f, "plane thickness must be thin but non-zero")
        assertClose(6f, box.getSize().z, "size.z")
    }
}
