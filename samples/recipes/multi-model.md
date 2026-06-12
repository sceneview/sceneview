# Recipe: Multi-Model Scene

**Intent:** "Display multiple 3D models in a single scene with different positions"

## Android (Kotlin + Jetpack Compose)

```kotlin
@Composable
fun MultiModelScene() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    val chair = rememberModelInstance(modelLoader, "models/chair.glb")
    val table = rememberModelInstance(modelLoader, "models/table.glb")
    val lamp = rememberModelInstance(modelLoader, "models/lamp.glb")

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        environment = rememberEnvironment(environmentLoader) {
            environmentLoader.createHDREnvironment("environments/studio_2k.hdr")
                ?: createEnvironment(environmentLoader)
        },
        cameraManipulator = rememberCameraManipulator()
    ) {
        chair?.let {
            ModelNode(
                modelInstance = it,
                scaleToUnits = 0.8f,
                position = Position(x = -0.5f)
            )
        }
        table?.let {
            ModelNode(
                modelInstance = it,
                scaleToUnits = 1.0f,
                position = Position(x = 0f, y = 0f, z = 0f)
            )
        }
        lamp?.let {
            ModelNode(
                modelInstance = it,
                scaleToUnits = 0.6f,
                position = Position(x = 0.5f, y = 0.5f)
            )
        }
    }
}
```

## iOS (SwiftUI)

```swift
@State private var chair: ModelNode?
@State private var table: ModelNode?
@State private var lamp: ModelNode?

SceneView { root in
    if let chair { root.addChild(chair.entity) }
    if let table { root.addChild(table.entity) }
    if let lamp { root.addChild(lamp.entity) }
}
.environment(.studio)
.cameraControls(.orbit)
.task {
    chair = try? await ModelNode.load("chair.usdz")
        .scaleToUnits(0.8)
        .position(.init(x: -0.5, y: 0, z: 0))
    table = try? await ModelNode.load("table.usdz")
        .scaleToUnits(1.0)
    lamp = try? await ModelNode.load("lamp.usdz")
        .scaleToUnits(0.6)
        .position(.init(x: 0.5, y: 0.5, z: 0))
}
```

## Web (sceneview.js)

```html
<canvas id="canvas" style="width:100%;height:100vh"></canvas>
<script src="https://sceneview.github.io/js/filament/filament.js"></script>
<script src="https://cdn.jsdelivr.net/npm/sceneview-web@4.18.0/sceneview-web.js"></script>
<script>
  // Load several models into one viewer sequentially
  sceneview.createViewer("canvas").then(function (sv) {
    sv.loadModel("models/table.glb")
      .then(function () { return sv.loadModel("models/chair.glb"); })
      .then(function () { sv.fitToModels(); });
  });
</script>
```

## Key Points

- Each `rememberModelInstance` loads asynchronously — handle `null` with `?.let`
- Use `position` to offset models in 3D space (units are meters)
- Use `scaleToUnits` to normalize models to consistent sizes
- All models share the same engine, loader, and environment
