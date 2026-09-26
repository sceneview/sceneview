package io.github.sceneview.haptic

/**
 * Semantic AR haptic events, played by [SceneViewHaptic.play].
 *
 * Each event is one transition, never a state that stays true: the SDK plays it once when
 * the placement flow crosses it, and only as a double of something the user already sees
 * (Apple HIG, *Playing haptics*). The same enum ships on iOS as `ARHapticEvent`.
 *
 * `ARHapticFeedback(state)` (module `arsceneview`) plays these for an `AutoPlacementState`.
 * It is **opt-in**: an app that does not call it vibrates exactly as before.
 *
 * Android tiers, first match wins — see [HapticRecipes]:
 * 1. `View.performHapticFeedback` with a modern constant (honours *Touch feedback*, needs no
 *    permission);
 * 2. a `VibrationEffect.Composition` of primitives, when the device supports all of them;
 * 3. a predefined `VibrationEffect` (API 29+);
 * 4. a legacy `HapticFeedbackConstants` constant.
 *
 * Raw one-shots and waveforms are never used for these events: Android's guidance is that no
 * haptic is better than a buzzy one.
 */
public enum class ARHapticEvent {
    /** A surface was found and the object stands on it. The pose is immediate: one event. */
    Placed,

    /** The user tapped the standing object. */
    Selected,

    /** A pinch entered the 100 % detent. Paired with the elastic rebound of the model. */
    ScaleSnapped,

    /** A pinch reached the 25 % or 400 % scale bound. */
    LimitReached,

    /** A drag (or an accessibility move) tried to leave the supported surface. */
    InvalidMove,

    /** Tracking was lost after it had been established at least once. */
    TrackingLost,

    /** The placement was found again after a tracking loss. */
    Recovered,

    /** The flow is stuck and a help card appears (no surface found, recovery failed). */
    HelpNeeded,
}
