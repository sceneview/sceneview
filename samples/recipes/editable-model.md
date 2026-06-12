# Recipe: Editable Model (Drag, Rotate, Scale)

**Intent:** "Let the user drag, rotate, and scale a 3D model with gestures"

## Android (Kotlin + Jetpack Compose)

```kotlin
@Composable
fun EditableModelViewer() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val model = rememberModelInstance(modelLoader, "models/chair.glb")

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
        model?.let {
            ModelNode(
                modelInstance = it,
                scaleToUnits = 1.0f,
                // Enable gesture editing — drag, rotate, scale
                isEditable = true
            )
        }
    }
}
```

## iOS (SwiftUI)

```swift
@State private var model: ModelNode?

SceneView { root in
    if let model {
        root.addChild(model.entity)
    }
}
.environment(.studio)
.cameraControls(.orbit)
.task {
    model = try? await ModelNode.load("chair.usdz")
    model?.scaleToUnits(1.0)
    // Per-entity gestures (v4.2.0+) — the iOS equivalent of Android's isEditable:
    model?.entity
        .onDrag { translation in model?.position += translation }
        .onScale { factor in model?.scale *= SIMD3<Float>(repeating: factor) }
        .onRotate { angle in model?.rotation = simd_quatf(angle: angle, axis: [0, 1, 0]) }
}
```

## Key Points

- `isEditable = true` enables all gesture interactions on the node:
  - **Drag** (one finger) — translates the model in the camera plane
  - **Rotate** (two-finger twist) — rotates the model around its Y axis
  - **Scale** (pinch) — scales the model uniformly
- Camera orbit still works on empty areas of the viewport
- For AR scenes, use `isEditable = true` on `AnchorNode` children for real-world-aligned editing
- To limit gestures, configure `editableScaleRange` or handle `onGesture` callbacks
