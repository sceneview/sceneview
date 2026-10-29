package io.github.sceneview.demo.demos.internal.room

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * A small RGB canvas written out as a PNG, for the corpus's plan images. `java.awt` is not on the
 * Android unit tests' compile classpath, so the few shapes a plan needs are drawn here: a filled
 * polygon, thick solid or dashed lines, and figures in a 3×5 pixel font.
 */
class PlanCanvas(val width: Int, val height: Int, background: Int = 0xFFFFFF) {
    private val rgb = IntArray(width * height) { background }

    operator fun get(x: Int, y: Int): Int = rgb[y * width + x]

    private fun set(x: Int, y: Int, color: Int) {
        if (x in 0 until width && y in 0 until height) rgb[y * width + x] = color
    }

    /** Fills the polygon of corners ([xs], [ys]) by the even-odd rule. */
    fun fillPolygon(xs: FloatArray, ys: FloatArray, color: Int) {
        val n = xs.size
        if (n < 3) return
        val crossings = FloatArray(n)
        for (y in 0 until height) {
            val cy = y + 0.5f
            var count = 0
            for (i in 0 until n) {
                val j = (i + 1) % n
                if ((ys[i] <= cy) != (ys[j] <= cy)) {
                    crossings[count++] = xs[i] + (cy - ys[i]) / (ys[j] - ys[i]) * (xs[j] - xs[i])
                }
            }
            crossings.sort(0, count)
            var k = 0
            while (k + 1 < count) {
                for (x in ceil(crossings[k] - 0.5f).toInt()..floor(crossings[k + 1] - 0.5f).toInt()) set(x, y, color)
                k += 2
            }
        }
    }

    /** A line [thickness] pixels wide; dashed [dash] pixels on, [dash] off when [dash] is above 0. */
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, thickness: Float, color: Int, dash: Float = 0f) {
        val length = hypot(x1 - x0, y1 - y0)
        val steps = (length * 2).toInt().coerceAtLeast(1)
        val r = thickness / 2
        for (s in 0..steps) {
            val d = length * s / steps
            if (dash > 0f && (d % (2 * dash)) >= dash) continue
            val cx = x0 + (x1 - x0) * s / steps
            val cy = y0 + (y1 - y0) * s / steps
            for (y in floor(cy - r).toInt()..ceil(cy + r).toInt()) {
                for (x in floor(cx - r).toInt()..ceil(cx + r).toInt()) {
                    if (hypot(x + 0.5f - cx, y + 0.5f - cy) <= r) set(x, y, color)
                }
            }
        }
    }

    /** The width [text] takes at [scale]. */
    fun textWidth(text: String, scale: Int): Int = text.sumOf { (GLYPHS[it]?.first()?.length ?: 2) + 1 } * scale

    /** [text] with its top-left corner at ([x], [y]), each font pixel [scale] pixels; unknown characters are left out. */
    fun text(x: Int, y: Int, text: String, scale: Int, color: Int) {
        var left = x
        for (c in text) {
            val glyph = GLYPHS[c] ?: continue
            for ((row, line) in glyph.withIndex()) {
                for ((col, bit) in line.withIndex()) {
                    if (bit != '#') continue
                    for (dy in 0 until scale) for (dx in 0 until scale) set(left + col * scale + dx, y + row * scale + dy, color)
                }
            }
            left += (glyph.first().length + 1) * scale
        }
    }

    /** The canvas as a PNG file: 8-bit RGB, unfiltered rows. */
    fun png(): ByteArray {
        val raw = ByteArray(height * (1 + width * 3))
        var p = 0
        for (y in 0 until height) {
            raw[p++] = 0
            for (x in 0 until width) {
                val c = rgb[y * width + x]
                raw[p++] = (c shr 16).toByte()
                raw[p++] = (c shr 8).toByte()
                raw[p++] = c.toByte()
            }
        }
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 16)
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeInt(width)
            writeInt(height)
            writeByte(8) // bit depth
            writeByte(2) // colour type: RGB
            writeByte(0)
            writeByte(0)
            writeByte(0)
        }
        chunk(out, "IHDR", header.toByteArray())
        chunk(out, "IDAT", compressed.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val stream = DataOutputStream(out)
        stream.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        stream.write(typeBytes)
        stream.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        stream.writeInt(crc.value.toInt())
    }

    companion object {
        val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        private val GLYPHS: Map<Char, List<String>> = mapOf(
            '0' to listOf("###", "#.#", "#.#", "#.#", "###"),
            '1' to listOf(".#.", "##.", ".#.", ".#.", "###"),
            '2' to listOf("###", "..#", "###", "#..", "###"),
            '3' to listOf("###", "..#", "###", "..#", "###"),
            '4' to listOf("#.#", "#.#", "###", "..#", "..#"),
            '5' to listOf("###", "#..", "###", "..#", "###"),
            '6' to listOf("###", "#..", "###", "#.#", "###"),
            '7' to listOf("###", "..#", "..#", "..#", "..#"),
            '8' to listOf("###", "#.#", "###", "#.#", "###"),
            '9' to listOf("###", "#.#", "###", "..#", "###"),
            '.' to listOf(".", ".", ".", ".", "#"),
            '-' to listOf("...", "...", "###", "...", "..."),
            '=' to listOf("...", "###", "...", "###", "..."),
            '*' to listOf("...", "#.#", ".#.", "#.#", "..."),
            'm' to listOf(".....", ".....", "####.", "#.#.#", "#.#.#"),
            'A' to listOf(".#.", "#.#", "###", "#.#", "#.#"),
            'H' to listOf("#.#", "#.#", "###", "#.#", "#.#"),
            'P' to listOf("##.", "#.#", "##.", "#..", "#.."),
            ' ' to listOf("..", "..", "..", "..", ".."),
        )
    }
}
