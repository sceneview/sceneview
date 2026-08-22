# visionOS Spatial Computing

SceneViewSwift supports visionOS 2+ via RealityKit. This page documents the spatial
computing features available on Apple Vision Pro and how SceneViewSwift will integrate
them.

!!! info "Status: Research & Roadmap"
    visionOS spatial features are **planned** for a future release. The API designs
    shown here are **target proposals** -- final APIs may differ after implementation.

---

## visionOS Scene Types

visionOS apps use three scene types, each progressively more immersive:

| Scene type | Description | SceneViewSwift mapping |
|---|---|---|
| **Window** | Standard 2D SwiftUI window, floating in shared space | `SceneView { }` (existing) |
| **Volume** | Fixed-size 3D container in shared space | `VolumetricSceneView { }` (planned) |
| **Immersive Space** | Full spatial experience -- mixed, progressive, or full | `ImmersiveSceneView { }` (planned) |

### Windows (existing support)

SceneViewSwift's `SceneView` already works in visionOS windows. The `RealityView`
renders 3D content inside a standard SwiftUI window:

```swift
import SceneViewSwift

struct ContentView: View {
    @State private var model: ModelNode?

    var body: some View {
        SceneView { root in
            if let model { root.addChild(model.entity) }
        }
        .environment(.studio)
        .task { model = try? await ModelNode.load("models/robot.usdz") }
    }
}
```

### Volumes (planned)

Volumes are bounded 3D containers that exist in the shared space alongside other apps.
Content is clipped to the volume boundary. visionOS provides `WindowGroup` with
`.windowStyle(.volumetric)` and a `defaultSize` in meters:

```swift
// Target API -- SceneViewSwift volumetric support
@main
struct MyApp: App {
    var body: some SceneView {
        WindowGroup {
            VolumetricSceneView(
                size: Size3D(width: 0.5, height: 0.5, depth: 0.5)
            ) { root in
                if let model { root.addChild(model.entity) }
            }
        }
        .windowStyle(.volumetric)
        .defaultSize(width: 0.5, height: 0.5, depth: 0.5, in: .meters)
    }
}
```

### Immersive Spaces (planned)

Immersive spaces take over the full display. Three immersion styles control how much
of the real world remains visible:

| Style | Behavior |
|---|---|
| `.mixed` | Virtual content blends with passthrough (AR-like) |
| `.progressive` | Passthrough replaced in a portion of the display |
| `.full` | Passthrough completely off -- fully virtual environment |

```swift
// Target API -- SceneViewSwift immersive space
@main
struct MyApp: App {
    @State private var immersionStyle: ImmersionStyle = .mixed

    var body: some SceneView {
        // Standard window for UI controls
        WindowGroup { ControlPanel() }

        // Immersive space for spatial content
        ImmersiveSpace(id: "spatialScene") {
            ImmersiveSceneView { root in
                // Place content in the user's physical space
                let model = try? await ModelNode.load("models/furniture.usdz")
                if let model { root.addChild(model.entity) }
            }
            .handTracking(enabled: true)
            .spatialAnchors(enabled: true)
            .sceneUnderstanding(enabled: true)
        }
        .immersionStyle(selection: $immersionStyle, in: .mixed, .progressive, .full)
    }
}
```

---

## Hand Tracking

ARKit on visionOS provides `HandTrackingProvider` for tracking hand and finger joint
positions at display refresh rate. Up to 27 joints per hand are available.

### Key ARKit APIs

- **`HandTrackingProvider`** -- source of live hand position data
- **`HandAnchor`** -- position and orientation of one hand
- **`HandSkeleton`** -- 27 named joints per hand (wrist, thumb tip, index tip, etc.)
- **`anchorUpdates`** -- `AsyncSequence` delivering real-time updates
- **`latestAnchors`** -- poll for current positions

### Requirements

- visionOS 1.0+
- App must be in a **Full Space** (not shared space) to access hand tracking data
- `NSHandsTrackingUsageDescription` in Info.plist
- `SpatialTrackingSession` must be configured and running

### Target SceneViewSwift API

```swift
// HandTrackingNode -- planned SceneViewSwift wrapper
public struct HandTrackingNode {
    /// Tracked joint positions for left and right hands.
    public var leftHand: HandAnchor?
    public var rightHand: HandAnchor?

    /// Position of a specific joint.
    public func jointPosition(_ joint: HandSkeleton.JointName, hand: Chirality) -> SIMD3<Float>?

    /// Distance between two joints (e.g., pinch detection = thumb tip to index tip).
    public func jointDistance(
        _ jointA: HandSkeleton.JointName,
        _ jointB: HandSkeleton.JointName,
        hand: Chirality
    ) -> Float?
}
```

```swift
// Usage in ImmersiveSceneView -- planned API
ImmersiveSceneView { root in
    // Content here
}
.handTracking(enabled: true)
.onHandUpdate { hands in
    // hands.leftHand, hands.rightHand
    if let pinchDistance = hands.jointDistance(.thumbTip, .indexFingerTip, hand: .right),
       pinchDistance < 0.02 {
        // Pinch gesture detected
        placeObject(at: hands.jointPosition(.indexFingerTip, hand: .right))
    }
}
```

### Gesture Detection Patterns

| Gesture | Detection method |
|---|---|
| Pinch | Distance between thumb tip and index finger tip < threshold |
| Point | Index finger extended, others curled |
| Open palm | All fingers extended, palm facing camera |
| Fist | All fingers curled |
| Custom | Combine joint positions and angles |

---

## Spatial Anchors

Spatial anchors persist content placement across sessions, allowing objects to remain
in the same physical location when the user returns to a space.

### Key ARKit APIs

- **`WorldTrackingProvider`** -- provides world tracking, plane detection, scene mesh
- **`WorldAnchor`** -- a persistent anchor at a world position
- **`SpatialTrackingSession`** -- authorizes and manages ARKit tracking
- **`SpatialTrackingSession.Configuration`** -- configures which capabilities to enable (hand tracking, world tracking, plane detection, scene understanding)

### SpatialTrackingSession

`SpatialTrackingSession` (visionOS 2.0+) is the gateway to ARKit data in RealityKit.
It unlocks anchor geometry extents, real-world offset data, and scene understanding mesh:

```swift
// Setting up a SpatialTrackingSession
let session = SpatialTrackingSession()
var config = SpatialTrackingSession.Configuration()
config.camera = .disallowed          // or .required
config.hands = .required             // enable hand tracking
config.sceneUnderstanding = .required // enable scene mesh

do {
    let unavailableCapabilities = try await session.run(config)
    if unavailableCapabilities.isEmpty {
        print("All spatial tracking features available")
    }
} catch {
    print("Spatial tracking failed: \(error)")
}
```

### Target SceneViewSwift API

```swift
// SpatialAnchorNode -- planned SceneViewSwift wrapper
public struct SpatialAnchorNode: Sendable {
    public let entity: AnchorEntity

    /// Creates a persistent world anchor at the given position.
    public static func world(position: SIMD3<Float>) -> SpatialAnchorNode

    /// Creates an anchor on a detected horizontal or vertical surface.
    public static func plane(alignment: PlaneAlignment) -> SpatialAnchorNode

    /// Creates an anchor attached to a hand joint.
    public static func hand(
        _ chirality: Chirality,
        joint: HandSkeleton.JointName
    ) -> SpatialAnchorNode

    /// Adds a child entity to this anchor.
    public func add(_ child: Entity)
}
```

```swift
// Usage -- planned API
ImmersiveSceneView { root in
    // Anchor content to a detected table surface
    let tableAnchor = SpatialAnchorNode.plane(alignment: .horizontal)
    let vase = try? await ModelNode.load("models/vase.usdz")
    if let vase { tableAnchor.add(vase.entity) }
    root.addChild(tableAnchor.entity)

    // Anchor content to the user's right hand
    let handAnchor = SpatialAnchorNode.hand(.right, joint: .indexFingerTip)
    let cursor = GeometryNode.sphere(radius: 0.005, color: .cyan)
    handAnchor.add(cursor.entity)
    root.addChild(handAnchor.entity)
}
```

---

## Scene Understanding

visionOS can generate a real-time mesh of the user's surroundings. This mesh enables
virtual objects to interact with physical surfaces through collision and physics.

### Capabilities

| Feature | Description |
|---|---|
| **Scene mesh** | Triangle mesh of room geometry (walls, floor, furniture) |
| **Plane detection** | Horizontal and vertical surfaces with extents |
| **Classification** | Label surfaces (floor, wall, ceiling, table, seat, door, window) |
| **Occlusion** | Virtual objects hidden behind real-world surfaces |

### Target SceneViewSwift API

```swift
ImmersiveSceneView { root in
    // Content
}
.sceneUnderstanding(enabled: true)
.onMeshUpdate { meshAnchors in
    // meshAnchors contains the updated scene mesh
    for anchor in meshAnchors {
        // anchor.geometry -- MeshAnchor.Geometry with vertices, normals, faces
        // anchor.classification -- .floor, .wall, .table, etc.
    }
}
.environmentOcclusion(enabled: true) // virtual objects occluded by real world
```

---

## Object Manipulation (visionOS 26)

visionOS 26 introduces `ManipulationComponent` for direct manipulation of 3D entities
using system gestures -- look, tap, drag, rotate, scale -- without custom gesture code.

### Key APIs (visionOS 26+)

- **`ManipulationComponent`** -- enables grab, drag, rotate, scale on an entity
- **`ManipulationComponent.configureEntity(_:)`** -- convenience that adds collision, input target, and hover effect
- **`EnvironmentBlendingComponent`** -- real-world occlusion of virtual objects
- **`MeshInstancesComponent`** -- efficient GPU instanced rendering of many copies

### Target SceneViewSwift API

```swift
// Planned API for object manipulation
let model = try await ModelNode.load("models/chair.usdz")
model.enableManipulation()  // Wraps ManipulationComponent.configureEntity
// User can now look at, grab, drag, rotate, and scale the model with system gestures

// Or with more control:
model.enableManipulation(
    allowTranslation: true,
    allowRotation: true,
    allowScale: true,
    snapToSurface: true
)
```

---

## Implementation Roadmap

### Phase 1: Volumetric Windows (Low complexity)

- [ ] `VolumetricSceneView` -- SceneView variant for `.volumetric` window style
- [ ] Automatic `defaultSize` in meters
- [ ] Depth gesture support (z-axis drag)
- [ ] Estimated effort: 1-2 weeks

### Phase 2: Immersive Spaces (Medium complexity)

- [ ] `ImmersiveSceneView` -- wrapper for `ImmersiveSpace` scene type
- [ ] Mixed / progressive / full immersion style support
- [ ] `SpatialTrackingSession` setup and lifecycle management
- [ ] Scene understanding mesh with collision
- [ ] Environment occlusion via `EnvironmentBlendingComponent`
- [ ] Estimated effort: 3-4 weeks

### Phase 3: Hand Tracking (Medium complexity)

- [ ] `HandTrackingNode` -- wrapper around `HandTrackingProvider`
- [ ] Joint position queries and visualization
- [ ] Built-in gesture detection (pinch, point, open palm)
- [ ] `.onHandUpdate` view modifier
- [ ] Hand-anchored content (`SpatialAnchorNode.hand`)
- [ ] Estimated effort: 2-3 weeks

### Phase 4: Spatial Anchors & Persistence (Medium complexity)

- [ ] `SpatialAnchorNode` with world, plane, and hand anchor types
- [ ] Persistent anchors across app sessions (via `WorldAnchor`)
- [ ] Plane anchor with surface classification
- [ ] Estimated effort: 2-3 weeks

### Phase 5: Object Manipulation (Low complexity, requires visionOS 26)

- [ ] `enableManipulation()` on ModelNode wrapping `ManipulationComponent`
- [ ] `MeshInstancesComponent` integration for efficient instanced rendering
- [ ] Estimated effort: 1-2 weeks

### Prerequisites

- visionOS 1.0+ for volumes and basic immersive spaces
- visionOS 2.0+ for `SpatialTrackingSession`, enhanced hand tracking, anchor geometry
- visionOS 26 for `ManipulationComponent`, `EnvironmentBlendingComponent`, `MeshInstancesComponent`
- Apple Vision Pro hardware for testing (or Xcode Simulator for basic layout)

---

## API Design Principles

The visionOS spatial APIs in SceneViewSwift follow the same design principles as the
existing iOS and macOS APIs:

1. **Declarative over imperative** -- configure via SwiftUI view modifiers, not callbacks
2. **Progressive disclosure** -- simple cases are simple, advanced features are available
3. **Platform-native** -- thin wrappers over RealityKit/ARKit, not abstractions
4. **AI-friendly** -- clear parameter names, comprehensive documentation, predictable patterns
5. **Consistent with SceneView Android** -- same concepts, platform-appropriate APIs

### Mapping to Android XR

| Feature | visionOS (SceneViewSwift) | Android XR (SceneView) |
|---|---|---|
| Spatial container | Volume (`.volumetric`) | `SpatialPanel` |
| Immersive mode | `ImmersiveSpace` | `SpatialEnvironment` |
| Hand tracking | `HandTrackingProvider` | Jetpack XR hand tracking API |
| Spatial anchors | `WorldAnchor` | `AnchorEntity` (SceneCore) |
| Scene understanding | Scene mesh + classification | Perception APIs |
| Object manipulation | `ManipulationComponent` | `GltfModelEntity` manipulation |
