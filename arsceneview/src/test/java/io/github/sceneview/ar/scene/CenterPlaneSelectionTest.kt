package io.github.sceneview.ar.scene

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.utils.fps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * JVM tests for the centre-plane selection maths that replaced `RENDER_CENTER`'s per-frame
 * `Frame.hitTest` (#3339).
 *
 * **What this pins down.** [rayPlaneDistance] and [nearestPlaneAlongRay] are `internal`
 * top-level functions with no ARCore types in their signatures, precisely so their
 * correctness can be owned by a plain JVM test instead of by on-device AR QA. Everything the
 * replaced hit test used to decide — which of the tracked floor planes the camera is aimed
 * at, and nothing when it is aimed at none — now flows through them. A regression here does
 * not throw: it shows up on a device as the plane grid highlighting the wrong plane, or as no
 * grid at all.
 *
 * **What this does NOT cover.** [CenterPlaneFinder.find] itself, which needs a live ARCore
 * `Frame` (native session) and is exercised by the device-QA harness —
 * `bash .claude/scripts/device-qa.sh --platform=android`. Its ARCore-facing part is
 * deliberately thin: filter to tracked, non-subsumed `HORIZONTAL_UPWARD_FACING` planes, read
 * each `centerPose` once, then delegate to the functions tested here.
 */
class CenterPlaneSelectionTest {

    // Camera at standing eye height above a floor at y = 0, looking straight down.
    private val eye = Float3(0f, 1.6f, 0f)
    private val down = Float3(0f, -1f, 0f)
    private val up = Float3(0f, 1f, 0f)

    /** An infinite horizontal upward-facing plane at height [y]. */
    private fun floorAt(y: Float) = PlaneRayCandidate(
        center = Float3(0f, y, 0f),
        normal = up
    )

    private val acceptAll: (Int, Float3) -> Boolean = { _, _ -> true }

    // ── rayPlaneDistance ───────────────────────────────────────────────────────────────

    @Test
    fun `looking straight down hits the floor at eye height`() {
        val distance = rayPlaneDistance(
            rayOrigin = eye,
            rayDirection = down,
            planeCenter = Float3(0f, 0f, 0f),
            planeNormal = up
        )

        assertEquals(1.6f, distance!!, 1e-5f)
    }

    @Test
    fun `a 45 degree ray travels the drop times root two`() {
        val distance = rayPlaneDistance(
            rayOrigin = eye,
            rayDirection = normalize(Float3(1f, -1f, 0f)),
            planeCenter = Float3(0f, 0f, 0f),
            planeNormal = up
        )

        assertEquals(1.6f * sqrt(2f), distance!!, 1e-4f)
    }

    @Test
    fun `the plane center may be any point on the plane`() {
        // Same floor, centre pose 10 m away laterally — the intersection distance is
        // unchanged because only the plane's height enters the equation.
        val distance = rayPlaneDistance(
            rayOrigin = eye,
            rayDirection = down,
            planeCenter = Float3(10f, 0f, -4f),
            planeNormal = up
        )

        assertEquals(1.6f, distance!!, 1e-5f)
    }

    @Test
    fun `a ray parallel to the plane never intersects`() {
        val distance = rayPlaneDistance(
            rayOrigin = eye,
            rayDirection = Float3(1f, 0f, 0f),
            planeCenter = Float3(0f, 0f, 0f),
            planeNormal = up
        )

        assertNull(distance)
    }

    @Test
    fun `a near parallel ray below the epsilon is rejected`() {
        // Tilted by 5e-7 — under RAY_PLANE_PARALLEL_EPSILON, so the intersection would be
        // hundreds of kilometres away and is meaningless.
        val distance = rayPlaneDistance(
            rayOrigin = eye,
            rayDirection = Float3(1f, -5e-7f, 0f),
            planeCenter = Float3(0f, 0f, 0f),
            planeNormal = up
        )

        assertNull(distance)
    }

    @Test
    fun `a tilt just above the epsilon still intersects`() {
        val distance = rayPlaneDistance(
            rayOrigin = eye,
            rayDirection = Float3(1f, -2e-6f, 0f),
            planeCenter = Float3(0f, 0f, 0f),
            planeNormal = up
        )

        assertNotNull(distance)
        assertTrue(distance!! > 0f)
    }

    @Test
    fun `a plane behind the camera is rejected`() {
        // Looking up: the floor is behind the ray, at a negative distance.
        val distance = rayPlaneDistance(
            rayOrigin = eye,
            rayDirection = up,
            planeCenter = Float3(0f, 0f, 0f),
            planeNormal = up
        )

        assertNull(distance)
    }

    @Test
    fun `a plane exactly at the ray origin is rejected`() {
        // The camera sits on the plane: distance 0, which is not a surface it is looking at.
        val distance = rayPlaneDistance(
            rayOrigin = Float3(0f, 0f, 0f),
            rayDirection = down,
            planeCenter = Float3(0f, 0f, 0f),
            planeNormal = up
        )

        assertNull(distance)
    }

    @Test
    fun `a NaN ray direction never produces a hit`() {
        val distance = rayPlaneDistance(
            rayOrigin = eye,
            rayDirection = Float3(0f, Float.NaN, 0f),
            planeCenter = Float3(0f, 0f, 0f),
            planeNormal = up
        )

        assertNull(distance)
    }

    @Test
    fun `a NaN plane center never produces a hit`() {
        val distance = rayPlaneDistance(
            rayOrigin = eye,
            rayDirection = down,
            planeCenter = Float3(0f, Float.NaN, 0f),
            planeNormal = up
        )

        assertNull(distance)
    }

    @Test
    fun `a non unit normal does not change the hit distance`() {
        // Only the ratio of the two dot products matters, so scaling the normal cancels out.
        val distance = rayPlaneDistance(
            rayOrigin = eye,
            rayDirection = down,
            planeCenter = Float3(0f, 0f, 0f),
            planeNormal = Float3(0f, 2f, 0f)
        )

        assertEquals(1.6f, distance!!, 1e-5f)
    }

    @Test
    fun `the parallel epsilon matches the repo canonical threshold`() {
        // io.github.sceneview.collision.Plane.NEAR_ZERO_THRESHOLD, also used by
        // Box.rayIntersection, MeshCollider and MathHelper.
        assertEquals(1e-6f, RAY_PLANE_PARALLEL_EPSILON, 0f)
    }

    // ── nearestPlaneAlongRay ───────────────────────────────────────────────────────────

    @Test
    fun `no candidates means no plane`() {
        assertNull(nearestPlaneAlongRay(eye, down, emptyList(), acceptAll))
    }

    @Test
    fun `the single hit candidate wins`() {
        assertEquals(0, nearestPlaneAlongRay(eye, down, listOf(floorAt(0f)), acceptAll))
    }

    @Test
    fun `the single missed candidate loses`() {
        // A floor above the camera, which is looking down.
        assertEquals(null, nearestPlaneAlongRay(eye, down, listOf(floorAt(3f)), acceptAll))
    }

    @Test
    fun `the nearest plane along the ray wins regardless of list order`() {
        // Distances from the camera looking down: y=1.0 → 0.6, y=0.5 → 1.1, y=0.0 → 1.6.
        val ground = floorAt(0f)
        val step = floorAt(0.5f)
        val table = floorAt(1.0f)

        assertEquals(1, nearestPlaneAlongRay(eye, down, listOf(ground, table, step), acceptAll))
        assertEquals(0, nearestPlaneAlongRay(eye, down, listOf(table, step, ground), acceptAll))
        assertEquals(2, nearestPlaneAlongRay(eye, down, listOf(step, ground, table), acceptAll))
    }

    @Test
    fun `a candidate outside its polygon falls through to the farther one`() {
        val ground = floorAt(0f)
        val table = floorAt(1.0f)

        // The near plane (the table) is rejected by the polygon test: the camera is aimed
        // past its edge, so the ground behind it is what the user is actually looking at.
        assertEquals(
            1,
            nearestPlaneAlongRay(eye, down, listOf(table, ground)) { index, _ -> index != 0 }
        )
        // Same outcome with the list order flipped — rejection must not depend on the order
        // ARCore happened to hand the planes over in.
        assertEquals(
            0,
            nearestPlaneAlongRay(eye, down, listOf(ground, table)) { index, _ -> index != 1 }
        )
    }

    @Test
    fun `all candidates outside their polygons means no plane`() {
        val candidates = listOf(floorAt(0f), floorAt(0.5f), floorAt(1.0f))

        assertNull(nearestPlaneAlongRay(eye, down, candidates) { _, _ -> false })
    }

    @Test
    fun `a plane met exactly at the origin gives way to a farther one`() {
        // The camera stands on the upper plane; the floor below it is the real answer.
        val candidates = listOf(floorAt(1.6f), floorAt(0f))

        assertEquals(1, nearestPlaneAlongRay(eye, down, candidates, acceptAll))
    }

    @Test
    fun `the polygon test is not consulted for planes the ray misses`() {
        val wall = PlaneRayCandidate(center = Float3(2f, 0f, 0f), normal = Float3(1f, 0f, 0f))
        val ceiling = floorAt(3f)
        val ground = floorAt(0f)
        val consulted = mutableListOf<Int>()

        val index = nearestPlaneAlongRay(eye, down, listOf(wall, ceiling, ground)) { i, _ ->
            consulted += i
            true
        }

        // The wall is parallel to the ray and the ceiling is behind it, so neither reaches
        // the polygon test — which on a device is a JNI round trip per call.
        assertEquals(listOf(2), consulted)
        assertEquals(2, index)
    }

    @Test
    fun `the polygon test receives the world space hit point`() {
        var hitPoint: Float3? = null

        nearestPlaneAlongRay(
            rayOrigin = eye,
            rayDirection = normalize(Float3(1f, -1f, 0f)),
            candidates = listOf(floorAt(0f))
        ) { _, point ->
            hitPoint = point
            true
        }

        // A 45° ray from 1.6 m up lands 1.6 m ahead, on the plane.
        assertNotNull(hitPoint)
        assertEquals(1.6f, hitPoint!!.x, 1e-4f)
        assertEquals(0f, hitPoint!!.y, 1e-4f)
        assertEquals(0f, hitPoint!!.z, 1e-4f)
    }

    // ── Renderer rate gate ─────────────────────────────────────────────────────────────

    @Test
    fun `the default rate gate admits one pass every fourth frame at 30 fps`() {
        // Both renderers gate their whole update pass on
        //     frame.fps(lastProcessedFrame) < maxHitTestPerSecond
        // with maxHitTestPerSecond defaulting to 10. Comparing against the last *processed*
        // frame rather than the previous frame is what makes the emergent rate 7.5 Hz rather
        // than 10 Hz — the arithmetic that identified the plane renderer as the source of the
        // 134 ms / 137 ms warning cadence in the #3339 log, and the reason a "10 per second"
        // knob must not be read as a promise of 10 passes per second.
        val maxHitTestPerSecond = 10
        val frameIntervalNanos = 33_333_333L // the 30 fps camera stream seen in the log
        var lastProcessed: Long? = null
        val processed = mutableListOf<Long>()

        repeat(60) { index ->
            val timestamp = index * frameIntervalNanos
            if (timestamp.fps(lastProcessed) < maxHitTestPerSecond) {
                lastProcessed = timestamp
                processed += timestamp
            }
        }

        assertTrue(processed.size >= 2)
        val periods = processed.zipWithNext { previous, current -> current - previous }
        assertEquals(listOf(4 * frameIntervalNanos), periods.distinct())
        assertEquals(133.3, (4 * frameIntervalNanos) / 1_000_000.0, 0.1)
        assertEquals(7.5, 1_000_000_000.0 / (4 * frameIntervalNanos), 0.01)
    }
}
