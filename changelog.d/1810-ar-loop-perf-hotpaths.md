<!-- category: Fixed -->
- arsceneview, sceneview: kill per-frame allocation hot paths in the AR render loop (#1810).
  - `ARScene.onARFrame`: single-pass `for (n in childNodes) when (n) { is PoseNode -> ...; is DepthMeshNode -> ... }` replaces two `filterIsInstance<...>().forEach { }` walks (~240 list allocations/sec at 60 fps on the render thread).
  - `DepthMeshNode.uploadGeometry`: vertex / index upload now reuses two cached direct `ByteBuffer`s grown in powers of two (was ~100 KB/s direct-buffer churn at 5 Hz, ~600 KB/s at 30 Hz).
  - `DepthMeshCollision.transformPositionsToWorld`: inline 4×4 × (x,y,z,1) matrix multiply writes straight into the output `FloatArray`, removing ~9k transient `Mat4 * Float3` allocs/sec.
  - `PhysicsBody.step`: velocity + position integrated as plain `Float` triples, committed in exactly 2 `Position` allocs per body per frame (was 3-4 → ~1200/sec at 5 balls × 60 fps).
  - `ARDepthColliderDemo`: now drives `DepthCollider.setBodiesRegion(...)` once per frame from the active sphere centres + 15 cm padding so the KDoc-documented region-cull fast path is no longer bypassed (was ~540k tri-tests/sec; region-cull collapses to the bodies' shared AABB).
