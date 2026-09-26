package io.github.sceneview.haptic

import android.content.ContentResolver
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import java.lang.ref.WeakReference

/**
 * Package-internal sentinel for "let the OS pick the amplitude".
 *
 * [android.os.VibrationEffect] is an **API 26 (O)** class — referencing
 * `VibrationEffect.DEFAULT_AMPLITUDE` (or any of its static fields) on an
 * API 24-25 device throws `NoClassDefFoundError`. The preset-mapping logic in
 * [AndroidSceneViewHaptic] runs on every supported API down to `minSdk 24`,
 * so it must never name a `VibrationEffect` symbol directly. Callers pass this
 * sentinel instead; only [SystemHapticEngine]'s `>= O` branch — which is
 * already guarded by an `SDK_INT` check — resolves it to the real
 * `VibrationEffect.DEFAULT_AMPLITUDE`.
 */
internal const val HAPTIC_DEFAULT_AMPLITUDE: Int = Int.MIN_VALUE

/**
 * Internal abstraction over the actual `Vibrator.vibrate(...)` calls so the
 * preset-mapping logic in [AndroidSceneViewHaptic] is testable on a pure
 * JVM (no Robolectric). The production implementation
 * [SystemHapticEngine] delegates to a real [Vibrator]; tests pass a
 * recording fake.
 *
 * Methods take values pre-resolved into platform primitives — predefined
 * effect ids, composition primitives, raw waveform `long[]`, intensity-scaled
 * one-shot durations — so the engine itself doesn't care about presets and
 * [HapticRecipes] is the single source of truth.
 *
 * Amplitudes are plain `Int`s in `0..255`, or the API-safe
 * [HAPTIC_DEFAULT_AMPLITUDE] sentinel for "OS default". No method on this
 * interface — nor any of its callers — references [android.os.VibrationEffect]
 * unless it is inside an `SDK_INT` branch, so the engine is safe to use
 * on API 24-25.
 */
internal interface HapticEngine {
    /**
     * Play a predefined effect (API 29+). Implementations on older API
     * levels can fall back; the parameter is always passed regardless.
     */
    fun playPredefined(effectId: Int)

    /** Play a raw waveform (timings in ms, no per-step amplitude). */
    fun playWaveform(timings: LongArray, repeat: Int = -1)

    /**
     * Play a waveform with per-step amplitudes (API 26+). Each amplitude is
     * a byte in 0..255. Implementations on older API levels fall back to a
     * timings-only waveform.
     */
    fun playAmplitudeWaveform(timings: LongArray, amplitudes: IntArray, repeat: Int = -1)

    /**
     * One-shot vibration of [durationMs] at [amplitude] (`1..255`, or
     * [HAPTIC_DEFAULT_AMPLITUDE] for the OS default).
     */
    fun playOneShot(durationMs: Long, amplitude: Int)

    /** Play a `VibrationEffect.Composition` (API 30+). No-op below. */
    fun playComposition(primitives: List<HapticPrimitive>)

    /** `Vibrator.areAllPrimitivesSupported` (API 30+); `false` below. */
    fun arePrimitivesSupported(ids: IntArray): Boolean

    /** Cancel any in-progress vibration. Maps to `Vibrator.cancel()`. */
    fun cancel()

    /** Sdk int — kept on the engine so test fakes can simulate older APIs. */
    val sdkInt: Int

    /**
     * Whether the user's *Touch feedback* setting allows a touch vibration.
     *
     * API 33+ always answers `true`: every call carries
     * `VibrationAttributes.USAGE_TOUCH`, which the system silences itself when
     * the setting is off. Below 33 the setting is only honoured by
     * `View.performHapticFeedback`, so the engine reads it and the caller skips
     * the vibrator when it is off.
     */
    val touchFeedbackEnabled: Boolean
}

/**
 * The production [HapticEngine]. Every vibration carries touch attributes —
 * `VibrationAttributes.USAGE_TOUCH` on API 33+, `AudioAttributes`
 * `USAGE_ASSISTANCE_SONIFICATION` below — so the platform routes it like the
 * system's own touch feedback (intensity setting, battery saver, DND).
 */
internal class SystemHapticEngine(
    private val vibrator: Vibrator,
    private val contentResolver: ContentResolver? = null,
) : HapticEngine {

    override val sdkInt: Int get() = Build.VERSION.SDK_INT

    override val touchFeedbackEnabled: Boolean
        get() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return true
            val resolver = contentResolver ?: return true
            return try {
                @Suppress("DEPRECATION")
                Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
            } catch (_: Throwable) {
                true
            }
        }

    private val audioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    /** Only called on API 26+, where [VibrationEffect] exists. */
    private fun vibrate(effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(
                effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, audioAttributes)
        }
    }

    override fun playPredefined(effectId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrate(VibrationEffect.createPredefined(effectId))
        } else {
            // Fallback for API 24..28: tick-like single shot. `playOneShot`
            // itself is API-safe — it never names a VibrationEffect symbol on
            // API 24-25.
            playOneShot(durationMs = 20, amplitude = HAPTIC_DEFAULT_AMPLITUDE)
        }
    }

    override fun playComposition(primitives: List<HapticPrimitive>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || primitives.isEmpty()) return
        val composition = VibrationEffect.startComposition()
        for (primitive in primitives) {
            composition.addPrimitive(primitive.id, primitive.scale.coerceIn(0f, 1f), primitive.delayMs)
        }
        vibrate(composition.compose())
    }

    override fun arePrimitivesSupported(ids: IntArray): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ids.isNotEmpty() &&
            vibrator.areAllPrimitivesSupported(*ids)

    @Suppress("DEPRECATION")
    override fun playWaveform(timings: LongArray, repeat: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrate(VibrationEffect.createWaveform(timings, repeat))
        } else {
            // API 24-25: legacy long[] overload, no VibrationEffect reference.
            vibrator.vibrate(timings, repeat, audioAttributes)
        }
    }

    @Suppress("DEPRECATION")
    override fun playAmplitudeWaveform(timings: LongArray, amplitudes: IntArray, repeat: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrate(VibrationEffect.createWaveform(timings, amplitudes, repeat))
        } else {
            // API 24-25: legacy long[] overload — no per-step amplitude, no
            // VibrationEffect reference.
            vibrator.vibrate(timings, repeat, audioAttributes)
        }
    }

    @Suppress("DEPRECATION")
    override fun playOneShot(durationMs: Long, amplitude: Int) {
        if (durationMs <= 0L) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // VibrationEffect is API 26 — only resolved inside this branch.
            // The sentinel maps to the OS-default amplitude here.
            val resolved = if (amplitude == HAPTIC_DEFAULT_AMPLITUDE) {
                VibrationEffect.DEFAULT_AMPLITUDE
            } else {
                amplitude
            }
            vibrate(VibrationEffect.createOneShot(durationMs, resolved))
        } else {
            // API 24-25: the deprecated duration-only overload. No amplitude
            // control and — critically — no VibrationEffect class reference.
            vibrator.vibrate(durationMs, audioAttributes)
        }
    }

    override fun cancel() {
        vibrator.cancel()
    }
}

/**
 * Plays a `HapticFeedbackConstants` value on the host view. Kept behind an interface so
 * the tier selection is testable on a pure JVM.
 */
internal fun interface HapticViewPerformer {
    /** @return what `View.performHapticFeedback` returned, `false` when the view is gone. */
    fun perform(constant: Int): Boolean

    /** `false` once the host view is gone; the view tiers are then skipped. */
    val isAvailable: Boolean get() = true
}

/**
 * The production [HapticViewPerformer]. Holds the view weakly: a haptic instance kept in a
 * ViewModel or a listener must not leak the Activity.
 */
internal class WeakViewHapticPerformer(view: android.view.View) : HapticViewPerformer {
    private val ref = WeakReference(view)
    override val isAvailable: Boolean get() = ref.get() != null
    override fun perform(constant: Int): Boolean = ref.get()?.performHapticFeedback(constant) ?: false
}
