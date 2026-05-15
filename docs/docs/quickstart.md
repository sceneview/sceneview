---
title: Android Quickstart — SceneView 3D & AR SDK
description: "Set up SceneView in your Android project in 10 minutes. Add a 3D model viewer with orbit camera, HDR lighting, and gesture controls using Jetpack Compose."
---

# Quickstart

**Time:** ~10 minutes |
**Goal:** Go from an empty Android Studio project to a 3D model you can orbit with touch gestures.

---

## Prerequisites

- **Android Studio Ladybug** (2024.2.1) or newer
- An Android device or emulator running **API 24+**
- Basic familiarity with Kotlin and Jetpack Compose

---

## Step 1: Create a new project

1. Open Android Studio and select **New Project**.
2. Choose the **Empty Activity** template (the one that generates a `ComponentActivity` with `setContent`).
3. Set the minimum SDK to **API 24**.
4. Finish the wizard and let Gradle sync.

You should have a working Compose app that displays "Hello Android" or similar.

---

## Step 2: Add the dependency

Open your **app-level** `build.gradle.kts` and add SceneView:

```kotlin
dependencies {
    implementation("io.github.sceneview:sceneview:4.3.5")
}
```

Sync Gradle.

---

## Step 3: Add a 3D model

You need a glTF/GLB file in the assets folder. The **Damaged Helmet** from Khronos is a good first model.

1. Create the directory `app/src/main/assets/models/`.
2. Download the model:
    - [DamagedHelmet.glb](https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/DamagedHelmet/glTF-Binary/DamagedHelmet.glb)
3. Save it as `app/src/main/assets/models/damaged_helmet.glb`.

!!! tip
    Any `.glb` or `.gltf` file works. If you have your own model, drop it in the same folder and update the path in the next step.

---

## Step 4: Write the SceneView composable

Replace the contents of `MainActivity.kt` with the following:

```kotlin
package com.example.my3dapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.sceneview.SceneView
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val engine = rememberEngine()
            val modelLoader = rememberModelLoader(engine)
            val environmentLoader = rememberEnvironmentLoader(engine)

            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                cameraManipulator = rememberCameraManipulator(),
            ) {
                rememberModelInstance(modelLoader, "models/damaged_helmet.glb")
                    ?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = 1.0f,
                            autoAnimate = true,
                        )
                    }
            }
        }
    }
}
```

That is the entire app. Here is what each piece does:

| Call | Purpose |
|---|---|
| `rememberEngine()` | Creates the Filament rendering engine (one per screen) |
| `rememberModelLoader(engine)` | Loads glTF/GLB models from assets |
| `rememberEnvironmentLoader(engine)` | Loads HDR environment maps for lighting |
| `rememberCameraManipulator()` | Adds built-in orbit, pan, and zoom touch gestures |
| `SceneView { }` | The 3D viewport composable — nodes go inside the trailing lambda |
| `rememberModelInstance(...)` | Asynchronously loads a model; returns `null` until ready |
| `ModelNode(...)` | Places the loaded model in the scene |

!!! warning "Always handle the null case"
    `rememberModelInstance` returns `null` while the model is loading. The `?.let` pattern shown above is the idiomatic way to handle this. Do not force-unwrap with `!!`.

---

## Step 5: Run it

1. Click **Run** (or press `Shift+F10`).
2. After a brief loading moment, you will see the Damaged Helmet rendered in your viewport.
3. **Drag** to orbit around the model, **pinch** to zoom, and **two-finger drag** to pan.

That is a production-quality, physically-based 3D viewer in under 30 lines of code.

---

## Next steps

- **Add HDR lighting** — Download a `.hdr` environment map and pass it via `rememberEnvironment(environmentLoader) { environmentLoader.createHDREnvironment("environments/sky_2k.hdr") ?: createEnvironment(environmentLoader) }` to the `environment` parameter of `SceneView`.
- **Try AR** — Follow the [AR Compose codelab](codelabs/codelab-ar-compose.md) to place models in the real world using `ARSceneView`.
- **Need a custom 3D model?** — See the [Blender pipeline](recipes/blender-pipeline.md) for authoring a model in Blender and exporting `.glb` for SceneView.
- **Explore the samples** — The [samples page](samples.md) covers model animation, camera manipulation, cloud anchors, and more.
- **Browse the API** — Read [`llms.txt`](https://github.com/sceneview/sceneview/blob/main/llms.txt) — the AI-first machine-readable reference covering every composable, node type, and loader.
- **Building for Apple platforms?** — See the [Apple Quickstart](quickstart-ios.md) for iOS, macOS, and visionOS setup with SwiftUI and RealityKit.
