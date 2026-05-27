---
title: Samples — SceneView for iOS, macOS, visionOS
description: "SwiftUI + RealityKit sample code for SceneViewSwift: model viewer, geometry shapes, camera controls, AR tap-to-place, physics, audio, text, fog, reflections, and 50+ more demos."
---

# Samples — Apple Platforms

!!! tip "Looking for Android samples?"
    See [Samples](samples.md) for Jetpack Compose sample apps with source code.

These samples demonstrate SceneViewSwift capabilities using **SwiftUI + RealityKit** on iOS, macOS, and visionOS. The [iOS demo app](https://apps.apple.com/app/sceneview/id6761329763) ships **59 demos** covering every category.

```swift
.package(url: "https://github.com/sceneview/sceneview.git", from: "4.16.8")
```

All demo source files live in
[`samples/ios-demo/SceneViewDemo/Views/Demos/`](https://github.com/sceneview/sceneview/tree/main/samples/ios-demo/SceneViewDemo/Views/Demos/).

---

## Demo catalog

The iOS demo app organises all samples under six categories, mirroring the Android catalog.

### 3D Basics

| Demo | Source | What it shows |
|---|---|---|
| Model Viewer | [`ModelViewerDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ModelViewerDemo.swift) | Load a USDZ with orbit camera, IBL, and animation |
| Geometry | [`GeometryDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/GeometryDemo.swift) | Procedural shapes — cube, sphere, cylinder, cone, plane |
| Animation | [`AnimationDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/AnimationDemo.swift) | `playAllAnimations()`, `autoRotate`, timeline scrubbing |
| Multi-Model | [`MultiModelDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/MultiModelDemo.swift) | Multiple models loaded and placed in one scene |
| Scene Gallery | [`SceneGalleryDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/SceneGalleryDemo.swift) | Carousel of USDZ models with Sketchfab integration |

### Lighting

| Demo | Source | What it shows |
|---|---|---|
| Lighting | [`LightingDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/LightingDemo.swift) | Directional, point, and spot lights with PBR materials |
| Movable Light | [`MovableLightDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/MovableLightDemo.swift) | Drag a `LightNode` around the scene |
| Dynamic Sky | [`DynamicSkyDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/DynamicSkyDemo.swift) | Time-of-day slider drives `DynamicSkyNode` |
| Fog | [`FogDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/FogDemo.swift) | Linear / exponential / height-based atmospheric fog |
| Environment | [`EnvironmentDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/EnvironmentDemo.swift) | HDR environment presets (`.studio`, `.outdoor`, `.night`) |

### Content

| Demo | Source | What it shows |
|---|---|---|
| 3D Text | [`TextDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/TextDemo.swift) | Extruded 3D text with depth, color, and font control |
| Lines & Paths | [`LinesPathsDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/LinesPathsDemo.swift) | `LineNode`, `PathNode`, axis gizmo |
| Image Planes | [`ImageDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ImageDemo.swift) | `ImageNode` — textures on planes in 3D space |
| Billboard | [`BillboardDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/BillboardDemo.swift) | Camera-facing labels and sprites |
| Video Texture | [`VideoTextureDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/VideoTextureDemo.swift) | `VideoNode` — play / pause / loop video on a 3D plane |
| Texture Streaming | [`TextureStreamingDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/TextureStreamingDemo.swift) | Swap PBR material presets in real time — no geometry rebuild |

### Interaction

| Demo | Source | What it shows |
|---|---|---|
| Camera Controls | [`CameraControlsDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/CameraControlsDemo.swift) | `.orbit`, `.pan`, `.firstPerson`; native Apple modes `.none/.tilt/.dolly` (iOS 18+) |
| Collision & Hit Test | [`CollisionHitTestDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/CollisionHitTestDemo.swift) | Ray-casting against geometry, highlight on tap |
| Gesture Editing | [`GestureEditingDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/GestureEditingDemo.swift) | Drag, scale, and rotate entities via multi-touch gestures |

### Advanced

| Demo | Source | What it shows |
|---|---|---|
| Physics | [`PhysicsDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/PhysicsDemo.swift) | Rigid-body simulation — tap to spawn bouncing balls |
| Double Pendulum | [`DoublePendulumDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/DoublePendulumDemo.swift) | Chaotic double-pendulum physics |
| Custom Mesh | [`CustomMeshDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/CustomMeshDemo.swift) | `MeshNode.fromVertices` — raw vertex data |
| PBR Materials | [`MaterialsDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/MaterialsDemo.swift) | Full PBR material parameter explorer |
| Spatial Audio | [`SpatialAudioDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/SpatialAudioDemo.swift) | `SpatialAudioNode` — positional audio tied to scene entities |
| Reflection Probes | [`ReflectionProbesDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ReflectionProbesDemo.swift) | Local cubemap reflection probes |
| Shape Extrude | [`ShapeExtrudeDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ShapeExtrudeDemo.swift) | `ShapeNode` — extrude a 2D path into a 3D solid |
| Occlusion Material | [`OcclusionMaterialDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/OcclusionMaterialDemo.swift) | Occluder plane that hides entities behind virtual geometry |
| Debug Overlay | [`DebugOverlayDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/DebugOverlayDemo.swift) | Live FPS counter + sphere stress test |

### AR (iOS only)

AR samples require a physical device with ARKit support (A9+ chip, iOS 18+).

| Demo | Source | What it shows |
|---|---|---|
| AR Placement | [`ARPlacementDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARPlacementDemo.swift) | Tap-to-place USDZ on a detected plane |
| AR Instant Placement | [`ARInstantPlacementDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARInstantPlacementDemo.swift) | Place without waiting for plane detection |
| AR Orbital | [`OrbitalARDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/OrbitalARDemo.swift) | Orbit camera in AR passthrough mode |
| AR Lighting | [`ARLightingDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARLightingDemo.swift) | ARKit environmental lighting estimation |
| AR Recording | [`ARRecorderDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARRecorderDemo.swift) | ReplayKit-backed AR session capture |
| AR Image Tracking | [`ARImageTrackingDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARImageTrackingDemo.swift) | Track printed reference images |
| Augmented Faces | [`ARAugmentedFacesDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARAugmentedFacesDemo.swift) | Front-camera face pose tracking (`AnchorNode.face()`) |
| AR Depth Occlusion | [`ARDepthOcclusionDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARDepthOcclusionDemo.swift) | LiDAR depth occlusion on supported devices |
| AR People Occlusion | [`ARPeopleOcclusionDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARPeopleOcclusionDemo.swift) | Occlude AR content behind real people |
| AR Body Tracker | [`ARBodyTrackerDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARBodyTrackerDemo.swift) | Full-body skeleton tracking |
| AR Scene Mesh | [`ARSceneMeshDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARSceneMeshDemo.swift) | LiDAR scene reconstruction mesh |
| AR Debug (Rerun) | [`RerunDebugDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/RerunDebugDemo.swift) | Live AR debug data streamed to [Rerun.io](https://rerun.io) viewer |

---

## Minimal working examples

### Model viewer

```swift
import SwiftUI
import SceneViewSwift

struct ModelViewerSample: View {
    @State private var model: ModelNode?

    var body: some View {
        SceneView { root in
            if let model {
                root.addChild(model.entity)
            }
        }
        .environment(.studio)
        .cameraControls(.orbit)
        .task {
            model = try? await ModelNode.load("models/car.usdz")
                .scaleToUnits(1.0)
                .withGroundingShadow()
            model?.playAllAnimations()
        }
    }
}
```

### Procedural geometry

```swift
import SwiftUI
import SceneViewSwift

struct GeometryShapesSample: View {
    var body: some View {
        SceneView {
            GeometryNode.cube(size: 0.3, color: .red)
                .position(.init(x: -0.6, y: 0.15, z: -2))
            GeometryNode.sphere(
                radius: 0.2,
                material: .pbr(color: .gray, metallic: 1.0, roughness: 0.2)
            )
            .position(.init(x: 0.4, y: 0.2, z: -2))
        }
        .environment(.studio)
        .cameraControls(.orbit)
    }
}
```

### Camera controls with native Apple modes

```swift
import SwiftUI
import SceneViewSwift

struct CameraControlsSample: View {
    @State private var mode: CameraControlMode = .orbit

    var body: some View {
        SceneView { root in
            let cube = GeometryNode.cube(size: 0.35, color: .orange)
            root.addChild(cube.entity)
        }
        .cameraControls(mode)
        .ignoresSafeArea()
        // mode options: .orbit | .pan | .firstPerson
        // native Apple modes (iOS 18+, not available on visionOS):
        //   .none | .tilt | .dolly
    }
}
```

### AR tap-to-place

```swift
import SwiftUI
import SceneViewSwift

struct ARTapToPlaceSample: View {
    @State private var model: ModelNode?

    var body: some View {
        ARSceneView(
            planeDetection: .horizontal,
            showPlaneOverlay: true,
            showCoachingOverlay: true,
            onTapOnPlane: { position, arView in
                guard let model else { return }
                let anchor = AnchorNode.world(position: position)
                anchor.add(model.entity)
                arView.scene.addAnchor(anchor.entity)
            }
        )
        .ignoresSafeArea()
        .task {
            model = try? await ModelNode.load("models/chair.usdz")
                .scaleToUnits(0.5)
        }
    }
}
```

---

## Running the samples

### 3D samples

3D samples run on iOS 18+, macOS 15+, and visionOS 1+. They work in both the Simulator and on physical devices.

### AR samples

AR samples require:

- A physical iPhone or iPad with ARKit support (A9 chip or later)
- iOS 18 or later
- Camera permission granted

!!! tip
    For best AR tracking, use a well-lit environment with textured surfaces. Plain white surfaces and glass are difficult for ARKit to detect.

---

## Android samples

Looking for Android (Jetpack Compose) samples? See the [Android samples page](samples.md).
