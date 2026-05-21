package io.github.sceneview.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowVibrator

/**
 * Robolectric pins for [SystemHapticEngine]'s `SDK_INT` branching.
 *
 * The pure-JVM [SceneViewHapticTest] uses [RecordingHapticEngine], which
 * *bypasses* every `SystemHapticEngine` `SDK_INT` branch — that gap is
 * exactly what let the API 24-25 `VibrationEffect.DEFAULT_AMPLITUDE`
 * `NoClassDefFoundError` ship (PR #1921 review, BLOCKING 1). These tests
 * drive the real [SystemHapticEngine] against a Robolectric-shadowed
 * [Vibrator], parameterised by SDK level via `@Config(sdk = …)`, so the
 * three-tier branch (`< O` / `O..P` / `>= Q`) is verified:
 *
 * - **sdk 24 / 25** — no `VibrationEffect` symbol may be touched; the plain
 *   `Vibrator.vibrate(Long)` overload must be invoked.
 * - **sdk 27** — API 26-28: `createOneShot`/`createWaveform` fallback.
 * - **sdk 33** — `createPredefined`.
 */
@RunWith(RobolectricTestRunner::class)
class SystemHapticEngineRobolectricTest {

    private fun vibrator(): Pair<Vibrator, ShadowVibrator> {
        val context: Context = RuntimeEnvironment.getApplication()
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        return vibrator to shadowOf(vibrator)
    }

    @After
    fun resetShadow() {
        ShadowVibrator.reset()
        ShadowLog.clear()
    }

    // ── BLOCKING 1 — API 24 / 25 must not name a VibrationEffect symbol ────

    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun playOneShot_onApi24_usesPlainDurationOverload_noCrash() {
        val (vibrator, shadow) = vibrator()
        val engine = SystemHapticEngine(vibrator)
        // The legacy preset path passes HAPTIC_DEFAULT_AMPLITUDE; on API 24
        // this must reach the deprecated Vibrator.vibrate(Long) overload and
        // must NOT throw NoClassDefFoundError for android.os.VibrationEffect.
        engine.playOneShot(durationMs = 40, amplitude = HAPTIC_DEFAULT_AMPLITUDE)
        assertEquals(
            "API 24 must use the plain Vibrator.vibrate(Long) overload",
            40L,
            shadow.milliseconds,
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.N_MR1])
    fun playOneShot_onApi25_usesPlainDurationOverload_noCrash() {
        val (vibrator, shadow) = vibrator()
        val engine = SystemHapticEngine(vibrator)
        engine.playOneShot(durationMs = 10, amplitude = HAPTIC_DEFAULT_AMPLITUDE)
        assertEquals(10L, shadow.milliseconds)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun playPredefined_onApi24_fallsBackToPlainOneShot() {
        val (vibrator, shadow) = vibrator()
        val engine = SystemHapticEngine(vibrator)
        // playPredefined on API 24 falls back to a 20 ms one-shot — and that
        // fallback must itself be VibrationEffect-free.
        engine.playPredefined(VibrationEffect.EFFECT_CLICK)
        assertEquals(20L, shadow.milliseconds)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun playWaveform_onApi24_usesPlainPatternOverload() {
        val (vibrator, shadow) = vibrator()
        val engine = SystemHapticEngine(vibrator)
        engine.playWaveform(longArrayOf(0, 30, 30, 30))
        assertTrue(
            "API 24 must use the plain Vibrator.vibrate(long[], int) overload",
            longArrayOf(0, 30, 30, 30).contentEquals(shadow.pattern),
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun playAmplitudeWaveform_onApi24_fallsBackToPlainPatternOverload() {
        val (vibrator, shadow) = vibrator()
        val engine = SystemHapticEngine(vibrator)
        // No per-step amplitude pre-O — must degrade to the long[] overload.
        engine.playAmplitudeWaveform(
            timings = longArrayOf(0, 50, 30, 20),
            amplitudes = intArrayOf(0, 255, 0, 127),
        )
        assertTrue(
            longArrayOf(0, 50, 30, 20).contentEquals(shadow.pattern),
        )
    }

    // ── API 26-28 (O..P) — createOneShot / createWaveform fallback ────────

    @Test
    @Config(sdk = [Build.VERSION_CODES.O_MR1])
    fun playOneShot_onApi27_usesVibrationEffectCreateOneShot() {
        val (vibrator, shadow) = vibrator()
        val engine = SystemHapticEngine(vibrator)
        // On API 26-28 the VibrationEffect.createOneShot path is taken — the
        // class exists here, so no NoClassDefFoundError. Robolectric records
        // the createOneShot duration into milliseconds; the call must not
        // throw and the duration must round-trip.
        engine.playOneShot(durationMs = 25, amplitude = 200)
        assertEquals(
            "API 27 createOneShot duration must round-trip",
            25L,
            shadow.milliseconds,
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O_MR1])
    fun playPredefined_onApi27_fallsBackToOneShotNotCreatePredefined() {
        val (vibrator, shadow) = vibrator()
        val engine = SystemHapticEngine(vibrator)
        // createPredefined is API 29 — on API 27 playPredefined falls back to
        // a 20 ms one-shot. It must not throw and must hit the one-shot path.
        engine.playPredefined(VibrationEffect.EFFECT_TICK)
        assertEquals(
            "API 27 playPredefined must fall back to a 20 ms one-shot",
            20L,
            shadow.milliseconds,
        )
    }

    // ── API 29+ (Q) — createPredefined ───────────────────────────────────

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun playPredefined_onApi33_usesCreatePredefinedNotOneShotFallback() {
        val (vibrator, shadow) = vibrator()
        val engine = SystemHapticEngine(vibrator)
        // On API 29+ playPredefined routes through
        // VibrationEffect.createPredefined — it must NOT fall back to the
        // 20 ms one-shot path that older APIs use. Robolectric records a
        // predefined effect as milliseconds == -1 (no concrete duration),
        // distinct from the one-shot fallback which would record 20.
        engine.playPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        assertEquals(
            "API 33 must route through createPredefined (milliseconds == -1), " +
                "not the 20 ms one-shot fallback",
            -1L,
            shadow.milliseconds,
        )
    }

    // ── cancel() ─────────────────────────────────────────────────────────

    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun cancel_onApi24_delegatesToVibratorCancel() {
        val (vibrator, shadow) = vibrator()
        val engine = SystemHapticEngine(vibrator)
        engine.cancel()
        assertTrue("cancel() must call Vibrator.cancel()", shadow.isCancelled)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun cancel_onApi33_delegatesToVibratorCancel() {
        val (vibrator, shadow) = vibrator()
        val engine = SystemHapticEngine(vibrator)
        engine.cancel()
        assertTrue(shadow.isCancelled)
    }

    // ── End-to-end through AndroidSceneViewHaptic on API 24 ───────────────

    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun androidSceneViewHaptic_legacyPresets_onApi24_doNotCrash() {
        val (vibrator, shadow) = vibrator()
        val haptic = AndroidSceneViewHaptic(
            vibratorOrNull = vibrator,
            hasVibratePermission = true,
        )
        // Every legacy-fallback preset must complete on API 24 without a
        // NoClassDefFoundError for android.os.VibrationEffect.
        haptic.light()
        assertEquals("light() → 10 ms one-shot on API 24", 10L, shadow.milliseconds)
        haptic.medium()
        assertEquals("medium() → 20 ms one-shot on API 24", 20L, shadow.milliseconds)
        haptic.heavy()
        assertEquals("heavy() → 40 ms one-shot on API 24", 40L, shadow.milliseconds)
        haptic.selection()
        assertEquals("selection() → 10 ms one-shot on API 24", 10L, shadow.milliseconds)
        // Notification presets are waveforms on every API.
        haptic.warning()
        assertTrue(longArrayOf(0, 30, 30, 30).contentEquals(shadow.pattern))
        haptic.success()
        assertTrue(longArrayOf(0, 30, 80, 30).contentEquals(shadow.pattern))
    }

    // ── Degraded paths — no-op + exactly one Log.d (MINOR review item) ────

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun noVibrator_everyCallIsNoop_andLogsExactlyOnce() {
        ShadowLog.clear()
        val haptic = AndroidSceneViewHaptic(
            engine = null,
            hasVibratePermission = true,
        )
        haptic.light()
        haptic.medium()
        haptic.success()
        haptic.continuous(0.5f, 100L)
        haptic.pattern(listOf(HapticEvent(1f, 1f, 10)))
        haptic.cancel()
        val hapticLogs = ShadowLog.getLogsForTag(SCENEVIEW_HAPTIC_TAG)
        assertEquals(
            "No vibrator → exactly one Log.d diagnostic across many calls",
            1,
            hapticLogs.size,
        )
        assertTrue(
            "Diagnostic must explain the no-vibrator degradation",
            hapticLogs.single().msg.contains("no vibrator"),
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun missingPermission_everyCallIsNoop_andLogsExactlyOnce() {
        ShadowLog.clear()
        val (vibrator, shadow) = vibrator()
        val haptic = AndroidSceneViewHaptic(
            vibratorOrNull = vibrator,
            hasVibratePermission = false,
        )
        haptic.light()
        haptic.heavy()
        haptic.warning()
        haptic.continuous(0.5f, 100L)
        haptic.cancel()
        // Nothing reached the vibrator.
        assertEquals(0L, shadow.milliseconds)
        assertTrue(!shadow.isCancelled)
        val hapticLogs = ShadowLog.getLogsForTag(SCENEVIEW_HAPTIC_TAG)
        assertEquals(
            "Missing VIBRATE permission → exactly one Log.d diagnostic",
            1,
            hapticLogs.size,
        )
        assertTrue(
            "Diagnostic must mention the VIBRATE permission",
            hapticLogs.single().msg.contains("VIBRATE permission"),
        )
    }
}
