# Android XR Integration

!!! warning "Status: Planned / Experimental"
    Android XR support is planned. The Jetpack XR SDK is in **beta** (not yet stable).
    APIs may change. This page documents the integration strategy for SceneView + Android XR.

## What is Android XR?

Android XR is Google's platform for XR headsets (like Samsung Project Moohan) and AI glasses.
It extends the Android platform with spatial capabilities: 3D scene graphs, passthrough AR,
spatial audio, hand tracking, and immersive environments.

The developer toolkit is the **Jetpack XR SDK**, which includes:

| Library | Purpose | Artifact |
|---|---|---|
| **Jetpack SceneCore** | 3D scene graph, entities, environments | `androidx.xr.scenecore:scenecore:1.0.0-beta02` |
| **Compose for XR** | Spatial Compose composables | `androidx.xr.compose:compose:1.0.0-beta01` |
| **ARCore for XR** | Planes, anchors, hand tracking | `androidx.xr.arcore:arcore:1.0.0-beta02` |
| **XR Runtime** | Session management, capabilities | `androidx.xr.runtime:runtime:1.0.0-beta02` |

## How it relates to SceneView

SceneView uses **Filament** as its 3D renderer on Android. Android XR uses its own
scene graph (**SceneCore**) with entity types like `GltfModelEntity` and `PanelEntity`.

The integration strategy is **not** to replace SceneView's renderer, but to embed
SceneView's `SceneView {}` composable inside Android XR's spatial layout system. This means:

- SceneView renders 3D content via Filament inside a `SpatialPanel`
- Android XR handles spatial positioning, passthrough, and device tracking
- SceneView's existing model loading, animation, and interaction APIs work unchanged
- ARCore for Jetpack XR provides spatial anchors and plane detection

```
┌──────────────────────────────────────────────┐
│            Android XR (Jetpack XR SDK)       │
│                                              │
│  ┌──────────────┐  ┌─────────────────────┐   │
│  │ SpatialPanel │  │ GltfModelEntity     │   │
│  │ ┌──────────┐ │  │ (SceneCore native)  │   │
│  │ │ SceneView│ │  └─────────────────────┘   │
│  │ │ SceneView {} │ │                            │
│  │ │(Filament)│ │  ┌─────────────────────┐   │
│  │ └──────────┘ │  │ SpatialEnvironment  │   │
│  └──────────────┘  │ (passthrough / sky) │   │
│                    └─────────────────────┘   │
└──────────────────────────────────────────────┘
```

## Integration strategy

### Approach 1: SceneView inside SpatialPanel (recommended)

Embed the existing `SceneView {}` composable inside a `SpatialPanel`. This gives you full
SceneView capabilities (Filament rendering, model loading, physics, animation) positioned
in XR space.

```kotlin
// build.gradle.kts
dependencies {
    // SceneView
    implementation("io.github.sceneview:sceneview:4.54.0")

    // Jetpack XR
    implementation("androidx.xr.scenecore:scenecore:1.0.0-beta02")
    implementation("androidx.xr.compose:compose:1.0.0-beta01")
}
```

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import io.github.sceneview.SceneView
import io.github.sceneview.createEnvironment
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

@Composable
fun XRSceneViewPanel() {
    Subspace {
        // Place SceneView as a spatial panel in XR space
        SpatialPanel(
            modifier = SubspaceModifier
                .width(1200.dp)
                .height(800.dp)
        ) {
            // Standard SceneView — runs Filament inside the panel
            val engine = rememberEngine()
            val modelLoader = rememberModelLoader(engine)
            val environmentLoader = rememberEnvironmentLoader(engine)
            val modelInstance = rememberModelInstance(modelLoader, "models/helmet.glb")

            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                environment = rememberEnvironment(environmentLoader) {
                    environmentLoader.createHDREnvironment("environments/studio.hdr")
                        ?: createEnvironment(environmentLoader)
                },
            ) {
                modelInstance?.let {
                    ModelNode(modelInstance = it)
                }
            }
        }
    }
}
```

### Approach 2: SceneView AR with XR passthrough

!!! warning "Compiles, but not verified on a headset"
    `ARSceneView` runs **phone ARCore** (`com.google.ar.core`), a different runtime from
    ARCore for Jetpack XR. The snippet below compiles against the pinned Jetpack XR artifacts,
    but it has **not** been shown to run inside a `SpatialPanel` on an Android XR headset. For
    headset tracking today, use SceneView's Jetpack XR nodes instead: `XrHandNode` (hand
    tracking) and `XrFaceNode` (face tracking), documented in
    [`llms.txt`](https://sceneview.github.io/llms.txt) under *Jetpack XR Extensions*.

This combines SceneView's `ARSceneView {}` with XR passthrough.
Inside a Compose for XR hierarchy the Jetpack XR `Session` is provided by `LocalSession`
(`null` when the app is not running on an XR device):

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import androidx.xr.scenecore.scene
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberOnGestureListener

@Composable
fun XRAugmentedView() {
    val xrSession = LocalSession.current
    LaunchedEffect(xrSession) {
        // Show the real world: 1f = fully visible passthrough, 0f = fully virtual
        xrSession?.scene?.spatialEnvironment?.preferredPassthroughOpacity = 1f
    }

    Subspace {
        SpatialPanel(
            modifier = SubspaceModifier
                .width(1400.dp)
                .height(900.dp)
        ) {
            // SceneView AR handles camera, hit-testing, plane detection
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                onSessionCreated = { arSession ->
                    // ARCore session — planes, anchors, etc.
                },
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { event, node ->
                        // Place models on detected surfaces
                    }
                )
            )
        }
    }
}
```

### Approach 3: Mixed — SceneView panels + SceneCore native entities

Use SceneView for complex 3D viewports alongside SceneCore's native `GltfModelEntity`
for standalone objects in the XR scene graph. `GltfModel.create` is a `suspend` call, so load
the model first, then wrap it in an entity:

```kotlin
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SceneCoreEntity
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialRow
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.width
import androidx.xr.scenecore.GltfModel
import androidx.xr.scenecore.GltfModelEntity
import io.github.sceneview.SceneView

@Composable
fun MixedXRExperience() {
    val xrSession = LocalSession.current ?: return // null when not on an XR device

    // Load the glTF once (asset path relative to src/main/assets/)
    val gltfModel by produceState<GltfModel?>(initialValue = null, xrSession) {
        value = GltfModel.create(xrSession, Uri.parse("models/simple-object.glb"))
        // Release the native model when this composable leaves the composition
        awaitDispose { value?.close() }
    }

    Subspace {
        SpatialRow {
            // Panel 1: SceneView-powered 3D editor
            SpatialPanel(SubspaceModifier.width(800.dp).height(600.dp)) {
                SceneView(modifier = Modifier.fillMaxSize()) {
                    // Full SceneView scene with nodes, lights, physics
                }
            }

            // Panel 2: Native SceneCore 3D model (lighter weight)
            gltfModel?.let { model ->
                SceneCoreEntity(
                    factory = { GltfModelEntity.create(xrSession, model) },
                    modifier = SubspaceModifier.offset(x = 100.dp),
                )
            }
        }
    }
}
```

## Key Jetpack XR concepts

### Session

Every XR app needs a `Session` — the entry point for spatial capabilities. Compose for XR
creates it for you (read it with `LocalSession.current`, or check
`LocalSpatialCapabilities.current.isSpatialUiEnabled`). Outside Compose, `Session.create` is a
`suspend` function that returns a `SessionCreateResult`:

```kotlin
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateApkRequired
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.SessionCreateUnsupportedDevice
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.scene
import kotlinx.coroutines.launch

class XrActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            // Name the `context` argument: a bare Activity resolves to a deprecated overload
            when (val result = Session.create(context = this@XrActivity)) {
                is SessionCreateSuccess -> {
                    val session = result.session
                    // Check spatial capabilities
                    if (SpatialCapability.SPATIAL_UI in session.scene.spatialCapabilities) {
                        // Device supports spatial panels, 3D content
                    }
                }
                is SessionCreateApkRequired -> {
                    // result.requiredApk must be installed first
                }
                is SessionCreateUnsupportedDevice -> {
                    // Not an XR device — fall back to the regular phone UI
                }
                else -> {
                    // SessionCreateUnknownError, SessionCreateTimedOut
                }
            }
        }
    }
}
```

### Spatial composables

| Composable | Purpose |
|---|---|
| `Subspace` | Container for spatial content (required wrapper) |
| `SpatialPanel` | 2D Compose UI placed in 3D space |
| `SpatialRow` / `SpatialColumn` | Layout spatial panels in 3D |
| `Orbiter` | Floating controls anchored to panels |
| `SceneCoreEntity` | Place SceneCore entities (3D models) |
| `SpatialDialog` | Dialog elevated in z-depth |

### Entity system (SceneCore)

| Entity | Purpose |
|---|---|
| `GltfModelEntity` | Load and display glTF/GLB 3D models |
| `PanelEntity` | 2D panel in 3D space |
| `AnchorEntity` | Content anchored to real-world surfaces |
| `SpatialEnvironment` | Skybox, passthrough, environment geometry |

### Components (behaviors on entities)

| Component | Purpose |
|---|---|
| `MovableComponent` | Let users grab and move entities |
| `ResizableComponent` | Let users resize entities |
| `InteractableComponent` | Handle hand/controller input events |

## What SceneView brings to Android XR

Using SceneView inside Android XR provides advantages over SceneCore alone:

| Feature | SceneCore alone | SceneView + XR |
|---|---|---|
| Model loading | `GltfModelEntity` (basic) | Full Filament material system, PBR |
| Animation | Basic glTF animations | Spring physics, property animations, blend |
| Lighting | Environment-based | Custom lights, shadows, IBL, dynamic sky |
| Interaction | `InteractableComponent` | Per-node hit testing, gesture handling |
| Geometry | glTF only | Procedural meshes, lines, text, shapes |
| Physics | None | Collision detection, physics simulation |
| Compose API | `SceneCoreEntity` factory | Declarative `SceneView {}` with node composables |

## Required dependencies

```kotlin
// build.gradle.kts (app module)
dependencies {
    // SceneView 3D
    implementation("io.github.sceneview:sceneview:4.54.0")
    // — or for AR —
    implementation("io.github.sceneview:arsceneview:4.54.0")

    // Jetpack XR SDK
    implementation("androidx.xr.scenecore:scenecore:1.0.0-beta02")
    implementation("androidx.xr.compose:compose:1.0.0-beta01")
    implementation("androidx.xr.arcore:arcore:1.0.0-beta02")  // optional: spatial anchors, planes
}
```

```kotlin
// settings.gradle.kts — ensure Google's Maven repo
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

### Android manifest

```xml
<!-- Android XR Spatial APIs (Jetpack XR SDK). required="false" keeps the same APK installable on phones -->
<uses-feature android:name="android.software.xr.api.spatial" android:required="false" />

<application>
    <activity
        android:name=".MainActivity"
        android:enableOnBackInvokedCallback="true">
        <!-- enableOnBackInvokedCallback required for SpatialPanel back navigation -->
    </activity>
</application>
```

## Roadmap

- [ ] **Phase 1**: Validate `SceneView {}` rendering inside `SpatialPanel` on XR emulator
- [ ] **Phase 2**: Bridge SceneView camera to XR head tracking for passthrough AR
- [ ] **Phase 3**: Expose `MovableComponent` / `ResizableComponent` on SceneView nodes
- [ ] **Phase 4**: Hand tracking integration with SceneView's gesture system
- [ ] **Phase 5**: Spatial audio integration
- [ ] **Phase 6**: Dedicated `XRScene {}` composable wrapping the setup boilerplate

## Resources

- [Jetpack XR SDK overview](https://developer.android.com/develop/xr/jetpack-xr-sdk)
- [Compose for XR UI guide](https://developer.android.com/develop/xr/jetpack-xr-sdk/develop-ui)
- [SceneCore entities guide](https://developer.android.com/develop/xr/jetpack-xr-sdk/work-with-entities)
- [XR SceneCore releases](https://developer.android.com/jetpack/androidx/releases/xr-scenecore)
- [Android XR developer home](https://developer.android.com/develop/xr)
