# SceneViewSwift

3D and AR scenes in SwiftUI, powered by RealityKit. The Apple companion to [SceneView for Android](https://github.com/sceneview/sceneview).

![iOS 18+](https://img.shields.io/badge/iOS-18%2B-blue)
![macOS 15+](https://img.shields.io/badge/macOS-15%2B-blue)
![visionOS 1+](https://img.shields.io/badge/visionOS-1%2B-blue)

## Installation

Add SceneViewSwift via Swift Package Manager:

1. In Xcode, go to **File > Add Package Dependencies...**
2. Enter the repository URL:
   ```
   https://github.com/sceneview/sceneview
   ```
3. Select the version rule and add the package to your target.

Or add it to your `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/sceneview/sceneview", from: "4.17.0")
]
```

## Quick Start

### 3D Model Viewer

```swift
import SwiftUI
import SceneViewSwift

struct ModelViewer: View {
    @State private var model: ModelNode?

    var body: some View {
        SceneView { content in
            if let model {
                content.addChild(model.entity)
            }
        }
        .environment(.studio)
        .cameraControls(.orbit)
        .autoRotate()
        .task {
            model = try? await ModelNode.load("robot.usdz")
        }
    }
}
```

### AR Tap-to-Place

```swift
import SwiftUI
import SceneViewSwift

struct ARPlacement: View {
    @State private var model: ModelNode?
    @State private var placedEntities: [Entity] = []

    var body: some View {
        ARSceneView(
            planeDetection: .horizontal,
            onTapOnPlane: { position in
                if let model {
                    let clone = model.entity.clone(recursive: true)
                    clone.position = position
                    // Add to AR scene
                }
            }
        )
        .task {
            model = try? await ModelNode.load("chair.usdz")
        }
    }
}
```

### Procedural Shapes with PBR

```swift
SceneView { root in
    let metal = GeometryNode.sphere(
        radius: 0.3,
        material: .pbr(color: .gray, metallic: 1.0, roughness: 0.1)
    )
    .position(.init(x: 0, y: 0.3, z: 0))
    .withGroundingShadow()
    root.addChild(metal.entity)
}
.environment(.sunset)
```

### Lights

```swift
let sun = LightNode.directional(color: .warm, intensity: 1000, castsShadow: true)
    .lookAt(.zero)

let lamp = LightNode.point(color: .custom(r: 1, g: 0.5, b: 0), intensity: 500)
    .position(.init(x: 1, y: 2, z: 0))
```

### 3D Text & Billboards

```swift
// Always faces camera
let label = BillboardNode.text("Player 1", fontSize: 0.04)
    .position(.init(x: 0, y: 1.5, z: 0))

// 3D extruded text
let title = TextNode(text: "SceneView", fontSize: 0.08, depth: 0.02)
    .centered()
```

## API Reference

### Views

| Type | Description |
|---|---|
| `SceneView` | 3D scene with orbit camera, lighting, and gestures |
| `ARSceneView` | AR scene with plane detection and tap-to-place |

### Nodes

| Type | Description |
|---|---|
| `ModelNode` | USDZ model loading with animations and collision |
| `GeometryNode` | Procedural shapes (cube, sphere, cylinder, cone, plane) |
| `MeshNode` | Custom mesh geometry from raw vertex data |
| `ShapeNode` | 2D polygon shapes (flat or extruded) with ear-clipping triangulation |
| `TextNode` | 3D extruded text with centering |
| `BillboardNode` | Always-faces-camera wrapper |
| `LineNode` | Line segments and axis gizmos |
| `PathNode` | Closed and open 3D paths with customizable geometry (circle, grid helpers) |
| `LightNode` | Directional, point, and spot lights |
| `CameraNode` | Programmatic camera control with orbit, fly-through, and custom modes |
| `ImageNode` | Display images on 3D planes with automatic aspect ratio |
| `VideoNode` | Play video content on 3D surfaces with playback controls |
| `PhysicsNode` | Apply physics simulation (dynamic, static, kinematic) to entities |
| `DynamicSkyNode` | Time-of-day sun positioning with atmospheric color model |
| `FogNode` | Atmospheric fog (linear, exponential, height-based) |
| `ReflectionProbeNode` | Local cubemap reflections for realistic surfaces |
| `AnchorNode` | AR world/plane anchor |
| `AugmentedImageNode` | Detect real-world images and place 3D content (iOS only) |

### Configuration

| Type | Description |
|---|---|
| `SceneEnvironment` | 6 HDR presets: studio, outdoor, sunset, night, warm, autumn |
| `CameraControls` | Orbit camera with inertia and auto-rotation |
| `GeometryMaterial` | PBR material: `.simple`, `.pbr`, `.unlit` |

## Platform Support

| Feature | iOS 18+ | macOS 15+ | visionOS 2+ |
|---|---|---|---|
| `SceneView` — 3D scene | ✅ | ✅ | ✅ |
| `ARSceneView` — AR/camera passthrough | ✅ | ❌ AR requires device camera | ❌ AR requires device camera |
| All 3D nodes (`ModelNode`, `GeometryNode`, lights, etc.) | ✅ | ✅ | ✅ |
| `BillboardNode` — always faces camera | ✅ | ✅ | ✅ |
| `CameraNode` field-of-view / clip planes | ✅ | ⚠️ RealityKit `PerspectiveCameraComponent` not available on macOS | ✅ |
| `SceneViewHaptic` | ✅ | ❌ iOS-only | ❌ |
| `AnchorNode` / `AugmentedImageNode` | ✅ | ❌ ARKit is iOS/iPadOS-only | ❌ |

**macOS:** `SceneView` (3D rendering) is fully supported on macOS 15+. `ARSceneView` is iOS/iPadOS-only and is not available on macOS — `ARSceneView.swift` is wrapped in `#if os(iOS)`. The same applies to all ARKit-dependent features.

## Platform Mapping (Android ↔ iOS)

| Android (Compose) | iOS (SwiftUI) |
|---|---|
| `SceneView { }` | `SceneView { root in }` |
| `ARSceneView { }` | `ARSceneView()` |
| `rememberModelInstance` | `ModelNode.load()` |
| `CubeNode` | `GeometryNode.cube()` |
| `SphereNode` | `GeometryNode.sphere()` |
| `ShapeNode` | `ShapeNode(points:)` |
| `LightNode(apply = { })` | `LightNode.directional()` |
| `rememberEnvironment` | `.environment(.studio)` |
| `CameraManipulator` | `CameraControls` |

## Example App

See [`Examples/SceneViewDemo/`](Examples/SceneViewDemo/) for a full 4-tab demo:
- **Explore** -- 3D viewer with orbit camera and 6 HDR environments
- **Shapes** -- All primitive shapes with live previews and code snippets
- **AR** -- Tap-to-place objects on real surfaces
- **About** -- SDK information and feature list

## License

Apache 2.0. See [LICENSE](LICENSE) for details.
