# SceneView Android Demo

Play Store-ready showcase app demonstrating SceneView's full feature set.

## Features

- **4-tab Material 3 Expressive UI** (3D, AR, Samples, About)
- **43 interactive demos** covering all node types
- 3D model viewer with orbit camera and HDR environments
- AR tap-to-place with plane detection
- Geometry nodes, animations, physics, dynamic sky
- Dark mode support

## Run

```bash
./gradlew :samples:android-demo:assembleDebug
```

Install the APK on a connected device:

```bash
adb install -r samples/android-demo/build/outputs/apk/debug/android-demo-debug.apk
```

…or, with Google's [`android` CLI](https://developer.android.com/tools/agents/android-cli) (atomic install + launch):

```bash
android run \
  --apks=samples/android-demo/build/outputs/apk/debug/android-demo-debug.apk \
  --activity=io.github.sceneview.demo/.MainActivity
```

## Requirements

- Android device or emulator (API 28+) — this is the demo app's `minSdk`; the SceneView library itself supports API 24+
- For AR features: ARCore-compatible device
