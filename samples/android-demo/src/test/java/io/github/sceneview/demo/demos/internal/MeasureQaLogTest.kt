package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The debug-only Measure QA log: one JSON line per figure, metres, named like the share text. */
class MeasureQaLogTest {

    private fun p(x: Float, y: Float, z: Float) = Position(x = x, y = y, z = z)

    /** Points are their own positions here; the demo maps ARCore anchors the same way. */
    private fun lines(board: MeasureBoard<Position>, liveHeight: Float? = null) = measureQaLines(
        board = board,
        point = { MeasureQaPoint(it, source = "Plane", snap = "None", plane = "VERTICAL", timestampNs = 42L, cameraDistance = 1.42f) },
        tracking = "TRACKING",
        device = "Pixel \"QA\"",
        app = "4.60.0-qa",
        liveHeight = liveHeight,
    )

    private fun MeasureBoard<Position>.taps(vararg points: Position): MeasureBoard<Position> =
        points.fold(this) { b, pt -> b.tap(pt, closesOutline = false) }

    private fun String.field(name: String): String =
        Regex("\"$name\":(\\[[^\\]]*\\]|\"[^\"]*\"|[^,}]+)").find(this)?.groupValues?.get(1)
            ?: error("no $name in $this")

    @Test
    fun distanceChainsAreNumberedLikeTheShareText() {
        val board = MeasureBoard<Position>()
            .taps(p(0f, 0f, 0f), p(1f, 0f, 0f))
            .finish()
            .taps(p(0f, 0f, 1f), p(1.2137f, 0f, 1f))
        val out = lines(board)
        assertEquals(2, out.size)
        assertEquals("\"Distance 1\"", out[0].field("figure"))
        val second = out[1]
        assertEquals("\"Distance 2\"", second.field("figure"))
        assertEquals("\"Distance\"", second.field("mode"))
        assertEquals("[1.2137]", second.field("segments_m"))
        assertEquals("1.2137", second.field("length_m"))
        assertEquals("false", second.field("closed"))
        assertEquals("null", second.field("outline_area_m2"))
        assertEquals("null", second.field("height_m"))
        assertEquals("null", second.field("live_height_m"))
        assertTrue(second.startsWith("{\"v\":1,"))
        assertTrue(second.contains("{\"x\":1.2137,\"y\":0.0000,\"z\":1.0000,\"source\":\"Plane\",\"snap\":\"None\",\"plane\":\"VERTICAL\",\"t_ns\":42,\"cam_dist_m\":1.4200}"))
        assertTrue(second.endsWith("\"tracking\":\"TRACKING\",\"device\":\"Pixel \\\"QA\\\"\",\"app\":\"4.60.0-qa\"}"))
    }

    @Test
    fun wallLogsWidthHeightAndArea() {
        val board = MeasureBoard<Position>().withMode(MeasureMode.Wall)
            .taps(p(0f, 0f, 0f), p(3f, 0.02f, 0f))
            .lockHeight(2.5f)
        val line = lines(board).single()
        assertEquals("\"Wall 1\"", line.field("figure"))
        assertEquals("3.0000", line.field("length_m"))
        assertEquals("2.5000", line.field("height_m"))
        assertEquals("7.5000", line.field("wall_area_m2"))
    }

    @Test
    fun roomLogsEveryWallTheFloorAndTheCeiling() {
        val board = MeasureBoard<Position>().withMode(MeasureMode.Room)
            .taps(p(0f, 0f, 0f), p(4f, 0f, 0f), p(4f, 0f, 3f), p(0f, 0f, 3f))
            .tap(null, closesOutline = true)
            .lockHeight(2.4f)
        val line = lines(board).single()
        assertEquals("\"Room 1\"", line.field("figure"))
        assertEquals("[4.0000,3.0000,4.0000,3.0000]", line.field("segments_m"))
        assertEquals("14.0000", line.field("length_m"))
        assertEquals("true", line.field("closed"))
        assertEquals("12.0000", line.field("outline_area_m2"))
        assertEquals("2.4000", line.field("height_m"))
        assertEquals("33.6000", line.field("wall_area_m2"))
    }

    @Test
    fun aRisingWallLogsItsLiveHeightApartFromTheLockedOne() {
        val rising = MeasureBoard<Position>().withMode(MeasureMode.Wall).taps(p(0f, 0f, 0f), p(2f, 0f, 0f))
        val line = lines(rising, liveHeight = 1.8f).single()
        assertEquals("null", line.field("height_m"))
        assertEquals("1.8000", line.field("live_height_m"))
        assertEquals("null", line.field("wall_area_m2"))
        // Once locked, the live reading is no longer the figure's: only height_m is.
        val locked = lines(rising.lockHeight(2.5f), liveHeight = 1.8f).single()
        assertEquals("2.5000", locked.field("height_m"))
        assertEquals("null", locked.field("live_height_m"))
    }

    @Test
    fun undoLeavesNoStaleFigure() {
        val two = MeasureBoard<Position>()
            .taps(p(0f, 0f, 0f), p(1f, 0f, 0f))
            .finish()
            .taps(p(0f, 0f, 1f), p(2f, 0f, 1f))
        assertEquals(2, lines(two).size)
        // Dropping the second chain's last point leaves it a single point: no segment, no line.
        val undone = two.undo()
        assertEquals(1, lines(undone).size)
        assertEquals("\"Distance 1\"", lines(undone).single().field("figure"))
        assertTrue(lines(MeasureBoard<Position>().taps(p(0f, 0f, 0f), p(1f, 0f, 0f)).undo()).isEmpty())
        assertTrue(lines(two.clear()).isEmpty())
    }

    @Test
    fun imperialAndCommaLocalesStillWriteMetresWithADot() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.FRANCE)) {
                Locale.setDefault(locale)
                val line = lines(MeasureBoard<Position>().taps(p(0f, 0f, 0f), p(0.3048f, 0f, 0f))).single()
                assertEquals("0.3048", line.field("length_m"))
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
