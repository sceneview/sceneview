# Flutter Quickstart

SceneView provides a Flutter plugin that bridges to native SceneView rendering on both Android (Filament) and iOS (RealityKit).

## Install

> **Note:** the plugin publishes to pub.dev as **`flutter_sceneview`**
> (first publish in progress — [#2735](https://github.com/sceneview/sceneview/issues/2735)).
> The pub.dev packages named `sceneview` and `sceneview_flutter` are
> unrelated third-party uploads — do not use them. Until the first
> `flutter_sceneview` publish lands, use the Git dependency below (the
> dependency key stays `sceneview_flutter` because that was the package
> name at tag v4.4.0).

```yaml
# pubspec.yaml
dependencies:
  sceneview_flutter:
    git:
      url: https://github.com/sceneview/sceneview
      path: flutter/sceneview_flutter
      ref: v4.4.0
```

## Usage

### 3D Scene

```dart
import 'package:sceneview_flutter/sceneview_flutter.dart';

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
        └── iOS → UIHostingController → SceneView { ModelNode(...) }
```

- **Android**: Uses `ComposeView` hosting the Jetpack Compose `SceneView { }` composable with Filament renderer
- **iOS**: Uses `UIHostingController` hosting the SwiftUI `SceneView { }` with RealityKit renderer

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
