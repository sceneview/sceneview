package io.github.sceneview.haptic

import android.annotation.SuppressLint
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.view.HapticFeedbackConstants

/*
 * The single table behind every Android haptic SceneView plays: the seven presets of
 * [SceneViewHaptic] and the eight [ARHapticEvent]s.
 *
 * Every id below is a `static final int` of the Android SDK, inlined by the compiler, so
 * naming `VibrationEffect.EFFECT_TICK` or `Composition.PRIMITIVE_THUD` here loads no class on
 * API 24-25. Whether an id may be *played* on the running API is decided by the `minSdk` each
 * entry carries, in [resolve], and nowhere else.
 */

/** One `VibrationEffect.Composition` primitive. [delayMs] is the gap before it (≥ 50 ms). */
internal data class HapticPrimitive(
    val id: Int,
    val scale: Float,
    val delayMs: Int = 0,
    val minSdk: Int = API_R,
)

/** A `HapticFeedbackConstants` value and the first API level that defines it. */
internal data class HapticConstant(val id: Int, val minSdk: Int)

/** Pre-API 29 compatibility for the presets only; the AR events never buzz. */
internal sealed class LegacyVibration {
    data class OneShot(val durationMs: Long) : LegacyVibration()
    class Waveform(val timings: LongArray) : LegacyVibration() {
        override fun equals(other: Any?) = other is Waveform && timings.contentEquals(other.timings)
        override fun hashCode() = timings.contentHashCode()
        override fun toString() = "Waveform(${timings.toList()})"
    }
}

/**
 * How one haptic is played, tier by tier. See [resolve] for the order.
 *
 * @property preferredView constants that fit the event well enough to be the first choice:
 *   `View.performHapticFeedback` honours *Touch feedback* and needs no permission.
 * @property primitives a composition, played only when the vibrator supports every primitive.
 * @property predefined a `VibrationEffect.EFFECT_*` id (API 29+).
 * @property fallbackView constants that approximate the event when nothing richer is possible.
 * @property legacy the preset's historical one-shot or waveform, below API 29 only.
 */
internal data class HapticRecipe(
    val preferredView: List<HapticConstant> = emptyList(),
    val primitives: List<HapticPrimitive> = emptyList(),
    val predefined: Int? = null,
    val fallbackView: List<HapticConstant> = emptyList(),
    val legacy: LegacyVibration? = null,
)

/** What [resolve] decided to play. */
internal sealed class HapticPlan {
    data class View(val constant: Int) : HapticPlan()
    data class Composed(val primitives: List<HapticPrimitive>) : HapticPlan()
    data class Predefined(val effectId: Int) : HapticPlan()
    data class Legacy(val vibration: LegacyVibration) : HapticPlan()
    object None : HapticPlan() {
        override fun toString() = "None"
    }
}

/**
 * Picks the first tier that can play on this device:
 *
 * 1. [HapticRecipe.preferredView] through the host `View`;
 * 2. [HapticRecipe.primitives], when [canVibrate] and the vibrator supports all of them;
 * 3. [HapticRecipe.predefined] on API 29+, when [canVibrate];
 * 4. [HapticRecipe.fallbackView] through the host `View`;
 * 5. [HapticRecipe.legacy], when [canVibrate] (presets only).
 *
 * A view tier is chosen before it is played, never retried on a `false` return: before API 33
 * `performHapticFeedback` returns `false` when the user turned *Touch feedback* off, and
 * falling through to the vibrator would override that choice.
 *
 * @param canVibrate a vibrator exists, `VIBRATE` is granted and *Touch feedback* is on.
 */
internal fun HapticRecipe.resolve(
    sdkInt: Int,
    hasView: Boolean,
    canVibrate: Boolean,
    supportsPrimitives: (IntArray) -> Boolean,
): HapticPlan {
    val preferred = if (hasView) preferredView.firstOrNull { sdkInt >= it.minSdk } else null
    val fallback = if (hasView) fallbackView.firstOrNull { sdkInt >= it.minSdk } else null
    // Only asked when no preferred View constant plays: the query crosses into the vibrator.
    fun composable() = canVibrate && primitives.isNotEmpty() &&
        sdkInt >= primitives.maxOf { it.minSdk } &&
        supportsPrimitives(IntArray(primitives.size) { primitives[it].id })
    return when {
        preferred != null -> HapticPlan.View(preferred.id)
        composable() -> HapticPlan.Composed(primitives)
        canVibrate && predefined != null && sdkInt >= API_Q -> HapticPlan.Predefined(predefined)
        fallback != null -> HapticPlan.View(fallback.id)
        canVibrate && legacy != null && sdkInt < API_Q -> HapticPlan.Legacy(legacy)
        else -> HapticPlan.None
    }
}

internal const val API_Q: Int = 29
internal const val API_R: Int = 30
internal const val API_S: Int = 31
internal const val API_U: Int = 34

/**
 * The mapping table. Strengths follow Android's composition guidance: 0.5 / 0.7 / 1.0 for low /
 * medium / high, at least a 1.4 ratio between two levels, at least 50 ms between primitives.
 */
@SuppressLint("InlinedApi")
internal object HapticRecipes {

    private fun view(id: Int, minSdk: Int = 1) = HapticConstant(id, minSdk)

    // ── Presets: light < medium < heavy, as on iOS ────────────────────────────────────────

    val light = HapticRecipe(
        preferredView = listOf(view(HapticFeedbackConstants.CONTEXT_CLICK, 23)),
        primitives = listOf(HapticPrimitive(Composition.PRIMITIVE_TICK, 0.7f)),
        predefined = VibrationEffect.EFFECT_TICK,
        legacy = LegacyVibration.OneShot(10),
    )

    val medium = HapticRecipe(
        preferredView = listOf(view(HapticFeedbackConstants.VIRTUAL_KEY)),
        primitives = listOf(HapticPrimitive(Composition.PRIMITIVE_CLICK, 0.7f)),
        predefined = VibrationEffect.EFFECT_CLICK,
        legacy = LegacyVibration.OneShot(20),
    )

    val heavy = HapticRecipe(
        preferredView = listOf(view(HapticFeedbackConstants.LONG_PRESS)),
        primitives = listOf(HapticPrimitive(Composition.PRIMITIVE_CLICK, 1.0f)),
        predefined = VibrationEffect.EFFECT_HEAVY_CLICK,
        legacy = LegacyVibration.OneShot(40),
    )

    val success = HapticRecipe(
        preferredView = listOf(view(HapticFeedbackConstants.CONFIRM, API_R)),
        primitives = listOf(
            HapticPrimitive(Composition.PRIMITIVE_CLICK, 0.5f),
            HapticPrimitive(Composition.PRIMITIVE_CLICK, 0.7f, delayMs = 80),
        ),
        predefined = VibrationEffect.EFFECT_DOUBLE_CLICK,
        fallbackView = listOf(view(HapticFeedbackConstants.LONG_PRESS)),
        legacy = LegacyVibration.Waveform(longArrayOf(0, 30, 80, 30)),
    )

    val warning = HapticRecipe(
        preferredView = listOf(view(HapticFeedbackConstants.REJECT, API_R)),
        primitives = listOf(
            HapticPrimitive(Composition.PRIMITIVE_LOW_TICK, 0.7f, minSdk = API_S),
            HapticPrimitive(Composition.PRIMITIVE_LOW_TICK, 0.7f, delayMs = 60, minSdk = API_S),
        ),
        predefined = VibrationEffect.EFFECT_DOUBLE_CLICK,
        fallbackView = listOf(view(HapticFeedbackConstants.LONG_PRESS)),
        legacy = LegacyVibration.Waveform(longArrayOf(0, 30, 30, 30)),
    )

    val error = HapticRecipe(
        preferredView = listOf(view(HapticFeedbackConstants.REJECT, API_R)),
        primitives = listOf(
            HapticPrimitive(Composition.PRIMITIVE_LOW_TICK, 1.0f, minSdk = API_S),
            HapticPrimitive(Composition.PRIMITIVE_LOW_TICK, 1.0f, delayMs = 60, minSdk = API_S),
            HapticPrimitive(Composition.PRIMITIVE_LOW_TICK, 1.0f, delayMs = 60, minSdk = API_S),
        ),
        predefined = VibrationEffect.EFFECT_DOUBLE_CLICK,
        fallbackView = listOf(view(HapticFeedbackConstants.LONG_PRESS)),
        legacy = LegacyVibration.Waveform(longArrayOf(0, 50, 30, 50, 30, 50)),
    )

    val selection = HapticRecipe(
        preferredView = listOf(
            view(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, API_U),
            view(HapticFeedbackConstants.CLOCK_TICK, 21),
        ),
        primitives = listOf(HapticPrimitive(Composition.PRIMITIVE_TICK, 0.5f)),
        predefined = VibrationEffect.EFFECT_TICK,
        legacy = LegacyVibration.OneShot(10),
    )

    // ── AR events (design table §4.2): no legacy buzz ─────────────────────────────────────

    /** The "pof" of an object touching the ground: a thud, then a tick 60 ms later. */
    val placed = HapticRecipe(
        preferredView = listOf(view(HapticFeedbackConstants.CONFIRM, API_R)),
        primitives = listOf(
            HapticPrimitive(Composition.PRIMITIVE_THUD, 0.7f, minSdk = API_S),
            HapticPrimitive(Composition.PRIMITIVE_TICK, 0.5f, delayMs = 60),
        ),
        predefined = VibrationEffect.EFFECT_HEAVY_CLICK,
        fallbackView = listOf(view(HapticFeedbackConstants.LONG_PRESS)),
    )

    val selected = HapticRecipe(
        preferredView = listOf(view(HapticFeedbackConstants.CONTEXT_CLICK, 23)),
        primitives = listOf(HapticPrimitive(Composition.PRIMITIVE_TICK, 0.5f)),
        predefined = VibrationEffect.EFFECT_TICK,
    )

    /** A crisp detent: the platform's own segment tick where it exists (API 34). */
    val scaleSnapped = HapticRecipe(
        preferredView = listOf(view(HapticFeedbackConstants.SEGMENT_TICK, API_U)),
        primitives = listOf(HapticPrimitive(Composition.PRIMITIVE_CLICK, 0.7f)),
        predefined = VibrationEffect.EFFECT_CLICK,
        fallbackView = listOf(view(HapticFeedbackConstants.CLOCK_TICK, 21)),
    )

    /** Dull and single, on entering the bound — the visual is the elastic resistance. */
    val limitReached = HapticRecipe(
        primitives = listOf(HapticPrimitive(Composition.PRIMITIVE_LOW_TICK, 0.7f, minSdk = API_S)),
        predefined = VibrationEffect.EFFECT_TICK,
        fallbackView = listOf(view(HapticFeedbackConstants.CLOCK_TICK, 21)),
    )

    /** A light error tick, paired with the "Keep the object on a surface" pill. */
    val invalidMove = HapticRecipe(
        preferredView = listOf(view(HapticFeedbackConstants.REJECT, API_R)),
        primitives = listOf(HapticPrimitive(Composition.PRIMITIVE_LOW_TICK, 0.5f, minSdk = API_S)),
        predefined = VibrationEffect.EFFECT_TICK,
        fallbackView = listOf(view(HapticFeedbackConstants.CLOCK_TICK, 21)),
    )

    /** A falling edge then a low tick; short transients only while the IMU matters. */
    val trackingLost = HapticRecipe(
        primitives = listOf(
            HapticPrimitive(Composition.PRIMITIVE_QUICK_FALL, 0.5f),
            HapticPrimitive(Composition.PRIMITIVE_LOW_TICK, 0.7f, delayMs = 60, minSdk = API_S),
        ),
        predefined = VibrationEffect.EFFECT_DOUBLE_CLICK,
        fallbackView = listOf(
            view(HapticFeedbackConstants.REJECT, API_R),
            view(HapticFeedbackConstants.LONG_PRESS),
        ),
    )

    val recovered = HapticRecipe(
        preferredView = listOf(view(HapticFeedbackConstants.CONFIRM, API_R)),
        primitives = listOf(
            HapticPrimitive(Composition.PRIMITIVE_QUICK_RISE, 0.5f),
            HapticPrimitive(Composition.PRIMITIVE_CLICK, 0.7f, delayMs = 60),
        ),
        predefined = VibrationEffect.EFFECT_DOUBLE_CLICK,
        fallbackView = listOf(view(HapticFeedbackConstants.LONG_PRESS)),
    )

    val helpNeeded = HapticRecipe(
        primitives = listOf(
            HapticPrimitive(Composition.PRIMITIVE_CLICK, 0.5f),
            HapticPrimitive(Composition.PRIMITIVE_CLICK, 0.7f, delayMs = 100),
        ),
        predefined = VibrationEffect.EFFECT_DOUBLE_CLICK,
        fallbackView = listOf(
            view(HapticFeedbackConstants.REJECT, API_R),
            view(HapticFeedbackConstants.LONG_PRESS),
        ),
    )

    fun of(event: ARHapticEvent): HapticRecipe = when (event) {
        ARHapticEvent.Placed -> placed
        ARHapticEvent.Selected -> selected
        ARHapticEvent.ScaleSnapped -> scaleSnapped
        ARHapticEvent.LimitReached -> limitReached
        ARHapticEvent.InvalidMove -> invalidMove
        ARHapticEvent.TrackingLost -> trackingLost
        ARHapticEvent.Recovered -> recovered
        ARHapticEvent.HelpNeeded -> helpNeeded
    }

    fun of(preset: HapticPreset): HapticRecipe = when (preset) {
        HapticPreset.Light -> light
        HapticPreset.Medium -> medium
        HapticPreset.Heavy -> heavy
        HapticPreset.Success -> success
        HapticPreset.Warning -> warning
        HapticPreset.Error -> error
        HapticPreset.Selection -> selection
    }
}
