---
title: Sceneform replacement — move from Sceneform to SceneView
description: "SceneView is the Compose-native successor to Sceneform on ARCore and Filament. See the Gradle dependency, a minimal automatic AR placement screen, and the Sceneform to SceneView class mapping."
---

# Sceneform replacement: SceneView

**Google archived Sceneform in 2021 and ships no replacement renderer for ARCore.** SceneView is
the maintained successor: it started as the community continuation of Sceneform, was rewritten
as a Compose-native library, and keeps the same job (ARCore for tracking, a real-time renderer
for the model), with Google
[Filament](https://github.com/google/filament) as the renderer and Jetpack Compose as the API.
Models are standard glTF (`.glb` / `.gltf`) instead of `.sfb`.

SceneView is open source (Apache 2.0) and ships in apps on Google Play such as the
Volkswagen, Audi, Porsche and CUPRA apps, Philips Hue, Medal and IKEA. Lenskart ships
[Sceneform Maintained](https://github.com/sceneview/sceneform-android), SceneView's predecessor.

## Dependency

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.github.sceneview:arsceneview:4.72.0")   // AR (includes the 3D module)
    // implementation("io.github.sceneview:sceneview:4.72.0")  // 3D only, no camera
}
```

The app module needs `compileSdk = 37`, `minSdk` 24 or higher, Kotlin 2.3 or newer and JVM target 21 (on AGP 8 / compileSdk 36, use 4.18.0).
The [quickstart requirements](quickstart.md#requirements) give the full Gradle block.

## Minimal AR model placement screen

This replaces an `ArFragment` that uses `setOnTapArPlaneListener`, `ModelRenderable` and
`TransformableNode`.

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

`arsceneview` already declares the `CAMERA` permission and ARCore as optional; the
[AR manifest](quickstart.md#ar-manifest) section shows what an AR Required app adds.

## Sceneform → SceneView mapping

| Sceneform | SceneView |
|---|---|
| `ArFragment` / `ArSceneView` | `AutoPlacementScene` for model placement, or `ARSceneView { }` for custom AR |
| `SceneView` (non-AR) | `SceneView { }` composable |
| `ModelRenderable.builder().setSource(…).build()` | `rememberModelInstance(modelLoader, "models/x.glb")` — `null` while loading |
| `.sfb` / `.sfa` assets | standard glTF: `.glb` or `.gltf` |
| `AnchorNode(anchor).setParent(scene)` | `AnchorNode(anchor = anchor) { … }` inside the scene block |
| `TransformableNode` | `ModelNode(…, isEditable = true)` — drag, pinch and twist |
| `setOnTapArPlaneListener` | `AutoPlacementScene` places the model when the asset and camera tracking are ready |
| `node.setParent(null)` / `destroy()` | remove the node from composition (an `if`, a list) |
| `onResume` / `onPause` / `onDestroy` forwarding | nothing to write — the Compose lifecycle handles it |

## Still on Android Views?

SceneView's API is composables, and a composable runs inside any View hierarchy through a
`ComposeView`, so a Fragment or an XML layout does not need to move to Compose first:

```kotlin
container.addView(
    ComposeView(context).apply {
        setContent {
            val engine = rememberEngine()
            val modelLoader = rememberModelLoader(engine)
            val helmet = rememberModelInstance(modelLoader, "models/helmet.glb")
            SceneView(engine = engine, modelLoader = modelLoader) {
                helmet?.let { ModelNode(modelInstance = it, scaleToUnits = 1.0f) }
            }
        }
    }
)
```

## Code written for SceneView 2.x

SceneView 2.x had a different Compose API. 2.x code may still compile, with deprecation warnings, but always write the 4.x form:

| SceneView 2.x | SceneView 4.x |
|---|---|
| `Scene(childNodes = …)` | `SceneView { … }` — nodes are composables in the block |
| `ARScene(childNodes = …)` | `ARSceneView { … }` |
| `rememberNodes { add(ModelNode(…)) }` | declare `ModelNode(…)` inside the block |
| `ModelNode(modelInstance = modelLoader.createModelInstance(…))` | `rememberModelInstance(modelLoader, path)?.let { ModelNode(modelInstance = it) }` |

[Upgrading from 2.3.x](upgrading-from-2-3.md) walks through every change.

## Also on iOS and the web

SceneView Web renders the same model in the browser with Filament.js and WebXR.
The retained SceneViewSwift package renders on Apple platforms with SwiftUI, RealityKit
and ARKit, but is end of life at 4.68.0; see [DEPRECATION.md](https://github.com/sceneview/sceneview/blob/main/DEPRECATION.md):
[Apple quickstart](quickstart-ios.md), [Web quickstart](quickstart-web.md).

## See also

- [Android quickstart](quickstart.md) — from an empty project to a model you can orbit
- [SceneView vs. the alternatives](comparison.md) — Sceneform, raw ARCore, Unity
- [Choosing a 3D and AR library](3d-ar-library.md) — the short answer per platform
