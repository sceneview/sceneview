package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.normalize
import dev.romainguy.kotlin.math.rotation
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #4732: Measure on top of the live walls — wall feet, labels the right way up, refusals, actions. */
class MeasureLiveTest {
    private val floorY = -1.4f

    // ── Label orientation ──────────────────────────────────────────────────────────────────

    private fun front(q: Quaternion): Float3 = rotation(q).z.xyz
    private fun top(q: Quaternion): Float3 = rotation(q).y.xyz

    private fun assertNear(expected: Float3, actual: Float3) {
        assertEquals("x", expected.x, actual.x, 1e-4f)
        assertEquals("y", expected.y, actual.y, 1e-4f)
        assertEquals("z", expected.z, actual.z, 1e-4f)
    }

    @Test
    fun `seen straight from above, the top follows the phone, whichever way it is held`() {
        val label = Position(0f, floorY, 0f)
        val eye = Position(0f, 0f, 0f)
        // The phone's top towards -Z, then turned to face the other way: +Z, then +X.
        for (up in listOf(Float3(0f, 0f, -1f), Float3(0f, 0f, 1f), Float3(1f, 0f, 0f))) {
            val q = measureLabelQuaternion(label, eye, up)
            assertNotNull(q)
            assertNear(Float3(0f, 1f, 0f), front(q!!))
            // A world-up billboard picks a fixed top here; the label must read along the screen.
            assertNear(up, top(q))
        }
    }

    @Test
    fun `seen at an angle, the label faces the eye and keeps the screen's up`() {
        val label = Position(0.5f, floorY, -1f)
        val eye = Position(0f, 0f, 0f)
        val toEye = normalize(Float3(eye.x - label.x, eye.y - label.y, eye.z - label.z))
        val cameraUp = normalize(Float3(0f, 0.6f, -0.8f))
        val q = measureLabelQuaternion(label, eye, cameraUp)!!
        assertNear(toEye, front(q))
        // The top is the screen's up made square to the line of sight: in the up/eye plane, upward.
        assertEquals(0f, dot(top(q), front(q)), 1e-4f)
        assertTrue(dot(top(q), cameraUp) > 0.5f)
    }

    @Test
    fun `phone rolled upside down rolls the label with it`() {
        val label = Position(0f, 0f, -2f)
        val eye = Position(0f, 0f, 0f)
        val q = measureLabelQuaternion(label, eye, Float3(0f, -1f, 0f))!!
        assertNear(Float3(0f, -1f, 0f), top(q))
    }

    @Test
    fun `no orientation on the eye or with the up along the sight line`() {
        assertNull(measureLabelQuaternion(Position(1f, 1f, 1f), Position(1f, 1f, 1f), Float3(0f, 1f, 0f)))
        assertNull(measureLabelQuaternion(Position(0f, -1f, 0f), Position(0f, 0f, 0f), Float3(0f, 1f, 0f)))
    }

    // ── Wall foot ──────────────────────────────────────────────────────────────────────────

    /** A wall 3 m wide, 2 m in front of the camera, running along x. */
    private val wall = LiveWall(id = 1, ax = -1.5f, az = -2f, bx = 1.5f, bz = -2f, bornAt = 0f)

    @Test
    fun `a point at a wall's foot is told, and kept where it was measured`() {
        val point = Position(0.4f, floorY, -1.95f)
        val foot = measureWallFootAt(point, listOf(wall), floorY)
        assertNotNull(foot)
        // Display only: the point is not moved onto the squared wall line (#4750 review).
        assertEquals(point, foot!!.position)
        assertEquals(Position(-1.5f, floorY, -2f), foot.a)
        assertEquals(Position(1.5f, floorY, -2f), foot.b)
    }

    @Test
    fun `a floor point away from the wall, or past its end, is not on its foot`() {
        assertNull(measureWallFootAt(Position(0f, floorY, -1.5f), listOf(wall), floorY))
        assertNull(measureWallFootAt(Position(2f, floorY, -2f), listOf(wall), floorY))
        assertNull(measureWallFootAt(Position(0f, floorY, -2f), emptyList(), floorY))
    }

    @Test
    fun `a point by the wall but off the floor is not on its foot`() {
        // Halfway up the wall, and on a 45 cm low table pushed against it: same plan distance.
        assertNull(measureWallFootAt(Position(0f, floorY + 1.2f, -1.97f), listOf(wall), floorY))
        assertNull(measureWallFootAt(Position(0f, floorY + 0.45f, -1.97f), listOf(wall), floorY))
        // The floor plane a few centimetres off the walls' floor still counts.
        assertNotNull(measureWallFootAt(Position(0f, floorY + 0.04f, -1.97f), listOf(wall), floorY))
        assertNotNull(measureWallFootAt(Position(0f, floorY - 0.04f, -1.97f), listOf(wall), floorY))
    }

    @Test
    fun `the nearest of two walls wins`() {
        val near = LiveWall(id = 2, ax = -1.5f, az = -2.08f, bx = 1.5f, bz = -2.08f, bornAt = 0f)
        val foot = measureWallFootAt(Position(0f, floorY, -2.06f), listOf(wall, near), floorY)!!
        assertEquals(-2.08f, foot.a.z, 1e-4f)
    }

    // ── Raising height (#4751) ─────────────────────────────────────────────────────────────

    @Test
    fun `the raising height is dropped as soon as the aim leaves the wall`() {
        assertNull(measureRaisingHeight(read = null, shown = 2.4f, epsilon = 0.005f))
        assertNull(measureRaisingHeight(read = null, shown = null, epsilon = 0.005f))
    }

    @Test
    fun `the raising height follows the read, without jitter`() {
        assertEquals(2.4f, measureRaisingHeight(read = 2.402f, shown = 2.4f, epsilon = 0.005f)!!, 0f)
        assertEquals(2.5f, measureRaisingHeight(read = 2.5f, shown = 2.4f, epsilon = 0.005f)!!, 0f)
    }

    @Test
    fun `aiming at the floor at the wall's foot locks no height (#4751)`() {
        // Camera 1.5 m up, a 3 m wall 2 m ahead, aiming at the floor where it meets the wall.
        val a = Position(-1.5f, floorY, -2f)
        val b = Position(1.5f, floorY, -2f)
        val eye = Position(0f, floorY + 1.5f, 0f)
        val atFoot = measureWallHeightFromRay(eye, Position(0f, -1.5f, -2f), a, b)
        val read = measureRaisingHeight(read = atFoot, shown = 2.4f, epsilon = 0.005f)
        assertNull(read)
        // Set height is refused and disabled, instead of locking the 5 cm minimum.
        assertEquals(
            MeasureRefusal.OffWall,
            measureRefusal(tracking = true, phase = MeasurePhase.Raising, aimFound = false, heightFound = read != null),
        )
        // Aiming a metre up the same wall reads it.
        assertEquals(1f, measureWallHeightFromRay(eye, Position(0f, -0.5f, -2f), a, b)!!, 1e-4f)
    }

    // ── Closing the shape (#4750 review) ───────────────────────────────────────────────────

    @Test
    fun `tapping on the first point closes the shape, with no new point`() {
        var room = MeasureBoard<Int>(mode = MeasureMode.Room)
        for (p in 1..3) room = room.tap(p, closesOutline = false)
        val closed = room.tap(null, closesOutline = true)
        assertTrue(closed != room)
        assertTrue(closed.figures.last().closed)
        assertEquals(MeasurePhase.Raising, closed.phase)

        var chain = MeasureBoard<Int>(mode = MeasureMode.Distance)
        for (p in 1..3) chain = chain.tap(p, closesOutline = false)
        val loop = chain.tap(null, closesOutline = true)
        assertTrue(loop.figures.last().closed)
        assertEquals(3, loop.figures.last().points.size)
    }

    // ── Refusals and the add button's action ───────────────────────────────────────────────

    @Test
    fun `a refused tap always has a reason`() {
        assertEquals(MeasureRefusal.NotTracking, measureRefusal(false, MeasurePhase.First, true, false))
        assertEquals(MeasureRefusal.NoSurface, measureRefusal(true, MeasurePhase.Next, false, false))
        assertEquals(MeasureRefusal.OffWall, measureRefusal(true, MeasurePhase.Raising, true, false))
        assertNull(measureRefusal(true, MeasurePhase.Raising, false, true))
        assertNull(measureRefusal(true, MeasurePhase.First, true, false))
    }

    @Test
    fun `the button says start, continue, close, set height, then new measure`() {
        var board = MeasureBoard<Int>(mode = MeasureMode.Room)
        assertEquals(MeasureAction.Start, measureAction(board, closes = false))
        board = board.tap(1, false).tap(2, false).tap(3, false)
        assertEquals(MeasureAction.Continue, measureAction(board, closes = false))
        assertEquals(MeasureAction.Close, measureAction(board, closes = true))
        board = board.tap(1, closesOutline = true)
        assertEquals(MeasureAction.SetHeight, measureAction(board, closes = false))
        board = board.lockHeight(2.4f)
        assertEquals(MeasureAction.StartNew, measureAction(board, closes = false))
    }

    @Test
    fun `a distance chain ended with Done makes the next point a new measure`() {
        var board = MeasureBoard<Int>().tap(1, false).tap(2, false)
        assertEquals(MeasureAction.Continue, measureAction(board, closes = false))
        board = board.finish()
        assertEquals(MeasureAction.StartNew, measureAction(board, closes = false))
        // Another mode has no measure of its own yet: a plain start.
        assertEquals(MeasureAction.Start, measureAction(board.withMode(MeasureMode.Wall), closes = false))
    }
}
