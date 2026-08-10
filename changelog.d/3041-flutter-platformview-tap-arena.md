<!-- category: Changed -->
<!-- breaking -->
<!-- Behaviour-breaking, not source-breaking: nothing fails to compile, but a tap
     that an existing app used to receive in its own GestureDetector/InkWell now
     goes to the platform view. The prose below says exactly that, yet never uses
     the literal token `breaking`, so frag_prose_claims_breaking() would not have
     caught it and a patch tag could have shipped it. Marker added explicitly. -->
- **Flutter: `SceneView` / `ARSceneView` now claim taps on Android too.** Adding
  `TapGestureRecognizer` to the platform view's gesture set is what lets the native hit
  test run, and it applies to both platforms — the Android views previously let taps
  fall through. An existing app that wrapped the widget in a `GestureDetector(onTap:)`
  or an `InkWell` to catch taps *around* the scene will find those taps now going to
  the platform view instead. Move that handling to `SceneView`'s own `onTap`.
