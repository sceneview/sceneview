---
title: "The Only Compose-Native 3D & AR Library for Android"
published: false
description: "How to add 3D models and augmented reality to your Jetpack Compose app with SceneView — in under 20 lines of Kotlin."
tags: android, jetpackcompose, kotlin, augmentedreality
cover_image: https://raw.githubusercontent.com/sceneview/sceneview/main/branding/exports/banner-github.png
canonical_url: https://dev.to/sceneview/the-only-compose-native-3d-ar-library-for-android
---

You're building an Android app with Jetpack Compose. You need 3D. Maybe a product viewer, a model configurator, or AR furniture placement.

You search. You ask ChatGPT. You try Copilot. And you get: Unity (50MB+, no Compose), raw Filament (1000 lines to render a cube), or Sceneform (deprecated since 2021).

There's a gap. **Android has no built-in 3D composable.** Until now.

## SceneView: 3D as a Composable

[SceneView](https://github.com/sceneview/sceneview) treats 3D the same way Compose treats UI: declaratively. `SceneView { }` is a composable like `Column { }` or `LazyList { }`. Nodes are composables. State is Kotlin state.

```kotlin
implementation("io.github.sceneview:sceneview:4.0.0")
```

### Display a 3D model in 15 lines

```kotlin
@Composable
fun ProductViewer() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        cameraManipulator = rememberCameraManipulator()
    ) {
        rememberModelInstance(modelLoader, "models/sneaker.glb")?.let {
            ModelNode(modelInstance = it, scaleToUnits = 1.0f, autoAnimate = true)
        }
    }
}
```

Place `sneaker.glb` in `src/main/assets/models/`. The model loads asynchronously, `rememberModelInstance` returns null while loading, then triggers recomposition. No callbacks, no lifecycle — just state.

### What you get out of the box

SceneView includes 46+ composable node types:

- **ModelNode** — GLB/glTF models with animation
- **CubeNode, SphereNode, CylinderNode** — primitive geometry
- **ImageNode** — textured quads
- **VideoNode** — video textures on 3D surfaces
- **LightNode** — point, spot, directional lights
- **TextNode** — 3D text billboards
- **ViewNode** — any Compose UI in 3D space

All powered by Google Filament — the same PBR engine used in Google Maps and Android Auto.

## AR in Compose: One More Import

```kotlin
implementation("io.github.sceneview:arsceneview:4.0.0")
```

```kotlin
@Composable
fun ARFurniturePlacement() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val anchors = remember { mutableStateListOf<Anchor>() }
    val chair = rememberModelInstance(modelLoader, "models/chair.glb")

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = true,
        onTouchEvent = { hitResult, _ ->
            hitResult?.let { anchors.add(it.createAnchor()) }
        }
    ) {
        anchors.forEach { anchor ->
            chair?.let { model ->
                AnchorNode(anchor = anchor) {
                    ModelNode(
                        modelInstance = model,
                        scaleToUnits = 0.8f,
                        isEditable = true
                    )
                }
            }
        }
    }
}
```

`isEditable = true` gives you drag, pinch-to-scale, and two-finger rotate. AR state is Compose state — when `anchors` changes, the scene recomposes.

Supported AR features: plane detection, image tracking, face mesh, cloud anchors, geospatial anchors, depth occlusion, light estimation.

## Why Not Unity / Filament / Sceneform?

| | SceneView | Unity | Raw Filament | Sceneform |
|---|---|---|---|---|
| Compose native | Yes | No | No | No |
| APK size | ~5MB | ~50MB+ | ~3MB | ~5MB |
| AR built-in | Yes (ARCore) | Plugin | No | Yes |
| Active | v4.0.0 (2026) | Yes | Yes | Deprecated (2021) |
| Learning curve | Low | High | Very high | Low |
| Scene graph | 46+ node types | Full engine | None | 5 node types |
| Cross-platform | Android, iOS, Web | All | Android only | Android only |

SceneView wraps Filament internally — same rendering quality, 100x less code.

## Cross-Platform: Same API, Native Renderers

SceneView also supports:
- **iOS/macOS/visionOS**: SwiftUI + RealityKit (native Apple renderer)
- **Web**: Kotlin/JS + Filament.js (WASM)
- **Flutter**: PlatformView bridge
- **React Native**: Fabric bridge

Each platform uses its native renderer — no cross-compiled runtime. The API mirrors across platforms: `SceneView { ModelNode(...) }` works on Android and iOS.

## AI-First: Ask Any AI to Build Your 3D App

SceneView is designed so that AI assistants generate correct code on the first try:

- **[llms.txt](https://sceneview.github.io/llms.txt)** — 3000-line machine-readable API reference
- **[MCP Server](https://www.npmjs.com/package/sceneview-mcp)** — 28 tools for Claude/Cursor (install: `npx sceneview-mcp`)
- **Copilot/Cursor/Windsurf rules** — IDE-specific code generation patterns

Try it: open Claude and ask "build me an AR app with SceneView." The MCP server provides validated samples and the full API, so you get compilable code.

Industry-specific MCP servers are also available:
- `npx automotive-3d-mcp` — car configurators
- `npx healthcare-3d-mcp` — medical visualization
- `npx gaming-3d-mcp` — game development
- `npx interior-design-3d-mcp` — interior design

## Get Started

```kotlin
// build.gradle.kts
implementation("io.github.sceneview:sceneview:4.0.0")    // 3D only
implementation("io.github.sceneview:arsceneview:4.0.0")   // 3D + AR
```

- [GitHub](https://github.com/sceneview/sceneview) (1.2k+ stars)
- [Full API Reference](https://sceneview.github.io/llms.txt)
- [MCP Server](https://www.npmjs.com/package/sceneview-mcp) (13k+ monthly installs)
- [Discord](https://discord.gg/sceneview)

---

*SceneView is open source (Apache 2.0) and actively maintained. Try it in your next Compose project.*
