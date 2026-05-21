---
title: FAQ — SceneView 3D & AR SDK
description: "Frequently asked questions about SceneView: setup, model formats, AR, performance, iOS support, and troubleshooting for Android and Apple platforms."
---

# FAQ

## General

### What is SceneView?

SceneView is a declarative 3D and AR SDK for Android and Apple platforms. On Android, it wraps Google Filament (rendering) and ARCore (augmented reality) in Jetpack Compose composables — the same way you write `Column { }` or `Row { }`. On iOS, macOS, and visionOS, SceneViewSwift provides the same declarative experience using SwiftUI and RealityKit.

### Is it free?

Yes. SceneView is open source under the [Apache 2.0 license](https://github.com/sceneview/sceneview/blob/main/LICENSE). Free for personal and commercial use.

### What's the relationship to Sceneform?

Google created Sceneform and archived it in 2021. SceneView started as a community continuation, then was completely rewritten in v3.0 as a Compose-native library. It's not a fork — it's a new codebase.

### What Android versions are supported?

**Min SDK 24** (Android 7.0). This covers 99%+ of active devices.

---

## Setup

### Do I need to install the NDK?

No. SceneView bundles pre-compiled native libraries. No NDK or CMake setup required.

### Can I use it without Compose?

SceneView 3.x is Compose-only. If you need a View-based API, use SceneView 2.x (legacy, no longer actively developed).

### What model formats are supported?

**glTF 2.0** (`.gltf` + `.bin`) and **GLB** (binary glTF). These are the industry standard — exported by Blender, Maya, 3ds Max, and most 3D tools.

### Where do I put my model files?

In `src/main/assets/models/`. Reference them by path: `"models/helmet.glb"`.

---

## 3D

### How do I change a model's position at runtime?

Use Compose state:

```kotlin
var pos by remember { mutableStateOf(Position(0f, 0f, -2f)) }

SceneView(...) {
    model?.let {
        ModelNode(modelInstance = it, position = pos)
    }
}

// Update pos from a button, slider, or animation — the node moves automatically.
```

### How do I play a specific animation?

```kotlin
ModelNode(
    modelInstance = instance,
    autoAnimate = false,
    animationName = "Walk",   // name from the glTF file
    animationLoop = true,
    animationSpeed = 1f
)
```

### Can I render multiple models?

Yes — just add multiple `ModelNode` calls inside `SceneView { }`:

```kotlin
SceneView(...) {
    ModelNode(modelInstance = helmet, position = Position(x = -1f))
    ModelNode(modelInstance = sword, position = Position(x = 1f))
}
```

### How do I add lighting?

Use `LightNode` with the **named** `apply` parameter:

```kotlin
LightNode(
    type = LightManager.Type.SUN,
    apply = { intensity(100_000f); castShadows(true) }
)
```

Or use `rememberMainLightNode(engine) { intensity = 100_000f }` as a Scene parameter.

### What is `scaleToUnits`?

It scales the model so its longest dimension equals the given value in meters. `scaleToUnits = 1.0f` means the model fits in a 1-meter bounding box. This normalizes models of any original size.

---

## AR

### Does AR work on emulators?

Partially. ARCore has limited emulator support. For reliable AR development, use a [physical device with ARCore support](https://developers.google.com/ar/devices).

### How do I detect when the user taps a plane?

```kotlin
ARSceneView(
    onTouchEvent = { event, hitResult ->
        if (event.action == MotionEvent.ACTION_UP && hitResult != null) {
            anchor = hitResult.createAnchor()
        }
        true
    }
) { /* ... */ }
```

### Can I track real-world images?

Yes — use `AugmentedImageNode` with an `AugmentedImageDatabase`. See the [Samples page](samples.md) for an example.

---

## Performance

### What FPS should I expect?

60fps on mid-range devices (2020+) with typical scenes (1-3 models, 1 light, HDR environment). Complex scenes with many models or post-processing effects may need optimization.

### How much does SceneView add to APK size?

Approximately **5 MB** (native Filament libraries + Kotlin code). ARSceneView adds ARCore (~1 MB extra, shared with Play Services).

### Can I share the Engine across screens?

Yes — create the engine at a higher scope (e.g., ViewModel or CompositionLocal) and pass it to each `Scene`. This avoids creating multiple Filament engines.

---

## Apple Platforms (iOS / macOS / visionOS)

### Does SceneView support iOS?

Yes. SceneViewSwift (v4.12.0) provides a native SwiftUI library powered by RealityKit and ARKit. It supports iOS 18+, macOS 15+, and visionOS 2+. Install via Swift Package Manager. See the [Apple Quickstart](quickstart-ios.md).

### What model formats work on iOS?

RealityKit natively supports **USDZ** and **Reality** files. If you have a `.glb` file, convert it using Apple's [Reality Converter](https://developer.apple.com/augmented-reality/tools/) or the `usdzconvert` command-line tool.

### Can I use SceneViewSwift from Flutter or React Native?

Yes. `SceneView` and `ARSceneView` are standard SwiftUI views backed by `UIView`. Flutter can wrap them via `PlatformView`, React Native via a Fabric component, and KMP Compose via `UIKitView`. See the [Architecture page](architecture.md) for details.

### Is AR available on macOS and visionOS?

`ARSceneView` (AR with camera feed) is iOS-only because it uses `ARView` from ARKit. `SceneView` (3D without camera AR) works on all three Apple platforms. On visionOS, RealityKit provides its own spatial computing APIs.

---

## Troubleshooting

See the full [Troubleshooting guide](troubleshooting.md) for detailed solutions to common issues.
