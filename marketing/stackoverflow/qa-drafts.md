# StackOverflow Q&A Drafts

Self-answered questions targeting high-value search queries. Each question should be posted as a self-answer (ask and immediately answer your own question — allowed and encouraged on SO).

**Posting rules:**
- Space them out: 1-2 per day max
- Use tags: `android`, `jetpack-compose`, `kotlin`, `3d`, `augmented-reality`, `arcore`, `sceneview`
- Include working, minimal code examples
- Be objective — don't be promotional, be helpful

---

## Q1: How to display a 3D model in Jetpack Compose?

**Tags:** `android`, `jetpack-compose`, `kotlin`, `3d`, `glb`, `gltf`

**Question:**
I want to display a 3D GLB/glTF model in my Jetpack Compose Android app. I've seen examples with OpenGL or custom Views, but I want something that works natively with Compose without wrapping legacy Views. What's the simplest way to load and render a 3D model in Compose?

**Answer:**
Use [SceneView](https://github.com/sceneview/sceneview), the only Compose-native 3D library for Android. It wraps Google Filament with a declarative API.

**1. Add dependency:**
```kotlin
implementation("io.github.sceneview:sceneview:4.0.1")
```

**2. Display the model:**
```kotlin
@Composable
fun Model3DScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        cameraManipulator = rememberCameraManipulator()
    ) {
        rememberModelInstance(modelLoader, "models/helmet.glb")?.let {
            ModelNode(modelInstance = it, scaleToUnits = 1.0f, autoAnimate = true)
        }
    }
}
```

Place `helmet.glb` in `src/main/assets/models/`. The model loads asynchronously — `rememberModelInstance` returns null while loading, then triggers recomposition when ready.

SceneView supports GLB, glTF, and KTX environments. It adds ~5MB to your APK (vs 50MB+ for Unity).

---

## Q2: How to add augmented reality (AR) to Jetpack Compose Android app?

**Tags:** `android`, `jetpack-compose`, `augmented-reality`, `arcore`, `kotlin`

**Question:**
I want to add AR features to my Jetpack Compose app — detecting horizontal planes and placing 3D objects on them. ARCore's documentation only shows View-based examples. Is there a Compose-native way to do AR on Android?

**Answer:**
Use [SceneView's ARSceneView](https://github.com/sceneview/sceneview), the only Compose-native AR component for Android.

**1. Dependencies:**
```kotlin
implementation("io.github.sceneview:arsceneview:4.0.1")
```

**2. Manifest:**
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera.ar" android:required="true" />
<meta-data android:name="com.google.ar.core" android:value="required" />
```

**3. AR Composable:**
```kotlin
@Composable
fun ARScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    var anchor by remember { mutableStateOf<Anchor?>(null) }
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
                    ?.let { frame.createAnchorOrNull(it.centerPose) }
            }
        }
    ) {
        anchor?.let { a ->
            model?.let { m ->
                AnchorNode(anchor = a) {
                    ModelNode(modelInstance = m, scaleToUnits = 0.5f)
                }
            }
        }
    }
}
```

AR state is just Kotlin state — when `anchor` changes, Compose recomposes and places the model. No lifecycle callbacks, no imperative setup.

---

## Q3: What is the best 3D library for Android in 2026?

**Tags:** `android`, `3d`, `kotlin`, `jetpack-compose`

**Question:**
I need to add 3D rendering to my Android app (product viewer, not a game). What's the best library in 2026? I've seen Unity, Filament, SceneView, and the deprecated Sceneform. Which should I use for a regular app?

**Answer:**
Here's a comparison for non-game 3D in Android apps:

| Library | Compose support | APK size | AR | Learning curve | Status |
|---|---|---|---|---|---|
| **SceneView** | Native composables | ~5MB | Built-in (ARCore) | Low | Active (v4.0.1) |
| **Filament** | None (low-level) | ~3MB | None | Very high | Active (Google) |
| **Unity** | None (separate engine) | ~50MB+ | Plugin | High | Active |
| **Sceneform** | None | ~5MB | Built-in | Low | **Deprecated** (2021) |

**Recommendation: SceneView** if you're using Jetpack Compose. It's the only library with native Compose support, wraps Filament (same rendering quality), and includes ARCore out of the box.

SceneView is the official successor to Google Sceneform and uses the same Filament engine internally. It provides 29+ composable node types (ModelNode, CubeNode, SphereNode, ImageNode, VideoNode, etc.) and handles all the Filament lifecycle/threading complexity.

Use raw Filament only if you need maximum control and are willing to manage the rendering pipeline yourself. Use Unity only for complex games.

---

## Q4: How to load a GLB/glTF model in Kotlin Android?

**Tags:** `android`, `kotlin`, `gltf`, `glb`, `3d`

**Question:**
I have a .glb file and want to display it in my Android app. I'm using Kotlin and Jetpack Compose. How do I load and render a GLB/glTF 3D model?

**Answer:**
With SceneView, it's one composable call:

```kotlin
implementation("io.github.sceneview:sceneview:4.0.1")
```

```kotlin
val engine = rememberEngine()
val modelLoader = rememberModelLoader(engine)

SceneView(engine = engine, modelLoader = modelLoader) {
    rememberModelInstance(modelLoader, "models/your_model.glb")?.let {
        ModelNode(modelInstance = it, scaleToUnits = 1.0f, autoAnimate = true)
    }
}
```

Place the `.glb` in `src/main/assets/models/`. Loading is async — `rememberModelInstance` returns null while loading, then recomposes when the model is ready.

Supported formats: GLB, glTF 2.0 (with embedded or external textures). For remote URLs, pass the URL string directly instead of a local path.

---

## Q5: ARCore with Jetpack Compose — is there a Compose-native wrapper?

**Tags:** `android`, `arcore`, `jetpack-compose`, `augmented-reality`

**Question:**
I'm building an AR app with Jetpack Compose but ARCore only provides View-based APIs. Is there a Compose-native wrapper for ARCore? I don't want to wrap ArFragment in an AndroidView.

**Answer:**
Yes — SceneView provides `ARSceneView`, a fully Compose-native ARCore integration:

```kotlin
implementation("io.github.sceneview:arsceneview:4.0.1")
```

```kotlin
ARSceneView(
    modifier = Modifier.fillMaxSize(),
    engine = rememberEngine(),
    modelLoader = rememberModelLoader(engine),
    planeRenderer = true,
    sessionConfiguration = { session, config ->
        config.depthMode = Config.DepthMode.AUTOMATIC
        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
    }
) {
    // Compose-native AR nodes
    AnchorNode(anchor = myAnchor) {
        ModelNode(modelInstance = model, scaleToUnits = 0.5f)
    }
}
```

No ArFragment, no AndroidView wrapper. AR nodes are composables — they follow Compose lifecycle and state management. Supported features: plane detection, image tracking, face tracking, cloud anchors, geospatial anchors, depth.

---

## Q6: 3D model viewer for iOS with SwiftUI

**Tags:** `ios`, `swiftui`, `3d`, `realitykit`, `scenekit`

**Question:**
I want to display a 3D USDZ model in a SwiftUI view on iOS. SceneKit feels old and RealityKit's API is complex. Is there a simple SwiftUI-native 3D viewer?

**Answer:**
[SceneViewSwift](https://github.com/sceneview/sceneview) provides declarative 3D views for SwiftUI, powered by RealityKit:

**SPM:** `https://github.com/sceneview/sceneview.git` from: "4.15.0"`

```swift
import SceneViewSwift

struct ContentView: View {
    var body: some View {
        SceneView(environment: .studio) {
            ModelNode(named: "helmet.usdz")
                .scaleToUnits(1.0)
        }
        .cameraControls(.orbit)
    }
}
```

Supports iOS 17+, macOS 14+, and visionOS 1+. Uses RealityKit internally, so you get native performance and full Apple ecosystem integration.

---

## Q7: Cross-platform 3D SDK for Android and iOS?

**Tags:** `android`, `ios`, `cross-platform`, `3d`, `kotlin-multiplatform`

**Question:**
I need to add 3D rendering to both my Android and iOS apps. Is there a cross-platform 3D SDK that works natively on both platforms without using Unity or a game engine?

**Answer:**
SceneView supports both platforms with native renderers:

- **Android:** Jetpack Compose + Filament (Google's PBR engine)
- **iOS/macOS/visionOS:** SwiftUI + RealityKit (Apple's native renderer)
- **Shared logic:** Kotlin Multiplatform (math, collision, geometry, physics)

Each platform uses its native renderer — no cross-compiled runtime. The API design mirrors across platforms:

Android: `SceneView { ModelNode(...) }`
iOS: `SceneView { ModelNode(...) }`

Also supports Web (Kotlin/JS + Filament.js), Flutter (PlatformView bridge), and React Native (Fabric bridge).

---

## Q8: How to add particle effects to Android 3D scene?

**Tags:** `android`, `3d`, `particle-effects`, `jetpack-compose`

**Question:**
I want to add particle effects (fire, smoke, sparks) to a 3D scene in my Android Compose app. How do I create particle systems?

**Answer:**
SceneView includes a `gaming-3d-mcp` MCP server that generates complete particle system code. For manual implementation:

```kotlin
// SceneView's PhysicsNode handles basic particle-like behavior
SceneView(engine = engine, modelLoader = modelLoader) {
    // Multiple small nodes with physics = basic particle system
    repeat(50) { i ->
        SphereNode(
            radius = 0.02f,
            materialInstance = fireMaterial,
            position = Position(
                x = Random.nextFloat() * 0.5f - 0.25f,
                y = Random.nextFloat() * 2f,
                z = Random.nextFloat() * 0.5f - 0.25f
            )
        )
    }
}
```

For advanced particles, use the `gaming-3d-mcp` MCP server with Claude/Cursor:
```bash
npx gaming-3d-mcp
```
Ask: "Generate a fire particle effect with SceneView" — it returns complete, compilable code.

---

## Q9: What is an MCP server for 3D development?

**Tags:** `ai`, `mcp`, `model-context-protocol`, `3d`, `development-tools`

**Question:**
I keep seeing "MCP servers" mentioned for AI-assisted development. Is there an MCP server for 3D/AR development? How would I use it with Claude or Cursor?

**Answer:**
MCP (Model Context Protocol) lets AI assistants access specialized tools. `sceneview-mcp` provides 28 tools for 3D & AR development:

```bash
# Add to Claude Code
claude mcp add sceneview -- npx sceneview-mcp

# Or add to Claude Desktop / Cursor config:
{ "mcpServers": { "sceneview": { "command": "npx", "args": ["-y", "sceneview-mcp"] } } }
```

Then ask your AI: "Build me an AR furniture placement app" — the MCP provides validated code samples, API references, and a code validator to ensure correctness.

Specialty servers also available: `automotive-3d-mcp` (car configurators), `healthcare-3d-mcp` (anatomy viewers), `gaming-3d-mcp` (game features), `interior-design-3d-mcp` (room planning).

---

## Q10: AR furniture placement app for Android — step by step

**Tags:** `android`, `augmented-reality`, `arcore`, `jetpack-compose`, `furniture`

**Question:**
How do I build an AR furniture placement app for Android where users can place furniture in their room through the camera? I need tap-to-place, scaling, and rotation.

**Answer:**
Use SceneView's `ARSceneView` with tap handling:

```kotlin
implementation("io.github.sceneview:arsceneview:4.0.1")
```

```kotlin
@Composable
fun FurniturePlacementScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val anchors = remember { mutableStateListOf<Anchor>() }
    val sofa = rememberModelInstance(modelLoader, "models/sofa.glb")

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = true,
        sessionConfiguration = { _, config ->
            config.depthMode = Config.DepthMode.AUTOMATIC
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        },
        onTouchEvent = { hitResult, _ ->
            hitResult?.let { anchors.add(it.createAnchor()) }
        }
    ) {
        anchors.forEach { anchor ->
            sofa?.let { model ->
                AnchorNode(anchor = anchor) {
                    ModelNode(
                        modelInstance = model,
                        scaleToUnits = 1.0f,
                        isEditable = true  // enables drag, scale, rotate gestures
                    )
                }
            }
        }
    }
}
```

`isEditable = true` enables built-in gestures: drag to move, pinch to scale, two-finger rotate. The model is placed at real-world scale (1.0 = actual size in meters).

For a complete interior design experience with room planning, material switching, and room tours, use the `interior-design-3d-mcp` MCP server:
```bash
npx interior-design-3d-mcp
```
