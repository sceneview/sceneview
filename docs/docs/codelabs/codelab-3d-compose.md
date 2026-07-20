# CodeLab: Your first 3D scene with SceneView

<img src="../assets/images/showcase-model-viewer.svg" alt="What you'll build: a 3D model viewer" width="200" style="border-radius: 28px; float: right; margin-left: 1rem; margin-bottom: 1rem;">

**Time:** ~25 minutes
**Level:** Beginner (requires Kotlin + Jetpack Compose basics)
**What you'll build:** A 3D model viewer with orbit camera, HDR lighting, and a double-tap-to-scale gesture

---

## Step 1 — What you'll build

By the end of this codelab, you will have a fully working 3D scene that:
- Loads a glTF 3D model asynchronously
- Renders it with physically-based HDR lighting
- Responds to orbit/zoom/pan gestures
- Scales the model on double-tap
- Overlays standard Compose UI on top of the 3D viewport

This is based on the 3D tab in the `android-demo` sample from the SceneView repository, built from scratch step by step.

**No 3D experience required.** If you know Compose, you already know most of this.

---

## Step 2 — Setup

### Add the dependency

In your module's `build.gradle`:

```kotlin
dependencies {
    implementation("io.github.sceneview:sceneview:4.24.0")
}
```

Sync Gradle.

### Add a 3D model asset

Create `app/src/main/assets/models/` and put a `.glb` file inside it.

For this codelab, use the **Damaged Helmet** from the Khronos glTF sample assets:
- Download: https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/DamagedHelmet/glTF-Binary/DamagedHelmet.glb
- Save as: `app/src/main/assets/models/damaged_helmet.glb`

### Add an HDR environment

Download a sky HDR for ambient lighting:
- Download: https://polyhaven.com/a/industrial_sunset_02_puresky (2K HDR, free)
- Or use any equirectangular `.hdr` file
- Save as: `app/src/main/assets/environments/sky_2k.hdr`

---

## Step 3 — The empty Scene

Create `ModelViewerScreen.kt`:

```kotlin
@Composable
fun ModelViewerScreen() {
    SceneView(modifier = Modifier.fillMaxSize())
}
```

Run the app. You'll see a dark grey rectangle — that's the Filament viewport with no content.

This is your empty 3D canvas. Let's add things to it.

---

## Step 4 — Add remembered resources

Every Filament resource in SceneView is a `remember`-ed value. Add them above the `Scene` call:

```kotlin
@Composable
fun ModelViewerScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
    )
}
```

`rememberEngine()` creates a Filament engine and its EGL context. Both are destroyed automatically when this composable leaves the tree. Same for all `remember*` resources — you never call `destroy()` yourself.

---

## Step 5 — Load a model

```kotlin
@Composable
fun ModelViewerScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Loads asynchronously on IO, creates Filament assets on Main.
    // Returns null while loading, non-null when ready.
    val modelInstance = rememberModelInstance(modelLoader, "models/damaged_helmet.glb")

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
    ) {
        // The model only exists in the scene when it's loaded.
        // When null, this block doesn't execute — no node, no problem.
        modelInstance?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 1.0f   // fit into a 1-metre cube
            )
        }
    }
}
```

Run the app. The model will appear (after a brief load) in the center of the scene.

It's probably pitch black. That's because there's no light yet.

---

## Step 6 — Add lighting

### Direct light (the sun)

```kotlin
SceneView(
    // ...
    mainLightNode = rememberMainLightNode(engine) {
        intensity = 100_000.0f
    }
) {
    // ...
}
```

### HDR environment lighting

```kotlin
val environment = rememberEnvironment(environmentLoader) {
    environmentLoader.createHDREnvironment("environments/sky_2k.hdr")
        ?: createEnvironment(environmentLoader)
}

SceneView(
    // ...
    environment = environment,
) {
    // ...
}
```

Run again. The model is now lit with physically-based rendering — specular highlights, ambient occlusion, reflections driven by the HDR sky.

---

## Step 7 — Camera position

The default camera is at the origin looking down -Z. Move it back so the model is visible:

```kotlin
SceneView(
    // ...
    cameraNode = rememberCameraNode(engine) {
        position = Position(z = 2.5f)
    }
) {
    // ...
}
```

`Position(z = 2.5f)` places the camera 2.5 metres in front of the model.

---

## Step 8 — Add orbit camera interaction

One line:

```kotlin
SceneView(
    // ...
    cameraManipulator = rememberCameraManipulator()
) {
    // ...
}
```

Run the app. You can now:
- **One-finger drag** → orbit around the model
- **Pinch** → zoom in/out
- **Two-finger drag** → pan

That's the complete camera interaction system.

---

## Step 9 — Add a gesture listener

Add double-tap-to-scale:

```kotlin
SceneView(
    // ...
    onGestureListener = rememberOnGestureListener(
        onDoubleTap = { _, node ->
            node?.apply { scale *= 2.0f }
        }
    )
) {
    // ...
}
```

`node` is the `ModelNode` that was tapped. `scale *= 2.0f` doubles its size in all three axes.

---

## Step 10 — Overlay Compose UI

`Scene` renders on a `SurfaceView` by default, which sits *behind* the Compose layer. Standard Compose composables placed *after* `Scene` in a `Box` appear on top:

```kotlin
Box(modifier = Modifier.fillMaxSize()) {
    SceneView(modifier = Modifier.fillMaxSize(), /* ... */) {
        // 3D content
    }

    // This Text is in the Compose layer, on top of the 3D scene
    Text(
        text = "Double-tap to scale",
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 24.dp)
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium
    )
}
```

No special APIs needed. The 3D scene is just a composable inside a `Box`.

---

## Step 11 — Complete code

```kotlin
@Composable
fun ModelViewerScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    val modelInstance = rememberModelInstance(modelLoader, "models/damaged_helmet.glb")
    val environment = rememberEnvironment(environmentLoader) {
        environmentLoader.createHDREnvironment("environments/sky_2k.hdr")
            ?: createEnvironment(environmentLoader)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            environment = environment,
            mainLightNode = rememberMainLightNode(engine) { intensity = 100_000.0f },
            cameraNode = rememberCameraNode(engine) { position = Position(z = 2.5f) },
            cameraManipulator = rememberCameraManipulator(),
            onGestureListener = rememberOnGestureListener(
                onDoubleTap = { _, node -> node?.apply { scale *= 2.0f } }
            )
        ) {
            modelInstance?.let { instance ->
                ModelNode(modelInstance = instance, scaleToUnits = 1.0f, autoAnimate = true)
            }
        }

        Text(
            text = "Double-tap to scale",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    shape = MaterialTheme.shapes.small
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
```

That's ~35 lines. A production-quality 3D model viewer with orbit camera, HDR lighting, and gestures.

---

## Step 12 — What's next?

- **Add AR** → See the [AR CodeLab](codelab-ar-compose.md) — same pattern, `ARScene` instead of `Scene`
- **Add geometry** → Try `CubeNode`, `SphereNode`, `CylinderNode` in the scene block
- **Embed in a screen** → Replace any `Image()` in your app with this `Box` wrapping a `Scene`
- **Explore samples** → The [samples page](../samples.md) covers model viewer, glTF camera, camera manipulator, and more
- **Read the API docs** → [sceneview source on GitHub](https://github.com/sceneview/sceneview/tree/main/sceneview) (KDoc-decorated) or the [`llms.txt`](https://github.com/sceneview/sceneview/blob/main/llms.txt) machine-readable reference
- **Building for iOS?** → See the [3D with SwiftUI codelab](codelab-3d-swiftui.md) for the equivalent experience using RealityKit
