# Module arsceneview

AR scene rendering for Jetpack Compose, powered by ARCore and Google Filament.

## Quick start

```kotlin
dependencies {
    implementation("io.github.sceneview:arsceneview:4.21.2")
}
```

```kotlin
@Composable
fun ARScreen() {
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val instance = rememberModelInstance(modelLoader, "models/helmet.glb")

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        onSessionUpdated = { session, frame ->
            if (anchor == null) {
                frame.hitTest(frame.width / 2f, frame.height / 2f)
                    .firstOrNull { it.isValid(depthPoint = false, point = false) }
                    ?.let { anchor = it.createAnchor() }
            }
        },
    ) {
        anchor?.let { AnchorNode(anchor = it) {
            instance?.let { ModelNode(modelInstance = it, scaleToUnits = 0.5f) }
        }}
    }
}
```

## API overview

### ARScene composable

| Composable | Description |
|---|---|
| `ARSceneView { }` | Root AR scene. Manages an ARCore Session, camera stream, light estimation, and plane rendering. Accepts an `ARSceneScope` content block. |

### AR remember helpers

| Function | Returns | Description |
|---|---|---|
| `rememberARCameraNode(engine)` | `ARCameraNode` | Camera driven by ARCore pose each frame. |
| `rememberARCameraStream(materialLoader)` | `ARCameraStream` | OpenGL external texture for camera background + depth occlusion. |
| `rememberAREnvironment(engine)` | `Environment` | No skybox, neutral IBL; updated by LightEstimator from the camera feed. |

All `rememberXxx` helpers from the base `sceneview` module are also available.

### AR node composables (inside `ARSceneView { }`)

| Node | Description |
|---|---|
| `AnchorNode` | Tracks a world-space `Anchor`. Children follow the anchor pose. |
| `PoseNode` | Tracks a raw ARCore `Pose` without an anchor. |
| `HitResultNode` | Performs per-frame hit tests and follows the result pose. Two overloads: coordinate-based and custom lambda. |
| `AugmentedImageNode` | Renders content anchored to a detected `AugmentedImage`. Optionally scales to physical image size. |
| `AugmentedFaceNode` | Renders a 3D mesh aligned to a detected `AugmentedFace` (front camera). |
| `CloudAnchorNode` | Hosts or resolves a Cloud Anchor for persistent cross-device AR. |
| `TrackableNode` | Generic node for any `Trackable` (plane, point, etc.). |
| `StreetscapeGeometryNode` | Renders Geospatial API building/terrain geometry meshes. |

### Geospatial anchor nodes (imperative — use via companion `resolve()`)

| Node | Description |
|---|---|
| `TerrainAnchorNode` | Anchor at lat/lon with altitude relative to terrain. |
| `RooftopAnchorNode` | Anchor at lat/lon with altitude relative to building rooftop. |

### AR subsystems

| Component | Description |
|---|---|
| `ARCameraStream` | Renders the device camera feed as the scene background. Supports depth occlusion. |
| `LightEstimator` | Per-frame real-world lighting estimation (ambient intensity or environmental HDR). |
| `PlaneRenderer` | Visualizes detected ARCore planes with configurable material and shadow receiving. |

## Features

- **Plane detection**: Horizontal, vertical, or both. Visual overlay with configurable material.
- **Hit testing**: Coordinate-based, ray-based, and motion-event hit tests with type filtering.
- **Anchors**: World-space anchors that persist across frames with automatic pose updates.
- **Cloud Anchors**: Host and resolve anchors via the Google Cloud ARCore API for cross-device AR.
- **Augmented Images**: Detect and track real-world images from an `AugmentedImageDatabase`.
- **Augmented Faces**: Front-camera face mesh tracking with region poses (nose, forehead).
- **Geospatial API**: Terrain anchors, rooftop anchors, streetscape geometry, Earth anchor helpers.
- **Light estimation**: Ambient intensity mode and Environmental HDR (spherical harmonics, cubemap reflections, directional light).
- **Depth occlusion**: Automatic depth-based occlusion of virtual objects behind real-world surfaces.
- **Camera permission**: Automatic camera permission request and ARCore install/update flow.
- **Lifecycle-aware**: Session pause/resume tied to the Compose lifecycle.

## ARCore Cloud API key — required for Cloud Anchors / Geospatial / Streetscape

`Config.CloudAnchorMode.ENABLED`, `Config.GeospatialMode.ENABLED`, and `Config.StreetscapeGeometryMode.ENABLED` all hit Google's ARCore Cloud backend, which requires:

1. **ARCore API enabled** on a Google Cloud project: <https://console.cloud.google.com/apis/library/arcore.googleapis.com>
2. **Billing activated** on that project (Geospatial endpoints are paid; free tier is generous for dev/test)
3. **An API key restricted** to your Android package + signing certificate(s) (debug + Play App Signing + upload key)
4. **`ACCESS_FINE_LOCATION` permission** at runtime — Geospatial throws `FineLocationPermissionNotGrantedException` otherwise

Wire the key into your app's `AndroidManifest.xml`:

```xml
<application>
    <meta-data
        android:name="com.google.android.ar.API_KEY"
        android:value="${arcoreApiKey}" />
    <!-- … your activities … -->
</application>
```

Inject the value at build time from an env var or `local.properties` (never commit it):

```groovy
// app/build.gradle
android {
    defaultConfig {
        def arcoreApiKey = System.getenv("ARCORE_API_KEY") ?: ""
        if (arcoreApiKey.isEmpty()) {
            def localProps = rootProject.file("local.properties")
            if (localProps.exists()) {
                def props = new Properties()
                localProps.withInputStream { props.load(it) }
                arcoreApiKey = props.getProperty("ARCORE_API_KEY", "")
            }
        }
        manifestPlaceholders["arcoreApiKey"] = arcoreApiKey
    }
}
```

Add `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />` to the manifest **and** request it at runtime (e.g. via `RequestMultiplePermissions`) before mounting any `ARSceneView` that uses Geospatial mode.

Step-by-step setup with Cloud Console screenshots: see [`samples/android-demo/ARCORE_CLOUD_SETUP.md`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/ARCORE_CLOUD_SETUP.md) in the repo.

> **Pure plane-finding / hit-testing / AR camera streaming does NOT need an API key** — only the three modes above. The SDK works fully offline for everything else.

## Session configuration examples

### Default (world-facing, plane detection)

```kotlin
ARSceneView(
    planeRenderer = true,
    sessionConfiguration = { session, config ->
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
    },
)
```

### Front-facing camera (face tracking)

```kotlin
ARSceneView(
    sessionFeatures = setOf(Session.Feature.FRONT_CAMERA),
    sessionConfiguration = { _, config ->
        config.augmentedFaceMode = Config.AugmentedFaceMode.MESH3D
    },
)
```

### Augmented image detection

```kotlin
ARSceneView(
    sessionConfiguration = { session, config ->
        config.addAugmentedImage(session, "poster", posterBitmap, widthInMeters = 0.3f)
    },
    onSessionUpdated = { _, frame ->
        frame.getUpdatedAugmentedImages().forEach { image ->
            if (image.isTracking) detectedImages += image
        }
    },
) {
    detectedImages.forEach { image ->
        AugmentedImageNode(augmentedImage = image) {
            ModelNode(modelInstance = rememberModelInstance(modelLoader, "overlay.glb"))
        }
    }
}
```

### Cloud Anchors

```kotlin
ARSceneView(
    sessionConfiguration = { _, config ->
        config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
    },
) {
    anchor?.let { a ->
        CloudAnchorNode(anchor = a, onHosted = { id, state ->
            if (!state.isError) shareCloudAnchorId(id!!)
        })
    }
}
```

### Geospatial / Streetscape

```kotlin
ARSceneView(
    sessionConfiguration = { session, config ->
        if (session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
            config.geospatialMode = Config.GeospatialMode.ENABLED
            config.streetscapeGeometryMode = Config.StreetscapeGeometryMode.ENABLED
        }
    },
)
```

## Package structure

| Package | Contents |
|---|---|
| `io.github.sceneview.ar` | `ARSceneView`, `ARSceneScope`, `ARCore`, `ARFactories`, `PlaneVisualizer` |
| `io.github.sceneview.ar.node` | `AnchorNode`, `PoseNode`, `HitResultNode`, `TrackableNode`, `CloudAnchorNode`, `TerrainAnchorNode`, `RooftopAnchorNode`, `AugmentedImageNode`, `AugmentedFaceNode`, `StreetscapeGeometryNode`, `ARCameraNode` |
| `io.github.sceneview.ar.arcore` | `ARSession`, Frame/Pose/Camera/HitResult/Trackable extensions, session configuration helpers |
| `io.github.sceneview.ar.camera` | `ARCameraStream` — camera feed rendering and depth occlusion |
| `io.github.sceneview.ar.scene` | `PlaneRenderer`, `Anchor` extensions |
| `io.github.sceneview.ar.light` | `LightEstimator` — real-world lighting from camera feed |
