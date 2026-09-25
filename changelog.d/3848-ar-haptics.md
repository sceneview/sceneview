<!-- category: Added -->
- **Semantic AR haptics, opt-in ([#3848](https://github.com/sceneview/sceneview/issues/3848)).** `ARHapticFeedback(state)` on Android and `.arHapticFeedback(controller)` on iOS play one haptic per placement moment: placed, selected, snapped to 100 %, scale limit, invalid move, tracking lost (only after tracking was established), recovered, and help cards. `SceneViewHaptic.play(ARHapticEvent)` plays one directly, and iOS adds `prepare(for:)`.
- **Pinch snaps to 100 % with an elastic rebound ([#3848](https://github.com/sceneview/sceneview/issues/3848)).** Within ±4 % of the real-world size, the pinch lands on exactly 100 % with a short damped rebound, on both platforms.

<!-- category: Fixed -->
- **Android `medium()` is stronger than `light()` and respects Touch feedback ([#3848](https://github.com/sceneview/sceneview/issues/3848)).** Presets try `View.performHapticFeedback` first, then primitive compositions and predefined effects. Vibrations carry touch attributes, so with *Touch feedback* off nothing vibrates. The docs now say the library declares `VIBRATE`.
