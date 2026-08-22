---
title: Recipes — SceneView Cookbook
description: "Copy-paste code patterns for SceneView: model loading, animations, camera control, gestures, lighting, AR placement, physics, and Compose UI integration."
---

# Recipes / Cookbook

Copy-paste patterns for the most common SceneView tasks.
Every snippet targets **SceneView 4.0.0** and uses Jetpack Compose.

---

## Loading & Display

### Load a model from a URL

`rememberModelInstance` accepts both **asset paths** and **https URLs**.
It returns `null` while the file downloads, so always handle the null case.

```kotlin
@Composable
fun RemoteModelScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader
    ) {
        val instance = rememberModelInstance(
            modelLoader,
            "https://example.com/models/robot.glb"
        )
        instance?.let {
            ModelNode(modelInstance = it, scaleToUnits = 1.0f)
        }
    }
}
```

### Load multiple models

Call `rememberModelInstance` once per model. Each loads independently and appears
when ready.

```kotlin
@Composable
fun MultiModelScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader
    ) {
        val helmet = rememberModelInstance(modelLoader, "models/damaged_helmet.glb")
        val fox    = rememberModelInstance(modelLoader, "models/Fox.glb")

        helmet?.let {
            ModelNode(
                modelInstance = it,
                scaleToUnits = 1.0f,
                position = Position(x = -1f, z = -2f)
            )
        }
        fox?.let {
            ModelNode(
                modelInstance = it,
                scaleToUnits = 1.0f,
                position = Position(x = 1f, z = -2f),
                autoAnimate = true
            )
        }
    }
}
```

### Show a loading indicator while model loads

`rememberModelInstance` returns `null` while loading. Use that to drive a Compose
overlay.

```kotlin
@Composable
fun ModelWithLoadingIndicator() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    val instance = rememberModelInstance(modelLoader, "models/large_scene.glb")

    Box(modifier = Modifier.fillMaxSize()) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader
        ) {
            instance?.let {
                ModelNode(modelInstance = it, scaleToUnits = 1.0f)
            }
        }

        // Overlay a spinner while the model is still null
        if (instance == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
```

### Switch between models dynamically

Change the asset path via Compose state. `rememberModelInstance` automatically
loads the new model when the path changes.

```kotlin
private val models = listOf(
    "models/damaged_helmet.glb" to 1.0f,
    "models/Fox.glb" to 0.012f,
)

@Composable
fun ModelSwitcherScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    var selectedIndex by remember { mutableIntStateOf(0) }
    val (path, scale) = models[selectedIndex]
    val instance = rememberModelInstance(modelLoader, path)

    Box(modifier = Modifier.fillMaxSize()) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader
        ) {
            instance?.let {
                ModelNode(modelInstance = it, scaleToUnits = scale)
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            models.forEachIndexed { index, (name, _) ->
                FilterChip(
                    selected = index == selectedIndex,
                    onClick = { selectedIndex = index },
                    label = { Text(name.substringAfterLast("/")) }
                )
            }
        }
    }
}
```

---

## Animation

### Auto-play all animations

Set `autoAnimate = true` on `ModelNode`. All glTF animations play simultaneously.

```kotlin
SceneView(...) {
    rememberModelInstance(modelLoader, "models/Fox.glb")?.let { instance ->
        ModelNode(
            modelInstance = instance,
            scaleToUnits = 1.0f,
            autoAnimate = true
        )
    }
}
```

### Play a specific animation by name

Set `autoAnimate = false` and pass the animation name. The name must match one
defined in the glTF file.

```kotlin
var currentAnimation by remember { mutableStateOf("Walk") }

SceneView(...) {
    rememberModelInstance(modelLoader, "models/Fox.glb")?.let { instance ->
        ModelNode(
            modelInstance = instance,
            scaleToUnits = 1.0f,
            autoAnimate = false,
            animationName = currentAnimation,
            animationLoop = true,
            animationSpeed = 1f
        )
    }
}

// Change currentAnimation to "Idle", "Run", etc. to switch animations.
```

### Loop an animation

Set `animationLoop = true`. Works with both `autoAnimate` and named animations.

```kotlin
SceneView(...) {
    rememberModelInstance(modelLoader, "models/Fox.glb")?.let { instance ->
        ModelNode(
            modelInstance = instance,
            scaleToUnits = 1.0f,
            autoAnimate = false,
            animationName = "Walk",
            animationLoop = true,
            animationSpeed = 1.5f  // 1.5x speed
        )
    }
}
```

### Rotate a model continuously

Use Compose's `rememberInfiniteTransition` with SceneView's `animateRotation`
extension, then apply the rotation via an `onFrame` callback or a parent node.

```kotlin
@Composable
fun SpinningModelScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    val centerNode = rememberNode(engine)

    val cameraNode = rememberCameraNode(engine) {
        position = Position(y = -0.5f, z = 2.0f)
        lookAt(centerNode)
        centerNode.addChildNode(this)
    }

    val transition = rememberInfiniteTransition(label = "Spin")
    val rotation by transition.animateRotation(
        initialValue = Rotation(y = 0f),
        targetValue = Rotation(y = 360f),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000)
        )
    )

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        cameraNode = cameraNode,
        onFrame = {
            centerNode.rotation = rotation
            cameraNode.lookAt(centerNode)
        }
    ) {
        rememberModelInstance(modelLoader, "models/damaged_helmet.glb")?.let {
            ModelNode(modelInstance = it, scaleToUnits = 1.0f)
        }
    }
}
```

### Animate position with Compose

Drive a node's `position` from standard Compose animation APIs.

```kotlin
@Composable
fun BouncingModelScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    val transition = rememberInfiniteTransition(label = "Bounce")
    val yOffset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Y"
    )

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader
    ) {
        rememberModelInstance(modelLoader, "models/damaged_helmet.glb")?.let {
            ModelNode(
                modelInstance = it,
                scaleToUnits = 1.0f,
                position = Position(y = yOffset, z = -2f)
            )
        }
    }
}
```

---

## Camera

### Orbit camera at a chosen distance

Use `rememberCameraManipulator` with `orbitRadius` — the camera starts that many metres from
the target, along the default 3/4 viewing angle (`orbitRadius = 2.78f` is the stock
framing). The user can orbit, pan, and zoom.

```kotlin
SceneView(
    modifier = Modifier.fillMaxSize(),
    engine = engine,
    modelLoader = modelLoader,
    cameraManipulator = rememberCameraManipulator(orbitRadius = 4.5f)
) {
    // nodes here
}
```

To place the eye yourself, pass `eyePosition` and `targetPosition` instead
(`eyePosition` was `orbitHomePosition` before 4.32 — the old name still compiles with a
deprecation warning; same behaviour, there was never a "home" gesture):

```kotlin
cameraManipulator = rememberCameraManipulator(
    // Absolute eye position. Targets the origin here, so |eyePosition| ≈ 4.47 m
    // is the distance the subject is framed from.
    eyePosition = Position(x = 0f, y = 2f, z = 4f),
    targetPosition = Position(x = 0f, y = 0f, z = 0f)
)
```

!!! warning "`eyePosition` is an absolute eye position, and its **length** is your framing distance"

    Filament's `OrbitManipulator` uses `eyePosition` verbatim as the eye
    (`mEye = mProps.orbitHomePosition`, default `(0, 0, 1)`); it is never re-based on
    `targetPosition`, which only sets the orbit pivot and the initial look direction.

    On top of that, `SceneView`'s default `autoCenterContent = true` translates the DSL
    content so its bounding-box centre lands on the **world origin**. The distance your
    subject is framed from is therefore `|eyePosition|` — the coordinates you gave
    your nodes do not survive, and `targetPosition` does not enter into it:

    ```kotlin
    // Frames from |(0, 0.2, 1.2)| ≈ 1.22 m — NOT the 2.7 m that
    // |eyePosition − targetPosition| suggests.
    rememberCameraManipulator(
        eyePosition = Position(0f, 0.2f, 1.2f),
        targetPosition = Position(0f, 0f, -1.5f)
    )
    ```

    Prefer `rememberCameraManipulator(orbitRadius = …)`, which takes the distance directly;
    otherwise pass a vector of that **length**, or set
    `autoCenterContent = false` so authored world positions survive (the framing distance
    then becomes `|eyePosition − contentCentre|`). Both readings coincide when the
    target is the origin — which is why every example on this page looks fine and why the
    discrepancy went unnoticed until [#2873](https://github.com/sceneview/sceneview/issues/2873).

    There is no built-in gesture that returns the camera to `eyePosition`:
    `onDoubleTap` is a plain callback `SceneView` forwards to your code, never wired to
    the camera.

### Fixed camera looking at a point

Use `rememberCameraNode` instead of a manipulator for a static viewpoint.

```kotlin
val cameraNode = rememberCameraNode(engine) {
    position = Position(x = 3f, y = 2f, z = 5f)
    lookAt(Position(0f, 0f, 0f))
}

SceneView(
    modifier = Modifier.fillMaxSize(),
    engine = engine,
    modelLoader = modelLoader,
    cameraNode = cameraNode,
    cameraManipulator = null  // disable orbit gestures
) {
    // nodes here
}
```

### Smooth camera transition

Use `node.transform()` with `smooth = true` to animate the camera to a new
position.

```kotlin
val cameraNode = rememberCameraNode(engine) {
    position = Position(0f, 2f, 5f)
    lookAt(Position(0f, 0f, 0f))
}

// Call this from a button click or any event:
fun flyToPosition(target: Position) {
    cameraNode.transform(
        position = target,
        smooth = true,
        smoothSpeed = 3f
    )
}
```

### Limit zoom range

Create the manipulator with a custom builder to control zoom speed.
Combine with `editableScaleRange` on interactive nodes to limit pinch-to-zoom
on individual objects.

```kotlin
// On the scene level — control orbit camera zoom speed
SceneView(
    modifier = Modifier.fillMaxSize(),
    engine = engine,
    modelLoader = modelLoader,
    cameraManipulator = rememberCameraManipulator(
        eyePosition = Position(0f, 1f, 3f),
        targetPosition = Position(0f, 0f, 0f)
    )
) {
    // On a per-node level — clamp pinch-to-scale range
    rememberModelInstance(modelLoader, "models/damaged_helmet.glb")?.let {
        ModelNode(
            modelInstance = it,
            scaleToUnits = 1.0f,
            isEditable = true,
            apply = { editableScaleRange = 0.5f..2.0f }
        )
    }
}
```

---

## Interaction

### Tap to select a node

Use `onGestureListener` with `onSingleTapConfirmed`. The `node` parameter is
the tapped node (or `null` if the user tapped empty space).

```kotlin
var selectedNode by remember { mutableStateOf<String?>(null) }

SceneView(
    modifier = Modifier.fillMaxSize(),
    engine = engine,
    modelLoader = modelLoader,
    onGestureListener = rememberOnGestureListener(
        onSingleTapConfirmed = { event, node ->
            selectedNode = node?.name
        }
    )
) {
    rememberModelInstance(modelLoader, "models/damaged_helmet.glb")?.let {
        ModelNode(
            modelInstance = it,
            scaleToUnits = 1.0f,
            isTouchable = true,
            apply = { name = "helmet" }
        )
    }
}
```

### Drag to move a node

Set `isEditable = true` on the node. Single-finger drag moves the node in the
scene.

```kotlin
SceneView(...) {
    rememberModelInstance(modelLoader, "models/damaged_helmet.glb")?.let {
        ModelNode(
            modelInstance = it,
            scaleToUnits = 1.0f,
            isEditable = true  // enables drag, pinch-scale, and two-finger rotate
        )
    }
}
```

### Pinch to scale

`isEditable = true` enables pinch-to-scale automatically. Use
`editableScaleRange` to clamp the allowed range.

```kotlin
SceneView(...) {
    rememberModelInstance(modelLoader, "models/damaged_helmet.glb")?.let {
        ModelNode(
            modelInstance = it,
            scaleToUnits = 0.5f,
            isEditable = true,
            apply = {
                editableScaleRange = 0.2f..2.0f
            }
        )
    }
}
```

### Double-tap to reset

Use the `onDoubleTap` gesture callback to reset a node's transform.

```kotlin
SceneView(
    modifier = Modifier.fillMaxSize(),
    engine = engine,
    modelLoader = modelLoader,
    onGestureListener = rememberOnGestureListener(
        onDoubleTap = { event, node ->
            node?.apply {
                position = Position(0f, 0f, -2f)
                rotation = Rotation(0f)
                scale = Scale(1f)
            }
        }
    )
) {
    rememberModelInstance(modelLoader, "models/damaged_helmet.glb")?.let {
        ModelNode(
            modelInstance = it,
            scaleToUnits = 1.0f,
            isEditable = true,
            isTouchable = true
        )
    }
}
```

### Long-press context menu

Combine `onLongPress` with Compose state to show a dropdown or bottom sheet.

```kotlin
var showMenu by remember { mutableStateOf(false) }
var menuNode by remember { mutableStateOf<String?>(null) }

Box(modifier = Modifier.fillMaxSize()) {
    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        onGestureListener = rememberOnGestureListener(
            onLongPress = { event, node ->
                node?.let {
                    menuNode = it.name
                    showMenu = true
                }
            }
        )
    ) {
        rememberModelInstance(modelLoader, "models/damaged_helmet.glb")?.let {
            ModelNode(
                modelInstance = it,
                scaleToUnits = 1.0f,
                isTouchable = true,
                apply = { name = "helmet" }
            )
        }
    }

    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
        DropdownMenuItem(text = { Text("Delete ${menuNode}") }, onClick = { showMenu = false })
        DropdownMenuItem(text = { Text("Duplicate") }, onClick = { showMenu = false })
    }
}
```

---

## Lighting & Environment

### HDR environment from assets

Place your `.hdr` file in the `assets/environments/` folder.

```kotlin
val engine = rememberEngine()
val environmentLoader = rememberEnvironmentLoader(engine)

val environment = rememberEnvironment(environmentLoader) {
    environmentLoader.createHDREnvironment("environments/sky_2k.hdr")
        ?: createEnvironment(environmentLoader)
}

SceneView(
    modifier = Modifier.fillMaxSize(),
    engine = engine,
    modelLoader = rememberModelLoader(engine),
    environment = environment,
    mainLightNode = rememberMainLightNode(engine) { intensity = 100_000f }
) {
    // nodes here
}
```

### Dynamic time-of-day lighting

Use `DynamicSkyNode` to simulate a sun that moves across the sky.

```kotlin
var timeOfDay by remember { mutableFloatStateOf(14f) } // 0-24

SceneView(
    modifier = Modifier.fillMaxSize(),
    engine = engine,
    modelLoader = modelLoader
) {
    DynamicSkyNode(
        timeOfDay = timeOfDay,   // 0=midnight, 6=sunrise, 12=noon, 18=sunset
        turbidity = 2f,          // atmospheric haze [1-10]
        sunIntensity = 110_000f
    )

    rememberModelInstance(modelLoader, "models/damaged_helmet.glb")?.let {
        ModelNode(modelInstance = it, scaleToUnits = 1.0f)
    }
}

// Drive timeOfDay from a Slider, animation, or system clock.
```

### Add fog

Use `FogNode` with a `rememberView` reference.

```kotlin
val engine = rememberEngine()
val view = rememberView(engine)

var fogEnabled by remember { mutableStateOf(true) }

SceneView(
    modifier = Modifier.fillMaxSize(),
    engine = engine,
    modelLoader = rememberModelLoader(engine),
    view = view
) {
    FogNode(
        view = view,
        density = 0.05f,
        height = 1.0f,
        color = Color(0xFFCCDDFF),
        enabled = fogEnabled
    )

    // scene content...
}
```

### Multiple lights in a scene

Combine the main directional light with additional point or spot lights using
`LightNode`. Remember: `apply` is a **named parameter**, not a trailing lambda.

```kotlin
SceneView(
    modifier = Modifier.fillMaxSize(),
    engine = engine,
    modelLoader = modelLoader,
    mainLightNode = rememberMainLightNode(engine) { intensity = 50_000f }
) {
    // Warm point light on the left
    LightNode(
        type = LightManager.Type.POINT,
        position = Position(x = -2f, y = 2f, z = 0f),
        apply = {
            color(1.0f, 0.8f, 0.6f)
            intensity(80_000f)
            falloff(10.0f)
        }
    )

    // Cool point light on the right
    LightNode(
        type = LightManager.Type.POINT,
        position = Position(x = 2f, y = 2f, z = 0f),
        apply = {
            color(0.6f, 0.8f, 1.0f)
            intensity(80_000f)
            falloff(10.0f)
        }
    )

    // Spot light from above
    LightNode(
        type = LightManager.Type.FOCUSED_SPOT,
        position = Position(y = 3f),
        apply = {
            intensity(100_000f)
            falloff(8.0f)
            castShadows(true)
        }
    )

    rememberModelInstance(modelLoader, "models/damaged_helmet.glb")?.let {
        ModelNode(modelInstance = it, scaleToUnits = 1.0f)
    }
}
```

---

## AR Patterns

### Tap-to-place on a plane

Tap the screen to place a model on a detected AR plane.

```kotlin
@Composable
fun TapToPlaceScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var frame by remember { mutableStateOf<Frame?>(null) }

    val instance = rememberModelInstance(modelLoader, "models/damaged_helmet.glb")

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = true,
        sessionConfiguration = { session, config ->
            config.depthMode = Config.DepthMode.AUTOMATIC
            config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        },
        onSessionUpdated = { _, updatedFrame -> frame = updatedFrame },
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { event, node ->
                if (node == null) {
                    frame?.hitTest(event.x, event.y)
                        ?.firstOrNull { it.isValid(depthPoint = false, point = false) }
                        ?.createAnchorOrNull()
                        ?.let { anchor = it }
                }
            }
        )
    ) {
        anchor?.let { a ->
            AnchorNode(anchor = a) {
                instance?.let {
                    ModelNode(
                        modelInstance = it,
                        scaleToUnits = 0.5f,
                        isEditable = true
                    )
                }
            }
        }
    }
}
```

### Place multiple objects

Store a list of anchors. Each tap adds a new anchor with its own model.

```kotlin
@Composable
fun MultiPlaceScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    var anchors by remember { mutableStateOf(listOf<Anchor>()) }
    var frame by remember { mutableStateOf<Frame?>(null) }

    val instance = rememberModelInstance(modelLoader, "models/damaged_helmet.glb")

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = true,
        sessionConfiguration = { session, config ->
            config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        },
        onSessionUpdated = { _, updatedFrame -> frame = updatedFrame },
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { event, node ->
                if (node == null) {
                    frame?.hitTest(event.x, event.y)
                        ?.firstOrNull { it.isValid(depthPoint = false, point = false) }
                        ?.createAnchorOrNull()
                        ?.let { anchors = anchors + it }
                }
            }
        )
    ) {
        anchors.forEach { a ->
            AnchorNode(anchor = a) {
                instance?.let {
                    ModelNode(
                        modelInstance = it,
                        scaleToUnits = 0.3f,
                        isEditable = true
                    )
                }
            }
        }
    }
}
```

### Show a reticle cursor

Use `HitResultNode` with the screen center coordinates to show a cursor that
tracks the detected surface.

```kotlin
@Composable
fun ReticleScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val view = LocalView.current

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        planeRenderer = true,
        sessionConfiguration = { _, config ->
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        }
    ) {
        HitResultNode(
            xPx = view.width / 2f,
            yPx = view.height / 2f
        ) {
            SphereNode(radius = 0.02f)
        }
    }
}
```

### Track a real-world image

Use `AugmentedImageNode` to overlay 3D content on a detected real-world image.

```kotlin
@Composable
fun ImageTrackingScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val context = LocalContext.current

    var augmentedImages by remember {
        mutableStateOf<Map<String, AugmentedImage>>(emptyMap())
    }

    val instance = rememberModelInstance(modelLoader, "models/rabbit.glb")

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        sessionConfiguration = { session, config ->
            config.addAugmentedImage(
                session,
                "target",
                context.assets.open("augmentedimages/target.jpg")
                    .use(BitmapFactory::decodeStream)
            )
        },
        onSessionUpdated = { _, frame ->
            frame.getUpdatedAugmentedImages().forEach { image ->
                augmentedImages = augmentedImages.toMutableMap().apply {
                    this[image.name] = image
                }
            }
        }
    ) {
        augmentedImages.values.forEach { image ->
            AugmentedImageNode(augmentedImage = image) {
                instance?.let {
                    ModelNode(
                        modelInstance = it,
                        scaleToUnits = image.extentX
                    )
                }
            }
        }
    }
}
```

### Face filter with front camera

Use the front camera with `AugmentedFaceNode` for face mesh effects.

```kotlin
@Composable
fun FaceFilterScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    var trackedFaces by remember {
        mutableStateOf(listOf<AugmentedFace>())
    }

    val faceMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(
            colorOf(r = 0.5f, g = 0.8f, b = 1.0f, a = 0.4f)
        )
    }

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        sessionFeatures = setOf(Session.Feature.FRONT_CAMERA),
        sessionConfiguration = { _, config ->
            config.augmentedFaceMode = Config.AugmentedFaceMode.MESH3D
        },
        onSessionUpdated = { session, _ ->
            trackedFaces = session.getAllTrackables(AugmentedFace::class.java)
                .filter { it.trackingState == TrackingState.TRACKING }
        }
    ) {
        trackedFaces.forEach { face ->
            AugmentedFaceNode(
                augmentedFace = face,
                meshMaterialInstance = faceMaterial
            )
        }
    }
}
```

---

## Layout & Composition

### 3D viewer in a scrollable list

Wrap the `Scene` in a fixed-height container inside a `LazyColumn`.

```kotlin
@Composable
fun ProductListScreen(products: List<Product>) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(products) { product ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column {
                    val instance = rememberModelInstance(modelLoader, product.modelPath)
                    SceneView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp),
                        engine = engine,
                        modelLoader = modelLoader,
                        cameraManipulator = rememberCameraManipulator(
                            eyePosition = Position(0f, 0.5f, 2f),
                            targetPosition = Position(0f)
                        )
                    ) {
                        instance?.let {
                            ModelNode(
                                modelInstance = it,
                                scaleToUnits = 1.0f
                            )
                        }
                    }
                    Text(
                        product.name,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
```

### Split screen: 3D + Compose UI

Use a `Column` or `Row` to place the 3D viewport alongside regular Compose UI.

```kotlin
@Composable
fun SplitScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    var scale by remember { mutableFloatStateOf(1.0f) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top half: 3D scene
        SceneView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            engine = engine,
            modelLoader = modelLoader
        ) {
            rememberModelInstance(modelLoader, "models/damaged_helmet.glb")?.let {
                ModelNode(modelInstance = it, scaleToUnits = scale)
            }
        }

        // Bottom half: Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
        ) {
            Text("Scale: %.1f".format(scale))
            Slider(
                value = scale,
                onValueChange = { scale = it },
                valueRange = 0.1f..3.0f
            )
        }
    }
}
```

### Overlay Compose UI on 3D scene

Use a `Box` to layer Compose widgets on top of the `Scene`.

```kotlin
@Composable
fun OverlayScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    Box(modifier = Modifier.fillMaxSize()) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader
        ) {
            rememberModelInstance(modelLoader, "models/damaged_helmet.glb")?.let {
                ModelNode(modelInstance = it, scaleToUnits = 1.0f)
            }
        }

        // Floating action button overlay
        FloatingActionButton(
            onClick = { /* action */ },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add")
        }

        // Top status bar overlay
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp),
            color = Color.Black.copy(alpha = 0.5f),
            shape = RoundedCornerShape(50)
        ) {
            Text(
                "Model Viewer",
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
    }
}
```

### ViewNode: Compose inside 3D space

Use `ViewNode` to render Compose UI as a texture mapped onto a plane in the 3D
scene.

```kotlin
@Composable
fun ViewNodeScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val windowManager = rememberViewNodeManager()

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        viewNodeWindowManager = windowManager
    ) {
        ViewNode(windowManager = windowManager) {
            Card(
                modifier = Modifier.padding(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = 0.9f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Hello 3D World!", style = MaterialTheme.typography.titleLarge)
                    Text("This is a Compose Card rendered in 3D space.")
                }
            }
        }

        rememberModelInstance(modelLoader, "models/damaged_helmet.glb")?.let {
            ModelNode(
                modelInstance = it,
                scaleToUnits = 1.0f,
                position = Position(x = 1f)
            )
        }
    }
}
```

---

## Materials

### Create a solid color material

Use `materialLoader.createColorInstance()` to create a material with a flat
color. Use `colorOf()` to convert from Compose `Color`.

```kotlin
val engine = rememberEngine()
val materialLoader = rememberMaterialLoader(engine)

val redMaterial = remember(materialLoader) {
    materialLoader.createColorInstance(colorOf(Color.Red))
}

val customMaterial = remember(materialLoader) {
    materialLoader.createColorInstance(
        colorOf(r = 0.2f, g = 0.6f, b = 1.0f, a = 1.0f)
    )
}
```

### Apply a material to geometry nodes

Pass the `materialInstance` to any geometry node: `CubeNode`, `SphereNode`,
`CylinderNode`, `PlaneNode`, `LineNode`, or `PathNode`.

```kotlin
@Composable
fun MaterialDemoScreen() {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    val redMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(colorOf(Color.Red))
    }
    val blueMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(colorOf(Color.Blue))
    }
    val greenMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(colorOf(Color.Green))
    }

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = rememberModelLoader(engine),
        materialLoader = materialLoader
    ) {
        CubeNode(
            size = Size(0.5f, 0.5f, 0.5f),
            materialInstance = redMaterial,
            position = Position(x = -1f, z = -2f)
        )
        SphereNode(
            radius = 0.3f,
            materialInstance = blueMaterial,
            position = Position(x = 0f, z = -2f)
        )
        CylinderNode(
            radius = 0.2f,
            height = 0.8f,
            materialInstance = greenMaterial,
            position = Position(x = 1f, z = -2f)
        )
        PlaneNode(
            size = Size(5f, 5f),
            materialInstance = remember(materialLoader) {
                materialLoader.createColorInstance(colorOf(rgb = 0.3f))
            },
            position = Position(y = -0.5f)
        )
    }
}
```
