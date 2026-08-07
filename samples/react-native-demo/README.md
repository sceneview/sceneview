# SceneView React Native Demo

> **Status: Alpha**
>
> Feature showcase app for the React Native bridge to SceneView. It demonstrates
> the bridge surface that exists today — geometry nodes, light nodes, AR mode,
> and Sketchfab search. The RN bridge exposes a **subset** of the native SceneView
> SDK; see [Bridge coverage](#bridge-coverage) below and issue
> [#909](https://github.com/sceneview/sceneview/issues/909) for what is and is
> not bridged yet.

## Architecture

```
React Native (JS/TS)
  +-- Native Component --> Android: SceneView (Filament)
  +-- Native Component --> iOS: SceneViewSwift (RealityKit)
```

## Tabs

| Tab | Demonstrates |
|---|---|
| **Search** | Sketchfab API search (fetch), model viewing with `modelNodes` |
| **Geometry** | `geometryNodes` (cube, sphere, cylinder, plane), color picker, add/remove shapes |
| **Lights** | `lightNodes` (directional, point, spot), preset lighting scenes, custom light creation |
| **AR** | `ARSceneView` with `planeDetection`; `depthOcclusion` / `instantPlacement` toggles are present but not yet bridged |

## Bridge Features Demonstrated

- `SceneView` component with `environment`, `cameraControlMode`
- `ARSceneView` with plane detection
- `modelNodes` for GLB model loading
- `geometryNodes` for procedural geometry (cube, sphere, cylinder, plane) with color — **Android only**
- `lightNodes` for scene lighting (directional, point, spot) with intensity and color — **Android only**

## Bridge coverage

The RN bridge is alpha and exposes only part of the SceneView SDK. The demo
honestly surfaces what does and does not work today:

| Demoed in this app | Real bridge status |
|---|---|
| `modelNodes` (GLB loading) | Works on Android and iOS |
| `geometryNodes`, `lightNodes` | Rendered on **Android** only; iOS port pending (#909) |
| `environment` (HDR IBL) | Works on Android 3D scenes; AR uses the camera feed |
| `planeDetection` | Wired into the ARCore session on Android |
| `depthOcclusion`, `instantPlacement` | **Not bridged** — the AR tab toggles them but the props are not yet applied to the native AR `Config` (#909) |
| `onTap` | Dispatched on Android and iOS, on both views. Payload is `{ x, y, z, nodeName }`; `nodeName` is the tapped model's file base name, or `null` when the tap hit no model (always the case on `ARSceneView`, which reports a surface point). The key is present on every dispatch path, so one `nodeName == null` check covers everything — it is never `undefined` |
| `onPlaneDetected` | Dispatched on **Android** only; SceneViewSwift's `ARSceneView` exposes no plane-detection callback (#909) |

Tracked in the [#909](https://github.com/sceneview/sceneview/issues/909)
bridge-parity umbrella.

## Assets

| File | Description |
|---|---|
| `environments/studio_small.hdr` | Studio lighting environment |

### Android asset setup

For Android, assets must be accessible from the app's asset manager. Add an asset source
directory in `android/app/build.gradle`:

```groovy
android {
    sourceSets {
        main {
            assets.srcDirs += ['../../assets']
        }
    }
}
```

### iOS asset setup

For iOS, add the assets to your Xcode project's bundle resources. In your Podfile or
Xcode project settings, ensure the `environments/` directory is included as a resource bundle.

## Run

```bash
cd samples/react-native-demo
npm install
npx react-native run-android  # or run-ios
```

## Requirements

- Node.js 18+
- React Native 0.73+
- Android SDK 24+ (for Android)
- iOS 18+ (for iOS) — matches `SceneViewSwift`'s deployment floor
