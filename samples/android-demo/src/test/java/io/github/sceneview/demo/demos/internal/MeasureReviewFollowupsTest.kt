package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The review follow-ups of the Measure rebuild (#4600): snapping on the floor's and the walls'
 * real outlines, a wall's foot as a first corner, Earcut floors, the wall picked along the aim,
 * and Done ending a Distance chain.
 */
class MeasureReviewFollowupsTest {

    private fun p(x: Float, y: Float, z: Float) = Position(x = x, y = y, z = z)

    private val floorY = -1.4f

    /** A 3 × 2 m floor patch, as ARCore tracks it. */
    private val floor = MeasurePlane(
        MeasurePlaneKind.Upward,
        listOf(p(0f, floorY, 0f), p(3f, floorY, 0f), p(3f, floorY, 2f), p(0f, floorY, 2f)),
    )

    /** A wall standing on x = 3, from z = 0 to z = 2, its polygon starting 20 cm above the floor. */
    private val wall = MeasurePlane(
        MeasurePlaneKind.Vertical,
        listOf(p(3f, floorY + 0.2f, 0f), p(3f, floorY + 0.2f, 2f), p(3f, floorY + 1.8f, 2f), p(3f, floorY + 1.8f, 0f)),
    )

    /** A table top 75 cm above the floor. */
    private val table = MeasurePlane(
        MeasurePlaneKind.Upward,
        listOf(
            p(1f, floorY + 0.75f, 0.5f), p(2f, floorY + 0.75f, 0.5f),
            p(2f, floorY + 0.75f, 1f), p(1f, floorY + 0.75f, 1f),
        ),
    )

    private val tolerance = 0.05f

    // ── P2-1: snapping on floor and wall outlines ──────────────────────────────────────────

    @Test
    fun `distance mode snaps to the floor edge and to a wall edge, not only a raised table`() {
        val targets = measurePlaneSnapTargets(MeasureMode.Distance, listOf(floor, wall, table), level = null)
        // 2 cm inside the floor's far edge.
        val onFloorEdge = snapMeasureTarget(p(1.5f, floorY, 1.98f), targets, tolerance)
        assertEquals(MeasureSnapKind.Edge, onFloorEdge.kind)
        assertEquals(2f, onFloorEdge.position.z, 1e-5f)
        // 2 cm off the wall's top edge, in mid-air in front of it.
        val onWallEdge = snapMeasureTarget(p(2.98f, floorY + 1.79f, 1f), targets, tolerance)
        assertEquals(MeasureSnapKind.Edge, onWallEdge.kind)
        assertEquals(p(3f, floorY + 1.8f, 1f), onWallEdge.position)
        // The floor's corner.
        assertEquals(MeasureSnapKind.Corner, snapMeasureTarget(p(0.02f, floorY, 0.01f), targets, tolerance).kind)
    }

    @Test
    fun `wall and room modes snap to the floor outline and to the foot of a wall, on the floor`() {
        val targets = measurePlaneSnapTargets(MeasureMode.Room, listOf(floor, wall, table), level = floorY)
        // The wall's foot is its polygon dropped to the floor: corners at its two ends.
        assertTrue(targets.corners.contains(p(3f, floorY, 0f)))
        assertTrue(targets.corners.contains(p(3f, floorY, 2f)))
        // Aiming 3 cm in front of the wall's foot, half way along it.
        val foot = snapMeasureTarget(p(2.97f, floorY, 1f), targets, tolerance)
        assertEquals(MeasureSnapKind.Edge, foot.kind)
        assertEquals(p(3f, floorY, 1f), foot.position)
        // The floor patch's own corner, far from the wall.
        val corner = snapMeasureTarget(p(0.02f, floorY, 1.99f), targets, tolerance)
        assertEquals(MeasureSnapKind.Corner, corner.kind)
        assertEquals(p(0f, floorY, 2f), corner.position)
        // Every target lies on the floor; the table top, 75 cm up, gives none.
        assertTrue(targets.corners.all { it.y == floorY })
        assertFalse(targets.corners.any { it.x == 1f && it.z == 0.5f })
    }

    @Test
    fun `only tracked polygons are snapped to, and the radius is the same for every one`() {
        // Nothing tracked: nothing to lock onto, however close a "corner" would be.
        val none = measurePlaneSnapTargets(MeasureMode.Room, emptyList(), level = floorY)
        assertEquals(MeasureSnapKind.None, snapMeasureTarget(p(0.01f, floorY, 0.01f), none, tolerance).kind)
        // Nothing to trace on yet in Wall mode: no target either.
        assertTrue(measurePlaneSnapTargets(MeasureMode.Wall, listOf(floor, wall), level = null).edges.isEmpty())
        // Just outside the radius of a wall foot and of a floor edge alike.
        val targets = measurePlaneSnapTargets(MeasureMode.Wall, listOf(floor, wall), level = floorY)
        assertEquals(MeasureSnapKind.None, snapMeasureTarget(p(2.94f, floorY, 1f), targets, tolerance).kind)
        assertEquals(MeasureSnapKind.None, snapMeasureTarget(p(1.5f, floorY, 0.06f), targets, tolerance).kind)
    }

    @Test
    fun `a wall foot is the two horizontal ends of its polygon`() {
        val foot = measureWallFootprint(wall.outline, floorY)
        assertNotNull(foot)
        val ends = setOf(foot!!.first, foot.second)
        assertEquals(setOf(p(3f, floorY, 0f), p(3f, floorY, 2f)), ends)
        // A sliver too narrow to be a wall has no foot.
        assertNull(measureWallFootprint(listOf(p(0f, 0f, 0f), p(0.01f, 1f, 0f), p(0f, 2f, 0f)), floorY))
    }

    // ── P2-2: a first corner on a wall's foot ──────────────────────────────────────────────

    @Test
    fun `the floor is the lowest upward plane`() {
        assertEquals(floorY, measureFloorLevel(listOf(table, wall, floor))!!, 1e-6f)
        assertNull(measureFloorLevel(listOf(wall)))
    }

    @Test
    fun `a first corner aimed on a wall drops to the floor when one is tracked`() {
        val wallHit = p(3f, floorY + 0.12f, 1f)
        assertEquals(p(3f, floorY, 1f), measureFirstFloorPoint(wallHit, MeasurePlaneKind.Vertical, floorY))
        // No tracked floor yet: the wall hit is kept as it is, never guessed.
        assertEquals(wallHit, measureFirstFloorPoint(wallHit, MeasurePlaneKind.Vertical, null))
        // A floor hit and a depth hit are kept.
        val floorHit = p(1f, floorY, 1f)
        assertEquals(floorHit, measureFirstFloorPoint(floorHit, MeasurePlaneKind.Upward, floorY))
        assertEquals(wallHit, measureFirstFloorPoint(wallHit, null, floorY))
    }

    // ── P2-3: Earcut floors ────────────────────────────────────────────────────────────────

    private fun triangulatedArea(outline: List<Position>): Float {
        val indices = measureTriangulateXZ(outline)
        var sum = 0f
        for (t in indices.indices step 3) {
            sum += measurePolygonArea(listOf(outline[indices[t]], outline[indices[t + 1]], outline[indices[t + 2]]))
        }
        return sum
    }

    @Test
    fun `earcut fills an L shaped room with the same area as before`() {
        // 4 × 4 minus a 2 × 2 notch: 12 m², what the old ear clipper filled too.
        val l = listOf(p(0f, 0f, 0f), p(4f, 0f, 0f), p(4f, 0f, 2f), p(2f, 0f, 2f), p(2f, 0f, 4f), p(0f, 0f, 4f))
        for (outline in listOf(l, l.reversed())) {
            assertEquals(12f, measureFloorArea(outline), 1e-4f)
            assertEquals(4 * 3, measureTriangulateXZ(outline).size)
            assertEquals(12f, triangulatedArea(outline), 1e-4f)
        }
    }

    @Test
    fun `earcut fills a concave pentagon with the same area as before`() {
        // A 3 × 2 m rectangle with a notch pushed 1 m in from its far side: 6 − 1.5 = 4.5 m².
        val pentagon = listOf(p(0f, 0f, 0f), p(3f, 0f, 0f), p(3f, 0f, 2f), p(1.5f, 0f, 1f), p(0f, 0f, 2f))
        for (outline in listOf(pentagon, pentagon.reversed())) {
            assertEquals(4.5f, measureFloorArea(outline), 1e-4f)
            assertEquals(3 * 3, measureTriangulateXZ(outline).size)
            assertEquals(4.5f, triangulatedArea(outline), 1e-4f)
        }
        assertEquals(0, measureTriangulateXZ(pentagon.take(2)).size)
    }

    // ── P3-1: the wall along the aim ───────────────────────────────────────────────────────

    @Test
    fun `room height comes from the wall the aim crosses first, not the nearest middle`() {
        // A long wall 2 m ahead whose middle is 10 m away, and a short wall 4 m ahead, behind it.
        val outline = listOf(p(-20f, 0f, -2f), p(0.5f, 0f, -2f), p(0.5f, 0f, -4f), p(-0.5f, 0f, -4f))
        val height = measureRoomHeightFromRay(p(0f, 1.5f, 0f), p(0f, 0.25f, -1f), outline, closed = false)
        // The near wall at t = 2 is reached at 1.5 + 0.5; the far one would read 2.5.
        assertEquals(2f, height!!, 1e-4f)
    }

    // ── P3-2: Done ends a Distance chain ───────────────────────────────────────────────────

    @Test
    fun `done ends the chain and the next point starts a new one`() {
        var board = MeasureBoard<Position>()
        assertFalse(board.canFinish)
        board = board.tap(p(0f, 0f, 0f), closesOutline = false)
        assertFalse(board.canFinish) // one point is no segment yet
        board = board.tap(p(1f, 0f, 0f), closesOutline = false)
        assertTrue(board.canFinish)
        board = board.finish()
        assertFalse(board.canFinish)
        assertEquals(MeasurePhase.First, board.phase)
        board = board.tap(p(5f, 0f, 0f), closesOutline = false)
        assertEquals(2, board.figures.size)
        assertEquals(2, board.figures[0].points.size)
        assertEquals(listOf(p(5f, 0f, 0f)), board.figures[1].points)
    }

    @Test
    fun `undo after done resumes the chain, and walls have no done`() {
        var board = MeasureBoard<Position>()
            .tap(p(0f, 0f, 0f), closesOutline = false)
            .tap(p(1f, 0f, 0f), closesOutline = false)
            .finish()
            .undo()
        assertTrue(board.canFinish)
        board = board.tap(p(2f, 0f, 0f), closesOutline = false)
        assertEquals(1, board.figures.size)
        assertEquals(3, board.figures[0].points.size)

        val wall = MeasureBoard<Position>(mode = MeasureMode.Room)
            .tap(p(0f, 0f, 0f), closesOutline = false)
            .tap(p(1f, 0f, 0f), closesOutline = false)
        assertFalse(wall.canFinish)
        assertEquals(wall, wall.finish())
    }
}
