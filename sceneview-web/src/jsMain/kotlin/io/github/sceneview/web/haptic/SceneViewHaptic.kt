package io.github.sceneview.web.haptic

import kotlinx.browser.window

/**
 * Lightweight, semantic haptic feedback for SceneView web demos.
 *
 * Maps to the [Vibration API](https://developer.mozilla.org/docs/Web/API/Vibration_API):
 * `window.navigator.vibrate(...)`. When the browser does not expose
 * `navigator.vibrate` (most desktop browsers, Safari on iOS, secure-context
 * gates not met, etc.), **every method is a silent no-op**.
 *
 * The API surface matches the Android `io.github.sceneview.haptic.SceneViewHaptic`
 * and iOS `SceneViewSwift.SceneViewHaptic` for cross-platform parity. The Web
 * Vibration API only exposes durations (no intensity / no wave-shape control),
 * so the seven semantic presets map to the duration table documented on the
 * Android `HapticPreset` enum.
 *
 * ```js
 * // Plain JS usage via the global `sceneview` namespace:
 * sceneview.haptic.light();
 * sceneview.haptic.success();
 * sceneview.haptic.continuous(1.0, 200);  // intensity ignored on Web
 * sceneview.haptic.pattern([10, 50, 20]);
 * ```
 *
 * Kotlin/JS usage:
 * ```kotlin
 * val haptic = SceneViewHaptic()
 * haptic.light()
 * haptic.success()
 * ```
 */
@JsExport
@JsName("SceneViewHaptic")
public class SceneViewHaptic {

    /** Light tap — taps, button presses, selections. `vibrate(10)`. */
    public fun light() {
        vibrate(10)
    }

    /** Medium tap — placing an anchor, mode change confirmation. `vibrate(20)`. */
    public fun medium() {
        vibrate(20)
    }

    /** Heavy tap — boundary hit, drag-lock engagement. `vibrate(40)`. */
    public fun heavy() {
        vibrate(40)
    }

    /** Success notification. `vibrate([10, 50, 20])`. */
    public fun success() {
        vibrate(intArrayOf(10, 50, 20))
    }

    /** Warning notification. `vibrate([30, 30, 30])`. */
    public fun warning() {
        vibrate(intArrayOf(30, 30, 30))
    }

    /** Error notification. `vibrate([50, 30, 50])`. */
    public fun error() {
        vibrate(intArrayOf(50, 30, 50))
    }

    /** Selection tick — drag tick, picker scroll. `vibrate(5)`. */
    public fun selection() {
        vibrate(5)
    }

    /**
     * Continuous vibration for [durationMs] milliseconds.
     *
     * Maps to `navigator.vibrate(durationMs)`. The Web Vibration API exposes
     * **durations only** — there is no amplitude/intensity knob — so the
     * [intensity] argument is accepted for cross-platform API parity with
     * Android (`continuous(intensity, durationMs)`) and iOS
     * (`continuous(intensity:durationMs:)`) but is **ignored at runtime**, the
     * same honest-degradation pattern as [pattern]. A non-positive
     * [durationMs] is a no-op.
     */
    @Suppress("UnusedParameter") // intensity is API parity with iOS — Web Vibration API has no intensity
    public fun continuous(intensity: Float, durationMs: Int) {
        if (durationMs <= 0) return
        vibrate(durationMs)
    }

    /**
     * Play a custom vibration pattern. Each entry is a duration in
     * milliseconds, alternating on/off starting with on. Falls through to
     * `navigator.vibrate(durationsMs)` — see the
     * [Vibration API docs](https://developer.mozilla.org/docs/Web/API/Navigator/vibrate)
     * for caveats (some browsers cap total duration to ~10s; others restrict
     * vibration to user-gesture handlers).
     */
    public fun pattern(durationsMs: IntArray) {
        if (durationsMs.isEmpty()) return
        vibrate(durationsMs)
    }

    private fun vibrate(durationMs: Int) {
        try {
            val nav = window.navigator.asDynamic()
            if (nav.vibrate != null) {
                nav.vibrate(durationMs)
            }
        } catch (_: Throwable) {
            // Desktop browser / secure-context block / user-gesture restriction
            // — every call is a no-op on failure.
        }
    }

    private fun vibrate(durations: IntArray) {
        try {
            val nav = window.navigator.asDynamic()
            if (nav.vibrate != null) {
                // navigator.vibrate accepts an Array<Number>; IntArray maps to a
                // typed array via Kotlin/JS which is also accepted by every
                // browser implementation.
                nav.vibrate(durations)
            }
        } catch (_: Throwable) {
        }
    }
}
