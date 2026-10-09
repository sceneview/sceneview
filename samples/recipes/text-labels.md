# Recipe: 3D Text Labels

**Intent:** "Add floating text labels in a 3D scene"

## Android (Kotlin + Jetpack Compose)

```kotlin
@Composable
fun TextLabels() {
    val engine = rememberEngine()
    val cameraNode = rememberCameraNode(engine)

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        cameraNode = cameraNode,
        cameraManipulator = rememberCameraManipulator()
    ) {
        // Text label — faces the camera because it is given a cameraPositionProvider
        TextNode(
            text = "Hello 3D!",
            fontSize = 48f,
            textColor = android.graphics.Color.WHITE,
            backgroundColor = 0xCC000000.toInt(),
            widthMeters = 0.6f,
            heightMeters = 0.2f,
            position = Position(y = 1f),
            cameraPositionProvider = { cameraNode.worldPosition }
        )
        // Second label at a different position, in another typeface
        TextNode(
            text = "SceneView",
            fontSize = 36f,
            textColor = android.graphics.Color.CYAN,
            typeface = Typeface.create("serif", Typeface.ITALIC),
            position = Position(y = 2f),
            cameraPositionProvider = { cameraNode.worldPosition }
        )
    }
}
```

## iOS (Swift + SwiftUI)

```swift
struct TextLabels: View {
    var body: some View {
        SceneView { root in
            // Static text label
            let label = TextNode(
                text: "Hello 3D!",
                fontSize: 0.1,
                color: .white
            )
            .position(.init(x: 0, y: 1, z: -2))
            root.addChild(label.entity)

            // Billboard text (always faces camera)
            let billboard = BillboardNode(
                child: TextNode(text: "SceneView", fontSize: 0.08).entity
            )
            .position(.init(x: 0, y: 2, z: -2))
            root.addChild(billboard.entity)
        }
        .cameraControls(.orbit)
    }
}
```

## Key concepts

| Concept | Android | iOS |
|---|---|---|
| Text node | `TextNode(text = "...", fontSize = 48f)` | `TextNode(text: "...", fontSize: 0.1)` |
| Text color | `textColor = android.graphics.Color.WHITE` | `color: .white` |
| Typeface | `typeface = Typeface.create("serif", Typeface.ITALIC)` | N/A |
| Facing the camera | `cameraPositionProvider = { cameraNode.worldPosition }` | wrap the label in a `BillboardNode` |
| Background | `backgroundColor = 0xCC000000.toInt()` | N/A (transparent by default) |
| Size (meters) | `widthMeters`, `heightMeters` | Derived from font size |
| Always faces camera | Automatic (built-in billboard behavior) | `BillboardComponent` |
| Text rendering | Canvas → texture quad | `MeshResource.generateText` |
