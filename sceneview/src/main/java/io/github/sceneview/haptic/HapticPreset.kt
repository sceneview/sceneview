package io.github.sceneview.haptic

/**
 * Semantic haptic preset shared across Android, iOS and Web.
 *
 * Presets map to the canonical platform feedback under the hood:
 *
 * | Preset | Android (API 29+) | iOS | Web |
 * |---|---|---|---|
 * | [Light] | `EFFECT_CLICK` | `UIImpactFeedbackGenerator(.light)` | `vibrate(10)` |
 * | [Medium] | `EFFECT_TICK` | `UIImpactFeedbackGenerator(.medium)` | `vibrate(20)` |
 * | [Heavy] | `EFFECT_HEAVY_CLICK` | `UIImpactFeedbackGenerator(.heavy)` | `vibrate(40)` |
 * | [Success] | `EFFECT_DOUBLE_CLICK` | `UINotificationFeedbackGenerator.success` | `vibrate([10,50,20])` |
 * | [Warning] | waveform `[0,30,30,30]` | `UINotificationFeedbackGenerator.warning` | `vibrate([30,30,30])` |
 * | [Error] | waveform `[0,50,30,50,30,50]` | `UINotificationFeedbackGenerator.error` | `vibrate([50,30,50])` |
 * | [Selection] | `EFFECT_TICK` | `UISelectionFeedbackGenerator.selectionChanged` | `vibrate(5)` |
 *
 * The mapping degrades gracefully on older Android (API < 29) to the closest
 * predefined effect or, where none exists, to a `long[]` waveform.
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
