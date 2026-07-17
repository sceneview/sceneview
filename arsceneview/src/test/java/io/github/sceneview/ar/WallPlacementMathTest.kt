package io.github.sceneview.ar

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.normalize
import dev.romainguy.kotlin.math.rotation
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Pure-JVM regression tests for the wall-placement geometry + state machine landed by #2740
 * ([WallPlacement.kt]). Filament / ARCore are JNI-only and off the unit-test classpath, so — like
 * [PlacementSceneHitFilterTest] and `BillboardNodeMathTest` — this exercises the extracted pure
 * functions directly, verifying rotations by reading the `dev.romainguy.kotlin.math` matrix columns.
 */
class WallPlacementMathTest {

    private val eps = 1e-5f

    // ── wallYaw / wallFacingRotation ──────────────────────────────────────────────────────────

    @Test
    fun `wall facing +Z normal is zero yaw`() {
        assertEquals(0f, wallYaw(Direction(0f, 0f, 1f)), eps)
    }

    @Test
    fun `wall facing +X normal is a quarter turn`() {
        assertEquals((PI / 2).toFloat(), wallYaw(Direction(1f, 0f, 0f)), eps)
    }

    @Test
    fun `wall normal vertical component is ignored`() {
        // A slightly-off ARCore normal with a vertical component must still yield the +X heading.
        assertEquals((PI / 2).toFloat(), wallYaw(Direction(1f, 0.15f, 0f)), 1e-3f)
    }

    @Test
    fun `degenerate straight-up normal falls back to zero yaw, no NaN`() {
        val yaw = wallYaw(Direction(0f, 1f, 0f))
        assertEquals(0f, yaw, eps)
        assertFalse(yaw.isNaN())
    }

    @Test
    fun `facing rotation maps local +Z onto the wall normal`() {
        listOf(
            Direction(0f, 0f, 1f),
            Direction(1f, 0f, 0f),
            Direction(0f, 0f, -1f),
            normalize(Float3(1f, 0f, 1f)).let { Direction(it.x, it.y, it.z) },
        ).forEach { normal ->
            val q = wallFacingRotation(normal)
            // Local +Z basis in world = third column of the rotation matrix.
            val m = rotation(q)
            val zInWorld = Float3(m.z.x, m.z.y, m.z.z)
            val expected = normalize(Float3(normal.x, 0f, normal.z))
            assertVecEquals(expected, zInWorld)
            // A rotation quaternion is unit-length.
            val len = sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w)
            assertEquals("quaternion must be unit-length", 1f, len, eps)
        }
    }

    @Test
    fun `facing rotation keeps the object upright (local +Y stays world up)`() {
        val q = wallFacingRotation(Direction(1f, 0f, 0f))
        val m = rotation(q)
        assertVecEquals(Float3(0f, 1f, 0f), Float3(m.y.x, m.y.y, m.y.z))
    }

    // ── floorWallSeam ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `seam point sits at floor height under the wall point`() {
        val seam = floorWallSeam(
            wallNormal = Direction(0f, 0f, 1f),
            wallPoint = Position(2f, 1.5f, -3f),
            floorY = 0.2f,
        )
        assertEquals(2f, seam.point.x, eps)
        assertEquals(0.2f, seam.point.y, eps)
        assertEquals(-3f, seam.point.z, eps)
    }

    @Test
    fun `seam direction is horizontal, unit-length and perpendicular to the wall normal`() {
        val normal = normalize(Float3(0.6f, 0f, 0.8f)).let { Direction(it.x, it.y, it.z) }
        val seam = floorWallSeam(normal, Position(0f, 0f, 0f), 0f)
        val d = seam.direction
        assertEquals("seam runs horizontally", 0f, d.y, eps)
        assertEquals("seam direction is unit-length", 1f, sqrt(d.x * d.x + d.z * d.z), eps)
        // Perpendicular to the wall normal → dot product ~ 0.
        assertEquals(0f, d.x * normal.x + d.z * normal.z, eps)
    }

    // ── wallAnchorPose ────────────────────────────────────────────────────────────────────────

    @Test
    fun `anchor keeps the tapped X-Z and seats at floor plus mount height`() {
        val pose = wallAnchorPose(
            wallHit = Position(1f, 2f, 3f),
            wallNormal = Direction(0f, 0f, 1f),
            floorY = 0.1f,
            mountHeight = 1.2f,
        )
        assertEquals(1f, pose.position.x, eps)
        assertEquals(1.3f, pose.position.y, eps) // 0.1 + 1.2, decoupled from the hit's own Y (2f)
        assertEquals(3f, pose.position.z, eps)
    }

    // ── nextWallPlacementPhase ────────────────────────────────────────────────────────────────

    @Test
    fun `phase progresses floor then wall then edge`() {
        assertEquals(
            WallPlacementPhase.FINDING_FLOOR,
            nextWallPlacementPhase(floorFound = false, wallFound = false, placed = false),
        )
        assertEquals(
            WallPlacementPhase.FINDING_WALL,
            nextWallPlacementPhase(floorFound = true, wallFound = false, placed = false),
        )
        assertEquals(
            WallPlacementPhase.ALIGNING_EDGE,
            nextWallPlacementPhase(floorFound = true, wallFound = true, placed = false),
        )
    }

    @Test
    fun `placed is terminal even if tracking is momentarily lost`() {
        assertEquals(
            WallPlacementPhase.PLACED,
            nextWallPlacementPhase(floorFound = false, wallFound = false, placed = true),
        )
    }

    // ── isWallPlacementHit ────────────────────────────────────────────────────────────────────

    @Test
    fun `vertical plane hit inside polygon while tracking is accepted`() {
        assertTrue(
            isWallPlacementHit(
                verticalPlaneInPolygon = true,
                trackableTracking = true,
                distance = 1.5f,
            )
        )
    }

    @Test
    fun `non-vertical or out-of-polygon hit is rejected`() {
        assertFalse(
            isWallPlacementHit(
                verticalPlaneInPolygon = false,
                trackableTracking = true,
                distance = 1.5f,
            )
        )
    }

    @Test
    fun `non-tracking or too-far wall hit is rejected`() {
        assertFalse(
            isWallPlacementHit(
                verticalPlaneInPolygon = true,
                trackableTracking = false,
                distance = 1.5f,
            )
        )
        assertFalse(
            isWallPlacementHit(
                verticalPlaneInPolygon = true,
                trackableTracking = true,
                distance = MAX_WALL_PLACEMENT_DISTANCE + 0.01f,
            )
        )
    }

    // ── computeSeam null-gating ───────────────────────────────────────────────────────────────

    @Test
    fun `seam is null until both a wall and a floor are known`() {
        assertNull(computeSeam(null, Position(0f, 0f, 0f), 0f))
        assertNull(computeSeam(Direction(0f, 0f, 1f), null, 0f))
        assertNull(computeSeam(Direction(0f, 0f, 1f), Position(0f, 0f, 0f), null))
        assertTrue(
            computeSeam(Direction(0f, 0f, 1f), Position(0f, 0f, 0f), 0f) != null
        )
    }

    private fun assertVecEquals(expected: Float3, actual: Float3) {
        assertEquals("x", expected.x, actual.x, eps)
        assertEquals("y", expected.y, actual.y, eps)
        assertEquals("z", expected.z, actual.z, eps)
    }
}
