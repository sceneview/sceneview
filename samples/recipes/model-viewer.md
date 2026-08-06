# Recipe: Model Viewer

**Intent:** "Show a 3D model that the user can orbit around"

## Android (Kotlin + Jetpack Compose)

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
        model?.let {
            ModelNode(
                modelInstance = it,
                scaleToUnits = 1f,
                autoAnimate = true
            )
        }
    }
}
```

## iOS (Swift + SwiftUI)

```swift
struct ModelViewer: View {
    @State private var model: ModelNode?

    var body: some View {
        SceneView { root in
            if let model {
                root.addChild(model.entity)
            }
        }
        .cameraControls(.orbit)
        .environment(.studio)
        // Required, not optional polish (v4.26.0+). The content closure runs
        // ONCE, when the scene is created — at which point `model` is still
        // `nil`, because the load below has not finished. Without a
        // `.contentID(_:)` that changes when the model lands, the closure never
        // runs again and the viewer stays empty forever.
        .contentID(model == nil ? "loading" : "helmet")
        .task {
            var node = try? await ModelNode.load("models/helmet.usdz")
            node = node?.scaleToUnits(1.0)
            node?.playAllAnimations()
            model = node
        }
    }
}
```

### Swapping the model

Change `.contentID(_:)` — **never** SwiftUI's `.id(_:)`, and never wrap the
`SceneView` in an `if let model` that unmounts it while the next one loads. Both
change the view's *identity*, so SwiftUI discards the `RealityView` and builds a
new one, and on iOS 26 Simulator a re-created `RealityView` intermittently
renders nothing at all — no model, and no skybox either — permanently
([#3008](https://github.com/sceneview/sceneview/issues/3008)). Put the spinner in
an overlay so the scene never leaves the tree:

```swift
struct ModelSwitcher: View {
    let paths: [String]
    @State private var selected = 0
    @State private var model: ModelNode?
    /// Monotonic, so the id changes even if two picks resolve to the same name.
    @State private var loadCount = 0

    var body: some View {
        ZStack {
            SceneView { root in
                guard let model else { return }     // scene stays mounted
                root.addChild(model.entity)
            }
            .cameraControls(.orbit)
            .environment(.studio)
            .contentID(model == nil ? nil : loadCount)

            if model == nil { ProgressView() }      // spinner as an OVERLAY
        }
        .task(id: selected) {
            model = nil
            let node = try? await ModelNode.load(paths[selected])
            model = node?.scaleToUnits(1.0)
            loadCount += 1
        }
    }
}
```

The id has to change **every time the closure would build something different**,
including "still loading" → "loaded" — hence the `Optional`. On each change the
previous content is removed (its gesture handlers unregistered first, so it
deallocates instead of leaking) and the auto-framing pass is re-armed for the new
model. Android needs none of this: its DSL content is re-read on recomposition,
so `modelInstance` changing is an ordinary state change.

## Key concepts

| Concept | Android | iOS |
|---|---|---|
| Scene container | `SceneView { }` | `SceneView { }` |
| Model loading | `rememberModelInstance(loader, path)` | `ModelNode.load(path)` |
| Camera orbit | `rememberCameraManipulator()` | `.cameraControls(.orbit)` |
| Environment | `rememberEnvironment(loader, path)` | `.environment(.studio)` |
| Scale to fit | `scaleToUnits = 1f` | `.scaleToUnits(1.0)` |
| Auto-animate | `autoAnimate = true` | `.playAllAnimations()` |
| Swap the model | state change (content is re-read) | `.contentID(_:)` — never `.id(_:)` |
