# SceneView Android Demo

Play Store-ready showcase app demonstrating SceneView's full feature set.

## Features

- **3-tab Material 3 Expressive UI** — Showcase · AR View · About
- **Showcase home**: a hero card, category filter chips and a grid of media cards
  (one generated light/dark preview image per demo); a closing "Browse online models"
  card opens the online gallery (Sketchfab / Icosa Gallery / Poly Haven)
- **Shared demo chrome**: edge-to-edge scene with a glass back button, identity pill
  and overflow menu on top, and a floating bottom dock whose Controls item opens the
  settings sheet — tap the scene to hide the chrome
- **50+ interactive demos** covering all node types
- 3D model viewer with fitted framing, orbit camera, HDR environments, animation
  controls and an AR handoff
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

…and launch it:

```bash
adb shell am start -n io.github.sceneview.demo/.MainActivity
```

> ⚠️ **Not `android run`.** Google's `android` CLI has a measured install
> no-op: it prints `App loaded:` / `Debuggable: true`, then rejects an activity
> the platform resolves fine, **and exits 0 having installed nothing** — leaving
> the previous build on the device. Seen three times in this repo (#2796, #2854,
> #2990). Use `adb install -r` and check the install actually landed:
> `adb shell dumpsys package io.github.sceneview.demo | grep lastUpdateTime`.

## Requirements

- Android device or emulator (API 28+) — this is the demo app's `minSdk`; the SceneView library itself supports API 24+
- For AR features: ARCore-compatible device
