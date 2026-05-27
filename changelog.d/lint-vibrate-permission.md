<!-- category: Fixed -->
- **Lint**: declare `VIBRATE` permission in `sceneview` library manifest so
  `HapticEngine`'s `Vibrator.vibrate()` calls no longer generate
  `MissingPermission` lint errors in the library and its consumers.
