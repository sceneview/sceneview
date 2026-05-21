package io.github.sceneview.haptic

/**
 * Test fake for [HapticEngine] that records every call instead of touching a
 * real [android.os.Vibrator]. Used by [SceneViewHapticTest] so the
 * preset → platform mapping can be pinned on a pure JVM unit test (no
 * Robolectric).
 */
internal class RecordingHapticEngine(override val sdkInt: Int) : HapticEngine {
    private val _calls = mutableListOf<HapticCall>()
    val calls: List<HapticCall> get() = _calls

    override fun playPredefined(effectId: Int) {
        _calls += HapticCall.Predefined(effectId)
    }

    override fun playWaveform(timings: LongArray, repeat: Int) {
        _calls += HapticCall.Waveform(timings, repeat)
    }

    override fun playAmplitudeWaveform(timings: LongArray, amplitudes: IntArray, repeat: Int) {
        _calls += HapticCall.AmplitudeWaveform(timings, amplitudes, repeat)
    }

    override fun playOneShot(durationMs: Long, amplitude: Int) {
        _calls += HapticCall.OneShot(durationMs, amplitude)
    }
}

internal sealed class HapticCall {
    data class Predefined(val effectId: Int) : HapticCall()
    class Waveform(val timings: LongArray, val repeat: Int) : HapticCall() {
        override fun equals(other: Any?): Boolean = other is Waveform &&
            timings.contentEquals(other.timings) && repeat == other.repeat
        override fun hashCode(): Int = 31 * timings.contentHashCode() + repeat
        override fun toString(): String =
            "Waveform(timings=${timings.toList()}, repeat=$repeat)"
    }
    class AmplitudeWaveform(
        val timings: LongArray,
        val amplitudes: IntArray,
        val repeat: Int,
    ) : HapticCall() {
        override fun equals(other: Any?): Boolean = other is AmplitudeWaveform &&
            timings.contentEquals(other.timings) &&
            amplitudes.contentEquals(other.amplitudes) &&
            repeat == other.repeat
        override fun hashCode(): Int {
            var h = timings.contentHashCode()
            h = 31 * h + amplitudes.contentHashCode()
            h = 31 * h + repeat
            return h
        }
        override fun toString(): String =
            "AmplitudeWaveform(timings=${timings.toList()}, amplitudes=${amplitudes.toList()}, repeat=$repeat)"
    }
    data class OneShot(val durationMs: Long, val amplitude: Int) : HapticCall()
}
