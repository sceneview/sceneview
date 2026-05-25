# SceneView for Web cheat sheet

Verified against `sceneview-web/src/jsMain/kotlin/io/github/sceneview/web/`
and the "SceneView Web" section of `llms.txt`. When in doubt, read the
Kotlin/JS source or `llms.txt` — do not improvise.

## Two API surfaces

| Surface | Entry point | Use when |
| --- | --- | --- |
| Plain JavaScript | `window.sceneview.*` | `<script>` tag, no bundler, no Kotlin |
| Kotlin/JS DSL | `SceneView.create(canvas, configure, onReady)` | Kotlin/JS project with a bundler |

## Setup

```html
<canvas id="viewer" style="width:100%;height:100vh;display:block"></canvas>
<script src="https://sceneview.github.io/js/filament/filament.js"></script>
<script src="https://cdn.jsdelivr.net/npm/sceneview-web@4.15.2/sceneview-web.js"></script>
```

filament.js MUST load first. npm: `npm install sceneview-web filament`.

## Plain-JavaScript API (`window.sceneview`)

```js
sceneview.modelViewer(canvasId, modelUrl)                  // → Promise<SceneViewer>
sceneview.modelViewerAutoRotate(canvasId, modelUrl, autoRotate)
sceneview.createViewer(canvasId)                           // autoRotate + controls on
sceneview.createViewerAutoRotate(canvasId, autoRotate)
sceneview.createViewerFull(canvasId, autoRotate, cameraControls,
                           cameraX, cameraY, cameraZ, fov, lightIntensity)
sceneview.version                                          // version string
```

### `SceneViewer` instance methods

```js
sv.loadModel(url)                       // → Promise<url>
sv.setEnvironment(iblUrl)
sv.setEnvironmentWithSkybox(iblUrl, skyboxUrl)
sv.setCameraOrbit(theta, phi, distance) // radians
sv.setCameraTarget(x, y, z)
sv.setAutoRotate(enabled)
sv.setAutoRotateSpeed(radiansPerFrame)
sv.setZoomLimits(min, max)
sv.setBackgroundColor(r, g, b, a)       // 0-1 range
sv.fitToModels()
sv.startRendering()
sv.stopRendering()
sv.resize(width, height)
sv.dispose()                            // release all GPU resources
```

## Kotlin/JS DSL

```kotlin
SceneView.create(
    canvas: HTMLCanvasElement,
    assets: Array<String> = emptyArray(),     // KTX URLs to preload
    configure: SceneViewBuilder.() -> Unit = {},
    onReady: (SceneView) -> Unit
)
```

### `SceneViewBuilder` (the `configure` block)

```kotlin
SceneView.create(canvas, configure = {
    camera {
        eye(0.0, 1.5, 5.0)
        target(0.0, 0.0, 0.0)
        up(0.0, 1.0, 0.0)
        fov(45.0)               // degrees
        near(0.1); far(1000.0)
        exposure(1.1)           // or exposure(aperture, shutterSpeed, sensitivity)
    }
    light {
        directional()           // or point() / spot()
        intensity(100_000.0)
        color(1.0f, 1.0f, 1.0f)
        direction(0.6f, -1.0f, -0.8f)
        // point/spot: position(x, y, z)
    }
    model("models/helmet.glb") {
        autoAnimate(true)
        scale(1.0f)
        onLoaded { asset -> /* FilamentAsset */ }
    }
    geometry {
        cube()                  // or sphere() / cylinder() / plane()
        size(1.0, 1.0, 1.0)
        color(1.0, 0.0, 0.0, 1.0)
        unlit()                 // optional — flat color, ignores lighting
        position(0.0, 0.5, -2.0)
        rotation(0.0, 45.0, 0.0)
        scale(1.0)
    }
    environment("ibl.ktx", skyboxUrl = "sky.ktx")
    noEnvironment()
    cameraControls(true)
    autoRotate(true)
})
```

### `SceneView` instance methods (Kotlin/JS)

```kotlin
sceneView.loadModel(url, onLoaded)
sceneView.loadEnvironment(iblUrl, skyboxUrl)
sceneView.loadDefaultEnvironment()
sceneView.addLight(config: LightConfig)
sceneView.addGeometry(config: GeometryConfig)
sceneView.enableCameraControls(distance, targetX, targetY, targetZ, autoRotate)
sceneView.fitToModels()
sceneView.resize(width, height)
sceneView.startRendering(); sceneView.stopRendering(); sceneView.destroy()
```

Geometry DSL types: `cube` (`size(w,h,d)`), `sphere` (`radius(r)`),
`cylinder` (`radius(r)` + `height(h)`), `plane` (`size(w,h,0)`).

## `OrbitCameraController`

Attached automatically when `cameraControls(true)`. Left-drag = orbit,
right-drag = pan, scroll = zoom. Tunable: `theta`, `phi`, `distance`,
`minDistance`, `maxDistance`, `autoRotate`, `autoRotateSpeed`,
`enableDamping`, `dampingFactor`, plus `target(x,y,z)`.

## WebXR — `ARSceneView` (browser AR)

```kotlin
ARSceneView.checkSupport { supported -> }
ARSceneView.create(
    canvas, features = WebXRSession.Features(...),
    onError = { msg -> }, onReady = { arView -> }
)
// arView: onHitTest, onSelect, onSessionEnd, start(), stop(), loadModel(url), sceneView
```

## WebXR — `VRSceneView` (browser VR)

```kotlin
VRSceneView.checkSupport { supported -> }
VRSceneView.create(canvas, features, referenceSpaceType, onError, onReady)
// vrView: onFrame, onInputSelect, onInputSqueeze, onSessionEnd, start(), stop()
```

## WebXR — `WebXRSession` (low-level, AR + VR unified)

```kotlin
WebXRSession.checkSupport(mode = XRSessionMode.IMMERSIVE_AR) { supported -> }
WebXRSession.create(canvas, mode, features, referenceSpaceType, onError, onReady)
```

`XRFeature`: `HIT_TEST`, `DOM_OVERLAY`, `LIGHT_ESTIMATION`, `HAND_TRACKING`.
`XRSessionMode`: `IMMERSIVE_AR`, `IMMERSIVE_VR`.
`XRReferenceSpaceType`: `LOCAL_FLOOR`, `LOCAL`, `VIEWER`, `BOUNDED_FLOOR`,
`UNBOUNDED`.

## Threading

One JS main thread — all Filament calls run there. `create` / `loadModel` are
async; await them. Never `destroy()`/`dispose()` inside an animation-frame
callback.

## Other platforms

Android → `sceneview` skill. iOS/macOS/visionOS → `sceneview-ios` skill.
