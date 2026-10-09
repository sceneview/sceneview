<!-- category: Fixed -->
- **Collaborative AR sessions now converge when peers edit the same node ([#4384](https://github.com/sceneview/sceneview/issues/4384)).** Node updates use a deterministic logical clock, removals broadcast ordered tombstones through the new `removeNode` API, and direct `broadcastLocalPose` calls now honor `poseRateHz` like AR frame updates.
