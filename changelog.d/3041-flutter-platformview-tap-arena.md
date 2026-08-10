<!-- category: Changed -->
- **Flutter: `SceneView` / `ARSceneView` now claim taps on Android too.** Adding
  `TapGestureRecognizer` to the platform view's gesture set is what lets the native hit
  test run, and it applies to both platforms — the Android views previously let taps
  fall through. An existing app that wrapped the widget in a `GestureDetector(onTap:)`
  or an `InkWell` to catch taps *around* the scene will find those taps now going to
  the platform view instead. Move that handling to `SceneView`'s own `onTap`.
