<!-- category: Fixed -->
- **iOS demo: the macOS build compiles again ([#0000](https://github.com/sceneview/sceneview/pull/0000)).** The Mac App Store upload of v4.52.0 failed on two iOS-only calls in the demo app (the Secondary Camera backdrop colour and the mode picker's haptic). macOS now uses `Color(nsColor:)` and skips the haptic; iOS is unchanged.
