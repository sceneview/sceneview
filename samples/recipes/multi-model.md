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
SceneView(environment: .studio) {
    ModelNode(named: "chair.usdz")
        .scaleToUnits(0.8)
        .position(x: -0.5)
    ModelNode(named: "table.usdz")
        .scaleToUnits(1.0)
    ModelNode(named: "lamp.usdz")
        .scaleToUnits(0.6)
        .position(x: 0.5, y: 0.5)
}
.cameraControls(.orbit)
```

## Web (sceneview.js)

```html
<canvas id="canvas" style="width:100%;height:100vh"></canvas>
<script src="https://cdn.jsdelivr.net/npm/sceneview-web@4.1.0/sceneview-web.js"></script>
<script>
  // sceneview.js supports one model per viewer
  // For multi-model, create the scene and load models sequentially
  const viewer = SceneView.modelViewer("canvas", "models/table.glb");
</script>
```

## Key Points

- Each `rememberModelInstance` loads asynchronously — handle `null` with `?.let`
- Use `position` to offset models in 3D space (units are meters)
- Use `scaleToUnits` to normalize models to consistent sizes
- All models share the same engine, loader, and environment
