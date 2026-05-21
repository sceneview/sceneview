---
title: API Cheatsheet — SceneView for iOS, macOS, visionOS
description: "Complete API reference for SceneViewSwift node types, views, materials, physics, and AR features on Apple platforms."
---

# API Cheatsheet — Apple Platforms

A quick reference for SceneViewSwift's most-used APIs. Print it, pin it, keep it next to your keyboard.

!!! tip "Building for Android?"
    See the [Android API Cheatsheet](cheatsheet.md) for Jetpack Compose equivalents.

---

## Setup

```swift
// Package.swift or Xcode SPM
.package(url: "https://github.com/sceneview/sceneview.git", from: "4.11.2")
```

```swift
import SceneViewSwift
```

---

## SceneView (3D)

```swift
// Declarative (recommended — v4.0.0+)
SceneView {
    GeometryNode.cube(size: 0.3, color: .red)
        .position(.init(x: -1, y: 0, z: -2))
    GeometryNode.sphere(radius: 0.2, color: .blue)
        .position(.init(x: 1, y: 0, z: -2))
}
.environment(.studio)
.cameraControls(.orbit)

// Imperative (still supported)
SceneView { root in
    root.addChild(model.entity)
}
.environment(.studio)              // IBL lighting preset
.cameraControls(.orbit)            // .orbit (default) | .pan | .firstPerson (v4.3.0+; firstPerson is true in-place look-around v4.4.0+)
.recentersTargetOnOrbit(true)      // v4.4.0+ — re-pivot on content centroid when returning to orbit (default false)
.onEntityTapped { entity in }      // tap handler
.autoRotate(speed: 0.3)            // turntable auto-rotation
.autoCenterContent(true)           // v4.3.0+ — library translates content centroid to orbit pivot (default true; pass false to keep explicit placements)
.mainLight(.systemDefault)         // v4.2.0+ — see LightSlot
.fillLight(.systemDefault)         // v4.2.0+
.renderQuality(.default)           // v4.2.0+ — .cinematic | .default | .performance
```

---

## ARSceneView (AR — iOS only)

```swift
ARSceneView(
    planeDetection: .horizontal,       // .horizontal, .vertical, .both, .none
    showPlaneOverlay: true,            // visualize detected planes
    showCoachingOverlay: true,         // ARKit coaching UI
    onTapOnPlane: { position, arView in
        let anchor = AnchorNode.world(position: position)
        anchor.add(model.entity)
        arView.scene.addAnchor(anchor.entity)
    }
)
.onSessionStarted { arView in }       // called when AR session begins
.mainLight(.systemDefault)             // v4.3.0+ — see LightSlot (default 10 000-lux directional + shadow)
.fillLight(.systemDefault)             // v4.3.0+ — Android-parity 3 000-lux fill on by default
```

Environment texturing defaults to `.automatic` — RealityKit's equivalent of ARCore's
`ENVIRONMENTAL_HDR` (which became the Android default in v4.3.0, `#1063`). PBR reflections
are driven by runtime-built environment probes; no configuration knob is exposed.

The dual-light setup matches the 3D ``SceneView`` defaults (issue [`#1138`](https://github.com/sceneview/sceneview/issues/1138)).
ARKit has no equivalent of ARCore's directional-light estimation, so the explicit lights
keep their baseline intensities each frame. Override or disable them via:

```swift
ARSceneView(planeDetection: .horizontal)
  .mainLight(.custom(LightNode.directional(intensity: 5_000)))   // dimmer key light
  .fillLight(.disabled)                                            // single-light AR
```

## ARRecorder (v4.3.0+, record-only)

Inside a SwiftUI `View` body:

```swift
@StateObject private var recorder = ARRecorder()

var body: some View {
    Button(recorder.isRecording ? "Stop" : "Record") {
        Task {
            if recorder.isRecording {
                let url = try await recorder.stopRecording()
                // url → .mov under <caches>/ARRecorder/. Share with
                // ShareLink(item: url) or save to PHPhotoLibrary.
            } else {
                try await recorder.startRecording()
            }
        }
    }
}
```

iOS port of Android `ARRecorder`. Uses ReplayKit's `RPScreenRecorder` to
capture screen pixels (NOT an ARKit session dataset). The `.mov` plays
back in Photos / QuickTime; it **cannot** be replayed into `ARSession`
(ARKit has no deterministic playback API — replay stays Android-only).
Typed errors via `ARRecorderError.{permissionDenied, disabled,
unavailable, alreadyRecording, notRecording, other(code:),
photoLibraryDenied, photoLibrarySaveFailed}`. See the parity table below.

### Save to Photos (v4.3.1+)

```swift
// after stopRecording() returns the URL:
let localID = try await ARRecorder.saveToPhotoLibrary(url)
// localID is the saved asset's PHAsset.localIdentifier (or nil) —
// resolve later via PHAsset.fetchAssets(withLocalIdentifiers:options:).
```

Wraps `PHPhotoLibrary.shared().performChanges` to copy the `.mov` into
the system Photos library and returns the created asset's
`PHAsset.localIdentifier` (`String?`) — mirrors Android's
`ARRecorder.exportToDownloads()` which returns the saved `Uri?`.
First call surfaces the system permission sheet; subsequent calls go
straight through. **Requires** the host app's `Info.plist` to declare
`NSPhotoLibraryAddUsageDescription` — without it, iOS crashes the app
on first invocation per Apple's privacy policy.

On user denial throws `ARRecorderError.photoLibraryDenied`; on
`performChanges` failure throws `.photoLibrarySaveFailed`.

---

## Node Types — 3D

| Node | Factory / Init | Key Parameters |
|---|---|---|
| `ModelNode` | `ModelNode.load("file.usdz")` | `async throws`, `.scaleToUnits()`, `.position()`, `.rotation()` |
| `GeometryNode` | `.cube(size:color:)` | `size`, `color`, `cornerRadius` |
| | `.sphere(radius:color:)` | `radius`, `color` |
| | `.cylinder(radius:height:color:)` | `radius`, `height`, `color` |
| | `.plane(width:depth:color:)` | `width`, `depth`, `color` |
| | `.cone(height:radius:color:)` | `height`, `radius`, `color` |
| `LightNode` | `.directional(color:intensity:castsShadow:)` | `.position()`, `.lookAt()`, `.shadowMaximumDistance()` |
| | `.point(color:intensity:attenuationRadius:)` | `.position()`, `.attenuationRadius()` |
| | `.spot(color:intensity:innerAngle:outerAngle:)` | `.position()`, `.lookAt()` |
| `TextNode` | `TextNode(text:fontSize:color:depth:)` | `.position()`, `.centered()`, `.withText()` |
| `ImageNode` | `ImageNode(named:size:)` | `.position()`, `.billboard()` |
| `BillboardNode` | `BillboardNode(named:width:height:)` | always faces camera |
| `VideoNode` | `VideoNode(url:size:)` | `.play()`, `.pause()` |
| `LineNode` | `LineNode(start:end:color:)` | `SIMD3<Float>` endpoints |
| `PathNode` | `PathNode(points:closed:color:)` | `[SIMD3<Float>]` path |
| `PhysicsNode` | `.dynamic(entity, mass:restitution:)` | `.static(entity)`, `.kinematic(entity)` |
| `DynamicSkyNode` | `DynamicSkyNode(timeOfDay:turbidity:)` | `0...24` time cycle |
| `FogNode` | `FogNode(density:color:)` | atmospheric fog |
| `ReflectionProbeNode` | `ReflectionProbeNode(position:radius:)` | zone-based IBL |

---

## Node Types — AR (iOS)

| Node | Usage |
|---|---|
| `AnchorNode.world(position:)` | Anchor at a world coordinate |
| `AnchorNode.plane(alignment:minimumBounds:)` | Anchor on a detected plane |
| `AugmentedImageNode` | Overlay content on a detected reference image |

---

## Common Patterns

### Load a model

```swift
@State private var model: ModelNode?

SceneView { root in
    if let model {
        root.addChild(model.entity)
    }
}
.task {
    model = try? await ModelNode.load("models/car.usdz")
    model?.scaleToUnits(1.0)
    model?.playAllAnimations()
}
```

### Add a light

```swift
SceneView { root in
    let sun = LightNode.directional(
        color: .warm,
        intensity: 1500,
        castsShadow: true
    )
    sun.entity.look(at: .zero, from: [2, 4, 2], relativeTo: nil)
    root.addChild(sun.entity)
}
```

### Create geometry

```swift
SceneView { root in
    let cube = GeometryNode.cube(size: 0.5, color: .red)
        .position(.init(x: -1, y: 0.25, z: -2))
    root.addChild(cube.entity)

    let metalSphere = GeometryNode.sphere(
        radius: 0.3,
        material: .pbr(color: .gray, metallic: 1.0, roughness: 0.2)
    )
    root.addChild(metalSphere.entity)
}
```

### AR tap-to-place

```swift
ARSceneView(
    planeDetection: .horizontal,
    onTapOnPlane: { position, arView in
        let anchor = AnchorNode.world(position: position)
        let cube = GeometryNode.cube(size: 0.1, color: .blue)
        anchor.add(cube.entity)
        arView.scene.addAnchor(anchor.entity)
    }
)
```

### Physics

```swift
SceneView { root in
    // Falling ball
    let ball = GeometryNode.sphere(radius: 0.1, color: .red)
    PhysicsNode.dynamic(ball.entity, mass: 1.0, restitution: 0.8)
    ball.position = .init(x: 0, y: 3, z: -2)
    root.addChild(ball.entity)

    // Static floor
    let floor = GeometryNode.plane(width: 10, depth: 10, color: .gray)
    PhysicsNode.static(floor.entity)
    root.addChild(floor.entity)
}
```

### 3D text

```swift
SceneView { root in
    let label = TextNode(
        text: "Hello 3D!",
        fontSize: 0.1,
        color: .white
    )
    .position(.init(x: 0, y: 1, z: -2))
    .centered()
    root.addChild(label.entity)
}
```

### Per-entity gestures (v4.0.0+)

```swift
// Fluent API on Entity
let cube = GeometryNode.cube(size: 0.3, color: .blue)
cube.entity
    .onTap { print("Tapped!") }
    .onDrag { translation in cube.position += translation }
    .onScale { factor in cube.scale *= .init(repeating: factor) }
    .onRotate { angle in /* handle rotation */ }

// Or via static NodeGesture methods
NodeGesture.onTap(entity) { print("Tapped!") }
NodeGesture.onLongPress(entity) { print("Long pressed!") }
NodeGesture.removeAll(from: entity)  // cleanup
```

---

## Environment Presets

```swift
.environment(.studio)    // neutral studio (default)
.environment(.outdoor)   // warm daylight
.environment(.sunset)    // golden hour
.environment(.night)     // dark, moody
.environment(.warm)      // cozy tungsten
.environment(.autumn)    // soft outdoor
```

Custom environment:

```swift
.environment(.custom(name: "My HDR", hdrFile: "custom.hdr", intensity: 1.5))
```

visionOS — immersive-space skybox: a windowed / volumetric scene composites
over passthrough and ignores the HDR skybox. For a fully immersive
`ImmersiveSpace`, opt in with `.immersiveSpace()` to render the HDR as a
`WorldComponent`-rooted background sphere:

```swift
ImmersiveSpace(id: "scene") {
    SceneView { root in /* ... */ }
        .environment(.nightSky)   // showSkybox == true
        .immersiveSpace()         // render the HDR skybox on visionOS
}
.immersionStyle(selection: .constant(.full), in: .full)
```

---

## Materials

```swift
// Simple color
GeometryNode.cube(size: 0.5, color: .red)

// PBR with metallic/roughness
GeometryNode.sphere(radius: 0.3, material: .pbr(
    color: .gray, metallic: 1.0, roughness: 0.2
))

// Textured PBR
let texture = try await GeometryMaterial.loadTexture("brick_diffuse.png")
let node = GeometryNode.cube(
    size: 1.0,
    material: .textured(baseColor: texture, roughness: 0.8)
)

// Unlit (no lighting response)
GeometryNode.plane(width: 1, depth: 1, color: .white)  // use .unlit for no shading
```

---

## Transform Helpers

```swift
model.position = SIMD3<Float>(x: 1, y: 0, z: -2)     // meters
model.rotation = simd_quatf(angle: .pi / 4, axis: [0, 1, 0])
model.scale = SIMD3<Float>(repeating: 2.0)             // uniform

// Fluent API
model.position(.init(x: 1, y: 0, z: -2))
     .scale(0.5)
     .rotation(angle: .pi, axis: [0, 1, 0])
```

---

## Animation

```swift
// Play all animations (looping)
model?.playAllAnimations(loop: true, speed: 1.0)

// Play specific animation by index
model?.playAnimation(at: 0, loop: true, speed: 1.5)

// Stop
model?.stopAllAnimations()
```

---

## Threading Rules

| Safe | Unsafe |
|---|---|
| `ModelNode.load(...)` in `.task` | Mutating entities off `@MainActor` |
| `GeometryNode.*` factory methods | Accessing RealityKit components from background threads |
| Any code in SwiftUI view body | Direct `Entity` manipulation from `DispatchQueue.global()` |

**Rule:** RealityKit entities are `@MainActor`-isolated. Use `await MainActor.run { }` if you need to modify entities from a background context. SwiftUI's `.task` modifier runs on the main actor by default for view-related work.

---

## Android ↔ Apple API Mapping

| Android (Compose) | Apple (SwiftUI) |
|---|---|
| `SceneView { }` | `SceneView { root in }` |
| `ARSceneView { }` | `ARSceneView(...)` |
| `rememberModelInstance(loader, path)` | `ModelNode.load(path)` |
| `ModelNode(modelInstance, scaleToUnits)` | `model.scaleToUnits(units)` |
| `CubeNode(size, material)` | `GeometryNode.cube(size:color:)` |
| `SphereNode(radius, material)` | `GeometryNode.sphere(radius:color:)` |
| `LightNode(type, apply = { })` | `LightNode.directional(...)` / `.point(...)` / `.spot(...)` |
| `Scene(mainLightNode = …, fillLightNode = …)` (3D) | `.mainLight(_:)` / `.fillLight(_:)` on `SceneView` (v4.2.0+) |
| `ARSceneView(mainLightNode = …, fillLightNode = …)` (AR) | `.mainLight(_:)` / `.fillLight(_:)` on `ARSceneView` (v4.3.0+, `#1138`) |
| `Config.LightEstimationMode.ENVIRONMENTAL_HDR` | `ARWorldTrackingConfiguration.environmentTexturing = .automatic` (on by default in `ARSceneView`) |
| `rememberEnvironmentLoader` | `.environment(.studio)` view modifier |
| `rememberCameraManipulator()` | `.cameraControls(.orbit)` view modifier |
| `AnchorNode(anchor)` | `AnchorNode.world(position:)` |
| `PhysicsNode(node, mass)` | `PhysicsNode.dynamic(entity, mass:)` |

---

## Demo parity status (#1194)

The iOS demo app now ships these Stage 2 (#1152) streaming demos as
proper iOS ports of the Android equivalents. They consume the curated
`SampleAssets` registry via `SketchfabAssetResolver`, fall back to
bundled USDZs when no Sketchfab key is configured, and are reachable
via deep-link as well as the Samples tab.

| Demo | Deep-link id | iOS file | Status |
|---|---|---|---|
| Animation (5-model carousel) | `animation` | `AnimationDemo.swift` | Ported (cinematic camera shots + IBL slider are Android-only) |
| Model Viewer (Surprise me) | `model-viewer` | `ModelViewerDemo.swift` | Ported |
| Multi-Model Park | `multi-model` | `MultiModelDemo.swift` | Ported |
| AR Plane Placement | `ar-placement` | `ARPlacementDemo.swift` | Ported (no per-model editing yet) |
| AR Instant Placement | (Samples tab) | `ARInstantPlacementDemo.swift` | Ported (approximates via `.estimatedPlane` raycasts) |
| Physics (streamed bodies) | `physics` | `PhysicsDemo.swift` | Ported (bundled cubes + 4 streamed crash-test meshes; capped at 20 active bodies for RealityKit) |

The pre-1194 placeholder shape — `model-viewer` / `multi-model` routing
to `SceneGalleryDemo` — is gone. Both deep-links now land on dedicated
SwiftUI demos.

---

## iOS parity status (#1036)

Some Android APIs map imperfectly to iOS because RealityKit / ARKit do
not expose the underlying feature. They fall into three buckets — keep
this table at hand before re-attacking a deprecated API as if it were a
silent stub.

### Deprecated on iOS (compile-warning, no-op at runtime)

| Symbol | Why iOS can't | Working alternative |
|---|---|---|
| `CameraNode.depthOfField(...)` | `PerspectiveCameraComponent` has no DOF | Custom Metal post-process required (out of scope) |
| `CameraNode.exposure(_:)` | No `exposureCompensation` on `PerspectiveCameraComponent` (verified Xcode 26.x compile failure in #1019) | `ARSceneView(cameraExposure:)` for AR; `SceneView.renderQuality(_:)` to tune IBL for 3D |
| `LightNode.shadowColor(_:)` | `DirectionalLightComponent.Shadow` has no `color` property | Use `castsShadow(_:)` + `shadowMaximumDistance(_:)` |
| `FogNode.heightBased(...)` / `FogNode.heightFalloff` | `UnlitMaterial` cannot vary opacity by world height; no per-view fog API in RealityKit (#1380) | `FogNode.exponential(density:color:)` |

### Android-only — no port planned (or pending)

| Symbol | Why iOS can't | iOS path |
|---|---|---|
| `ARSceneView(playbackDataset:)` | ARKit has no deterministic recording playback | Record-only via [#1032 ReplayKit](https://github.com/sceneview/sceneview/issues/1032); replay stays Android-only |
| `SurfaceType.texture` | RealityKit always renders to `MTKView` | N/A — no port needed |
| `StreetscapeGeometry` | ARGeoTrackingConfiguration exists but no mesh equivalent | iOS-skip with doc warning |
| `TerrainAnchor / RooftopAnchor` (geo-anchored to terrain or rooftop) | `ARGeoAnchor` only does ground; rooftop has no ARKit equivalent | iOS-skip with doc warning |
| `Config.SemanticMode.ENABLED` + `Frame.semanticImage()` / `.semanticConfidenceImage()` / `.semanticLabelFraction(label)` (#1730) | ARKit has no equivalent per-pixel outdoor classifier — the closest primitive is `ARFrame.detectedBody.skeleton` (single-person joints, not pixel labels) | iOS-skip with doc warning. Apps that need semantic-aware placement on iOS must ship their own Vision/Core ML segmentation model; AR-engine integration is not on the SceneViewSwift roadmap. |

### AR Depth & Cloud Anchors — May 2026 sprint (#1813)

The May 2026 AR sprint shipped four new Android-only public surfaces. Each has a
documented RealityKit / ARKit counterpart — the table below maps them so AI-generated
SceneViewSwift code can pick the closest native primitive instead of asking for the
Android API name. Implementation issues are tracked under `platform: ios`.

| Android API | iOS counterpart | Migration note |
|---|---|---|
| `DepthMeshNode` + `rememberDepthMesh` (renderable depth mesh) | ARKit `ARMeshAnchor` via [Scene Reconstruction](https://developer.apple.com/documentation/arkit/arconfiguration/sceneReconstruction) (`ARWorldTrackingConfiguration.sceneReconstruction = .mesh`) — LiDAR-only (iPad Pro / iPhone Pro / Vision Pro). Not yet wrapped — tracked in [#1860](https://github.com/sceneview/sceneview/issues/1860). | iOS surfaces the real-world mesh as a stream of `ARMeshAnchor`s with `MTLBuffer`-backed geometry. There is no ARCore-style depth image; the mesh comes directly from the LiDAR fusion pipeline. Devices without LiDAR have no equivalent — fall back to plane detection. |
| `DepthCollider` + `rememberDepthCollider` (depth → physics collider) | `ARMeshAnchor` geometry → `CollisionComponent` with a `ShapeResource.generateConvex(from:)` / `.generateStaticMesh(positions:faceIndices:)` per anchor. Not yet wrapped — tracked in [#1860](https://github.com/sceneview/sceneview/issues/1860). | Drive `PhysicsBodyComponent.dynamic` bodies in RealityKit; the static-mesh collider is rebuilt on `ARSession`-delivered mesh-anchor updates. Same LiDAR-only constraint as `DepthMeshNode`. |
| `Frame.hitTestDepth(xPx, yPx)` (single-pixel depth raycast → position + normal) | `ARView.raycast(from:allowing:alignment:)` with `.estimatedPlane` + `.any`, or `ARView.scene.raycast(origin:direction:length:query:mask:)` against `CollisionComponent`s built from `ARMeshAnchor`. Use the returned `ARRaycastResult.worldTransform` for both position and normal. | The iOS API is already documented — use it directly. The pixel-screen → world ray conversion is `view.unprojectPoint` or just feed `CGPoint` from a `UITapGestureRecognizer`. No new library wrapper needed. |
| `CloudAnchorNode.host` / `.resolve` returning `Future` + cancel-on-dispose (#1768) | `ARGeoAnchor` is not equivalent; iOS Cloud Anchors require Google's `arcore-ios-sdk` (Swift Package). The Future + cancel-on-dispose pattern needs porting to `SceneViewSwift.CloudAnchorNode` — tracked in [#1859](https://github.com/sceneview/sceneview/issues/1859). | The Future pattern matters because Cloud Anchor hosting is a billed network round-trip — cancelling on `View` disappearance avoids accruing Google Cloud charges. The iOS port wraps `GARSession.hostCloudAnchor` similarly. |

**Why this matters for AI assistants:** when a developer asks for "real-world depth"
or "cloud anchor" code on iOS, generate the RealityKit / ARKit primitive listed above
rather than the Android-only SceneView symbol. The implementation issues (#1859,
#1860) track when a SceneViewSwift wrapper will exist; until then, raw ARKit is the
supported path.

### Approximated — iOS implements via different mechanism

Same public API name on both platforms, but the iOS render path differs.
Use as you would on Android; expect minor visual differences.

| Symbol | Android renderer | iOS approximation |
|---|---|---|
| `FogNode.linear / .exponential` | Filament fog modes | Translucent-sphere shader (visual approximation; same factory API). `FogNode.heightBased` is deprecated on iOS — see #1380. |
| `ReflectionProbeNode.box(...) / .sphere(...)` | Volumetric Filament probe | Unbounded `ImageBasedLightReceiverComponent` (volume scope is best-effort) |
| `CustomMaterial.subsurface(...)` | Filament SSS | PBR `metallic` + `roughness` tuning |

**Why this matters for AI assistants:** when generating SceneViewSwift
code, treat the Deprecated row as no-ops to avoid; Android-only entries
as iOS-not-implemented; Approximated entries are fine to use as-is and
will compile + render with visual fidelity differences only.

---

## Android

Building for Android with Jetpack Compose? See the [Android API Cheatsheet](cheatsheet.md).
