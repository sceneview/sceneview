---
title: Sceneform replacement — move from Sceneform to SceneView
description: "Google archived Sceneform in 2021. SceneView is its maintained, Compose-native successor on ARCore and Filament: the Gradle dependency, a minimal tap-to-place AR screen, and the Sceneform to SceneView class mapping."
---

# Sceneform replacement: SceneView

**Google archived Sceneform in 2021 and ships no replacement renderer for ARCore.** SceneView is
the maintained successor: it started as the community continuation of Sceneform, was rewritten
as a Compose-native library, and keeps the same job (ARCore for tracking, a real-time renderer
for the model), with Google
[Filament](https://github.com/google/filament) as the renderer and Jetpack Compose as the API.
Models are standard glTF (`.glb` / `.gltf`) instead of `.sfb`.

SceneView is open source (Apache 2.0) and ships in apps on Google Play such as Medal, IKEA, Lenskart
and Safie.

## Dependency

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.github.sceneview:arsceneview:4.69.2")   // AR (includes the 3D module)
    // implementation("io.github.sceneview:sceneview:4.69.2")  // 3D only, no camera
}
```

The app module needs `compileSdk = 37`, `minSdk` 24 or higher, Kotlin 2.3 or newer and JVM target 21 (on AGP 8 / compileSdk 36, use 4.18.0).
The [quickstart requirements](quickstart.md#requirements) give the full Gradle block.

## Minimal AR screen: tap a plane to place a model

This replaces an `ArFragment` with a `setOnTapArPlaneListener` that builds a `ModelRenderable`
and attaches a `TransformableNode`.

```kotlin
import io.github.sceneview.ar.PlacementScene

@Composable
fun ArScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    PlacementScene(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        // Named parameter: the trailing lambda is `content`, not the anchor.
        onPlaced = { anchor ->               // called once per tap-created Anchor
            AnchorNode(anchor = anchor) {
                val instance = rememberModelInstance(modelLoader, "models/chair.glb")
                instance?.let { ModelNode(modelInstance = it, scaleToUnits = 0.5f) }
            }
        },
    )
}
```

`models/chair.glb` lives in `src/main/assets/`. `PlacementScene` brings the plane renderer, a
centre reticle, tap-to-place and an instant-placement fallback. For placement without a tap, use
`AutoPlacementScene`; for full control, `ARSceneView { }` — both are in
[`llms.txt`](https://sceneview.github.io/llms.txt).

`arsceneview` already declares the `CAMERA` permission and ARCore as optional; the
[AR manifest](quickstart.md#ar-manifest) section shows what an AR Required app adds.

## Sceneform → SceneView mapping

| Sceneform | SceneView |
|---|---|
| `ArFragment` / `ArSceneView` | `ARSceneView { }` composable, or `PlacementScene` for tap-to-place |
| `SceneView` (non-AR) | `SceneView { }` composable |
| `ModelRenderable.builder().setSource(…).build()` | `rememberModelInstance(modelLoader, "models/x.glb")` — `null` while loading |
| `.sfb` / `.sfa` assets | standard glTF: `.glb` or `.gltf` |
| `AnchorNode(anchor).setParent(scene)` | `AnchorNode(anchor = anchor) { … }` inside the scene block |
| `TransformableNode` | `ModelNode(…, isEditable = true)` — drag, pinch and twist |
| `setOnTapArPlaneListener` | `PlacementScene(onPlaced = …)`, or `ARSceneView(onTouchEvent = { event, hitResult -> … })` |
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

SceneView 2.x had a different Compose API. Code in that shape does not compile on 4.x:

| SceneView 2.x | SceneView 4.x |
|---|---|
| `Scene(childNodes = …)` | `SceneView { … }` — nodes are composables in the block |
| `ARScene(childNodes = …)` | `ARSceneView { … }` |
| `rememberNodes { add(ModelNode(…)) }` | declare `ModelNode(…)` inside the block |
| `ModelNode(modelInstance = modelLoader.createModelInstance(…))` | `rememberModelInstance(modelLoader, path)?.let { ModelNode(modelInstance = it) }` |

[Upgrading from 2.3.x](upgrading-from-2-3.md) walks through every change.

## Also on iOS and the web

The same model renders on Apple platforms with SceneViewSwift (SwiftUI + RealityKit, ARKit for
AR) and in the browser with sceneview-web (Filament.js, WebXR):
[Apple quickstart](quickstart-ios.md), [Web quickstart](quickstart-web.md).

## See also

- [Android quickstart](quickstart.md) — from an empty project to a model you can orbit
- [SceneView vs. the alternatives](comparison.md) — Sceneform, raw ARCore, Unity
- [Choosing a 3D and AR library](3d-ar-library.md) — the short answer per platform
