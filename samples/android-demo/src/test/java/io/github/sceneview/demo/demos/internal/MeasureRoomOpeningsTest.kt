package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Doors and windows in Room mode, as in Wall mode: the same two taps on opposite corners, on one
 * of the room's walls. The walls' net area leaves them out; the floor, the volume and the plan
 * stay what they were.
 *
 * On a room, an opening is kept on the wall it was marked on, along that wall from its start as
 * it was traced: the walls before it may be re-measured longer or shorter, it stays as it was.
 */
class MeasureRoomOpeningsTest {
    private fun p(x: Float, z: Float) = Position(x, 0f, z)

    // A 4 × 3 m room traced from (0, 0): its walls are counted from the first one, (0, 0) to
    // (4, 0), to the last one, back to the first corner. 2.5 m high.
    private val corners = listOf(p(0f, 0f), p(4f, 0f), p(4f, 3f), p(0f, 3f))

    /** The opening [from]..[to] metres along [wall] from its start, [bottom]..[top] up. */
    private fun opening(wall: Int, from: Float, to: Float, bottom: Float, top: Float): MeasureOpening =
        measureOpening(MeasureWallPoint(from, bottom, wall), MeasureWallPoint(to, top, wall))!!

    // A door 0.9 × 2.1 m on the second wall, half a metre from its start; a window 1.2 × 1 m
    // on the third, 1 m off the floor.
    private val door = opening(wall = 1, from = 0.5f, to = 1.4f, bottom = 0f, top = 2.1f)
    private val window = opening(wall = 2, from = 0.5f, to = 1.7f, bottom = 1f, top = 2f)

    private fun traced(): MeasureBoard<Position> =
        corners.fold(MeasureBoard<Position>(mode = MeasureMode.Room)) { board, corner -> board.tap(corner, false) }
            .tap(null, closesOutline = true)

    private fun room(height: Float = 2.5f): MeasureBoard<Position> = traced().lockHeight(height)

    // The add button with the aim on a wall at [point], as the screen plays it while marking:
    // the first tap places a corner, the second adds the opening between the two.
    private fun MeasureBoard<Position>.tapOnWall(point: MeasureWallPoint): MeasureBoard<Position> {
        if (!markingOpening || openingWall == null) return this
        val first = openingCorner ?: return withOpeningCorner(point)
        return measureOpening(first, point)?.let { addOpening(it).stopOpening() } ?: this
    }

    private fun MeasureBoard<Position>.numbers(): MeasureFigureNumbers {
        val figure = figures.last()
        return measureFigureNumbers(figure, measureFigureOutline(figure.mode, figure.points))
    }

    // ── The gesture ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a raised room takes a door by two opposite corners, as a wall does`() {
        var board = room()
        assertSame(board.figures.last(), board.openingWall)
        board = board.startOpening()
            .tapOnWall(MeasureWallPoint(along = 1.4f, up = 2.1f, wall = 1))
        assertEquals(MeasureWallPoint(1.4f, 2.1f, wall = 1), board.openingCorner)
        board = board.tapOnWall(MeasureWallPoint(along = 0.5f, up = 0f, wall = 1))
        assertEquals(listOf(door), board.figures.last().openings)
        assertFalse(board.markingOpening)
        assertNull(board.openingCorner)
        // The room stays finished: the shutter still starts a new measure.
        assertEquals(MeasureAction.StartNew, measureAction(board, closes = false))
    }

    @Test
    fun `no opening before the room is closed and raised, nor from another mode`() {
        val open = corners.take(3).fold(MeasureBoard<Position>(mode = MeasureMode.Room)) { b, c -> b.tap(c, false) }
        assertNull(open.openingWall)
        assertSame(open, open.addOpening(door))
        val raising = traced()
        assertNull(raising.openingWall)
        assertSame(raising, raising.addOpening(door))
        assertNull(room().withMode(MeasureMode.Distance).openingWall)
        assertNull(room().withMode(MeasureMode.Wall).openingWall)
    }

    @Test
    fun `undo drops the room's last opening first, then its height`() {
        val board = room().addOpening(door).addOpening(window)
        assertEquals(listOf(door, window), board.figures.last().openings)
        assertEquals(listOf(door), board.undo().figures.last().openings)
        val bare = board.undo().undo()
        assertTrue(bare.figures.last().openings.isEmpty())
        assertEquals(2.5f, bare.figures.last().height)
        assertNull(bare.undo().figures.last().height)
    }

    @Test
    fun `a corner left on a room marks nothing once the room is left`() {
        val marked = room().startOpening().tapOnWall(MeasureWallPoint(along = 1.4f, up = 2.1f, wall = 1))
        assertNotNull(marked.openingCorner)
        // Another mode, or the next room started: the marking ends, corner and all.
        for (next in listOf(marked.withMode(MeasureMode.Wall), marked.tap(p(6f, 0f), false), marked.undo().undo())) {
            assertFalse(next.markingOpening)
            assertNull(next.openingCorner)
        }
    }

    // ── The figures ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the walls' net area leaves the openings out, the floor and the volume do not move`() {
        val bare = room().numbers()
        val numbers = room().addOpening(door).addOpening(window).numbers()
        assertEquals(12f, numbers.outlineArea!!, 1e-4f)
        assertEquals(14f, numbers.length, 1e-4f)
        assertEquals(35f, numbers.wallArea!!, 1e-4f)
        assertEquals(0.9f * 2.1f + 1.2f * 1f, numbers.openingsArea!!, 1e-4f)
        assertEquals(35f - 0.9f * 2.1f - 1.2f * 1f, numbers.netWallArea!!, 1e-4f)
        // A door is no hole in the floor or the ceiling: the volume stays floor × height.
        assertEquals(30f, numbers.volume!!, 1e-4f)
        assertEquals(bare.volume!!, numbers.volume!!, 0f)
        assertEquals(bare.outlineArea!!, numbers.outlineArea!!, 0f)
    }

    @Test
    fun `an opening on the closing wall counts, and one marked twice counts once`() {
        // The last wall, from the fourth corner back to the first.
        val back = opening(wall = 3, from = 1f, to = 2f, bottom = 1f, top = 2f)
        val numbers = room().addOpening(back).addOpening(back).addOpening(door).numbers()
        assertEquals(1f + 0.9f * 2.1f, numbers.openingsArea!!, 1e-4f)
        assertEquals(35f - 1f - 0.9f * 2.1f, numbers.netWallArea!!, 1e-4f)
    }

    @Test
    fun `a room without openings reads no net area`() {
        val numbers = room().numbers()
        assertNull(numbers.openingsArea)
        assertNull(numbers.netWallArea)
        assertFalse(measureShareText(listOf(numbers), MeasureUnitSystem.Metric, Locale.US).contains("Net"))
    }

    @Test
    fun `the share text gives a room's openings and net wall area`() {
        val numbers = room().addOpening(door).addOpening(window).numbers()
        val text = measureShareText(listOf(numbers), MeasureUnitSystem.Metric, Locale.US)
        assertTrue(text, text.contains("  Wall area: 35.0 m²"))
        assertTrue(text, text.contains("  Doors and windows: 3.09 m²"))
        assertTrue(text, text.contains("  Net wall area: 31.9 m²"))
        assertTrue(text, text.contains("  Volume: 30.0 m³"))
    }

    // ── Aiming at a room's walls ────────────────────────────────────────────────────────────

    private fun at(x: Float, y: Float, z: Float) = Position(x, y, z)

    // Standing in the room, 1.5 m up.
    private val eye = at(2f, 1.5f, 1.5f)

    private fun aim(target: Position, from: Position = eye, onWall: Int? = null): MeasureWallPoint? =
        measureFigureWallPointFromRay(
            origin = from,
            direction = Position(target.x - from.x, target.y - from.y, target.z - from.z),
            outline = corners,
            closed = true,
            height = 2.5f,
            onWall = onWall,
        )

    private fun assertPoint(expected: MeasureWallPoint, actual: MeasureWallPoint?) {
        assertNotNull(actual)
        assertEquals(expected.wall, actual!!.wall)
        assertEquals(expected.along, actual.along, 1e-4f)
        assertEquals(expected.up, actual.up, 1e-4f)
    }

    @Test
    fun `the aim reads the wall it meets, along that wall from its start`() {
        // The second wall, x = 4, 1 m from its start.
        assertPoint(MeasureWallPoint(1f, 1f, wall = 1), aim(at(4f, 1f, 1f)))
        // The closing wall, from the last corner (0, 3) back to the first.
        assertPoint(MeasureWallPoint(1f, 2f, wall = 3), aim(at(0f, 2f, 2f)))
        // Above the ceiling, nothing.
        assertNull(aim(at(4f, 2.8f, 1f)))
    }

    @Test
    fun `the nearest wall wins, unless the first corner holds the aim to its own`() {
        // From outside, through the first wall to the third one behind it.
        val outside = at(2f, 1.5f, -2f)
        val through = aim(at(2f, 1f, 1.5f), from = outside)
        assertEquals(0, through!!.wall)
        assertEquals(2f, through.along, 1e-4f)
        val behind = aim(at(2f, 1f, 1.5f), from = outside, onWall = 2)
        assertEquals(2, behind!!.wall)
        // The third wall runs from (4, 3) back to (0, 3).
        assertEquals(2f, behind.along, 1e-4f)
    }

    @Test
    fun `just round a corner, the second corner stays on the first one's wall`() {
        val round = at(4f, 1f, 0.05f)
        // Free, the aim reads the second wall just past its start.
        assertPoint(MeasureWallPoint(0.05f, 1f, wall = 1), aim(round))
        // Held to the first wall, it is kept on that wall's end.
        val held = aim(round, onWall = 0)
        assertEquals(0, held!!.wall)
        assertEquals(4f, held.along, 1e-4f)
    }

    @Test
    fun `two corners on two walls mark nothing`() {
        assertNull(measureOpening(MeasureWallPoint(3.5f, 0f, wall = 0), MeasureWallPoint(0.5f, 2f, wall = 1)))
        // Either corner first.
        assertEquals(door, measureOpening(MeasureWallPoint(1.4f, 2.1f, wall = 1), MeasureWallPoint(0.5f, 0f, wall = 1)))
    }

    @Test
    fun `an opening is drawn on the wall it was marked on`() {
        assertCorners(
            listOf(at(4f, 0f, 0.5f), at(4f, 0f, 1.4f), at(4f, 2.1f, 1.4f), at(4f, 2.1f, 0.5f)),
            measureFigureOpeningCorners(door, corners, closed = true),
        )
        // On the closing wall, which runs from the last corner back to the first.
        assertCorners(
            listOf(at(0f, 1f, 2f), at(0f, 1f, 1f), at(0f, 2f, 1f), at(0f, 2f, 2f)),
            measureFigureOpeningCorners(opening(wall = 3, from = 1f, to = 2f, bottom = 1f, top = 2f), corners, closed = true),
        )
    }

    @Test
    fun `an opening stays as wide as it was marked when the walls before it are re-measured`() {
        // A door marked at the start of the third wall, (4, 3) to (0, 3): 0.05–0.95 m along it.
        val first = aim(at(3.95f, 0f, 3f))!!
        val second = aim(at(3.05f, 2.1f, 3f), onWall = first.wall)!!
        val marked = measureOpening(first, second)!!
        val board = room().addOpening(marked)
        // The first two walls then read 4 and 3 cm longer, as their corners settle.
        val settled = listOf(p(0f, 0f), p(4.04f, 0f), p(4.04f, 3.03f), p(0f, 3.03f))
        // Drawn on the third wall, from its new start, as wide as it was marked.
        assertCorners(
            listOf(at(3.99f, 0f, 3.03f), at(3.09f, 0f, 3.03f), at(3.09f, 2.1f, 3.03f), at(3.99f, 2.1f, 3.03f)),
            measureFigureOpeningCorners(marked, settled, closed = true),
        )
        // The same on the plan, and off the net wall area.
        val onPlan = measurePlanOpenings(settled, listOf(marked)).single()
        assertEquals(0.9f, onPlan.opening.width, 1e-4f)
        val numbers = measureFigureNumbers(board.figures.last(), settled)
        assertEquals(0.9f * 2.1f, numbers.openingsArea!!, 1e-4f)
    }

    private fun assertCorners(expected: List<Position>, actual: List<Position>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            assertEquals(e.x, a.x, 1e-4f)
            assertEquals(e.y, a.y, 1e-4f)
            assertEquals(e.z, a.z, 1e-4f)
        }
    }

    // ── On the plan ─────────────────────────────────────────────────────────────────────────

    private fun assertOnPlan(wall: String, expected: MeasureOpening, actual: MeasurePlanOpening) {
        assertEquals(wall, actual.wall)
        assertEquals(expected.from, actual.opening.from, 1e-4f)
        assertEquals(expected.to, actual.opening.to, 1e-4f)
        assertEquals(expected.bottom, actual.opening.bottom, 0f)
        assertEquals(expected.top, actual.opening.top, 0f)
    }

    @Test
    fun `the plan names each opening by its wall's letter`() {
        // Traced clockwise on the page from its top-left corner: wall A is the first one traced.
        val onPlan = measurePlanOpenings(corners, listOf(window, door))
        assertEquals(2, onPlan.size)
        assertOnPlan("B", MeasureOpening(0.5f, 1.4f, 0f, 2.1f), onPlan[0])
        assertOnPlan("C", MeasureOpening(0.5f, 1.7f, 1f, 2f), onPlan[1])
        val plan = measureRoomSurvey(corners, 2.5f)!!
        assertEquals(3f, plan.walls.single { it.tag == "B" }.length.value, 1e-4f)
    }

    @Test
    fun `traced the other way round, the plan runs each wall backwards`() {
        // The same room from another corner, the other way round: the plan still walks it
        // clockwise, so each wall's openings are read from its other end.
        val other = listOf(p(4f, 0f), p(0f, 0f), p(0f, 3f), p(4f, 3f))
        // 0.5–1.4 m along the third wall traced, (0, 3) to (4, 3); 1–2 m along the first.
        val third = opening(wall = 2, from = 0.5f, to = 1.4f, bottom = 0f, top = 2.1f)
        val first = opening(wall = 0, from = 1f, to = 2f, bottom = 1f, top = 2f)
        val onPlan = measurePlanOpenings(other, listOf(first, third))
        assertEquals(2, onPlan.size)
        assertOnPlan("A", MeasureOpening(2.6f, 3.5f, 0f, 2.1f), onPlan[0])
        assertOnPlan("C", MeasureOpening(2f, 3f, 1f, 2f), onPlan[1])
        // The plan itself does not change for it.
        assertEquals(12f, measureRoomSurvey(other, 2.5f)!!.area!!.value, 1e-4f)
    }

    @Test
    fun `no plan, no openings on it`() {
        assertTrue(measurePlanOpenings(corners.take(2), listOf(door)).isEmpty())
        assertTrue(measurePlanOpenings(corners, emptyList()).isEmpty())
    }

    @Test
    fun `the plan's volume is the readout's, openings or not`() {
        val board = room().addOpening(door).addOpening(window)
        val figure = board.figures.last()
        val outline = measureFigureOutline(figure.mode, figure.points)
        val plan = measureRoomSurvey(outline, figure.height)!!
        val volume = PlanAreas.volume(plan.area, plan.ceilingHeight)!!
        assertEquals(board.numbers().volume!!, volume.value, 1e-4f)
    }
}
