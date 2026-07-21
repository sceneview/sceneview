<!-- category: Added -->
- Web: `Node.smoothTransform` / `Node.smoothTransformSpeed` — smooth
  transform animation on the retained web node tree, with the Android core
  semantics and the same `5f` default speed (no `isSmoothTransformEnabled`
  gate, no `onSmoothEnd` on web). Setting a target local `Transform` starts a
  per-frame speed-scaled slerp/lerp on the scene's frame loop (the
  pre-decomposed TRS core path — zero matrix decompositions per tick); on
  convergence the node snaps and the property resets to `null`; setting
  `null` cancels in place. The repaint hook (`onInvalidate`) moved up from
  `SplatNode` to `Node`, is wired subtree-wide by `addNode` (and inherited on
  attach) and released by `removeNode`, so animations keep the on-demand
  render gate awake from idle scenes. `CameraNode`/`SplatNode` `onFrame`
  overrides call `super`, so camera and splat nodes smooth-animate too
  (#2024 P5b).
