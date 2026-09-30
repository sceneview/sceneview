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

    /**
     * Rise of the [envelope] within one window that counts as a new note — the moment a
     * sound shell leaves the orb. Tuned on the four stems: every bell, bass note and drum hit,
     * and the pad's few swells.
     */
    const val ONSET_RISE = 0.18f

    /** Level a rise has to reach to count: quiet hats and pad shimmer do not fire a shell. */
    const val ONSET_FLOOR = 0.35f

    /** Closest two onsets can be: 8 windows = 171 ms, a sixteenth note at 90 BPM. */
    const val ONSET_MIN_GAP = 8

    /**
     * For every window of [envelope], the window of the latest note that started at or before
     * it — a jump of at least [ONSET_RISE] up to at least [ONSET_FLOOR] — looking back across
     * the loop point like the audio does. All `-1` when the part has no note at all.
     */
    fun lastOnsets(envelope: FloatArray): IntArray {
        val count = envelope.size
        val result = IntArray(count) { -1 }
        if (count == 0) return result
        val onset = BooleanArray(count)
        var last = Int.MIN_VALUE / 2
        for (w in 0 until count) {
            val rise = envelope[w] - envelope[(w - 1 + count) % count]
            if (rise >= ONSET_RISE && envelope[w] >= ONSET_FLOOR && w - last >= ONSET_MIN_GAP) {
                onset[w] = true
                last = w
            }
        }
        // Before the loop's first note, the latest one is the last note of the previous pass.
        var current = onset.lastIndexOf(true)
        if (current < 0) return result
        for (w in 0 until count) {
            if (onset[w]) current = w
            result[w] = current
        }
        return result
    }

    /**
     * Seconds since the latest note heard at loop position [frame], from [lastOnsets]'s table;
     * `null` when the part has no note. Wraps: just after the loop point, the last note of the
     * previous pass is a few hundred milliseconds old, not ten seconds in the future.
     */
    fun secondsSinceOnset(lastOnsets: IntArray, frame: Long, window: Int = ENVELOPE_WINDOW): Float? {
        if (lastOnsets.isEmpty()) return null
        val loopFrames = lastOnsets.size.toLong() * window
        val loopFrame = Math.floorMod(frame, loopFrames)
        val onset = lastOnsets[(loopFrame / window).toInt()]
        if (onset < 0) return null
        val delta = Math.floorMod(loopFrame - onset.toLong() * window, loopFrames)
        return delta.toFloat() / SAMPLE_RATE
    }
}
