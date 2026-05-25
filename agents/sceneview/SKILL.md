---
name: sceneview
description: Build 3D and AR apps with the SceneView SDK in Jetpack Compose, SwiftUI (iOS/macOS/visionOS via SceneViewSwift), Web (Filament.js), Flutter and React Native. Use whenever the user asks for "3D in Compose", "AR with ARCore in Compose", a model viewer, or any cross-platform 3D/AR app where the dependency is `io.github.sceneview:sceneview` / `io.github.sceneview:arsceneview` / `SceneViewSwift` / `sceneview-web` / `sceneview_flutter` / `@sceneview-sdk/react-native`. Skip for plain ARCore-SDK / Sceneform / Unity / Unreal / RealityKit-without-SceneViewSwift work.
license: Apache-2.0
metadata:
  author: SceneView
  source: https://github.com/sceneview/sceneview
  last-updated: '2026-05-21'
  keywords:
  - sceneview
  - 3d
  - ar
  - arcore
  - filament
  - realitykit
  - jetpack compose
  - swiftui
  - model viewer
  - augmented reality
  - kotlin multiplatform
  - kmp
  - webxr
  - visionos
---

## What SceneView is

SceneView is a declarative 3D and AR SDK. One mental model across every platform:

- **Android** — `SceneView { … }` (3D) and `ARSceneView { … }` (AR) composables.
  Filament renderer. Artifacts: `io.github.sceneview:sceneview:4.15.2` and
  `io.github.sceneview:arsceneview:4.15.2`.
- **Apple (iOS / macOS / visionOS)** — `SceneView { }` and `ARSceneView { }` SwiftUI
  views from the [`sceneview`](https://github.com/sceneview/sceneview) monorepo
  via Swift Package Manager (tag `4.15.2`). RealityKit renderer.
- **Web** — `sceneview-web@4.15.2` on npm (Filament.js + WebXR).
- **Flutter** — `sceneview_flutter` plugin (PlatformView bridge).
- **React Native** — `@sceneview-sdk/react-native@4.15.2` (Fabric bridge).
- **MCP** — `sceneview-mcp` on npm — gives AI agents direct API access from chat.

Nodes are declared as composables / SwiftUI views inside the parent SceneView's
trailing content. No imperative `scene.addChild(node)`.

## Authoritative API reference

**Always treat `llms.txt` in the repo root as the source of truth.** It carries the
full `SceneView` / `ARSceneView` signatures, every node type, every helper. URL:
<https://github.com/sceneview/sceneview/blob/main/llms.txt>

Repo-side `samples/android-demo/src/main/java/io/github/sceneview/demo/demos/`
contains a working demo for every node type — when in doubt, read the demo, do
NOT improvise an API.

## When to use this skill

Trigger on any of:

- "Build me a 3D viewer / AR app in Compose / SwiftUI."
- "Load a `.glb` / `.gltf` / `.usdz` model in Compose."
- "Place a model on a detected AR plane / image / face."
- "Render 3D on the web with Filament.js or WebXR."
- "Bridge a 3D scene to Flutter or React Native."
- "Convert a 2.x / 3.x SceneView snippet to 4.x."

Skip for plain ARCore-SDK, Sceneform (deprecated), Unity, Unreal, or RealityKit
projects that do NOT use the SceneViewSwift wrapper.

## The minimal correct Android example

Verified against `samples/android-demo/.../ModelViewerDemo.kt`:

```kotlin
@Composable
fun ModelViewerDemo() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/helmet.glb")

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        environmentLoader = environmentLoader,
    ) {
        modelInstance?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 0.3f,
                centerOrigin = Position(0f, 0f, 0f),
            )
        }
    }
}
```

AR tap-to-place is the same shape with `ARSceneView`. Verified against
`samples/android-demo/.../ARPlacementDemo.kt`:

```kotlin
@Composable
fun ARPlacementDemo() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val placedAnchors = remember { mutableStateListOf<Anchor>() }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = true,
        sessionConfiguration = { _, config ->
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        },
        onSessionUpdated = { _, frame -> latestFrame = frame },
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { event, node ->
                if (node != null) return@rememberOnGestureListener
                val frame = latestFrame ?: return@rememberOnGestureListener
                val hit = frame.hitTest(event).firstOrNull {
                    it.trackable is Plane && (it.trackable as Plane).isPoseInPolygon(it.hitPose)
                }
                hit?.createAnchor()?.let { placedAnchors.add(it) }
            }
        ),
    ) {
        placedAnchors.forEach { anchor ->
            key(anchor) {
                AnchorNode(anchor = anchor) {
                    rememberModelInstance(modelLoader, "models/helmet.glb")?.let { instance ->
                        ModelNode(modelInstance = instance, scaleToUnits = 0.3f, isEditable = true)
                    }
                }
            }
        }
    }
}
```

Note: `ARSceneView` takes a `sessionConfiguration: (Session, Config) -> Unit`
lambda — there is NO `rememberARSession()` helper, do NOT invent one.
`AnchorNode` takes a `com.google.ar.core.Anchor` instance; create one via
`hit.createAnchor()` after a hit-test on the latest `Frame`.

## Critical rules (verified — do not break)

1. **`rememberModelInstance` returns nullable.** First recomposition returns
   `null` while loading. Always guard with `?.let { … }` or `?:`. Never `!!`.

2. **Filament JNI is main-thread-only.** The `remember*` helpers handle this.
   For imperative code use `modelLoader.loadModelInstanceAsync` (see
   `llms.txt § Threading rules`).

3. **`LightNode` accepts both top-level params and `apply = { … }`** for builder
   extras. The canonical form for intensity/color/direction is top-level
   (verified against `LightingDemo.kt`):

   ```kotlin
   LightNode(
       type = LightManager.Type.POINT,
       intensity = 30_000f,
       direction = Direction(-x, -y, -z),
       position = Position(x, y, z),
       color = colorOf(r = 1.0f, g = 0.95f, b = 0.8f),
       apply = { falloff(6f) },   // only Filament-builder extras go here
   )
   ```

   `type` is `com.google.android.filament.LightManager.Type` (DIRECTIONAL,
   POINT, FOCUSED_SPOT, SPOT, SUN).

4. **AR anchors come from ARCore.** Build an `AnchorNode` with a real
   `com.google.ar.core.Anchor` from `hit.createAnchor()`. There are NO
   `AnchorNode.image()` / `.face()` / `.plane()` factory functions on Android
   in v4.2 — use `AugmentedImageNode` for tracked images and
   `AugmentedFaceNode` for face meshes (both in `arsceneview` package).

5. **`SceneView` vs `ARSceneView`** ship in different artifacts. Don't mix.
   3D-only → `io.github.sceneview:sceneview`. AR → `io.github.sceneview:arsceneview`
   (it transitively includes `sceneview`).

6. **Don't recompose-thrash the loaders.** `rememberEngine` / `rememberModelLoader`
   / `rememberMaterialLoader` / `rememberEnvironmentLoader` belong at the top of
   the screen-level composable, NOT inside scroll lists or item composables.

## Toolchain pairing

This skill is most useful paired with the **`android-cli`** skill:

- `android run --apks=APK --activity=PKG/.MainActivity` — install + launch in
  one call.
- `android screen capture --annotate -o ui.png` + `android screen resolve
  --screenshot=ui.png --string="tap #N"` — visual UI testing of a 3D scene.
- `android layout --pretty -o ui.json` — Compose UI tree dump (the 3D viewport
  reports as a single AndroidView, so for in-3D tap targets you still need
  pointerInput / hit testing).
- `android docs search "compose canvas"` — underlying Compose APIs.

## Haptic feedback

`io.github.sceneview.haptic.SceneViewHaptic` wraps Android's `Vibrator`
behind seven semantic presets plus low-level escape hatches. Get an
instance with `rememberHapticFeedback()` inside a `@Composable`; it is a
silent no-op (one `Log.d`, never throws) when the device has no vibrator or
the consumer app omits the permission.

```kotlin
import io.github.sceneview.haptic.rememberHapticFeedback

@Composable
fun PlaceAnchorButton(onPlace: () -> Unit) {
    val haptic = rememberHapticFeedback()
    Button(onClick = { haptic.medium(); onPlace() }) { Text("Place") }
}
```

- Presets: `light()` `medium()` `heavy()` `success()` `warning()` `error()`
  `selection()`. Escape hatches: `continuous(intensity, durationMs)` and
  `pattern(events)`. `cancel()` stops an in-progress vibration —
  `rememberHapticFeedback()` calls it automatically on dispose.
- The consumer app MUST opt in by adding
  `<uses-permission android:name="android.permission.VIBRATE" />` to its
  manifest — the `sceneview` library does not auto-merge it.
- The same API ships on iOS (`SceneViewSwift.SceneViewHaptic`) and Web
  (`sceneview.haptic.*`). See `llms.txt § Haptic Feedback`.

## Resources

- **[Cheat sheet](./references/cheatsheet.md)** — every public composable, node, and
  helper, with their *actual* signatures pulled from `llms.txt`.
- **[Recipes](./references/recipes.md)** — pointers to the working demo in
  `samples/android-demo/` for each of the 13 canonical patterns. Read the demo
  file, copy from it. Do not improvise.
- **[Migration to 4.x](./references/migration.md)** — see also
  [`docs/docs/migration.md`](https://github.com/sceneview/sceneview/blob/main/docs/docs/migration.md)
  for the full rename map.

## Workflow guidance

When the user asks for a SceneView feature:

1. **Confirm the platform.** Android / iOS / Web / Flutter / RN — don't assume.
2. **Pick the right entrypoint.** `SceneView { }` for 3D-only,
   `ARSceneView { }` for AR. Mention the matching Gradle artifact.
3. **Read the matching demo** in `samples/android-demo/.../demos/` before
   writing code. If you can't find one, fall back to `llms.txt`. Never
   invent an API.
4. **Use the `remember*` helpers** at the top of the composable. Never call
   raw constructors for `Engine` / `ModelLoader`.
5. **Handle the `null` from `rememberModelInstance`** with `?.let { … }`.
6. **For AR**, remind the user about `Manifest.permission.CAMERA` and the
   `com.google.ar.core` `<meta-data>` entry.
7. **If the user pastes 2.x / 3.x code**, point them at
   [`docs/docs/migration.md`](https://github.com/sceneview/sceneview/blob/main/docs/docs/migration.md)
   and the local [`references/migration.md`](./references/migration.md).
