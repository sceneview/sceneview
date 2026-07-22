- **Picking & Collision demo:** the "Tapped N times" counter no longer increments on a tap
  anywhere in the scene. It now counts only taps whose ray-cast actually hits the 3D card —
  which is what a picking demo is meant to show. A scene-level `onSingleTapUp` was bumping
  the counter without checking the hit node, so empty-space taps counted too. The embedded
  Compose button cannot count them itself: a `ViewNode` never receives touch events (#2845).
  (#2819)
<!-- category: Fixed -->
