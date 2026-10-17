package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Snapping, rising walls, areas, units, the board's flow and the share text of `ar-measure`. */
class MeasureGeometryTest {

    private fun p(x: Float, y: Float, z: Float) = Position(x = x, y = y, z = z)

    // ── Units ───────────────────────────────────────────────────────────────

    @Test
    fun `locale picks imperial only where feet are used`() {
        assertEquals(MeasureUnitSystem.Imperial, MeasureUnitSystem.forLocale(Locale.US))
        assertEquals(MeasureUnitSystem.Metric, MeasureUnitSystem.forLocale(Locale.UK))
        assertEquals(MeasureUnitSystem.Metric, MeasureUnitSystem.forLocale(Locale.FRANCE))
        assertEquals(MeasureUnitSystem.Imperial, MeasureUnitSystem.forLocale(Locale("en", "LR")))
    }

    @Test
    fun `metric lengths read like a tape`() {
        val m = MeasureUnitSystem.Metric
        assertEquals("42 cm", formatMeasureLength(0.4249f, m, Locale.US))
        assertEquals("1.42 m", formatMeasureLength(1.421f, m, Locale.US))
        assertEquals("1,42 m", formatMeasureLength(1.421f, m, Locale.FRANCE))
        // 99.6 cm rounds to a full metre and switches unit instead of printing "100 cm".
        assertEquals("1.00 m", formatMeasureLength(0.996f, m, Locale.US))
        assertEquals("0 cm", formatMeasureLength(-0.2f, m, Locale.US))
    }

    @Test
    fun `imperial lengths in feet and inches`() {
        val i = MeasureUnitSystem.Imperial
        assertEquals("11 in", formatMeasureLength(0.2794f, i, Locale.US))
        assertEquals("3 ft 4 in", formatMeasureLength(1.016f, i, Locale.US))
        assertEquals("3 ft", formatMeasureLength(0.9144f, i, Locale.US))
    }

    @Test
    fun `areas in square metres or square feet`() {
        assertEquals("3.24 m²", formatMeasureArea(3.24f, MeasureUnitSystem.Metric, Locale.US))
        assertEquals("12.5 m²", formatMeasureArea(12.5f, MeasureUnitSystem.Metric, Locale.US))
        assertEquals("108 ft²", formatMeasureArea(10f, MeasureUnitSystem.Imperial, Locale.US))
        assertEquals("5.4 ft²", formatMeasureArea(0.5f, MeasureUnitSystem.Imperial, Locale.US))
    }

    // ── Snapping ────────────────────────────────────────────────────────────

    @Test
    fun `existing point wins over everything`() {
        val corner = p(0.02f, 0f, 0f)
        val point = p(0.01f, 0f, 0.01f)
        val snap = snapMeasureTarget(
            p(0.012f, 0f, 0.012f),
            MeasureSnapContext(points = listOf(point), corners = listOf(corner)),
            tolerance = 0.03f,
        )
        assertEquals(MeasureSnapKind.Point, snap.kind)
        assertEquals(point, snap.position)
    }

    @Test
    fun `corner then edge then raw`() {
        val edge = p(0f, 0f, 0f) to p(1f, 0f, 0f)
        val context = MeasureSnapContext(corners = listOf(p(1f, 0f, 0f)), edges = listOf(edge))
        assertEquals(MeasureSnapKind.Corner, snapMeasureTarget(p(0.99f, 0f, 0.01f), context, 0.03f).kind)
        val onEdge = snapMeasureTarget(p(0.5f, 0f, 0.02f), context, 0.03f)
        assertEquals(MeasureSnapKind.Edge, onEdge.kind)
        assertEquals(0f, onEdge.position.z, 1e-6f)
        assertEquals(0.5f, onEdge.position.x, 1e-6f)
        assertEquals(MeasureSnapKind.None, snapMeasureTarget(p(0.5f, 0f, 0.2f), context, 0.03f).kind)
    }

    @Test
    fun `vertical guide locks x and z to the previous point`() {
        val previous = p(1f, 0f, 1f)
        val snap = snapMeasureTarget(p(1.02f, 0.8f, 0.99f), MeasureSnapContext(previous = previous), 0.03f)
        assertEquals(MeasureSnapKind.Vertical, snap.kind)
        assertEquals(p(1f, 0.8f, 1f), snap.position)
        assertEquals(previous, snap.guideFrom)
    }

    @Test
    fun `level guide locks the previous height`() {
        val previous = p(0f, 0.75f, 0f)
        val snap = snapMeasureTarget(p(1f, 0.77f, 0.3f), MeasureSnapContext(previous = previous), 0.03f)
        assertEquals(MeasureSnapKind.Level, snap.kind)
        assertEquals(0.75f, snap.position.y, 1e-6f)
    }

    @Test
    fun `snap tolerance grows with distance and is clamped`() {
        assertEquals(0.015f, measureSnapTolerance(0.1f), 1e-6f)
        assertEquals(0.07f, measureSnapTolerance(2f), 1e-6f)
        assertEquals(0.08f, measureSnapTolerance(10f), 1e-6f)
    }

    @Test
    fun `outline corners skip near collinear vertices`() {
        // A 1 m square with an extra vertex in the middle of one side.
        val outline = listOf(p(0f, 0f, 0f), p(0.5f, 0f, 0.001f), p(1f, 0f, 0f), p(1f, 0f, 1f), p(0f, 0f, 1f))
        val corners = measureOutlineCorners(outline)
        assertEquals(4, corners.size)
        assertFalse(corners.contains(p(0.5f, 0f, 0.001f)))
        assertEquals(5, measureOutlineEdges(outline).size)
    }

    // ── Rising walls ────────────────────────────────────────────────────────

    @Test
    fun `aim height on a wall comes from the ray plane hit`() {
        // Wall along x at z = -2, camera at 1.5 m looking slightly up at it.
        val height = measureWallHeightFromRay(
            origin = p(0.5f, 1.5f, 0f),
            direction = p(0f, 0.5f, -2f),
            a = p(0f, 0f, -2f),
            b = p(2f, 0f, -2f),
        )
        assertNotNull(height)
        assertEquals(2f, height!!, 1e-4f)
    }

    @Test
    fun `aim height is clamped and rejects parallel or backward rays`() {
        val a = p(0f, 0f, -2f)
        val b = p(2f, 0f, -2f)
        assertEquals(WALL_MIN_HEIGHT, measureWallHeightFromRay(p(1f, 1f, 0f), p(0f, -1f, -1f), a, b)!!, 1e-6f)
        assertEquals(WALL_MAX_HEIGHT, measureWallHeightFromRay(p(1f, 1f, 0f), p(0f, 10f, -1f), a, b)!!, 1e-6f)
        assertNull(measureWallHeightFromRay(p(1f, 1f, 0f), p(1f, 0f, 0f), a, b))
        assertNull(measureWallHeightFromRay(p(1f, 1f, 0f), p(0f, 0f, 1f), a, b))
        // Far past the wall's end.
        assertNull(measureWallHeightFromRay(p(1f, 1f, 0f), p(3f, 0f, -1f), a, b))
    }

    @Test
    fun `room height follows the nearest wall crossed`() {
        val square = listOf(p(-2f, 0f, -2f), p(2f, 0f, -2f), p(2f, 0f, 2f), p(-2f, 0f, 2f))
        val height = measureRoomHeightFromRay(p(0f, 1.5f, 0f), p(0f, 1f, -2f), square, closed = true)
        assertEquals(2.5f, height!!, 1e-4f)
    }

    @Test
    fun `wall vertices are four per wall and rise by the height`() {
        val square = listOf(p(0f, 0f, 0f), p(1f, 0f, 0f), p(1f, 0f, 1f), p(0f, 0f, 1f))
        val closed = measureWallVertices(square, closed = true, height = 2f)
        assertEquals(16, closed.size)
        assertEquals(p(1f, 2f, 0f), closed[2])
        assertEquals(12, measureWallVertices(square, closed = false, height = 2f).size)
        assertArrayEquals(intArrayOf(0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7), measureWallIndices(2))
    }

    // ── Areas ───────────────────────────────────────────────────────────────

    @Test
    fun `polygon area in any orientation`() {
        val wall = listOf(p(0f, 0f, 0f), p(3f, 0f, 0f), p(3f, 2f, 0f), p(0f, 2f, 0f))
        assertEquals(6f, measurePolygonArea(wall), 1e-4f)
        val floor = listOf(p(0f, 0f, 0f), p(4f, 0f, 0f), p(4f, 0f, 3f), p(0f, 0f, 3f))
        assertEquals(12f, measureFloorArea(floor), 1e-4f)
        assertEquals(12f, measureFloorArea(floor.reversed()), 1e-4f)
    }

    @Test
    fun `an L shaped room triangulates into its true area`() {
        // 4x4 minus a 2x2 notch: 12 m², the shape a rectangle model reads as 16.
        val l = listOf(
            p(0f, 0f, 0f), p(4f, 0f, 0f), p(4f, 0f, 2f), p(2f, 0f, 2f), p(2f, 0f, 4f), p(0f, 0f, 4f),
        )
        assertEquals(12f, measureFloorArea(l), 1e-4f)
        for (outline in listOf(l, l.reversed())) {
            val indices = measureTriangulateXZ(outline)
            assertEquals((outline.size - 2) * 3, indices.size)
            var sum = 0f
            for (t in indices.indices step 3) {
                sum += measurePolygonArea(listOf(outline[indices[t]], outline[indices[t + 1]], outline[indices[t + 2]]))
            }
            assertEquals(12f, sum, 1e-4f)
        }
    }

    @Test
    fun `closing tap needs three points and the first point under the reticle`() {
        val outline = listOf(p(0f, 0f, 0f), p(1f, 0f, 0f), p(1f, 0f, 1f))
        assertTrue(measureClosesOutline(outline, p(0.01f, 0f, 0f), 0.03f))
        assertFalse(measureClosesOutline(outline, p(0.5f, 0f, 0f), 0.03f))
        assertFalse(measureClosesOutline(outline.take(2), p(0f, 0f, 0f), 0.03f))
    }

    @Test
    fun `labels scale with distance within bounds`() {
        assertEquals(0.45f, measureLabelScale(0.1f), 1e-6f)
        assertEquals(1.5f, measureLabelScale(2f), 1e-6f)
        assertEquals(3f, measureLabelScale(20f), 1e-6f)
    }

    @Test
    fun `square guide squares a floor corner within six degrees`() {
        val from = p(0f, 0f, 0f)
        val corner = p(2f, 0f, 0f)
        // 3 degrees off a right angle, 1.5 m long.
        val snap = snapSquareCorner(from, corner, p(2.0785f, 0f, 1.4979f))
        assertNotNull(snap)
        assertEquals(MeasureSnapKind.Square, snap!!.kind)
        assertEquals(2f, snap.position.x, 1e-4f)
        assertEquals(1.4979f, snap.position.z, 1e-3f)
        // Other side of the wall.
        assertEquals(-1f, snapSquareCorner(from, corner, p(2.02f, 0f, -1f))!!.position.z, 1e-3f)
        // 20 degrees off: left alone.
        assertNull(snapSquareCorner(from, corner, p(2.5f, 0f, 1.4f)))
    }

    @Test
    fun `floor hit is where the ray meets the floor`() {
        val hit = measureRayFloorHit(p(0f, 1.5f, 0f), p(0f, -1.5f, -2f), floorY = 0f)
        assertEquals(p(0f, 0f, -2f), hit)
        assertNull(measureRayFloorHit(p(0f, 1.5f, 0f), p(0f, 0.2f, -1f), floorY = 0f))
        assertNull(measureRayFloorHit(p(0f, 1.5f, 0f), p(0f, -0.01f, -1f), floorY = 0f))
    }

    // ── Board flow ──────────────────────────────────────────────────────────

    @Test
    fun `distance mode chains points and closes on the first one`() {
        var board = MeasureBoard<Int>()
        assertEquals(MeasurePhase.First, board.phase)
        board = board.tap(1, false).tap(2, false).tap(3, false)
        assertEquals(MeasurePhase.Next, board.phase)
        assertEquals(listOf(1, 2, 3), board.active!!.points)
        board = board.tap(1, closesOutline = true)
        assertTrue(board.figures.single().closed)
        // Closed figure is finished: the next tap starts a new chain.
        assertNull(board.active)
        board = board.tap(4, false)
        assertEquals(2, board.figures.size)
    }

    @Test
    fun `wall mode rises after the second base point and locks on the next tap`() {
        var board = MeasureBoard<Int>(mode = MeasureMode.Wall).tap(1, false).tap(2, false)
        assertEquals(MeasurePhase.Raising, board.phase)
        // A tap while raising does not add a point.
        assertEquals(board, board.tap(3, false))
        board = board.lockHeight(2.4f)
        val wall = board.figures.single()
        assertEquals(2.4f, wall.height!!, 1e-6f)
        assertTrue(wall.finished)
        assertEquals(MeasurePhase.First, board.phase)
    }

    @Test
    fun `room mode closes then raises then locks`() {
        var board = MeasureBoard<Int>(mode = MeasureMode.Room).tap(1, false).tap(2, false)
        // Two points cannot close a room.
        assertEquals(board.tap(1, true).active!!.points, listOf(1, 2, 1))
        board = board.tap(3, false).tap(1, closesOutline = true)
        assertEquals(MeasurePhase.Raising, board.phase)
        board = board.lockHeight(9f)
        assertEquals(WALL_MAX_HEIGHT, board.figures.single().height!!, 1e-6f)
    }

    @Test
    fun `undo steps back one action at a time`() {
        var board = MeasureBoard<Int>(mode = MeasureMode.Room)
            .tap(1, false).tap(2, false).tap(3, false).tap(1, true).lockHeight(2.5f)
        board = board.undo()
        assertEquals(MeasurePhase.Raising, board.phase)
        board = board.undo()
        assertFalse(board.figures.single().closed)
        assertEquals(MeasurePhase.Next, board.phase)
        board = board.undo().undo().undo()
        assertTrue(board.figures.isEmpty())
        assertEquals(board, board.undo())

        val wall = MeasureBoard<Int>(mode = MeasureMode.Wall).tap(1, false).tap(2, false).undo()
        assertEquals(listOf(1), wall.active!!.points)
        assertEquals(MeasurePhase.Next, wall.phase)
    }

    @Test
    fun `switching mode keeps finished figures and undo returns to their mode`() {
        val board = MeasureBoard<Int>().tap(1, false).tap(2, false).withMode(MeasureMode.Wall)
        assertNull(board.active)
        assertEquals(listOf(1, 2), board.allPoints)
        assertEquals(MeasureMode.Distance, board.undo().mode)
        assertTrue(board.clear().isEmpty)
    }

    // ── Report ──────────────────────────────────────────────────────────────

    @Test
    fun `room numbers and share text`() {
        val outline = measureFigureOutline(
            MeasureMode.Room,
            listOf(p(0f, 0f, 0f), p(4f, 0.03f, 0f), p(4f, -0.02f, 3f), p(0f, 0f, 3f)),
        )
        assertTrue(outline.all { it.y == 0f })
        val figure = MeasureFigure(MeasureMode.Room, listOf(1, 2, 3, 4), closed = true, height = 2.5f)
        val numbers = measureFigureNumbers(figure, outline)
        assertEquals(14f, numbers.length, 1e-4f)
        assertEquals(12f, numbers.outlineArea!!, 1e-4f)
        assertEquals(35f, numbers.wallArea!!, 1e-4f)
        val text = measureShareText(listOf(numbers), MeasureUnitSystem.Metric, Locale.US)
        assertTrue(text.contains("Floor area: 12.0 m²"))
        assertTrue(text.contains("Ceiling height: 2.50 m"))
        assertTrue(text.contains("Wall 4: 3.00 m"))
    }

    @Test
    fun `raising wall reports the live height`() {
        val figure = MeasureFigure(MeasureMode.Wall, listOf(1, 2), raising = true)
        val numbers = measureFigureNumbers(figure, listOf(p(0f, 0f, 0f), p(3f, 0f, 0f)), liveHeight = 2f)
        assertEquals(2f, numbers.height!!, 1e-6f)
        assertEquals(6f, numbers.wallArea!!, 1e-4f)
        assertNull(numbers.outlineArea)
    }
}
