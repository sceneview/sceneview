<!-- category: Fixed -->
- **Perf:** `PoseNode.pose` (and `ARCameraNode.pose`) now write the world translation + rotation
  directly from the ARCore `Pose` components instead of routing through
  `worldTransform(pose.transform)`, which allocated a fresh `FloatArray(16)` + `Transform` and ran
  a matrix decompose/recompose on every anchor / plane / face / image / camera pose update, every
  frame. Added an allocation-free `Pose.toTransform(out: FloatArray)` scratch variant for callers
  that still need the matrix form. ([#2266](https://github.com/sceneview/sceneview/issues/2266),
  umbrella [#2263](https://github.com/sceneview/sceneview/issues/2263))
