/**
 * platform-setup.ts
 *
 * Setup guides for every supported SceneView platform.
 * Consolidates Android, iOS, Web, Flutter, React Native, Desktop, and TV.
 */

import { LATEST_FLUTTER_PUB_RELEASE, LATEST_SCENEVIEW_RELEASE } from "./generated/version.js";

export type Platform = "android" | "ios" | "web" | "flutter" | "react-native" | "desktop" | "tv";
export type SetupType = "3d" | "ar";

export const PLATFORM_IDS: Platform[] = ["android", "ios", "web", "flutter", "react-native", "desktop", "tv"];

interface PlatformSetup {
  name: string;
  renderer: string;
  status: string;
  guide3d: string;
  guideAr: string | null;
}

const ANDROID_3D = `## SceneView Android — 3D Setup

### 1. Gradle Dependencies

\`\`\`kotlin
// build.gradle.kts (app module)
dependencies {
    implementation("io.github.sceneview:sceneview:${LATEST_SCENEVIEW_RELEASE}")
}
\`\`\`

### 2. Minimum SDK

\`\`\`kotlin
android {
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
\`\`\`

### 3. Basic 3D Scene

\`\`\`kotlin
@Composable
fun My3DScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        environment = rememberEnvironment(environmentLoader) {
            environmentLoader.createHDREnvironment("environments/sky_2k.hdr")
                ?: createEnvironment(environmentLoader)
        }
    ) {
        rememberModelInstance(modelLoader, "models/chair.glb")?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 1.0f
            )
        }

        LightNode(
            type = LightManager.Type.DIRECTIONAL,
            apply = {
                intensity(100_000f)
                castShadows(true)
            }
        )
    }
}
\`\`\`

### 4. Asset Location

Place 3D assets in \`src/main/assets/\`:
\`\`\`
app/src/main/assets/
  models/         # GLB/glTF files
  environments/   # HDR environment maps
  materials/      # Custom .filamat materials
\`\`\`

### 5. No Manifest Changes Required

3D-only scenes need no special permissions or manifest entries.`;

const ANDROID_AR = `## SceneView Android — AR Setup

### 1. Gradle Dependencies

\`\`\`kotlin
// build.gradle.kts (app module)
dependencies {
    implementation("io.github.sceneview:arsceneview:${LATEST_SCENEVIEW_RELEASE}")
    // arsceneview includes sceneview transitively
}
\`\`\`

### 2. AndroidManifest.xml

\`\`\`xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera.ar" android:required="true" />

<application>
    <meta-data android:name="com.google.ar.core" android:value="required" />
</application>
\`\`\`

### 3. Camera Permission at Runtime

\`\`\`kotlin
val cameraPermission = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
) { granted -> if (granted) showAR = true }

LaunchedEffect(Unit) {
    cameraPermission.launch(Manifest.permission.CAMERA)
}
\`\`\`

### 4. Basic AR Scene

\`\`\`kotlin
@Composable
fun MyARScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    var anchor by remember { mutableStateOf<Anchor?>(null) }

    val modelInstance = rememberModelInstance(modelLoader, "models/robot.glb")

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        planeRenderer = true,
        sessionConfiguration = { session, config ->
            config.depthMode =
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                    Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        },
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
                        isEditable = true
                    )
                }
            }
        }
    }
}
\`\`\`

### 5. Session Configuration Options

| Option | Values | Default |
|--------|--------|---------|
| \`depthMode\` | \`DISABLED\`, \`AUTOMATIC\` | \`DISABLED\` |
| \`lightEstimationMode\` | \`DISABLED\`, \`AMBIENT_INTENSITY\`, \`ENVIRONMENTAL_HDR\` | \`ENVIRONMENTAL_HDR\` |
| \`planeFindingMode\` | \`DISABLED\`, \`HORIZONTAL\`, \`VERTICAL\`, \`HORIZONTAL_AND_VERTICAL\` | \`HORIZONTAL_AND_VERTICAL\` |
| \`instantPlacementMode\` | \`DISABLED\`, \`LOCAL_Y_UP\` | \`DISABLED\` |
| \`cloudAnchorMode\` | \`DISABLED\`, \`ENABLED\` | \`DISABLED\` |`;

const IOS_3D = `## SceneViewSwift — iOS/macOS/visionOS 3D Setup

### 1. SPM Dependency

In Xcode: **File > Add Package Dependencies** > paste:
\`\`\`
https://github.com/sceneview/sceneview
\`\`\`
Set version rule to **"from: ${LATEST_SCENEVIEW_RELEASE}"**.

Or in Package.swift:
\`\`\`swift
// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "MyApp",
    platforms: [.iOS(.v18), .macOS(.v15), .visionOS(.v1)],
    dependencies: [
        .package(url: "https://github.com/sceneview/sceneview", from: "${LATEST_SCENEVIEW_RELEASE}")
    ],
    targets: [
        .executableTarget(
            name: "MyApp",
            dependencies: [
                .product(name: "SceneViewSwift", package: "sceneview")
            ]
        )
    ]
)
\`\`\`

### 2. Minimum Platform Versions

| Platform | Minimum |
|----------|---------|
| iOS      | 18.0    |
| macOS    | 15.0    |
| visionOS | 1.0     |

### 3. Basic SwiftUI Integration

\`\`\`swift
import SwiftUI
import SceneViewSwift
import RealityKit

struct ContentView: View {
    @State private var model: ModelNode?

    var body: some View {
        SceneView { root in
            if let model {
                root.addChild(model.entity)
            }
        }
        .cameraControls(.orbit)
        .task {
            do {
                model = try await ModelNode.load("models/car.usdz")
                model?.scaleToUnits(1.0)
            } catch {
                print("Failed to load model: \\(error)")
            }
        }
    }
}
\`\`\`

### 4. Model Formats

| Format | Support |
|--------|---------|
| USDZ   | Native — recommended for iOS |
| .reality | Native — RealityKit format |
| glTF/GLB | Not natively supported (use Android/Web) |

### 5. Available Node Types

SceneViewSwift provides 16 node types:

| Node | Purpose |
|------|---------|
| \`ModelNode\` | Load and display 3D models (USDZ/.reality) |
| \`GeometryNode\` | Procedural shapes (cube, sphere, cylinder, cone, plane) |
| \`LightNode\` | Directional, point, and spot lights |
| \`TextNode\` | Extruded 3D text labels |
| \`BillboardNode\` | Camera-facing nodes (labels, health bars, icons) |
| \`VideoNode\` | Video playback on a 3D plane |
| \`ImageNode\` | Image display on a 3D plane |
| \`LineNode\` | Line segments between 3D points |
| \`PathNode\` | Connected line paths through multiple points |
| \`MeshNode\` | Custom mesh geometry with vertex data |
| \`PhysicsNode\` | Rigid-body physics (dynamic, static, kinematic) |
| \`DynamicSkyNode\` | Time-of-day sun simulation |
| \`FogNode\` | Atmospheric fog effects |
| \`ReflectionProbeNode\` | Local environment reflection captures |
| \`CameraNode\` | Programmatic camera control |
| \`AugmentedImageNode\` | AR image detection and tracking (iOS only) |`;

const IOS_AR = `## SceneViewSwift — iOS AR Setup

### 1. SPM Dependency

\`\`\`swift
.package(url: "https://github.com/sceneview/sceneview", from: "${LATEST_SCENEVIEW_RELEASE}")
\`\`\`

### 2. Info.plist — Camera Permission (Required)

\`\`\`xml
<key>NSCameraUsageDescription</key>
<string>This app uses the camera for augmented reality.</string>
\`\`\`

### 3. Minimum Platform

AR requires **iOS 18.0+**. Not available on macOS or visionOS via \`ARSceneView\`.

### 4. Basic AR Integration

\`\`\`swift
import SwiftUI
import SceneViewSwift
import RealityKit

struct ARContentView: View {
    @State private var model: ModelNode?

    var body: some View {
        ARSceneView(
            planeDetection: .horizontal,
            showCoachingOverlay: true,
            onTapOnPlane: { position, arView in
                guard let model else { return }
                let anchor = AnchorNode.world(position: position)
                let clone = model.entity.clone(recursive: true)
                clone.scale = .init(repeating: 0.3)
                anchor.add(clone)
                arView.scene.addAnchor(anchor.entity)
            }
        )
        .edgesIgnoringSafeArea(.all)
        .task {
            model = try? await ModelNode.load("models/robot.usdz")
        }
    }
}
\`\`\`

### 5. AR Configuration

| Parameter | Options | Default |
|-----------|---------|---------|
| \`planeDetection\` | \`.none\`, \`.horizontal\`, \`.vertical\`, \`.both\` | \`.horizontal\` |
| \`showPlaneOverlay\` | \`true\` / \`false\` | \`true\` |
| \`showCoachingOverlay\` | \`true\` / \`false\` | \`true\` |
| \`imageTrackingDatabase\` | \`Set<ARReferenceImage>?\` | \`nil\` |
| \`onTapOnPlane\` | \`((SIMD3<Float>, ARView) -> Void)?\` | \`nil\` |
| \`onImageDetected\` | \`((String, AnchorNode, ARView) -> Void)?\` | \`nil\` |

### 6. View Modifiers

- \`.onSessionStarted { arView in ... }\` — called once when the AR session starts`;

const WEB_3D = `## SceneView Web — Browser 3D Setup

SceneView Web uses **Filament.js** — the same rendering engine as Android, compiled to WebAssembly (WebGL2).

### 1. Install

\`\`\`bash
npm install @sceneview/sceneview-web
\`\`\`

Or in a Kotlin/JS Gradle project:
\`\`\`kotlin
kotlin {
    js(IR) { browser(); binaries.executable() }
    sourceSets {
        jsMain.dependencies {
            implementation("@sceneview/sceneview-web")
        }
    }
}
\`\`\`

### 2. HTML

\`\`\`html
<canvas id="scene-canvas" style="width:100%;height:100vh"></canvas>
<script src="your-app.js"></script>
\`\`\`

### 3. Kotlin/JS Code

\`\`\`kotlin
import io.github.sceneview.web.SceneView
import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement

fun main() {
    val canvas = document.getElementById("scene-canvas") as HTMLCanvasElement
    canvas.width = canvas.clientWidth
    canvas.height = canvas.clientHeight

    SceneView.create(
        canvas = canvas,
        configure = {
            camera {
                eye(0.0, 1.5, 5.0)
                target(0.0, 0.0, 0.0)
                fov(45.0)
            }
            light {
                directional()
                intensity(100_000.0)
            }
            model("models/DamagedHelmet.glb")
            autoRotate()
        },
        onReady = { sceneView ->
            sceneView.startRendering()
        }
    )
}
\`\`\`

### 4. Supported Features

- Same Filament PBR renderer as Android (WASM)
- glTF 2.0 / GLB model loading
- IBL environment lighting (KTX)
- Orbit camera with mouse/touch/pinch
- Auto-rotation, directional/point/spot lights

### 5. Limitations

- No AR (requires native sensors)
- WebGL2 required (~95% of browsers)
- glTF/GLB format only`;

const FLUTTER_3D = `## SceneView Flutter — 3D Setup

SceneView Flutter uses **PlatformView** to embed native SceneView (Android: Filament, iOS: RealityKit).

### 1. Dependencies

> **Note:** the plugin is published on pub.dev as \`flutter_sceneview\`
> (the pub.dev names \`sceneview\` and \`sceneview_flutter\` are unrelated
> third-party uploads — do not use them).

\`\`\`yaml
# pubspec.yaml
dependencies:
  flutter_sceneview: ^${LATEST_FLUTTER_PUB_RELEASE}
\`\`\`

### 2. Android Setup

Add to \`android/app/build.gradle\`:
\`\`\`groovy
android {
    defaultConfig {
        minSdkVersion 24
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}
\`\`\`

### 3. iOS Setup

Both lines in \`ios/Podfile\` are required — skipping the second one fails the
build with \`Unable to find a specification for 'SceneViewSwift'\`:

\`\`\`ruby
platform :ios, '18.0'

target 'Runner' do
  use_frameworks!
  pod 'SceneViewSwift',
      :podspec => 'https://raw.githubusercontent.com/sceneview/sceneview/main/SceneViewSwift.podspec'
  flutter_install_all_ios_pods File.dirname(File.realpath(__FILE__))
end
\`\`\`

Adding \`SceneViewSwift\` as a **Swift package** in Xcode does not work: the bridge
is itself a pod and compiles inside the generated \`Pods.xcodeproj\`, which cannot
see a package added to \`Runner.xcodeproj\`. \`:podspec\` reads from the mutable
\`main\` branch because no released tag carries the root podspec yet; the sources
it pulls are tag-pinned via \`s.source\`, and this becomes a tagged coordinate once
a release includes it.

### 4. Basic 3D Scene

\`\`\`dart
import 'package:flutter/foundation.dart' show TargetPlatform, defaultTargetPlatform;
import 'package:flutter_sceneview/flutter_sceneview.dart';

class My3DScreen extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    // Not one path for both: Filament reads glTF/GLB, RealityKit reads USDZ and
    // cannot read glTF at all. A hardcoded .glb compiles and renders nothing on iOS.
    // Branch on defaultTargetPlatform, never on dart:io's Platform — importing
    // dart:io makes the file uncompilable on Flutter web.
    final model = defaultTargetPlatform == TargetPlatform.iOS
        ? 'models/chair.usdz'
        : 'models/chair.glb';

    return SceneView(
      initialModels: [
        ModelNode(modelPath: model, x: 0, y: 0, z: -2, scale: 1.0),
      ],
      // Android only today — the iOS path is wired but RealityKit's
      // entity-targeted hit test resolves no entity, so this never fires (#3045).
      onTap: (nodeName) => print('tapped: \$nodeName'),
    );
  }
}
\`\`\`

For imperative loading, attach a \`SceneViewController\` via \`onViewCreated\` and
call \`loadModel\` / \`setEnvironment\` / \`clearScene\` on it.

### 5. Platform Differences

| Feature | Android | iOS |
|---------|---------|-----|
| Renderer | Filament | RealityKit |
| Model format | GLB/glTF | USDZ/.reality |
| Environment | HDR | Built-in IBL |`;

const FLUTTER_AR = `## SceneView Flutter — AR Setup

### 1. Dependencies

\`\`\`yaml
# pubspec.yaml — published on pub.dev (the names sceneview / sceneview_flutter are unrelated third-party uploads)
dependencies:
  flutter_sceneview: ^${LATEST_FLUTTER_PUB_RELEASE}
\`\`\`

### 2. Android Manifest

\`\`\`xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera.ar" android:required="true" />
<application>
    <meta-data android:name="com.google.ar.core" android:value="required" />
</application>
\`\`\`

### 3. iOS Setup

Both lines in \`ios/Podfile\` are required — skipping the second one fails the
build with \`Unable to find a specification for 'SceneViewSwift'\`:

\`\`\`ruby
platform :ios, '18.0'

target 'Runner' do
  use_frameworks!
  pod 'SceneViewSwift',
      :podspec => 'https://raw.githubusercontent.com/sceneview/sceneview/main/SceneViewSwift.podspec'
  flutter_install_all_ios_pods File.dirname(File.realpath(__FILE__))
end
\`\`\`

Adding \`SceneViewSwift\` as a **Swift package** in Xcode does not work: the bridge
is itself a pod and compiles inside the generated \`Pods.xcodeproj\`, which cannot
see a package added to \`Runner.xcodeproj\`. \`:podspec\` reads from the mutable
\`main\` branch because no released tag carries the root podspec yet; the sources
it pulls are tag-pinned via \`s.source\`, and this becomes a tagged coordinate once
a release includes it.

### 4. iOS Info.plist

\`\`\`xml
<key>NSCameraUsageDescription</key>
<string>This app uses the camera for augmented reality.</string>
\`\`\`

### 5. Basic AR Scene

\`\`\`dart
import 'package:flutter_sceneview/flutter_sceneview.dart';

class MyARScreen extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return ARSceneView(
      planeDetection: true,
      onPlaneDetected: (planeType) => print('plane: \$planeType'),
      // Android only: SceneViewSwift's ARSceneView exposes no entity
      // hit-test hook, so on iOS this never fires (#2051).
      onTap: (nodeName) => print('tapped: \$nodeName'),
    );
  }
}
\`\`\``;

const REACT_NATIVE_3D = `## SceneView React Native — 3D Setup

SceneView React Native uses **Fabric/Turbo** to bridge to native SceneView.

### 1. Install

\`\`\`bash
npm install @sceneview/react-native
cd ios && pod install
\`\`\`

### 2. Android Setup

In \`android/app/build.gradle\`:
\`\`\`groovy
android {
    defaultConfig {
        minSdkVersion 24
    }
}
\`\`\`

### 3. iOS Setup

In \`ios/Podfile\`:
\`\`\`ruby
platform :ios, '18.0'
\`\`\`

### 4. Basic 3D Scene

\`\`\`tsx
import { SceneView } from '@sceneview/react-native';

export default function My3DScreen() {
  return (
    <SceneView
      style={{ flex: 1 }}
      modelNodes={[{ src: 'models/chair.glb', position: [0, 0, -1], scale: 1.0 }]}
      environment="environments/sky_2k.hdr"
      cameraControlMode="orbit"
      // Android only for now — the iOS path goes through the same RealityKit
      // entity-targeted hit test that never fires on the Flutter bridge (#3045);
      // the React Native measurement is #3072.
      onTap={(e) => console.log(e.nativeEvent.nodeName)}
    />
  );
}
\`\`\`

### 5. Platform Differences

| Feature | Android | iOS |
|---------|---------|-----|
| Renderer | Filament | RealityKit |
| Model format | GLB/glTF | USDZ/.reality |
| Bridge | Fabric component | Fabric component |`;

const REACT_NATIVE_AR = `## SceneView React Native — AR Setup

### 1. Install

\`\`\`bash
npm install @sceneview/react-native
cd ios && pod install
\`\`\`

### 2. Android Permissions

Add to \`AndroidManifest.xml\`:
\`\`\`xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera.ar" android:required="true" />
<application>
    <meta-data android:name="com.google.ar.core" android:value="required" />
</application>
\`\`\`

### 3. iOS Permissions

Add to \`Info.plist\`:
\`\`\`xml
<key>NSCameraUsageDescription</key>
<string>This app uses the camera for augmented reality.</string>
\`\`\`

### 4. Basic AR Scene

\`\`\`tsx
import { ARSceneView } from '@sceneview/react-native';

export default function MyARScreen() {
  return (
    <ARSceneView
      style={{ flex: 1 }}
      modelNodes={[{ src: 'models/robot.glb', scale: 0.5 }]}
      planeDetection={true}
      // Declared so the API surface is stable, but NOT bridged natively yet:
      // setting it true changes nothing today (#909). Left explicitly false so
      // nobody ships an app that silently expects LiDAR occlusion.
      depthOcclusion={false}
      onPlaneDetected={(e) => console.log(e.nativeEvent.type)}
      // Android only: SceneViewSwift's ARSceneView exposes no entity
      // hit-test hook, so on iOS this never fires (#2051).
      onTap={(e) => console.log(e.nativeEvent.nodeName)}
    />
  );
}
\`\`\``;

const DESKTOP_3D = `## SceneView Desktop — Setup

SceneView Desktop uses **Compose Desktop** with a software 3D renderer (wireframe). A Filament JNI backend is planned for full PBR rendering.

### 1. Gradle Dependencies

\`\`\`kotlin
// build.gradle.kts
plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
}

dependencies {
    implementation(compose.desktop.currentOs)
    // NOTE: there is no published \`io.github.sceneview:sceneview-desktop\`
    // artifact. Desktop is a Compose Canvas placeholder living in
    // \`samples/desktop-demo\`, not a library you can depend on yet.
}
\`\`\`

### 2. Basic Desktop Scene

There is no \`SceneView\` composable on desktop. The sample draws its wireframe
with a plain Compose \`Canvas\`, so the starting point is ordinary Compose
Desktop — copy \`samples/desktop-demo/src/desktopMain/kotlin/io/github/sceneview/desktop/Main.kt\`
rather than importing a SceneView type that does not exist.

\`\`\`kotlin
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SceneView Desktop"
    ) {
        // From samples/desktop-demo — a Compose Canvas animating a wireframe,
        // NOT a Filament scene. The only public entry point that exists today.
        WireframeCubeViewer()
    }
}
\`\`\`

### 3. Current Status

| Feature | Status |
|---------|--------|
| Software wireframe renderer | Alpha |
| Filament JNI (full PBR) | Planned (18-29 day effort) |
| GLB/glTF loading | Planned (needs Filament JNI) |
| Windows / macOS / Linux | Alpha |

### 4. Limitations

- Currently wireframe only (no PBR, no textures)
- No AR support (desktop has no camera/sensor API)
- Model loading requires Filament JNI (not yet available)`;

const TV_3D = `## SceneView Android TV — Setup

SceneView on Android TV uses the same Filament renderer as mobile Android, with D-pad navigation support.

### 1. Gradle Dependencies

\`\`\`kotlin
dependencies {
    implementation("io.github.sceneview:sceneview:${LATEST_SCENEVIEW_RELEASE}")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.tv:tv-foundation:1.0.0-alpha10")
}
\`\`\`

### 2. Manifest

\`\`\`xml
<uses-feature android:name="android.software.leanback" android:required="true" />
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />
\`\`\`

### 3. Basic TV Scene with D-pad Controls

\`\`\`kotlin
@Composable
fun TVModelViewer() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    var rotation by remember { mutableFloatStateOf(0f) }

    // D-pad rotation control
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                when (event.key) {
                    Key.DirectionLeft -> { rotation -= 15f; true }
                    Key.DirectionRight -> { rotation += 15f; true }
                    else -> false
                }
            }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader
        ) {
            rememberModelInstance(modelLoader, "models/car.glb")?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = 2.0f
                )
            }
        }
    }
}
\`\`\`

### 4. TV-Specific Considerations

- **No touch** — use D-pad for rotation, Select for actions
- **10-foot UI** — larger models, simpler scenes, bolder text
- **No AR** — Android TV has no camera/sensors for AR
- **Performance** — TV SoCs are weaker than phones; limit to <50K triangles`;

const SETUPS: Record<Platform, PlatformSetup> = {
  android: {
    name: "Android (Jetpack Compose)",
    renderer: "Filament (OpenGL ES / Vulkan)",
    status: "Stable (v${LATEST_SCENEVIEW_RELEASE})",
    guide3d: ANDROID_3D,
    guideAr: ANDROID_AR,
  },
  ios: {
    name: "iOS / macOS / visionOS (SwiftUI)",
    renderer: "RealityKit (Metal)",
    status: "Alpha (v${LATEST_SCENEVIEW_RELEASE})",
    guide3d: IOS_3D,
    guideAr: IOS_AR,
  },
  web: {
    name: "Web (Kotlin/JS + Filament.js)",
    renderer: "Filament.js (WebGL2 / WASM)",
    status: "Alpha",
    guide3d: WEB_3D,
    guideAr: null,
  },
  flutter: {
    name: "Flutter (PlatformView bridge)",
    renderer: "Filament (Android) / RealityKit (iOS)",
    status: "Alpha",
    guide3d: FLUTTER_3D,
    guideAr: FLUTTER_AR,
  },
  "react-native": {
    name: "React Native (Fabric bridge)",
    renderer: "Filament (Android) / RealityKit (iOS)",
    status: "Alpha",
    guide3d: REACT_NATIVE_3D,
    guideAr: REACT_NATIVE_AR,
  },
  desktop: {
    name: "Desktop (Compose Desktop)",
    renderer: "Software wireframe (Filament JNI planned)",
    status: "Alpha",
    guide3d: DESKTOP_3D,
    guideAr: null,
  },
  tv: {
    name: "Android TV",
    renderer: "Filament (OpenGL ES)",
    status: "Alpha",
    guide3d: TV_3D,
    guideAr: null,
  },
};

export function getPlatformSetup(platform: Platform, type: SetupType): string {
  const setup = SETUPS[platform];
  if (!setup) {
    return `Unknown platform "${platform}". Available: ${PLATFORM_IDS.join(", ")}`;
  }

  if (type === "ar" && !setup.guideAr) {
    return `# ${setup.name}\n\n**Status:** ${setup.status}\n**Renderer:** ${setup.renderer}\n\n> AR is not supported on ${setup.name}. Only 3D scenes are available on this platform.\n\nUse \`get_platform_setup\` with \`type: "3d"\` for 3D setup instructions.`;
  }

  const guide = type === "ar" ? setup.guideAr! : setup.guide3d;
  return `# ${setup.name}\n\n**Status:** ${setup.status} | **Renderer:** ${setup.renderer}\n\n${guide}`;
}

export function listPlatforms(): string {
  const header = `## SceneView — Supported Platforms\n\n| Platform | Renderer | Status | 3D | AR |\n|----------|----------|--------|-----|-----|\n`;
  const rows = PLATFORM_IDS.map((id) => {
    const s = SETUPS[id];
    return `| ${s.name} | ${s.renderer} | ${s.status} | Yes | ${s.guideAr ? "Yes" : "No"} |`;
  }).join("\n");
  return header + rows + "\n\nUse `get_platform_setup` with a specific platform for full setup instructions.";
}
