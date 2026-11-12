---
title: SceneView — 3D & AR SDK for Android, iOS, Web, and more
description: "Open-source 3D & AR SDK. Build immersive 3D and AR experiences with Jetpack Compose, SwiftUI, and Filament.js. 10 platforms supported."
---

# SceneView

SceneView is an open-source SDK that puts 3D and AR scenes in your UI as declarative
components: Jetpack Compose on Android, SwiftUI on Apple platforms, and a web component on the web.

| Platform | Built on | Get started |
|---|---|---|
| Android | Jetpack Compose, Filament, ARCore | [Quickstart](quickstart.md) |
| iOS, macOS, visionOS | SwiftUI, RealityKit, ARKit | [Quickstart (Apple)](quickstart-ios.md) |
| Web | Filament.js (WebGL2, WebAssembly) | [Quickstart (Web)](quickstart-web.md) |
| Desktop | Compose Multiplatform | [Compose Multiplatform](compose-multiplatform.md) |
| Android TV | Jetpack Compose, Filament | [Quickstart (TV)](quickstart-tv.md) |
| Flutter | Native bridge to Android and iOS | [Quickstart (Flutter)](quickstart-flutter.md) |
| React Native | Native bridge to Android and iOS | [Quickstart (React Native)](quickstart-react-native.md) |

[Get started](quickstart.md){ .md-button .md-button--primary }
[View on GitHub](https://github.com/sceneview/sceneview){ .md-button }

## Write 3D the same way you write UI

Nodes are composables. Lifecycle is automatic. State drives everything.
No boilerplate. No manual cleanup. Just declare what you want.

=== "Kotlin (Android)"

    ```kotlin
    // build.gradle: implementation("io.github.sceneview:sceneview:4.69.0")

    SceneView(modifier = Modifier.fillMaxSize()) {
        val model = rememberModelInstance(modelLoader, "models/helmet.glb")
        model?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 1.0f,
                autoAnimate = true,
                animationLoop = true
            )
        }
        LightNode(
            type = LightManager.Type.SUN,
            apply = { intensity(100_000f); castShadows(true) }
        )
    }
    ```

=== "Swift (iOS / macOS / visionOS)"

    ```swift
    // Package.swift: .package(url: "https://github.com/sceneview/sceneview", from: "4.69.0")

    SceneView { root in
        let model = try? await ModelNode.load("helmet.usdz")
        model?.scaleToUnits(1.0)
        root.addChild(model!.entity)
    }
    .cameraControls(.orbit)
    ```

=== "Web (JavaScript)"

    ```html
    <!-- One-liner 3D for the web -->
    <script src="https://cdn.jsdelivr.net/npm/sceneview-web@4/sceneview-web.js"></script>
    <scene-view model="helmet.glb" auto-rotate camera-orbit></scene-view>
    ```

---

## Features

- **Model Viewer.** Load glTF/GLB (Android, Web), USDZ (Apple), plus OBJ, STL, PLY and 3MF on Android, with PBR materials, HDR environment lighting, and automatic animations. Built-in orbit camera with gesture controls. See [Model Formats](formats.md).
- **Augmented Reality.** Place 3D objects on real-world surfaces with a single tap. ARCore on Android, ARKit on iOS. Plane detection, image tracking, anchor persistence, and face tracking.
- **Declarative API.** Nodes are composables inside `SceneView { }`. State drives the scene. No `addChildNode()` or `destroy()` calls. Toggle a Boolean to show/hide. Update state to animate.
- **Cross-Platform.** Android uses Filament. Apple uses RealityKit. Web uses Filament.js (WASM). Shared logic via Kotlin Multiplatform. Native performance everywhere.
- **Procedural Geometry.** Cube, Sphere, Cylinder, Plane, Line, Path, Text nodes. All declarative, all with PBR materials. Build scenes without any 3D files.
- **AI-First Design.** Every API optimized so AI assistants generate correct code on the first try. `llms.txt` at the repo root, MCP server for tool-augmented generation, full code recipes.
- **Dynamic Sky & Fog.** Time-of-day sun with physically-based colour model. Atmospheric fog with density and height controls. Create mood and atmosphere reactively.
- **Physics Simulation.** Rigid body physics with gravity, collisions, and restitution. Drop objects, bounce balls, simulate real-world interactions.

---

## Install in 30 seconds

=== "Android (3D)"

    ```kotlin
    // build.gradle.kts
    dependencies {
        implementation("io.github.sceneview:sceneview:4.69.0")
    }
    ```

=== "Android (3D + AR)"

    ```kotlin
    // build.gradle.kts
    dependencies {
        implementation("io.github.sceneview:arsceneview:4.69.0")
    }
    ```

=== "iOS / macOS / visionOS"

    ```swift
    // Package.swift or Xcode > Add Package Dependency
    .package(url: "https://github.com/sceneview/sceneview", from: "4.69.0")
    ```

=== "Web"

    ```html
    <script src="https://cdn.jsdelivr.net/npm/sceneview-web@4/sceneview-web.js"></script>
    ```

---

## AR is just as easy

=== "Kotlin (Android)"

    ```kotlin
    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        onSessionUpdated = { session, frame -> }
    ) {
        val anchor = rememberAnchor()
        anchor?.let {
            AnchorNode(anchor = it) {
                rememberModelInstance(modelLoader, "models/chair.glb")?.let { instance ->
                    ModelNode(modelInstance = instance, scaleToUnits = 0.5f)
                }
            }
        }
    }
    ```

=== "Swift (iOS)"

    ```swift
    ARSceneView(
        planeDetection: .horizontal,
        onTapOnPlane: { position, arView in
            let chair = GeometryNode.cube(size: 0.3, color: .systemBlue)
                .withGroundingShadow()
            let anchor = AnchorNode.world(position: position)
            anchor.add(chair.entity)
            arView.scene.addAnchor(anchor.entity)
        }
    )
    ```

---

## Codelabs

Step-by-step guides to build your first 3D and AR apps.

- [3D with Compose](codelabs/codelab-3d-compose.md): Build your first 3D scene with a rotating glTF model, HDR lighting, and orbit camera gestures.
- [AR with Compose](codelabs/codelab-ar-compose.md): Place 3D objects in the real world using ARCore plane detection and anchor tracking.
- [3D with SwiftUI](codelabs/codelab-3d-swiftui.md): Build a 3D model viewer on iOS/macOS using SceneViewSwift, RealityKit, and orbit camera.
- [AR with SwiftUI](codelabs/codelab-ar-swiftui.md): Detect planes with ARKit and tap to place 3D objects in the real world on iOS.

---

## Key Concepts

### Nodes are composables

Every 3D object -- models, lights, geometry, cameras -- is a `@Composable` function inside `SceneView { }`. No manual `addChildNode()` or `destroy()` calls.

### State drives the scene

Pass Compose state into node parameters. The scene updates on the next frame. Toggle a `Boolean` to show/hide a node. Update a `mutableStateOf<Anchor?>` to place content in AR.

### Everything is `remember`

The Filament engine, model loaders, environment, camera -- all are `remember`-ed values with automatic cleanup. Create them, use them, forget about them.

### Native renderers per platform

Android uses Filament (high-performance C++ via JNI). Apple platforms use RealityKit (native Metal). Web uses Filament.js (WebAssembly). Each platform gets native performance and native tooling.

---

## Samples & Showcase

The demo apps ship with 41 interactive demos on Android and 35 on Apple platforms, covering model viewing, AR placement, geometry, animations, lighting, physics, fog, camera controls, post-processing, and more.

[Android samples](samples.md){ .md-button }
[Apple samples](samples-ios.md){ .md-button }

---

## Upgrading from v2.x?

See the [Migration Guide](migration.md) for a step-by-step walkthrough of every breaking change.

---

## Coming from Sceneform?

Google [archived Sceneform](https://github.com/google-ar/sceneform-android-sdk) in 2021 and
ships no first-party declarative AR renderer. **SceneView is its Compose-native successor** —
it descends from the maintained Sceneform community fork and pairs ARCore (perception) with
Filament (rendering) behind a Jetpack Compose API. The `.sfb` model format gives way to
standard glTF (`.glb` / `.gltf`), and manual lifecycle handling is replaced by the Compose
lifecycle. See the [Migration Guide](migration.md) for the concept-by-concept mapping
(`ArFragment` → `ARScene { }`, `ModelRenderable` → `rememberModelInstance`, …) and the
[comparison page](comparison.md) for a side-by-side feature table.

---

## Community

- [Discord](https://discord.gg/UbNDDBTNqb): questions and help
- [GitHub](https://github.com/sceneview/sceneview): source, issues and releases
- [sceneview-mcp on npm](https://www.npmjs.com/package/sceneview-mcp): the MCP server for AI assistants
- [Set up your AI assistant](ai-development.md#per-tool-setup)
