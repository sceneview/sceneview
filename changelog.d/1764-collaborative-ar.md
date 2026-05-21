<!-- category: Added -->
- **Collaborative AR — multi-user sessions.** New `io.github.sceneview.ar.collaborative`
  package brings shared-coordinate-frame multiplayer to ARCore. `CollaborativeSession`
  (and the lifecycle-bound `rememberCollaborativeSession()` helper) orchestrates a shared
  AR experience on top of the existing `CloudAnchorNode`: one device hosts the shared
  Cloud Anchor, every other resolves the same id, and participant camera poses + placed-node
  transforms are relayed between peers as JSON-lines messages. The networking layer is a
  pluggable `CollaborativeTransport` interface — SceneView deliberately does not pick a
  stack — shipped alongside an always-available, no-networking `LoopbackCollaborativeTransport`
  reference impl that makes the API unit-testable and demonstrable on a single device.
  `CollaborativeWireFormat` is pure Kotlin with zero new runtime dependencies, and the
  whole merge core (`CollaborativeState`, last-writer-wins) is covered by 52 JVM unit
  tests. The new `ar-collaborative` sample demo proves the full sync end-to-end without a
  second phone. ARCore has no `collaborationData` API (unlike ARKit) — this is the honest,
  buildable shape of multi-user AR on Android. A production Nearby Connections transport is
  filed as a follow-up (#1764).
