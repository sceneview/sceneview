<!-- category: Fixed -->
- **`SceneView.onEntityTapped` never fired on iOS.** `ModelNode.load` generated collision
  shapes — the documented purpose of its `enableCollision` parameter is "for hit testing" —
  but SwiftUI's `targetedToAnyEntity()` gestures additionally require an
  `InputTargetComponent`, which nothing in the package ever set. The failure was completely
  silent: no error, no warning, a scene that looked correct until someone tapped it.
  Measured on the iOS 26.3 simulator, a tap on a loaded `.usdz` produced no callback at all
  before and fired on the first try after. This also repairs the Flutter bridge's `onTap`
  ([#2051](https://github.com/sceneview/sceneview/issues/2051)) and the per-entity
  `NodeGesture` dispatch, which depend on the same mechanism.
- **`ModelSource`'s format documentation was wrong about iOS.** It claimed every platform
  accepts glTF and GLB; RealityKit reads neither. There is no format all platforms accept,
  and the KDoc now says so instead of letting it be discovered as a load that fails
  invisibly.
