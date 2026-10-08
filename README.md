# SceneView for Android

![SceneView Logo](https://github.com/SceneView/sceneview-android/assets/6597529/ad382001-a771-4484-9746-3ad200d00f05)

> 3D and AR for Android using Jetpack Compose and Layout View, powered by Google Filament and ARCore

[![Sceneview](https://img.shields.io/maven-central/v/io.github.sceneview/sceneview.svg?label=Sceneview&color=6c35aa)](https://search.maven.org/artifact/io.github.sceneview/sceneview)
[![ARSceneview](https://img.shields.io/maven-central/v/io.github.sceneview/arsceneview.svg?label=ARSceneview&color=6c35aa)](https://search.maven.org/artifact/io.github.sceneview/arsceneview)
[![Filament](https://img.shields.io/badge/Filament-v1.66.0-yellow)](https://github.com/google/filament)
[![ARCore](https://img.shields.io/badge/ARCore-v1.51.0-c961cb)](https://github.com/google-ar/arcore-android-sdk)

[![Discord](https://img.shields.io/discord/893787194295222292?color=7389D8&label=Discord&logo=Discord&logoColor=ffffff&style=flat-square)](https://discord.gg/UbNDDBTNqb)
[![Open Collective](https://opencollective.com/sceneview/tiers/badge.svg?label=Donators%20)](https://opencollective.com/sceneview)

## Table of Contents

- [Overview](#overview)
- [3D Scene with Filament](#3d-scene-with-filament)
    - [Installation](#3d-installation)
    - [Basic Usage](#3d-basic-usage)
    - [Sample Projects](#3d-sample-projects)
- [AR Scene with ARCore](#ar-scene-with-arcore)
    - [Installation](#ar-installation)
    - [Basic Usage](#ar-basic-usage)
    - [Sample Projects](#ar-sample-projects)
- [Resources](#resources)
- [Support the Project](#support-the-project)

## Overview

SceneView enables developers to easily incorporate 3D and AR capabilities into Android applications using Google's Filament rendering engine and ARCore. The library offers two main components:

1. **Sceneview**: 3D rendering capabilities using Filament
2. **ARSceneview**: Augmented Reality capabilities using Filament and ARCore

## <a name="3d-scene-with-filament"></a>3D Scene with Filament

### <a name="3d-installation"></a>Installation

Add the dependency to your app's build.gradle:

```gradle
dependencies {
    // Sceneview for 3D capabilities
    implementation("io.github.sceneview:sceneview:2.3.4")
}
```

### <a name="3d-basic-usage"></a>Basic Usage

Here's a basic example of creating a 3D scene in Jetpack Compose:

```kotlin

// Filament 3D Engine
val engine = rememberEngine()

// Asset loaders
val modelLoader = rememberModelLoader(engine)
val materialLoader = rememberMaterialLoader(engine)
val environmentLoader = rememberEnvironmentLoader(engine)

Scene(
    modifier = Modifier.fillMaxSize(),
    engine = engine,

    // Core rendering components
    view = rememberView(engine),
    renderer = rememberRenderer(engine),
    scene = rememberScene(engine),
 
    // Asset loaders
    modelLoader = modelLoader,
    materialLoader = materialLoader,
    environmentLoader = environmentLoader,

    // Collision System
    collisionSystem = rememberCollisionSystem(view),
    
    // Add a direct light source (required for shadows)
    mainLightNode = rememberMainLightNode(engine) {
        intensity = 100_000.0f
    },
    
    // Set up environment lighting and skybox from an HDR file
    environment = rememberEnvironment(environmentLoader) {
        environmentLoader.createHDREnvironment(
            assetFileLocation = "environments/sky_2k.hdr"
        )!!
    },
    
    // Configure camera position
    cameraNode = rememberCameraNode(engine) {
        position = Position(z = 4.0f)
    },
    
    // Enable user interaction with the camera
    cameraManipulator = rememberCameraManipulator(),
    
    // Add 3D models and objects to the scene
    childNodes = rememberNodes {
        // Add a glTF model
        add(
            ModelNode(
                // Create a single instance model from assets file
                modelInstance = modelLoader.createModelInstance(
                    assetFileLocation = "models/damaged_helmet.glb"
                ),
                // Make the model fit into a 1 unit cube
                scaleToUnits = 1.0f
            )
        )
        
        // Add a 3D cylinder with custom material
        add(
            CylinderNode(
                engine = engine,
                radius = 0.2f,
                height = 2.0f,
                // Simple colored material with physics properties
                materialInstance = materialLoader.createColorInstance(
                    color = Color.Blue,
                    metallic = 0.5f,
                    roughness = 0.2f,
                    reflectance = 0.4f
                )
        ).apply {
            // Define the node position and rotation 
            transform(
                position = Position(y = 1.0f),
                rotation = Rotation(x = 90.0f)
            )
        })
    },
    
    // Handle user interactions
    onGestureListener = rememberOnGestureListener(
        onDoubleTapEvent = { event, tappedNode ->
            tappedNode?.let { it.scale *= 2.0f }
        }
    ),
    
    // Handle tap event on the scene
    onTouchEvent = { event: MotionEvent, hitResult: HitResult? ->
        hitResult?.let { println("World tapped : ${it.worldPosition}") }
        false
    },
    
    // Frame update callback
    onFrame = { frameTimeNanos ->
        // Handle per-frame updates here
    }
)
```

### <a name="3d-sample-projects"></a>Sample Projects

- [Model Viewer (Compose)](/samples/model-viewer-compose)
- [Model Viewer (Layout)](/samples/model-viewer)
- [Camera Manipulator (Compose)](/samples/camera-manipulator-compose)
- [gtTF Camera (Compose)](/samples/gltf-camera)

## <a name="ar-scene-with-arcore"></a>AR Scene with ARCore

### <a name="ar-installation"></a>Installation

Add the dependency to your app's build.gradle:

```gradle
dependencies {
    // ARSceneview for augmented reality capabilities
    implementation 'io.github.sceneview:arsceneview:2.3.4'
}
```

### <a name="ar-basic-usage"></a>Basic Usage

Here's a basic example of creating an AR scene:

```kotlin
ARScene(
    // Configure AR session features
    sessionFeatures = setOf(),
    sessionCameraConfig = null,
    
    // Configure AR session settings
    sessionConfiguration = { session, config ->
        // Enable depth if supported on the device
        config.depthMode =
            when (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                true -> Config.DepthMode.AUTOMATIC
                else -> Config.DepthMode.DISABLED
            }
        config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
    },
    
    // Enable plane detection visualization
    planeRenderer = true,
    
    // Configure camera stream
    cameraStream = rememberARCameraStream(materialLoader),
    
    // Session lifecycle callbacks
    onSessionCreated = { session ->
        // Handle session creation
    },
    onSessionResumed = { session ->
        // Handle session resume
    },
    onSessionPaused = { session ->
        // Handle session pause
    },
    
    // Frame update callback
    onSessionUpdated = { session, updatedFrame ->
        // Process AR frame updates
    },
    
    // Error handling
    onSessionFailed = { exception ->
        // Handle ARCore session errors
    },
    
    // Track camera tracking state changes
    onTrackingFailureChanged = { trackingFailureReason ->
        // Handle tracking failures
    }
)
```

### <a name="ar-sample-projects"></a>Sample Projects

- [AR Model Viewer (Compose)](/samples/ar-model-viewer-compose)
- [AR Model Viewer (Layout)](/samples/ar-model-viewer)
- [AR Augmented Image](/samples/ar-augmented-image)
- [AR Cloud Anchors](/samples/ar-cloud-anchor)
- [AR Point Cloud](/samples/ar-point-cloud)

## Resources

### Documentation
- [3D API Reference](https://sceneview.github.io/api/sceneview-android/sceneview/)
- [AR API Reference](https://sceneview.github.io/api/sceneview-android/arsceneview/)

### Community
- [Website](https://sceneview.github.io/)
- [Discord](https://discord.gg/UbNDDBTNqb)
- [YouTube](https://www.youtube.com/results?search_query=Sceneview+android)

### Related Projects
- [Google Filament](https://github.com/google/filament)
- [Google ARCore](https://github.com/google-ar/arcore-android-sdk)

## Support the Project

### Ways to Contribute
- [Open Collective Donations](https://opencollective.com/sceneview/contribute/say-thank-you-ask-a-question-ask-for-features-and-fixes-33651)
- [GitHub Sponsorship](https://github.com/sponsors/ThomasGorisse)
- [Buy SceneView Merchandise](https://sceneview.threadless.com/designs/sceneview)
- Create a Pull Request

> ⚠️ **Geospatial API Note**: Be sure to follow the official [Google Geospatial Developer guide](https://developers.google.com/ar/develop/java/geospatial/developer-guide) to enable Geospatial API in your application.