<!-- category: Added -->
- iOS port of the `ar-depth-collider` demo (#2838) — drops small bouncy balls (5 cm
  spheres, SceneView brand blue) in front of the live camera pose and lets them bounce
  off the **real** floor / table / wall via `SceneReconstructionNode.enablePhysics`
  (ARKit scene reconstruction / LiDAR), the RealityKit analogue of Android's
  `DepthCollider`. Mirrors Android's own fallback behaviour exactly: when the depth
  subsystem can't run — no LiDAR on the device, or the Simulator, which has no camera
  at all — the demo does **not** gate itself off. It falls back to a static, collidable
  floor (`floorY = -1`, matching Android's own fallback value) so a bounce is still
  visible in every case, on-device or in the Simulator. Lands with `@status knownIssue`,
  mirroring Android's own `KnownIssue` status for this id — the depth-driven collision
  path compiles and the static-floor fallback is exercised in CI, but real LiDAR-mesh
  collision has not yet been verified on physical LiDAR hardware.
