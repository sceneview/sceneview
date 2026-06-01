package io.github.sceneview.collision

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behaviour-preservation pins for the #2330 Phase-2 collision allocation-hygiene work.
 *
 * The change inlined the per-test [Vector3] math in [Box.rayIntersection],
 * [Capsule.rayIntersection], the SAT box/box test, the sphere/box (now point/box) test,
 * and added caller-supplied-sink overloads on [Octree] plus a shared MISS constant in
 * [MeshCollider]. Every intersection RESULT (hit/miss, distance, point) must be identical
 * — these tests assert that, exercising the new scalar paths under rotation (where the
 * old per-axis [Vector3] allocations lived) and the new sink/`writeSegmentEndpoints`
 * allocation-free helpers.
 */
class CollisionAllocHygieneTest {

    private fun assertClose(expected: Float, actual: Float, eps: Float = 1e-4f) {
        assertTrue(abs(expected - actual) < eps, "Expected $expected but got $actual")
    }

    // --- Box.rayIntersection: inlined scalar slab test, exercised under rotation ---

    @Test
    fun orientedBoxRayIntersectionUnchanged() {
        // 45° about Y: the box stays symmetric, so an axis-aligned ray down -z toward
        // the origin must still hit, with the near face at half-extent 1 along the
        // rotated axis. The hit must be reported and lie in front of the origin.
        val box = Box(Vector3(2f, 2f, 2f))
        box.setRotation(Quaternion.axisAngle(Vector3(0f, 1f, 0f), 45f))

        val ray = Ray(Vector3(0f, 0f, -5f), Vector3(0f, 0f, 1f))
        val hit = RayHit()
        assertTrue(box.rayIntersection(ray, hit))
        // Rotated 45° about Y, the box's z-extent footprint is sqrt(2) ≈ 1.4142,
        // so the near face is at z = -1.4142 → distance 5 - 1.4142 = 3.5858.
        assertClose(3.5858f, hit.getDistance(), 1e-3f)
        assertClose(0f, hit.getPoint().x, 1e-3f)
        assertClose(0f, hit.getPoint().y, 1e-3f)
    }

    @Test
    fun boxRayMissStillMisses() {
        val box = Box(Vector3(2f, 2f, 2f))
        box.setRotation(Quaternion.axisAngle(Vector3(0f, 1f, 0f), 30f))
        val ray = Ray(Vector3(10f, 10f, -5f), Vector3(0f, 0f, 1f))
        assertFalse(box.rayIntersection(ray, RayHit()))
    }

    @Test
    fun rayFromInsideBoxReportsExitPoint() {
        // Ray starting inside the box: distance must be the (positive) exit distance.
        val box = Box(Vector3(2f, 2f, 2f))
        val ray = Ray(Vector3(0f, 0f, 0f), Vector3(0f, 0f, 1f))
        val hit = RayHit()
        assertTrue(box.rayIntersection(ray, hit))
        assertClose(1f, hit.getDistance())
    }

    // --- Capsule: writeSegmentEndpoints parity + rotated rayIntersection ---

    @Test
    fun writeSegmentEndpointsMatchesGetter() {
        val capsule = Capsule(radius = 0.5f, height = 3f, center = Vector3(1f, 2f, 3f))
        capsule.setRotation(Quaternion.axisAngle(Vector3(1f, 0f, 0f), 37f))

        val (expectedBottom, expectedTop) = capsule.getSegmentEndpoints()
        val bottom = Vector3()
        val top = Vector3()
        capsule.writeSegmentEndpoints(bottom, top)

        assertClose(expectedBottom.x, bottom.x)
        assertClose(expectedBottom.y, bottom.y)
        assertClose(expectedBottom.z, bottom.z)
        assertClose(expectedTop.x, top.x)
        assertClose(expectedTop.y, top.y)
        assertClose(expectedTop.z, top.z)
    }

    @Test
    fun orientedCapsuleRayIntersectionUnchanged() {
        // Capsule laid along X (rotate the default-Y axis 90° about Z), radius 1.
        // A ray down -z through the body must hit the cylinder at z = -1 → distance 4.
        val capsule = Capsule(radius = 1f, height = 4f)
        capsule.setRotation(Quaternion.axisAngle(Vector3(0f, 0f, 1f), 90f))
        val ray = Ray(Vector3(0f, 0f, -5f), Vector3(0f, 0f, 1f))
        val hit = RayHit()
        assertTrue(capsule.rayIntersection(ray, hit))
        assertClose(4f, hit.getDistance())
    }

    @Test
    fun capsuleCapHitReported() {
        // Ray aimed at a cap (just past the cylinder end) still reports a hit.
        val capsule = Capsule(radius = 1f, height = 4f) // axis along Y, caps at y=±1
        // Fire down -z, offset above the cylinder top cap centre (y just under 1).
        val ray = Ray(Vector3(0f, 0.9f, -5f), Vector3(0f, 0f, 1f))
        assertTrue(capsule.rayIntersection(ray, RayHit()))
    }

    // --- Intersections: point/box (was sphere/box) + SAT box/box under rotation ---

    @Test
    fun sphereBoxIntersectionUnderRotationUnchanged() {
        val box = Box(Vector3(2f, 2f, 2f))
        box.setRotation(Quaternion.axisAngle(Vector3(0f, 1f, 0f), 45f))
        // Sphere just touching the rotated face footprint.
        assertTrue(Intersections.sphereBoxIntersection(Sphere(0.5f, Vector3(1.2f, 0f, 0f)), box))
        // Sphere well clear.
        assertFalse(Intersections.sphereBoxIntersection(Sphere(0.5f, Vector3(5f, 0f, 0f)), box))
    }

    @Test
    fun pointWithinBoxDistanceMatchesSphereSemantics() {
        val box = Box(Vector3(2f, 2f, 2f))
        box.setRotation(Quaternion.axisAngle(Vector3(0f, 0f, 1f), 20f))
        // The point helper must agree with the equivalent Sphere/box test for the same
        // centre + radius, across hits and misses.
        for (c in listOf(Vector3(0f, 0f, 0f), Vector3(1.4f, 0f, 0f), Vector3(3f, 0f, 0f))) {
            val r = 0.6f
            val viaPoint = Intersections.pointWithinBoxDistance(c.x, c.y, c.z, r, box)
            val viaSphere = Intersections.sphereBoxIntersection(Sphere(r, c), box)
            assertEquals(viaSphere, viaPoint, "Mismatch at $c")
        }
    }

    @Test
    fun boxBoxIntersectionUnderRotationUnchanged() {
        val a = Box(Vector3(2f, 2f, 2f))
        val b = Box(Vector3(2f, 2f, 2f), Vector3(1.5f, 0f, 0f))
        b.setRotation(Quaternion.axisAngle(Vector3(0f, 1f, 0f), 45f))
        assertTrue(Intersections.boxBoxIntersection(a, b), "Overlapping oriented boxes must intersect")

        val far = Box(Vector3(2f, 2f, 2f), Vector3(10f, 0f, 0f))
        far.setRotation(Quaternion.axisAngle(Vector3(0f, 1f, 0f), 30f))
        assertFalse(Intersections.boxBoxIntersection(a, far), "Distant boxes must not intersect")
    }

    @Test
    fun capsuleBoxIntersectionUnchanged() {
        val box = Box(Vector3(2f, 2f, 2f))
        val touching = Capsule(radius = 1f, height = 2f, center = Vector3(1.5f, 0f, 0f))
        assertTrue(touching.boxIntersection(box))

        val clear = Capsule(radius = 1f, height = 2f, center = Vector3(5f, 0f, 0f))
        assertFalse(clear.boxIntersection(box))
    }

    // --- Octree: sink overloads produce identical results to the list-returning ones ---

    private fun sampleOctree(): Pair<Octree, List<AABB>> {
        val bounds = AABB(Vector3(-10f, -10f, -10f), Vector3(10f, 10f, 10f))
        val octree = Octree(bounds, maxObjectsPerNode = 2)
        val boxes = ArrayList<AABB>()
        for (i in 0 until 20) {
            val o = -8f + i * 0.8f
            val aabb = AABB(Vector3(o, o, o), Vector3(o + 0.3f, o + 0.3f, o + 0.3f))
            boxes.add(aabb)
            octree.insert(aabb, i)
        }
        return octree to boxes
    }

    @Test
    fun querySinkOverloadMatchesListVersion() {
        val (octree, _) = sampleOctree()
        val region = AABB(Vector3(-3f, -3f, -3f), Vector3(3f, 3f, 3f))

        val viaList = octree.query(region)
        val sink = ArrayList<Int>()
        octree.query(region, sink)

        // Same indices, same order (own objects then children, recursion preserved).
        assertEquals(viaList, sink)
    }

    @Test
    fun queryRaySinkOverloadMatchesListVersion() {
        val (octree, _) = sampleOctree()
        val ray = Ray(Vector3(-8f, -8f, -8f), Vector3(1f, 1f, 1f))

        val viaList = octree.queryRay(ray)
        val sink = ArrayList<Int>()
        octree.queryRay(ray, sink)

        assertEquals(viaList, sink)
    }

    @Test
    fun querySinkAppendsWithoutClearing() {
        val (octree, _) = sampleOctree()
        val region = AABB(Vector3(-3f, -3f, -3f), Vector3(3f, 3f, 3f))
        val sink = arrayListOf(-1)
        octree.query(region, sink)
        // Pre-existing element preserved (append-only contract).
        assertEquals(-1, sink.first())
        assertTrue(sink.size > 1)
    }

    // --- MeshCollider: shared MISS constant behaves like a fresh miss ---

    @Test
    fun missResultIsStableAcrossCalls() {
        // A parallel / missing triangle returns a miss with hit=false and MAX distance.
        val ray = Ray(Vector3(0f, 0f, -1f), Vector3(0f, 0f, 1f))
        // Degenerate triangle behind / off the ray → miss.
        val t = MeshCollider.Triangle(Vector3(5f, 5f, 5f), Vector3(6f, 5f, 5f), Vector3(5f, 6f, 5f))
        val r1 = MeshCollider.rayTriangleIntersection(ray, t.v0, t.v1, t.v2)
        val r2 = MeshCollider.rayTriangleIntersection(ray, t.v0, t.v1, t.v2)
        assertFalse(r1.hit)
        assertFalse(r2.hit)
        assertEquals(Float.MAX_VALUE, r1.distance)
        assertEquals(Float.MAX_VALUE, r2.distance)
    }

    @Test
    fun emptyMeshIntersectionMisses() {
        val ray = Ray(Vector3(0f, 0f, -1f), Vector3(0f, 0f, 1f))
        val result = MeshCollider.rayMeshIntersection(ray, emptyList())
        assertFalse(result.hit)
        assertEquals(Float.MAX_VALUE, result.distance)
        assertEquals(-1, result.triangleIndex)
    }
}
