package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measure's snapping as it stands, pinned value by value: where a point lands, which wall the
 * reticle reads, how a corner squares, when a loop closes, and which moments buzz.
 *
 * These are characterization tests: they describe today's behaviour, not a wish. Any later
 * change to Measure must leave them green as they are written here.
 */
class MeasureSnappingCharacterizationTest {

    private fun assertAt(expected: Position, actual: Position) {
        assertEquals("x", expected.x, actual.x, 1e-5f)
        assertEquals("y", expected.y, actual.y, 1e-5f)
        assertEquals("z", expected.z, actual.z, 1e-5f)
    }

    // ── Snap radius ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the snap radius is 3_5 cm per metre, from 1_5 cm to 8 cm`() {
        assertEquals(0.015f, measureSnapTolerance(0.2f), 1e-6f)
        assertEquals(0.035f, measureSnapTolerance(1f), 1e-6f)
        assertEquals(0.07f, measureSnapTolerance(2f), 1e-6f)
        assertEquals(0.08f, measureSnapTolerance(3f), 1e-6f)
        assertEquals(0.08f, measureSnapTolerance(10f), 1e-6f)
    }

    // ── Distance: points, corners, edges, guides ────────────────────────────────────────────

    private val tableTop = MeasurePlane(
        MeasurePlaneKind.Upward,
        listOf(
            Position(0f, 0.75f, 0f),
            Position(1f, 0.75f, 0f),
            Position(1f, 0.75f, -0.6f),
            Position(0f, 0.75f, -0.6f),
        ),
    )

    private fun distanceContext(points: List<Position> = emptyList(), previous: Position? = null) =
        measurePlaneSnapTargets(MeasureMode.Distance, listOf(tableTop), level = null)
            .copy(points = points, previous = previous)

    @Test
    fun `distance mode takes every corner and edge of a plane as it is in space`() {
        val context = distanceContext()
        assertEquals(4, context.corners.size)
        assertEquals(4, context.edges.size)
        assertTrue(context.corners.all { it.y == 0.75f })
    }

    @Test
    fun `a corner within the radius wins over the edge it ends`() {
        val snap = snapMeasureTarget(Position(0.02f, 0.75f, -0.01f), distanceContext(), 0.035f)
        assertEquals(MeasureSnapKind.Corner, snap.kind)
        assertAt(Position(0f, 0.75f, 0f), snap.position)
        assertNull(snap.guideFrom)
    }

    @Test
    fun `an edge pulls the point square onto it`() {
        val snap = snapMeasureTarget(Position(0.5f, 0.75f, -0.02f), distanceContext(), 0.035f)
        assertEquals(MeasureSnapKind.Edge, snap.kind)
        assertAt(Position(0.5f, 0.75f, 0f), snap.position)
    }

    @Test
    fun `a placed point wins over a corner, and reaches one and a half radii`() {
        val placed = Position(0.01f, 0.75f, 0f)
        val onCorner = snapMeasureTarget(Position(0.02f, 0.75f, -0.01f), distanceContext(listOf(placed)), 0.035f)
        assertEquals(MeasureSnapKind.Point, onCorner.kind)
        assertAt(placed, onCorner.position)

        val middle = Position(0.5f, 0.75f, -0.3f)
        val reached = snapMeasureTarget(Position(0.55f, 0.75f, -0.3f), distanceContext(listOf(middle)), 0.035f)
        assertEquals(MeasureSnapKind.Point, reached.kind)
        assertAt(middle, reached.position)

        val raw = Position(0.555f, 0.75f, -0.3f)
        val missed = snapMeasureTarget(raw, distanceContext(listOf(middle)), 0.035f)
        assertEquals(MeasureSnapKind.None, missed.kind)
        assertAt(raw, missed.position)
    }

    @Test
    fun `the plumb line keeps the previous point's x and z`() {
        val previous = Position(0.3f, 0f, -1f)
        val snap = snapMeasureTarget(Position(0.31f, 0.6f, -1f), MeasureSnapContext(previous = previous), 0.035f)
        assertEquals(MeasureSnapKind.Vertical, snap.kind)
        assertAt(Position(0.3f, 0.6f, -1f), snap.position)
        assertEquals(previous, snap.guideFrom)
    }

    @Test
    fun `the level keeps the previous point's height`() {
        val previous = Position(0.3f, 0f, -1f)
        val snap = snapMeasureTarget(Position(0.8f, 0.02f, -1.2f), MeasureSnapContext(previous = previous), 0.035f)
        assertEquals(MeasureSnapKind.Level, snap.kind)
        assertAt(Position(0.8f, 0f, -1.2f), snap.position)
        assertEquals(previous, snap.guideFrom)
    }

    @Test
    fun `a guide needs twice the radius of span, and nothing near leaves the hit as it is`() {
        val previous = Position(0.3f, 0f, -1f)
        // Close to the previous point both ways: neither guide.
        val short = Position(0.31f, 0.05f, -1f)
        val near = snapMeasureTarget(short, MeasureSnapContext(previous = previous), 0.035f)
        assertEquals(MeasureSnapKind.None, near.kind)
        assertAt(short, near.position)

        val far = Position(2f, 1f, 2f)
        val none = snapMeasureTarget(far, distanceContext(previous = previous), 0.035f)
        assertEquals(MeasureSnapKind.None, none.kind)
        assertAt(far, none.position)
        assertNull(none.guideFrom)
    }

    // ── Wall and room: alignment on the floor and on walls' feet ────────────────────────────

    private val floor = MeasurePlane(
        MeasurePlaneKind.Upward,
        listOf(
            Position(-2f, 0.02f, -2f),
            Position(2f, 0.02f, -2f),
            Position(2f, 0.02f, 2f),
            Position(-2f, 0.02f, 2f),
        ),
    )
    private val wall = MeasurePlane(
        MeasurePlaneKind.Vertical,
        listOf(
            Position(1f, 0.1f, -3f),
            Position(1f, 2f, -3f),
            Position(-1f, 2f, -3f),
            Position(-1f, 0.1f, -3f),
        ),
    )
    private val raisedTable = tableTop
    private val ceiling = MeasurePlane(
        MeasurePlaneKind.Downward,
        listOf(Position(-2f, 2.5f, -2f), Position(2f, 2.5f, -2f), Position(2f, 2.5f, 2f)),
    )

    @Test
    fun `room mode traces on the floor's level and on the foot of every wall`() {
        val context = measurePlaneSnapTargets(MeasureMode.Room, listOf(floor, wall, raisedTable, ceiling), 0f)
        // The floor's four corners flattened onto the level, and the wall's two foot ends.
        assertEquals(6, context.corners.size)
        assertEquals(5, context.edges.size)
        assertTrue(context.corners.all { it.y == 0f })
        assertTrue(context.corners.contains(Position(-1f, 0f, -3f)))
        assertTrue(context.corners.contains(Position(1f, 0f, -3f)))
        assertTrue(context.edges.contains(Position(-1f, 0f, -3f) to Position(1f, 0f, -3f)))
        assertTrue(context.points.isEmpty())
        assertNull(context.previous)
    }

    @Test
    fun `wall mode reads the same targets as room mode`() {
        assertEquals(
            measurePlaneSnapTargets(MeasureMode.Room, listOf(floor, wall, raisedTable, ceiling), 0f),
            measurePlaneSnapTargets(MeasureMode.Wall, listOf(floor, wall, raisedTable, ceiling), 0f),
        )
    }

    @Test
    fun `without a level a wall or a room has nothing to snap to`() {
        val context = measurePlaneSnapTargets(MeasureMode.Room, listOf(floor, wall), null)
        assertTrue(context.corners.isEmpty())
        assertTrue(context.edges.isEmpty())
    }

    @Test
    fun `a floor patch more than 10 cm off the level is left out`() {
        val off = MeasurePlane(MeasurePlaneKind.Upward, floor.outline.map { Position(it.x, 0.11f, it.z) })
        val near = MeasurePlane(MeasurePlaneKind.Upward, floor.outline.map { Position(it.x, 0.09f, it.z) })
        assertTrue(measurePlaneSnapTargets(MeasureMode.Room, listOf(off), 0f).corners.isEmpty())
        assertEquals(4, measurePlaneSnapTargets(MeasureMode.Room, listOf(near), 0f).corners.size)
    }

    @Test
    fun `a room point locks onto a wall's foot corner, then onto its foot line`() {
        val context = measurePlaneSnapTargets(MeasureMode.Room, listOf(floor, wall), 0f)
        val corner = snapMeasureTarget(Position(0.99f, 0f, -2.98f), context, 0.035f)
        assertEquals(MeasureSnapKind.Corner, corner.kind)
        assertAt(Position(1f, 0f, -3f), corner.position)

        val edge = snapMeasureTarget(Position(0.3f, 0f, -2.98f), context, 0.035f)
        assertEquals(MeasureSnapKind.Edge, edge.kind)
        assertAt(Position(0.3f, 0f, -3f), edge.position)
    }

    @Test
    fun `a first corner aimed on a wall drops onto the floor, any other hit stays`() {
        val hit = Position(0.5f, 0.3f, -3f)
        assertAt(Position(0.5f, 0.02f, -3f), measureFirstFloorPoint(hit, MeasurePlaneKind.Vertical, 0.02f))
        assertAt(hit, measureFirstFloorPoint(hit, MeasurePlaneKind.Vertical, null))
        assertAt(hit, measureFirstFloorPoint(hit, MeasurePlaneKind.Upward, 0.02f))
        assertAt(hit, measureFirstFloorPoint(hit, null, 0.02f))
        assertEquals(0.02f, measureFloorLevel(listOf(floor, raisedTable, wall))!!, 1e-6f)
    }

    @Test
    fun `points after the first come from the floor under the aim`() {
        val hit = measureRayFloorHit(Position(0f, 1.5f, 0f), Position(0f, -1f, -1f), 0f)
        assertNotNull(hit)
        assertAt(Position(0f, 0f, -1.5f), hit!!)
        assertNull(measureRayFloorHit(Position(0f, 1.5f, 0f), Position(0f, 0f, -1f), 0f))
        assertNull(measureRayFloorHit(Position(0f, 1.5f, 0f), Position(0f, -0.1f, -1f), 0f))
    }

    // ── Square corner ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a corner within six degrees of square is squared, keeping its reach`() {
        val from = Position(0f, 0f, 0f)
        val corner = Position(3f, 0f, 0f)
        val squared = snapSquareCorner(from, corner, Position(3.1f, 0f, 2f))
        assertNotNull(squared)
        assertEquals(MeasureSnapKind.Square, squared!!.kind)
        assertAt(Position(3f, 0f, 2f), squared.position)
        assertEquals(corner, squared.guideFrom)

        val otherSide = snapSquareCorner(from, corner, Position(2.9f, 0f, -2f))
        assertAt(Position(3f, 0f, -2f), otherSide!!.position)

        // About 7° off square: left as aimed.
        assertNull(snapSquareCorner(from, corner, Position(3.25f, 0f, 2f)))
    }

    @Test
    fun `a squared corner keeps the aim's height`() {
        val squared = snapSquareCorner(Position(0f, 0f, 0f), Position(3f, 0f, 0f), Position(3.05f, 0.01f, 1.5f))
        assertEquals(0.01f, squared!!.position.y, 1e-6f)
    }

    // ── The wall the reticle reads ──────────────────────────────────────────────────────────

    private val floorY = -1.4f
    private val front = LiveWall(id = 1, ax = -1.5f, az = -2f, bx = 1.5f, bz = -2f, bornAt = 0f)
    private val inner = LiveWall(id = 2, ax = -1.5f, az = -1.9f, bx = 1.5f, bz = -1.9f, bornAt = 0f)

    @Test
    fun `a point within 10 cm of a wall's foot reads that wall and is not moved`() {
        val point = Position(0f, floorY, -1.95f)
        val foot = measureWallFootAt(point, listOf(front), floorY)
        assertNotNull(foot)
        assertEquals(point, foot!!.position)
        assertAt(Position(-1.5f, floorY, -2f), foot.a)
        assertAt(Position(1.5f, floorY, -2f), foot.b)
        assertNull(measureWallFootAt(Position(0f, floorY, -1.88f), listOf(front), floorY))
    }

    @Test
    fun `the nearest wall wins`() {
        val foot = measureWallFootAt(Position(0f, floorY, -1.93f), listOf(front, inner), floorY)
        assertAt(Position(-1.5f, floorY, -1.9f), foot!!.a)
        val other = measureWallFootAt(Position(0f, floorY, -1.97f), listOf(front, inner), floorY)
        assertAt(Position(-1.5f, floorY, -2f), other!!.a)
    }

    @Test
    fun `a wall's foot reaches 10 cm past its ends and 10 cm off the floor`() {
        assertNotNull(measureWallFootAt(Position(1.59f, floorY, -1.97f), listOf(front), floorY))
        assertNull(measureWallFootAt(Position(1.62f, floorY, -1.97f), listOf(front), floorY))
        assertNotNull(measureWallFootAt(Position(0f, floorY + 0.09f, -1.97f), listOf(front), floorY))
        assertNull(measureWallFootAt(Position(0f, floorY + 0.11f, -1.97f), listOf(front), floorY))
        val stub = LiveWall(id = 3, ax = 0f, az = -1.97f, bx = 0.04f, bz = -1.97f, bornAt = 0f)
        assertNull(measureWallFootAt(Position(0.02f, floorY, -1.97f), listOf(stub), floorY))
    }

    // ── Closing a loop ──────────────────────────────────────────────────────────────────────

    private val outline = listOf(Position(0f, 0f, 0f), Position(3f, 0f, 0f), Position(3f, 0f, 4f))

    @Test
    fun `a loop closes on its first point within one and a half radii, once three points are down`() {
        assertTrue(measureClosesOutline(outline, Position(0.05f, 0f, 0f), 0.035f))
        assertFalse(measureClosesOutline(outline, Position(0.06f, 0f, 0f), 0.035f))
        assertFalse(measureClosesOutline(outline.take(2), Position(0f, 0f, 0f), 0.035f))
    }

    private fun board(mode: MeasureMode, vararg points: Position): MeasureBoard<Position> =
        points.fold(MeasureBoard<Position>(mode = mode)) { b, p -> b.tap(p, closesOutline = false) }

    @Test
    fun `closing a room raises its walls, closing a distance outline finishes it`() {
        val room = board(MeasureMode.Room, *outline.toTypedArray()).tap(null, closesOutline = true)
        assertTrue(room.figures.last().closed)
        assertTrue(room.figures.last().raising)
        assertEquals(MeasurePhase.Raising, room.phase)
        assertEquals(3, room.figures.last().points.size)

        val distance = board(MeasureMode.Distance, *outline.toTypedArray()).tap(null, closesOutline = true)
        assertTrue(distance.figures.last().closed)
        assertFalse(distance.figures.last().raising)
        assertTrue(distance.figures.last().finished)
        assertEquals(MeasurePhase.First, distance.phase)
    }

    @Test
    fun `two points never close, and a wall never closes`() {
        val two = board(MeasureMode.Room, outline[0], outline[1])
        assertEquals(two, two.tap(null, closesOutline = true))

        val wallStart = board(MeasureMode.Wall, outline[0]).tap(outline[1], closesOutline = true)
        assertFalse(wallStart.figures.last().closed)
        assertTrue(wallStart.figures.last().raising)
        assertEquals(2, wallStart.figures.last().points.size)
    }

    @Test
    fun `a locked height is held between 5 cm and 4_5 m`() {
        val raising = board(MeasureMode.Wall, outline[0], outline[1])
        assertEquals(2.4f, raising.lockHeight(2.4f).figures.last().height!!, 1e-6f)
        assertEquals(4.5f, raising.lockHeight(6f).figures.last().height!!, 1e-6f)
        assertEquals(0.05f, raising.lockHeight(0.01f).figures.last().height!!, 1e-6f)
    }

    // ── The moments that buzz ───────────────────────────────────────────────────────────────
    //
    // The screen ticks (selection) whenever the reticle locks — any snap kind but None — or
    // sits on the first point of an open loop; a tap that closes buzzes success and any other
    // accepted point a light tap; Set height and Done buzz success; a refused tap buzzes a
    // warning. These pin the states each of those moments is read from.

    @Test
    fun `the reticle ticks on every lock and not on a free hit`() {
        val kinds = listOf(
            snapMeasureTarget(Position(0.02f, 0.75f, -0.01f), distanceContext(), 0.035f).kind,
            snapMeasureTarget(Position(0.5f, 0.75f, -0.02f), distanceContext(), 0.035f).kind,
            snapMeasureTarget(Position(2f, 1f, 2f), distanceContext(), 0.035f).kind,
        )
        assertEquals(listOf(MeasureSnapKind.Corner, MeasureSnapKind.Edge, MeasureSnapKind.None), kinds)
        assertEquals(listOf(true, true, false), kinds.map { it != MeasureSnapKind.None })
    }

    @Test
    fun `the add button's action tells a closing tap from any other`() {
        val empty = MeasureBoard<Position>(mode = MeasureMode.Room)
        assertEquals(MeasureAction.Start, measureAction(empty, closes = false))
        val open = board(MeasureMode.Room, *outline.toTypedArray())
        assertEquals(MeasureAction.Continue, measureAction(open, closes = false))
        assertEquals(MeasureAction.Close, measureAction(open, closes = true))
        val raising = open.tap(null, closesOutline = true)
        assertEquals(MeasureAction.SetHeight, measureAction(raising, closes = false))
        val locked = raising.lockHeight(2.5f)
        assertEquals(MeasureAction.StartNew, measureAction(locked, closes = false))
    }

    @Test
    fun `a tap is refused, with a warning, when lost, off every surface, or off the wall`() {
        assertEquals(MeasureRefusal.NotTracking, measureRefusal(false, MeasurePhase.Next, true, true))
        assertEquals(MeasureRefusal.NoSurface, measureRefusal(true, MeasurePhase.Next, false, false))
        assertEquals(MeasureRefusal.OffWall, measureRefusal(true, MeasurePhase.Raising, true, false))
        assertNull(measureRefusal(true, MeasurePhase.Raising, false, true))
        assertNull(measureRefusal(true, MeasurePhase.First, true, false))
    }

    @Test
    fun `done is offered only on a distance chain with a segment`() {
        assertFalse(board(MeasureMode.Distance, outline[0]).canFinish)
        assertTrue(board(MeasureMode.Distance, outline[0], outline[1]).canFinish)
        assertFalse(board(MeasureMode.Room, outline[0], outline[1]).canFinish)
    }
}
