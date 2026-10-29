package io.github.sceneview.demo.demos.internal.semantic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/** The YOLOX letterbox and decoding, on hand-made outputs: what runs round the model on the phone. */
class YoloxCodecTest {
    @Test
    fun `a 416 input has one row per cell of the three grids`() {
        assertEquals(52 * 52 + 26 * 26 + 13 * 13, YoloxCodec.rowsFor(416))
    }

    @Test
    fun `the photo is drawn top left, BGR, unscaled 0-255, and the rest is grey`() {
        // A red pixel beside a blue one, into a 4 × 4 input: drawn 2× into the top two rows.
        val image = SceneImage(2, 1, intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()))
        val input = FloatArray(3 * 16)
        val ratio = YoloxCodec.input(image, 4, input)
        assertEquals(2f, ratio, 0f)
        val plane = 16
        assertEquals("blue of the red pixel", 0f, input[0], 0f)
        assertEquals("red of the red pixel", 255f, input[2 * plane], 0f)
        assertEquals("blue of the blue pixel", 255f, input[3], 0f)
        assertEquals("red of the blue pixel", 0f, input[2 * plane + 3], 0f)
        for (at in 8 until 16) assertEquals("row ${at / 4} is padding", YoloxCodec.PAD, input[at], 0f)
    }

    @Test
    fun `the LiteRT input holds each pixel's blue, green and red side by side`() {
        // Four pixels of known colours, drawn 1:1 into a 2 × 2 input.
        val image = SceneImage(
            2, 2,
            intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF102030.toInt()),
        )
        val planar = FloatArray(3 * 4)
        assertEquals(1f, YoloxCodec.input(image, 2, planar), 0f)
        val interleaved = FloatArray(planar.size)

        YoloxCodec.channelsLast(planar, 2, interleaved)

        // NCHW to NHWC with the channels kept in order: blue at pixel × 3, then green, then red.
        val expected = floatArrayOf(
            0f, 0f, 255f, // red
            0f, 255f, 0f, // green
            255f, 0f, 0f, // blue
            48f, 32f, 16f, // 0x102030: blue 0x30, green 0x20, red 0x10
        )
        assertArrayEquals(expected, interleaved, 0f)
    }

    @Test
    fun `the conversion's tensors read channels last in, one row of 85 per cell out`() {
        val layout = YoloxCodec.channelsLastLayout(intArrayOf(1, 416, 416, 3), intArrayOf(1, 3549, 85))
        assertEquals(YoloxCodec.TensorLayout(size = 416, columns = 85), layout)
    }

    @Test
    fun `a conversion that reads its tensors otherwise is refused, not decoded into noise`() {
        val channelsLast = intArrayOf(1, 416, 416, 3)
        val rows = intArrayOf(1, 3549, 85)
        val refused = listOf(
            "the channels first" to (intArrayOf(1, 3, 416, 416) to rows),
            "the output transposed" to (channelsLast to intArrayOf(1, 85, 3549)),
            "another class list" to (channelsLast to intArrayOf(1, 3549, 84)),
            "the rows of another input side" to (channelsLast to intArrayOf(1, 2100, 85)),
            "a side no grid divides" to (intArrayOf(1, 420, 420, 3) to rows),
            "a side the tensor does not say" to (intArrayOf(1, -1, -1, 3) to rows),
            "a batch of two" to (intArrayOf(2, 416, 416, 3) to intArrayOf(2, 3549, 85)),
        )
        for ((what, shapes) in refused) {
            val thrown = runCatching { YoloxCodec.channelsLastLayout(shapes.first, shapes.second) }.exceptionOrNull()
            assertTrue("$what was accepted", thrown is IllegalArgumentException)
        }
    }

    @Test
    fun `a row decodes to its box in the photo's fractions, the best class named`() {
        val out = Output(size = 32)
        // Grid of stride 8, cell (1, 2): centre (1.5, 2.5) cells = (12, 20) px, 8 px square.
        out.row(gx = 1, gy = 2, dx = 0.5f, dy = 0.5f, objectness = 0.9f, "couch" to 0.8f, "chair" to 0.1f)
        val found = out.decode()
        assertEquals(1, found.size)
        val d = found.single()
        assertEquals("couch", d.name)
        assertEquals(SceneLabel.Sofa, d.label)
        assertEquals(0.72f, d.score, 1e-5f)
        assertEquals(0.25f, d.left, 1e-5f)
        assertEquals(0.5f, d.top, 1e-5f)
        assertEquals(0.5f, d.right, 1e-5f)
        assertEquals(0.75f, d.bottom, 1e-5f)
    }

    @Test
    fun `the box is scaled back by the letterbox ratio`() {
        val out = Output(size = 32)
        out.row(gx = 1, gy = 2, dx = 0.5f, dy = 0.5f, objectness = 0.9f, "bed" to 0.9f)
        // The photo was drawn at half size: 64 × 64 px, the box twice as large in pixels, the same fraction.
        val d = out.decode(ratio = 0.5f, width = 64, height = 64).single()
        assertEquals(0.25f, d.left, 1e-5f)
        assertEquals(0.75f, d.bottom, 1e-5f)
    }

    @Test
    fun `overlapping boxes of one class merge, of two classes both stay, faint ones drop`() {
        val out = Output(size = 32)
        out.row(gx = 1, gy = 2, dx = 0.5f, dy = 0.5f, objectness = 0.9f, "couch" to 0.8f)
        out.row(gx = 2, gy = 2, dx = -0.4f, dy = 0.5f, objectness = 0.9f, "couch" to 0.5f)
        out.row(gx = 1, gy = 3, dx = 0.5f, dy = -0.5f, objectness = 0.9f, "chair" to 0.6f)
        out.row(gx = 3, gy = 0, dx = 0.5f, dy = 0.5f, objectness = 0.4f, "tv" to 0.5f)
        val found = out.decode()
        assertEquals(listOf("couch", "chair"), found.map { it.name })
        assertEquals(listOf("couch"), out.decode(keep = { it != "chair" }).map { it.name })
    }

    @Test
    fun `COCO names map to the room's labels, the rest to none`() {
        assertEquals(SceneLabel.Table, SceneLabel.ofDetector("dining table"))
        assertEquals(SceneLabel.Television, SceneLabel.ofDetector("tv"))
        assertEquals(SceneLabel.Plant, SceneLabel.ofDetector("potted plant"))
        assertNull(SceneLabel.ofDetector("person"))
        assertNull(SceneLabel.ofDetector("sports ball"))
        for (label in SceneLabel.entries) assertEquals(label, SceneLabel.ofWire(label.wireName))
        assertTrue(SceneLabel.Door.isOpening && SceneLabel.Window.isOpening && !SceneLabel.Bed.isOpening)
    }

    /** A model output for a [size]² input, every row empty until written. */
    private class Output(val size: Int) {
        val rows = YoloxCodec.rowsFor(size)
        val columns = 5 + YoloxCodec.COCO.size
        val data = FloatArray(rows * columns)

        /** Writes the row of the stride-8 grid's cell ([gx], [gy]): an 8 px box, scores as given. */
        @Suppress("LongParameterList")
        fun row(gx: Int, gy: Int, dx: Float, dy: Float, objectness: Float, vararg scores: Pair<String, Float>) {
            val at = (gy * (size / 8) + gx) * columns
            data[at] = dx
            data[at + 1] = dy
            data[at + 2] = ln(1f)
            data[at + 3] = ln(1f)
            data[at + 4] = objectness
            for ((name, score) in scores) data[at + 5 + YoloxCodec.COCO.indexOf(name)] = score
        }

        fun decode(ratio: Float = 1f, width: Int = size, height: Int = size, keep: (String) -> Boolean = { true }) =
            YoloxCodec.decode(data, rows, columns, size, ratio, width, height, keep = keep)
    }
}
