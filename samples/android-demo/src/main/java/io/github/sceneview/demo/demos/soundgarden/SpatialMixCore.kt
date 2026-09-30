package io.github.sceneview.demo.demos.soundgarden

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.tanh

/**
 * The Sound Garden mixer: N mono loops of the same length, one shared playhead, each voice
 * placed by its own [VoiceParams], summed into interleaved stereo float.
 *
 * Pure Kotlin on purpose — no `AudioTrack`, no thread — so the whole signal path is exercised
 * by JVM tests; [SoundGardenAudio] only feeds [render]'s output to the device.
 *
 * **One playhead** is the point of the demo: the four stems are four parts of one piece, and
 * they must stay sample-locked forever. Four `MediaPlayer`s (the SDK's `SpatialAudioNode`
 * phase 1) each loop on their own clock and drift apart within a minute.
 *
 * **Threading.** [setTarget] is called from the AR frame callback; [render] from the audio
 * thread. The hand-over is one `@Volatile` reference to an immutable [VoiceParams] per voice —
 * no lock on the audio thread, ever. [render] then ramps every parameter linearly from its
 * value at the end of the previous block to the new target across the block, so a camera pose
 * that updates at 30 Hz never reaches the ear as a 30 Hz zipper.
 */
class SpatialMixCore(
    private val sampleRate: Int,
    stems: List<FloatArray>,
    private val masterGain: Float = MASTER_GAIN,
) {
    init {
        require(stems.isNotEmpty()) { "At least one stem" }
        require(stems.all { it.size == stems.first().size }) {
            "Every stem must be exactly the same length — they are parts of one loop"
        }
    }

    /** Frames in the shared loop. */
    val loopFrames: Int = stems.first().size

    private val voices = stems.map { Voice(it, lowpassCoefficient(SpatialVoiceMath.FRONT_CUTOFF_HZ)) }

    /** Number of voices, in the order the stems were given. */
    val voiceCount: Int get() = voices.size

    /** Next frame of the loop [render] will read. Audio thread only. */
    var playhead: Int = 0
        private set

    /** Hands a new target to voice [index]; the next [render] block ramps to it. Any thread. */
    fun setTarget(index: Int, params: VoiceParams) {
        voices[index].target = params
    }

    /**
     * Renders [frames] frames of interleaved stereo (L, R, L, R…) into [out], overwriting it,
     * and advances the playhead. [out] must hold at least `2 × frames` floats.
     */
    fun render(out: FloatArray, frames: Int) {
        require(out.size >= frames * 2) { "Output buffer too small" }
        java.util.Arrays.fill(out, 0, frames * 2, 0f)
        if (frames == 0) return
        for (voice in voices) voice.renderInto(out, frames, playhead)
        for (i in 0 until frames * 2) out[i] = softClip(out[i] * masterGain)
        playhead = (playhead + frames) % loopFrames
    }

    private fun lowpassCoefficient(cutoffHz: Float): Float =
        (1.0 - exp(-2.0 * PI * cutoffHz / sampleRate)).toFloat()

    private inner class Voice(private val samples: FloatArray, initialCoefficient: Float) {
        @Volatile
        var target: VoiceParams = VoiceParams.SILENT

        private var gainLeft = 0f
        private var gainRight = 0f
        private var delayLeft = 0f      // frames
        private var delayRight = 0f     // frames
        private var coefficient = initialCoefficient
        private var lowpassState = 0f
        private val history = FloatArray(HISTORY_FRAMES)
        private var write = 0

        fun renderInto(out: FloatArray, frames: Int, start: Int) {
            val t = target
            val targetDelayLeft = (t.delayLeftSec * sampleRate).coerceIn(0f, MAX_DELAY_FRAMES)
            val targetDelayRight = (t.delayRightSec * sampleRate).coerceIn(0f, MAX_DELAY_FRAMES)
            val targetCoefficient = lowpassCoefficient(t.cutoffHz)
            val step = 1f / frames
            val dGainLeft = (t.gainLeft - gainLeft) * step
            val dGainRight = (t.gainRight - gainRight) * step
            val dDelayLeft = (targetDelayLeft - delayLeft) * step
            val dDelayRight = (targetDelayRight - delayRight) * step
            val dCoefficient = (targetCoefficient - coefficient) * step
            // Silent at both ends of the block (not planted yet, or muted): skip it. Its filter
            // and delay line resume where they stopped, but the gain ramp then starts from zero,
            // so that stale state never reaches the ear.
            if (gainLeft == 0f && gainRight == 0f && t.gainLeft == 0f && t.gainRight == 0f) {
                delayLeft = targetDelayLeft
                delayRight = targetDelayRight
                coefficient = targetCoefficient
                return
            }
            var frame = start
            for (i in 0 until frames) {
                gainLeft += dGainLeft
                gainRight += dGainRight
                delayLeft += dDelayLeft
                delayRight += dDelayRight
                coefficient += dCoefficient
                lowpassState += coefficient * (samples[frame] - lowpassState)
                history[write] = lowpassState
                out[2 * i] += tap(delayLeft) * gainLeft
                out[2 * i + 1] += tap(delayRight) * gainRight
                write = (write + 1) and HISTORY_MASK
                frame++
                if (frame == loopFrames) frame = 0
            }
            // Land exactly on the target — no drift from summing float steps.
            gainLeft = t.gainLeft
            gainRight = t.gainRight
            delayLeft = targetDelayLeft
            delayRight = targetDelayRight
            coefficient = targetCoefficient
        }

        /** The filtered signal [delay] frames ago, linearly interpolated between two frames. */
        private fun tap(delay: Float): Float {
            val position = write - delay
            val base = floor(position).toInt()
            val fraction = position - base
            val a = history[base and HISTORY_MASK]
            val b = history[(base + 1) and HISTORY_MASK]
            return a + (b - a) * fraction
        }
    }

    companion object {
        /**
         * +6 dB on the sum. The distance law already takes most of it back: from where the user
         * first stands (≈ 1.2 m from the near orbs, 2.2 m from the far ones) the four parts sit
         * at −9 to −15 dB, and walking into one brings it to unity — and to [softClip]'s knee.
         */
        const val MASTER_GAIN = 2f

        /** Level above which [softClip] starts bending the waveform. */
        const val SOFT_CLIP_KNEE = 0.85f

        /** Delay-line length — a power of two above the largest interaural delay at 48 kHz. */
        private const val HISTORY_FRAMES = 64
        private const val HISTORY_MASK = HISTORY_FRAMES - 1
        private const val MAX_DELAY_FRAMES = (HISTORY_FRAMES - 2).toFloat()

        /**
         * Identity below [SOFT_CLIP_KNEE], then a tanh shoulder that approaches ±1 and never
         * crosses it. Continuous in value and slope at the knee, so it is inaudible until the
         * sum really is too hot — where a hard clip would crackle.
         */
        fun softClip(x: Float): Float {
            val magnitude = abs(x)
            if (magnitude <= SOFT_CLIP_KNEE) return x
            val headroom = 1f - SOFT_CLIP_KNEE
            return sign(x) * (SOFT_CLIP_KNEE + headroom * tanh((magnitude - SOFT_CLIP_KNEE) / headroom))
        }
    }
}
