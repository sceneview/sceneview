# SceneView Android TV Demo

3D model viewer optimized for Android TV with D-pad navigation.

## Features

- D-pad controls for model rotation and selection
- Model cycling through multiple 3D assets
- Auto-rotation mode
- Compose for TV UI (`androidx.tv`), declared as a Leanback launcher app

## Run

```bash
./gradlew :samples:android-tv-demo:assembleDebug
```

## Requirements

- Android TV device or emulator (API 28+ — the demo's `minSdk`)
