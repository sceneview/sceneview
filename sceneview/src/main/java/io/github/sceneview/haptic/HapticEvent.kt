package io.github.sceneview.haptic

/**
 * A single timed haptic event used as a building block for [SceneViewHaptic.pattern].
 *
 * A pattern is a list of [HapticEvent]s, played back in order with the requested
 * [delayMs] applied between events. Each event renders for [durationMs] at the
 * requested [intensity] (and, where the OS supports it, the requested
 * [sharpness]).
 *
 * Platform behaviour:
 * - **Android API 26+** — events render via [android.os.VibrationEffect.createWaveform]
 *   with per-step amplitudes scaled from [intensity] (0.0..1.0 → 1..255). The
 *   [sharpness] value is currently informational on Android; the legacy API does
 *   not expose a wave-shape knob.
 * - **Android API 24..25** — events render via the legacy
 *   [android.os.Vibrator.vibrate] long[] overload (no amplitude control); [intensity]
 *   and [sharpness] are ignored at runtime but kept in the model so the same
 *   pattern definitions work across API levels.
 * - **iOS / visionOS** — events render via `CHHapticEngine` with full intensity +
 *   sharpness fidelity (Core Haptics).
 * - **Web** — events render via `navigator.vibrate(durationsMs[])`; [intensity] and
 *   [sharpness] are ignored (the Web Vibration API takes durations only).
 *
 * @property intensity Strength of the haptic, `0.0` (off) .. `1.0` (max). Values
 * outside the range are clamped.
 * @property sharpness Wave-shape hint, `0.0` (round/dull) .. `1.0` (sharp/clicky).
 * Honoured on iOS; ignored on Android / Web in this release.
 * @property durationMs How long this event vibrates, in milliseconds.
 * @property delayMs Delay before this event starts, relative to the end of the
 * previous event (or to `pattern()` invocation for the first event).
 */
public data class HapticEvent(
    val intensity: Float,
    val sharpness: Float,
    val durationMs: Long,
    val delayMs: Long = 0,
)
