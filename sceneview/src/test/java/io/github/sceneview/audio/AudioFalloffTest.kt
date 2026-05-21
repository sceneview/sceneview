package io.github.sceneview.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-curve regression pins for [AudioFalloff.gainFor]. These tests are the contract
 * between Android / iOS / Web implementations — the same `(refDistance, maxDistance)`
 * inputs must produce identical gains on every platform so a SpatialAudioNode authored
 * on one platform reads the same on every other.
 *
 * The chosen `(refDistance = 1, maxDistance = 100)` curve matches the default
 * [SpatialAudioNode] parameters and is what the demos ship with.
 */
class AudioFalloffTest {

    private val tol = 1e-4f

    // ── None ─────────────────────────────────────────────────────────────────────────

    @Test fun noneReturnsUnityAtZero() {
        assertEquals(1f, AudioFalloff.gainFor(AudioFalloff.None, 0f), tol)
    }

    @Test fun noneReturnsUnityAtFarDistance() {
        assertEquals(1f, AudioFalloff.gainFor(AudioFalloff.None, 1_000_000f), tol)
    }

    @Test fun noneIgnoresNaNAndNegative() {
        assertEquals(1f, AudioFalloff.gainFor(AudioFalloff.None, Float.NaN), tol)
        assertEquals(1f, AudioFalloff.gainFor(AudioFalloff.None, -5f), tol)
    }

    // ── Inverse ──────────────────────────────────────────────────────────────────────

    @Test fun inverseUnityBelowRefDistance() {
        val f = AudioFalloff.Inverse(refDistance = 1f, maxDistance = 100f)
        assertEquals(1f, AudioFalloff.gainFor(f, 0f), tol)
        assertEquals(1f, AudioFalloff.gainFor(f, 0.5f), tol)
        assertEquals(1f, AudioFalloff.gainFor(f, 1f), tol)
    }

    @Test fun inverseHalvesAroundDoubleRef() {
        // ref / (ref + (d - ref)) = 1 / d when ref = rolloff = 1.
        val f = AudioFalloff.Inverse(refDistance = 1f, maxDistance = 100f)
        assertEquals(0.5f, AudioFalloff.gainFor(f, 2f), tol)         // 1/(1+1) = 0.5
        assertEquals(0.2f, AudioFalloff.gainFor(f, 5f), tol)         // 1/(1+4) = 0.2
        assertEquals(0.1f, AudioFalloff.gainFor(f, 10f), tol)        // 1/(1+9) = 0.1
    }

    @Test fun inverseClampsAtMaxDistance() {
        // Past maxDistance, gain stops decreasing — it stays at the gain computed at max.
        val f = AudioFalloff.Inverse(refDistance = 1f, maxDistance = 100f)
        val atMax = AudioFalloff.gainFor(f, 100f)
        val past = AudioFalloff.gainFor(f, 200f)
        assertEquals(atMax, past, tol)
    }

    @Test fun inverseRolloffFactorScalesDropoff() {
        // Higher rolloff → sharper drop. At 2× the ref distance:
        //   rolloff = 1 → 1/(1+1*1) = 0.5
        //   rolloff = 2 → 1/(1+2*1) ≈ 0.333
        val mild = AudioFalloff.Inverse(refDistance = 1f, maxDistance = 100f, rolloffFactor = 1f)
        val sharp = AudioFalloff.Inverse(refDistance = 1f, maxDistance = 100f, rolloffFactor = 2f)
        val mildGain = AudioFalloff.gainFor(mild, 2f)
        val sharpGain = AudioFalloff.gainFor(sharp, 2f)
        assertTrue("sharper rolloff must yield less gain", sharpGain < mildGain)
        assertEquals(1f / 3f, sharpGain, tol)
    }

    @Test fun inverseDegenerateRangeStaysUnity() {
        // refDistance > maxDistance is degenerate: the clamped distance can never exceed
        // maxDistance, which is below refDistance, so gain stays at unity for ALL
        // distances. Must not divide by zero or go negative.
        val f = AudioFalloff.Inverse(refDistance = 20f, maxDistance = 5f)
        assertEquals(1f, AudioFalloff.gainFor(f, 0f), tol)
        assertEquals(1f, AudioFalloff.gainFor(f, 5f), tol)
        assertEquals(1f, AudioFalloff.gainFor(f, 50f), tol)
        assertEquals(1f, AudioFalloff.gainFor(f, 10_000f), tol)
    }

    @Test fun inverseGoldenCurve() {
        val f = AudioFalloff.Inverse(refDistance = 1f, maxDistance = 100f, rolloffFactor = 1f)
        // Golden table — matches WebAudio "inverse" model with the same parameters.
        assertEquals(1.0f, AudioFalloff.gainFor(f, 0f), tol)
        assertEquals(1.0f, AudioFalloff.gainFor(f, 1f), tol)
        assertEquals(0.2f, AudioFalloff.gainFor(f, 5f), tol)
        assertEquals(0.1f, AudioFalloff.gainFor(f, 10f), tol)
        assertEquals(1f / 50f, AudioFalloff.gainFor(f, 50f), tol)
        // 100f is at maxDistance — gain = 1/100 = 0.01
        assertEquals(0.01f, AudioFalloff.gainFor(f, 100f), tol)
        // 200f is beyond max → clamped to gain at 100f
        assertEquals(0.01f, AudioFalloff.gainFor(f, 200f), tol)
    }

    // ── Linear ───────────────────────────────────────────────────────────────────────

    @Test fun linearUnityAtRefDistance() {
        val f = AudioFalloff.Linear(refDistance = 1f, maxDistance = 100f)
        assertEquals(1f, AudioFalloff.gainFor(f, 0f), tol)
        assertEquals(1f, AudioFalloff.gainFor(f, 1f), tol)
    }

    @Test fun linearReachesZeroAtMaxDistance() {
        val f = AudioFalloff.Linear(refDistance = 1f, maxDistance = 100f)
        assertEquals(0f, AudioFalloff.gainFor(f, 100f), tol)
        assertEquals(0f, AudioFalloff.gainFor(f, 200f), tol)  // clamped past max
    }

    @Test fun linearGoldenCurve() {
        val f = AudioFalloff.Linear(refDistance = 0f, maxDistance = 100f)
        // Slope is -1/100 — easy mental math.
        assertEquals(1.0f, AudioFalloff.gainFor(f, 0f), tol)
        assertEquals(0.99f, AudioFalloff.gainFor(f, 1f), tol)
        assertEquals(0.95f, AudioFalloff.gainFor(f, 5f), tol)
        assertEquals(0.9f, AudioFalloff.gainFor(f, 10f), tol)
        assertEquals(0.5f, AudioFalloff.gainFor(f, 50f), tol)
        assertEquals(0f, AudioFalloff.gainFor(f, 100f), tol)
    }

    @Test fun linearHandlesDegenerateRange() {
        // Ref == max — degenerate; the helper must not divide by zero.
        val f = AudioFalloff.Linear(refDistance = 10f, maxDistance = 10f)
        assertEquals(1f, AudioFalloff.gainFor(f, 5f), tol)
        assertEquals(0f, AudioFalloff.gainFor(f, 20f), tol)
    }

    @Test fun linearClampsToZeroOne() {
        val f = AudioFalloff.Linear(refDistance = 1f, maxDistance = 100f)
        // Negative distance treated as 0 → unity gain.
        assertEquals(1f, AudioFalloff.gainFor(f, -10f), tol)
        // Past max → zero, not negative.
        assertTrue(AudioFalloff.gainFor(f, 10_000f) >= 0f)
    }
}
