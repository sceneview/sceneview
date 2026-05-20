<!-- category: Added -->
`rememberDepthCollider()` — depth-driven static physics collider so `PhysicsNode` bodies bounce off the real floor / table / wall in AR. Thin wrapper over `DepthMeshNode` (#1739): each rebuild's vertex/index buffers feed a per-frame surface lookup via the new `FloorProvider` interface on `PhysicsBody`. SceneView port of arcore-depth-lab's "Collider" scene (#1713).
