<!-- category: Added -->
- **iOS `ARSceneView(showPlacementReticle:)`** — opt-in placement reticle: the tap-to-place
  raycast now runs every AR frame and drives a surface-snapped translucent disc at the screen
  centre (orientation slerp `0.75`, Depth Lab / Android `PlacementReticle` parity; hidden while
  the ray misses). Android's `PlacementReticle` iOS counterpart from the Sprint-1 design (#894).
- **iOS `ARSceneView(groundingShadows:)`** — entities placed synchronously in `onTapOnPlane` now
  automatically get RealityKit's `GroundingShadowComponent(castsShadow: true)`, projecting a
  contact shadow onto the detected surface — the RealityKit analogue of Android's
  `ShadowReceiverPlane` (#2580). Opt out with `groundingShadows: false` (#894).
