<!-- category: Changed -->
- **Entities are now eligible for entity-targeted SwiftUI gestures by default on Apple
  platforms.** This is the other face of the `InputTargetComponent` fix, and it is a
  behaviour change to code that did not ask for it: `NodeGesture` handlers
  (`onTap` / `onDrag` / `onScale` / `onRotate` / `onLongPress`) that were registered and
  silently never fired will now fire. If your app registered one, saw nothing, and worked
  around it, re-check that wiring — the workaround and the handler will now both run.
  Camera orbit and pinch are unaffected: the entity gestures are attached with
  `.simultaneousGesture`, and a drag over a model was verified on the iOS 26.3 simulator
  to still orbit the camera by the expected amount.
- **`SceneCameraPose` write-through clamps to RealityKit's dolly envelope** (`1…50` scene
  units) and to ±85° of elevation, and reports the clamped value back through
  `onCameraChanged`. A pose that cannot be honoured verbatim now says so instead of
  leaving your state and the screen disagreeing.
