<!-- category: Added -->
- **`SceneViewerHostView` — the reusable `@objc UIView` around `SceneViewSwift`.** This is
  the missing half of the `sceneview-compose` iOS bridge shipped in
  [#3009](https://github.com/sceneview/sceneview/pull/3009): the Kotlin side declared it
  needed a `UIView` factory, and every app had to write that `UIView` itself. It now ships
  in `SceneViewSwift`, driven entirely by primitives on `SceneViewerConfiguration`, so a
  `SceneViewerViewFactory` is a field-by-field copy plus two callbacks. Same wrapper is
  intended for the Flutter and React Native bridges, which still carry their own platform
  views today. See [`sceneview-compose/README.md`](https://github.com/sceneview/sceneview/blob/main/sceneview-compose/README.md).
- **Four additive `SceneView` modifiers** the wrapper needed, all opt-in and none changing
  existing behaviour: `cameraPose(_:)` (continuous camera write-through, applied only when
  the value changes so it does not fight a live drag), `onCameraChanged(_:)` (the camera
  read-back — fired for drag, pinch, auto-rotate and re-framing alike), `cameraGesturesEnabled(_:)`
  (freeze the gestures without handing the camera to Apple's `realityViewCameraControls`,
  which `CameraControlMode.none` does), and `onEntityTapHit(_:)` (tap plus a world-space
  position). The distinct *base* name is deliberate and was arrived at the hard way: an
  overload distinguished only by a `hit:` label does not protect existing call sites,
  because an unlabelled trailing closure ignores the label — measured, every published
  `.onEntityTapped { entity in }` snippet stopped compiling.
- **`CameraState` is now genuinely two-way on iOS.** Gestures write into it and writes
  drive the camera, verified on the iOS 26.3 simulator: a 180-point drag moved the camera
  to the arithmetically expected −51.6° and reported exactly that back. A pose the renderer
  has to clamp is reported back clamped, so the clamp is visible in your state instead of a
  silent disagreement with the screen.
