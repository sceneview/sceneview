package io.github.sceneview.haptic

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Lightweight, semantic haptic feedback for SceneView demos and apps.
 *
 * Wraps Android's [Vibrator] / [VibratorManager] behind a small set of
 * **semantic** presets (`light`, `medium`, `heavy`, `success`, `warning`,
 * `error`, `selection`) plus low-level [continuous] and [pattern] escape
 * hatches. The same API surface ships on iOS (`SceneViewSwift.SceneViewHaptic`)
 * and on Web (`navigator.vibrate(...)` fallback) so cross-platform code paths
 * stay symmetric — see the platform mapping table on [HapticPreset].
 *
 * ### Permissions
 *
 * Android requires `<uses-permission android:name="android.permission.VIBRATE" />`
 * in the **consumer app's** manifest. The `sceneview` library does not
 * auto-merge it — apps that don't want haptic feedback shouldn't carry the
 * permission. When the permission is missing (or the device has no vibrator),
 * **every method is a no-op** and a single `Log.d("SceneViewHaptic", …)` line
 * is emitted on first call. The API never throws.
 *
 * ### Usage
 *
 * ```kotlin
 * @Composable
 * fun PlaceAnchorButton(onPlace: () -> Unit) {
 *     val haptic = rememberHapticFeedback()
 *     Button(onClick = {
 *         haptic.medium()  // confirm the placement
 *         onPlace()
 *     }) {
 *         Text("Place")
 *     }
 * }
 * ```
 *
 * ### Threading
 *
 * The underlying [Vibrator] is thread-safe; calls from any thread are
 * accepted. For consistency with the iOS API (`@MainActor`-bound) and to
 * keep the gesture-event flow predictable, prefer calling from the main
 * thread.
 */
public interface SceneViewHaptic {

    /** Light tap — taps, button presses, selections. Maps to [HapticPreset.Light]. */
    public fun light()

    /** Medium tap — placing an anchor, mode change confirmation. Maps to [HapticPreset.Medium]. */
    public fun medium()

    /** Heavy tap — boundary hit, drag-lock engagement. Maps to [HapticPreset.Heavy]. */
    public fun heavy()

    /** Success notification — anchor stable, action confirmed. Maps to [HapticPreset.Success]. */
    public fun success()

    /** Warning notification — tracking degraded, soft failure. Maps to [HapticPreset.Warning]. */
    public fun warning()

    /** Error notification — action rejected, hard failure. Maps to [HapticPreset.Error]. */
    public fun error()

    /** Selection tick — drag tick, picker scroll. Maps to [HapticPreset.Selection]. */
    public fun selection()

    /**
     * Continuous vibration for [durationMs] at [intensity] (0.0..1.0).
     *
     * Maps to [VibrationEffect.createOneShot] on API 26+ (intensity ×255 as
     * amplitude) and to the legacy [Vibrator.vibrate] long-duration overload
     * on older API levels (intensity ignored).
     */
    public fun continuous(intensity: Float, durationMs: Long)

    /**
     * Play a sequence of [HapticEvent]s.
     *
     * Maps to [VibrationEffect.createWaveform] on API 26+ (event amplitudes
     * scaled from per-event [HapticEvent.intensity]) and to the legacy
     * [Vibrator.vibrate] long[] overload on older API levels (intensity
     * ignored).
     */
    public fun pattern(events: List<HapticEvent>)

    /**
     * Cancel any in-progress vibration (a long [continuous] or [pattern]).
     *
     * Maps to [Vibrator.cancel]. [rememberHapticFeedback] calls this
     * automatically from its `DisposableEffect.onDispose` so a long
     * [continuous] does not keep vibrating after the composable leaves
     * composition. The short semantic presets finish near-instantly, so
     * calling [cancel] for them is harmless but rarely necessary.
     */
    public fun cancel()
}

/**
 * Remember a [SceneViewHaptic] scoped to the current composition's [Context].
 *
 * The returned instance is safe to call from any thread but is most useful
 * from gesture callbacks and Compose `onClick` handlers. If the device has
 * no vibrator, or the consumer app didn't declare
 * `android.permission.VIBRATE`, every call becomes a silent no-op (one
 * `Log.d` line is emitted on the first call so missing-permission
 * misconfigurations are still discoverable in logcat).
 *
 * Add the permission to the consumer app's manifest to opt in:
 *
 * ```xml
 * <uses-permission android:name="android.permission.VIBRATE" />
 * ```
 *
 * ```kotlin
 * val haptic = rememberHapticFeedback()
 * haptic.light()    // tap
 * haptic.success()  // confirmation
 * ```
 *
 * A `DisposableEffect` calls [SceneViewHaptic.cancel] when the composable
 * leaves composition, so a long [SceneViewHaptic.continuous] does not keep
 * vibrating after the screen is gone or the app is backgrounded.
 */
@Composable
public fun rememberHapticFeedback(): SceneViewHaptic {
    val context = LocalContext.current
    // SceneViewHaptic(context) resolves context.applicationContext internally,
    // so the instance is independent of the (possibly Activity-scoped)
    // LocalContext — key on the stable applicationContext, not LocalContext,
    // to avoid a needless rebuild on a configuration change.
    val haptic = remember(context.applicationContext) { SceneViewHaptic(context) }
    DisposableEffect(haptic) {
        onDispose { haptic.cancel() }
    }
    return haptic
}

/**
 * Construct a [SceneViewHaptic] outside a `@Composable` (for imperative code
 * paths — non-Compose Views, ARCore listeners, etc.). Inside `@Composable`
 * prefer [rememberHapticFeedback].
 */
public fun SceneViewHaptic(context: Context): SceneViewHaptic {
    val appContext = context.applicationContext ?: context
    val vibrator = resolveVibrator(appContext)
    val hasPermission = hasVibratePermission(appContext)
    return AndroidSceneViewHaptic(
        vibratorOrNull = vibrator,
        hasVibratePermission = hasPermission,
    )
}

internal const val SCENEVIEW_HAPTIC_TAG: String = "SceneViewHaptic"

internal fun hasVibratePermission(context: Context): Boolean =
    context.checkSelfPermission(android.Manifest.permission.VIBRATE) ==
        PackageManager.PERMISSION_GRANTED

@Suppress("DEPRECATION")
internal fun resolveVibrator(context: Context): Vibrator? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(VibratorManager::class.java)
        manager?.defaultVibrator?.takeIf { it.hasVibrator() }
    } else {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.takeIf { it.hasVibrator() }
    }
} catch (t: Throwable) {
    Log.d(SCENEVIEW_HAPTIC_TAG, "Vibrator lookup failed; all calls are no-op", t)
    null
}
