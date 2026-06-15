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
        .task {
            var node = try? await ModelNode.load("models/helmet.usdz")
            node = node?.scaleToUnits(1.0)
            node?.playAllAnimations()
            model = node
        }
    }
}
```

## Key concepts

| Concept | Android | iOS |
|---|---|---|
| Scene container | `SceneView { }` | `SceneView { }` |
| Model loading | `rememberModelInstance(loader, path)` | `ModelNode.load(path)` |
| Camera orbit | `rememberCameraManipulator()` | `.cameraControls(.orbit)` |
| Environment | `rememberEnvironment(loader, path)` | `.environment(.studio)` |
| Scale to fit | `scaleToUnits = 1f` | `.scaleToUnits(1.0)` |
| Auto-animate | `autoAnimate = true` | `.playAllAnimations()` |
