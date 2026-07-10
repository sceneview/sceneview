# Architecture

How SceneView turns declarative UI composables into real-time 3D and AR experiences,
from your Kotlin/Swift code all the way down to the GPU.

SceneView uses **native renderers per platform**: Filament on Android, RealityKit on Apple
(iOS, macOS, visionOS). Shared logic (math, collision, geometry, animations) lives in
`sceneview-core` via Kotlin Multiplatform.

---

## The layer cake

SceneView is a stack of five layers.  Each layer only talks to the one directly below it,
keeping responsibilities clean and dependencies one-directional.

```text
 ┌──────────────────────────────────────────────────┐
 │          Your Android App (Kotlin/Compose)        │
 ├──────────────────────────────────────────────────┤
 │   SceneView Composables (Scene, ARScene, nodes)   │
 ├──────────────────────────────────────────────────┤
 │     SceneNodeManager  (Compose ↔ Filament bridge) │
 ├──────────────────────────────────────────────────┤
 │        Google Filament  (PBR rendering, JNI)      │
 ├──────────────────────────────────────────────────┤
 │     ARCore  (motion tracking, plane detection)    │
 │          ↑ only present in arsceneview            │
 └──────────────────────────────────────────────────┘
```

**From top to bottom:**

| Layer | Role |
|---|---|
| **App** | Your composables, state, and business logic. You call `SceneView { }` or `ARSceneView { }` and declare nodes. |
| **SceneView composables** | `Scene`, `ARScene`, `SceneScope`, `ARSceneScope`, and every node type (`ModelNode`, `LightNode`, `CubeNode`, etc.). These are `@Composable` functions that translate Compose state into scene-graph operations. |
| **SceneNodeManager** | An internal class that bridges the Compose snapshot world and the Filament scene graph. It adds/removes Filament entities as nodes enter and leave the Compose tree. |
| **Google Filament** | The C++ physically-based rendering engine, accessed through JNI. Owns the `Engine`, `Scene`, `View`, `Renderer`, and all GPU resources. |
| **ARCore** | Google's AR SDK. Provides camera pose, plane detection, anchors, image tracking, and light estimation. Only linked by the `arsceneview` module. |

---

## Compose to Filament bridge

The central challenge SceneView solves is keeping Compose's reactive, declarative model
in sync with Filament's imperative, mutable scene graph. Three mechanisms make this work.

### 1. Node enter/exit via `DisposableEffect`

Every node composable in `SceneScope` ends with a call to `NodeLifecycle`:

```kotlin
// Simplified from SceneScope.kt
@Composable
fun NodeLifecycle(node: Node, content: (@Composable NodeScope.() -> Unit)?) {
    DisposableEffect(node) {
        attach(node)          // adds to SnapshotStateList → triggers SceneNodeManager.addNode()
        onDispose {
            detach(node)      // removes from list → triggers SceneNodeManager.removeNode()
            node.destroy()    // releases Filament entity + components
        }
    }
    // child nodes compose inside a NodeScope tied to this parent
    if (content != null) {
        NodeScope(parentNode = node, scope = this).content()
    }
}
```

When a node composable enters the Compose tree, `DisposableEffect` fires and the node is
attached to a `SnapshotStateList`. A `LaunchedEffect` in the `Scene` composable collects
changes to that list via `snapshotFlow` and calls `SceneNodeManager.addNode()` /
`removeNode()` to insert or remove entities from the Filament `Scene`.

When the composable leaves the tree, `onDispose` detaches the node synchronously (so the
Filament entity is gone before `node.destroy()` releases its material/mesh resources) and
then destroys it.

### 2. Property updates via keyed `DisposableEffect` (and `SideEffect`)

Position, rotation, scale, visibility, and other node properties are pushed to the Filament
entity from an effect that runs on the main thread. **Every node composable** keys each
transform component on its scalar values, so the value is re-applied to the runtime node
**only when the declared value actually changes** — not on an unrelated recomposition:

```kotlin
// From SceneScope.Node()
val node = remember(engine) { Node(engine = engine).apply(apply) }
DisposableEffect(node, position.x, position.y, position.z) {
    node.position = position; onDispose {}
}
DisposableEffect(node, rotation.x, rotation.y, rotation.z) {
    node.rotation = rotation; onDispose {}
}
DisposableEffect(node, scale.x, scale.y, scale.z) {
    node.scale = scale; onDispose {}
}
DisposableEffect(node, isVisible) { node.isVisible = isVisible; onDispose {} }
```

Keying on the components means a recomposition that did **not** change a transform leaves the
runtime node untouched — so a transform that a gesture or a frame-loop driver wrote directly on
the node survives the recomposition
([#2639](https://github.com/sceneview/sceneview/issues/2639)). The geometry/media nodes
(`CubeNode`, `TextNode`, …) used the same unkeyed-`SideEffect` idiom until
[#2653](https://github.com/sceneview/sceneview/issues/2653) migrated their transforms to this
component-keyed idiom too; their `SideEffect` now only carries diffed geometry/material updates
(and idempotent text/bitmap setters).

Because these effects run on the main thread after composition, Filament's JNI calls (which
*must* happen on the main thread) are naturally satisfied.

### 3. Scene-level sync via `snapshotFlow`

The `Scene` composable uses a `LaunchedEffect` that watches `scopeChildNodes` (a
`SnapshotStateList<Node>`) through `snapshotFlow`. Every time the Compose snapshot system
detects an add or remove, the diff is forwarded to `SceneNodeManager`:

```kotlin
LaunchedEffect(nodeManager) {
    var prevNodes = emptyList<Node>()
    snapshotFlow { scopeChildNodes.toList() }.collect { newNodes ->
        (prevNodes - newNodes.toSet()).forEach { nodeManager.removeNode(it) }
        (newNodes - prevNodes.toSet()).forEach { nodeManager.addNode(it) }
        prevNodes = newNodes
    }
}
```

`SceneNodeManager` itself is straightforward -- it calls `scene.addEntities()` and
`scene.removeEntities()` on the Filament `Scene`, wires up child-node listeners, and
maintains an idempotent `managedNodes` set to prevent double-add/remove.

---

## Threading model

!!! danger "Main thread only"
    **All Filament JNI calls must execute on the main (UI) thread.** Calling
    `modelLoader.createModel*`, `materialLoader.*`, or any Filament API from a background
    coroutine will cause a native crash (SIGABRT).

### How the threading works in practice

```text
  ┌────────────────────┐      ┌──────────────────────┐
  │   Dispatchers.IO   │      │   Main Thread         │
  │                    │      │                      │
  │  Read file bytes   │─────▶│  createModelInstance  │
  │  (assets, network) │      │  (Filament JNI)      │
  └────────────────────┘      │                      │
                              │  SideEffect { ... }   │
                              │  (property updates)   │
                              │                      │
                              │  withFrameNanos { }   │
                              │  (render loop)        │
                              └──────────────────────┘
```

**`rememberModelInstance`** demonstrates the correct pattern:

1. `produceState` launches on the main thread's coroutine context.
2. File bytes are read on `Dispatchers.IO` via `withContext`.
3. Execution returns to `Main`, where `modelLoader.createModelInstance(buffer)` calls
   Filament's `AssetLoader` through JNI -- safely on the main thread.

```kotlin
@Composable
fun rememberModelInstance(modelLoader: ModelLoader, assetFileLocation: String): ModelInstance? {
    val context = LocalContext.current
    return produceState<ModelInstance?>(initialValue = null, modelLoader, assetFileLocation) {
        val buffer = withContext(Dispatchers.IO) {
            context.assets.readBuffer(assetFileLocation)
        } ?: return@produceState
        // Back on Main -- safe for Filament JNI
        value = modelLoader.createModelInstance(buffer)
    }.value
}
```

**The render loop** runs on `Main` via Compose's `withFrameNanos`, which is backed by
`Choreographer` frame callbacks:

```kotlin
LaunchedEffect(engine, renderer, view, scene) {
    while (true) {
        withFrameNanos { frameTimeNanos ->
            // all of this executes on Main
            modelLoader.updateLoad()
            nodes.forEach { it.onFrame(frameTimeNanos) }
            if (renderer.beginFrame(swapChain, frameTimeNanos)) {
                renderer.render(view)
                renderer.endFrame()
            }
        }
    }
}
```

!!! tip "Safe async loading for imperative code"
    Outside of composables, use `modelLoader.loadModelAsync(fileLocation) { model -> ... }`.
    The callback is delivered on IO, but you must marshal any Filament calls back to Main.

---

## Resource lifecycle

SceneView ties every Filament resource to Compose's lifecycle through `remember` +
`DisposableEffect`, following a consistent pattern:

```text
remember { create resource }  →  DisposableEffect { onDispose { destroy resource } }
```

### Engine and loaders

| Resource | Created by | Destroyed by |
|---|---|---|
| `Engine` + EGL context | `rememberEngine()` | `DisposableEffect.onDispose` calls `engine.safeDestroy()` + `eglContext.destroy()` |
| `ModelLoader` | `rememberModelLoader(engine)` | `DisposableEffect.onDispose` destroys `AssetLoader`, `ResourceLoader`, `MaterialProvider` |
| `MaterialLoader` | `rememberMaterialLoader(engine)` | `DisposableEffect.onDispose` |
| `EnvironmentLoader` | `rememberEnvironmentLoader(engine)` | `DisposableEffect.onDispose` |
| `Renderer`, `View`, `Scene` | `rememberRenderer()`, `rememberView()`, `rememberScene()` | `DisposableEffect.onDispose` |

### Nodes

Every node composable calls `NodeLifecycle`, which:

1. **On enter:** attaches the node to the scene via `SceneNodeManager.addNode()`.
2. **On exit:** detaches the node synchronously, then calls `node.destroy()` which releases
   the Filament entity and all associated components (transform, renderable, light, etc.).

### Model instances

`rememberModelInstance` returns `null` while loading and a `ModelInstance` once ready. The
underlying `Model` (Filament asset) is tracked by `ModelLoader`, which destroys all
registered models when the loader itself is disposed.

!!! info "No manual cleanup needed"
    If you use the composable API (`SceneView { }` + node composables), you never need to call
    `destroy()` yourself. Resource cleanup follows the Compose tree automatically.

---

## Scene rendering pipeline

Every frame follows this sequence:

```text
 1.  Compose recomposition
     └─ keyed DisposableEffect / SideEffect pushes changed node properties to Filament entities

 2.  Choreographer frame callback (withFrameNanos)
     ├─ modelLoader.updateLoad()         ← finishes async resource loads
     ├─ node.onFrame(frameTimeNanos)     ← per-node frame tick (animations, etc.)
     ├─ CameraManipulator.update()       ← orbit/pan/zoom from gestures
     │   └─ cameraNode.transform = ...   ← updates Filament camera transform
     ├─ onFrame callback                 ← user-supplied per-frame hook
     └─ renderer.beginFrame / render / endFrame
         └─ Filament PBR pipeline:
             ├─ Shadow map passes
             ├─ Color pass (PBR shading, IBL, fog)
             └─ Post-processing (tone mapping, FXAA, bloom)

 3.  Result composited onto SurfaceView or TextureView
```

For AR scenes (`ARScene`), step 2 additionally:

- Calls `session.update()` to get the latest ARCore `Frame`.
- Updates the camera projection and view matrix from the ARCore `Camera` pose.
- Runs `LightEstimator` to adjust the main light and environment from the real-world
  lighting conditions.
- Feeds the camera texture stream to `ARCameraStream` for the passthrough background.

---

## Module boundaries

SceneView is split into two Gradle modules with a strict dependency direction:

```text
 ┌─────────────────────┐         ┌─────────────────────────┐
 │     sceneview/       │◀────────│     arsceneview/         │
 │                     │ depends │                         │
 │  Scene              │   on    │  ARScene                │
 │  SceneScope         │         │  ARSceneScope           │
 │  SceneNodeManager   │         │  ARCameraNode           │
 │  Node, ModelNode,   │         │  AnchorNode, PoseNode,  │
 │  LightNode, ...     │         │  AugmentedImageNode,    │
 │  ModelLoader        │         │  TrackableNode, ...     │
 │  Engine utilities   │         │  ArSession, ARCameraStream│
 │  CollisionSystem    │         │  LightEstimator         │
 │  CameraManipulator  │         │  PlaneRenderer          │
 └─────────────────────┘         └─────────────────────────┘
    No ARCore dependency             Depends on ARCore SDK
```

### `sceneview/` -- pure 3D

Contains everything needed for 3D rendering without AR: the `Scene` composable,
`SceneScope` DSL, all base node types, model/material/environment loaders, the collision
system, gesture detectors, and camera manipulation. Has **zero dependency on ARCore**.

Artifact: `io.github.sceneview:sceneview`

### `arsceneview/` -- AR layer

Depends on `sceneview/` and adds:

- **`ARScene`** composable -- manages the ARCore `Session` lifecycle, camera stream, and
  light estimation.
- **`ARSceneScope`** -- extends `SceneScope` with AR-specific node composables like
  `AnchorNode`, `PoseNode`, `AugmentedImageNode`, `HitResultNode`,
  `AugmentedFaceNode`, `CloudAnchorNode`, and `TrackableNode`.
- **`ARCameraNode`** -- syncs the Filament camera with the ARCore camera pose each frame.
- **`ArSession`**, **`ARCameraStream`**, **`LightEstimator`**, **`PlaneRenderer`** --
  ARCore integration utilities.

Artifact: `io.github.sceneview:arsceneview`

!!! note "Scope inheritance"
    `ARSceneScope` extends `SceneScope`, so all base node composables (`ModelNode`,
    `LightNode`, `CubeNode`, etc.) are available inside `ARSceneView { }` blocks. AR-specific
    nodes are only available at the `ARSceneScope` level, not inside nested `NodeScope`
    child blocks.

---

## Apple platforms (iOS, macOS, visionOS)

The Apple side (`SceneViewSwift/`) follows the same declarative philosophy but uses
**RealityKit** instead of Filament, and **SwiftUI** instead of Jetpack Compose.

```text
 ┌──────────────────────────────────────────────────┐
 │        Your App (Swift / SwiftUI)                 │
 ├──────────────────────────────────────────────────┤
 │   SceneViewSwift (SceneView, ARSceneView, nodes)  │
 ├──────────────────────────────────────────────────┤
 │        Apple RealityKit  (PBR rendering, Metal)   │
 ├──────────────────────────────────────────────────┤
 │     ARKit  (motion tracking, plane detection)     │
 │          ↑ only present on iOS                    │
 └──────────────────────────────────────────────────┘
```

| Layer | Role |
|---|---|
| **App** | Your SwiftUI views and state. You create `SceneView { }` or `ARSceneView()`. |
| **SceneViewSwift** | Swift Package with `SceneView`, `ARSceneView`, all node types (`ModelNode`, `GeometryNode`, `LightNode`, etc.). Declarative API using SwiftUI view modifiers. |
| **RealityKit** | Apple's native 3D engine. Handles PBR rendering, physics, spatial audio via Metal. |
| **ARKit** | Apple's AR SDK. Camera tracking, plane detection, image recognition, face tracking. iOS only. |

### Cross-framework consumption

SceneViewSwift is the native rendering layer for all Apple platforms. Other frameworks
consume it through their standard bridging mechanisms:

| Framework | Bridge |
|---|---|
| Swift native | `import SceneViewSwift` via SPM |
| Flutter | `PlatformView` wrapping `SceneView` / `ARSceneView` |
| React Native | Turbo Module / Fabric component |
| KMP Compose iOS | `UIKitView` wrapping the underlying UIView |

### Shared logic via `sceneview-core`

The Kotlin Multiplatform `sceneview-core` module provides shared algorithms (collision,
triangulation, geometry generation, animations, physics) that both Android and Apple
implementations can consume. On Apple, `sceneview-core` compiles to an XCFramework.
