# SceneViewSwift cheat sheet (Apple platforms)

The **canonical, complete** API reference is
[`docs/docs/cheatsheet-ios.md`](https://github.com/sceneview/sceneview/blob/main/docs/docs/cheatsheet-ios.md)
in the repo — it carries every node factory, every view modifier, the
environment presets, the Android↔Apple mapping table, and the iOS
parity-status tables. **Read that file first.** This page is a quick
extract of the most-used signatures, verified against
`SceneViewSwift/Sources/SceneViewSwift/`.

## Top-level views

| View | Platform | Demo |
| --- | --- | --- |
| `SceneView { … }` | iOS / macOS / visionOS | `ModelViewerDemo.swift` |
| `ARSceneView(…)` | iOS only (ARKit) | `ARPlacementDemo.swift` |

## `SceneView` — two initializers

```swift
// Declarative — @NodeBuilder result-builder
SceneView {
    GeometryNode.cube(size: 0.3, color: .red)
}

// Imperative — gives you the RealityKit root Entity
SceneView { root in
    root.addChild(model.entity)
}
```

## `SceneView` modifiers

```swift
SceneView { … }
    .environment(.studio)        // IBL preset — see "Environment presets" below
    .cameraControls(.orbit)      // .orbit (default) | .pan | .firstPerson | native (iOS 18+): .none | .tilt | .dolly | .gimbal
    .onEntityTapped { entity in }
    .autoRotate(speed: 0.3)      // turntable
    .autoCenterContent(true)     // translate content centroid to orbit pivot
    .framingMargin(0.95)         // auto-fit padding; 1.15 default, 1.0 = bounding sphere tangent, < 1 tighter
    .cameraOrbit(azimuth: .pi / 5, elevation: .pi / 15)  // INITIAL orbit pose, radians (defaults 0, 30°)
    .mainLight(.systemDefault)   // see LightSlot
    .fillLight(.systemDefault)
    .renderQuality(.default)     // .cinematic | .default | .performance
    .contentID(model?.id)        // re-runs the content closure IN PLACE when the id changes
```

**Swapping the model: `.contentID(_:)`, never SwiftUI's `.id(_:)`.** The content
closure runs once, when the scene is created. Change `.contentID(_:)` to show a
different model, and do not wrap the `SceneView` in an `if let model` that
unmounts it while the next one loads — put the spinner in an overlay. Both
change the view's *identity*, so SwiftUI discards the `RealityView` and builds a
new one, and a re-created `RealityView` on iOS 26 Simulator intermittently
renders nothing at all — no model, no skybox — permanently (#3008). The id must
change every time the closure would build something different, **including
"loading" → "loaded"**, so make it an `Optional`: keyed on the selection alone
it already sits at its final value while the model is still being fetched and
the scene stays empty forever.

```swift
ZStack {
    SceneView { root in
        guard let model else { return }
        root.addChild(model.entity)
    }
    .contentID(model == nil ? nil : selectedID)

    if model == nil { ProgressView() }
}
```

Android needs no equivalent — its DSL content is re-read on recomposition.

The auto-fit fits the content's **bounding sphere** to the narrower frustum
axis, then scales that distance by `framingMargin` — lower it to fill a tall
portrait frame, but stay at or above ~`0.95` on an `autoRotate` scene or a long
model clips at its broadside azimuth. `framingMargin` runs *inside* the auto-fit,
so it is a **silent no-op under `.autoCenterContent(false)`**. Android's
equivalent is `frameToContent(padding =)`, a fraction rather than a multiplier:
`margin == 1 + padding` (iOS `1.15` = Android `0.15`). `cameraOrbit` seeds only the starting
pose. Watch elevation: at the 60° vertical FOV the **30° default pitch puts the
horizon on the top edge**, so a `showSkybox` environment shows no sky until you
lower it.

## `ARSceneView` (iOS)

```swift
ARSceneView(
    planeDetection: .horizontal,   // .horizontal | .vertical | .both | .none
    showPlaneOverlay: true,
    showCoachingOverlay: true,
    showPlacementReticle: true,   // continuous smoothed placement cursor (#894, opt-in)
    groundingShadows: true,       // contact shadows on tap-placed models (#894, DEFAULT ON — opt out with false; sync placement flow only)
    onTapOnPlane: { position, arView in /* place content */ }
)
    .onSessionStarted { arView in }
    .cameraExposure(0.0)           // EV stops — AR-only camera exposure (iOS-specific semantics)
    .onFrame { frame, arView in }  // (ARFrame, ARView)
    .mainLight(.systemDefault)
    .fillLight(.systemDefault)
```

## Node factories — 3D

| Node | Factory | Notes |
| --- | --- | --- |
| `ModelNode` | `ModelNode.load("file.usdz")` | `async throws`. Then `.scaleToUnits(_:)`, `.centerOrigin(normalized:)` (apply before positioning), `.playAllAnimations()` |
| `GeometryNode` | `.cube(size:color:)` `.sphere(radius:color:)` `.cylinder(radius:height:color:)` `.plane(width:depth:color:)` `.cone(height:radius:color:)` `.torus(...)` `.capsule(...)` | also `material: .pbr(...)` overloads; `unlit: Bool` for flat fill |
| `LightNode` | `.directional(color:intensity:castsShadow:)` `.point(color:intensity:attenuationRadius:)` `.spot(color:intensity:innerAngle:outerAngle:)` | aim via `.position(_:)` / `.lookAt(_:)` |
| `TextNode` | `TextNode(text:fontSize:color:depth:)` | `.centered()` |
| `ImageNode` | `ImageNode.load("img.png")` | `async throws`; `width:` / `height:` |
| `BillboardNode` | `BillboardNode(child:)` / `BillboardNode.text(_:fontSize:color:)` | always faces camera |
| `LineNode` | `LineNode(from:to:color:)` | `SIMD3<Float>` endpoints |
| `PathNode` | `PathNode(points:closed:color:)` | `[SIMD3<Float>]` |
| `PhysicsNode` | `.dynamic(entity, mass:restitution:)` `.static(entity)` `.kinematic(entity)` | |
| `DynamicSkyNode` | `DynamicSkyNode(timeOfDay:turbidity:)` | `0...24` time cycle |
| `FogNode` | `FogNode.linear(start:end:color:)` `FogNode.exponential(density:color:)` | translucent-shader approximation on iOS |
| `ReflectionProbeNode` | `ReflectionProbeNode(position:radius:)` | zone IBL |

## Node factories — AR (iOS)

| Node | Usage |
| --- | --- |
| `AnchorNode.world(position:)` | anchor at a world coordinate |
| `AnchorNode.plane(alignment:minimumBounds:)` | anchor on a detected plane |
| `AugmentedImageNode` | overlay content on a detected reference image |
| `CloudAnchorNode.host(ttlDays:completion:operation:)` / `.resolve(cloudAnchorId:completion:operation:)` | cross-device persistent anchors — both return a cancellable `CloudAnchorFuture`; call `future.cancel()` from `.onDisappear`. The app supplies the `GARSession` round-trip (Google's `arcore-ios-sdk`) through the `operation` closure |

## Environment presets

`.studio` (default) · `.outdoor` · `.sunset` · `.night` · `.warm` · `.autumn`
· `.custom(name:hdrFile:intensity:)`

`intensity` is a linear multiplier (`1.0` = the HDR's own radiance); SceneView
converts it to RealityKit's power-of-two `intensityExponent`, so never pre-apply
a `log2`. Not interchangeable with Android's IBL intensity, which is Filament
lux (default `10_000`) — #2897.

## Transform & animation

```swift
model.position = SIMD3<Float>(x: 1, y: 0, z: -2)
model.rotation = simd_quatf(angle: .pi / 4, axis: [0, 1, 0])
model.scale    = SIMD3<Float>(repeating: 2.0)

// fluent
model.position(.init(x: 1, y: 0, z: -2)).scale(0.5)

model?.playAllAnimations(loop: true, speed: 1.0)
model?.playAnimation(at: 0, loop: true, speed: 1.5)
model?.stopAllAnimations()
```

## Threading

RealityKit entities are `@MainActor`-isolated. `ModelNode.load` and the
`GeometryNode.*` factories are safe to call from any async context, but
mutating an `Entity` must happen on the main actor — use `await MainActor.run`.

## iOS parity caveats

Some Android APIs are deprecated no-ops or unsupported on iOS — see the
**"iOS parity status (#1036)"** tables in `docs/docs/cheatsheet-ios.md` before
reusing `CameraNode.exposure`, `CameraNode.depthOfField`,
`LightNode.shadowColor`, `ARSceneView(playbackDataset:)`,
`ARSceneView(renderQuality:)` (Android-only; on iOS tune AR via `cameraExposure`,
and use `.renderQuality(_:)` on the 3D `SceneView`), `StreetscapeGeometry`
or terrain/rooftop anchors.

## Android equivalents

Building for Jetpack Compose instead? Use the `sceneview` skill. The full
Android↔Apple mapping table is in `docs/docs/cheatsheet-ios.md`.
