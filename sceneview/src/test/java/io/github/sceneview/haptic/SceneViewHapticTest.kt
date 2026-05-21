package io.github.sceneview.haptic

import android.os.Build
import android.os.VibrationEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM pins for the preset → platform-call mapping table documented on
 * [HapticPreset]. We don't need Robolectric: [AndroidSceneViewHaptic]
 * delegates every platform call to an internal [HapticEngine], so a
 * recording fake captures the exact call each preset issues.
 *
 * If a future refactor wants to change a preset's mapping (e.g. swap
 * `medium` from `EFFECT_TICK` to `EFFECT_CLICK`), update this test in the
 * same PR with a CHANGELOG entry under "Changed" — the mapping is part of
 * the public contract because every demo + every consumer relies on the
 * "this preset feels like this" muscle memory.
 */
class SceneViewHapticTest {

    private fun newHaptic(
        sdkInt: Int = Build.VERSION_CODES.TIRAMISU,
        hasVibrator: Boolean = true,
        hasPermission: Boolean = true,
    ): Pair<SceneViewHaptic, RecordingHapticEngine> {
        val engine = if (hasVibrator) RecordingHapticEngine(sdkInt) else null
        val haptic = AndroidSceneViewHaptic(
            engine = engine,
            hasVibratePermission = hasPermission,
        )
        return haptic to (engine ?: RecordingHapticEngine(sdkInt))
    }

    // ── Preset → platform mapping (API 29+ path) ──────────────────────────

    @Test
    fun light_onModernApi_playsEffectClick() {
        val (haptic, engine) = newHaptic(sdkInt = Build.VERSION_CODES.TIRAMISU)
        haptic.light()
        assertEquals(
            "Light → EFFECT_CLICK (API 29+)",
            listOf<HapticCall>(HapticCall.Predefined(VibrationEffect.EFFECT_CLICK)),
            engine.calls,
        )
    }

    @Test
    fun medium_onModernApi_playsEffectTick() {
        val (haptic, engine) = newHaptic()
        haptic.medium()
        assertEquals(
            listOf<HapticCall>(HapticCall.Predefined(VibrationEffect.EFFECT_TICK)),
            engine.calls,
        )
    }

    @Test
    fun heavy_onModernApi_playsEffectHeavyClick() {
        val (haptic, engine) = newHaptic()
        haptic.heavy()
        assertEquals(
            listOf<HapticCall>(HapticCall.Predefined(VibrationEffect.EFFECT_HEAVY_CLICK)),
            engine.calls,
        )
    }

    @Test
    fun success_onModernApi_playsEffectDoubleClick() {
        val (haptic, engine) = newHaptic()
        haptic.success()
        assertEquals(
            listOf<HapticCall>(HapticCall.Predefined(VibrationEffect.EFFECT_DOUBLE_CLICK)),
            engine.calls,
        )
    }

    @Test
    fun warning_playsTripleTickWaveform() {
        val (haptic, engine) = newHaptic()
        haptic.warning()
        assertEquals(1, engine.calls.size)
        val call = engine.calls.single()
        assertTrue("Warning is a waveform, was: $call", call is HapticCall.Waveform)
        val timings = (call as HapticCall.Waveform).timings.toList()
        assertEquals(listOf(0L, 30L, 30L, 30L), timings)
    }

    @Test
    fun error_playsDescendingPulseWaveform() {
        val (haptic, engine) = newHaptic()
        haptic.error()
        assertEquals(1, engine.calls.size)
        val call = engine.calls.single()
        assertTrue("Error is a waveform, was: $call", call is HapticCall.Waveform)
        val timings = (call as HapticCall.Waveform).timings.toList()
        assertEquals(listOf(0L, 50L, 30L, 50L, 30L, 50L), timings)
    }

    @Test
    fun selection_onModernApi_playsEffectTick() {
        val (haptic, engine) = newHaptic()
        haptic.selection()
        assertEquals(
            "Selection → EFFECT_TICK matches Android's haptic-on-scroll feel",
            listOf<HapticCall>(HapticCall.Predefined(VibrationEffect.EFFECT_TICK)),
            engine.calls,
        )
    }

    // ── Legacy fallback (API < 29 → predefined effects unavailable) ────────

    @Test
    fun light_onLegacyApi_fallsBackToShortOneShot() {
        val (haptic, engine) = newHaptic(sdkInt = Build.VERSION_CODES.O)
        haptic.light()
        assertEquals(1, engine.calls.size)
        val call = engine.calls.single() as HapticCall.OneShot
        assertEquals(10L, call.durationMs)
    }

    @Test
    fun success_onLegacyApi_fallsBackToDoubleTickWaveform() {
        val (haptic, engine) = newHaptic(sdkInt = Build.VERSION_CODES.O)
        haptic.success()
        val call = engine.calls.single() as HapticCall.Waveform
        assertEquals(listOf(0L, 30L, 80L, 30L), call.timings.toList())
    }

    // ── continuous() + pattern() ─────────────────────────────────────────

    @Test
    fun continuous_emitsScaledOneShot() {
        val (haptic, engine) = newHaptic()
        haptic.continuous(intensity = 0.5f, durationMs = 250L)
        val call = engine.calls.single() as HapticCall.OneShot
        assertEquals(250L, call.durationMs)
        // 0.5 * 255 = 127
        assertEquals(127, call.amplitude)
    }

    @Test
    fun continuous_clampsIntensity() {
        val (haptic, engine) = newHaptic()
        haptic.continuous(intensity = 5f, durationMs = 100L)
        val call = engine.calls.single() as HapticCall.OneShot
        assertEquals(255, call.amplitude)
    }

    @Test
    fun continuous_withZeroDuration_isNoop() {
        val (haptic, engine) = newHaptic()
        haptic.continuous(intensity = 0.5f, durationMs = 0L)
        assertEquals(emptyList<HapticCall>(), engine.calls)
    }

    @Test
    fun pattern_emitsAlternatingDelayDurationWaveformWithAmplitudes() {
        val (haptic, engine) = newHaptic()
        haptic.pattern(
            listOf(
                HapticEvent(intensity = 1.0f, sharpness = 0.5f, durationMs = 50L, delayMs = 0L),
                HapticEvent(intensity = 0.5f, sharpness = 0.0f, durationMs = 20L, delayMs = 30L),
            )
        )
        val call = engine.calls.single() as HapticCall.AmplitudeWaveform
        assertEquals(listOf(0L, 50L, 30L, 20L), call.timings.toList())
        assertEquals(listOf(0, 255, 0, 127), call.amplitudes.toList())
    }

    @Test
    fun pattern_empty_isNoop() {
        val (haptic, engine) = newHaptic()
        haptic.pattern(emptyList())
        assertEquals(emptyList<HapticCall>(), engine.calls)
    }

    // ── Edge cases ───────────────────────────────────────────────────────

    @Test
    fun withoutPermission_everyCallIsNoop() {
        val (haptic, engine) = newHaptic(hasPermission = false)
        haptic.light()
        haptic.medium()
        haptic.heavy()
        haptic.success()
        haptic.warning()
        haptic.error()
        haptic.selection()
        haptic.continuous(0.5f, 100L)
        haptic.pattern(listOf(HapticEvent(1f, 1f, 10L)))
        assertEquals(
            "Missing VIBRATE permission must short-circuit every API",
            emptyList<HapticCall>(),
            engine.calls,
        )
    }

    @Test
    fun withoutVibratorOnDevice_everyCallIsNoop() {
        val haptic = AndroidSceneViewHaptic(
            engine = null,
            hasVibratePermission = true,
        )
        // None of these must throw — the only observable behavior is the one
        // Log.d line, which is fine to skip asserting in pure JVM.
        haptic.light()
        haptic.success()
        haptic.continuous(0.5f, 100L)
        haptic.pattern(listOf(HapticEvent(1f, 1f, 10L)))
    }

    @Test
    fun nullEngineAndNoPermission_compose_areAlsoNoop() {
        val haptic = AndroidSceneViewHaptic(
            engine = null,
            hasVibratePermission = false,
        )
        haptic.heavy()  // must not throw, must not log twice (assert via no exception)
        haptic.heavy()
        haptic.heavy()
    }
}
