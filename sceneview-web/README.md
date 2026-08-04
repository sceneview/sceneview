# SceneView Web

3D rendering for browsers using **Filament.js** (WebGL2/WASM) — the same rendering engine as SceneView Android.

## Install

```bash
npm install @sceneview/sceneview-web
```

## Quick Start

```html
<canvas id="scene-canvas" style="width:100%;height:100vh"></canvas>
<script src="sceneview-web.js"></script>
```

```kotlin
SceneView.create(
    canvas = document.getElementById("scene-canvas") as HTMLCanvasElement,
    configure = {
        camera {
            eye(0.0, 1.5, 5.0)
            target(0.0, 0.0, 0.0)
        }
        model("models/DamagedHelmet.glb")
    },
    onReady = { it.startRendering() }
)
```

## JavaScript API

For browser usage without Kotlin, load the `sceneview-web.js` bundle and use the
global `sceneview` object it registers on `window`:

```html
<script src="https://cdn.jsdelivr.net/npm/sceneview-web@4.26.0/sceneview-web.js"></script>
<script>
  sceneview.modelViewer("scene-canvas", "model.glb").then((viewer) => {
    viewer.setBackgroundColor(0.05, 0.05, 0.08, 1);
  });
</script>
```

> TypeScript declarations ship with the package (`sceneview-web.d.ts`) — the
> `sceneview` global is typed automatically when you `import "sceneview-web"`.

### Factory functions (`sceneview.*`)

| Function | Description |
|---|---|
| `sceneview.createViewer(canvasId)` | Create a viewer attached to the canvas with that DOM id |
| `sceneview.createViewerAutoRotate(canvasId, autoRotate)` | Like `createViewer` with explicit auto-rotate override |
| `sceneview.createViewerFull(canvasId, autoRotate, cameraControls, cameraX, cameraY, cameraZ, fov, lightIntensity)` | Full factory — every option in one call |
| `sceneview.modelViewer(canvasId, modelUrl)` | One-call helper: create a viewer AND load a model |
| `sceneview.modelViewerAutoRotate(canvasId, modelUrl, autoRotate)` | Like `modelViewer` with explicit auto-rotate override |
| `sceneview.version` | Library version string |

Every factory returns a `Promise<SceneViewer>`.

### Viewer instance methods

| Method | Description |
|---|---|
| `viewer.loadModel(url)` | Load a glTF/GLB model — resolves when decoded |
| `viewer.setEnvironment(iblUrl)` | Apply IBL environment lighting (KTX1) |
| `viewer.setEnvironmentWithSkybox(iblUrl, skyboxUrl)` | Apply environment lighting AND skybox |
| `viewer.setCameraOrbit(theta, phi, distance)` | Set camera orbit in spherical coordinates |
| `viewer.setCameraTarget(x, y, z)` | Set the camera look-at point |
| `viewer.setAutoRotate(enabled)` | Toggle auto-rotation |
| `viewer.setAutoRotateSpeed(speed)` | Auto-rotate angular speed (radians/sec) |
| `viewer.setZoomLimits(min, max)` | Constrain pinch-zoom range (metres) |
| `viewer.setBackgroundColor(r, g, b, a)` | Set clear color (components `0..1`) |
| `viewer.startRendering()` / `viewer.stopRendering()` | Start/stop the render loop |
| `viewer.resize(width, height)` | Resize the underlying canvas |
| `viewer.fitToModels()` | Frame the camera so every loaded model is visible |
| `viewer.dispose()` | Release Filament resources |

## Features

- Same Filament PBR renderer as Android (compiled to WASM)
- glTF 2.0 / GLB model loading
- IBL environment lighting + skybox (KTX)
- Camera configuration (FOV, position, orbit, zoom limits)
- Auto-rotation with configurable speed
- Kotlin/JS DSL API + vanilla JavaScript API

## Requirements

- WebGL2 browser (~95% coverage)
- No AR support (requires native sensors)

## Part of SceneView

SceneView is a declarative 3D/AR SDK for Android, iOS, macOS, visionOS, Web, and Desktop.

- [GitHub](https://github.com/sceneview/sceneview)
- [Documentation](https://sceneview.github.io)
