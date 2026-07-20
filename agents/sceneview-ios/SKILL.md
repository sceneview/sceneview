---
name: sceneview-ios
description: Build 3D and AR apps on Apple platforms (iOS, macOS, visionOS) with SceneViewSwift — the SwiftUI wrapper around RealityKit. Use whenever the user asks for "3D in SwiftUI", "AR with ARKit in SwiftUI", a model viewer for iOS, or any Apple-platform 3D/AR app where the dependency is the `SceneViewSwift` Swift Package from `github.com/sceneview/sceneview`. For Jetpack Compose / Android use the `sceneview` skill instead; for the browser use `sceneview-web`. Skip for plain ARKit-SDK / SceneKit / Unity / Unreal / RealityKit-without-SceneViewSwift work.
license: Apache-2.0
metadata:
  author: SceneView
  source: https://github.com/sceneview/sceneview
  last-updated: '2026-05-22'
  keywords:
  - sceneview
  - sceneviewswift
  - 3d
  - ar
  - arkit
  - realitykit
  - swiftui
  - model viewer
  - augmented reality
  - ios
  - macos
  - visionos
  - swift package manager
  - usdz
---

## What SceneViewSwift is

SceneViewSwift is the Apple-platform half of the SceneView SDK — a **declarative
SwiftUI** API over **RealityKit**. Same mental model as the Android library
(`SceneView { }` / `ARSceneView { }`) but expressed with SwiftUI result-builders
and view modifiers, not Jetpack Compose.

- **3D** — `SceneView { … }` SwiftUI view (iOS / macOS / visionOS).
- **AR** — `ARSceneView(…)` SwiftUI view (iOS only — ARKit).
- **Renderer** — RealityKit. There is **no Filament** on Apple platforms.
- **Distribution** — Swift Package Manager. The package lives in the
  `github.com/sceneview/sceneview` monorepo; consumers add it by URL and pin a
  version tag (currently `4.24.0`).

`SceneViewSwift` is consumable directly from Swift, and also underneath the
Flutter and React Native bridges.

## Authoritative API reference

Three sources, in priority order:

1. **`docs/docs/cheatsheet-ios.md`** in the repo — the complete Apple-platform
   API reference (every node factory, view modifier, environment preset,
   the Android↔Apple mapping table, and the iOS parity-status tables).
   <https://github.com/sceneview/sceneview/blob/main/docs/docs/cheatsheet-ios.md>
2. **`SceneViewSwift/Sources/SceneViewSwift/`** — the actual Swift source. When
   in doubt about a signature, read it. Node factories live under `Nodes/`,
   the views in `SceneView.swift` / `ARSceneView.swift`.
3. **`samples/ios-demo/SceneViewDemo/Views/Demos/`** — a working SwiftUI demo
   for every feature. Read the demo, do NOT improvise an API.

`llms.txt` at the repo root carries the cross-platform surface but is
Android-centric — prefer `cheatsheet-ios.md` for Swift.

## When to use this skill

Trigger on any of:

- "Build me a 3D viewer / AR app in SwiftUI."
- "Load a `.usdz` / `.glb` / `.gltf` / `.reality` model on iOS."
- "Place a model on a detected AR plane in ARKit + SwiftUI."
- "Add 3D content to a visionOS / macOS app with SceneView."
- "Convert this SceneKit / RealityView code to SceneViewSwift."

Skip for plain ARKit-SDK, SceneKit, Unity, Unreal, or RealityKit projects that
do NOT use the SceneViewSwift wrapper.

## Setup

```swift
// Package.swift
.package(url: "https://github.com/sceneview/sceneview.git", from: "4.24.0")
```

```swift
import SceneViewSwift
```

For AR, the host app's `Info.plist` must declare `NSCameraUsageDescription`.
For `ARRecorder` saving to Photos, add `NSPhotoLibraryAddUsageDescription`.

## The minimal correct 3D example

Verified against `samples/ios-demo/.../ModelViewerDemo.swift` and the
declarative `@NodeBuilder` initializer in `SceneView.swift`:

```swift
import SwiftUI
import SceneViewSwift

struct ModelViewerScreen: View {
    @State private var model: ModelNode?

    var body: some View {
        SceneView { root in
            if let model {
                root.addChild(model.entity)
            }
        }
        .environment(.studio)          // IBL lighting preset
        .cameraControls(.orbit)        // .orbit | .pan | .firstPerson | native (iOS 18+): .none | .tilt | .dolly | .gimbal
        .autoCenterContent(true)
        .task {
            model = try? await ModelNode.load("models/helmet.usdz")
            model?.scaleToUnits(0.3)
            model?.playAllAnimations()
        }
    }
}
```

The declarative form uses the `@NodeBuilder` result-builder — nodes are
expressions inside the trailing closure:

```swift
SceneView {
    GeometryNode.cube(size: 0.3, color: .red)
        .position(.init(x: -1, y: 0, z: -2))
    GeometryNode.sphere(radius: 0.2, color: .blue)
        .position(.init(x: 1, y: 0, z: -2))
}
.environment(.studio)
.cameraControls(.orbit)
```

## The minimal correct AR example

Verified against `samples/ios-demo/.../ARPlacementDemo.swift`. `ARSceneView` is
a `UIViewRepresentable` — iOS only:

```swift
ARSceneView(
    planeDetection: .horizontal,        // .horizontal | .vertical | .both | .none
    showPlaneOverlay: true,
    showCoachingOverlay: true,
    showPlacementReticle: true,   // built-in smoothed placement cursor (#894)
    // groundingShadows: true is the default — tap-placed models get a
    // RealityKit contact shadow automatically (opt out with false).
    onTapOnPlane: { position, arView in
        let anchor = AnchorNode.world(position: position)
        let cube = GeometryNode.cube(size: 0.1, color: .blue)
        anchor.add(cube.entity)
        arView.scene.addAnchor(anchor.entity)
    }
)
.onSessionStarted { arView in /* session began */ }
```

## Critical rules (verified — do not break)

1. **`ModelNode.load(_:)` is `async throws`.** It is NOT a `remember*`-style
   nullable. Call it inside a SwiftUI `.task { }` (or another async context)
   and `try`/`try?` it. Store the result in `@State`.

2. **RealityKit entities are `@MainActor`-isolated.** Never mutate an `Entity`
   from `DispatchQueue.global()`. Use `await MainActor.run { }` to cross back.
   SwiftUI `.task` already runs on the main actor for view work.

3. **`AnchorNode` factories are iOS-specific** — `AnchorNode.world(position:)`
   and `AnchorNode.plane(alignment:minimumBounds:)`. This differs from Android,
   where `AnchorNode` wraps a `com.google.ar.core.Anchor`. Do NOT translate the
   Android `AnchorNode(anchor:)` shape to Swift.

4. **`GeometryNode` factories** are static methods: `.cube(size:color:)`,
   `.sphere(radius:color:)`, `.cylinder(radius:height:color:)`,
   `.plane(width:depth:color:)`, `.cone(height:radius:color:)`, `.torus(...)`,
   `.capsule(...)`. There are NO `CubeNode` / `SphereNode` types — that naming
   is Android-only.

5. **`LightNode` factories** are `.directional(color:intensity:castsShadow:)`,
   `.point(color:intensity:attenuationRadius:)`,
   `.spot(color:intensity:innerAngle:outerAngle:)`. Position/aim via the fluent
   `.position(_:)` / `.lookAt(_:)` modifiers — not Android's `LightManager.Type`
   enum + `apply` lambda.

6. **Some Android APIs do not port to RealityKit.** Before re-attacking a
   deprecated symbol, consult the "iOS parity status (#1036)" tables in
   `cheatsheet-ios.md`: `CameraNode.exposure`, `CameraNode.depthOfField`, and
   `LightNode.shadowColor` are compile-warning no-ops on iOS;
   `ARSceneView(playbackDataset:)`, `StreetscapeGeometry`, terrain/rooftop
   anchors have no ARKit equivalent. `ARRecorder` on iOS is **record-only**
   (ReplayKit screen capture) — there is no deterministic playback.
   `SurfaceMirrorer` / `rememberSurfaceMirrorer()` (in-app scene→MP4 recording,
   #2626) is **Android-only** — on iOS record the scene via that same ReplayKit path.

7. **`SceneView` is cross-platform (iOS/macOS/visionOS); `ARSceneView` is iOS
   only.** macOS and visionOS get 3D but not the ARKit camera view.

## Performance / hot paths

**Don't drive SwiftUI `@State` from a per-frame loop** (an `onFrame` / RealityKit
`update` closure). A `@State` write every frame churns the view `body` — the
per-frame loop should mutate entities (or a reference-box `class` you hold) and only
flip `@State` when UI-visible state actually changes. Same root rule as the other
platforms: never recompute or allocate per frame what you can read once and cache.
Full cross-platform guidance:
[`docs/docs/performance.md` § Hot Paths & Allocation-Free APIs](https://github.com/sceneview/sceneview/blob/main/docs/docs/performance.md)
(audit umbrella [#2263](https://github.com/sceneview/sceneview/issues/2263)).

## Toolchain pairing

Pair this skill with Xcode's command-line tools:

- `xcrun simctl boot "iPhone 16"` + `xcodebuild -scheme … -destination …` —
  build and run on the simulator.
- `xcrun simctl io booted screenshot ui.png` — capture the rendered scene.
- `swift build` / `swift test` from `SceneViewSwift/` — build/test the package
  in isolation.

## Resources

- **[Cheat sheet](./references/cheatsheet.md)** — pointer to the canonical
  `docs/docs/cheatsheet-ios.md` plus the most-used SwiftUI signatures.
- **[Recipes](./references/recipes.md)** — pointers to the working demo in
  `samples/ios-demo/` for each canonical pattern. Read the demo, copy from it.
- **[Migration](./references/migration.md)** — SceneKit / RealityView →
  SceneViewSwift, and the Android↔Apple mapping.

## Workflow guidance

When the user asks for a SceneViewSwift feature:

1. **Confirm the Apple platform.** iOS / macOS / visionOS — `ARSceneView` is
   iOS-only; `SceneView` works everywhere.
2. **Pick the right entrypoint.** `SceneView { }` for 3D, `ARSceneView(…)` for
   AR. Mention `import SceneViewSwift` and the SPM dependency line.
3. **Read the matching demo** under `samples/ios-demo/.../Demos/` before
   writing code. Fall back to `cheatsheet-ios.md`. Never invent an API.
4. **`ModelNode.load` is async** — wrap it in `.task { }`, store in `@State`.
5. **Keep entity mutation on `@MainActor`.**
6. **For AR**, remind the user about `NSCameraUsageDescription` in `Info.plist`.
7. **If the user pastes Android Kotlin**, do NOT translate it character by
   character — the result-builder + modifiers + factory naming differ. Use the
   Android↔Apple mapping table in `cheatsheet-ios.md`.
8. **Before reusing a deprecated symbol**, check the iOS parity-status tables.
