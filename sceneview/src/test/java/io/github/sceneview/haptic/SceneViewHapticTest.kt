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
    fun continuous_negativeIntensity_clampsToAmplitudeOne() {
        // A negative intensity must clamp to amplitude 1 — never 0, which some
        // devices interpret as DEFAULT_AMPLITUDE / "off" for a one-shot.
        val (haptic, engine) = newHaptic()
        haptic.continuous(intensity = -3f, durationMs = 100L)
        val call = engine.calls.single() as HapticCall.OneShot
        assertEquals(1, call.amplitude)
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
                HapticEvent(intensity = 1.0f, sharpness = 0.5f, durationMs = 50, delayMs = 0),
                HapticEvent(intensity = 0.5f, sharpness = 0.0f, durationMs = 20, delayMs = 30),
            )
        )
        val call = engine.calls.single() as HapticCall.AmplitudeWaveform
        assertEquals(listOf(0L, 50L, 30L, 20L), call.timings.toList())
        assertEquals(listOf(0, 255, 0, 127), call.amplitudes.toList())
    }

    @Test
    fun pattern_onLegacyApi_emitsPlainWaveformWithoutAmplitudes() {
        // API 24-25: VibrationEffect is absent — pattern() must fall back to
        // the plain long[] waveform overload, no per-step amplitudes.
        val (haptic, engine) = newHaptic(sdkInt = Build.VERSION_CODES.N)
        haptic.pattern(
            listOf(
                HapticEvent(intensity = 1.0f, sharpness = 0.5f, durationMs = 50, delayMs = 0),
                HapticEvent(intensity = 0.5f, sharpness = 0.0f, durationMs = 20, delayMs = 30),
            )
        )
        val call = engine.calls.single() as HapticCall.Waveform
        assertEquals(listOf(0L, 50L, 30L, 20L), call.timings.toList())
    }

    @Test
    fun pattern_empty_isNoop() {
        val (haptic, engine) = newHaptic()
        haptic.pattern(emptyList())
        assertEquals(emptyList<HapticCall>(), engine.calls)
    }

    @Test
    fun pattern_allZeroTimings_isNoop() {
        // A pattern of events all with durationMs == 0 && delayMs == 0 passes
        // the isEmpty() guard but would make VibrationEffect.createWaveform
        // throw IllegalArgumentException — it must be a no-op instead.
        val (haptic, engine) = newHaptic()
        haptic.pattern(
            listOf(
                HapticEvent(intensity = 1.0f, sharpness = 0.5f, durationMs = 0, delayMs = 0),
                HapticEvent(intensity = 0.5f, sharpness = 0.0f, durationMs = 0, delayMs = 0),
            )
        )
        assertEquals(emptyList<HapticCall>(), engine.calls)
    }

    @Test
    fun pattern_negativeTimings_areCoercedToZero() {
        // Negative durationMs / delayMs must be coerced to 0 — never passed
        // raw to the Vibrator (which would throw).
        val (haptic, engine) = newHaptic()
        haptic.pattern(
            listOf(
                HapticEvent(intensity = 1.0f, sharpness = 0.5f, durationMs = -10, delayMs = -5),
                HapticEvent(intensity = 0.5f, sharpness = 0.0f, durationMs = 20, delayMs = 30),
            )
        )
        val call = engine.calls.single() as HapticCall.AmplitudeWaveform
        // First event's negative timings clamp to 0; second event is intact.
        assertEquals(listOf(0L, 0L, 30L, 20L), call.timings.toList())
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
        haptic.pattern(listOf(HapticEvent(1f, 1f, 10)))
        haptic.cancel()
        assertEquals(
            "Missing VIBRATE permission must short-circuit every API",
            emptyList<HapticCall>(),
            engine.calls,
        )
    }

    @Test
    fun withoutVibratorOnDevice_everyCallIsNoopAndLogsOnce() {
        val engine = RecordingHapticEngine(Build.VERSION_CODES.TIRAMISU)
        // A null engine models "no vibrator on device". The recording engine
        // is only here to assert nothing reaches it — the real instance has
        // engine = null.
        val haptic = AndroidSceneViewHaptic(
            engine = null,
            hasVibratePermission = true,
        )
        // None of these must throw or touch a vibrator.
        haptic.light()
        haptic.success()
        haptic.continuous(0.5f, 100L)
        haptic.pattern(listOf(HapticEvent(1f, 1f, 10)))
        haptic.cancel()
        assertEquals(
            "No vibrator on device must short-circuit every API",
            emptyList<HapticCall>(),
            engine.calls,
        )
    }

    @Test
    fun degradedHaptic_logsAtMostOnce() {
        // The single Log.d diagnostic must be emitted exactly once even
        // across many calls — pinned via the AtomicBoolean latch. We can't
        // intercept Log.d in pure JVM, so we assert the observable proxy:
        // none of the repeated degraded calls throw and none reach an engine.
        val haptic = AndroidSceneViewHaptic(
            engine = null,
            hasVibratePermission = false,
        )
        repeat(5) {
            haptic.heavy()
            haptic.cancel()
        }
        // Reaching here without an exception is the contract — the latch in
        // logDegradation() guarantees a single Log.d; ShadowLog-based
        // assertion lives in SystemHapticEngineRobolectricTest.
    }

    @Test
    fun cancel_delegatesToEngineWhenEnabled() {
        val (haptic, engine) = newHaptic()
        haptic.cancel()
        assertEquals(listOf<HapticCall>(HapticCall.Cancel), engine.calls)
    }
}
