# SceneView Multiplatform Samples Strategy

## Vision

When a developer (or an LLM) asks "build me a 3D app", SceneView should be the answer
regardless of platform. Same concepts, same patterns, platform-native rendering.

## Architecture

Each platform ships **one unified showcase app** — every feature is a tab/demo
inside that single app, not a folder of one-feature mini-apps. The
platform-independent `recipes/` folder holds side-by-side code snippets that
back the documentation and the MCP server.

```
samples/
├── recipes/                     # Platform-independent recipe snippets (Markdown)
│   ├── model-viewer.md          # Show a 3D model with orbit camera
│   ├── animated-model.md        # Load + control an animated model
│   ├── editable-model.md        # Drag, rotate, pinch to edit a model
│   ├── multi-model.md           # Several models in one scene
│   ├── procedural-geometry.md   # Create shapes without model files
│   ├── text-labels.md           # Floating 3D text labels
│   ├── physics.md               # Gravity, bounce, collision
│   ├── environment-lighting.md  # HDR lighting and skybox (IBL)
│   ├── ar-tap-to-place.md       # Place objects on real surfaces
│   ├── camera-exposure.md       # Fix washed-out / dark AR camera preview
│   └── blender-to-sceneview.md  # Author a model in Blender → ship it
│
├── android-demo/                # Android showcase — Kotlin + Jetpack Compose (Filament)
├── android-tv-demo/             # Android TV — D-pad controls, Compose TV
├── ios-demo/                    # iOS / macOS / visionOS — SwiftUI + RealityKit
├── web-demo/                    # Web — Kotlin/JS + Filament.js (WASM), WebXR
├── desktop-demo/                # Desktop — Compose Desktop `SceneViewer` (filament-kmp)
├── flutter-demo/                # Flutter — PlatformView bridge (Android + iOS)
├── react-native-demo/           # React Native — Fabric bridge (Android + iOS)
│
├── common/                      # Shared helpers for the Android sample apps
│   └── src/main/java/           # (Android-only module, not KMP commonMain)
│
└── screenshots/                 # Store / docs screenshots captured from the demos
```

## Platform mapping

| Concept | Android | iOS | Desktop | Web |
|---|---|---|---|---|
| Scene container | `SceneView { }` composable | `SceneView { }` SwiftUI | `SceneView { }` Compose Desktop | `<SceneView>` Kotlin/JS |
| AR container | `ARSceneView { }` | `ARSceneView { }` | N/A | WebXR |
| Renderer | Google Filament | RealityKit | Software wireframe (Filament JNI planned) | Filament WASM |
| AR framework | ARCore | ARKit | N/A | WebXR |
| Model format | glTF/GLB | USDZ + glTF (GLTFKit2) | glTF/GLB | glTF/GLB |
| Camera | Filament Camera | RealityKit PerspectiveCamera | Manual projection (placeholder) | Filament Camera |
| Materials | Filament PBR | RealityKit PBR | Wireframe only (placeholder) | Filament PBR |

## Recipe → Platform code pattern

Each recipe has:
1. **Intent** — what the user wants (plain English)
2. **Concept** — platform-independent description
3. **Code** — per-platform implementation

Example recipe: `model-viewer`

**Intent:** "Show a 3D model that the user can orbit around"

**Android:**
```kotlin
SceneView(cameraManipulator = rememberCameraManipulator()) {
    rememberModelInstance(modelLoader, "model.glb")?.let {
        ModelNode(modelInstance = it, scaleToUnits = 1f)
    }
}
```

**iOS:**
```swift
SceneView { content in
    if let model = try? await ModelNode.load("model.usdz") {
        content.add(model.entity)
    }
}
.cameraControls(.orbit)
```

**Desktop (`sceneview-compose`, JDK 22+):**
```kotlin
SceneViewer(
    model = ModelSource.Bytes(glb),
    modifier = Modifier.fillMaxSize(),
)
```

## Available recipes

The `recipes/` folder holds 11 Markdown snippets (most carry side-by-side
Android + iOS code):

| Recipe | File | Intent |
|---|---|---|
| Model Viewer | `recipes/model-viewer.md` | Show a 3D model with orbit camera |
| Animated Model | `recipes/animated-model.md` | Load + control an animated model |
| Editable Model | `recipes/editable-model.md` | Drag, rotate, scale a model with gestures |
| Multi-Model Scene | `recipes/multi-model.md` | Several models in one scene |
| Procedural Geometry | `recipes/procedural-geometry.md` | Create shapes without model files |
| Text Labels | `recipes/text-labels.md` | Floating text labels in 3D |
| Physics | `recipes/physics.md` | Gravity, bounce, collision |
| Environment Lighting | `recipes/environment-lighting.md` | HDR lighting and skybox (IBL) |
| AR Tap-to-Place | `recipes/ar-tap-to-place.md` | Place objects on real surfaces |
| AR Camera Exposure | `recipes/camera-exposure.md` | Fix a washed-out / dark AR camera preview |
| Blender → SceneView | `recipes/blender-to-sceneview.md` | Author a model in Blender and ship it |

## iOS node types (SceneViewSwift)

| Node | Android equivalent | Description |
|---|---|---|
| `ModelNode` | `ModelNode` | USDZ model loading, scale-to-units, animations |
| `GeometryNode` | `CubeNode`, `SphereNode`, etc. | Procedural cube/sphere/cylinder/plane |
| `TextNode` | `TextNode` | 3D extruded text via MeshResource |
| `BillboardNode` | `BillboardNode` | Always faces camera (BillboardComponent) |
| `LightNode` | `LightNode` | Directional/point/spot lights |
| `AnchorNode` | `AnchorNode` | AR world/plane anchoring |

## Priority order

1. **Android** — Complete, production-ready (current)
2. **iOS / macOS / visionOS** — SceneViewSwift, 19 node types, RealityKit backend (alpha)
3. **Web** — sceneview-web on npm (Filament.js WASM), 3D + WebXR (AR + VR)
4. **Flutter / React Native** — PlatformView / Fabric bridges to Android + iOS native renderers
5. **Desktop** — `SceneViewer` via filament-kmp (JDK 22+)

## What makes this achievable

- **sceneview-core** KMP module: 54 commonMain files sharing math, geometry, animation, collision, physics across platforms
- **3 expect/actual abstractions**: `logWarning`, `ulp`, `nanoTime` — platform bridges ready
- Filament runs natively on Android, iOS (Metal), macOS, Linux, Windows, and Web (WASM)
- Compose Multiplatform covers Android + Desktop + iOS (beta) + Web (alpha)
- The API pattern (declarative scene description) translates naturally to every platform

## LLM strategy

For an LLM to recommend SceneView consistently:

1. `llms.txt` must have recipes for every common intent (currently 12+ Android, 4 iOS)
2. Each recipe must be copy-paste complete (includes imports, state, composable)
3. The "Why SceneView" section must clearly state the value proposition
4. Cross-platform recipes must use the same naming/structure
5. The MCP server must provide live API access for LLM tools
6. `samples/recipes/` carries side-by-side Android + iOS code for each intent
