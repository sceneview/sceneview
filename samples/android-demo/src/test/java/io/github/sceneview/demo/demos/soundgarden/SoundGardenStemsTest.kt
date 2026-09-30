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

    @Test
    fun `a jump in the envelope is a note and the next windows point back to it`() {
        val envelope = FloatArray(40) { 0.05f }
        envelope[10] = 1f
        envelope[11] = 0.87f // the release after the hit is not a new note
        envelope[30] = 0.6f
        val last = SoundGardenStems.lastOnsets(envelope)
        assertEquals(10, last[10])
        assertEquals(10, last[11])
        assertEquals(10, last[29])
        assertEquals(30, last[30])
        // Before the first note of the loop, the latest note is the last one of the previous pass.
        assertEquals(30, last[3])
    }

    @Test
    fun `quiet rises and notes closer than a sixteenth do not fire`() {
        val envelope = FloatArray(40) { 0.05f }
        envelope[5] = 0.3f // under the floor
        envelope[12] = 1f
        envelope[14] = 1f // 2 windows after the previous note
        val last = SoundGardenStems.lastOnsets(envelope)
        assertEquals(12, last[12])
        assertEquals(12, last[14])
        assertEquals(12, last[20])
        // The only note of the loop is window 12, so window 5 looks back to it across the loop.
        assertEquals(12, last[5])
    }

    @Test
    fun `a part without notes has no onset`() {
        val flat = SoundGardenStems.lastOnsets(FloatArray(20) { 0.5f })
        assertTrue(flat.all { it == -1 })
        assertEquals(null, SoundGardenStems.secondsSinceOnset(flat, 1234L))
        assertEquals(0, SoundGardenStems.lastOnsets(FloatArray(0)).size)
    }

    @Test
    fun `time since the last note wraps across the loop point`() {
        val window = SoundGardenStems.ENVELOPE_WINDOW
        val envelope = FloatArray(500) { 0.05f }
        envelope[100] = 1f
        envelope[490] = 1f
        val last = SoundGardenStems.lastOnsets(envelope)
        val rate = SoundGardenStems.SAMPLE_RATE.toFloat()
        // 1 000 frames after the note at window 100.
        assertEquals(1_000f / rate, SoundGardenStems.secondsSinceOnset(last, 100L * window + 1_000)!!, 1e-6f)
        // Window 2 of the next pass: the note at window 490 is 12 windows old.
        val loop = 500L * window
        assertEquals(12f * window / rate, SoundGardenStems.secondsSinceOnset(last, loop + 2L * window)!!, 1e-6f)
    }
}
