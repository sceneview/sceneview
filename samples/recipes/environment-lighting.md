# Recipe: Environment Lighting (HDR/IBL)

**Intent:** "Set up realistic lighting with an HDR environment"

## Android (Kotlin + Jetpack Compose)

```kotlin
@Composable
fun ModelWithEnvironment() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val model = rememberModelInstance(modelLoader, "models/helmet.glb")

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        // HDR environment provides both indirect lighting (IBL) and skybox
        environment = rememberEnvironment(environmentLoader) {
            environmentLoader.createHDREnvironment("environments/studio_2k.hdr")
                ?: createEnvironment(environmentLoader)
        },
        cameraManipulator = rememberCameraManipulator()
    ) {
        model?.let {
            ModelNode(modelInstance = it, scaleToUnits = 1.0f)
        }

        // Optional: add a directional light for sharper shadows
        LightNode(
            type = LightManager.Type.DIRECTIONAL,
            intensity = 100_000f,
            direction = Direction(0f, -1f, -1f),
            apply = { castShadows(true) }   // Filament-builder extras go in apply
        )
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
    let sun = LightNode.directional(intensity: 1000)
        .position(.init(x: 0, y: 2, z: 2))
        .lookAt(.zero)               // aim via position + lookAt (no .direction modifier)
    root.addChild(sun.entity)
}
.environment(.studio)
.cameraControls(.orbit)
.task {
    model = try? await ModelNode.load("helmet.usdz")
    model?.scaleToUnits(1.0)
}
```

## visionOS — immersive-space skybox

A windowed or volumetric `RealityView` on visionOS composites over
passthrough, so the HDR `showSkybox` only lights the scene — the background
stays passthrough. A fully immersive `ImmersiveSpace` *can* host an HDR
background: opt in with `.immersiveSpace()` and the HDR is rendered as a
`WorldComponent`-rooted inverted sphere.

```swift
@main
struct MyApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }

        ImmersiveSpace(id: "scene") {
            SceneView { root in
                root.addChild(model.entity)
            }
            .environment(.nightSky)   // showSkybox == true
            .immersiveSpace()         // render the HDR skybox on visionOS
        }
        .immersionStyle(selection: .constant(.full), in: .full)
    }
}
```

`.immersiveSpace()` is a no-op on iOS / macOS — those use the windowed
`.skybox(_:)` path automatically.

## Available Environments

These are the HDR files bundled with `android-demo` under
`src/main/assets/environments/`:

| Environment | Description | Best for |
|---|---|---|
| `studio_2k.hdr` | Neutral studio lighting | Product shots, model viewers |
| `studio_warm_2k.hdr` | Warm studio lighting | Product shots with a warmer tone |
| `outdoor_cloudy_2k.hdr` | Overcast outdoor daylight | Architectural scenes |
| `chinese_garden_2k.hdr` | Outdoor garden | Natural / scenic scenes |
| `sunset_2k.hdr` | Warm golden hour | Atmospheric scenes |
| `rooftop_night_2k.hdr` | City rooftop at night | Dramatic urban lighting |
| `night_sky_2k.hdr` | Dark night sky | Dramatic, low-key lighting |

## Key Points

- `rememberEnvironment(environmentLoader) { environmentLoader.createHDREnvironment("environments/studio_2k.hdr") ?: createEnvironment(environmentLoader) }` loads the HDR file from assets (falling back to the neutral default environment if the load fails)
- HDR environments provide **Image-Based Lighting** (IBL) for realistic reflections
- They also set the skybox (background) — pass `createSkybox = false` to `createHDREnvironment` to keep a solid background
- Combine IBL with `LightNode` for direct light sources (sun, lamps)
- `LightNode`'s `apply` is a **named parameter**, not a trailing lambda: `apply = { ... }`
- All HDR files should be in `src/main/assets/environments/` for Android
