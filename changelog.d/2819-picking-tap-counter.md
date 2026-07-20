- **Picking & Collision demo:** the "Tapped N times" counter no longer increments on a tap
  anywhere in the scene — only the "Tap me" button inside the 3D card counts, as intended.
  A scene-level `onSingleTapUp` was bumping the counter without checking the hit node. (#2819)
<!-- category: Fixed -->
