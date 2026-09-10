<!-- category: Fixed -->
- **Every model in the iOS viewer now opens correctly framed, zooms both ways, and comes
  back to where it started ([#3595](https://github.com/sceneview/sceneview/issues/3595),
  [#3596](https://github.com/sceneview/sceneview/issues/3596),
  [#3597](https://github.com/sceneview/sceneview/issues/3597),
  [#3598](https://github.com/sceneview/sceneview/issues/3598)).** App Store QA on an iPhone
  SE found the Toy Car opening as an extreme close-up that refused to zoom out, the
  Butterfly opening as a speck, the Cyberpunk Hovercar off-centre, the Recenter button doing
  nothing visible, and the menu's Reset restoring everything except the zoom. Those were
  four faults wearing one costume. `SceneView` re-armed its fit-to-bounds pass by writing
  value-type `@State` from the `RealityView` `update:` closure and from a `.task(id:)` —
  writes SwiftUI drops, because both run on a view value it has already moved past — so
  after the first subject latched, every later model inherited the previous one's pivot and
  orbit radius. The latch, the stability tracker and the recenter token now live in the
  reference-type applied cache, where a write sticks. The zoom-radius limits are **assigned**
  from the current content's bounds instead of merged with the outgoing subject's: a stale
  floor clamped the fit *above* the distance that frames the new model, which is precisely
  "opened zoomed in and will not zoom out". The orbit drag and the pinch were two competing
  `.gesture(_:)` modifiers, so `DragGesture` claimed the touch sequence and no closing pinch
  ever reached the SDK — they are composed with `.simultaneousGesture` now, with the orbit
  drag suppressed for the duration of a pinch. And a model file may ship its own cameras
  (the Khronos `ToyCar` sample carries eight); RealityKit renders through one of those, so
  the camera being fitted and recentred was not the camera on screen. `SceneView` owns the
  camera, so authored ones are stripped from loaded content.
- **New: `SceneView.recenterCamera(_:)`** — bump a token to re-frame the camera on the
  content that is already loaded. Hosts previously had to re-key `contentID`, which rebuilds
  the model and restarts its animation to move a camera.
- **The framing driver no longer allocates per tick.** The content-bounds union folded into
  a fresh array on every one of its 30 Hz passes, on the main thread, while the user was
  pinching — and with the latch broken it never stopped. It folds in place now, and it
  latches.
