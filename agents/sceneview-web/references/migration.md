# Migrating to SceneView for Web

This page covers the rewrites AI agents most often need when bringing existing
browser 3D code onto `sceneview-web`. The exhaustive API is in the
"SceneView Web" section of
[`llms.txt`](https://github.com/sceneview/sceneview/blob/main/llms.txt).

## From `<model-viewer>` (Google's web component)

`<model-viewer>` is the closest analogue — both are drop-in viewers. The
`sceneview` JS API mirrors its most common features:

| `<model-viewer>` | `sceneview-web` (plain JS) |
| --- | --- |
| `<model-viewer src="model.glb">` | `sceneview.modelViewer('canvasId', 'model.glb')` |
| `auto-rotate` attribute | `sv.setAutoRotate(true)` |
| `camera-orbit` attribute | `sv.setCameraOrbit(theta, phi, distance)` |
| `environment-image` attribute | `sv.setEnvironment(iblUrl)` |
| `min-camera-orbit` / `max-camera-orbit` | `sv.setZoomLimits(min, max)` |
| `<model-viewer>` is a custom element | `sceneview-web` renders into a `<canvas>` |

Note: `sceneview-web` renders into a plain `<canvas>` you provide, not a custom
element. filament.js must load before sceneview-web.js.

## From Three.js

| Three.js | `sceneview-web` (Kotlin/JS DSL) |
| --- | --- |
| `new THREE.WebGLRenderer({ canvas })` | `SceneView.create(canvas, …)` |
| `GLTFLoader().load(url, …)` | `sceneView.loadModel(url, onLoaded)` or `model(url) { }` |
| `new THREE.BoxGeometry(...)` + `Mesh` | `geometry { cube(); size(...) }` |
| `new THREE.DirectionalLight(...)` | `light { directional(); intensity(...) }` |
| `OrbitControls` | `cameraControls(true)` → `OrbitCameraController` |
| `RGBELoader` / `PMREMGenerator` for IBL | `environment(iblUrl, skyboxUrl)` (KTX) |
| `renderer.setAnimationLoop(...)` | `sceneView.startRendering()` (internal loop) |

`sceneview-web` uses Filament's KTX IBL format, not Three.js's `.hdr`/`.exr`
pipeline — convert environments to KTX.

## From the SceneView Android Kotlin API

The web DSL and the Compose API share concepts but not syntax. **Don't
translate Compose code character by character.**

| Android (Compose) | Web (Kotlin/JS DSL) |
| --- | --- |
| `SceneView { … }` composable | `SceneView.create(canvas, configure = { … })` |
| `rememberModelInstance(loader, path)` | `model(path) { }` in the DSL, or `loadModel(path)` |
| `ModelNode(modelInstance, …)` | `model(url) { autoAnimate(true); scale(...) }` — or the Kotlin-only `sceneView.addModelNode(url)` returning a retained `ModelNode` (#2024 slice 2, not JS-exported) |
| `CubeNode(size, material)` | `geometry { cube(); size(...); color(...) }` — or the Kotlin-only `sceneView.addCubeNode(size)` / `addSphereNode` / `addCylinderNode` / `addPlaneNode` (#2024 slice 2) |
| `LightNode(type = …, intensity = …)` | `light { directional(); intensity(...) }` |
| `rememberCameraNode` / `rememberCameraManipulator` | `camera { eye(...); target(...) }` + `cameraControls(true)` |
| `rememberEnvironment(...)` | `environment(iblUrl, skyboxUrl)` |
| AR via `ARSceneView` (ARCore) | AR via `ARSceneView` (WebXR) — different API shape |

## WebXR caveats

- WebXR `ARSceneView` / `VRSceneView` is NOT the ARCore-based Android
  `ARSceneView` — the entry point is `ARSceneView.create(...)`, not a
  composable, and hit-tests come from the WebXR Device API.
- `requestSession` only works inside a user gesture — call `create` from a
  click/tap handler.
- AR support: Chrome Android 79+, Meta Quest Browser, Safari iOS 18+. VR:
  Meta Quest Browser, desktop Chrome with a headset. Always `checkSupport`
  first and ship a non-XR fallback.

## Common mistakes and fixes

1. **Blank canvas, no errors** — filament.js was loaded after sceneview-web.js,
   or the canvas had zero dimensions. Load filament.js first; give the canvas
   explicit CSS size.
2. **`Canvas element 'id' not found`** — the `<canvas>` id passed to
   `createViewer`/`modelViewer` doesn't match the DOM element.
3. **Calling instance methods before the viewer is ready** — `createViewer` /
   `loadModel` return Promises; chain with `.then` / `await`.
4. **WebXR session rejected** — `requestSession` was called outside a user
   gesture, or the browser lacks WebXR. Gate on `checkSupport`, create inside a
   click handler.
