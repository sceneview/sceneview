package io.github.sceneview.demo.demos.soundgarden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The loop contract between the generator and the mixer, and the envelope behind the pulse. */
class SoundGardenStemsTest {

    @Test
    fun `the loop is 16 beats at 90 BPM`() {
        val beatFrames = SoundGardenStems.SAMPLE_RATE * 60 / 90
        assertEquals(16 * beatFrames, SoundGardenStems.LOOP_FRAMES)
    }

    @Test
    fun `the generator writes the same loop length`() {
        // The two numbers live in two languages; this is the test that keeps them one number.
        val script = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "tools/generate-sound-garden-stems.py") }
            .firstOrNull { it.isFile }
            ?: return // not reachable from this test's working directory — nothing to compare
        val text = script.readText()
        assertTrue(text.contains("SR = 48_000"))
        assertTrue(text.contains("BPM = 90"))
        assertTrue(text.contains("BARS = 4"))
    }

    @Test
    fun `a decoded stem is fitted to exactly one loop`() {
        assertEquals(SoundGardenStems.LOOP_FRAMES, SoundGardenStems.fitToLoop(FloatArray(511_000)).size)
        assertEquals(SoundGardenStems.LOOP_FRAMES, SoundGardenStems.fitToLoop(FloatArray(513_000)).size)
        val exact = FloatArray(SoundGardenStems.LOOP_FRAMES)
        assertTrue(SoundGardenStems.fitToLoop(exact) === exact)
    }

    @Test
    fun `the envelope peaks at 1 where the stem is loudest`() {
        val window = SoundGardenStems.ENVELOPE_WINDOW
        // Silence, one loud window, silence.
        val stem = FloatArray(window * 60)
        for (i in window * 5 until window * 6) stem[i] = if (i % 2 == 0) 0.8f else -0.8f
        val envelope = SoundGardenStems.envelope(stem)
        assertEquals(60, envelope.size)
        assertEquals(1f, envelope[5], 1e-6f)
        // The hold wraps around the loop like the audio does, so the window before the hit only
        // sees the hit's tail from almost a whole loop ago: next to nothing.
        assertTrue(envelope[4] < 0.01f)
        // Released, not cut: the hit lingers for a few windows, then fades.
        assertTrue(envelope[6] in 0.5f..0.99f)
        assertTrue(envelope[7] < envelope[6])
        assertTrue(envelope[40] < 0.1f)
    }

    @Test
    fun `envelope lookup follows the played position and wraps with the loop`() {
        val envelope = FloatArray(SoundGardenStems.LOOP_FRAMES / SoundGardenStems.ENVELOPE_WINDOW) { it.toFloat() }
        assertEquals(0f, SoundGardenStems.envelopeAt(envelope, 0L), 0f)
        assertEquals(3f, SoundGardenStems.envelopeAt(envelope, 3L * SoundGardenStems.ENVELOPE_WINDOW + 10), 0f)
        val oneLoopLater = SoundGardenStems.LOOP_FRAMES.toLong() + 3L * SoundGardenStems.ENVELOPE_WINDOW
        assertEquals(3f, SoundGardenStems.envelopeAt(envelope, oneLoopLater), 0f)
    }
}
