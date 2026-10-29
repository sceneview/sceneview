package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * #4765: the labels on the walls in AR say what the card says, in the same units, from one
 * formatting of the wall's numbers ([measureWallText]); and the drawn walls reach the height
 * read, instead of staying a strip on the floor while the aim moves.
 */
class MeasureWallLabelsTest {
    private val floor = -1.3f

    // A wall 22 cm wide, its height read at 80 cm: the card reads 2 ft 7 in and 1.9 ft² under en-US.
    private val narrow = listOf(Position(0f, floor, 0f), Position(0.22f, floor, 0f))
    private val rising = MeasureFigure(mode = MeasureMode.Wall, points = narrow, raising = true)
    private val read = 0.8f

    private class Formats(locale: Locale) {
        private val units = MeasureUnitSystem.forLocale(locale)
        val len: (Float) -> String = { formatMeasureLength(it, units, locale) }
        val area: (Float) -> String = { formatMeasureArea(it, units, locale) }
    }

    /** The card's words and the labels' words for [figure] along [outline], walls drawn [drawn] high. */
    private fun read(
        figure: MeasureFigure<Position>,
        outline: List<Position>,
        height: Float?,
        drawn: Float,
        locale: Locale,
    ): Pair<MeasureWallText, List<String>> {
        val f = Formats(locale)
        val numbers = measureFigureNumbers(figure, outline, liveHeight = height)
        val labels = measureWallLabels(figure, outline, numbers, drawn, f.len, f.area)
        return measureWallText(numbers, f.len, f.area) to labels.map { it.text }
    }

    @Test
    fun `the labels on a wall say what the card says, in the screen's units`() {
        for (locale in listOf(Locale.US, Locale.FRANCE, Locale.UK)) {
            // The walls still rising from the floor: the labels already read the height.
            val (card, labels) = read(rising, narrow, read, drawn = WALL_MIN_HEIGHT, locale)
            assertEquals(locale.toString(), listOf(card.height, card.area), labels)
        }
    }

    @Test
    fun `under en-US the wall reads feet and inches, not the strip it rises from`() {
        assertEquals(listOf("2 ft 7 in", "1.9 ft²"), read(rising, narrow, read, WALL_MIN_HEIGHT, Locale.US).second)
        assertEquals(listOf("80 cm", "0,18 m²"), read(rising, narrow, read, WALL_MIN_HEIGHT, Locale.FRANCE).second)
    }

    @Test
    fun `with a door marked, the wall's label reads the card's net area`() {
        val outline = listOf(Position(0f, floor, 0f), Position(2f, floor, 0f))
        val door = MeasureOpening(from = 0.5f, to = 1.3f, bottom = 0f, top = 2f)
        val wall = MeasureFigure(mode = MeasureMode.Wall, points = outline, height = 2.4f, openings = listOf(door))
        val (card, labels) = read(wall, outline, height = null, drawn = 1.2f, Locale.FRANCE)
        assertEquals("3,20 m²", card.net)
        assertEquals(listOf(card.height, card.net), labels)
    }

    @Test
    fun `a room's height label is the card's ceiling height`() {
        val corners = listOf(0f to 0f, 4f to 0f, 4f to 3f, 0f to 3f).map { (x, z) -> Position(x, floor, z) }
        val room = MeasureFigure(mode = MeasureMode.Room, points = corners, closed = true, height = 2.5f)
        val (card, labels) = read(room, corners, height = null, drawn = 0.4f, Locale.US)
        assertEquals("8 ft 2 in", card.height)
        assertEquals(listOf(card.height), labels)
    }

    @Test
    fun `no height label before a height reads, as on the card`() {
        val (card, labels) = read(rising, narrow, height = null, drawn = WALL_MIN_HEIGHT, Locale.US)
        assertEquals(null, card.height)
        assertTrue(labels.toString(), labels.isEmpty())
    }

    @Test
    fun `the labels sit halfway up the walls as drawn`() {
        val f = Formats(Locale.US)
        val numbers = measureFigureNumbers(rising, narrow, liveHeight = read)
        val labels = measureWallLabels(rising, narrow, numbers, drawn = 0.6f, f.len, f.area)
        assertEquals(2, labels.size)
        labels.forEach { assertEquals(floor + 0.3f, it.position.y, 1e-5f) }
    }

    @Test
    fun `the drawn walls reach the height read while it moves every frame`() {
        val frame = 1f / 60f
        var drawn = WALL_MIN_HEIGHT
        // A second of aim held on the wall, the read trembling by a few millimetres every frame.
        repeat(60) { i ->
            drawn = measureWallRise(drawn, read + if (i % 2 == 0) 0.003f else -0.003f, frame)
        }
        assertEquals(read, drawn, 0.01f)
        // The aim steady: the walls land on the height, exactly.
        repeat(30) { drawn = measureWallRise(drawn, read, frame) }
        assertEquals(read, drawn, 0f)
    }

    @Test
    fun `the drawn walls rise without overshoot, and a long frame lands on the height`() {
        var drawn = WALL_MIN_HEIGHT
        repeat(20) {
            val next = measureWallRise(drawn, 2.4f, 1f / 60f)
            assertTrue("$drawn -> $next", next > drawn && next <= 2.4f)
            drawn = next
        }
        assertEquals(2.4f, measureWallRise(WALL_MIN_HEIGHT, 2.4f, 1f), 0f)
        // Down too, once the height set is lower; and a frame of no time moves nothing.
        assertTrue(measureWallRise(2.4f, 1f, 1f / 60f) < 2.4f)
        assertEquals(1.5f, measureWallRise(1.5f, 2.4f, 0f), 0f)
    }
}
