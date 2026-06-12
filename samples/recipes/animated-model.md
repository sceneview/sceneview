# Recipe: Animated Model with Controls

**Intent:** "Load an animated model and control its animation playback"

## Android (Kotlin + Jetpack Compose)

```kotlin
@Composable
fun AnimatedModelViewer() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val model = rememberModelInstance(modelLoader, "models/astronaut.glb")
    var isPlaying by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize()) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            cameraManipulator = rememberCameraManipulator()
        ) {
            model?.let {
                ModelNode(
                    modelInstance = it,
                    scaleToUnits = 1.0f,
                    autoAnimate = isPlaying
                )
            }
        }

        // Play/Pause button
        IconButton(
            onClick = { isPlaying = !isPlaying },
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play"
            )
        }
    }
}
```

## iOS (SwiftUI)

```swift
struct AnimatedModelViewer: View {
    @State private var model: ModelNode?
    @State private var isPlaying = true

    var body: some View {
        ZStack(alignment: .bottom) {
            SceneView { root in
                if let model {
                    root.addChild(model.entity)
                }
            }
            .environment(.studio)
            .cameraControls(.orbit)
            .task {
                model = try? await ModelNode.load("astronaut.usdz")
                model?.scaleToUnits(1.0)
                model?.playAllAnimations()
            }

            Button(action: {
                isPlaying.toggle()
                if isPlaying { model?.resumeAllAnimations() } else { model?.pauseAllAnimations() }
            }) {
                Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                    .font(.title)
                    .padding()
            }
        }
    }
}
```

## Key Points

- `autoAnimate = true` plays all animations in the glTF file on loop
- Toggle `autoAnimate` via Compose state to play/pause — recomposition handles the rest
- Animated models (`.glb` with embedded animations) work out of the box
- For specific animation control, access `modelInstance.animator` directly
