package io.github.sceneview.collision

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Behavioural pins for the #2276 hot-path fix: the collision math now reads the
 * ray's backing vectors through [Ray.originRef] / [Ray.directionRef] instead of
 * allocating a defensive [Vector3] copy per call (per *triangle* in
 * [MeshCollider.rayTriangleIntersection]).
 *
 * The allocation count itself is not portable to assert across KMP targets, so
 * these tests instead prove the migration preserved behaviour: the same hit
 * results, and — critically — the ray is not corrupted by the read-only refs
 * even after thousands of intersection calls reuse the same [Ray] instance.
 */
class RayAllocationTest {

    private fun assertClose(expected: Float, actual: Float, epsilon: Float = 1e-4f) {
        assertTrue(abs(expected - actual) < epsilon, "Expected $expected but got $actual")
    }

    /** Builds a flat NxN quad grid on the z=2 plane, two triangles per cell. */
    private fun syntheticMesh(cells: Int): List<MeshCollider.Triangle> {
        val triangles = ArrayList<MeshCollider.Triangle>(cells * cells * 2)
        val step = 2f / cells
        for (i in 0 until cells) {
            for (j in 0 until cells) {
                val x0 = -1f + i * step
                val x1 = x0 + step
                val y0 = -1f + j * step
                val y1 = y0 + step
                val a = Vector3(x0, y0, 2f)
                val b = Vector3(x1, y0, 2f)
                val c = Vector3(x1, y1, 2f)
                val d = Vector3(x0, y1, 2f)
                triangles.add(MeshCollider.Triangle(a, b, c))
                triangles.add(MeshCollider.Triangle(a, c, d))
            }
        }
        return triangles
    }

    @Test
    fun rayMeshIntersectionStillHitsAfterRefMigration() {
        // Ray fired straight down +z from z=-1 at the grid centre. The grid lies
        // on z=2, so the hit distance must be exactly 3.
        val mesh = syntheticMesh(cells = 20) // 800 triangles
        val ray = Ray(Vector3(0.05f, 0.05f, -1f), Vector3(0f, 0f, 1f))

        val result = MeshCollider.rayMeshIntersection(ray, mesh)

        assertTrue(result.hit, "Ray through the centre of a z=2 grid must hit")
        assertClose(3f, result.distance)
        // World-space hit point: x/y unchanged, z on the grid plane.
        assertClose(0.05f, result.point.x)
        assertClose(0.05f, result.point.y)
        assertClose(2f, result.point.z)
    }

    @Test
    fun rayMeshIntersectionMissesOffGrid() {
        // Same grid, but the ray is offset well outside the [-1,1] extent.
        val mesh = syntheticMesh(cells = 20)
        val ray = Ray(Vector3(5f, 5f, -1f), Vector3(0f, 0f, 1f))

        val result = MeshCollider.rayMeshIntersection(ray, mesh)

        assertTrue(!result.hit, "Ray outside the grid extent must miss")
    }

    @Test
    fun originRefAndDirectionRefReturnLiveBackingVectors() {
        // Strategy A contract: the refs are the live backing instances, not copies.
        val origin = Vector3(1f, 2f, 3f)
        val direction = Vector3(0f, 0f, 4f) // normalized on set → (0,0,1)
        val ray = Ray(origin, direction)

        // Two ref calls return the SAME instance (no allocation).
        assertSame(ray.originRef(), ray.originRef())
        assertSame(ray.directionRef(), ray.directionRef())

        // The public getter still returns an independent copy (API-stable).
        assertTrue(ray.getOrigin() !== ray.originRef())
        assertTrue(ray.getDirection() !== ray.directionRef())

        // Values match.
        assertClose(1f, ray.originRef().x)
        assertClose(2f, ray.originRef().y)
        assertClose(3f, ray.originRef().z)
        assertClose(0f, ray.directionRef().x)
        assertClose(0f, ray.directionRef().y)
        assertClose(1f, ray.directionRef().z)
    }

    @Test
    fun repeatedIntersectionsDoNotCorruptTheRay() {
        // The read-only contract: running many intersections that read the refs
        // must leave the ray's origin/direction untouched.
        val mesh = syntheticMesh(cells = 32) // ~2048 triangles → ~6k ref reads/call
        val ray = Ray(Vector3(0.1f, -0.2f, -1f), Vector3(0f, 0f, 1f))

        val originBefore = ray.getOrigin()
        val directionBefore = ray.getDirection()

        repeat(50) {
            val result = MeshCollider.rayMeshIntersection(ray, mesh)
            assertTrue(result.hit)
            assertClose(3f, result.distance)
        }

        // The ray must be byte-for-byte unchanged.
        assertEquals(originBefore.x, ray.getOrigin().x)
        assertEquals(originBefore.y, ray.getOrigin().y)
        assertEquals(originBefore.z, ray.getOrigin().z)
        assertEquals(directionBefore.x, ray.getDirection().x)
        assertEquals(directionBefore.y, ray.getDirection().y)
        assertEquals(directionBefore.z, ray.getDirection().z)
    }

    @Test
    fun shapeIntersectionsAgreeWithRefMigration() {
        // Cross-check every migrated shape getter against a known hit.
        val ray = Ray(Vector3(0f, 0f, -5f), Vector3(0f, 0f, 1f))

        val box = Box(Vector3(2f, 2f, 2f))
        val sphere = Sphere(1f)
        val plane = Plane(Vector3(0f, 0f, 0f), Vector3(0f, 0f, 1f))
        val capsule = Capsule(radius = 1f, height = 2f)

        val boxHit = RayHit().also { assertTrue(box.rayIntersection(ray, it)) }
        val sphereHit = RayHit().also { assertTrue(sphere.rayIntersection(ray, it)) }
        val planeHit = RayHit().also { assertTrue(plane.rayIntersection(ray, it)) }
        val capsuleHit = RayHit().also { assertTrue(capsule.rayIntersection(ray, it)) }

        // Box & capsule half-extent 1 → near face at z=-1 → distance 4.
        assertClose(4f, boxHit.getDistance())
        assertClose(4f, sphereHit.getDistance())
        assertClose(4f, capsuleHit.getDistance())
        // Plane at z=0 → distance 5.
        assertClose(5f, planeHit.getDistance())
    }
}
