# Try SceneView

Get the demo app running on your device in under a minute.

---

## Option 1 — One command (recommended)

Clone the repo and run:

```bash
git clone https://github.com/sceneview/sceneview.git
cd sceneview
./tools/try-demo.sh
```

That's it. The script builds the demo app and installs it on your connected Android device.

!!! tip "Requirements"
    - Android device with **USB debugging** enabled
    - **Java 17+** installed
    - **adb** on your PATH (comes with Android Studio)
    - Optional: Google's [`android` CLI](https://developer.android.com/tools/agents/android-cli)
      for atomic install + launch. When detected, the `try-demo.sh` script uses
      it automatically.

### Try a specific platform demo

```bash
./tools/try-demo.sh --sample android-demo       # Full showcase (47 demos: 17 non-AR + 30 AR)
./tools/try-demo.sh --sample android-tv-demo    # D-pad controlled TV viewer
```

Run `./tools/try-demo.sh --help` for the full list.

---

## Option 2 — Download the APK

<div class="try-download-grid">

<div class="try-download-card">
<h3>Android Demo</h3>
<p>Full showcase: 4 tabs, 47 interactive demos, 41+ node types, animations, physics, post-processing.</p>
<a href="https://github.com/sceneview/sceneview/releases/latest/download/sceneview-android-demo.apk" class="md-button md-button--primary">
Download APK
</a>
<p class="try-download-note">Debug-signed — works on any device, no Play Store needed.</p>
</div>

<div class="try-download-card">
<h3>All Platform Demos</h3>
<p>APKs for Android, Android TV. iOS and Web demos available from source.</p>
<a href="https://github.com/sceneview/sceneview/releases/latest" class="md-button">
Browse all APKs
</a>
</div>

</div>

### Install from terminal

```bash
# Download and install + launch in one line, using Google's android CLI
# (https://developer.android.com/tools/agents/android-cli)
curl -fSL -o /tmp/sceneview-android-demo.apk \
  https://github.com/sceneview/sceneview/releases/latest/download/sceneview-android-demo.apk \
  && android run \
    --apks=/tmp/sceneview-android-demo.apk \
    --activity=io.github.sceneview.demo/.MainActivity
```

…or, with the legacy `adb` toolchain:

```bash
curl -fSL -o /tmp/sceneview-android-demo.apk \
  https://github.com/sceneview/sceneview/releases/latest/download/sceneview-android-demo.apk \
  && adb install -r /tmp/sceneview-android-demo.apk
```

Or use the script's download mode (no build required):

```bash
./tools/try-demo.sh --download
```

---

## Option 3 — Android Studio

1. Open the project in Android Studio
2. Select the **`samples:android-demo`** run configuration
3. Click **Run**

---

## What's in the demo

| Tab | What it shows |
|---|---|
| **3D** | Full-screen 3D viewer — orbit camera, 8 models, 6 HDR environments |
| **AR** | Tap-to-place, plane detection, 4 AR models, gesture controls |
| **Samples** | Model viewer, geometry, animation, dynamic sky demos |
| **About** | Platform info, GitHub links |

<div class="try-demo-features">

<div class="try-feature">
<strong>11 models</strong><br>
Khronos PBR demos, Sketchfab realistic & animated characters
</div>

<div class="try-feature">
<strong>6 HDR environments</strong><br>
Night, studio, warm, sunset, outdoor, autumn
</div>

<div class="try-feature">
<strong>41+ node types</strong><br>
Model, Light, Cube, Sphere, Text, Path, Video, View...
</div>

<div class="try-feature">
<strong>60fps on mid-range</strong><br>
Filament rendering engine, optimized for mobile
</div>

</div>

---

## Build from source

```bash
# Clone
git clone https://github.com/sceneview/sceneview.git
cd sceneview

# Build just the demo
./gradlew :samples:android-demo:assembleDebug

# Build all samples
./gradlew assembleDebug

# Run lint
./gradlew :samples:android-demo:lint
```

The APK lands in `samples/android-demo/build/outputs/apk/debug/`.
