<!-- category: Fixed -->
- **Entity tap and every `NodeGesture` handler never fired on iOS.** Nodes generated
  collision shapes — `ModelNode.load`'s `enableCollision` parameter is documented "for
  hit testing" — but SwiftUI's `targetedToAnyEntity()` gestures additionally require an
  `InputTargetComponent`, which nothing in the package ever set. The failure was
  completely silent: no error, no warning, a scene that looked correct until someone
  tapped it. The repo's own `CollisionHitTestDemo` had never been tappable.
  `SceneView` now applies it to the whole content subtree (so `GeometryNode`, `MeshNode`,
  `TextNode`, `ImageNode`, `ShapeNode`, `ViewNode` and `PhysicsNode` are covered, not
  just loaded models), `ModelNode.load` applies it under `enableCollision`, and
  `NodeGesture` registration applies it to the entity it registers on. Measured on the
  iOS 26.3 simulator: a tap on a loaded `.usdz` and on an inline `GeometryNode.cube`
  produced no callback before and fired on the first try after. This also repairs the
  Flutter bridge's `onTap` ([#2051](https://github.com/sceneview/sceneview/issues/2051)).
- **`ModelNode.load(from:)` accepted any URL scheme.** Its documentation says "remote
  HTTP/HTTPS URL", but `URLSession` honours `file://` — measured: it returns the bytes of
  a local path, with a response that is not an `HTTPURLResponse` and therefore skipped
  the status check entirely. A caller forwarding a user- or network-supplied string
  turned it into an in-sandbox file read handed to RealityKit's USD parser. The scheme is
  now enforced, the response check rejects rather than skips a non-HTTP response, and the
  temporary files are cleaned up on the failure paths too. Use `load(contentsOf:)` for a
  local file.
- **`ModelSource`'s format documentation was wrong about iOS.** It claimed every platform
  accepts glTF and GLB; RealityKit reads neither. There is no format all platforms accept,
  and the KDoc now says so instead of letting it be discovered as a load that fails
  invisibly.
