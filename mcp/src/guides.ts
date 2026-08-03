/**
 * guides.ts
 *
 * Static content for the platform roadmap, best practices, and AR setup tools.
 */

import { LATEST_SCENEVIEW_RELEASE } from "./generated/version.js";

// ─── Platform Roadmap ─────────────────────────────────────────────────────────

export const PLATFORM_ROADMAP = `# SceneView Multi-Platform Roadmap

## Current Status (v${LATEST_SCENEVIEW_RELEASE})

| Platform | Status | Artifact | Renderer |
|----------|--------|----------|----------|
| **Android (Compose)** | Stable | \`io.github.sceneview:sceneview:${LATEST_SCENEVIEW_RELEASE}\` | Filament |
| **Android (AR)** | Stable | \`io.github.sceneview:arsceneview:${LATEST_SCENEVIEW_RELEASE}\` | Filament + ARCore |
| **iOS (SwiftUI)** | Alpha | SceneViewSwift SPM \`from: "${LATEST_SCENEVIEW_RELEASE}"\` | RealityKit + ARKit |
| **macOS (SwiftUI)** | Alpha | SceneViewSwift SPM (in Package.swift) | RealityKit |
| **visionOS (SwiftUI)** | Alpha | SceneViewSwift SPM (in Package.swift) | RealityKit |
| **KMP Core** | Stable | \`io.github.sceneview:sceneview-core:${LATEST_SCENEVIEW_RELEASE}\` | N/A (shared logic) |

## Architecture: Native Renderers per Platform

\`\`\`
Android: Filament (OpenGL ES / Vulkan)
Apple:   RealityKit (Metal)
Shared:  sceneview-core (KMP) — math, collision, geometry, animations, physics
\`\`\`

KMP shares **logic**, not **rendering**. Each platform uses its native renderer.

## Android — What Ships Today

- **3D rendering** via Google Filament: PBR materials, HDR environments, glTF/GLB models, post-processing.
- **AR** via ARCore: plane detection, hit testing, anchors, cloud anchors, augmented images, depth, light estimation, point cloud.
- **Compose-native DSL**: all nodes are \`@Composable\` functions inside \`SceneView { }\` or \`ARSceneView { }\`.
- **46+ node types**: ModelNode, LightNode, AnchorNode, CameraNode, TextNode, PathNode, ViewNode, PlaneNode, SphereNode, CylinderNode, CubeNode, DynamicSkyNode, FogNode, ReflectionProbeNode, PhysicsNode, BillboardNode, LineNode, and more.

## Apple — Alpha (SceneViewSwift)

- **3D + AR** in SwiftUI via RealityKit — iOS 17+ / macOS 14+ / visionOS 1+
- Node types: ModelNode, AnchorNode, GeometryNode, LightNode, CameraNode, ImageNode, VideoNode, PhysicsNode, AugmentedImageNode
- PBR material system with textures
- Swift Package Manager distribution
- Consumable by: Swift native, Flutter (PlatformView), React Native (Fabric), KMP Compose (UIKitView)

## KMP Core (\`sceneview-core\`)

Shared Kotlin Multiplatform module providing:
- Collision system (Ray, Box, Sphere, Intersections)
- Triangulation (Earcut, Delaunator)
- Geometry generation (Cube, Sphere, Cylinder, Plane, Path, Line, Shape)
- Animation (Spring, Property, Interpolation, SmoothTransform)
- Physics simulation
- Scene graph, math utilities

## Upcoming

- **v4.0.0**: SceneViewSwift stabilization, API parity with Android core nodes, KMP core XCFramework consumption
- **v4.0.0**: Android XR, visionOS spatial computing, cross-framework bridges (Flutter, React Native)

## How to Stay Updated

- **GitHub:** https://github.com/sceneview/sceneview
- **Releases:** https://github.com/sceneview/sceneview/releases
- **Website:** https://sceneview.github.io
`;

// ─── Best Practices ───────────────────────────────────────────────────────────

const PERFORMANCE_PRACTICES = `## Performance Best Practices

### Model Optimization
- **Use GLB over glTF** for production — GLB is a single binary file, faster to load than multi-file glTF.
- **Compress with Draco or Meshopt** — reduces model size by 70-90%. Filament supports both.
- **Limit polygon count** — aim for <100K triangles per model on mobile. Use LODs for complex scenes.
- **Texture size** — keep textures at 1024x1024 or smaller. Use KTX2 with Basis Universal compression.
- **Reduce draw calls** — merge meshes in your 3D editor. Each material = 1 draw call minimum.

### Runtime Performance
- **Reuse engines and loaders** — always use \`rememberEngine()\`, \`rememberModelLoader(engine)\`, etc. Creating multiple engines wastes GPU memory.
- **Limit simultaneous model loads** — \`rememberModelInstance\` is async, but loading 10+ models simultaneously can spike memory.
- **Use \`scaleToUnits\`** — avoids runtime bounding-box computation when you know the desired size.
- **Avoid per-frame allocations** — in \`onFrame\` callbacks, reuse objects instead of creating new Position/Rotation instances.
- **Profile with Android GPU Inspector** — Filament renders via OpenGL ES / Vulkan; AGI shows exactly where GPU time goes.

### Environment & Lighting
- **Use small HDR environments** — \`sky_2k.hdr\` (2K) is usually sufficient. 4K+ HDRs waste GPU memory on mobile.
- **Prefer \`ENVIRONMENTAL_HDR\`** for AR — gives the most realistic lighting but costs more. Use \`DISABLED\` if not needed.
- **Limit shadow-casting lights** — each shadow-casting light adds a depth render pass.
`;

const ARCHITECTURE_PRACTICES = `## Architecture Best Practices

### Compose Integration
- **Treat SceneView/ARSceneView like any Compose layout** — it participates in the Compose lifecycle. Don't fight it with imperative code.
- **State hoisting** — hoist anchor state, model selection, and UI state to the parent composable. The SceneView should be a pure renderer.
- **ViewModel for business logic** — keep AR session state (anchors, detected images) in a ViewModel. Pass it down to the SceneView composable.
- **Side effects** — use \`LaunchedEffect\` and \`DisposableEffect\` for async operations, not raw coroutines in composables.

### Project Structure
\`\`\`
app/
  src/main/
    assets/
      models/         # GLB/glTF files
      environments/   # HDR environment maps
      materials/      # Custom .filamat materials
    kotlin/
      ui/
        scene/
          SceneScreen.kt       # Compose screen with SceneView { }
          SceneViewModel.kt    # State management
        ar/
          ARScreen.kt          # Compose screen with ARSceneView { }
          ARViewModel.kt       # Anchor and session state
\`\`\`

### Error Handling
- **Always null-check \`rememberModelInstance\`** — it returns \`null\` while loading and if the asset fails to load.
- **Show loading indicators** — wrap the Scene with a loading overlay keyed on the model instance being null.
- **Handle AR session failures** — ARCore may not be installed or the device may not support AR. Check \`ArCoreApk.getInstance().checkAvailability(context)\`.
`;

const MEMORY_PRACTICES = `## Memory Management Best Practices

### Lifecycle Rules
- **Never call \`engine.destroy()\` manually** when using \`rememberEngine()\` — it handles cleanup on composition disposal.
- **Never call \`destroy()\` on nodes** created as composables — the composition lifecycle manages them.
- **Destroy order matters** for imperative code: MaterialInstance first, then Texture, then Engine. Reversing this causes SIGABRT.

### Reducing Memory Usage
- **Release unused model instances** — if you swap models, the old instance is released when recomposition removes its \`ModelNode\`.
- **Limit concurrent scenes** — each \`Scene\` composable creates its own Filament View and Renderer. Avoid multiple visible scenes.
- **Watch for Bitmap leaks** — if loading textures from Bitmaps, ensure they are recycled after Filament consumes them.
- **Use \`rememberEnvironment\`** — it caches the HDR environment and releases it on disposal. Don't load environments in \`LaunchedEffect\`.
`;

const THREADING_PRACTICES = `## Threading Best Practices

### Swift / RealityKit Threading Rules

**RealityKit is \`@MainActor\`-bound.** All entity mutations must happen on the main thread.

#### Safe Patterns (Swift)
- **In SwiftUI:** Use \`.task { }\` — it is \`@MainActor\`-isolated by default.
- **Standalone functions:** Annotate with \`@MainActor\` when modifying entities.
- **Model loading:** \`ModelNode.load()\` is \`async throws\` — call it with \`try await\` inside a \`.task { }\` block.

#### Anti-Patterns (Swift)
\`\`\`swift
// WRONG — detached task is not @MainActor
Task.detached {
    let model = try await ModelNode.load("car.usdz")
    model.entity.position = .init(x: 0, y: 1, z: 0) // Race condition!
}

// CORRECT — .task { } is @MainActor in SwiftUI
.task {
    do {
        let model = try await ModelNode.load("car.usdz")
        model.entity.position = .init(x: 0, y: 1, z: 0) // Safe — main actor
    } catch {
        print("Load failed: \\(error)")
    }
}
\`\`\`

---

### Android / Filament Threading Rules

### The Golden Rule
**All Filament JNI calls must run on the main thread.** This includes:
- \`modelLoader.createModelInstance()\`
- \`materialLoader.createMaterial()\`
- \`engine.createTexture()\`
- \`Texture.Builder().build(engine)\`
- Any \`engine.*\` call

### Safe Patterns
- **In composables:** Use \`rememberModelInstance(modelLoader, path)\` — it handles threading internally.
- **In ViewModels:** Use \`modelLoader.loadModelInstanceAsync(path)\` — it posts the JNI call to the main thread.
- **Manual threading:** Wrap in \`withContext(Dispatchers.Main) { }\`.

### Anti-Patterns
\`\`\`kotlin
// WRONG — Filament JNI call on IO thread → crash
viewModelScope.launch(Dispatchers.IO) {
    val model = modelLoader.createModelInstance(buffer) // SIGABRT!
}

// CORRECT — load data on IO, create model on Main
viewModelScope.launch(Dispatchers.IO) {
    val buffer = loadFromNetwork(url)
    withContext(Dispatchers.Main) {
        val model = modelLoader.createModelInstance(buffer) // Safe
    }
}
\`\`\`

### ARCore Threading
- \`Session.update()\` must run on the main thread (SceneView handles this via \`onSessionUpdated\`).
- \`hitResult.createAnchor()\` is safe on the main thread (called from \`onTouchEvent\` which runs on main).
- Cloud anchor hosting/resolving is async — SceneView's \`CloudAnchorNode\` handles the callbacks correctly.
`;

export const BEST_PRACTICES: Record<string, string> = {
  all: [
    "# SceneView Best Practices\n",
    PERFORMANCE_PRACTICES,
    ARCHITECTURE_PRACTICES,
    MEMORY_PRACTICES,
    THREADING_PRACTICES,
  ].join("\n---\n\n"),
  performance: `# SceneView Best Practices\n\n${PERFORMANCE_PRACTICES}`,
  architecture: `# SceneView Best Practices\n\n${ARCHITECTURE_PRACTICES}`,
  memory: `# SceneView Best Practices\n\n${MEMORY_PRACTICES}`,
  threading: `# SceneView Best Practices\n\n${THREADING_PRACTICES}`,
};

// ─── AR Setup Guide ───────────────────────────────────────────────────────────

// ─── Troubleshooting Guide ────────────────────────────────────────────────────

export const TROUBLESHOOTING_GUIDE = `# SceneView Troubleshooting Guide

## Common Crashes

### SIGABRT / Native Crash on Destroy
**Cause:** Destroying Filament resources in wrong order or from wrong thread.
**Fix:**
- Never call \`engine.destroy()\` manually when using \`rememberEngine()\`
- Never call \`node.destroy()\` on composable nodes — Compose handles lifecycle
- If using imperative API: destroy MaterialInstance before Texture, then Engine last
- All Filament calls must be on the main thread

### Model Not Showing / Invisible
**Check list:**
1. \`rememberModelInstance\` returns null while loading — are you null-checking it?
2. Is the model path correct? Assets go in \`src/main/assets/models/\`
3. Is \`scaleToUnits\` too small or too large? Try \`1.0f\`
4. Is there a light in the scene? Add a directional light: \`LightNode(apply = { intensity(100_000.0f); direction(0f, -1f, 0f) })\`
5. Is the camera pointing at the model? Default camera is at origin looking -Z

### AR Camera Feed Shows but No Planes Detected
**Fix:**
- Ensure camera permission is granted at runtime (not just in manifest)
- Point the device at a textured surface (plain white walls are hard to track)
- Check \`planeFindingMode\` is not \`DISABLED\`
- Wait 2-3 seconds for ARCore to initialize tracking

### "ARCore not installed" on Device
**Fix:**
- Ensure \`<meta-data android:name="com.google.ar.core" android:value="required" />\` is in manifest
- Install Google Play Services for AR from Play Store
- Some emulators don't support ARCore — test on a real device

### Build Fails: "Cannot find Filament material"
**Fix:**
- Pre-compiled materials are in \`src/main/assets/materials/\`
- Don't delete assets when cleaning — they're checked into the repository
- If \`filamentPluginEnabled=true\` in gradle.properties, you need the Filament desktop tools installed

### Gradle Sync Fails
**Fix:**
- Ensure Java 17 is set: \`compileOptions { sourceCompatibility = JavaVersion.VERSION_17 }\`
- Use compatible AGP/Gradle versions (AGP 8.7.3 + Gradle 8.11.1)
- Clear Gradle cache: \`./gradlew clean && rm -rf ~/.gradle/caches\`

## Performance Issues

### Low FPS / Jank
- Reduce model polygon count (< 100K triangles)
- Use KTX2 compressed textures (1024x1024 max)
- Avoid per-frame allocations in \`onFrame\` callbacks
- Disable post-processing if not needed: \`SceneView(postProcessing = false)\`
- Profile with Android GPU Inspector

### High Memory Usage
- Don't create multiple Engine instances — reuse \`rememberEngine()\`
- Limit concurrent model loads (max 3-4 simultaneously)
- Use smaller HDR environments (2K, not 4K)
- Release unused environments: let them leave composition

## AR-Specific Issues

### Anchor Drift / Objects Moving
- Use \`AnchorNode\` instead of setting \`worldPosition\` manually
- ARCore anchors are world-locked; plain nodes follow the coordinate system which shifts during tracking
- For persistent anchors across sessions, use \`CloudAnchorNode\`

### AR Objects Too Bright / Overexposed
- Set \`toneMapper = ToneMapper.Linear\` on the AR scene view
- Default tone mapping enhances contrast which looks wrong on the camera feed

### Augmented Images Not Detected
- Image must be >= 300x300 pixels with good contrast and detail
- Specify physical width when registering: \`addImage("name", bitmap, 0.15f)\` (meters)
- Only one image database per session — add all images before starting

---

## iOS-Specific Issues (SceneViewSwift)

### Model Not Showing in SceneView
**Check list:**
1. Is the model format USDZ or .reality? RealityKit does not support GLB/glTF natively.
2. Is \`ModelNode.load()\` called with \`try await\`? It's async — if you forget \`await\`, the model won't load.
3. Are you adding \`.entity\` (not the wrapper)? \`root.addChild(model.entity)\`, not \`root.addChild(model)\`.
4. Check the model path — it must be in the app bundle (add to Xcode project, ensure "Copy Bundle Resources" includes it).
5. Is the model too large or too small? Try \`.scaleToUnits(1.0)\`.

### AR Camera Shows Black Screen (iOS)
**Fix:**
- Check Info.plist has \`NSCameraUsageDescription\` — without it the app crashes or shows a black screen.
- Ensure the device supports AR: \`ARWorldTrackingConfiguration.isSupported\`.
- Test on a real device — the simulator does not support ARKit camera.

### ARSceneView Crash on macOS / visionOS
**Fix:**
- \`ARSceneView\` uses \`ARView\` which is iOS-only. Use \`SceneView\` for 3D on macOS/visionOS.
- For visionOS AR, use \`ARKitSession\` with \`RealityView\` directly.

### Swift Concurrency Warnings
**Fix:**
- If you get "Non-sendable type" warnings, note that SceneViewSwift node types are marked \`@unchecked Sendable\` — RealityKit entities are main-actor-bound.
- Always load models inside \`.task { }\` (which is \`@MainActor\`) or annotate functions with \`@MainActor\`.

### SPM Package Resolution Fails
**Fix:**
- Ensure Xcode 15.0+ (required for iOS 17 / visionOS targets).
- Clean derived data: Xcode → Product → Clean Build Folder, then File → Packages → Reset Package Caches.
- Check the URL is exactly: \`https://github.com/sceneview/sceneview\`

### Image Tracking Not Working (iOS)
**Fix:**
- Reference images need high contrast and detail — avoid solid colors or simple patterns.
- Specify \`physicalWidth\` in meters when creating the reference image.
- Only one set of detection images per AR session configuration.
- The image must be physically present and visible to the camera — screenshots don't track well.
`;

export const AR_SETUP_GUIDE = `# SceneView AR — Complete Setup Guide (Android + iOS)

---

# iOS AR Setup (ARKit + RealityKit)

## 1. SPM Dependency

\`\`\`swift
.package(url: "https://github.com/sceneview/sceneview", from: "${LATEST_SCENEVIEW_RELEASE}")
\`\`\`

## 2. Info.plist — Camera Permission

\`\`\`xml
<key>NSCameraUsageDescription</key>
<string>This app uses the camera for augmented reality.</string>
\`\`\`

Without this entry, the app will crash on camera access.

## 3. Minimum Platform

AR requires **iOS 17.0+**. ARKit is not available on macOS or visionOS via \`ARSceneView\` (visionOS uses \`ARKitSession\` directly).

## 4. Basic AR Template

\`\`\`swift
import SwiftUI
import SceneViewSwift
import RealityKit

struct MyARView: View {
    @State private var model: ModelNode?

    var body: some View {
        ARSceneView(
            planeDetection: .horizontal,
            showCoachingOverlay: true,
            onTapOnPlane: { position, arView in
                guard let model else { return }
                let anchor = AnchorNode.world(position: position)
                anchor.add(model.entity.clone(recursive: true))
                arView.scene.addAnchor(anchor.entity)
            }
        )
        .edgesIgnoringSafeArea(.all)
        .task {
            model = try? await ModelNode.load("models/object.usdz")
            model?.scaleToUnits(0.3)
        }
    }
}
\`\`\`

## 5. AR Configuration Options

| Parameter | Options | Default |
|-----------|---------|---------|
| \`planeDetection\` | \`.none\`, \`.horizontal\`, \`.vertical\`, \`.both\` | \`.horizontal\` |
| \`showPlaneOverlay\` | \`true\` / \`false\` | \`true\` |
| \`showCoachingOverlay\` | \`true\` / \`false\` | \`true\` |
| \`imageTrackingDatabase\` | \`Set<ARReferenceImage>?\` | \`nil\` |

## 6. Image Tracking (iOS)

\`\`\`swift
let images = AugmentedImageNode.createImageDatabase([
    AugmentedImageNode.ReferenceImage(
        name: "poster",
        image: UIImage(named: "poster_ref")!,
        physicalWidth: 0.3
    )
])

ARSceneView(
    imageTrackingDatabase: images,
    onImageDetected: { name, anchor, arView in
        let cube = GeometryNode.cube(size: 0.1, color: .blue)
        anchor.add(cube.entity)
        arView.scene.addAnchor(anchor.entity)
    }
)
\`\`\`

---

# Android AR Setup (ARCore + Filament)

## 1. Gradle Dependencies

\`\`\`kotlin
// build.gradle.kts (app module)
dependencies {
    implementation("io.github.sceneview:arsceneview:${LATEST_SCENEVIEW_RELEASE}")
    // arsceneview includes sceneview transitively — no need to add both
}
\`\`\`

## 2. AndroidManifest.xml

\`\`\`xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Required: camera access for AR -->
    <uses-permission android:name="android.permission.CAMERA" />

    <!-- Required: declares AR hardware dependency -->
    <uses-feature android:name="android.hardware.camera.ar" android:required="true" />

    <!-- Optional: internet for cloud anchors -->
    <uses-permission android:name="android.permission.INTERNET" />

    <!-- Required ONLY if you use Geospatial / Streetscape / Cloud Anchors.
         Geospatial mode throws FineLocationPermissionNotGrantedException at
         configure() time without this — request at runtime BEFORE mounting
         ARSceneView (see Cloud setup section below). -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

    <application>
        <!-- Required: tells Play Store this app needs ARCore -->
        <!-- Use "required" to block installs on non-AR devices -->
        <!-- Use "optional" to allow install but degrade gracefully -->
        <meta-data android:name="com.google.ar.core" android:value="required" />

        <!-- Required ONLY for Cloud Anchors / Geospatial / Streetscape.
             Inject via manifestPlaceholders in app/build.gradle (read from
             ARCORE_API_KEY env var or local.properties — never hardcode). -->
        <meta-data android:name="com.google.android.ar.API_KEY"
                   android:value="\${arcoreApiKey}" />
    </application>
</manifest>
\`\`\`

### ARCore Cloud API key — needed for Cloud Anchors / Geospatial / Streetscape

The three Cloud features above hit Google's ARCore backend. Without a configured key, plain
plane-finding / hit-testing / face mesh / image detection still work — only Cloud features fail.

Setup once on a Google Cloud project:

1. Enable the ARCore API: <https://console.cloud.google.com/apis/library/arcore.googleapis.com>
2. Activate billing (Geospatial endpoints are paid; free tier is generous for dev/test).
3. Create an API key restricted to your Android package + signing SHA-1.

Inject the key at build time via \`app/build.gradle\` (key never committed):

\`\`\`groovy
android.defaultConfig {
    def key = System.getenv("ARCORE_API_KEY") ?: ""
    if (key.isEmpty()) {
        def f = rootProject.file("local.properties")
        if (f.exists()) {
            def p = new Properties(); f.withInputStream { p.load(it) }
            key = p.getProperty("ARCORE_API_KEY", "")
        }
    }
    manifestPlaceholders["arcoreApiKey"] = key
}
\`\`\`

### AR Required vs Optional

| Value | Behavior |
|-------|----------|
| \`"required"\` | App only visible in Play Store on ARCore-supported devices. ARCore auto-installed. |
| \`"optional"\` | App visible to all devices. You must check ARCore availability at runtime. |

For optional AR, check availability before showing AR features:
\`\`\`kotlin
val availability = ArCoreApk.getInstance().checkAvailability(context)
if (availability.isSupported) { /* show AR */ }
\`\`\`

## 3. Session Configuration Options

\`\`\`kotlin
ARSceneView(
    engine = engine,
    modelLoader = modelLoader,
    sessionConfiguration = { session, config ->

        // ── Depth ─────────────────────────────────────────────
        // Enables occlusion (virtual objects hidden behind real ones)
        config.depthMode =
            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                Config.DepthMode.AUTOMATIC
            else Config.DepthMode.DISABLED

        // ── Light Estimation ──────────────────────────────────
        // ENVIRONMENTAL_HDR: most realistic (reflects real lighting on models)
        // AMBIENT_INTENSITY: lighter weight, just brightness + color temp
        // DISABLED: no lighting adjustment
        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

        // ── Plane Detection ───────────────────────────────────
        // HORIZONTAL: floors, tables
        // VERTICAL: walls
        // HORIZONTAL_AND_VERTICAL: both
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL

        // ── Instant Placement ─────────────────────────────────
        // Allows placing objects before planes are fully detected
        config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP

        // ── Cloud Anchors ─────────────────────────────────────
        // Required for cross-device persistent anchors
        config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED

        // ── Augmented Images ──────────────────────────────────
        // Register reference images for detection
        val imageDb = AugmentedImageDatabase(session)
        imageDb.addImage("my-image", bitmap, 0.15f) // 15cm physical width
        config.augmentedImageDatabase = imageDb

        // ── Focus Mode ────────────────────────────────────────
        config.focusMode = Config.FocusMode.AUTO
    }
) {
    // AR content goes here
}
\`\`\`

## 4. Common AR Patterns

### Tap-to-Place with Anchor
\`\`\`kotlin
var anchor by remember { mutableStateOf<Anchor?>(null) }

ARSceneView(
    engine = engine,
    modelLoader = modelLoader,
    planeRenderer = true,
    onTouchEvent = { event, hitResult ->
        if (event.action == MotionEvent.ACTION_UP && hitResult != null) {
            anchor = hitResult.createAnchor()
        }
        true
    }
) {
    anchor?.let { a ->
        AnchorNode(anchor = a) {
            modelInstance?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = 0.5f,
                    isEditable = true  // pinch-to-scale + drag-to-rotate
                )
            }
        }
    }
}
\`\`\`

### Surface Cursor (HitResultNode)
\`\`\`kotlin
ARSceneView(
    engine = engine,
    modelLoader = modelLoader,
    planeRenderer = true
) {
    HitResultNode(engine = engine) {
        modelInstance?.let { instance ->
            ModelNode(modelInstance = instance, scaleToUnits = 0.3f)
        }
    }
}
\`\`\`

### Augmented Image Detection
\`\`\`kotlin
var images by remember { mutableStateOf<Map<String, AugmentedImage>>(emptyMap()) }

ARSceneView(
    engine = engine,
    sessionConfiguration = { session, config ->
        config.addAugmentedImage(session, "poster", posterBitmap)
    },
    onSessionUpdated = { _, frame ->
        frame.getUpdatedAugmentedImages().forEach { img ->
            images = images + (img.name to img)
        }
    }
) {
    for ((_, image) in images) {
        AugmentedImageNode(augmentedImage = image) {
            ModelNode(modelInstance = instance, scaleToUnits = 0.1f)
        }
    }
}
\`\`\`

## 5. Permissions

Camera permission must be requested at runtime (Android 6.0+). SceneView does **not** handle this — you must request it before showing ARSceneView:

\`\`\`kotlin
val cameraPermission = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
) { granted -> if (granted) showAR = true }

LaunchedEffect(Unit) {
    cameraPermission.launch(Manifest.permission.CAMERA)
}

if (showAR) {
    ARSceneView(engine = engine, modelLoader = modelLoader) { /* ... */ }
}
\`\`\`
`;
