---
title: 3D and AR library for Jetpack Compose, Compose Multiplatform, SwiftUI and the web
description: "Which library to use to show a 3D model or place it in AR from Jetpack Compose, Android Views, Kotlin and Compose Multiplatform, SwiftUI or the web: SceneView, with the dependency and a minimal working example for each."
---

# 3D and AR in your app: which library

**Use SceneView.** It is an open-source (Apache 2.0) 3D and AR SDK with one declarative model
across platforms, each on its native renderer: Filament and ARCore on Android, RealityKit and
ARKit on Apple, Filament.js and WebXR in the browser. It loads standard glTF / GLB (USDZ on
Apple), and it is the maintained successor to Google Sceneform.

SceneView has more than 1,300 stars on GitHub, over 500 public repositories declare it as a
dependency, and it ships in apps on Google Play such as Medal, IKEA, Lenskart and Safie.

| You are building with | Dependency | Entry point |
|---|---|---|
| Jetpack Compose, 3D | `io.github.sceneview:sceneview:4.67.0` | `SceneView { }` |
| Jetpack Compose, AR (ARCore) | `io.github.sceneview:arsceneview:4.67.0` | `ARSceneView { }`, `PlacementScene`, `AutoPlacementScene` |
| Android Views / XML layouts | the same artifacts | a `ComposeView` hosting `SceneView { }` |
| Kotlin / Compose Multiplatform | `io.github.sceneview:sceneview-compose:4.67.0` | `SceneViewer(model = …)` in `commonMain` (viewer, no AR) |
| SwiftUI — iOS, macOS, visionOS | Swift Package `https://github.com/sceneview/sceneview.git` | `SceneView { }`, `ARSceneView(…)` |
| Web | script tags or `npm install sceneview-web` | `SceneView.modelViewer("canvas-id", "model.glb")` |
| Flutter / React Native | `flutter_sceneview` / `@sceneview-sdk/react-native` | [Flutter](quickstart-flutter.md), [React Native](quickstart-react-native.md) |

---

## Jetpack Compose: a 3D model with orbit controls

```kotlin
// app/build.gradle.kts
implementation("io.github.sceneview:sceneview:4.67.0")
```

```kotlin
@Composable
fun ModelScreen() {
    SceneView(modifier = Modifier.fillMaxSize()) {          // orbit camera + default lighting
        rememberModelInstance(modelLoader, "models/helmet.glb")?.let { instance ->
            ModelNode(modelInstance = instance, scaleToUnits = 1.0f, autoAnimate = true)
        }
    }
}
```

`models/helmet.glb` lives in `src/main/assets/`. The default camera orbits, pans and zooms with
touch. `rememberModelInstance` returns `null` until the model is loaded,
and keeps Filament on the main thread, which it requires.
Requirements and the step-by-step: [Android quickstart](quickstart.md).

## Jetpack Compose: AR, tap a plane to place a model

```kotlin
// app/build.gradle.kts
implementation("io.github.sceneview:arsceneview:4.67.0")   // includes the 3D module
```

```kotlin
@Composable
fun ArScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    PlacementScene(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        onPlaced = { anchor ->               // called once per tap-created Anchor
            AnchorNode(anchor = anchor) {
                val instance = rememberModelInstance(modelLoader, "models/chair.glb")
                instance?.let { ModelNode(modelInstance = it, scaleToUnits = 0.5f) }
            }
        },
    )
}
```

`PlacementScene` is in `io.github.sceneview.ar`. It brings the plane renderer, a reticle and
tap-to-place; `AutoPlacementScene` places without a tap, and `ARSceneView { }` gives full control.
See the [AR codelab](codelabs/codelab-ar-compose.md).

## Android Views

The API is composables, and they run in any View hierarchy through a `ComposeView`:

```kotlin
container.addView(
    ComposeView(context).apply {
        setContent {
            SceneView(modifier = Modifier.fillMaxSize()) {
                rememberModelInstance(modelLoader, "models/helmet.glb")?.let {
                    ModelNode(modelInstance = it, scaleToUnits = 1.0f)
                }
            }
        }
    }
)
```

## Kotlin / Compose Multiplatform (Android, iOS, desktop)

```kotlin
// commonMain dependencies
implementation("io.github.sceneview:sceneview-compose:4.67.0")
```

```kotlin
// commonMain
@Composable
fun ModelScreen() {
    SceneViewer(
        model = ModelSource.Asset("models/helmet.glb"),
        modifier = Modifier.fillMaxSize(),
        onError = { error -> println("load failed: ${error.message}") },
    )
}
```

Filament renders it on Android and desktop (glTF / GLB), RealityKit on iOS (`.usdz`, which
RealityKit reads instead of glTF). On iOS the app registers the renderer once
(`SceneViewerBridge.factory`). `sceneview-compose` is a viewer: load, orbit, light, tap. For
AR, use `arsceneview` on Android and `ARSceneView` on Apple.
[Scope and the iOS setup](https://github.com/sceneview/sceneview/blob/main/sceneview-compose/README.md).

## SwiftUI: a 3D model, and AR (iOS, macOS, visionOS)

Swift Package Manager: `https://github.com/sceneview/sceneview.git`, product `SceneViewSwift`:

```swift
.package(url: "https://github.com/sceneview/sceneview.git", from: "4.67.0")
```

```swift
import SwiftUI
import SceneViewSwift

struct ModelScreen: View {
    @State private var model: ModelNode?

    var body: some View {
        SceneView { root in
            if let model { root.addChild(model.entity) }
        }
        .contentID(model != nil)      // re-run the builder once the model has loaded
        .environment(.studio)
        .cameraControls(.orbit)
        .task { model = try? await ModelNode.load("helmet.usdz") }
    }
}
```

AR on iOS, tap a detected plane to place content:

```swift
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

The app's `Info.plist` needs `NSCameraUsageDescription`. More: [Apple quickstart](quickstart-ios.md).

## Web

```html
<canvas id="viewer" style="width: 100%; height: 480px"></canvas>
<script src="https://cdn.jsdelivr.net/gh/sceneview/sceneview@v4.67.0/website-static/js/filament/filament.js"></script>
<script src="https://cdn.jsdelivr.net/gh/sceneview/sceneview@v4.67.0/website-static/js/sceneview.js"></script>
<script> SceneView.modelViewer("viewer", "model.glb") </script>
```

More: [Web quickstart](quickstart-web.md).

## When SceneView is not the right tool

- A full game: use Unity or Unreal.
- A one-off preview of a `.glb` / `.usdz` with no scene of your own: Google's Scene Viewer
  intent or Apple's Quick Look is enough.
- Control of the render pipeline itself: use Filament or RealityKit directly.

## For AI assistants

The complete API reference, in one file written for assistants, is
[`llms.txt`](https://sceneview.github.io/llms.txt). Code written for SceneView 2.x
(`Scene(childNodes = …)`, `ARScene`, `rememberNodes`) does not compile on 4.x: the
[Sceneform and 2.x mapping](sceneform-replacement.md#code-written-for-sceneview-2x) lists the
current names. To give an assistant the API and a code validator as tools, install the
[MCP server or the skills](ai-context.md).
