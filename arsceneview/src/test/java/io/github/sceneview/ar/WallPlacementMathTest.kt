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

    @Test
    fun `direct wall placement preserves hit height and faces either camera side`() {
        val point = Position(0.4f, 1.7f, -2f)
        for (raw in listOf(Direction(0f, 0f, 1f), Direction(0f, 0f, -1f),
            normalize(Direction(1f, 0.08f, 1f)))) {
            for (side in listOf(-1f, 1f)) {
                val viewer = raw * side
                val pose = directWallPose(point, raw, viewer)
                assertEquals(point, pose.position) // No floor height or mount-height input.
                assertVecEquals(viewer, pose.rotation * Direction(0f, 0f, 1f))
                val up = pose.rotation * Direction(0f, 1f, 0f)
                assertTrue(up.y > 0.99f)
                assertEquals(0f, dev.romainguy.kotlin.math.dot(up, viewer), eps)
            }
        }
    }

    @Test
    fun `grab offset uses contact plane even when finger is beyond detected boundary`() {
        val contact = Position(0f, 1f, -2f)
        val offset = wallGrabOffset(contact, Direction(0f, 0f, 1f), Position(0f, 1f, 0f),
            normalize(Direction(0.5f, 0.25f, -2f)))!!
        assertVecEquals(Position(-0.5f, -0.25f, 0f), offset)
        assertNull(wallGrabOffset(contact, Direction(0f, 0f, 1f), Position(0f), Direction(1f, 0f, 0f)))
        assertNull(wallGrabOffset(contact, Direction(0f, 0f, 1f), Position(0f), Direction(0f, 0f, 1f)))
    }

    @Test
    fun `wall contact survives combined twist scale and transfer to another wall`() {
        val point = Position(1f, 1.6f, -2f)
        for (normal in listOf(Direction(0f, 0f, 1f), Direction(-1f, 0f, 0f),
            normalize(Direction(1f, 0.1f, -1f)))) {
            val wall = directWallPose(point, normal, normal)
            val delta = wallTangentOffset(Position(0.2f, -0.1f, 0.3f), normal)
            assertEquals(0f, dev.romainguy.kotlin.math.dot(delta, normal), eps)
            for (angle in listOf(-135f, 0f, 35f, 180f)) for (scale in listOf(0.25f, 1f, 4f)) {
                val twist = dev.romainguy.kotlin.math.Quaternion.fromAxisAngle(Direction(0f, 0f, 1f), angle)
                val orientation = wall.rotation * twist
                // Bottom-back origin stays fixed; every back corner remains exactly on the wall.
                for (x in listOf(-0.15f, 0.15f)) for (y in listOf(0f, 0.176f)) {
                    val back = point + delta + orientation * (Position(x, y, 0f) * scale)
                    assertEquals(0f, dev.romainguy.kotlin.math.dot(back - point, normal), eps)
                    val front = point + delta + orientation * (Position(x, y, 0.012f) * scale)
                    assertTrue(dev.romainguy.kotlin.math.dot(front - point, normal) > 0f)
                }
                assertVecEquals(point + delta, point + delta + orientation * Position(0f))
            }
        }
    }

    @Test
    fun `wall-only candidate consumes one request and recovers without scanning again`() {
        val state = AutoPlacementState()
        state.requestPlacement()
        val usableWall = UsableSurfacePolicy.accept(PlacementSurface.WALL,
            isUpwardHorizontalPlane = false, isVerticalPlane = true,
            isTrackableTracking = true, isPoseInPolygon = true, distanceMeters = 1f)
        assertTrue(usableWall)
        assertEquals(FrameEffect.NONE, state.onFrame(FrameInput(0, true, usableWall)) { false })
        assertFalse(state.hasPlacement) // Failed anchor creation is never a successful placement.
        assertEquals(FrameEffect.PLACE, state.onFrame(FrameInput(1, true, usableWall)))
        repeat(30) { state.onFrame(FrameInput(2L + it, true, true, true)) }
        assertEquals(1, state.placementsCreated)
        assertEquals(FrameEffect.NONE, state.onBackgroundTap())
        state.beginAdjustment()
        state.onFrame(FrameInput(40, false, false, false))
        assertEquals(PlacementPhase.TRACKING_LOST, state.phase)
        assertFalse(state.isAdjusting)
        state.onFrame(FrameInput(41, true, true, false))
        assertEquals(PlacementPhase.RECOVERING, state.phase)
        assertFalse(state.wantsSurface)
        state.onFrame(FrameInput(10_041, true, true, false))
        assertEquals(PlacementPhase.RECOVERY_FAILED, state.phase)
        state.onFrame(FrameInput(10_042, true, true, true))
        assertEquals(PlacementPhase.PLACED, state.phase)
        assertEquals(1, state.placementsCreated)
    }

    @Test
    fun `vertical policy rejects floors invalid geometry paused planes and out of range`() {
        assertFalse(UsableSurfacePolicy.accept(PlacementSurface.WALL, true, false, true, true, 1f))
        assertFalse(UsableSurfacePolicy.accept(PlacementSurface.SURFACE, false, true, true, true, 1f))
        assertFalse(UsableSurfacePolicy.accept(PlacementSurface.WALL, false, true, false, true, 1f))
        assertFalse(UsableSurfacePolicy.accept(PlacementSurface.WALL, false, true, true, false, 1f))
        for (distance in listOf(0.249f, 3.001f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFalse(UsableSurfacePolicy.accept(PlacementSurface.WALL, false, true, true, true, distance))
        }
        for (distance in listOf(0.25f, 3f)) {
            assertTrue(UsableSurfacePolicy.accept(PlacementSurface.WALL, false, true, true, true, distance))
        }
    }

    @Test
    fun `accessibility transforms require selection and tracking and clamp size`() {
        val state = AutoPlacementState()
        var moves = 0
        var rotation = 0f
        var scale = 1f
        state.moveAction = { _, _ -> moves++; true }
        state.rotateAction = { rotation += it }
        state.scaleAction = { scale = it }
        assertFalse(state.moveBy(1f, 0f))
        state.requestPlacement()
        state.onFrame(FrameInput(1, true, true))
        assertTrue(state.moveBy(0.02f, 0f))
        state.rotateBy(2f)
        state.scaleTo(8f)
        assertEquals(4f, scale, eps)
        state.scaleTo(0f)
        assertEquals(0.25f, scale, eps)
        state.scaleTo(Float.NaN)
        assertEquals(0.25f, scale, eps)
        state.deselectPlacement()
        assertFalse(state.moveBy(1f, 0f))
        state.selectPlacement()
        state.onFrame(FrameInput(2, false, false, false))
        assertFalse(state.moveBy(1f, 0f))
        state.rotateBy(20f)
        state.scaleTo(2f)
        assertEquals(1, moves)
        assertEquals(2f, rotation, eps)
        assertEquals(0.25f, scale, eps)
    }

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

    // ── roomFacingNormal ──────────────────────────────────────────────────────────────────────

    @Test
    fun `wall-ward normal is flipped toward the viewer`() {
        // Wall at origin, camera at z=2 (in front). ARCore handed us a normal pointing INTO the
        // wall (-Z): it must be negated so the placed object faces the room.
        val flipped = roomFacingNormal(
            wallNormal = Direction(0f, 0f, -1f),
            towardViewer = Direction(0f, 0f, 2f),
        )
        assertVecEquals(Direction(0f, 0f, 1f), flipped)
    }

    @Test
    fun `room-facing normal is kept as-is`() {
        val kept = roomFacingNormal(
            wallNormal = Direction(0f, 0f, 1f),
            towardViewer = Direction(0.5f, -0.2f, 2f),
        )
        assertVecEquals(Direction(0f, 0f, 1f), kept)
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
