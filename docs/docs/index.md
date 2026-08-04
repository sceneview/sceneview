---
title: SceneView — 3D & AR SDK for Android, iOS, Web, and more
description: "The #1 open-source 3D & AR SDK. Build immersive 3D and AR experiences with Jetpack Compose, SwiftUI, and Filament.js. 9 platforms supported."
---

<div class="sv-hero" markdown>

<div class="sv-hero-badge">Open Source SDK</div>

# 3D and AR as<br>declarative UI

<p class="sv-tagline">Build immersive 3D and augmented reality experiences with the frameworks you already know. Compose-native on Android. SwiftUI-native on Apple. Zero boilerplate.</p>

<p class="sv-platforms">
<span class="sv-platform-chip">Android</span>
<span class="sv-platform-chip">iOS</span>
<span class="sv-platform-chip">macOS</span>
<span class="sv-platform-chip">visionOS</span>
<span class="sv-platform-chip">Web</span>
<span class="sv-platform-chip">TV</span>
<span class="sv-platform-chip">Flutter</span>
<span class="sv-platform-chip">React Native</span>
</p>

[Get Started](quickstart.md){ .md-button .md-button--primary .sv-btn-lg }
[View on GitHub :material-github:](https://github.com/sceneview/sceneview){ .md-button .sv-btn-lg }

</div>

<div class="sv-stats" markdown>

<div class="sv-stat">
<span class="sv-stat-number">1.9K+</span>
<span class="sv-stat-label">GitHub Stars</span>
</div>

<div class="sv-stat">
<span class="sv-stat-number">9</span>
<span class="sv-stat-label">Platforms</span>
</div>

<div class="sv-stat">
<span class="sv-stat-number">46+</span>
<span class="sv-stat-label">Node Types</span>
</div>

<div class="sv-stat">
<span class="sv-stat-number">v4.26.0</span>
<span class="sv-stat-label">Latest Release</span>
</div>

</div>

---

## Write 3D the same way you write UI

Nodes are composables. Lifecycle is automatic. State drives everything.
No boilerplate. No manual cleanup. Just declare what you want.

=== "Kotlin (Android)"

    ```kotlin
    // build.gradle: implementation("io.github.sceneview:sceneview:4.26.0")

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
    // Package.swift: .package(url: "https://github.com/sceneview/sceneview", from: "4.26.0")

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
    <script src="https://cdn.jsdelivr.net/npm/sceneview-web@4.26.0/sceneview-web.js"></script>
    <scene-view model="helmet.glb" auto-rotate camera-orbit></scene-view>
    ```

---

## Features

<div class="sv-features" markdown>

<div class="sv-feature-card" markdown>
<span class="sv-card-icon">:material-cube-outline:</span>

### Model Viewer

Load glTF/GLB (Android, Web) and USDZ (Apple) models with PBR materials, HDR environment lighting, and automatic animations. Built-in orbit camera with gesture controls.

</div>

<div class="sv-feature-card" markdown>
<span class="sv-card-icon">:material-cellphone-arrow-down:</span>

### Augmented Reality

Place 3D objects on real-world surfaces with a single tap. ARCore on Android, ARKit on iOS. Plane detection, image tracking, anchor persistence, and face tracking.

</div>

<div class="sv-feature-card" markdown>
<span class="sv-card-icon">:material-code-tags:</span>

### Declarative API

Nodes are composables inside `SceneView { }`. State drives the scene. No `addChildNode()` or `destroy()` calls. Toggle a Boolean to show/hide. Update state to animate.

</div>

<div class="sv-feature-card" markdown>
<span class="sv-card-icon">:material-cellphone-link:</span>

### Cross-Platform

Android uses Filament. Apple uses RealityKit. Web uses Filament.js (WASM). Shared logic via Kotlin Multiplatform. Native performance everywhere.

</div>

<div class="sv-feature-card" markdown>
<span class="sv-card-icon">:material-shape-outline:</span>

### Procedural Geometry

Cube, Sphere, Cylinder, Plane, Line, Path, Text nodes. All declarative, all with PBR materials. Build scenes without any 3D files.

</div>

<div class="sv-feature-card" markdown>
<span class="sv-card-icon">:material-robot-outline:</span>

### AI-First Design

Every API optimized so AI assistants generate correct code on the first try. `llms.txt` at the repo root, MCP server for tool-augmented generation, full code recipes.

</div>

<div class="sv-feature-card" markdown>
<span class="sv-card-icon">:material-weather-sunset:</span>

### Dynamic Sky & Fog

Time-of-day sun with physically-based colour model. Atmospheric fog with density and height controls. Create mood and atmosphere reactively.

</div>

<div class="sv-feature-card" markdown>
<span class="sv-card-icon">:material-atom:</span>

### Physics Simulation

Rigid body physics with gravity, collisions, and restitution. Drop objects, bounce balls, simulate real-world interactions.

</div>

</div>

---

## Install in 30 seconds

=== "Android (3D)"

    ```kotlin
    // build.gradle.kts
    dependencies {
        implementation("io.github.sceneview:sceneview:4.26.0")
    }
    ```

=== "Android (3D + AR)"

    ```kotlin
    // build.gradle.kts
    dependencies {
        implementation("io.github.sceneview:arsceneview:4.26.0")
    }
    ```

=== "iOS / macOS / visionOS"

    ```swift
    // Package.swift or Xcode > Add Package Dependency
    .package(url: "https://github.com/sceneview/sceneview", from: "4.26.0")
    ```

=== "Web"

    ```html
    <script src="https://cdn.jsdelivr.net/npm/sceneview-web@4.26.0/sceneview-web.js"></script>
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

<div class="grid cards" markdown>

-   :material-cube-outline:{ .lg .middle } **3D with Compose**

    ---

    Build your first 3D scene with a rotating glTF model, HDR lighting, and orbit camera gestures.

    [:octicons-arrow-right-24: Start the codelab](codelabs/codelab-3d-compose.md)

-   :material-cellphone-arrow-down:{ .lg .middle } **AR with Compose**

    ---

    Place 3D objects in the real world using ARCore plane detection and anchor tracking.

    [:octicons-arrow-right-24: Start the codelab](codelabs/codelab-ar-compose.md)

-   :material-apple:{ .lg .middle } **3D with SwiftUI**

    ---

    Build a 3D model viewer on iOS/macOS using SceneViewSwift, RealityKit, and orbit camera.

    [:octicons-arrow-right-24: Start the codelab](codelabs/codelab-3d-swiftui.md)

-   :material-cellphone-wireless:{ .lg .middle } **AR with SwiftUI**

    ---

    Detect planes with ARKit and tap to place 3D objects in the real world on iOS.

    [:octicons-arrow-right-24: Start the codelab](codelabs/codelab-ar-swiftui.md)

</div>

---

## Key Concepts

<div class="sv-concepts" markdown>

### :material-puzzle: Nodes are composables

Every 3D object -- models, lights, geometry, cameras -- is a `@Composable` function inside `SceneView { }`. No manual `addChildNode()` or `destroy()` calls.

### :material-state-machine: State drives the scene

Pass Compose state into node parameters. The scene updates on the next frame. Toggle a `Boolean` to show/hide a node. Update a `mutableStateOf<Anchor?>` to place content in AR.

### :material-memory: Everything is `remember`

The Filament engine, model loaders, environment, camera -- all are `remember`-ed values with automatic cleanup. Create them, use them, forget about them.

### :material-devices: Native renderers per platform

Android uses Filament (high-performance C++ via JNI). Apple platforms use RealityKit (native Metal). Web uses Filament.js (WebAssembly). Each platform gets native performance and native tooling.

</div>

---

## Trusted by developers worldwide

<div class="sv-testimonials">
<div class="sv-testimonial">
<p>"SceneView made adding 3D to our Compose app trivial. What would have taken weeks with raw Filament took us an afternoon."</p>
</div>
<div class="sv-testimonial">
<p>"The declarative API just makes sense. Coming from Compose, SceneView {} felt immediately natural."</p>
</div>
<div class="sv-testimonial">
<p>"Finally an AR SDK that doesn't fight the framework. It's just Compose."</p>
</div>
</div>

---

## Samples & Showcase

The demo apps ship with 14+ interactive samples covering model viewing, AR placement, geometry, animations, lighting, physics, fog, camera controls, post-processing, and more.

[:octicons-arrow-right-24: Android Samples](samples.md){ .md-button }
[:octicons-arrow-right-24: Apple Samples](samples-ios.md){ .md-button }

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

<div class="sv-community" markdown>

## Join the Community

Join thousands of developers building 3D and AR experiences with SceneView.

[Join Discord](https://discord.gg/UbNDDBTNqb){ .md-button }
[Star on GitHub](https://github.com/sceneview/sceneview){ .md-button .md-button--primary }
[MCP Server on npm](https://www.npmjs.com/package/sceneview-mcp){ .md-button }
[Claude Code Plugin](https://github.com/sceneview/claude-marketplace){ .md-button }

</div>
