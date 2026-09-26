package io.github.sceneview.haptic

/**
 * Semantic haptic preset shared across Android, iOS and Web.
 *
 * Presets map to the canonical platform feedback under the hood. On Android the first tier the
 * device supports wins: a `View.performHapticFeedback` constant (when the haptic has a view),
 * then a `VibrationEffect.Composition`, then a predefined effect (API 29+). `light < medium <
 * heavy` in strength, as on iOS.
 *
 * | Preset | Android view | Android composition / predefined | iOS | Web |
 * |---|---|---|---|---|
 * | [Light] | `CONTEXT_CLICK` | `TICK` 0.7 / `EFFECT_TICK` | `UIImpactFeedbackGenerator(.light)` | `vibrate(10)` |
 * | [Medium] | `VIRTUAL_KEY` | `CLICK` 0.7 / `EFFECT_CLICK` | `UIImpactFeedbackGenerator(.medium)` | `vibrate(20)` |
 * | [Heavy] | `LONG_PRESS` | `CLICK` 1.0 / `EFFECT_HEAVY_CLICK` | `UIImpactFeedbackGenerator(.heavy)` | `vibrate(40)` |
 * | [Success] | `CONFIRM` (30+) | `CLICK` 0.5 + `CLICK` 0.7 / `EFFECT_DOUBLE_CLICK` | `UINotificationFeedbackGenerator.success` | `vibrate([10,50,20])` |
 * | [Warning] | `REJECT` (30+) | 2 × `LOW_TICK` 0.7 / `EFFECT_DOUBLE_CLICK` | `UINotificationFeedbackGenerator.warning` | `vibrate([30,30,30])` |
 * | [Error] | `REJECT` (30+) | 3 × `LOW_TICK` 1.0 / `EFFECT_DOUBLE_CLICK` | `UINotificationFeedbackGenerator.error` | `vibrate([50,30,50])` |
 * | [Selection] | `SEGMENT_FREQUENT_TICK` (34+), `CLOCK_TICK` | `TICK` 0.5 / `EFFECT_TICK` | `UISelectionFeedbackGenerator.selectionChanged` | `vibrate(5)` |
 *
 * Below API 29, without a view, the presets keep their historical one-shots (10 / 20 / 40 ms)
 * and waveforms. Every tier honours the system *Touch feedback* setting.
 */
public enum class HapticPreset {
    Light,
    Medium,
    Heavy,
    Success,
    Warning,
    Error,
    Selection,
}
