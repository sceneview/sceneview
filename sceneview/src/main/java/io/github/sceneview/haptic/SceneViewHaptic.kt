package io.github.sceneview.haptic

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

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
 * ### Permissions and settings
 *
 * The `sceneview` library manifest **declares**
 * `<uses-permission android:name="android.permission.VIBRATE" />`, so it is merged into every
 * consumer app (it is a normal, install-time permission — no prompt). An app that wants no
 * vibrator access removes it with `tools:node="remove"`; the haptic created by
 * [rememberHapticFeedback] or `SceneViewHaptic(view)` then keeps the `View` tiers
 * (`View.performHapticFeedback`, no permission needed) and drops the vibrator ones. With
 * neither a view nor the permission (or no vibrator), **every method is a no-op** and a single
 * `Log.d("SceneViewHaptic", …)` line is emitted on first call. The API never throws.
 *
 * Every haptic honours the system *Touch feedback* setting: when the user turned it off,
 * nothing vibrates.
 *
 * ### Semantic AR events
 *
 * [play] with an [ARHapticEvent] plays the SDK's recipe for a placement-flow moment (placed,
 * scale snapped to 100 %, tracking lost, …). `ARHapticFeedback(state)` in `arsceneview`
 * plays them automatically; it is opt-in.
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
 * Remember a [SceneViewHaptic] bound to the current composition's host `View`.
 *
 * Presets and [ARHapticEvent]s play through `View.performHapticFeedback` where a platform
 * constant fits, then through the vibrator (see [HapticPreset] for the mapping). The instance
 * is safe to call from any thread but is most useful from gesture callbacks and Compose
 * `onClick` handlers. The library manifest declares `android.permission.VIBRATE`; on a device
 * without a vibrator every vibrator tier becomes a silent no-op.
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
    val view = LocalView.current
    val haptic = remember(view) { SceneViewHaptic(view) }
    DisposableEffect(haptic) {
        onDispose { haptic.cancel() }
    }
    return haptic
}

/**
 * Construct a [SceneViewHaptic] outside a `@Composable` (for imperative code
 * paths — ARCore listeners, services, etc.). Without a `View` only the vibrator tiers are
 * available; prefer `SceneViewHaptic(view)` when a view is at hand, and
 * [rememberHapticFeedback] inside `@Composable`.
 */
public fun SceneViewHaptic(context: Context): SceneViewHaptic = createHaptic(context, view = null)

/**
 * Construct a [SceneViewHaptic] bound to [view] — the preferred factory for classic Views.
 *
 * Haptics go through `View.performHapticFeedback` first (honours *Touch feedback*, needs no
 * permission), then through the vibrator. The view is held weakly.
 */
public fun SceneViewHaptic(view: View): SceneViewHaptic = createHaptic(view.context, view)

private fun createHaptic(context: Context, view: View?): SceneViewHaptic {
    val appContext = context.applicationContext ?: context
    val vibrator = resolveVibrator(appContext)
    return AndroidSceneViewHaptic(
        engine = vibrator?.let { SystemHapticEngine(it, appContext.contentResolver) },
        hasVibratePermission = hasVibratePermission(appContext),
        view = view?.let(::WeakViewHapticPerformer),
    )
}

/**
 * Play the SDK's recipe for a semantic AR [event] — see [ARHapticEvent] for the tiers.
 *
 * On a custom [SceneViewHaptic] implementation the event maps to the closest preset
 * (`Placed` → [SceneViewHaptic.medium], `Recovered` → [SceneViewHaptic.success], …).
 */
public fun SceneViewHaptic.play(event: ARHapticEvent) {
    if (this is AndroidSceneViewHaptic) {
        play(HapticRecipes.of(event))
        return
    }
    when (event) {
        ARHapticEvent.Placed -> medium()
        ARHapticEvent.Selected, ARHapticEvent.ScaleSnapped -> selection()
        ARHapticEvent.LimitReached, ARHapticEvent.InvalidMove -> light()
        ARHapticEvent.TrackingLost, ARHapticEvent.HelpNeeded -> warning()
        ARHapticEvent.Recovered -> success()
    }
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
