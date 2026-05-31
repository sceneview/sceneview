# Recipe: Physics Simulation

**Intent:** "Add gravity and bounce physics to objects in a scene"

## Android (Kotlin + Jetpack Compose)

```kotlin
@Composable
fun PhysicsScene() {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val material = remember(materialLoader) {
        materialLoader.createColorInstance(Color.Gray, metallic = 0f, roughness = 0.4f)
    }
    var balls by remember { mutableStateOf(listOf<BallState>()) }

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        cameraManipulator = rememberCameraManipulator(),
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { _, _ ->
                balls = balls + BallState(
                    position = Position(
                        x = Random.nextFloat() * 2 - 1,
                        y = 3f,
                        z = Random.nextFloat() * 2 - 1
                    )
                )
            }
        )
    ) {
        // Floor
        CubeNode(
            size = Size(5f, 0.1f, 5f),
            materialInstance = material,
            position = Position(y = -0.05f)
        )
        // Bouncing balls
        balls.forEach { ball ->
            val sphereNode = remember(engine) {
                SphereNode(engine, radius = 0.15f, materialInstance = material)
            }
            PhysicsNode(
                node = sphereNode,
                restitution = 0.7f,
                linearVelocity = Position(0f, 0f, 0f)
            )
        }
    }
}
```

## iOS (Swift + SwiftUI)

```swift
struct PhysicsScene: View {
    @State private var balls: [Entity] = []

    var body: some View {
        SceneView { content in
            // Floor
            let floor = GeometryNode.cube(size: 5.0, color: .gray)
                .scale(.init(x: 1, y: 0.02, z: 1))
            content.add(floor.entity)

            // Balls (RealityKit has built-in physics)
            for ball in balls {
                content.add(ball)
            }
        }
        .cameraControls(.orbit)
        .onTapGesture {
            let sphere = GeometryNode.sphere(radius: 0.15, color: .red)
                .position(.init(
                    x: Float.random(in: -1...1),
                    y: 3,
                    z: Float.random(in: -1...1)
                ))

            // RealityKit physics components
            sphere.entity.components.set(
                PhysicsBodyComponent(
                    massProperties: .default,
                    material: .generate(
                        staticFriction: 0.5,
                        dynamicFriction: 0.5,
                        restitution: 0.7
                    ),
                    mode: .dynamic
                )
            )
            sphere.entity.generateCollisionShapes(recursive: true)
            balls.append(sphere.entity)
        }
    }
}
```

## Key concepts

| Concept | Android | iOS |
|---|---|---|
| Physics engine | Custom Euler integration | RealityKit PhysicsBodyComponent |
| Gravity | `GRAVITY = -9.8f` constant | Built-in (configurable) |
| Bounce | `restitution: Float` | `PhysicsMaterialResource.restitution` |
| Collision | Floor plane check | `generateCollisionShapes` |
| Sleep | `SLEEP_THRESHOLD` auto-sleep | Built-in deactivation |
| Cross-platform math | `sceneview-core/physics/` | N/A (RealityKit native) |
