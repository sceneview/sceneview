# @sceneview-sdk/react-native

[![npm version](https://img.shields.io/npm/v/@sceneview-sdk/react-native.svg)](https://www.npmjs.com/package/@sceneview-sdk/react-native)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![GitHub](https://img.shields.io/badge/GitHub-sceneview%2Fsceneview-black)](https://github.com/sceneview/sceneview)

React Native bindings for [SceneView](https://sceneview.github.io) — 3D and AR scenes powered by Filament (Android) and RealityKit (iOS).

> **Status:** Alpha — 3D model loading works on both platforms. AR scene is
> functional on Android. The iOS native bridge is CI-verified against the real
> `SceneViewSwift` API **and the real React Native API**
> (`.github/workflows/rn-ios-compile.yml`): both modules come from an actual
> build — SceneViewSwift from SwiftPM, React from the demo's `pod install` —
> and the job proves each import is load-bearing before trusting the result.
> It type-checks `ios/*.swift` only: it does not link, does not run, and does
> not compile the Obj-C `RCT_EXTERN_MODULE` glue in `ios/RNSceneViewManager.m`
> that exposes the module to JS.

## Features

- Load and display 3D models (GLB/GLTF) using native renderers
- AR scenes with plane detection — ARCore (Android) and ARKit (iOS)
- HDR environment lighting
- Orbit camera controls
- TypeScript types for all props and events
- Fallback message on unsupported platforms (web, etc.)

## Installation

```sh
npm install @sceneview-sdk/react-native
```

The `publish-rn` job publishes this bridge on every `vX.Y.Z` tag, so `latest`
on npm tracks the current SceneView release (#924, #962).

There is no GitHub-install fallback: this is a monorepo with no root
`package.json`, so `npm install github:sceneview/sceneview` cannot resolve the
module at `react-native/react-native-sceneview/`. To build against unreleased
source, clone the repo and `npm install <path-to-clone>/react-native/react-native-sceneview`.

### iOS

```sh
cd ios && pod install
```

Requires iOS 18+ and Xcode 16+ — `SceneViewSwift`'s own floor is iOS 18.0
(`SceneViewSwift/Package.swift`), so the host app's `Podfile` must declare
`platform :ios, '18.0'` rather than React Native's `min_ios_version_supported`
(13.4), or `pod install` fails to resolve this module.

**The host app must add `SceneViewSwift` via Swift Package Manager.**
`SceneViewSwift` ships as a SwiftPM package only (no CocoaPods spec), so this
module's podspec deliberately does **not** declare it as a `s.dependency` —
add it once in Xcode (*File ▸ Add Package Dependencies…*):

> **Known gap ([#3072](https://github.com/sceneview/sceneview/issues/3072)).**
> A root `SceneViewSwift.podspec` exists since the Flutter plugin moved to the
> pod route — it is unpublished on the CocoaPods trunk, not missing. The SwiftPM
> instruction below works when the module compiles with the host project's
> packages in scope, as in `samples/react-native-demo`. If your app builds this
> module inside `Pods.xcodeproj`, `import SceneViewSwift` will not resolve;
> follow #3072 for the pod-based route.

- URL: `https://github.com/sceneview/sceneview`
- Version: `4.26.0` (or *Up to Next Major*)

Unlike the Flutter plugin's pub.dev range, this version tracks the SDK release
directly: SwiftPM resolves against a git tag in this same repository, which the
release creates, so it is never ahead of something that does not exist yet.

> **Keep the two versions in step.** npm `4.27.0`'s Swift requires SPM tag
> `v4.27.0` or newer: this module's `ios/*.swift` builds on
> `SceneViewerHostView` — which landed *after* `v4.26.0`, so no `SceneViewer*`
> type exists at that tag or any earlier one — and it calls the two-parameter
> `onTapEntity` (the tapped model root is the second parameter). A mismatch is a
> loud Swift compile error at build time, not a runtime surprise, but no CI job
> here will catch it for you: `rn-ios-compile.yml` type-checks against the
> `SceneViewSwift` sources *in this repo*, not against the tag your app
> resolves.

The module's `ios/*.swift` `import SceneViewSwift` resolves against that
app-level package at build time.

### Android

Requires `minSdk 24`. SceneView is published to Maven Central — no extra repository needed:

```groovy
// android/app/build.gradle
android {
    defaultConfig {
        minSdkVersion 24
    }
}
```

## Usage

### 3D Scene

```tsx
import { SceneView } from '@sceneview-sdk/react-native';

<SceneView
  style={{ flex: 1 }}
  environment="environments/studio_small.hdr"
  modelNodes={[{ src: 'models/damaged_helmet.glb', position: [0, 0, -2] }]}
  cameraControlMode="orbit"
/>
```

### AR Scene

```tsx
import { ARSceneView } from '@sceneview-sdk/react-native';

<ARSceneView
  style={{ flex: 1 }}
  planeDetection
  modelNodes={[{ src: 'models/chair.glb', position: [0, 0, -1] }]}
/>
```

### Props — SceneView

| Prop                | Type                 | Default   | Description                              |
|---------------------|----------------------|-----------|------------------------------------------|
| `style`             | `ViewStyle`          | —         | Standard React Native style              |
| `environment`       | `string`             | —         | HDR environment asset path               |
| `modelNodes`        | `ModelNode[]`        | `[]`      | Models to render                         |
| `geometryNodes`     | `GeometryNode[]`     | `[]`      | Geometry nodes (forward-compatible)      |
| `lightNodes`        | `LightNode[]`        | `[]`      | Light nodes (forward-compatible)         |
| `cameraOrbit`       | `boolean`            | `true`    | **Deprecated**, inert on iOS — use `cameraControlMode` |
| `cameraControlMode` | `CameraControlMode`  | `'orbit'` | Camera mode (v4.3.0). `'pan'`/`'firstPerson'` are iOS-only |
| `autoCenterContent` | `boolean`            | `true`    | Auto-centre content on first frame (v4.3.0, iOS-first) |
| `onTap`             | `function`           | —         | Tap callback — `{ x, y, z, nodeName }`   |

`cameraControlMode` `'pan'` and `'firstPerson'` are iOS-only in v4.3.0; on
Android they fall back to orbit. `autoCenterContent` is iOS-first — the
Android side is tracked in issue #1051.

`cameraOrbit` is **deprecated and inert on iOS**. It predates
`cameraControlMode`, and the two contradict each other — nothing can say which
should win for `cameraOrbit: false, cameraControlMode: 'orbit'` — so the iOS
bridge reads only `cameraControlMode`. Note this leaves **no** way to freeze the
camera from this bridge on iOS: `SceneViewSwift` has `cameraGesturesEnabled`,
but the React Native surface does not expose it yet. `cameraOrbit: false` still
works on Android.

### Props — ARSceneView (extends SceneView)

| Prop                | Type       | Default | Description                               |
|---------------------|------------|---------|-------------------------------------------|
| `planeDetection`    | `boolean`  | `true`  | Enable plane detection                    |
| `depthOcclusion`    | `boolean`  | `false` | **Not yet bridged** — accepted but not configured natively (#909) |
| `instantPlacement`  | `boolean`  | `false` | **Not yet bridged** — accepted but not configured natively (#909) |
| `onPlaneDetected`   | `function` | —       | Callback when a new plane is detected     |

> ⚠️ `depthOcclusion` and `instantPlacement` are declared so the API surface is
> stable, but the native bridge does **not** yet apply them to the ARCore
> `Config`. Setting either has no visible effect today. Wiring them into the
> native AR session is tracked in the [#909](https://github.com/sceneview/sceneview/issues/909)
> bridge-parity umbrella.

### ModelNode interface

```ts
interface ModelNode {
  src: string;                             // Asset path or URL to GLB/GLTF
  position?: [number, number, number];     // World-space [x, y, z]
  rotation?: [number, number, number];     // Euler degrees [x, y, z]
  scale?: number | [number, number, number]; // Uniform or per-axis scale
  animation?: string;                      // Animation name to auto-play
}
```

### AR recording (v4.3.0 — iOS)

`ARRecorder` records an AR session to a `.mov` video (iOS via ReplayKit):

```ts
import { ARRecorder } from '@sceneview-sdk/react-native';

const recorder = new ARRecorder();

await recorder.start();
// ... later ...
const path = await recorder.stop();
await recorder.saveToPhotoLibrary(path);
```

`ARRecorder` is iOS-only; on Android every method rejects with an
`UNSUPPORTED` error (ARCore session recording is tracked in issue #1051).
Check `ARRecorder.isSupported` before use. The host iOS app must declare
`NSPhotoLibraryAddUsageDescription` in `Info.plist` for `saveToPhotoLibrary`.

## Architecture

```
React Native JS
    |
    v
requireNativeComponent('RNSceneView' / 'RNARSceneView')
    |
    +---> Android: ViewManager -> ComposeView -> SceneView { } / ARSceneView { }
    |                              (Filament, SceneView SDK)
    |
    +---> iOS 3D: RCTViewManager -> SceneViewerHostView -> SceneView
    |                                (RealityKit, SceneViewSwift — the same host
    |                                 the Flutter plugin and sceneview-compose use)
    |
    +---> iOS AR: RCTViewManager -> UIHostingController -> ARSceneView
                                     (RealityKit, SceneViewSwift)
```

Props are mapped from the React Native bridge to native view parameters on each platform.

## Limitations

This bridge currently exposes a subset of the SceneView SDK. The honest
coverage map (tracked in [#909](https://github.com/sceneview/sceneview/issues/909)):

- **`geometryNodes` / `lightNodes`** — rendered natively on **Android** only;
  the iOS RealityKit port is not yet bridged.
- **`depthOcclusion` / `instantPlacement`** — declared as props but **not
  configured natively** on either platform. Setting them has no effect today.
- **`onPlaneDetected`** — dispatched on **Android** only; SceneViewSwift's
  `ARSceneView` exposes no plane-detection callback, so it never fires on iOS.
- **`onTap`** — dispatched on both platforms and on both views. On `SceneView`
  (3D) it carries the tapped model's world position and its file base name
  without extension as `nodeName`, identically on Android and iOS. On
  `ARSceneView` *what a hit reports* differs: **Android** hit-tests the AR
  scene, so a tap on a model reports that model's file base name just as
  `SceneView` does and a plane hit or a miss reports `null`; **iOS** always
  reports `null`, because SceneViewSwift's `ARSceneView` exposes no entity
  hit-test hook and its tap can only resolve the surface point
  ([#2051](https://github.com/sceneview/sceneview/issues/2051)). Every dispatch
  path still writes the key, so `nodeName == null` is the single "the tap hit no
  model" test — it is never `undefined`. *How often* it dispatches differs
  though: on iOS a tap that hits no entity fires no `onTap` at all (RealityKit's
  gesture is entity-targeted), where Android dispatches a `0, 0, 0` miss — so
  the two platforms do not deliver the same number of tap events.
- **`environment` on `ARSceneView`** — AR scenes use the camera feed; the HDR
  environment is accepted but not applied.
- **`ModelNode.scale`** — parsed as a uniform float; the per-axis `[x, y, z]`
  array form is not yet supported.
- **`cameraControlMode` `'pan'` / `'firstPerson'`** — iOS-only; fall back to
  orbit on Android.
- Not yet bridged at all: `ViewNode` / `ImageNode` / `VideoNode` / `TextNode`,
  camera positioning, advanced AR anchors, Augmented Faces/Images, and the
  `sceneview-core` physics/collision/geometry-generation APIs.
- Only Android and iOS are supported; other platforms render a fallback message.

## Contributing

See [CONTRIBUTING.md](https://github.com/sceneview/sceneview/blob/main/.github/CONTRIBUTING.md).

### Local checks

From this directory, after `npm ci`:

```bash
npm run lint        # Biome (repo-root biome.json) — lint + format + import assists
npm run lint:fix    # same, applying the safe fixes
npm run typescript  # tsc --noEmit
npm test            # jest
```

All four run on every PR that touches this package's TypeScript, via
[`.github/workflows/rn-ts-check.yml`](https://github.com/sceneview/sceneview/blob/main/.github/workflows/rn-ts-check.yml).
They do not all cover the same files:

| check | covers |
|---|---|
| `lint` | `src/`, `__tests__/`, `example/src/` |
| `typescript` | `src/` only — that is `tsconfig.json`'s `include` |
| `test` | `__tests__/` |

The linter is **Biome**, not ESLint — the repo has a single JS/TS rule set in
the root `biome.json`, and those three directories are listed in its
`files.includes`. The `lint` scripts `cd` to the repo root before invoking
Biome, mirroring `mcp/package.json`'s script; Biome also walks up and finds the
root config on its own, so calling `./node_modules/.bin/biome check .` from
this directory works too.

Note that most rules in `biome.json` are **warning** severity, so `npm run
lint` can exit 0 with findings still printed. Read its output, don't just read
its exit code.

## License

Apache-2.0 — see [LICENSE](LICENSE) for details.
