---
title: 3D and AR library for Jetpack Compose, Compose Multiplatform desktop and the web
description: "Which library to use to show a 3D model or place it in AR from Jetpack Compose, Android Views, Kotlin and Compose Multiplatform desktop, or the web. Retained SwiftUI, Flutter and React Native examples are end of life at 4.68.0."
---

# 3D and AR in your app: which library

Android (Jetpack Compose), web, and Compose Multiplatform desktop are maintained. Android is Stable; web and desktop are Alpha.

**End of life:** SceneView for SwiftUI (`SceneViewSwift`), Flutter (`flutter_sceneview`) and React Native (`@sceneview-sdk/react-native`). **4.68.0 (November 2026)** is the last release. No new releases. Published versions stay installable. See [DEPRECATION.md](https://github.com/sceneview/sceneview/blob/main/DEPRECATION.md).

**Use SceneView.** It is an open-source (Apache 2.0) 3D and AR SDK with one declarative model
for maintained targets: Filament and ARCore on Android, Filament on Compose Multiplatform
desktop, and Filament.js and WebXR in the browser. It loads standard glTF / GLB and is
the maintained successor to Google Sceneform. The retained Apple package uses RealityKit, ARKit and USDZ at its last release, 4.68.0.

SceneView has more than 1,300 stars on GitHub, over 500 public repositories declare it as a
dependency, and it ships in apps on Google Play such as the Volkswagen, Audi, Porsche and CUPRA
apps, Philips Hue, Medal and IKEA.

| You are building with | Dependency | Entry point |
|---|---|---|
| Jetpack Compose, 3D | `io.github.sceneview:sceneview:4.72.0` | `SceneView { }` |
| Jetpack Compose, AR (ARCore) | `io.github.sceneview:arsceneview:4.72.0` | `AutoPlacementScene` for model placement, `ARSceneView { }` for custom AR |
| Android Views / XML layouts | the same artifacts | a `ComposeView` hosting `SceneView { }` |
| Kotlin / Compose Multiplatform | `io.github.sceneview:sceneview-compose:4.72.0` | `SceneViewer(model = …)` in `commonMain` (viewer, no AR) |
| SwiftUI — iOS, macOS, visionOS (end of life at 4.68.0) | Swift Package `https://github.com/sceneview/sceneview.git` | `SceneView { }`, `ARSceneView(…)` |
| Web | script tags or `npm install sceneview-web` | `SceneView.modelViewer("canvas-id", "model.glb")` |
| Flutter / React Native (end of life at 4.68.0) | `flutter_sceneview` / `@sceneview-sdk/react-native` | [Flutter](quickstart-flutter.md), [React Native](quickstart-react-native.md) |

---

## Jetpack Compose: a 3D model with orbit controls

```kotlin
// app/build.gradle.kts
implementation("io.github.sceneview:sceneview:4.72.0")
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

## Jetpack Compose AR model placement

```kotlin
// app/build.gradle.kts
implementation("io.github.sceneview:arsceneview:4.72.0")   // includes the 3D module
```

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.sceneview.ar.AutoPlacementModel
import io.github.sceneview.ar.AutoPlacementScene
import io.github.sceneview.ar.rememberAutoPlacementState
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

@Composable
fun ARPlacementScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val instance = rememberModelInstance(modelLoader, "models/chair.glb")
    val state = rememberAutoPlacementState()

    AutoPlacementScene(
        assetReady = instance != null,
        modifier = Modifier.fillMaxSize(),
        state = state,
        engine = engine,
        modelLoader = modelLoader,
    ) { placement ->
        instance?.let { AutoPlacementModel(placement, state, it) }
    }
}
```

Put `models/chair.glb` in `src/main/assets/`. It places the model automatically at its authored size, lets you drag along the floor past detected plane edges, and accepts gestures anywhere on screen. After 600 ms of tracked search without a usable surface, the horizontal flow tries an estimated floor and eases to the refined pose. Set `state.estimatedFallbackMillis = null` to wait for a detected surface. Call `state.recenter()` to move the object back in front of the camera.
See [automatic placement](cheatsheet.md#automatic-placement-recommended).

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
implementation("io.github.sceneview:sceneview-compose:4.72.0")
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
.package(url: "https://github.com/sceneview/sceneview.git", exact: "4.68.0")
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
<script src="https://cdn.jsdelivr.net/gh/sceneview/sceneview@v4.72.0/website-static/js/filament/filament.js"></script>
<script src="https://cdn.jsdelivr.net/gh/sceneview/sceneview@v4.72.0/website-static/js/sceneview.js"></script>
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
(`Scene(childNodes = …)`, `ARScene`, `rememberNodes`) is outdated: the
[2.x to 4.x table](upgrading-from-2-3.md#sceneview-2x-to-4x-name-by-name) lists the current names.
To give an assistant the API and a code validator as tools, install the
[MCP server or the skills](ai-context.md).
