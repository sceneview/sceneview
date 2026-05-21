---
title: Samples — SceneView for iOS, macOS, visionOS
description: "SwiftUI + RealityKit sample code for SceneViewSwift: model viewer, geometry shapes, dynamic sky, physics, text, fog, reflections, and AR tap-to-place."
---

# Samples — Apple Platforms

!!! tip "Looking for Android samples?"
    See [Samples](samples.md) for 15 working Jetpack Compose sample apps with source code.

These samples demonstrate SceneViewSwift capabilities using **SwiftUI + RealityKit** on iOS, macOS, and visionOS. Each example is a self-contained SwiftUI view you can drop into an Xcode project after adding the SceneViewSwift package.

```swift
.package(url: "https://github.com/sceneview/sceneview.git", from: "4.13.0")
```

---

## 3D samples

### Model Viewer

<!-- Screenshot placeholder: A USDZ car model rendered with studio IBL lighting,
     orbit camera at ~30 degrees elevation, soft grounding shadow beneath the model. -->

Load a USDZ model with environment lighting, orbit camera, and animation playback.

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

**Demonstrates:** `ModelNode.load`, `scaleToUnits`, `playAllAnimations`, `SceneEnvironment.studio`, orbit camera, grounding shadows

---

### Geometry Shapes

<!-- Screenshot placeholder: Four procedural shapes (red cube, metallic sphere,
     blue cylinder, green cone) arranged in a row with studio lighting. -->

Create procedural geometry with PBR materials — no external model files required.

```swift
import SwiftUI
import SceneViewSwift

struct GeometryShapesSample: View {
    var body: some View {
        SceneView { root in
            // Red cube
            let cube = GeometryNode.cube(size: 0.3, color: .red)
                .position(.init(x: -0.6, y: 0.15, z: -2))
            root.addChild(cube.entity)

            // Metallic sphere
            let sphere = GeometryNode.sphere(
                radius: 0.2,
                material: .pbr(color: .gray, metallic: 1.0, roughness: 0.2)
            )
            .position(.init(x: -0.1, y: 0.2, z: -2))
            root.addChild(sphere.entity)

            // Blue cylinder
            let cylinder = GeometryNode.cylinder(
                radius: 0.12, height: 0.4, color: .blue
            )
            .position(.init(x: 0.4, y: 0.2, z: -2))
            root.addChild(cylinder.entity)

            // Green cone
            let cone = GeometryNode.cone(
                height: 0.4, radius: 0.15, color: .green
            )
            .position(.init(x: 0.9, y: 0.2, z: -2))
            root.addChild(cone.entity)
        }
        .environment(.studio)
        .cameraControls(.orbit)
    }
}
```

**Demonstrates:** `GeometryNode.cube`, `sphere`, `cylinder`, `cone`, `GeometryMaterial.pbr`, procedural mesh generation, PBR material parameters

---

### Dynamic Sky

<!-- Screenshot placeholder: A scene with a sphere on a plane, warm sunset
     directional light casting long shadows, slider at the bottom controlling time of day. -->

Drive a directional light's color, direction, and intensity from a time-of-day slider. Sunrise at 06:00, noon at 12:00, sunset at 18:00.

```swift
import SwiftUI
import SceneViewSwift

struct DynamicSkySample: View {
    @State private var hour: Float = 12

    var body: some View {
        VStack {
            SceneView { root in
                // Ground plane
                let ground = GeometryNode.plane(
                    width: 5, depth: 5, color: .init(white: 0.4, alpha: 1)
                )
                root.addChild(ground.entity)

                // Subject sphere
                let sphere = GeometryNode.sphere(
                    radius: 0.3,
                    material: .pbr(color: .white, metallic: 0.0, roughness: 0.5)
                )
                .position(.init(x: 0, y: 0.3, z: -2))
                .withGroundingShadow()
                root.addChild(sphere.entity)

                // Dynamic sky light driven by slider
                let sky = DynamicSkyNode(
                    timeOfDay: hour,
                    turbidity: 3,
                    sunIntensity: 1200
                )
                root.addChild(sky.entity)
            }
            .cameraControls(.orbit)

            HStack {
                Text("06:00")
                Slider(value: $hour, in: 0...24, step: 0.5)
                Text("24:00")
            }
            .padding()
        }
    }
}
```

**Demonstrates:** `DynamicSkyNode`, time-of-day presets (`.sunrise()`, `.noon()`, `.sunset()`, `.night()`), turbidity, reactive SwiftUI state driving 3D lighting

---

### Physics Playground

<!-- Screenshot placeholder: Several colored cubes and spheres mid-fall above
     a grey floor plane, some resting and stacked at the bottom. -->

Tap to throw balls that bounce off a floor and each other using rigid-body physics.

```swift
import SwiftUI
import SceneViewSwift

struct PhysicsPlaygroundSample: View {
    @State private var balls: [Entity] = []

    var body: some View {
        SceneView { root in
            // Static floor
            let floor = GeometryNode.plane(
                width: 10, depth: 10, color: .lightGray
            )
            PhysicsNode.static(floor.entity, restitution: 0.8)
            root.addChild(floor.entity)

            // Dynamic balls
            for ball in balls {
                root.addChild(ball)
            }
        }
        .environment(.studio)
        .cameraControls(.orbit)
        .onEntityTapped { _ in
            // Spawn a ball at a random position above the floor
            let colors: [SimpleMaterial.Color] = [.red, .blue, .green, .yellow, .orange]
            let ball = GeometryNode.sphere(
                radius: 0.15,
                color: colors.randomElement() ?? .red
            )
            .position(.init(
                x: Float.random(in: -1...1),
                y: 3.0,
                z: Float.random(in: -2...(-1))
            ))
            PhysicsNode.dynamic(
                ball.entity,
                mass: 1.0,
                restitution: 0.7,
                friction: 0.3
            )
            balls.append(ball.entity)
        }
    }
}
```

**Demonstrates:** `PhysicsNode.dynamic`, `PhysicsNode.static`, `restitution` (bounciness), `friction`, collision shapes, `applyImpulse`, `setVelocity`

---

### Text & Billboards

<!-- Screenshot placeholder: Three spheres at different positions with white text labels
     floating above each one ("Earth", "Mars", "Venus"), all facing the camera. -->

Place 3D text labels that always face the camera using `BillboardNode`.

```swift
import SwiftUI
import SceneViewSwift

struct TextBillboardSample: View {
    var body: some View {
        SceneView { root in
            let planets: [(String, SIMD3<Float>, SimpleMaterial.Color)] = [
                ("Earth", .init(x: -1, y: 0, z: -3), .cyan),
                ("Mars",  .init(x:  0, y: 0, z: -3), .red),
                ("Venus", .init(x:  1, y: 0, z: -3), .orange),
            ]

            for (name, pos, color) in planets {
                // Planet sphere
                let sphere = GeometryNode.sphere(radius: 0.2, color: color)
                    .position(pos)
                root.addChild(sphere.entity)

                // Billboard label above the sphere
                let label = BillboardNode.text(name, fontSize: 0.04, color: .white)
                    .position(.init(x: pos.x, y: pos.y + 0.35, z: pos.z))
                root.addChild(label.entity)
            }
        }
        .environment(.studio)
        .cameraControls(.orbit)
    }
}
```

**Demonstrates:** `TextNode`, `BillboardNode`, `BillboardNode.text` convenience, `BillboardComponent`, camera-facing behavior, `.centered()`

---

### Image & Video

<!-- Screenshot placeholder: A poster image on the left and a 16:9 video screen
     on the right, both floating in a 3D scene with studio lighting. -->

Display images and videos on 3D planes in the scene.

```swift
import SwiftUI
import SceneViewSwift

struct ImageVideoSample: View {
    @State private var imageNode: ImageNode?
    @State private var videoNode: VideoNode?

    var body: some View {
        SceneView { root in
            // Image on a plane
            if let imageNode {
                root.addChild(imageNode.entity)
            }

            // Video on a plane
            if let videoNode {
                root.addChild(videoNode.entity)
            }
        }
        .environment(.studio)
        .cameraControls(.orbit)
        .task {
            // Load an image from the bundle
            imageNode = try? await ImageNode.load(
                "textures/poster.png",
                width: 0.8,
                height: 1.0
            )
            .position(.init(x: -0.8, y: 0.5, z: -3))

            // Load a video from the bundle
            videoNode = VideoNode.load(
                "videos/intro.mp4",
                width: 1.2,
                height: 0.675,
                loop: true
            )
            .position(.init(x: 0.8, y: 0.5, z: -3))
            videoNode?.play()
        }
    }
}
```

**Demonstrates:** `ImageNode.load`, `VideoNode.load`, `VideoNode.play` / `pause` / `stop` / `seek`, video looping, image sizing, unlit vs lit image materials

---

### Fog & Reflections

<!-- Screenshot placeholder: A metallic sphere and cube on a floor with cool-tinted
     linear fog fading distant objects, subtle cubemap reflections visible on the sphere. -->

Combine atmospheric fog with local cubemap reflections for realistic environments.

```swift
import SwiftUI
import SceneViewSwift

struct FogReflectionSample: View {
    var body: some View {
        SceneView { root in
            // Ground plane
            let floor = GeometryNode.plane(
                width: 20, depth: 20, color: .init(white: 0.3, alpha: 1)
            )
            root.addChild(floor.entity)

            // Metallic sphere
            let sphere = GeometryNode.sphere(
                radius: 0.4,
                material: .pbr(color: .gray, metallic: 1.0, roughness: 0.1)
            )
            .position(.init(x: -0.5, y: 0.4, z: -3))
            .withGroundingShadow()
            root.addChild(sphere.entity)

            // Rough cube
            let cube = GeometryNode.cube(
                size: 0.5,
                material: .pbr(color: .blue, metallic: 0.5, roughness: 0.6),
                cornerRadius: 0.04
            )
            .position(.init(x: 0.6, y: 0.25, z: -3))
            .withGroundingShadow()
            root.addChild(cube.entity)

            // Linear fog
            let fog = FogNode.linear(start: 2.0, end: 15.0)
                .color(.cool)
            root.addChild(fog.entity)

            // Reflection probe around the objects
            let probe = ReflectionProbeNode.box(
                size: .init(repeating: 6.0),
                intensity: 1.2
            )
            .position(.init(x: 0, y: 1.5, z: -3))
            root.addChild(probe.entity)
        }
        .environment(.outdoor)
        .cameraControls(.orbit)
    }
}
```

**Demonstrates:** `FogNode.linear`, `FogNode.exponential`, `FogNode.heightBased`, `FogNode.Color.cool`, `ReflectionProbeNode.box`, `ReflectionProbeNode.sphere`, `.intensity()`, `.environmentTexture()`

---

## AR samples

### AR Tap-to-Place

<!-- Screenshot placeholder: Camera feed showing a detected horizontal plane with
     a 3D model (a small chair) placed on a real table surface, plane overlay visible. -->

Detect real-world surfaces and tap to place a 3D model. Includes coaching overlay and plane visualization.

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

                // Create an anchor at the tapped surface point
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

**Demonstrates:** `ARSceneView`, `AnchorNode.world`, plane detection (`.horizontal`, `.vertical`, `.both`), coaching overlay, `onTapOnPlane` hit testing, `ModelNode` in AR

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
