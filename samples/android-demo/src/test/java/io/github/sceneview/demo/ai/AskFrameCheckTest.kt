package io.github.sceneview.demo.ai

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the frame validation that stands between the AR read-back and Gemini
 * Nano (#3407).
 *
 * The regression these exist for: the pre-#3407 check looked only for `alpha == 0`. A window
 * read-back that lost the Filament `SurfaceView` layer as **opaque black** — the shape an
 * opaque window background produces — sailed through it, was then cropped tighter around the
 * tap (#3343), and reached the model as a flat frame. "Qui voit rien sur la frame AR."
 */
class AskFrameCheckTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    /** A frame of one flat colour. */
    private fun flat(color: Int, count: Int = 4_096) = IntArray(count) { color }

    /** A frame with real texture: pseudo-random but deterministic luminance. */
    private fun textured(count: Int = 4_096): IntArray {
        val random = Random(seed = 3407)
        return IntArray(count) {
            val v = random.nextInt(256)
            argb(0xFF, v, v, v)
        }
    }

    @Test
    fun `a textured opaque frame is usable`() {
        assertEquals(
            AskFrameVerdict.Usable,
            inspectAskFrame(768, 576, textured()),
        )
    }

    @Test
    fun `an opaque black frame is rejected as blank - the #3407 regression`() {
        val verdict = inspectAskFrame(768, 576, flat(argb(0xFF, 0, 0, 0)))
        assertTrue("an all-black frame must not reach the model", verdict is AskFrameVerdict.Blank)
        assertEquals(AskFailure.CaptureBlank, verdict.asFailure())
    }

    @Test
    fun `a flat mid-grey frame is rejected too, not just black`() {
        val verdict = inspectAskFrame(768, 576, flat(argb(0xFF, 128, 128, 128)))
        assertTrue(verdict is AskFrameVerdict.Blank)
    }

    @Test
    fun `a flat white frame is rejected - a covered lens is not a question`() {
        assertTrue(
            inspectAskFrame(768, 576, flat(argb(0xFF, 255, 255, 255)))
                is AskFrameVerdict.Blank
        )
    }

    @Test
    fun `a transparent hole is named as a lost AR layer, not as a blank frame`() {
        // A tenth of the frame punched out, the rest textured.
        val samples = textured().also { pixels ->
            for (i in 0 until pixels.size / 10) pixels[i] = argb(0, 0, 0, 0)
        }
        val verdict = inspectAskFrame(768, 576, samples)
        assertTrue(verdict is AskFrameVerdict.MissingArLayer)
        assertEquals(AskFailure.CaptureMissingArLayer, verdict.asFailure())
    }

    @Test
    fun `a handful of transparent pixels does not condemn an otherwise good frame`() {
        val samples = textured().also { pixels ->
            // Well under ASK_FRAME_TRANSPARENT_FRACTION.
            for (i in 0 until 10) pixels[i] = argb(0, 0, 0, 0)
        }
        assertEquals(AskFrameVerdict.Usable, inspectAskFrame(768, 576, samples))
    }

    @Test
    fun `the transparency threshold is what the constant says it is`() {
        val total = 1_000
        val justUnder = textured(total).also { pixels ->
            val holes = (total * ASK_FRAME_TRANSPARENT_FRACTION).toInt() - 1
            for (i in 0 until holes) pixels[i] = argb(0, 0, 0, 0)
        }
        assertEquals(AskFrameVerdict.Usable, inspectAskFrame(768, 576, justUnder))

        val atThreshold = textured(total).also { pixels ->
            val holes = (total * ASK_FRAME_TRANSPARENT_FRACTION).toInt()
            for (i in 0 until holes) pixels[i] = argb(0, 0, 0, 0)
        }
        assertTrue(inspectAskFrame(768, 576, atThreshold) is AskFrameVerdict.MissingArLayer)
    }

    @Test
    fun `a fully transparent frame reads as a lost layer`() {
        val verdict = inspectAskFrame(768, 576, flat(argb(0, 0, 0, 0)))
        assertTrue(verdict is AskFrameVerdict.MissingArLayer)
        assertEquals(1f, (verdict as AskFrameVerdict.MissingArLayer).transparentFraction, 1e-6f)
    }

    @Test
    fun `a degenerate size is caught before anything else`() {
        assertEquals(
            AskFrameVerdict.TooSmall(0, 0),
            inspectAskFrame(0, 0, textured()),
        )
        assertTrue(inspectAskFrame(32, 512, textured()) is AskFrameVerdict.TooSmall)
        assertTrue(inspectAskFrame(512, 32, textured()) is AskFrameVerdict.TooSmall)
        assertEquals(
            AskFailure.CaptureFailed,
            inspectAskFrame(0, 0, textured()).asFailure(),
        )
    }

    @Test
    fun `an empty sample set is a degenerate frame, not a usable one`() {
        assertTrue(inspectAskFrame(768, 576, IntArray(0)) is AskFrameVerdict.TooSmall)
    }

    @Test
    fun `a frame at exactly the minimum edge is allowed through`() {
        assertEquals(
            AskFrameVerdict.Usable,
            inspectAskFrame(ASK_FRAME_MIN_EDGE, ASK_FRAME_MIN_EDGE, textured()),
        )
    }

    @Test
    fun `a nearly-flat frame with a few stray bright pixels is still blank`() {
        // The percentile bounds exist for this: a clipped status-bar glyph must not vouch
        // for a frame that is otherwise a black rectangle.
        val samples = flat(argb(0xFF, 2, 2, 2), count = 1_000).also { pixels ->
            for (i in 0 until 5) pixels[i] = argb(0xFF, 255, 255, 255)
        }
        assertTrue(inspectAskFrame(768, 576, samples) is AskFrameVerdict.Blank)
    }

    @Test
    fun `a real gradient is not mistaken for a flat frame`() {
        // A gentle wall-lit gradient — the closest a legitimate camera frame gets to flat.
        val samples = IntArray(1_000) { i ->
            val v = 80 + (i * 60 / 1_000)
            argb(0xFF, v, v, v)
        }
        assertEquals(AskFrameVerdict.Usable, inspectAskFrame(768, 576, samples))
    }

    @Test
    fun `the blank verdict reports the spread it measured`() {
        val verdict = inspectAskFrame(768, 576, flat(argb(0xFF, 10, 10, 10)))
        assertEquals(0, (verdict as AskFrameVerdict.Blank).lumaSpread)
    }

    @Test
    fun `only a usable frame maps to no failure`() {
        assertNull(AskFrameVerdict.Usable.asFailure())
        listOf(
            AskFrameVerdict.TooSmall(1, 1),
            AskFrameVerdict.MissingArLayer(0.5f),
            AskFrameVerdict.Blank(0),
        ).forEach { assertTrue("$it must name a failure", it.asFailure() != null) }
    }

    @Test
    fun `a colourful but equally-luminant frame still counts as content`() {
        // Luma is the probe, so guard the case where colour varies more than brightness:
        // it must not be called blank, because there is plainly something to describe.
        val samples = IntArray(1_000) { i ->
            when (i % 3) {
                0 -> argb(0xFF, 200, 20, 20)
                1 -> argb(0xFF, 20, 200, 20)
                else -> argb(0xFF, 20, 20, 200)
            }
        }
        assertEquals(AskFrameVerdict.Usable, inspectAskFrame(768, 576, samples))
    }
}
