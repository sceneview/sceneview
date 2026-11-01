<!-- category: Fixed -->
- **The macOS build of the iOS demo compiles again ([#4895](https://github.com/sceneview/sceneview/pull/4895)).** Scene capture (Take photo / Record video) is iOS-only in SceneViewSwift, so the demo now gates it behind `#if os(iOS)`; on macOS the settings sheet keeps the screen-record rows. Fixes the failed Mac App Store upload of v4.64.0.
