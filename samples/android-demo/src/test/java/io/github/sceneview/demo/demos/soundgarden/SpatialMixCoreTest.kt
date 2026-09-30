package io.github.sceneview.demo.demos.soundgarden

import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.audio.AudioFalloff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The mixer end to end, on synthetic stems: that a voice placed on the right comes out of the
 * right channel, that the rear low-pass really removes highs, that every stem advances on the
 * same playhead, and that the output never leaves [-1, 1].
 */
class SpatialMixCoreTest {

    private val rate = 48_000
    private val loop = 4_800 // 100 ms: long enough for every filter to settle

    private val listener = SpatialVoiceMath.listenerFrame(
        cameraPosition = Float3(0f, 1.5f, 0f),
        cameraForward = Float3(0f, 0f, -1f),
        cameraUp = Float3(0f, 1f, 0f),
    )
    private val falloff = AudioFalloff.Inverse(refDistance = 0.5f, maxDistance = 8f, rolloffFactor = 1.3f)

    private fun tone(hz: Float, amplitude: Float = 0.25f) =
        FloatArray(loop) { amplitude * sin(2.0 * PI * hz * it / rate).toFloat() }

    /** Renders [blocks] blocks of 256 frames and returns the last one, interleaved. */
    private fun renderSettled(core: SpatialMixCore, blocks: Int = 8): FloatArray {
        val out = FloatArray(512)
        repeat(blocks) { core.render(out, 256) }
        return out
    }

    private fun energy(out: FloatArray, channel: Int): Double {
        var sum = 0.0
        for (i in channel until out.size step 2) sum += out[i].toDouble() * out[i]
        return sum
    }

    @Test
    fun `a voice on the right renders into the right channel`() {
        val core = SpatialMixCore(rate, listOf(tone(440f)))
        core.setTarget(0, SpatialVoiceMath.voice(listener, Float3(1f, 1.5f, 0f), falloff))
        val out = renderSettled(core)
        val left = energy(out, 0)
        val right = energy(out, 1)
        assertTrue("right $right must dominate left $left", right > left * 10.0)
    }

    @Test
    fun `a voice ahead renders equally in both channels`() {
        val core = SpatialMixCore(rate, listOf(tone(440f)))
        core.setTarget(0, SpatialVoiceMath.voice(listener, Float3(0f, 1.5f, -1f), falloff))
        val out = renderSettled(core)
        assertEquals(1.0, energy(out, 0) / energy(out, 1), 1e-3)
    }

    @Test
    fun `the rear low-pass removes highs and keeps lows`() {
        fun level(hz: Float, z: Float): Double {
            val core = SpatialMixCore(rate, listOf(tone(hz)))
            core.setTarget(0, SpatialVoiceMath.voice(listener, Float3(0f, 1.5f, z), falloff))
            return energy(renderSettled(core), 0)
        }
        // 8 kHz loses far more going behind than 200 Hz does: the source turns darker, not just quieter.
        val highDrop = level(8_000f, 1f) / level(8_000f, -1f)
        val lowDrop = level(200f, 1f) / level(200f, -1f)
        assertTrue("8 kHz behind/ahead = $highDrop must be far below 200 Hz's $lowDrop", highDrop < lowDrop * 0.3)
    }

    @Test
    fun `a silent voice adds nothing`() {
        val core = SpatialMixCore(rate, listOf(tone(440f)))
        val out = renderSettled(core)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `every stem shares one playhead that wraps at the loop`() {
        val core = SpatialMixCore(rate, listOf(tone(440f), tone(660f)))
        assertEquals(loop, core.loopFrames)
        val out = FloatArray(512)
        repeat(20) { core.render(out, 256) } // 5 120 frames = one loop + 320
        assertEquals(5_120 % loop, core.playhead)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stems of different lengths are refused`() {
        SpatialMixCore(rate, listOf(FloatArray(100), FloatArray(101)))
    }

    @Test
    fun `gain changes are ramped across the block, not stepped`() {
        val dc = FloatArray(loop) { 0.5f }
        val core = SpatialMixCore(rate, listOf(dc), masterGain = 1f)
        core.setTarget(0, VoiceParams(1f, 1f, 0f, 0f, SpatialVoiceMath.FRONT_CUTOFF_HZ))
        val out = FloatArray(512)
        core.render(out, 256)
        // From silence to full over one block: each frame only a little louder than the last.
        var previous = 0f
        for (i in 0 until 256) {
            val sample = out[2 * i]
            assertTrue("frame $i jumped from $previous to $sample", sample - previous < 0.05f)
            previous = sample
        }
    }

    @Test
    fun `the soft clip is transparent below the knee and bounded above it`() {
        assertEquals(0.5f, SpatialMixCore.softClip(0.5f), 0f)
        assertEquals(-0.8f, SpatialMixCore.softClip(-0.8f), 0f)
        for (x in listOf(0.9f, 1.5f, 4f, 100f)) {
            val y = SpatialMixCore.softClip(x)
            assertTrue("clip($x) = $y", y > SpatialMixCore.SOFT_CLIP_KNEE && y <= 1f)
            assertEquals(-y, SpatialMixCore.softClip(-x), 0f)
        }
    }

    @Test
    fun `four loud voices up close never leave the unit range`() {
        val stems = List(4) { FloatArray(loop) { i -> if (i % 2 == 0) 0.9f else -0.9f } }
        val core = SpatialMixCore(rate, stems)
        repeat(4) { core.setTarget(it, SpatialVoiceMath.voice(listener, Float3(0f, 1.5f, -0.3f), falloff)) }
        val out = renderSettled(core)
        assertTrue(out.all { abs(it) <= 1f })
    }
}
