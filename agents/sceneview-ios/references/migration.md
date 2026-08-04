# Migrating to SceneViewSwift

This page covers the rewrites AI agents most often need when bringing existing
Apple-platform 3D code onto SceneViewSwift. The full per-API mapping is in
[`docs/docs/cheatsheet-ios.md`](https://github.com/sceneview/sceneview/blob/main/docs/docs/cheatsheet-ios.md);
the cross-platform rename map is in
[`docs/docs/migration.md`](https://github.com/sceneview/sceneview/blob/main/docs/docs/migration.md).

## Setup

```swift
// Package.swift
.package(url: "https://github.com/sceneview/sceneview.git", from: "4.26.0")
```

```swift
import SceneViewSwift
```

## From a raw RealityKit `RealityView`

| RealityKit | SceneViewSwift |
| --- | --- |
| `RealityView { content in … }` | `SceneView { root in … }` (root is the content `Entity`) |
| `ModelEntity(named:)` / `Entity(named:)` async load | `try await ModelNode.load("file.usdz")` then `.entity` |
| `ModelEntity(mesh: .generateBox(...))` | `GeometryNode.cube(size:color:)` |
| Manual `DirectionalLight` entity | `LightNode.directional(color:intensity:castsShadow:)` |
| Manual IBL / `ImageBasedLightComponent` | `.environment(.studio)` view modifier |
| Hand-rolled gesture recognizers | `.onEntityTapped { }`, per-entity `.onTap/.onDrag/...` |

## From SceneKit (`SCNView` / `SCNScene`)

| SceneKit | SceneViewSwift |
| --- | --- |
| `SCNView` | `SceneView { }` |
| `SCNScene(named:)` | `try await ModelNode.load(...)` |
| `SCNNode` geometry primitives (`SCNBox`, `SCNSphere`…) | `GeometryNode.cube / .sphere / …` |
| `SCNLight` | `LightNode.directional / .point / .spot` |
| `SCNCamera` + `allowsCameraControl` | `.cameraControls(.orbit)` |
| `node.position = SCNVector3(...)` | `node.position = SIMD3<Float>(...)` |

## From Android Kotlin (cross-platform parity)

**Do NOT translate Android Kotlin character by character.** The result-builder,
view modifiers, and factory naming differ. Use the **Android↔Apple mapping
table** in `cheatsheet-ios.md`. Key shape differences:

| Android (Compose) | Apple (SwiftUI) |
| --- | --- |
| `SceneView { }` | `SceneView { root in }` or `SceneView { … }` (`@NodeBuilder`) |
| `ARSceneView { }` | `ARSceneView(...)` |
| `rememberModelInstance(loader, path)` (nullable) | `try await ModelNode.load(path)` (`async throws`) |
| `CubeNode(size, material)` | `GeometryNode.cube(size:color:)` |
| `ModelNode(centerOrigin = Position(0,-1,0))` (normalized AABB origin) | `.centerOrigin(normalized: SIMD3(0, -1, 0))` (bottom-align — same normalized `-1..1` semantics; apply before positioning: does not compose additively with a prior `.position`) |
| `LightNode(type = LightManager.Type.POINT, …)` | `LightNode.point(color:intensity:attenuationRadius:)` |
| `AnchorNode(anchor: arcoreAnchor)` | `AnchorNode.world(position:)` / `.plane(...)` |
| `rememberEnvironmentLoader` + `rememberEnvironment` | `.environment(.studio)` modifier |
| `rememberCameraManipulator()` | `.cameraControls(.orbit)` modifier |

## Common compile errors and fixes

1. `Cannot find 'CubeNode' / 'SphereNode' in scope` — those are Android type
   names. On iOS use `GeometryNode.cube(...)` / `.sphere(...)`.
2. `'async' call in a function that does not support concurrency` —
   `ModelNode.load` is `async throws`; call it inside `.task { }` or another
   async context, not directly in a synchronous initializer.
3. `Cannot find 'rememberModelInstance' / 'rememberEngine'` — those are Compose
   helpers; there is no iOS equivalent. Use `ModelNode.load` + `@State`.
4. `Main actor-isolated property … can not be mutated from a non-isolated
   context` — RealityKit entities are `@MainActor`; wrap mutation in
   `await MainActor.run { }`.
5. `Value of type 'ARSceneView' has no member 'playbackDataset'` — AR record
   playback is Android-only; ARKit has no deterministic playback. iOS
   `ARRecorder` is record-only.

## iOS parity status

Some Android APIs are deprecated no-ops or unsupported on iOS. Before
re-attacking one, consult the **"iOS parity status (#1036)"** tables in
`docs/docs/cheatsheet-ios.md` — they classify each gap as Deprecated,
Android-only, or Approximated.
