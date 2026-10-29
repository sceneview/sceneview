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

/** What a tradesperson works from: a wall net of its doors and windows, a room's paint area and volume. */
class MeasureOpeningsTest {
    private fun p(x: Float, y: Float, z: Float) = Position(x, y, z)

    // A wall 4 m long on the floor at y 0, along x at z = -2, 2.5 m high; the camera stands at
    // the origin, 1.5 m up.
    private val a = p(0f, 0f, -2f)
    private val b = p(4f, 0f, -2f)
    private val eye = p(2f, 1.5f, 0f)

    private fun towards(target: Position) = Position(target.x - eye.x, target.y - eye.y, target.z - eye.z)

    private fun wallBoard(height: Float = 2.5f): MeasureBoard<Int> =
        MeasureBoard<Int>(mode = MeasureMode.Wall).tap(1, false).tap(2, false).lockHeight(height)

    // ── Aiming at the wall ──────────────────────────────────────────────────────────────────

    @Test
    fun `the aim reads where it meets the wall`() {
        val point = measureWallPointFromRay(eye, towards(p(1f, 2f, -2f)), a, b, height = 2.5f)
        assertNotNull(point)
        assertEquals(1f, point!!.along, 1e-4f)
        assertEquals(2f, point.up, 1e-4f)
    }

    @Test
    fun `an aim just off the wall is kept on its edge`() {
        val past = measureWallPointFromRay(eye, towards(p(4.05f, 2.55f, -2f)), a, b, height = 2.5f)!!
        assertEquals(4f, past.along, 1e-4f)
        assertEquals(2.5f, past.up, 1e-4f)
        val under = measureWallPointFromRay(eye, towards(p(1f, -0.05f, -2f)), a, b, height = 2.5f)!!
        assertEquals(0f, under.up, 1e-4f)
    }

    @Test
    fun `an aim off the wall reads nothing`() {
        assertNull(measureWallPointFromRay(eye, towards(p(4.2f, 1f, -2f)), a, b, height = 2.5f))
        assertNull(measureWallPointFromRay(eye, towards(p(1f, 2.7f, -2f)), a, b, height = 2.5f))
        // Facing away from the wall, or along it.
        assertNull(measureWallPointFromRay(eye, p(0f, 0f, 1f), a, b, height = 2.5f))
        assertNull(measureWallPointFromRay(eye, p(1f, 0f, 0f), a, b, height = 2.5f))
    }

    // ── Openings ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `two corners mark an opening either way round`() {
        val door = measureOpening(MeasureWallPoint(1.9f, 2.1f), MeasureWallPoint(1f, 0f))!!
        assertEquals(MeasureOpening(1f, 1.9f, 0f, 2.1f), door)
        assertEquals(0.9f, door.width, 1e-5f)
        assertEquals(2.1f, door.height, 1e-5f)
    }

    @Test
    fun `two taps on one spot mark nothing`() {
        assertNull(measureOpening(MeasureWallPoint(1f, 1f), MeasureWallPoint(1.05f, 2f)))
        assertNull(measureOpening(MeasureWallPoint(1f, 1f), MeasureWallPoint(2f, 1.05f)))
    }

    @Test
    fun `openings count once where they overlap and only on the wall`() {
        val window = MeasureOpening(1f, 2f, 1f, 2f)
        assertEquals(1f, measureOpeningsArea(listOf(window), 4f, 2.5f), 1e-5f)
        assertEquals(1f, measureOpeningsArea(listOf(window, window), 4f, 2.5f), 1e-5f)
        // Two windows sharing a quarter: 1 + 1 − 0.25.
        val shifted = MeasureOpening(1.5f, 2.5f, 1.5f, 2.5f)
        assertEquals(1.75f, measureOpeningsArea(listOf(window, shifted), 4f, 2.5f), 1e-5f)
        // Kept on a wall 2 m high: the part above it does not count.
        assertEquals(1.25f, measureOpeningsArea(listOf(window, shifted), 4f, 2f), 1e-5f)
        assertEquals(0f, measureOpeningsArea(emptyList(), 4f, 2.5f), 0f)
    }

    @Test
    fun `the corners of an opening stand on the wall`() {
        val corners = measureOpeningCorners(MeasureOpening(1f, 2f, 0f, 2f), a, b)
        assertEquals(listOf(p(1f, 0f, -2f), p(2f, 0f, -2f), p(2f, 2f, -2f), p(1f, 2f, -2f)), corners)
    }

    // ── The board ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `openings go on a measured wall or a raised room only`() {
        val door = MeasureOpening(1f, 1.9f, 0f, 2.1f)
        val raising = MeasureBoard<Int>(mode = MeasureMode.Wall).tap(1, false).tap(2, false)
        assertNull(raising.openingWall)
        assertSame(raising, raising.addOpening(door))
        val walls = MeasureBoard<Int>(mode = MeasureMode.Room).tap(1, false).tap(2, false).tap(3, false).tap(null, true)
        assertNull(walls.openingWall)
        val room = walls.lockHeight(2.5f)
        assertSame(room.figures.last(), room.openingWall)
        // Back in Distance, the wall stays as it was.
        assertNull(wallBoard().withMode(MeasureMode.Distance).openingWall)

        val board = wallBoard().addOpening(door)
        assertEquals(listOf(door), board.figures.last().openings)
        // The wall stays finished: the shutter still starts a new measure.
        assertEquals(MeasureAction.StartNew, measureAction(board, closes = false))
    }

    @Test
    fun `undo drops the last opening first`() {
        val door = MeasureOpening(1f, 1.9f, 0f, 2.1f)
        val window = MeasureOpening(2.5f, 3.5f, 1f, 2f)
        val board = wallBoard().addOpening(door).addOpening(window)
        assertEquals(listOf(door), board.undo().figures.last().openings)
        val bare = board.undo().undo()
        assertTrue(bare.figures.last().openings.isEmpty())
        assertEquals(2.5f, bare.figures.last().height)
        // Then the height, as before.
        assertNull(bare.undo().figures.last().height)
    }

    // ── Figures ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a wall with a door and a window reads its net area`() {
        val board = wallBoard()
            .addOpening(MeasureOpening(0.5f, 1.4f, 0f, 2.1f))
            .addOpening(MeasureOpening(2.5f, 3.5f, 1f, 2f))
        val numbers = measureFigureNumbers(board.figures.last(), listOf(a, b))
        assertEquals(10f, numbers.wallArea!!, 1e-4f)
        assertEquals(0.9f * 2.1f + 1f, numbers.openingsArea!!, 1e-4f)
        assertEquals(10f - 0.9f * 2.1f - 1f, numbers.netWallArea!!, 1e-4f)
        val text = measureShareText(listOf(numbers), MeasureUnitSystem.Metric, Locale.US)
        assertTrue(text, text.contains("  Area: 10.0 m²"))
        assertTrue(text, text.contains("  Doors and windows: 2.89 m²"))
        assertTrue(text, text.contains("  Net area: 7.11 m²"))
    }

    @Test
    fun `a wall without openings has no net area`() {
        val numbers = measureFigureNumbers(wallBoard().figures.last(), listOf(a, b))
        assertNull(numbers.openingsArea)
        assertNull(numbers.netWallArea)
        assertFalse(measureShareText(listOf(numbers), MeasureUnitSystem.Metric, Locale.US).contains("Net area"))
    }

    @Test
    fun `no area before the height or the closed loop`() {
        val open = MeasureFigure(MeasureMode.Room, listOf(1, 2, 3))
        val outline = listOf(p(0f, 0f, 0f), p(4f, 0f, 0f), p(4f, 0f, 3f))
        val numbers = measureFigureNumbers(open, outline)
        assertNull(numbers.outlineArea)
        assertNull(numbers.wallArea)
        assertNull(numbers.volume)
        val wall = measureFigureNumbers(MeasureFigure(MeasureMode.Wall, listOf(1, 2)), listOf(a, b))
        assertNull(wall.wallArea)
        assertNull(wall.netWallArea)
    }

    @Test
    fun `a room reads floor, perimeter, paint area and volume`() {
        val outline = listOf(p(0f, 0f, 0f), p(4f, 0f, 0f), p(4f, 0f, 3f), p(0f, 0f, 3f))
        val room = MeasureFigure(MeasureMode.Room, listOf(1, 2, 3, 4), closed = true, height = 2.5f)
        val numbers = measureFigureNumbers(room, outline)
        assertEquals(12f, numbers.outlineArea!!, 1e-4f)
        assertEquals(14f, numbers.length, 1e-4f)
        assertEquals(35f, numbers.wallArea!!, 1e-4f)
        assertEquals(30f, numbers.volume!!, 1e-4f)
        val text = measureShareText(listOf(numbers), MeasureUnitSystem.Metric, Locale.US)
        assertTrue(text, text.contains("  Volume: 30.0 m³"))
        val imperial = measureShareText(listOf(numbers), MeasureUnitSystem.Imperial, Locale.US)
        assertTrue(imperial, imperial.contains("  Volume: 1059 ft³"))
    }

    @Test
    fun `volumes read like areas`() {
        assertEquals("4.50 m³", formatMeasureVolume(4.5f, MeasureUnitSystem.Metric, Locale.US))
        assertEquals("30.0 m³", formatMeasureVolume(30f, MeasureUnitSystem.Metric, Locale.US))
        assertEquals("30,0 m³", formatMeasureVolume(30f, MeasureUnitSystem.Metric, Locale.FRANCE))
        assertEquals("3.5 ft³", formatMeasureVolume(0.1f, MeasureUnitSystem.Imperial, Locale.US))
        assertEquals("0.00 m³", formatMeasureVolume(-1f, MeasureUnitSystem.Metric, Locale.US))
    }
}
