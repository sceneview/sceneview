# Android XR Emulator Setup

Step-by-step guide to running SceneView in the Android XR emulator for demos,
screenshots, and video recordings.

!!! info "Prerequisites"
    - **Android Studio Ladybug** (stable) or **Canary** (recommended for latest XR tools)
    - **macOS with Apple Silicon** (ARM64) — the XR system image is `arm64-v8a`
    - Android SDK with command-line tools installed
    - At least **8 GB free disk space** for the XR system image

## 1. Install the XR system image

Open a terminal and run:

```bash
# Accept licenses and install the Google Play XR image (API 34, ARM64)
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "system-images;android-34;google-xr;arm64-v8a"
```

This downloads the **Google Play XR ARM 64 v8a System Image (Developer Preview)**.
It requires emulator version **35.6.7 or later** (check with `emulator -version`).

If your emulator is outdated:

```bash
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --install "emulator"
```

## 2. Create the XR AVD

### Option A: Android Studio (recommended)

1. Open **Device Manager** (Tools > Device Manager)
2. Click **Create Virtual Device**
3. In the **Category** column, select **XR**
4. Select the **XR Device** hardware profile, click **Next**
5. Select **Google Play XR** system image (ARM64), click **Next**
6. Name it (e.g., `Android_XR`), set RAM to **4096 MB**, click **Finish**

!!! note "XR hardware profile"
    The XR device profile is available in **Android Studio Canary**. If you only
    have the stable release, use Option B below.

### Option B: Command line

```bash
# Create AVD using a generic device profile with the XR system image
echo "no" | $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd \
  --name "Android_XR" \
  --package "system-images;android-34;google-xr;arm64-v8a" \
  --device "medium_tablet" \
  --force
```

Then optimize performance by editing `~/.android/avd/Android_XR.avd/config.ini`:

```ini
# Increase RAM (default 1536M is too low for XR)
hw.ramSize=4096M

# Enable GPU acceleration (critical for XR rendering)
hw.gpu.enabled=yes
hw.gpu.mode=host

# Increase heap size
vm.heapSize=512M
```

## 3. Launch the XR emulator

### From Android Studio

Click the **Play** button next to the `Android_XR` AVD in Device Manager.

### From command line

```bash
$ANDROID_HOME/emulator/emulator -avd Android_XR -gpu host
```

The XR emulator boots into a 3D virtual environment with a simulated room.
You can navigate the space using mouse and keyboard.

## 4. XR emulator controls

### Navigation (hold Option/Alt)

| Key | Action |
|-----|--------|
| `W` / Up Arrow | Move forward |
| `S` / Down Arrow | Move backward |
| `A` / Left Arrow | Strafe left |
| `D` / Right Arrow | Strafe right |
| `E` | Move up |
| `Q` | Move down |

### Mouse controls (emulator toolbar)

| Tool | Action |
|------|--------|
| **Rotate** | Drag to pivot your view |
| **Pan** | Drag to move laterally |
| **Dolly** | Drag to zoom in/out |
| **Reset** | Return to default viewpoint |

### Interaction

- Select the **interaction controls** option in the emulator menu to interact with
  spatial panels using the mouse
- Toggle **Passthrough** in the top menu to switch between immersive and mixed-reality modes

### Recording

- **Screenshot**: Use the camera icon in the emulator toolbar
- **Screen Record**: Use the video icon to record a session (ideal for communication)

## 5. Run SceneView in the XR emulator

### Gradle dependencies

```kotlin
// build.gradle.kts (app module)
dependencies {
    // SceneView 3D
    implementation("io.github.sceneview:sceneview:4.11.1")

    // Jetpack XR Compose (spatial panels, layouts)
    implementation("androidx.xr.compose:compose:1.0.0-alpha12")

    // Jetpack XR SceneCore (session, entities — optional)
    implementation("androidx.xr.scenecore:scenecore:1.0.0-alpha12")
}
```

### Manifest configuration

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Declare XR support (required=false allows same APK on phones) -->
    <uses-feature
        android:name="android.software.xr.api.spatial"
        android:required="false" />

    <application ...>
        <activity
            android:name=".MainActivity"
            android:enableOnBackInvokedCallback="true">

            <!-- Auto-enter Full Space mode at launch -->
            <property
                android:name="android.window.PROPERTY_XR_ACTIVITY_START_MODE"
                android:value="XR_ACTIVITY_START_MODE_FULL_SPACE_MANAGED" />
        </activity>
    </application>
</manifest>
```

### Minimal XR + SceneView activity

```kotlin
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

class XRDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Subspace: container for spatial content (3D space)
            Subspace {
                // SpatialPanel: places a 2D Compose surface in 3D space
                SpatialPanel(
                    modifier = SubspaceModifier
                        .width(1200.dp)
                        .height(800.dp)
                ) {
                    // Standard SceneView — Filament renders inside the panel
                    val engine = rememberEngine()
                    val modelLoader = rememberModelLoader(engine)
                    val environmentLoader = rememberEnvironmentLoader(engine)
                    val modelInstance = rememberModelInstance(
                        modelLoader, "models/damaged_helmet.glb"
                    )

                    SceneView(
                        modifier = Modifier.fillMaxSize(),
                        engine = engine,
                        modelLoader = modelLoader,
                        environment = environmentLoader.createHDREnvironment(
                            "environments/studio_small.hdr"
                        )!!,
                    ) {
                        modelInstance?.let { instance ->
                            ModelNode(modelInstance = instance)
                        }
                    }
                }
            }
        }
    }
}
```

### Multi-panel layout with controls

```kotlin
import androidx.xr.compose.subspace.SpatialRow
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.spatial.Orbiter
import androidx.compose.material3.*

@Composable
fun XRShowcase() {
    Subspace {
        SpatialRow {
            // Main 3D viewport
            SpatialPanel(
                modifier = SubspaceModifier
                    .width(1200.dp)
                    .height(800.dp)
            ) {
                val engine = rememberEngine()
                val modelLoader = rememberModelLoader(engine)
                val modelInstance = rememberModelInstance(
                    modelLoader, "models/damaged_helmet.glb"
                )

                SceneView(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                ) {
                    modelInstance?.let { instance ->
                        ModelNode(modelInstance = instance)
                    }
                }

                // Orbiter: floating controls anchored to the panel
                Orbiter(
                    position = ContentEdge.Bottom,
                    offset = 96.dp,
                    alignment = Alignment.CenterHorizontally
                ) {
                    Surface(shape = MaterialTheme.shapes.large) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { /* switch model */ }) {
                                Text("Helmet")
                            }
                            Button(onClick = { /* switch model */ }) {
                                Text("Astronaut")
                            }
                        }
                    }
                }
            }

            // Side info panel
            SpatialPanel(
                modifier = SubspaceModifier
                    .width(400.dp)
                    .height(800.dp)
            ) {
                Surface {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text("SceneView XR Demo", style = MaterialTheme.typography.headlineMedium)
                        Text("Filament PBR rendering inside Android XR spatial panel")
                        Text("Tap the buttons below to switch 3D models")
                    }
                }
            }
        }
    }
}
```

## 6. How it works: architecture

```
Android XR Emulator
├── XR Runtime (spatial tracking, passthrough)
├── Subspace (3D spatial container)
│   ├── SpatialPanel (2D surface in 3D space)
│   │   └── SceneView SceneView {} (Filament renderer)
│   │       ├── ModelNode (glTF/GLB models)
│   │       ├── LightNode (PBR lighting)
│   │       └── ... (all SceneView nodes work)
│   ├── Orbiter (floating controls)
│   └── SpatialRow / SpatialColumn (3D layout)
└── SpatialEnvironment (passthrough / skybox)
```

SceneView renders via Filament inside a `SpatialPanel`, which the XR runtime places
in 3D space. All existing SceneView features (model loading, animation, physics,
procedural geometry, lighting) work unchanged inside the panel.

## 7. Adaptive code: XR + phone from one APK

Use `LocalSpatialCapabilities` to detect XR at runtime:

```kotlin
import androidx.xr.compose.spatial.LocalSpatialCapabilities
import androidx.xr.compose.spatial.LocalSpatialConfiguration

@Composable
fun AdaptiveScene() {
    val isSpatial = LocalSpatialCapabilities.current.isSpatialUiEnabled

    if (isSpatial) {
        // XR: render in a spatial panel
        Subspace {
            SpatialPanel(SubspaceModifier.width(1200.dp).height(800.dp)) {
                SceneViewContent()
            }
        }
    } else {
        // Phone/tablet: render fullscreen
        SceneViewContent()
    }
}

@Composable
fun SceneViewContent() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/helmet.glb")

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
    ) {
        modelInstance?.let { ModelNode(modelInstance = it) }
    }
}
```

## 8. Tips for screenshots and recordings

1. **Use passthrough mode** for mixed-reality screenshots (toggle in emulator menu) --
   shows SceneView panel floating in a simulated room
2. **Orbit the view** with mouse drag to find the best angle showing the spatial panel
3. **Screen record** from emulator toolbar for demo videos
4. **Multi-panel layouts** (`SpatialRow`) are visually impressive for communication
5. **Add an Orbiter** with controls to show the interactive nature of spatial UI
6. For best visual quality, use **host GPU mode** (`-gpu host` flag or config setting)

## 9. Troubleshooting

| Issue | Solution |
|-------|----------|
| Emulator won't boot | Ensure emulator >= 35.6.7: `$ANDROID_HOME/emulator/emulator -version` |
| Black screen in XR | Enable GPU: set `hw.gpu.enabled=yes` and `hw.gpu.mode=host` in config.ini |
| No XR device profile in Studio | Use Android Studio Canary, or create AVD via command line (Option B above) |
| App crashes on launch | Ensure `compileSdk = 34` or higher and XR compose dependency is added |
| Filament rendering issues | Increase RAM to 4096M in AVD config; XR rendering is GPU-intensive |
| `SpatialPanel` not appearing | Wrap in `Subspace {}` and ensure Full Space mode is enabled in manifest |
| Slow performance | Close other AVDs; XR emulator is resource-intensive on ARM translation |

## 10. Current XR AVD on this machine

An AVD named `Android_XR` has been created with:

- **System image**: `system-images;android-34;google-xr;arm64-v8a` (revision 7)
- **RAM**: 4096 MB
- **GPU**: host mode (hardware acceleration)
- **Heap**: 512 MB
- **Emulator**: v35.6.11 (meets the >= 35.6.7 requirement)

Launch it with:

```bash
$ANDROID_HOME/emulator/emulator -avd Android_XR -gpu host
```

Or from Android Studio Device Manager.

## References

- [Install Android Studio for XR](https://developer.android.com/develop/xr/jetpack-xr-sdk/get-studio)
- [Run XR apps on the emulator](https://developer.android.com/develop/xr/jetpack-xr-sdk/run/emulator/xr-headsets-glasses)
- [XR Fundamentals codelab](https://developer.android.com/codelabs/xr-fundamentals-part-1)
- [Compose for XR spatial UI](https://developer.android.com/develop/xr/jetpack-xr-sdk/ui-compose)
- [SceneView Android XR integration](android-xr.md)
