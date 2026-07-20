<!-- category: Added -->
- Web XR: `XRAnchorNode.drive(node)` bridges a tracked anchor to the retained
  scene graph — the bound root `Node`'s `worldTransform` follows the anchor's
  per-frame pose, so AR-placed content is real graph content with children
  composing beneath it. `stopDriving()` releases the node; a destroyed node is
  auto-released; parented nodes are rejected (world-space poses must not
  double-compose). Proven with synthetic poses in `jsTest` — no new embind
  binding (the write path is the #2024-P1-probed `TransformManager.setTransform`)
  (#2024 P5a).
