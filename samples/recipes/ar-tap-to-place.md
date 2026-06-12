# Recipe: AR Tap-to-Place

**Intent:** "Place a 3D model on a real surface when the user taps"

## Android (Kotlin + Jetpack Compose)

```kotlin
@Composable
fun ARTapToPlace() {
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val model = rememberModelInstance(modelLoader, "models/chair.glb")

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = true,
        onSessionUpdated = { _, frame ->
            if (anchor == null) {
                anchor = frame.getUpdatedPlanes()
                    .firstOrNull { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                    ?.let { plane -> plane.createAnchorOrNull(plane.centerPose) }
            }
        }
    ) {
        anchor?.let { a ->
            AnchorNode(anchor = a) {
                model?.let { ModelNode(modelInstance = it, scaleToUnits = 0.5f) }
            }
        }
    }
}
```

## iOS (Swift + SwiftUI)

```swift
struct ARTapToPlace: View {
    @State private var model: ModelNode?

    var body: some View {
        ARSceneView(
            planeDetection: .horizontal,
            onTapOnPlane: { position, arView in
                guard let model else { return }
                let anchor = AnchorNode.world(position: position)
                anchor.add(model.entity)
                arView.scene.addAnchor(anchor.entity)
            }
        )
        .task {
            model = try? await ModelNode.load("models/chair.usdz")
                .scaleToUnits(0.5)
        }
    }
}
```

## Key concepts

| Concept | Android | iOS |
|---|---|---|
| AR container | `ARSceneView { }` | `ARSceneView { }` |
| Plane detection | `planeRenderer = true` | `planeDetection: .horizontal` |
| Anchor | `AnchorNode(anchor = a) { }` | `AnchorNode.world(position:)` |
| Hit testing | `onSessionUpdated` + frame planes | `onTapOnPlane` callback |
| AR framework | ARCore | ARKit |
