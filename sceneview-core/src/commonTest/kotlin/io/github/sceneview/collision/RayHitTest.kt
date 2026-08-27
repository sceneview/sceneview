package io.github.sceneview.collision

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class RayHitTest {

    private fun assertClose(expected: Float, actual: Float, epsilon: Float = 0.001f) {
        assertTrue(abs(expected - actual) < epsilon, "Expected $expected but got $actual")
    }

    @Test
    fun defaultDistanceIsMaxValue() {
        val hit = RayHit()
        assertTrue(hit.getDistance() == Float.MAX_VALUE)
    }

    @Test
    fun defaultPointIsZero() {
        val hit = RayHit()
        val point = hit.getPoint()
        assertClose(0f, point.x)
        assertClose(0f, point.y)
        assertClose(0f, point.z)
    }

    @Test
    fun setDistanceUpdatesDistance() {
        val hit = RayHit()
        hit.setDistance(42f)
        assertClose(42f, hit.getDistance())
    }

    @Test
    fun setPointUpdatesPoint() {
        val hit = RayHit()
        hit.setPoint(Vector3(1f, 2f, 3f))
        val point = hit.getPoint()
        assertClose(1f, point.x)
        assertClose(2f, point.y)
        assertClose(3f, point.z)
    }

    @Test
    fun resetRestoresDefaults() {
        val hit = RayHit()
        hit.setDistance(5f)
        hit.setPoint(Vector3(10f, 20f, 30f))
        hit.reset()
        assertTrue(hit.getDistance() == Float.MAX_VALUE)
        val point = hit.getPoint()
        assertClose(0f, point.x)
        assertClose(0f, point.y)
        assertClose(0f, point.z)
    }

    @Test
    fun setCopiesFromOtherRayHit() {
        val source = RayHit()
        source.setDistance(7.5f)
        source.setPoint(Vector3(3f, 6f, 9f))

        val target = RayHit()
        target.set(source)

        assertClose(7.5f, target.getDistance())
        assertClose(3f, target.getPoint().x)
        assertClose(6f, target.getPoint().y)
        assertClose(9f, target.getPoint().z)
    }

    @Test
    fun setIsCopyNotReference() {
        val source = RayHit()
        source.setDistance(5f)
        source.setPoint(Vector3(1f, 1f, 1f))

        val target = RayHit()
        target.set(source)

        // Modifying source should not affect target
        source.setDistance(99f)
        source.setPoint(Vector3(99f, 99f, 99f))

        assertClose(5f, target.getDistance())
        assertClose(1f, target.getPoint().x)
    }

    @Test
    fun getWorldPositionReturnsFloat3() {
        val hit = RayHit()
        hit.setPoint(Vector3(1f, 2f, 3f))
        val worldPos = hit.getWorldPosition()
        assertClose(1f, worldPos.x)
        assertClose(2f, worldPos.y)
        assertClose(3f, worldPos.z)
    }

    @Test
    fun getPointReturnsCopy() {
        val hit = RayHit()
        hit.setPoint(Vector3(5f, 5f, 5f))
        val point1 = hit.getPoint()
        val point2 = hit.getPoint()
        // Both should be equal but different instances
        assertClose(point1.x, point2.x)
        assertClose(point1.y, point2.y)
        assertClose(point1.z, point2.z)
    }

    // --- Ray direction (#3329) ---

    @Test
    fun defaultDirectionLooksDownNegativeZ() {
        // A hit built outside CollisionSystem.hitTest still has to answer a facing test, and the
        // front-facing answer is the safe one: it keeps the pre-#3329 mapping.
        val direction = RayHit().getDirection()
        assertClose(0f, direction.x)
        assertClose(0f, direction.y)
        assertClose(-1f, direction.z)
    }

    @Test
    fun directionRoundTrips() {
        val hit = RayHit()
        hit.setWorldDirection(dev.romainguy.kotlin.math.Float3(0.2f, -0.5f, 0.8f))
        val world = hit.getWorldDirection()
        assertClose(0.2f, world.x)
        assertClose(-0.5f, world.y)
        assertClose(0.8f, world.z)
    }

    @Test
    fun setCopiesTheDirection() {
        val source = RayHit()
        source.setDirection(Vector3(1f, 0f, 0f))
        val target = RayHit()
        target.set(source)
        assertClose(1f, target.getDirection().x)
    }

    @Test
    fun resetRestoresTheDefaultDirection() {
        val hit = RayHit()
        hit.setDirection(Vector3(1f, 0f, 0f))
        hit.reset()
        assertClose(0f, hit.getDirection().x)
        assertClose(-1f, hit.getDirection().z)
    }
}
