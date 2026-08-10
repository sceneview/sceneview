# Android XR Integration

!!! warning "Status: Planned / Experimental"
    Android XR support is planned. The Jetpack XR SDK is in **Developer Preview** (alpha).
    APIs may change. This page documents the integration strategy for SceneView + Android XR.

## What is Android XR?

Android XR is Google's platform for XR headsets (like Samsung Project Moohan) and AI glasses.
It extends the Android platform with spatial capabilities: 3D scene graphs, passthrough AR,
spatial audio, hand tracking, and immersive environments.

The developer toolkit is the **Jetpack XR SDK**, which includes:

| Library | Purpose | Artifact |
|---|---|---|
| **Jetpack SceneCore** | 3D scene graph, entities, environments | `androidx.xr.scenecore:scenecore:1.0.0-alpha12` |
| **Compose for XR** | Spatial Compose composables | `androidx.xr.compose:compose:1.0.0-alpha12` |
| **ARCore for XR** | Planes, anchors, hand tracking | `androidx.xr.arcore:arcore:1.0.0-alpha12` |
| **XR Runtime** | Session management, capabilities | `androidx.xr.runtime:runtime:1.0.0-alpha12` |

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
    implementation("io.github.sceneview:sceneview:4.27.0")

    // Jetpack XR
    implementation("androidx.xr.scenecore:scenecore:1.0.0-alpha12")
    implementation("androidx.xr.compose:compose:1.0.0-alpha12")
}
```

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.spatial.SpatialPanel
import androidx.xr.compose.subspace.SubspaceModifier
import androidx.xr.compose.subspace.height
import androidx.xr.compose.subspace.width
import io.github.sceneview.SceneView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberModelInstance

@Composable
fun XRSceneViewPanel() {
    Subspace {
        // Place SceneView as a spatial panel in XR space
        SpatialPanel(
            SubspaceModifier
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
                environment = environmentLoader.createHDREnvironment("environments/studio.hdr")!!,
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

For AR experiences on XR headsets, combine SceneView's `ARSceneView {}` with XR passthrough:

```kotlin
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.spatial.SpatialPanel
import androidx.xr.scenecore.SpatialEnvironment
import io.github.sceneview.ar.ARSceneView

@Composable
fun XRAugmentedView(xrSession: Session) {
    // Enable passthrough so the real world is visible
    xrSession.spatialEnvironment.setPassthroughEnabled(true)

    Subspace {
        SpatialPanel(
            SubspaceModifier
                .width(1400.dp)
                .height(900.dp)
        ) {
            // SceneView AR handles camera, hit-testing, plane detection
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                onSessionCreated = { arSession ->
                    // ARCore session — planes, anchors, etc.
                },
                onTap = { hitResult ->
                    // Place models on detected surfaces
                }
            )
        }
    }
}
```

### Approach 3: Mixed — SceneView panels + SceneCore native entities

Use SceneView for complex 3D viewports alongside SceneCore's native `GltfModelEntity`
for standalone objects in the XR scene graph:

```kotlin
@Composable
fun MixedXRExperience(xrSession: Session) {
    Subspace {
        SpatialRow {
            // Panel 1: SceneView-powered 3D editor
            SpatialPanel(SubspaceModifier.width(800.dp).height(600.dp)) {
                SceneView(modifier = Modifier.fillMaxSize()) {
                    // Full SceneView scene with nodes, lights, physics
                }
            }

            // Panel 2: Native SceneCore 3D model (lighter weight)
            SceneCoreEntity(
                modifier = SubspaceModifier.offset(x = 100.dp),
                factory = {
                    GltfModelEntity.create(
                        session = xrSession,
                        glbUri = Uri.parse("models/simple-object.glb")
                    )
                }
            )
        }
    }
}
```

## Key Jetpack XR concepts

### Session

Every XR app needs a `Session` — the entry point for spatial capabilities:

```kotlin
val session = Session.create(activity)

// Check spatial capabilities
if (session.spatialCapabilities.hasCapability(SpatialCapabilities.SPATIAL_UI)) {
    // Device supports spatial panels, 3D content
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
    implementation("io.github.sceneview:sceneview:4.27.0")
    // — or for AR —
    implementation("io.github.sceneview:arsceneview:4.27.0")

    // Jetpack XR SDK
    implementation("androidx.xr.scenecore:scenecore:1.0.0-alpha12")
    implementation("androidx.xr.compose:compose:1.0.0-alpha12")
    implementation("androidx.xr.arcore:arcore:1.0.0-alpha12")  // optional: spatial anchors, planes
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
<!-- Required for XR -->
<uses-feature android:name="android.hardware.xr.headtracking" android:required="true" />

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
