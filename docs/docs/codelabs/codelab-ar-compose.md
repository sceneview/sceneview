# CodeLab: AR with Jetpack Compose — SceneView

<img src="../assets/images/showcase-ar-placement.svg" alt="What you'll build: AR model placement" width="200" style="border-radius: 28px; float: right; margin-left: 1rem; margin-bottom: 1rem;">

**Time:** ~20 minutes
**Prerequisites:** Complete the [3D CodeLab](codelab-3d-compose.md) first, or have basic SceneView knowledge
**What you'll build:** An AR scene that detects horizontal planes and places a 3D model anchored to the physical world

---

## Step 1 — Setup

### Add the AR dependency

```kotlin
dependencies {
    implementation("io.github.sceneview:arsceneview:4.20.0")
}
```

### AndroidManifest permissions

```xml
<uses-permission android:name="android.permission.CAMERA" />

<uses-feature android:name="android.hardware.camera.ar" android:required="true" />

<application>
    <!-- Required for ARCore -->
    <meta-data
        android:name="com.google.ar.core"
        android:value="required" />
</application>
```

### Runtime camera permission

Request the camera permission before showing the AR scene. The simplest way:

```kotlin
val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

LaunchedEffect(Unit) {
    cameraPermissionState.launchPermissionRequest()
}

if (cameraPermissionState.status.isGranted) {
    ARViewerScreen()
}
```

---

## Step 2 — The key idea

AR state in SceneView is just Compose state.

You don't manage the AR session lifecycle. You don't add/remove nodes imperatively. You update a `mutableStateOf<Anchor?>` and let Compose react.

```kotlin
var anchor by remember { mutableStateOf<Anchor?>(null) }
// anchor is null → no node in the scene
// anchor is non-null → AnchorNode is in the scene
```

When `anchor` becomes non-null, `AnchorNode` enters the composition. When it's cleared, `AnchorNode` leaves and is destroyed. Same Compose rules you already know.

---

## Step 3 — The empty ARScene

```kotlin
@Composable
fun ARViewerScreen() {
    ARSceneView(modifier = Modifier.fillMaxSize())
}
```

Run on a physical device. You'll see the camera feed — that's `ARCameraStream` rendering the device camera as the scene background, enabled by default.

Walk around slowly so ARCore can initialise.

---

## Step 4 — Plane detection

Enable plane rendering and react to detected planes:

```kotlin
var anchor by remember { mutableStateOf<Anchor?>(null) }

ARSceneView(
    modifier = Modifier.fillMaxSize(),
    planeRenderer = true,    // shows the AR grid on detected planes
    onSessionUpdated = { _, frame ->
        // Create an anchor on the first detected horizontal plane
        if (anchor == null) {
            anchor = frame.getUpdatedPlanes()
                .firstOrNull { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                ?.let { it.createAnchorOrNull(it.centerPose) }
        }
    }
)
```

Run the app. Point the camera at a flat surface (floor, table). The AR grid appears when ARCore detects the plane.

`anchor` is still null — we haven't put anything in the scene yet.

---

## Step 5 — Place a model

Add the AR content block with a model on the anchor:

```kotlin
val engine = rememberEngine()
val modelLoader = rememberModelLoader(engine)

val modelInstance = rememberModelInstance(modelLoader, "models/damaged_helmet.glb")

var anchor by remember { mutableStateOf<Anchor?>(null) }

ARSceneView(
    modifier = Modifier.fillMaxSize(),
    engine = engine,
    modelLoader = modelLoader,
    cameraNode = rememberARCameraNode(engine),
    planeRenderer = true,
    onSessionUpdated = { _, frame ->
        if (anchor == null) {
            anchor = frame.getUpdatedPlanes()
                .firstOrNull { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                ?.let { it.createAnchorOrNull(it.centerPose) }
        }
    }
) {
    anchor?.let { a ->
        AnchorNode(anchor = a) {
            modelInstance?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = 0.3f   // 30cm cube
                )
            }
        }
    }
}
```

Run on device. When a horizontal plane is detected, the model appears on it — physically placed in your room.

---

## Step 6 — Configure the AR session

Enable depth and HDR light estimation for a more realistic result:

```kotlin
ARSceneView(
    // ...
    sessionConfiguration = { session, config ->
        // Depth — makes virtual objects occlude behind real ones
        config.depthMode =
            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                Config.DepthMode.AUTOMATIC
            else Config.DepthMode.DISABLED

        // Light estimation — matches virtual lighting to the real room
        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

        // Instant placement — model appears immediately, then locks to plane
        config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
    }
)
```

With `ENVIRONMENTAL_HDR`, `ARScene` automatically updates the scene's `IndirectLight` every frame using ARCore's light estimation. The model lights match the room.

---

## Step 7 — Make it interactive

Add `isEditable = true` to `ModelNode` for free pinch-to-scale and drag-to-rotate:

```kotlin
ModelNode(
    modelInstance = instance,
    scaleToUnits = 0.3f,
    isEditable = true   // pinch-to-scale + drag-to-rotate, zero extra code
)
```

Or handle gestures manually for full control:

```kotlin
ARSceneView(
    // ...
    onGestureListener = rememberOnGestureListener(
        onSingleTapConfirmed = { event, _ ->
            // Tap anywhere to move the anchor
            anchor?.detach()
            anchor = null
        }
    )
)
```

---

## Step 8 — Add status UI

Show what ARCore is doing with a simple overlay:

```kotlin
Box(modifier = Modifier.fillMaxSize()) {
    ARSceneView(modifier = Modifier.fillMaxSize(), /* ... */) { /* ... */ }

    AnimatedVisibility(
        visible = anchor == null,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = if (anchor == null) "Point at a flat surface" else "Tap to move",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
```

---

## Step 9 — Complete code

```kotlin
@Composable
fun ARViewerScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    val modelInstance = rememberModelInstance(modelLoader, "models/damaged_helmet.glb")

    var anchor by remember { mutableStateOf<Anchor?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            cameraNode = rememberARCameraNode(engine),
            planeRenderer = true,
            sessionConfiguration = { session, config ->
                config.depthMode =
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                        Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            },
            onSessionUpdated = { _, frame ->
                if (anchor == null) {
                    anchor = frame.getUpdatedPlanes()
                        .firstOrNull { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                        ?.let { it.createAnchorOrNull(it.centerPose) }
                }
            },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { _, _ ->
                    anchor?.detach(); anchor = null
                }
            )
        ) {
            anchor?.let { a ->
                AnchorNode(anchor = a) {
                    modelInstance?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            scaleToUnits = 0.3f,
                            isEditable = true
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = anchor == null,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "Point at a flat surface",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
```

---

## Step 10 — What's next?

- **Image tracking** → `AugmentedImageNode` — overlay content on printed images or magazine covers
- **Cloud anchors** → `CloudAnchorNode` — share AR experiences between devices
- **Face effects** → `AugmentedFaceNode` with front camera
- **Hit result cursor** → `HitResultNode(xPx, yPx)` — a placement reticle that follows the center of the screen
- **Explore samples** → See the [samples page](../samples.md) for AR model viewer, augmented image, cloud anchor, and more
- **Building for iOS?** → See the [AR with SwiftUI codelab](codelab-ar-swiftui.md) for the equivalent experience using ARKit
