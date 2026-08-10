# flutter_sceneview

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![GitHub](https://img.shields.io/badge/GitHub-sceneview%2Fsceneview-black)](https://github.com/sceneview/sceneview)

Flutter plugin for [SceneView](https://sceneview.github.io) — 3D and AR scenes using native renderers.

| Platform | Renderer                          | Status                          |
|----------|-----------------------------------|---------------------------------|
| Android  | Filament (via Jetpack Compose)    | Alpha — 3D model loading works  |
| iOS      | RealityKit (via SceneViewSwift)   | Alpha — 3D model loading works  |

## Features

- Load and display 3D models (GLB/GLTF) using native renderers
- AR scenes with plane detection on Android (ARCore) and iOS (ARKit)
- HDR environment lighting (Android; iOS support pending — #909)
- Orbit camera controls (touch gestures)
- `SceneViewController` for imperative commands
- Geometry and light node APIs — rendered natively on **Android**; the iOS
  RealityKit port is not yet bridged (#909)

> This plugin exposes a **subset** of the SceneView SDK. See the
> [Controller API](#controller-api) note and issue
> [#909](https://github.com/sceneview/sceneview/issues/909) for the full
> coverage map.

## Installation

> **Naming note:** this package publishes to pub.dev as **`flutter_sceneview`**
> ([#2735](https://github.com/sceneview/sceneview/issues/2735)). Two similar
> pub.dev names are *not* what you want, for different reasons:
> `sceneview` is this project's own pre-rename package, abandoned at 3.6.1 —
> ours, but years stale; `sceneview_flutter` is an unrelated third-party demo
> upload at 0.0.1. Neither receives updates.

In your `pubspec.yaml`:

```yaml
dependencies:
  flutter_sceneview: ^4.24.0
```

This tracks the latest version **published to pub.dev**, which lags the SDK's
`VERSION_NAME` whenever the `pub-publish` job has not shipped the newest
release yet. Do not raise it to match the Android SDK version: a caret range
against an unpublished version resolves to nothing and fails `flutter pub get`.

Or as a Git dependency (note: at tags `v4.24.0` and earlier the package name
was `sceneview_flutter` — the dependency key must match the name at the ref):

```yaml
dependencies:
  flutter_sceneview:
    git:
      url: https://github.com/sceneview/sceneview
      path: flutter/sceneview_flutter
      ref: main
```

Then run:

```sh
flutter pub get
```

### Android setup

Minimum SDK 24. In `android/app/build.gradle`:

```groovy
android {
    defaultConfig {
        minSdkVersion 24
    }
}
```

### iOS setup

Minimum iOS 18 (`SceneViewSwift`'s `Package.swift` requires iOS 18.0). In
`ios/Podfile`:

```ruby
platform :ios, '18.0'
```

The plugin's iOS bridge wraps `SceneViewSwift` (the RealityKit renderer), and
declares it as a **pod** dependency. `SceneViewSwift` is **not published to the
CocoaPods trunk**, so your `Podfile` has to say where it comes from — otherwise
`pod install` fails with `Unable to find a specification for 'SceneViewSwift'`.

Installing the plugin from pub.dev (no clone of the SDK repo):

```ruby
target 'Runner' do
  use_frameworks!
  pod 'SceneViewSwift',
      :podspec => 'https://raw.githubusercontent.com/sceneview/sceneview/main/SceneViewSwift.podspec'
  flutter_install_all_ios_pods File.dirname(File.realpath(__FILE__))
end
```

The `:podspec =>` form is deliberate, and the obvious `:git => …, :tag => 'vX.Y.Z'`
alternative does **not** work yet. CocoaPods resolves `:git` by looking for
`SceneViewSwift.podspec` at the root of the checked-out tag, and the podspec was
added after `v4.26.0` was cut — so a tagged coordinate fails with exactly the
`Unable to find a specification` error this section exists to prevent, until the
first release that carries the file. `:podspec =>` reads the spec from `main`
while the *sources* still come from the tag the spec pins (`:tag => "v#{s.version}"`),
so the build stays reproducible. Once a release ships with the podspec at the
root, `:git => …, :tag => 'vX.Y.Z'` becomes the better pin.

Working from a checkout of the SDK monorepo instead — point at the **repo root**,
which is where `SceneViewSwift.podspec` lives:

```ruby
  pod 'SceneViewSwift', :path => '<path-to>/sceneview'
```

> **Adding the Swift package to your Xcode project does not work**, and this
> README used to tell you to do exactly that. The bridge is itself a pod, so it
> compiles inside the generated `Pods.xcodeproj`, which cannot see a package
> added to `Runner.xcodeproj`; the build fails with
> `Unable to find module dependency: 'SceneViewSwift'`. The pod route is what
> makes `import SceneViewSwift` resolve.

`samples/flutter-demo/ios/Podfile` is a working reference.

> **Pick the tag to match the plugin, not the pub.dev range.** The two versions
> are resolved by different package managers and only the pub.dev one is allowed
> to lag: `ios/Classes/*.swift` compiles against whatever tag *your* app pins.
> The plugin builds on `SceneViewerHostView`, which landed **after** `v4.26.0` —
> no `SceneViewer*` type exists at that tag or earlier — and it calls the
> two-parameter `onTapEntity` (the tapped model root is the second parameter),
> so pin `v4.27.0` or newer.
>
> Nothing in this repository's CI will warn you: `bridge-ios-compile.yml`
> type-checks the plugin against the `SceneViewSwift` sources *in the repo*, not
> against the tag your app resolves. A stale pin therefore surfaces as a Swift
> compile error in your own build — loud and at build time, never a runtime
> surprise, but yours to notice.

> **iOS model format.** RealityKit loads `.usdz` and `.reality` natively. Pass
> `.usdz` model paths to `loadModel(...)` on iOS — a `.glb` path fails to load
> (the failure is logged; the rest of the scene is unaffected).

## Usage

### 3D Scene

```dart
import 'package:flutter_sceneview/flutter_sceneview.dart';

final controller = SceneViewController();

SceneView(
  controller: controller,
  onViewCreated: () {
    controller.setEnvironment('environments/studio_small.hdr');
    controller.loadModel(const ModelNode(modelPath: 'models/damaged_helmet.glb'));
  },
)
```

### AR Scene

```dart
import 'package:flutter_sceneview/flutter_sceneview.dart';

final controller = SceneViewController();

ARSceneView(
  controller: controller,
  planeDetection: true,
  onViewCreated: () {
    controller.loadModel(const ModelNode(modelPath: 'models/andy.glb'));
  },
)
```

### Controller API

| Method                                | Description                                              |
|----------------------------------------|----------------------------------------------------------|
| `loadModel(ModelNode)`                 | Load a glTF/GLB model into the scene                     |
| `clearScene()`                         | Remove all models from the scene                         |
| `setEnvironment(String path)`          | Set HDR environment for image-based lighting (Android; iOS accepts but does not apply it — #909) |
| `addGeometry(GeometryNode)`            | Add a geometry node — rendered on **Android**; iOS port pending (#909) |
| `addLight(LightNode)`                  | Add a light node — rendered on **Android**; iOS port pending (#909) |
| `setCameraControlMode(CameraControlMode)` | Change the camera mode at runtime (v4.3.0)            |
| `setAutoCenterContent(bool)`           | Toggle content auto-centring at runtime (v4.3.0)         |
| `isAttached`                           | Whether the controller is attached to a live view        |

> ⚠️ **Bridge coverage.** This plugin exposes a subset of the native SceneView
> SDK. `addGeometry` / `addLight` render natively on Android only; plane events
> and the HDR environment are forwarded on Android but not yet on iOS. Node taps
> reach `onTap` for `SceneView` (3D) on **Android**, carrying the model file's
> base name without extension; the iOS 3D path is wired end to end but no tap
> has ever been observed to arrive — see "`onTap` does not fire on iOS" below,
> where it is measured. For `ARSceneView` taps stay Android-only,
> since SceneViewSwift's `ARSceneView` exposes no entity hit-test hook
> ([#2051](https://github.com/sceneview/sceneview/issues/2051)).
> Camera positioning, `ViewNode` / `ImageNode` / `VideoNode` / `TextNode`,
> advanced AR anchors and the `sceneview-core` physics/geometry APIs are not
> bridged at all. The full gap is tracked in the
> [#909](https://github.com/sceneview/sceneview/issues/909) umbrella.

### Camera controls & content centring (v4.3.0)

`SceneView` accepts a `cameraControlMode` and `autoCenterContent`:

```dart
SceneView(
  controller: controller,
  cameraControlMode: CameraControlMode.pan, // .orbit | .pan | .firstPerson
  autoCenterContent: false,                 // default true
)
```

`CameraControlMode.pan` and `.firstPerson` are iOS-only in v4.3.0; on Android
they fall back to orbit. `autoCenterContent` is iOS-first — the Android side
is tracked in issue #1051.

### AR recording (v4.3.0 — iOS)

`ARRecorder` records an AR session to a `.mov` video (iOS via ReplayKit):

```dart
final recorder = ARRecorder(arController);

await recorder.startRecording();
// ... later ...
final path = await recorder.stopRecording();
await recorder.saveToPhotoLibrary(path);
```

`ARRecorder` is iOS-only; on Android every method throws an `UnsupportedError`
(ARCore session recording is tracked in issue #1051). The host iOS app must
declare `NSPhotoLibraryAddUsageDescription` in `Info.plist` to use
`saveToPhotoLibrary`.

### ModelNode properties

| Property    | Type     | Default | Description                         |
|-------------|----------|---------|-------------------------------------|
| `modelPath` | `String` | —       | Asset path or URL to GLB/GLTF file  |
| `x`         | `double` | `0.0`   | X position in world space           |
| `y`         | `double` | `0.0`   | Y position in world space           |
| `z`         | `double` | `0.0`   | Z position in world space           |
| `scale`     | `double` | `1.0`   | Uniform scale factor                |

## Architecture

```
Flutter (Dart)
  |
  +-- PlatformView -----> Android: ComposeView + SceneView { }
  |                        (Filament renderer, SceneView SDK)
  |
  +-- PlatformView -----> iOS 3D: SceneViewerHostView + SceneViewSwift
  |                        (RealityKit renderer — the shared host, also used by
  |                         the React Native bridge and sceneview-compose)
  |
  +-- PlatformView -----> iOS AR: UIHostingController + ARSceneView
                           (RealityKit renderer)
```

Method channels bridge Dart commands (`loadModel`, `clearScene`, `setEnvironment`) to native implementations.

## Limitations

- Geometry and light nodes are not yet rendered natively (API exists for forward compatibility)
- AR tap-to-place is not yet implemented
- `onTap` is delivered for `SceneView` (3D) on Android; on iOS the wiring is
  complete but no tap has been observed to arrive (measured — see below).
  `ARSceneView` taps are Android-only
- `onModelLoaded` is not bridged; a model that fails to load is logged natively
  and not reported to Dart. The prefix to grep for differs by path, because the
  two paths report from different places: the 3D viewer logs
  `[SceneViewSwift] SceneViewerHostView failed to load model '<path>': <error>`,
  while AR logs `[flutter_sceneview] Cannot load AR model '<path>': <reason>`
  for a format RealityKit cannot parse, and
  `[flutter_sceneview] Failed to load AR model '<path>': <error>` for anything else
- Only Android and iOS are supported; other platforms show a fallback message

### `onTap` does not fire on iOS (known gap, measured 2026-08-07)

`onTap` works on Android. On iOS the callback is wired end to end — the platform
view now claims tap gestures, the model loads and renders, and its entity
carries both a `CollisionComponent` and an `InputTargetComponent` — but
RealityKit's entity-targeted hit test never resolves an entity, so the handler
is never called. Verified on an iPhone 17 Pro Max simulator (iOS 26.3):

- a plain `SpatialTapGesture` on the same view **does** fire, at the correct
  location inside the viewport, so the touch reaches SwiftUI;
- the same tap through `.targetedToAnyEntity()` fires for no entity, whether the
  collision shape is the generated one or an explicit bounding box.

**A missing component is not the explanation — that was ruled out by
measurement, not by reading.** #3027 landed `Entity.makeInputTargetable()` and
applies it to the *whole* `contentRoot` in `buildContent`, so every entity in
the scene carries an `InputTargetComponent` regardless of collision. Re-measured
against that code on the same simulator: four taps at three positions on a
rendered, orbitable model produced no callback. A drag in the same session
orbited the camera, so the touches were reaching the native view throughout.

**`SceneViewerHostView` is not the fix either.** #3035 moved this bridge onto
that shared host — an `@objc UIView` built for UIKit embedding, which was the
most promising remaining lead. Re-measured on the same simulator after the move,
with the fox rendering and the camera orbiting: three taps on the model, no
callback. So the gap survives a change of host, which is evidence against the
host being what breaks it.

**The package itself picks correctly.** Measured on the same simulator, same
session: `samples/ios-demo`'s `Collision & Hit Test` demo — the same
`SceneView`, the same `.targetedToAnyEntity()` — highlights the shape that was
tapped. So `targetedToAnyEntity()` resolving nothing is specific to how this
bridge presents its scene, not a property of the modifier.

**Asynchronous loading is not the difference either.** Same session, same
simulator: `samples/ios-demo`'s Model Viewer, temporarily given an
`onEntityTapped`, reported `car_019_0` on the first tap — a `.usdz` loaded
through the same `ModelNode.load`, well after the first frame. So neither the
loader nor `.usdz` content is what breaks the bridge.

Three differences remain, and no measurement so far has varied only one:

1. **Host** — a Flutter platform view versus plain SwiftUI.
2. **How the entity reaches the scene** — the native demo re-runs `SceneView`'s
   `content` closure through `.contentID(loadCount)`, so `buildContent` runs
   again and `makeInputTargetable()` sweeps the tree with the model in it.
   `SceneViewerHostView` hands `SceneView` an empty `SceneViewerContentRoot` once
   and never re-keys it, so that sweep only ever sees an empty root; the attached
   models depend entirely on the components `ModelNode.load` set on them.
3. **Scale** — the native hero is normalised with `scaleToUnits(0.6)`; the fox
   reaches the bridge at its authored 155 units.

Whichever of these it is, note that (2) is a property of the shared host, not of
Flutter — so the React Native bridge, which uses the same host, is likely to have
the same gap.

Do not describe `onTap` as working on iOS until a tap has been seen to reach
Dart. Tracked in
[#3045](https://github.com/sceneview/sceneview/issues/3045).

## Contributing

See [CONTRIBUTING.md](https://github.com/sceneview/sceneview/blob/main/.github/CONTRIBUTING.md).

## License

Apache-2.0 — see [LICENSE](LICENSE) for details.
