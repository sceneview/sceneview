package io.github.sceneview.demo.demos.soundgarden

import kotlin.math.sqrt

/**
 * The four parts of the Sound Garden's piece and the two numbers every part shares with
 * `tools/generate-sound-garden-stems.py`, which synthesizes them.
 */
object SoundGardenStems {

    /** Rate the stems are written at, and the rate the mix runs at. */
    const val SAMPLE_RATE = 48_000

    /**
     * Frames in one loop: 4 bars of 4/4 at 90 BPM = 16 beats × 32 000 frames = 10.667 s.
     * Must equal `LOOP` in the generator — every stem is cut or padded to exactly this.
     */
    const val LOOP_FRAMES = 512_000

    /** RMS window of the visual envelope: 21 ms, 500 windows per loop. */
    const val ENVELOPE_WINDOW = 1_024

    /**
     * Per-window decay of [envelope]'s peak hold: a hit lights its orb at once and lets go over
     * ≈ 150 ms, so a 30 Hz frame loop never misses a drum hit that lives for 50 ms.
     */
    private const val ENVELOPE_RELEASE = 0.87f

    /**
     * Cuts or zero-pads a decoded stem to exactly [LOOP_FRAMES]. A Vorbis decoder may return a
     * few frames more or less than were encoded; the mix needs every part the same length.
     */
    fun fitToLoop(pcm: FloatArray): FloatArray =
        if (pcm.size == LOOP_FRAMES) pcm else pcm.copyOf(LOOP_FRAMES)

    /**
     * The loudness contour that drives an orb's pulse: RMS per [window] frames, held with a
     * fast attack and a [ENVELOPE_RELEASE] decay, then normalised so each part's loudest
     * moment is 1. The hold wraps around the loop, like the audio does.
     */
    fun envelope(stem: FloatArray, window: Int = ENVELOPE_WINDOW): FloatArray {
        require(window > 0) { "window must be positive" }
        val count = (stem.size + window - 1) / window
        if (count == 0) return FloatArray(0)
        val rms = FloatArray(count) { w ->
            val from = w * window
            val to = minOf(from + window, stem.size)
            var sum = 0.0
            for (i in from until to) sum += stem[i].toDouble() * stem[i]
            sqrt(sum / (to - from)).toFloat()
        }
        // Two passes so the hold carried over the loop point is settled when the second pass starts.
        val held = FloatArray(count)
        var level = 0f
        repeat(2) {
            for (w in 0 until count) {
                level = maxOf(rms[w], level * ENVELOPE_RELEASE)
                held[w] = level
            }
        }
        val peak = held.max()
        if (peak > 0f) for (w in held.indices) held[w] /= peak
        return held
    }

    /** The envelope value heard at loop position [frame]. */
    fun envelopeAt(envelope: FloatArray, frame: Long, window: Int = ENVELOPE_WINDOW): Float {
        if (envelope.isEmpty()) return 0f
        val loopFrame = Math.floorMod(frame, LOOP_FRAMES.toLong())
        return envelope[(loopFrame / window).toInt().coerceAtMost(envelope.size - 1)]
    }
}
