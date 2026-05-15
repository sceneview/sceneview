---
title: Apple Quickstart — SceneView for iOS, macOS, visionOS
description: "Set up SceneViewSwift in your Xcode project in 10 minutes. Build a 3D model viewer with orbit camera and environment lighting using SwiftUI and RealityKit."
---

# Quickstart — Apple Platforms

!!! tip "Looking for Android?"
    See the [Android Quickstart](quickstart.md) for Jetpack Compose setup.

**Time:** ~10 minutes |
**Goal:** Go from an empty Xcode project to a 3D model you can orbit with touch gestures — on iOS, macOS, or visionOS.

---

## Prerequisites

- **Xcode 15** or newer
- An Apple device or simulator running **iOS 17+**, **macOS 14+**, or **visionOS 1+**
- Basic familiarity with Swift and SwiftUI

---

## Step 1: Create a new project

1. Open Xcode and select **File > New > Project**.
2. Choose the **App** template under the platform you are targeting (iOS, macOS, or multiplatform).
3. Set the interface to **SwiftUI** and language to **Swift**.
4. Finish the wizard.

You should have a working SwiftUI app that displays "Hello, world!" or similar.

---

## Step 2: Add the dependency

SceneViewSwift is distributed as a **Swift Package**.

1. In Xcode, go to **File > Add Package Dependencies**.
2. Enter the repository URL:

```
https://github.com/sceneview/sceneview.git
```

3. Set the version rule to **Up to Next Major** from `4.0.0`.
4. Click **Add Package** and add `SceneViewSwift` to your app target.

!!! tip
    You can also add the dependency manually in your `Package.swift`:
    ```swift
    .package(url: "https://github.com/sceneview/sceneview.git", from: "4.3.5")
    ```

---

## Step 3: Add a 3D model

You need a USDZ file in your Xcode project.

1. Download the **Toy Drummer** from Apple's AR Quick Look gallery, or use any `.usdz` file.
2. Drag the file into your Xcode project navigator and ensure it is added to your app target.
3. Name it `toy_drummer.usdz` (or update the path in the next step).

!!! tip
    RealityKit supports `.usdz` and `.reality` files natively. If you have a `.glb` file, convert it using Apple's Reality Converter or the `usdzconvert` command-line tool.

---

## Step 4: Write the SceneView

Replace the contents of `ContentView.swift` with the following:

```swift
import SwiftUI
import SceneViewSwift

struct ContentView: View {
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
            model = try? await ModelNode.load("toy_drummer.usdz")
            model?.scaleToUnits(1.0)
        }
    }
}
```

That is the entire app. Here is what each piece does:

| Call | Purpose |
|---|---|
| `SceneView { root in }` | The 3D viewport — add entities as children of the root |
| `.environment(.studio)` | Applies studio IBL lighting for physically-based rendering |
| `.cameraControls(.orbit)` | Adds built-in orbit, pan, and zoom touch gestures |
| `ModelNode.load(...)` | Asynchronously loads a USDZ model; throws on failure |
| `.scaleToUnits(1.0)` | Fits the model into a 1-metre bounding cube |

!!! warning "Load models asynchronously"
    `ModelNode.load` is an `async` function. Always call it from a `.task` modifier or an async context. The `@State` property starts as `nil` — the scene renders empty until the model is ready.

---

## Step 5: Run it

1. Click **Run** (or press `Cmd+R`).
2. After a brief loading moment, you will see the model rendered in your viewport.
3. **Drag** to orbit around the model, **pinch** to zoom.

That is a production-quality, physically-based 3D viewer in under 20 lines of code.

---

## Minimal AR example (iOS only)

AR requires a physical iOS device with ARKit support. Add camera permissions to your `Info.plist`:

```xml
<key>NSCameraUsageDescription</key>
<string>This app uses the camera for augmented reality.</string>
```

Then create an AR scene with tap-to-place:

```swift
import SwiftUI
import SceneViewSwift

struct ARContentView: View {
    var body: some View {
        ARSceneView(
            planeDetection: .horizontal,
            onTapOnPlane: { position, arView in
                // Place a 10 cm blue cube at the tapped surface
                let cube = GeometryNode.cube(size: 0.1, color: .blue)
                let anchor = AnchorNode.world(position: position)
                anchor.add(cube.entity)
                arView.scene.addAnchor(anchor.entity)
            }
        )
        .ignoresSafeArea()
    }
}
```

Point the camera at a flat surface, wait for plane detection, then tap to place a cube.

---

## Platform requirements

| Platform | Minimum version | Renderer |
|---|---|---|
| iOS | 17.0 | RealityKit + ARKit |
| macOS | 14.0 | RealityKit |
| visionOS | 1.0 | RealityKit |

!!! tip "AR is iOS-only"
    `ARSceneView` uses `ARView` from ARKit, which is only available on iOS. `SceneView` (3D without AR) works on all three platforms.

---

## Next steps

- **Add environment lighting** — Use `.environment(.sunset)` or create a custom environment with `SceneEnvironment.custom(name:hdrFile:intensity:)`.
- **Try geometry** — Add shapes with `GeometryNode.cube(...)`, `GeometryNode.sphere(...)`, and `GeometryNode.cylinder(...)`.
- **Add physics** — Use `PhysicsNode.dynamic(entity)` to make objects fall with gravity.
- **Build for visionOS** — The same `SceneView` API works in immersive spaces on Apple Vision Pro.
- **Need a custom 3D model?** — See the [Blender pipeline](recipes/blender-pipeline.md) for authoring a model in Blender and converting it to `.usdz`.
- **Explore the Android SDK** — See the [Android Quickstart](quickstart.md) for the Compose equivalent of every API shown here.
