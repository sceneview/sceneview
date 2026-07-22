# SceneView Samples — Recipe Index

This directory contains sample apps demonstrating SceneView capabilities across Android, iOS, Web, Desktop, Flutter, and React Native.

> **Sample apps are English-only — do not add localized resources.** They are technical demonstrations for developers; localization adds maintenance and test-matrix cost for zero practical value (see [#1294](https://github.com/sceneview/sceneview/issues/1294)).

## Quick reference — "I want to..."

| I want to... | Sample | Key code |
|---|---|---|
| Show a 3D model with orbit camera | `model-viewer` | `SceneView { ModelNode(modelInstance) }` |
| Place a model in AR on a surface | `ar-model-viewer` | `ARSceneView { AnchorNode { ModelNode() } }` |
| Control camera orbit/pan/zoom | `camera-manipulator` | `rememberCameraManipulator()` |
| Draw lines and curves | `line-path` | `LineNode(start, end)`, `PathNode(points)` |
| Add text labels in 3D | `text-labels` | `TextNode(text = "Label")` |
| Procedural sky + fog atmosphere | `dynamic-sky` | `DynamicSkyNode`, `FogNode` |
| Add physics (gravity, bounce) | `physics-demo` | Tap-to-spawn with Euler integration |
| Bloom, DoF, SSAO effects | `post-processing` | View options (bloomOptions, etc.) |
| Local reflections / IBL zones | `reflection-probe` | `ReflectionProbeNode` |
| Detect real-world images in AR | `ar-augmented-image` | `AugmentedImageNode(image)` |
| Share AR anchors across devices | `ar-cloud-anchor` | `CloudAnchorNode(anchor)` |
| Build a full demo app | `android-demo` | 4-tab Material 3 app (Explore, AR View, Samples, About) |

## Samples by category

### 3D Scenes

| Sample | Description | Complexity |
|---|---|---|
| `model-viewer` | Load a glTF/GLB model, HDR environment, orbit camera, animations | Beginner |
| `camera-manipulator` | Orbit, pan, zoom camera with gesture and collision hit-testing | Beginner |
| `line-path` | LineNode, PathNode, procedural curves, animated sine waves | Intermediate |
| `text-labels` | World-space text labels with face-to-camera constraints | Intermediate |
| `dynamic-sky` | Procedural sky + fog atmosphere, real-time parameter sliders | Advanced |
| `physics-demo` | Tap-to-spawn spheres with gravity, bounce, Euler integration | Advanced |
| `post-processing` | Bloom, Depth of Field, SSAO, fog — all post-processing effects | Advanced |
| `reflection-probe` | ReflectionProbeNode, zone-based IBL switching | Advanced |

### Augmented Reality

| Sample | Description | Complexity |
|---|---|---|
| `ar-model-viewer` | Tap-to-place on planes, model picker, pinch/rotate | Beginner |
| `ar-augmented-image` | Image detection, overlay 3D content on real images | Intermediate |
| `ar-cloud-anchor` | Persistent cross-device anchors via Google Cloud | Advanced |

### Showcase

| Sample | Description |
|---|---|
| `android-demo` | Play Store demo app — Explore, AR View, Samples, About tabs (52 demos: 18 non-AR + 34 AR, Material 3) |

## Common recipes (copy-paste ready)

### Minimal 3D model viewer

```kotlin
@Composable
fun ModelViewer() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val model = rememberModelInstance(modelLoader, "models/helmet.glb")

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        cameraManipulator = rememberCameraManipulator()
    ) {
        model?.let { ModelNode(modelInstance = it, scaleToUnits = 1f, autoAnimate = true) }
    }
}
```

### Minimal AR tap-to-place

```kotlin
@Composable
fun ARTapToPlace() {
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val model = rememberModelInstance(modelLoader, "models/chair.glb")

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = true,
        onSessionUpdated = { _, frame ->
            if (anchor == null) {
                anchor = frame.getUpdatedPlanes()
                    .firstOrNull { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                    ?.let { it.createAnchorOrNull(it.centerPose) }
            }
        }
    ) {
        anchor?.let { a ->
            AnchorNode(anchor = a) {
                model?.let { ModelNode(modelInstance = it, scaleToUnits = 0.5f) }
            }
        }
    }
}
```

### Procedural geometry (no model files)

```kotlin
@Composable
fun ProceduralScene() {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val material = remember(materialLoader) {
        materialLoader.createColorInstance(Color.Gray, metallic = 0f, roughness = 0.4f)
    }

    SceneView(modifier = Modifier.fillMaxSize(), engine = engine) {
        CubeNode(size = Size(0.5f), materialInstance = material)
        SphereNode(radius = 0.3f, materialInstance = material,
            position = Position(x = 1f))
        CylinderNode(radius = 0.2f, height = 0.8f, materialInstance = material,
            position = Position(x = -1f))
    }
}
```

### Embed Compose UI in 3D

```kotlin
@Composable
fun ComposeIn3D() {
    val engine = rememberEngine()
    val windowManager = rememberViewNodeManager()

    SceneView(modifier = Modifier.fillMaxSize(), engine = engine) {
        ViewNode(windowManager = windowManager) {
            Card { Text("Hello from 3D!") }
        }
    }
}
```

### Animated model with controls

```kotlin
@Composable
fun AnimatedModel() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val model = rememberModelInstance(modelLoader, "models/character.glb")
    var isPlaying by remember { mutableStateOf(true) }

    Column {
        SceneView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            engine = engine, modelLoader = modelLoader
        ) {
            model?.let { ModelNode(modelInstance = it, autoAnimate = isPlaying) }
        }
        Button(onClick = { isPlaying = !isPlaying }) {
            Text(if (isPlaying) "Pause" else "Play")
        }
    }
}
```

### Multiple models in a scene

```kotlin
@Composable
fun MultiModelScene() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val helmet = rememberModelInstance(modelLoader, "models/helmet.glb")
    val car = rememberModelInstance(modelLoader, "models/car.glb")

    SceneView(modifier = Modifier.fillMaxSize(), engine = engine, modelLoader = modelLoader) {
        helmet?.let { ModelNode(modelInstance = it, scaleToUnits = 0.5f,
            position = Position(x = -0.5f)) }
        car?.let { ModelNode(modelInstance = it, scaleToUnits = 0.5f,
            position = Position(x = 0.5f)) }
    }
}
```

## Sample apps by platform

| Directory | Platform | Build command | Verified |
|---|---|---|---|
| `android-demo/` | Android (Jetpack Compose) | `./gradlew :samples:android-demo:bundleRelease` | ✓ |
| `android-tv-demo/` | Android TV | `./gradlew :samples:android-tv-demo:assembleDebug` | ✓ |
| `ios-demo/` | iOS (SwiftUI) | `open samples/ios-demo/SceneViewDemo/SceneViewDemo.xcodeproj` | ✓ |
| `web-demo/` | Web (Kotlin/JS + Filament.js) | `./gradlew :samples:web-demo:jsBrowserProductionWebpack` | ✓ |
| `desktop-demo/` | Desktop (Compose) — wireframe placeholder, not SceneView | `./gradlew :samples:desktop-demo:run` | ✓ |
| `flutter-demo/` | Flutter | `cd samples/flutter-demo && flutter build apk --debug` | ✓ |
| `react-native-demo/` | React Native | See [`react-native-demo/SETUP.md`](react-native-demo/SETUP.md) — one-time bridge build + pod install required before first `run-android`/`run-ios` | scaffold only |

"Verified" means the command above produces a successful build on a
clean checkout of `main` (validated in session 34, 2026-04-11).

### Platform-specific notes

- **Android / Android TV**: copy the root `local.properties` into the
  worktree or export `ANDROID_HOME=~/Library/Android/sdk` before running
  `./gradlew`.
- **Web**: depends on `samples/web-demo/webpack.config.d/filament.js`
  which disables Node polyfills for `path`, `fs`, `crypto`. Do not
  remove — filament.js imports those unconditionally and webpack 5
  fails the build otherwise.
- **Flutter**: the plugin uses Kotlin 2.0 + Compose Compiler Gradle
  plugin (`org.jetbrains.kotlin.plugin.compose`). No action needed in
  the demo itself; the plugin is wired in
  `flutter/sceneview_flutter/android/build.gradle`.
- **React Native**: native scaffolding is present under `android/` and
  `ios/` with the SceneView namespace `io.github.sceneview.demo.rn`.
  Before first run, build the linked bridge module and install pods —
  see `react-native-demo/SETUP.md` for the exact command sequence.

## Asset integrity

All demo apps are continuously verified by
`.claude/scripts/validate-demo-assets.sh`, which:

- Scans every Kotlin, Swift, Dart, and TypeScript source file under
  `samples/` for glb, gltf, usdz, and hdr string literals, plus the
  Swift-only patterns `asset: "name"` and `ModelNode.load("name")`
  where the `.usdz` suffix is implicit.
- Expands `$CDN/` prefixes to the real GitHub release URL and follows
  redirects when verifying CDN availability (`curl -L`).
- Checks every bundled reference against `src/main/assets/`,
  `Models/`, `src/jsMain/resources/`, and similar platform-specific
  asset directories.

The script runs in three enforcement gates:

1. **Local pre-push**: `bash .claude/scripts/pre-push-check.sh` runs it
   with `--no-cdn` (fast) after all other checks.
2. **Quality gate**: `bash .claude/scripts/quality-gate.sh` runs it with
   full CDN checks unless `--quick` is passed.
3. **CI on every PR**: `.github/workflows/pr-check.yml` runs both
   `test-validate-demo-assets.sh` (self-test on a synthetic fixture)
   and the full validator with live CDN checks.

To run it manually:

```bash
bash .claude/scripts/validate-demo-assets.sh            # full
bash .claude/scripts/validate-demo-assets.sh --android  # one platform
bash .claude/scripts/validate-demo-assets.sh --no-cdn   # skip CDN HEAD
```

A passing run reports `102 bundled, 55 CDN checked` — if those numbers
drop, a reference was deleted silently and should be investigated.

## Shared module

`samples/common/` contains shared helpers (theme, icons, navigation) used across all Android samples.

## Package naming convention

New Android/JVM-based sample apps use the `io.github.sceneview.demo.*`
prefix. Sub-packages match the sample dimension:

| Sample | Namespace |
|---|---|
| `android-demo/` | `io.github.sceneview.demo` + `.about`, `.ar`, `.explore`, `.samples`, `.theme`, `.update` |
| `react-native-demo/` (Android host) | `io.github.sceneview.demo.rn` |
| New samples | `io.github.sceneview.demo.<short-name>` |

One legacy exception: `android-tv-demo/` uses
`io.github.sceneview.sample.tv` for both its Kotlin package and
`applicationId`. This predates the `demo.*` convention. It is
intentionally NOT renamed because `applicationId` is the Play Store
identity for the published TV demo — changing it would break the
install path for every existing user. The Kotlin package could be
renamed independently of `applicationId`, but since no test or
downstream consumer depends on the TV package name, the churn is not
worth the benefit.

**When adding a new sample:** pick `io.github.sceneview.demo.<short>`
from the start, so the inconsistency ends with the TV demo.

## Running the Android demo

```bash
./gradlew :samples:android-demo:assembleDebug
adb install samples/android-demo/build/outputs/apk/debug/android-demo-debug.apk
```

Alternatively, with Google's [`android` CLI](https://developer.android.com/tools/agents/android-cli)
(combines install + launch in one step, useful for agent-driven workflows):

```bash
android run \
  --apks=samples/android-demo/build/outputs/apk/debug/android-demo-debug.apk \
  --activity=io.github.sceneview.demo/.MainActivity
```
