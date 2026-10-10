---
title: Samples — SceneView for iOS, macOS, visionOS
description: "SwiftUI + RealityKit sample code for SceneViewSwift: model viewer, geometry shapes, camera controls, AR tap-to-place, physics, audio, text, reflections, and 30+ more demos."
---

# Samples — Apple Platforms

!!! tip "Looking for Android samples?"
    See [Samples](samples.md) for Jetpack Compose sample apps with source code.

These samples demonstrate SceneViewSwift capabilities using **SwiftUI + RealityKit** on iOS, macOS, and visionOS. The [iOS demo app](https://apps.apple.com/app/sceneview/id6761329763) ships **34 demos** covering every category.

```swift
.package(url: "https://github.com/sceneview/sceneview.git", from: "4.54.0")
```

All demo source files live in
[`samples/ios-demo/SceneViewDemo/Views/Demos/`](https://github.com/sceneview/sceneview/tree/main/samples/ios-demo/SceneViewDemo/Views/Demos/).

---

## Demo catalog

The iOS demo app organises all samples under six categories, mirroring the Android catalog.

### 3D Basics

| Demo | Source | What it shows |
|---|---|---|
| Models | [`ModelViewerDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ModelViewerDemo.swift) | Load a USDZ with orbit camera, IBL, and animation; a Park mode loads several models in one scene ([`MultiModelDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/MultiModelDemo.swift)) |
| Geometry | [`GeometryScene.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/Scenes/GeometryScene.swift) | Procedural shapes — cube, sphere, cylinder, cone, plane |
| Animation | [`AnimationDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/AnimationDemo.swift) | `playAllAnimations()`, `autoRotate`, timeline scrubbing |

### Lighting

| Demo | Source | What it shows |
|---|---|---|
| Lighting | [`LightTypesDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/LightTypesDemo.swift) | One card, three rigs: Image (HDR environments), Studio (key, fill, rim), Sun (time of day) |

### Content

| Demo | Source | What it shows |
|---|---|---|
| 3D Text | [`TextDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/TextDemo.swift) | Extruded 3D text with depth, color, and font control |
| Lines & Paths | [`LinesPathsDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/LinesPathsDemo.swift) | `LineNode`, `PathNode`, axis gizmo |
| Image Planes | [`ImagePlaneDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ImagePlaneDemo.swift) | `ImageNode` — textures on planes in 3D space |
| Billboard | [`BillboardDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/BillboardDemo.swift) | Camera-facing labels and sprites |
| Video Texture | [`VideoTextureDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/VideoTextureDemo.swift) | `VideoNode` — play / pause / loop video on a 3D plane |

### Interaction

| Demo | Source | What it shows |
|---|---|---|
| Camera & Gestures | [`CameraControlsDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/CameraControlsDemo.swift) | Camera mode: `.orbit`, `.pan`, `.firstPerson`; native Apple modes `.none/.tilt/.dolly` (iOS 18+). Gestures mode ([`GestureEditingDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/GestureEditingDemo.swift)): drag, scale and rotate entities |
| Collision & Hit Test | [`CollisionHitTestDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/CollisionHitTestDemo.swift) | Ray-casting against geometry, highlight on tap |

### Advanced

| Demo | Source | What it shows |
|---|---|---|
| Rolling Balls | [`RollingBallsDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/RollingBallsDemo.swift) | Drop rubber, steel and foam balls on a tray, tilt it, knock the opening pyramid over; a Pendulum mode runs a chaotic double pendulum ([`DoublePendulumDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/DoublePendulumDemo.swift)) |
| Custom Mesh | [`CustomMeshDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/CustomMeshDemo.swift) | `MeshNode.fromVertices` — raw vertex data |
| PBR Materials | [`MaterialsDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/MaterialsDemo.swift) | Full PBR material parameter explorer; an Occlusion mode hides entities behind an occluder plane ([`OcclusionMaterialDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/OcclusionMaterialDemo.swift)) |
| Spatial Audio | [`SpatialAudioDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/SpatialAudioDemo.swift) | `SpatialAudioNode` — positional audio tied to scene entities |
| Lighting Lab | [`LightingLabDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/LightingLabDemo.swift) | Environment intensity, sky, sunset reflection probe with a camera zone |
| Shape Extrude | [`ShapeExtrudeDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ShapeExtrudeDemo.swift) | `ShapeNode` — extrude a 2D path into a 3D solid |
| Debug Overlay | [`DebugOverlayDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/DebugOverlayDemo.swift) | Live FPS counter + sphere stress test |

### AR (iOS only)

AR samples require a physical device with ARKit support (A9+ chip, iOS 18+).
`ar-placement` is the single placement catalogue entry. Depth occlusion requires LiDAR;
people occlusion requires supported person segmentation. Every AR card stays in the
catalogue on every device: opening one on a device that cannot run it shows the honest
requirement card before any camera starts, rather than the card disappearing.
Occlusion comparisons keep perception enabled and change only the renderer effect.
Every demo's settings sheet carries a shared **Record** action (ReplayKit screen video,
not AR-session playback), which replaced the former AR Recording card.

| Demo | Source | What it shows |
|---|---|---|
| AR Placement | [`ARPlacementDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARPlacementDemo.swift) | One 0.3 m preview model automatically placed on the first usable horizontal surface; drag, pinch and twist to adjust. Modes: Place, Wall, Free pose ([`ARPoseDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARPoseDemo.swift)), Light ([`ARLightingDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARLightingDemo.swift)) |
| AR Orbital | [`OrbitalARDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/OrbitalARDemo.swift) | Orbit camera in AR passthrough mode |
| AR Image Tracking | [`ARImageTrackingDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARImageTrackingDemo.swift) | Track printed reference images |
| Face anchor accessories | [`ARAugmentedFacesDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARAugmentedFacesDemo.swift) | Accessories pinned to a tracked face anchor — pose only, no morphable mesh |
| AR Depth Occlusion | [`ARDepthOcclusionDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARDepthOcclusionDemo.swift) | Bundled helmet placed automatically; toggle LiDAR mesh occlusion without moving or rescaling it |
| AR People Occlusion | [`ARPeopleOcclusionDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARPeopleOcclusionDemo.swift) | The same bundled helmet and placement flow; toggle person occlusion without restarting tracking |
| Body anchor tracking | [`ARBodyTrackerDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARBodyTrackerDemo.swift) | Follow a detected body anchor — anchor pose, not per-joint data |
| AR Scene Mesh | [`ARSceneMeshDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/ARSceneMeshDemo.swift) | LiDAR scene reconstruction mesh |
| Rerun AR Replay | [`RerunShowcaseDemo.swift`](https://github.com/sceneview/sceneview/blob/main/samples/ios-demo/SceneViewDemo/Views/Demos/Rerun/RerunShowcaseDemo.swift) | Record a room, replay it in 3D, and export it to `.rrd`, `.glb`, `.usdz` and `.ply` |

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

3D samples run on iOS 18+, macOS 15+, and visionOS 2+. They work in both the Simulator and on physical devices.

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
