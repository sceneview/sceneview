package io.github.sceneview.demo.demos.soundgarden

import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.audio.AudioFalloff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Sound Garden's binaural cues as numbers: what the ear gets for a source at a given place
 * relative to the head. Audio cannot be captured as proof, so these are the proof that "left"
 * is left, "behind" is darker, and "closer" is louder.
 */
class SpatialVoiceMathTest {

    private val falloff = AudioFalloff.Inverse(refDistance = 0.5f, maxDistance = 8f, rolloffFactor = 1.3f)

    /** Listener at the origin, head height 1.5 m, facing −Z (ARCore's default forward). */
    private val facingMinusZ = SpatialVoiceMath.listenerFrame(
        cameraPosition = Float3(0f, 1.5f, 0f),
        cameraForward = Float3(0f, 0f, -1f),
        cameraUp = Float3(0f, 1f, 0f),
    )

    /** Same place, turned around to face +Z. */
    private val facingPlusZ = SpatialVoiceMath.listenerFrame(
        cameraPosition = Float3(0f, 1.5f, 0f),
        cameraForward = Float3(0f, 0f, 1f),
        cameraUp = Float3(0f, 1f, 0f),
    )

    private fun at(x: Float, z: Float) = Float3(x, 1.5f, z)

    // ── Listener frame ──────────────────────────────────────────────────────────────────

    @Test
    fun `facing minus Z puts the right ear on plus X`() {
        assertVector(Float3(0f, 0f, -1f), facingMinusZ.forward)
        assertVector(Float3(1f, 0f, 0f), facingMinusZ.right)
    }

    @Test
    fun `a phone tilted down keeps the facing direction level`() {
        // Pitched 40° down, still facing −Z: the frame must not tilt with it.
        val pitch = Math.toRadians(40.0).toFloat()
        val frame = SpatialVoiceMath.listenerFrame(
            cameraPosition = Float3(0f, 1.5f, 0f),
            cameraForward = Float3(0f, -sin(pitch), -cos(pitch)),
            cameraUp = Float3(0f, cos(pitch), -sin(pitch)),
        )
        assertVector(Float3(0f, 0f, -1f), frame.forward)
        assertVector(Float3(1f, 0f, 0f), frame.right)
    }

    @Test
    fun `a phone pointing straight at the floor still knows where the user faces`() {
        // Camera straight down; the top edge of the screen points where the user faces (+X).
        val frame = SpatialVoiceMath.listenerFrame(
            cameraPosition = Float3(0f, 1.5f, 0f),
            cameraForward = Float3(0f, -1f, 0f),
            cameraUp = Float3(1f, 0f, 0f),
        )
        assertVector(Float3(1f, 0f, 0f), frame.forward)
        assertVector(Float3(0f, 0f, 1f), frame.right)
    }

    @Test
    fun `a phone pointing at the ceiling faces the other way from its top edge`() {
        // Looking straight up while facing −Z: the top of the phone tips back towards +Z.
        val frame = SpatialVoiceMath.listenerFrame(
            cameraPosition = Float3(0f, 1.5f, 0f),
            cameraForward = Float3(0f, 1f, 0f),
            cameraUp = Float3(0f, 0f, 1f),
        )
        assertVector(Float3(0f, 0f, -1f), frame.forward)
    }

    // ── Pan ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a source on the right is louder in the right ear`() {
        val v = SpatialVoiceMath.voice(facingMinusZ, at(1f, 0f), falloff)
        assertTrue("right ${v.gainRight} must beat left ${v.gainLeft}", v.gainRight > v.gainLeft * 3f)
    }

    @Test
    fun `a source on the left is louder in the left ear`() {
        val v = SpatialVoiceMath.voice(facingMinusZ, at(-1f, 0f), falloff)
        assertTrue("left ${v.gainLeft} must beat right ${v.gainRight}", v.gainLeft > v.gainRight * 3f)
    }

    @Test
    fun `turning around swaps the ears`() {
        val source = at(1f, -0.3f)
        val before = SpatialVoiceMath.voice(facingMinusZ, source, falloff)
        val after = SpatialVoiceMath.voice(facingPlusZ, source, falloff)
        assertTrue(before.gainRight > before.gainLeft)
        assertTrue(after.gainLeft > after.gainRight)
    }

    @Test
    fun `a source dead ahead is centred`() {
        val v = SpatialVoiceMath.voice(facingMinusZ, at(0f, -1f), falloff)
        assertEquals(v.gainLeft, v.gainRight, 1e-5f)
        assertEquals(0f, v.delayLeftSec, 0f)
        assertEquals(0f, v.delayRightSec, 0f)
    }

    @Test
    fun `the pan keeps constant power across the whole arc`() {
        for (step in -10..10) {
            val (left, right) = SpatialVoiceMath.panGains(step / 10f)
            assertEquals("lateral ${step / 10f}", 1f, left * left + right * right, 1e-5f)
        }
    }

    @Test
    fun `a source at 90 degrees still leaks into the far ear`() {
        val (left, right) = SpatialVoiceMath.panGains(1f)
        assertTrue("far ear must not vanish, got $left", left > 0.1f)
        assertTrue(right > 0.95f)
    }

    // ── Interaural time difference ──────────────────────────────────────────────────────

    @Test
    fun `the far ear is the one delayed`() {
        val right = SpatialVoiceMath.voice(facingMinusZ, at(1f, 0f), falloff)
        assertTrue(right.delayLeftSec > 0f)
        assertEquals(0f, right.delayRightSec, 0f)
        val left = SpatialVoiceMath.voice(facingMinusZ, at(-1f, 0f), falloff)
        assertTrue(left.delayRightSec > 0f)
        assertEquals(0f, left.delayLeftSec, 0f)
    }

    @Test
    fun `the interaural delay peaks at about 0,66 ms at 90 degrees`() {
        assertEquals(0.000656f, SpatialVoiceMath.MAX_ITD_SEC, 0.000005f)
        assertEquals(SpatialVoiceMath.MAX_ITD_SEC, SpatialVoiceMath.interauralDelaySec(1f), 1e-7f)
        assertEquals(-SpatialVoiceMath.MAX_ITD_SEC, SpatialVoiceMath.interauralDelaySec(-1f), 1e-7f)
        var previous = -1f
        for (step in 0..10) {
            val itd = SpatialVoiceMath.interauralDelaySec(step / 10f)
            assertTrue("ITD must grow with the angle", itd > previous)
            assertTrue(itd <= SpatialVoiceMath.MAX_ITD_SEC + 1e-7f)
            previous = itd
        }
    }

    // ── Front / back ────────────────────────────────────────────────────────────────────

    @Test
    fun `a source behind is darker and quieter than the same source ahead`() {
        val ahead = SpatialVoiceMath.voice(facingMinusZ, at(0f, -1f), falloff)
        val behind = SpatialVoiceMath.voice(facingMinusZ, at(0f, 1f), falloff)
        assertEquals(SpatialVoiceMath.FRONT_CUTOFF_HZ, ahead.cutoffHz, 1f)
        assertEquals(SpatialVoiceMath.REAR_CUTOFF_HZ, behind.cutoffHz, 1f)
        assertTrue(behind.gainLeft + behind.gainRight < ahead.gainLeft + ahead.gainRight)
        // Same place, same ears: front/back must not leak into left/right.
        assertEquals(behind.gainLeft, behind.gainRight, 1e-5f)
    }

    @Test
    fun `the rear cutoff falls steadily as the source moves behind`() {
        var previous = Float.MAX_VALUE
        for (step in 0..10) {
            val cutoff = SpatialVoiceMath.rearCutoffHz(-step / 10f)
            assertTrue(cutoff <= previous)
            previous = cutoff
        }
        assertEquals(SpatialVoiceMath.FRONT_CUTOFF_HZ, SpatialVoiceMath.rearCutoffHz(0.5f), 1e-3f)
    }

    // ── Distance and level ──────────────────────────────────────────────────────────────

    @Test
    fun `walking up to a source makes it louder, never softer`() {
        var previous = -1f
        for (step in 40 downTo 1) {
            val distance = step / 10f
            val v = SpatialVoiceMath.voice(facingMinusZ, at(0f, -distance), falloff)
            val power = v.gainLeft * v.gainLeft + v.gainRight * v.gainRight
            assertTrue("gain at $distance m must be ≥ gain further away", power >= previous)
            previous = power
        }
    }

    @Test
    fun `inside the reference distance the source is at full level`() {
        val v = SpatialVoiceMath.voice(facingMinusZ, at(0f, -0.4f), falloff)
        assertEquals(1f, sqrt(v.gainLeft * v.gainLeft + v.gainRight * v.gainRight), 1e-5f)
    }

    @Test
    fun `level scales the voice and zero silences it`() {
        val full = SpatialVoiceMath.voice(facingMinusZ, at(0.5f, -1f), falloff, level = 1f)
        val half = SpatialVoiceMath.voice(facingMinusZ, at(0.5f, -1f), falloff, level = 0.5f)
        val none = SpatialVoiceMath.voice(facingMinusZ, at(0.5f, -1f), falloff, level = 0f)
        assertEquals(full.gainLeft / 2f, half.gainLeft, 1e-6f)
        assertEquals(full.gainRight / 2f, half.gainRight, 1e-6f)
        assertEquals(0f, none.gainLeft, 0f)
        assertEquals(0f, none.gainRight, 0f)
    }

    @Test
    fun `a source inside the head is centred, not NaN`() {
        val v = SpatialVoiceMath.voice(facingMinusZ, facingMinusZ.position, falloff)
        assertEquals(v.gainLeft, v.gainRight, 0f)
        assertTrue(!v.gainLeft.isNaN())
    }

    private fun assertVector(expected: Float3, actual: Float3) {
        assertEquals("x", expected.x, actual.x, 1e-4f)
        assertEquals("y", expected.y, actual.y, 1e-4f)
        assertEquals("z", expected.z, actual.z, 1e-4f)
    }
}
