<!-- category: Changed -->
- **The AR placement reticle is achromatic ([#3570](https://github.com/sceneview/sceneview/issues/3570)).**
  `RETICLE_TINT` was `#44E7FF` and its documentation called that "the DESIGN.md primary cyan".
  DESIGN.md has no cyan: `primary` is `#005bc1` / `#a4c1ff`, and `#44E7FF` is in no token table.
  On a real floor that saturated ring was the loudest thing in the frame and tinted the room.
  Every reticle worth copying — RealityKit's `FocusEntity`, Scene Viewer, Polycam, IKEA Place —
  is neutral, and says *searching* versus *ready* with opacity and shape rather than hue. The
  default is now the `on-ar-scrim` white, drawn as a hairline ring over a faint `ar-scrim` contact
  halo so it stays readable on a pale floor, with the one colour on screen confined to a small
  `#a4c1ff` centre dot that appears only in the ready phase. Theme-independent, like every
  element drawn over a camera frame. Callers that want the old look can still pass
  `reticleColor = DEFAULT_RETICLE_COLOR`.
