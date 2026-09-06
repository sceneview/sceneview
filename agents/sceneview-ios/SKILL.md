---
name: sceneview-ios
description: Build 3D and AR apps on Apple platforms (iOS, macOS, visionOS) with SceneViewSwift — the SwiftUI wrapper around RealityKit. Use whenever the user asks for "3D in SwiftUI", "AR with ARKit in SwiftUI", a model viewer for iOS, or any Apple-platform 3D/AR app where the dependency is the `SceneViewSwift` Swift Package from `github.com/sceneview/sceneview`. For Jetpack Compose / Android use the `sceneview` skill instead; for the browser use `sceneview-web`. Skip for plain ARKit-SDK / SceneKit / Unity / Unreal / RealityKit-without-SceneViewSwift work.
license: Apache-2.0
metadata:
  author: SceneView
  source: https://github.com/sceneview/sceneview
  last-updated: '2026-08-06'
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
  - stl
  - obj
  - ply
  - mesh formats
  - 3d printing
  - 3mf
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
  version tag (currently `4.34.0`).

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
- "Load a `.usdz` / `.reality` / `.stl` / `.obj` / `.ply` / `.3mf` model on iOS."
- "Open an STL from Files / show a 3D print at real size in AR."
- "Place a model on a detected AR plane in ARKit + SwiftUI."
- "Add 3D content to a visionOS / macOS app with SceneView."
- "Convert this SceneKit / RealityView code to SceneViewSwift."
- "Make `SceneViewer` from our Compose Multiplatform app actually render on iOS."

Skip for plain ARKit-SDK, SceneKit, Unity, Unreal, or RealityKit projects that
do NOT use the SceneViewSwift wrapper.

### If the ask comes from a Compose Multiplatform app

`sceneview-compose` exposes one `SceneViewer` composable from `commonMain`, but a Kotlin
Multiplatform module cannot depend on a Swift Package — so on iOS it renders a "no
renderer registered" notice until the **app** supplies one. That supply is a Swift task,
which is why it lands here:

1. Write an `@objc UIView` wrapping `SceneViewSwift.SceneView` in a
   `UIHostingController`. The production-tested pattern is
   `flutter/sceneview_flutter/ios/Classes/SceneViewPlugin.swift` — copy its retain-cycle
   and Swift-6 actor handling rather than reinventing them.
2. Implement `SceneViewerViewFactory` (`create` **and** `update` — Kotlin/Native does not
   bridge interface default bodies into the generated Obj-C protocol, so Swift must
   implement both), and assign `SceneViewerBridge.factory` once before the first compose.
3. `update` must **mutate** the existing view. Recreating the scene reloads the model and
   throws away the user's camera position.
4. Call the spec's `onCameraMoved` after every gesture — without it `CameraState` reports
   only what the app last wrote — and `onError` when a load fails, since a failed load on
   RealityKit looks exactly like a slow one.

`SceneViewerSpec` compares by value (model bytes by content), so `update` is **not**
called on every recomposition. Do not build Swift-side change caching that assumes it is.

SceneView does not ship that wrapper yet. If the user wants AR, custom materials or
post-processing, they are outside `sceneview-compose`'s scope — use `ARSceneView` /
`SceneView` from SceneViewSwift directly.

## Setup

```swift
// Package.swift
.package(url: "https://github.com/sceneview/sceneview.git", from: "4.34.0")
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
        .cameraControls(.orbit)        // .orbit | .pan | .firstPerson | native (iOS 18+): .none | .tilt | .dolly
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

## Model formats and units

`ModelNode.load(contentsOf:unit:)` is the **one entry point for every supported
format**. It sniffs the format from the file's own bytes before believing the
extension — a model that arrived through a share sheet or a download often has
the wrong one.

| Format | Reader | Default unit |
|---|---|---|
| `.usdz` `.usda` `.usdc` `.usd` `.reality` | RealityKit | metres (already metric) |
| `.stl` | ModelIO | **millimetres** |
| `.obj` (+ `.mtl`) | ModelIO | metres |
| `.ply` | ModelIO | metres |
| `.3mf` | SceneViewSwift's own parser | **declared by the file** |

`.3mf` is parsed by SceneViewSwift, not by Apple — `MDLAsset` does not read it and
neither does Quick Look, even though it is the format 3D printing standardised
on. It is also the only mesh format here that states its own unit.

**glTF (`.glb` / `.gltf`) is NOT supported on Apple platforms.** Neither
RealityKit nor ModelIO reads it. Convert to USDZ (Reality Converter,
`usdzconvert`) or use the Android/web SDK. Do not emit Swift that loads a `.glb`.

**Always reason about the unit.** STL, OBJ and PLY store bare numbers with no
unit: a slicer STL means millimetres, a photogrammetry OBJ means metres.
RealityKit works in metres, so getting this wrong puts a 21 cm print in the room
at 210 m. `ModelFormat.carriesUnit` is `false` for exactly those three — that is
the signal to ask the user, or to offer a mm/cm/in picker.

One example per format — each compiles as written:

```swift
import SceneViewSwift

// USDZ — already metric, `unit:` is ignored.
let helmet = try await ModelNode.load("models/helmet.usdz")

// STL — millimetres by default, so a 210 mm print is 0.21 m in AR.
let printed = try await ModelNode.load(contentsOf: stlURL)

// OBJ + .mtl sidecar, authored in centimetres.
let panel = try await ModelNode.load(contentsOf: objURL, unit: .centimeters)

// PLY from a phone scanner, bundled with the app.
let bust = try await ModelNode.load("scans/bust.ply", unit: .meters)

// 3MF from a slicer — the file states its own unit, so pass none.
let plate = try await ModelNode.load(contentsOf: threeMFURL)
```

Measure before you render — parsing has no RealityKit dependency:

```swift
let asset = try MeshAsset.load(contentsOf: url)
print(asset.format, asset.unit, asset.triangleCount)
if let size = asset.boundsInMeters?.extents {
    print("real size in metres:", size)   // the "will it fit?" number
}
let node = try await ModelNode(asset)     // then display it
```

Failures name the format: `ModelLoadingError.unsupportedFormat(fileExtension:)`
carries the extension the user actually tried, so surface it
("SceneView cannot open .fbx files yet") instead of a generic "load failed".

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

2. **A unitless mesh format needs a `unit:`.** `.stl`, `.obj` and `.ply` carry no
   unit; RealityKit is metric. Pass `unit:` when you know it, and default to the
   format's own convention otherwise (mm for STL — see "Model formats and
   units"). Never emit code that loads a `.glb` / `.gltf` on Apple platforms.

3. **RealityKit entities are `@MainActor`-isolated.** Never mutate an `Entity`
   from `DispatchQueue.global()`. Use `await MainActor.run { }` to cross back.
   SwiftUI `.task` already runs on the main actor for view work.

4. **`AnchorNode` factories are iOS-specific** — `AnchorNode.world(position:)`
   and `AnchorNode.plane(alignment:minimumBounds:)`. This differs from Android,
   where `AnchorNode` wraps a `com.google.ar.core.Anchor`. Do NOT translate the
   Android `AnchorNode(anchor:)` shape to Swift.

5. **`GeometryNode` factories** are static methods: `.cube(size:color:)`,
   `.sphere(radius:color:)`, `.cylinder(radius:height:color:)`,
   `.plane(width:depth:color:)`, `.cone(height:radius:color:)`, `.torus(...)`,
   `.capsule(...)`. There are NO `CubeNode` / `SphereNode` types — that naming
   is Android-only.

6. **`LightNode` factories** are `.directional(color:intensity:castsShadow:)`,
   `.point(color:intensity:attenuationRadius:)`,
   `.spot(color:intensity:innerAngle:outerAngle:)`. Position/aim via the fluent
   `.position(_:)` / `.lookAt(_:)` modifiers — not Android's `LightManager.Type`
   enum + `apply` lambda.

7. **Some Android APIs do not port to RealityKit.** Before re-attacking a
   deprecated symbol, consult the "iOS parity status (#1036)" tables in
   `cheatsheet-ios.md`: `CameraNode.exposure`, `CameraNode.depthOfField`, and
   `LightNode.shadowColor` are compile-warning no-ops on iOS;
   `ARSceneView(playbackDataset:)`, `StreetscapeGeometry`, terrain/rooftop
   anchors have no ARKit equivalent. `ARRecorder` on iOS is **record-only**
   (ReplayKit screen capture) — there is no deterministic playback.
   `SurfaceMirrorer` / `rememberSurfaceMirrorer()` (in-app scene→MP4 recording,
   #2626) is **Android-only** — on iOS record the scene via that same ReplayKit path.

8. **`SceneView` is cross-platform (iOS/macOS/visionOS); `ARSceneView` is iOS
   only.** macOS and visionOS get 3D but not the ARKit camera view.

9. **Cloud Anchors on iOS are a wrapper you complete — not a missing feature.**
   `CloudAnchorNode.host(ttlDays:completion:operation:)` /
   `.resolve(cloudAnchorId:completion:operation:)` and the cancellable
   `CloudAnchorFuture` are REAL SceneViewSwift API. By design the core library
   stays dependency-free: the billed `GARSession` round-trip is supplied by the
   **app**, which adds Google's `arcore-ios-sdk` (SPM `google-ar/arcore-ios-sdk`,
   product `ARCoreCloudAnchors`) and plugs it into the `operation:` closure.
   Generate that app-side wiring — never tell a developer Cloud Anchors are
   impossible on iOS. Caveat: the `ios-demo` app itself does NOT link
   arcore-ios-sdk, so its `ar-cloud-anchor` screen shows a "Preview" badge with
   Host / Resolve disabled (plane detection + tap-to-place stay live). See #2864.

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

- Build and run on the simulator — resolve the destination, never hardcode
  `name=iPhone <model>`. A model name is a promise about a machine you do not
  own, and the resolver also waits out the CoreSimulator cold window that made
  iOS CI look flaky ([#3174](https://github.com/sceneview/sceneview/issues/3174)):

  ```bash
  . .claude/scripts/lib/ios-simulator.sh
  # Assign — the resolver reports failure through its EXIT CODE, and `set -e`
  # cannot see a `$(...)` that fails inside another command's arguments.
  DEST="$(ios_simulator_destination)"
  xcodebuild -scheme SceneViewSwift -destination "$DEST"
  ```
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
