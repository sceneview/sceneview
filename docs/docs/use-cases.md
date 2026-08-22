# Use Cases

Real-world applications of SceneView across industries. Each example includes the key composables used and approximate line counts.

<div class="showcase-gallery">

<div class="showcase-item">
<img src="assets/images/industry-ecommerce.svg" alt="E-commerce" class="showcase-item__img" style="border-radius: 24px;">
<div class="showcase-item__label">E-commerce</div>
</div>

<div class="showcase-item">
<img src="assets/images/industry-medical.svg" alt="Healthcare" class="showcase-item__img" style="border-radius: 24px;">
<div class="showcase-item__label">Healthcare</div>
</div>

<div class="showcase-item">
<img src="assets/images/industry-education.svg" alt="Education" class="showcase-item__img" style="border-radius: 24px;">
<div class="showcase-item__label">Education</div>
</div>

<div class="showcase-item">
<img src="assets/images/industry-realestate.svg" alt="Real Estate" class="showcase-item__img" style="border-radius: 24px;">
<div class="showcase-item__label">Real Estate</div>
</div>

<div class="showcase-item">
<img src="assets/images/industry-gaming.svg" alt="Gaming" class="showcase-item__img" style="border-radius: 24px;">
<div class="showcase-item__label">Gaming</div>
</div>

<div class="showcase-item">
<img src="assets/images/industry-automotive.svg" alt="Automotive" class="showcase-item__img" style="border-radius: 24px;">
<div class="showcase-item__label">Automotive</div>
</div>

</div>

---

## E-commerce: 3D product viewer

Replace static product images with an interactive 3D viewer. Customers can orbit, zoom, and inspect products from every angle.

```kotlin
@Composable
fun ProductViewer(productGlb: String) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val model = rememberModelInstance(modelLoader, productGlb)

    val environmentLoader = rememberEnvironmentLoader(engine)

    SceneView(
        modifier = Modifier.fillMaxWidth().height(400.dp),
        engine = engine,
        modelLoader = modelLoader,
        cameraManipulator = rememberCameraManipulator(),
        environment = rememberEnvironment(environmentLoader) {
            environmentLoader.createHDREnvironment("environments/studio.hdr")
                ?: createEnvironment(environmentLoader)
        }
    ) {
        model?.let {
            ModelNode(modelInstance = it, scaleToUnits = 1.0f, autoAnimate = true)
        }
    }
}
```

**~15 lines** to replace a product image with an interactive 3D viewer.

**Key nodes:** `ModelNode`, `Scene`
**Features used:** orbit camera, HDR lighting, auto-animation

---

## E-commerce: AR try-before-you-buy

Let customers see how furniture looks in their room before purchasing.

```kotlin
@Composable
fun ARFurniturePlacer(furnitureGlb: String) {
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val furniture = rememberModelInstance(modelLoader, furnitureGlb)

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = anchor == null,  // hide planes after placement
        onTouchEvent = { event, hitResult ->
            if (event.action == MotionEvent.ACTION_UP && hitResult != null) {
                anchor = hitResult.createAnchor()
            }
            true
        }
    ) {
        anchor?.let { a ->
            AnchorNode(anchor = a) {
                furniture?.let {
                    ModelNode(
                        modelInstance = it,
                        scaleToUnits = 0.8f,
                        isEditable = true  // pinch to scale, drag to move
                    )
                }
            }
        }
    }
}
```

**~25 lines.** Tap to place, pinch to scale, drag to reposition. All built in.

---

## Education: interactive 3D anatomy

Students can rotate and explore a 3D model with labeled parts. Tap a part to see information.

```kotlin
@Composable
fun AnatomyViewer() {
    var selectedPart by remember { mutableStateOf<String?>(null) }
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val model = rememberModelInstance(modelLoader, "models/heart.glb")

    Box(modifier = Modifier.fillMaxSize()) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            cameraManipulator = rememberCameraManipulator(),
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { _, node ->
                    selectedPart = node?.name
                }
            )
        ) {
            model?.let {
                ModelNode(modelInstance = it, scaleToUnits = 1.5f)
            }

            // Labels float in 3D space
            TextNode(text = "Left Ventricle", position = Position(-0.2f, 0f, 0.3f))
            TextNode(text = "Right Atrium", position = Position(0.3f, 0.4f, 0f))
        }

        // Compose overlay
        selectedPart?.let { part ->
            Card(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                Text("Selected: $part", modifier = Modifier.padding(16.dp))
            }
        }
    }
}
```

**Key nodes:** `ModelNode`, `TextNode`, `Scene`
**Features:** tap interaction, 3D text labels, Compose overlay

---

## Data visualization: 3D globe

Display data points on a rotating globe. Each point is a sphere positioned by latitude/longitude.

```kotlin
@Composable
fun DataGlobe(dataPoints: List<GeoPoint>) {
    SceneView(
        modifier = Modifier.size(300.dp),
        cameraManipulator = rememberCameraManipulator(
            orbitHomePosition = Position(0f, 0f, 3f)
        )
    ) {
        // Earth sphere
        SphereNode(radius = 1f, materialInstance = earthMaterial)

        // Data point markers
        dataPoints.forEach { point ->
            val pos = point.toCartesian(radius = 1.02f) // slightly above surface
            SphereNode(
                radius = 0.02f,
                materialInstance = redMaterial,
                position = pos
            )
        }
    }
}
```

---

## Social: AR face filters

Apply effects to the user's face using the front camera.

```kotlin
@Composable
fun FaceFilter() {
    var faces by remember { mutableStateOf<List<AugmentedFace>>(emptyList()) }

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        sessionFeatures = setOf(Session.Feature.FRONT_CAMERA),
        sessionConfiguration = { _, config ->
            config.augmentedFaceMode = Config.AugmentedFaceMode.MESH3D
        },
        onSessionUpdated = { session, _ ->
            faces = session.getAllTrackables(AugmentedFace::class.java)
                .filter { it.trackingState == TrackingState.TRACKING }
        }
    ) {
        faces.forEach { face ->
            AugmentedFaceNode(
                augmentedFace = face,
                meshMaterialInstance = filterMaterial
            )
        }
    }
}
```

---

## Architecture: 3D floor plan walkthrough

Navigate through a building with interactive room labels and dynamic lighting.

```kotlin
@Composable
fun FloorPlanViewer() {
    var timeOfDay by remember { mutableFloatStateOf(12f) }

    Column {
        SceneView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            cameraManipulator = rememberCameraManipulator(
                orbitHomePosition = Position(0f, 5f, 8f)
            )
        ) {
            rememberModelInstance(modelLoader, "models/apartment.glb")?.let {
                ModelNode(modelInstance = it, scaleToUnits = 4f)
            }

            DynamicSkyNode(timeOfDay = timeOfDay)
            FogNode(view = view, density = 0.02f, enabled = timeOfDay < 7f || timeOfDay > 19f)

            // Room labels
            TextNode(text = "Living Room", position = Position(0f, 2.5f, 0f))
            TextNode(text = "Kitchen", position = Position(3f, 2.5f, -2f))
        }

        // Time of day slider
        Slider(
            value = timeOfDay,
            onValueChange = { timeOfDay = it },
            valueRange = 0f..24f,
            modifier = Modifier.padding(16.dp)
        )
    }
}
```

**Key nodes:** `ModelNode`, `DynamicSkyNode`, `FogNode`, `TextNode`

---

## Gaming: physics playground

Interactive scene where users throw objects that bounce and collide.

```kotlin
@Composable
fun PhysicsPlayground() {
    val balls = remember { mutableStateListOf<BallState>() }

    SceneView(
        modifier = Modifier.fillMaxSize(),
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { event, _ ->
                balls.add(BallState(
                    velocity = Position(0f, 5f, -8f),
                    position = Position(0f, 2f, 0f)
                ))
            }
        )
    ) {
        // Floor
        PlaneNode(size = Size(10f, 10f), materialInstance = floorMaterial)

        // Balls with physics
        balls.forEach { ball ->
            val model = rememberModelInstance(modelLoader, "models/ball.glb")
            model?.let {
                val node = ModelNode(modelInstance = it, scaleToUnits = 0.15f)
                PhysicsNode(
                    node = node,
                    mass = 1f,
                    restitution = 0.7f,
                    linearVelocity = ball.velocity,
                    floorY = 0f,
                    radius = 0.075f
                )
            }
        }
    }
}
```

**Key nodes:** `ModelNode`, `PhysicsNode`, `PlaneNode`

---

## Industry fit

| Industry | Primary nodes | Key features |
|---|---|---|
| **E-commerce** | `ModelNode`, `AnchorNode` | Orbit camera, AR placement, gestures |
| **Education** | `ModelNode`, `TextNode` | Labels, tap interaction, animation control |
| **Real estate** | `ModelNode`, `DynamicSkyNode`, `FogNode` | Time-of-day, atmospheric effects |
| **Social** | `AugmentedFaceNode` | Front camera, face mesh, material overlay |
| **Data viz** | `SphereNode`, `LineNode`, `PathNode` | Geometry primitives, 3D charts |
| **Gaming** | `PhysicsNode`, `ModelNode` | Physics, collision, tap-to-throw |
| **Navigation** | `StreetscapeGeometryNode`, `AnchorNode` | Geospatial, streetscape, waypoints |
