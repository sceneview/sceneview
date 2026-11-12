package io.github.sceneview.demo.demos

import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picture the example's result opens on: before it, the first second of "See an example" was
 * an empty stage, the time the renderer takes to draw its first frame (Pixel 4a, 2026-11-12).
 */
class ScanStillTest {
    private val res = listOf(
        File("src/main/res/drawable-nodpi"),
        File("samples/android-demo/src/main/res/drawable-nodpi"),
    ).first { it.isDirectory }

    @Test
    fun `the example's picture is embedded for both stages, at the shape it is laid out at`() {
        for (name in listOf("scan_example_still_light", "scan_example_still_dark")) {
            val file = File(res, "$name.webp")
            assertTrue("$name is not embedded", file.isFile)
            val (width, height) = webpSize(file.readBytes())
            assertTrue(
                "$name is ${width}x$height, laid out at ${ScanStill.Example.aspect}",
                abs(width.toFloat() / height - ScanStill.Example.aspect) < 0.01f,
            )
            // A second's placeholder, not an asset: both together stay a rounding error of the APK.
            assertTrue("$name weighs ${file.length()} bytes", file.length() < MAX_BYTES)
        }
    }

    @Test
    fun `a result opened on its picture is whole from its first frame`() {
        // The picture shows the whole place: a live scene growing from nothing under it would
        // fade from a garden to an empty stage.
        assertEquals(1f, ResultLayers(offered = true, photoFirst = true, whole = true).grown)
        // Without one, the place grows as the scan found it.
        assertEquals(0f, ResultLayers(offered = true, photoFirst = true).grown)
    }

    @Test
    fun `it is laid where the place is framed, on its own screen and on a taller one`() {
        val still = ScanStill.Example
        // On the screen it was taken on, it covers that screen exactly.
        assertEquals(0f, still.topOn(still.aspect, still.zoneCentre), 1e-4f)
        // On a taller one, as wide as the screen and its place still on the framing's centre.
        val taller = still.aspect * 0.9f
        val top = still.topOn(taller, centre = 0.45f)
        assertEquals(0.45f, top + still.zoneCentre * taller / still.aspect, 1e-4f)
    }

    /** The size of a lossy (`VP8 `) WebP, from its frame header. */
    private fun webpSize(bytes: ByteArray): Pair<Int, Int> {
        assertEquals("not a lossy WebP without alpha", "VP8 ", String(bytes, 12, 4, Charsets.US_ASCII))
        fun int14(at: Int) = ((bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)) and 0x3FFF
        return int14(26) to int14(28)
    }

    private companion object {
        const val MAX_BYTES = 60_000L
    }
}
