<!-- category: Added -->
- Web: `Node.smoothTransform` / `Node.smoothTransformSpeed` — Android-mirror
  smooth transform animation on the retained web node tree. Setting a target
  local `Transform` starts a per-frame speed-scaled slerp/lerp on the scene's
  frame loop (the pre-decomposed TRS core path — zero matrix decompositions
  per tick); on convergence the node snaps and the property resets to `null`;
  setting `null` cancels in place. The repaint hook (`onInvalidate`) moved up
  from `SplatNode` to `Node` and is wired subtree-wide by `addNode` (and
  inherited on attach), so animations keep the on-demand render gate awake
  from idle scenes (#2024 P5b).
