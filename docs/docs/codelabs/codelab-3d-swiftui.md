# CodeLab: Your first 3D scene with SceneViewSwift

**Time:** ~15 minutes
**Level:** Beginner (requires Swift + SwiftUI basics)
**What you'll build:** A 3D model viewer with orbit camera, environment lighting, and tap interaction

---

## Step 1 — What you'll build

By the end of this codelab, you will have a fully working 3D scene that:

- Loads a USDZ 3D model asynchronously
- Renders it with physically-based environment lighting
- Responds to orbit/zoom gestures
- Reacts to tap gestures
- Overlays standard SwiftUI on top of the 3D viewport

This mirrors the Android [3D with Compose codelab](codelab-3d-compose.md), built with SwiftUI and RealityKit instead.

**No 3D experience required.** If you know SwiftUI, you already know most of this.

---

## Step 2 — Create the Xcode project

1. Open Xcode and select **File > New > Project**.
2. Choose **App** under the iOS tab (or Multiplatform if you want macOS too).
3. Set **Interface** to SwiftUI, **Language** to Swift.
4. Name it `My3DViewer` and finish the wizard.

---

## Step 3 — Add SceneViewSwift via SPM

1. Go to **File > Add Package Dependencies**.
2. Enter the URL:

```
https://github.com/sceneview/sceneview.git
```

3. Set the version rule to **Up to Next Major** from `4.15.0`.
4. Click **Add Package** and add `SceneViewSwift` to your app target.

---

## Step 4 — Add a 3D model

You need a USDZ file. Options:

- Download a free model from [Apple's AR Quick Look Gallery](https://developer.apple.com/augmented-reality/quick-look/)
- Convert a `.glb` using [Reality Converter](https://developer.apple.com/augmented-reality/tools/)
- Use any `.usdz` file you already have

Drag the file into your Xcode project navigator. Make sure **"Add to target"** is checked for your app.

For this codelab, we will assume the file is named `toy_drummer.usdz`.

---

## Step 5 — The empty SceneView

Replace the contents of `ContentView.swift`:

```swift
import SwiftUI
import SceneViewSwift

struct ContentView: View {
    var body: some View {
        SceneView { root in
            // Empty scene — just the viewport
        }
    }
}
```

Run the app. You will see a dark viewport with default lighting — that is the RealityKit scene with no content. This is your empty 3D canvas.

---

## Step 6 — Load and display a model

Add state to hold the loaded model and load it asynchronously:

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
        .task {
            model = try? await ModelNode.load("toy_drummer.usdz")
            model?.scaleToUnits(1.0)   // fit into a 1-metre cube
        }
    }
}
```

Run the app. The model appears after a brief load. It may be hard to see — there is only default directional lighting.

!!! warning "Always handle loading failure"
    `ModelNode.load` is a throwing async function. The `try?` pattern shown above returns `nil` on failure. In production, use `do/catch` to display an error state.

---

## Step 7 — Add environment lighting

Apply an IBL (image-based lighting) environment for physically-based rendering:

```swift
SceneView { root in
    if let model {
        root.addChild(model.entity)
    }
}
.environment(.studio)
.task {
    model = try? await ModelNode.load("toy_drummer.usdz")
    model?.scaleToUnits(1.0)
}
```

Run again. The model now has soft, realistic lighting with specular highlights and ambient occlusion driven by the studio HDR.

??? tip "Available environment presets"
    SceneViewSwift ships with several presets: `.studio`, `.outdoor`, `.sunset`, `.night`, `.warm`, `.autumn`. You can also load a custom HDR with `SceneEnvironment.custom(name:hdrFile:intensity:)`.

---

## Step 8 — Add orbit camera gestures

One line:

```swift
SceneView { root in
    // ...
}
.environment(.studio)
.cameraControls(.orbit)
```

Run the app. You can now:

- **Drag** to orbit around the model
- **Pinch** to zoom in/out

That is the complete camera interaction system.

---

## Step 9 — Add a tap handler

React when the user taps on the scene:

```swift
@State private var tapped = false

SceneView { root in
    if let model {
        root.addChild(model.entity)
    }
}
.environment(.studio)
.cameraControls(.orbit)
.onEntityTapped { entity in
    tapped.toggle()
}
```

---

## Step 10 — Overlay SwiftUI

SwiftUI views compose naturally with SceneView. Wrap everything in a `ZStack`:

```swift
var body: some View {
    ZStack(alignment: .bottom) {
        SceneView { root in
            if let model {
                root.addChild(model.entity)
            }
        }
        .environment(.studio)
        .cameraControls(.orbit)
        .onEntityTapped { entity in
            tapped.toggle()
        }
        .task {
            model = try? await ModelNode.load("toy_drummer.usdz")
            model?.scaleToUnits(1.0)
            model?.playAllAnimations()
        }

        Text(tapped ? "Tapped!" : "Tap the model")
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial, in: Capsule())
            .padding(.bottom, 32)
    }
}
```

No special APIs needed. The 3D scene is just a SwiftUI view inside a `ZStack`.

---

## Step 11 — Complete code

```swift
import SwiftUI
import SceneViewSwift

struct ContentView: View {
    @State private var model: ModelNode?
    @State private var tapped = false

    var body: some View {
        ZStack(alignment: .bottom) {
            SceneView { root in
                if let model {
                    root.addChild(model.entity)
                }
            }
            .environment(.studio)
            .cameraControls(.orbit)
            .onEntityTapped { entity in
                tapped.toggle()
            }
            .task {
                model = try? await ModelNode.load("toy_drummer.usdz")
                model?.scaleToUnits(1.0)
                model?.playAllAnimations()
            }

            Text(tapped ? "Tapped!" : "Tap the model")
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(.ultraThinMaterial, in: Capsule())
                .padding(.bottom, 32)
        }
    }
}
```

That is ~30 lines. A production-quality 3D model viewer with orbit camera, environment lighting, animations, and tap interaction.

---

## Step 12 — What's next?

- **Add AR** — See the [AR with SwiftUI codelab](codelab-ar-swiftui.md) — same package, `ARSceneView` instead of `SceneView`
- **Add geometry** — Try `GeometryNode.cube(...)`, `.sphere(...)`, `.cylinder(...)` in the content closure
- **Add physics** — Use `PhysicsNode.dynamic(entity)` to make objects fall and bounce
- **Try auto-rotation** — Add `.autoRotate(speed: 0.3)` for a turntable effect
- **Cross-platform** — Compare with the [Android 3D codelab](codelab-3d-compose.md) to see the API parallels
