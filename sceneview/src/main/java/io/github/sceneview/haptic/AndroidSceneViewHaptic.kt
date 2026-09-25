package io.github.sceneview.haptic

import android.os.Build
import android.os.Vibrator
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default [SceneViewHaptic] implementation backed by the host `View` and an Android [Vibrator].
 *
 * Internally testable: [engine] and [view] are injection points so JVM unit tests can pass
 * recording fakes and assert the exact platform call each preset issues, without Robolectric.
 *
 * ### Tiers
 *
 * Every preset and every [ARHapticEvent] is a [HapticRecipe]; [resolve] picks the first tier
 * the device can play — a modern `View.performHapticFeedback` constant, a
 * `VibrationEffect.Composition`, a predefined effect, a legacy view constant, and for the
 * presets only, below API 29, the historical one-shot or waveform.
 *
 * ### Touch feedback setting
 *
 * The view tiers honour the system *Touch feedback* setting natively. The vibrator tiers carry
 * `USAGE_TOUCH` on API 33+, and below 33 are skipped when the setting is off
 * ([HapticEngine.touchFeedbackEnabled]). A user who turned touch feedback off feels nothing.
 *
 * ### API 24-25 safety
 *
 * The module `minSdk` is **24**. [android.os.VibrationEffect] is an **API 26 (O)** class.
 * This class never names a `VibrationEffect` symbol: [HapticRecipes] only holds inlined
 * `static final int` ids, and only [SystemHapticEngine] touches the class, inside `SDK_INT`
 * branches.
 *
 * No-op behaviour:
 * - no view, and no vibrator or no `VIBRATE` permission → every method returns silently. One
 *   `Log.d` is emitted on the first call.
 */
internal class AndroidSceneViewHaptic internal constructor(
    private val engine: HapticEngine?,
    private val hasVibratePermission: Boolean,
    private val view: HapticViewPerformer? = null,
    private val sdkInt: Int = engine?.sdkInt ?: Build.VERSION.SDK_INT,
) : SceneViewHaptic {

    constructor(vibratorOrNull: Vibrator?, hasVibratePermission: Boolean) : this(
        engine = vibratorOrNull?.let { SystemHapticEngine(it) },
        hasVibratePermission = hasVibratePermission,
    )

    private val loggedDegradation = AtomicBoolean(false)

    /** Vibrator usable for a *touch* haptic right now. */
    private val canVibrate: Boolean
        get() = engine != null && hasVibratePermission && engine.touchFeedbackEnabled

    private inline fun whenEnabled(block: HapticEngine.() -> Unit) {
        if (engine == null) {
            logDegradation("no vibrator on device; all calls are no-op")
            return
        }
        if (!hasVibratePermission) {
            logDegradation(
                "VIBRATE permission missing; add " +
                    "<uses-permission android:name=\"android.permission.VIBRATE\" /> " +
                    "to the consumer app manifest. All calls are no-op."
            )
            return
        }
        if (!engine.touchFeedbackEnabled) return
        engine.block()
    }

    private fun logDegradation(message: String) {
        if (loggedDegradation.compareAndSet(false, true)) {
            // Wrapped: Log.d throws a RuntimeException("Method d in
            // android.util.Log not mocked") in pure-JVM unit tests. The
            // diagnostic line is non-essential — swallow so the degradation
            // path stays observable from instrumented tests but never breaks
            // the JVM suite.
            try {
                Log.d(SCENEVIEW_HAPTIC_TAG, message)
            } catch (_: Throwable) {
                // Pure-JVM test runtime — Log.d is not mocked. Ignore.
            }
        }
    }

    /** The tier chain. Returns the plan it played, for tests. */
    internal fun play(recipe: HapticRecipe): HapticPlan {
        val host = view?.takeIf { it.isAvailable }
        val vibrate = canVibrate
        val plan = recipe.resolve(sdkInt, host != null, vibrate) { ids ->
            engine?.arePrimitivesSupported(ids) == true
        }
        when (plan) {
            // The view's answer is not retried: `false` means the user disabled touch feedback.
            is HapticPlan.View -> host?.perform(plan.constant)
            is HapticPlan.Composed -> engine?.playComposition(plan.primitives)
            is HapticPlan.Predefined -> engine?.playPredefined(plan.effectId)
            is HapticPlan.Legacy -> when (val legacy = plan.vibration) {
                is LegacyVibration.OneShot ->
                    engine?.playOneShot(legacy.durationMs, HAPTIC_DEFAULT_AMPLITUDE)
                is LegacyVibration.Waveform -> engine?.playWaveform(legacy.timings)
            }
            HapticPlan.None -> when {
                host != null || (engine?.touchFeedbackEnabled == false) -> Unit
                engine == null -> logDegradation("no vibrator on device and no view; calls are no-op")
                !hasVibratePermission -> logDegradation(
                    "VIBRATE permission missing and no view; add " +
                        "<uses-permission android:name=\"android.permission.VIBRATE\" /> " +
                        "or create the haptic with SceneViewHaptic(view). Calls are no-op."
                )
            }
        }
        return plan
    }

    internal fun play(event: ARHapticEvent): HapticPlan = play(HapticRecipes.of(event))

    override fun light() {
        play(HapticRecipes.light)
    }

    override fun medium() {
        play(HapticRecipes.medium)
    }

    override fun heavy() {
        play(HapticRecipes.heavy)
    }

    override fun success() {
        play(HapticRecipes.success)
    }

    override fun warning() {
        play(HapticRecipes.warning)
    }

    override fun error() {
        play(HapticRecipes.error)
    }

    override fun selection() {
        play(HapticRecipes.selection)
    }

    override fun continuous(intensity: Float, durationMs: Long): Unit = whenEnabled {
        if (durationMs <= 0L) return@whenEnabled
        val amplitude = scaleIntensityToAmplitude(intensity)
        playOneShot(durationMs = durationMs, amplitude = amplitude)
    }

    override fun pattern(events: List<HapticEvent>): Unit = whenEnabled {
        if (events.isEmpty()) return@whenEnabled
        // Build alternating off/on timings — first entry is a leading delay.
        // HapticEvent exposes Int milliseconds; the Vibrator long[] APIs need
        // Long, so widen here at the single boundary that touches the platform.
        val timings = LongArray(events.size * 2)
        val amplitudes = IntArray(events.size * 2)
        for ((i, event) in events.withIndex()) {
            timings[i * 2] = event.delayMs.coerceAtLeast(0).toLong()
            timings[i * 2 + 1] = event.durationMs.coerceAtLeast(0).toLong()
            amplitudes[i * 2] = 0
            amplitudes[i * 2 + 1] = scaleIntensityToAmplitude(event.intensity)
        }
        // An all-zero timing array (every event durationMs == 0 && delayMs == 0)
        // passes the isEmpty() guard above but makes VibrationEffect.createWaveform
        // throw IllegalArgumentException ("at least one timing must be non-zero").
        // Treat a zero-total pattern as a no-op.
        if (timings.sum() == 0L) return@whenEnabled
        if (sdkInt >= Build.VERSION_CODES.O) {
            playAmplitudeWaveform(timings, amplitudes)
        } else {
            playWaveform(timings)
        }
    }

    override fun cancel() {
        // Bypass whenEnabled() on purpose: cancel() is called from
        // rememberHapticFeedback's onDispose for *every* haptic instance,
        // including degraded ones. Routing it through whenEnabled() would
        // emit a spurious "no vibrator / no permission" Log.d on teardown.
        // A missing engine or permission simply means nothing to cancel.
        if (hasVibratePermission) {
            engine?.cancel()
        }
    }

    private fun scaleIntensityToAmplitude(intensity: Float): Int {
        val clamped = intensity.coerceIn(0f, 1f)
        // Avoid 0 (interpreted as DEFAULT_AMPLITUDE off / pause on some
        // devices when used with a one-shot); clamp lower bound to 1.
        val amplitude = (clamped * 255f).toInt().coerceIn(1, 255)
        return amplitude
    }
}
