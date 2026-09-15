<!-- category: Changed -->
- **iOS demo — the home screen is alive.** The hero card now renders the bundled
  Damaged Helmet through SceneViewSwift (RealityKit) instead of a poster frame:
  a slow turntable on the same studio IBL the demos use, framed in the card's
  free upper-right corner. Exactly one 3D scene runs at a time — the stage is
  torn down when the Showcase tab is hidden, the app backgrounds or a demo is
  presented, so the opened demo owns the GPU alone.
- **iOS demo — motion pass on the catalogue.** The hero, the category chips and
  the cards fan in on `ease-expressive` (`DESIGN.md` Motion), the result count
  updates with `contentTransition(.numericText)`, and every card answers a press
  with the app's one spring. Under `accessibilityReduceMotion` the turntable
  stops and the rise and cascade give way to a plain opacity fade.
