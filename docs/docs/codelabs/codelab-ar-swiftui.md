# CodeLab: AR with SwiftUI — SceneViewSwift

**Time:** ~15 minutes
**Prerequisites:** Complete the [3D with SwiftUI codelab](codelab-3d-swiftui.md) first, or have basic SceneViewSwift knowledge
**What you'll build:** An AR scene that detects horizontal planes and places 3D objects at tapped locations

---

## Step 1 — What you'll build

By the end of this codelab, you will have a working AR app that:

- Shows the device camera feed as background
- Detects horizontal surfaces with ARKit
- Displays a coaching overlay while tracking initializes
- Places 3D objects when the user taps on a detected plane
- Loads and places a USDZ model in the real world

!!! warning "Physical device required"
    AR requires a physical iOS device with ARKit support (iPhone 6s or later). The iOS Simulator does not support AR.

---

## Step 2 — Setup

### Add the dependency

If you have not already added SceneViewSwift, follow the same SPM steps from the [3D codelab](codelab-3d-swiftui.md):

1. **File > Add Package Dependencies**
2. URL: `https://github.com/sceneview/sceneview.git`
3. Version: from `4.16.7`

### Camera permission

Add a camera usage description to your `Info.plist`. You can do this in Xcode:

1. Select your project in the navigator.
2. Select your app target, then the **Info** tab.
3. Add a new key: `Privacy - Camera Usage Description`
4. Set the value to: `This app uses the camera for augmented reality.`

Or add it directly to `Info.plist`:

```xml
<key>NSCameraUsageDescription</key>
<string>This app uses the camera for augmented reality.</string>
```

!!! warning "Camera permission is required"
    Without `NSCameraUsageDescription`, the app will crash on launch when ARKit tries to access the camera. This is an Apple requirement, not a SceneViewSwift limitation.

---

## Step 3 — The empty ARSceneView

Create a new `ARContentView.swift`:

```swift
import SwiftUI
import SceneViewSwift

struct ARContentView: View {
    var body: some View {
        ARSceneView(planeDetection: .horizontal)
            .ignoresSafeArea()
    }
}
```

Run on a physical device. You will see the camera feed with a coaching overlay prompting you to move the device. Walk around slowly — when ARKit detects a horizontal surface, the coaching overlay will dismiss.

That is `ARSceneView` doing all the work: session lifecycle, camera stream, plane detection, and coaching.

---

## Step 4 — Tap to place a cube

Add the `onTapOnPlane` callback to place geometry when the user taps on a detected surface:

```swift
import SwiftUI
import SceneViewSwift

struct ARContentView: View {
    var body: some View {
        ARSceneView(
            planeDetection: .horizontal,
            onTapOnPlane: { position, arView in
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

Run on device. Point at a flat surface (table, floor), wait for plane detection, then tap on the surface. A 10 cm blue cube appears at the tap location, anchored to the real world.

Each tap creates a new anchor and cube. The objects stay in place as you move the camera.

---

## Step 5 — Place a 3D model instead

Load a USDZ model and place it on tap:

```swift
import SwiftUI
import SceneViewSwift

struct ARContentView: View {
    @State private var model: ModelNode?

    var body: some View {
        ARSceneView(
            planeDetection: .horizontal,
            onTapOnPlane: { position, arView in
                guard let model else { return }

                // Clone the model for each placement
                let clone = model.entity.clone(recursive: true)
                let anchor = AnchorNode.world(position: position)
                anchor.add(clone)
                arView.scene.addAnchor(anchor.entity)
            }
        )
        .ignoresSafeArea()
        .task {
            model = try? await ModelNode.load("toy_drummer.usdz")
            model?.scaleToUnits(0.3)   // 30 cm — appropriate for AR
        }
    }
}
```

Run on device. The model loads in the background, and each tap places a copy at the tapped surface location.

??? tip "Scale matters in AR"
    In AR, units are real-world meters. A `scaleToUnits(0.3)` model will appear 30 cm tall. Use values between 0.1 and 0.5 for tabletop objects. Use the model's actual real-world size when accuracy matters.

---

## Step 6 — Customize plane detection

You can detect vertical planes (walls) or both:

```swift
// Detect walls
ARSceneView(planeDetection: .vertical, ...)

// Detect both floors and walls
ARSceneView(planeDetection: .both, ...)

// Disable plane detection (for image tracking only)
ARSceneView(planeDetection: .none, ...)
```

Control the visual overlays:

```swift
ARSceneView(
    planeDetection: .horizontal,
    showPlaneOverlay: true,        // show/hide plane visualization
    showCoachingOverlay: true,     // show/hide coaching instructions
    onTapOnPlane: { ... }
)
```

---

## Step 7 — Add status UI with SwiftUI

Overlay SwiftUI on the AR view for instructions:

```swift
struct ARContentView: View {
    @State private var model: ModelNode?
    @State private var placedCount = 0

    var body: some View {
        ZStack(alignment: .bottom) {
            ARSceneView(
                planeDetection: .horizontal,
                onTapOnPlane: { position, arView in
                    guard let model else { return }
                    let clone = model.entity.clone(recursive: true)
                    let anchor = AnchorNode.world(position: position)
                    anchor.add(clone)
                    arView.scene.addAnchor(anchor.entity)
                    placedCount += 1
                }
            )
            .ignoresSafeArea()
            .task {
                model = try? await ModelNode.load("toy_drummer.usdz")
                model?.scaleToUnits(0.3)
            }

            VStack(spacing: 8) {
                if model == nil {
                    ProgressView("Loading model...")
                        .padding()
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
                } else {
                    Text(placedCount == 0
                        ? "Tap on a surface to place"
                        : "Placed \(placedCount) object\(placedCount == 1 ? "" : "s")")
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(.ultraThinMaterial, in: Capsule())
                }
            }
            .padding(.bottom, 32)
        }
    }
}
```

---

## Step 8 — Complete code

```swift
import SwiftUI
import SceneViewSwift

struct ARContentView: View {
    @State private var model: ModelNode?
    @State private var placedCount = 0

    var body: some View {
        ZStack(alignment: .bottom) {
            ARSceneView(
                planeDetection: .horizontal,
                showCoachingOverlay: true,
                onTapOnPlane: { position, arView in
                    guard let model else { return }
                    let clone = model.entity.clone(recursive: true)
                    let anchor = AnchorNode.world(position: position)
                    anchor.add(clone)
                    arView.scene.addAnchor(anchor.entity)
                    placedCount += 1
                }
            )
            .ignoresSafeArea()
            .task {
                model = try? await ModelNode.load("toy_drummer.usdz")
                model?.scaleToUnits(0.3)
            }

            VStack(spacing: 8) {
                if model == nil {
                    ProgressView("Loading model...")
                        .padding()
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
                } else {
                    Text(placedCount == 0
                        ? "Tap on a surface to place"
                        : "Placed \(placedCount) object\(placedCount == 1 ? "" : "s")")
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(.ultraThinMaterial, in: Capsule())
                }
            }
            .padding(.bottom, 32)
        }
    }
}
```

That is a complete AR placement app with model loading, plane detection, coaching, and status UI — in about 40 lines.

---

## Step 9 — What's next?

- **Image tracking** — Use `AugmentedImageNode` with `imageTrackingDatabase` to detect and overlay content on real-world images
- **Multiple plane types** — Try `.both` to detect walls and floors, then place shelves on walls and rugs on floors
- **Physics in AR** — Combine `PhysicsNode.dynamic(...)` with AR placement to drop objects that fall onto the real floor
- **Cross-platform** — Compare with the [Android AR codelab](codelab-ar-compose.md) to see the API parallels
- **Custom environments** — Use `onSessionStarted` to configure advanced ARKit features
