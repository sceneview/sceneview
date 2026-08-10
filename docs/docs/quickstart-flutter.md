# Flutter Quickstart

SceneView provides a Flutter plugin that bridges to native SceneView rendering on both Android (Filament) and iOS (RealityKit).

## Install

> **Note:** the plugin is published on pub.dev as
> [**`flutter_sceneview`**](https://pub.dev/packages/flutter_sceneview).
> The pub.dev packages named `sceneview` and `sceneview_flutter` are
> unrelated third-party uploads — do not use them.

```yaml
# pubspec.yaml
dependencies:
  flutter_sceneview: ^4.24.0
```

### iOS setup — one required Podfile line

Android needs nothing beyond `minSdkVersion 24`. iOS needs two edits, and
skipping the second one fails the build rather than degrading quietly.

`SceneViewSwift` — the RealityKit renderer the bridge wraps — is **not published
to the CocoaPods trunk**, so your `Podfile` must say where it comes from.
Without that line, `pod install` stops with
`Unable to find a specification for 'SceneViewSwift'`.

```ruby
# ios/Podfile
platform :ios, '18.0'   # SceneViewSwift's Package.swift requires iOS 18.0

target 'Runner' do
  use_frameworks!
  pod 'SceneViewSwift',
      :podspec => 'https://raw.githubusercontent.com/sceneview/sceneview/main/SceneViewSwift.podspec'
  flutter_install_all_ios_pods File.dirname(File.realpath(__FILE__))
end
```

Adding `SceneViewSwift` as a **Swift package** in Xcode does not work: the
bridge is itself a pod and compiles inside the generated `Pods.xcodeproj`, which
cannot see a package added to `Runner.xcodeproj`. See the
[plugin README](https://github.com/sceneview/sceneview/blob/main/flutter/sceneview_flutter/README.md#ios-setup)
for the full rationale and for the `:path =>` form used when working from a
clone of the monorepo. `samples/flutter-demo/ios/Podfile` is a working reference.

## Usage

### 3D Scene

```dart
import 'package:flutter_sceneview/flutter_sceneview.dart';

class MyModelViewer extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return SceneView(
      onSceneCreated: (controller) {
        controller.loadModel(ModelNode(
          modelPath: 'models/damaged_helmet.glb',
          scale: 1.0,
        ));
        controller.setEnvironment('environments/sky_2k.hdr');
      },
    );
  }
}
```

### AR Scene

```dart
ARSceneView(
  onSceneCreated: (controller) {
    controller.loadModel(ModelNode(
      modelPath: 'models/chair.glb',
      scale: 0.5,
    ));
  },
);
```

## How It Works

```
Flutter (Dart)
  └── PlatformView
        ├── Android → ComposeView → SceneView { ModelNode(...) }
        └── iOS → SceneViewerHostView → SceneView { ModelNode(...) }
```

- **Android**: Uses `ComposeView` hosting the Jetpack Compose `SceneView { }` composable with Filament renderer
- **iOS**: Uses `SceneViewerHostView` — the shared `@objc UIView` host in `SceneViewSwift`,
  also used by the React Native bridge and `sceneview-compose` — with RealityKit renderer.
  The AR path keeps its own platform view

## Available Methods

| Method | Description |
|---|---|
| `loadModel(ModelNode)` | Load a glTF/GLB (Android) or USDZ (iOS) model |
| `clearScene()` | Remove all models from the scene |
| `setEnvironment(hdrPath)` | Set HDR environment lighting |

## Limitations

- AR requires platform-specific permissions (camera)
- Model format differs: glTF/GLB on Android, USDZ on iOS
- Gesture handling is delegated to the native layer
