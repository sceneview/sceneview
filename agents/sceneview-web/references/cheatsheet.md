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
<script src="https://cdn.jsdelivr.net/npm/sceneview-web@4.23.0/sceneview-web.js"></script>
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

### Node scene-graph factories (`window.sceneview`, since #2024 slice 3)

Each factory adds a retained node to the scene and returns an opaque
`NodeHandle` you keep to mutate the content imperatively after `create()` —
the thing the fire-and-forget builder DSL cannot do. Angles are Euler degrees.

```js
sv.addNode()                            // → NodeHandle — empty pivot / grouping transform
sv.addModelNode(url)                    // → Promise<NodeHandle> (resolves once loaded)
sv.addSplatNode(url)                    // → Promise<NodeHandle> (3D Gaussian Splatting; .ply/.spz)
sv.addCubeNode(size)                    // → NodeHandle (content already in scene)
sv.addSphereNode(radius)                // → NodeHandle
sv.addLightNode(type)                   // type: "directional" | "point" | "spot" — else throws
sv.removeNode(handle)                   // detach + free the node's Filament entity
```

#### `NodeHandle` methods

```js
h.setPosition(x, y, z)                  // parent space (world for a root node)
h.setRotation(x, y, z)                  // Euler degrees, ZYX (same as Android Node.rotation)
h.setScale(x, y, z)                     // non-uniform
h.setScaleUniform(s)                    // uniform — the common case
h.setVisible(v)                         // content node: actually adds/removes from the scene
h.visible                               // Boolean getter
h.addChild(child)                       // parent `child` under `h` (child keeps its local transform)
h.removeChild(child)
h.getWorldPosition()                    // → [x, y, z] composed through the parent chain
h.destroy()                             // remove from graph + free its Filament entity (idempotent)
```

```js
const cube = sv.addCubeNode(0.2);
cube.setPosition(0, 1, 0);
sv.addModelNode("models/helmet.glb").then(model => model.addChild(cube));
```

## Kotlin/JS DSL

```kotlin
SceneView.create(
    canvas: HTMLCanvasElement,
    assets: Array<String> = emptyArray(),     // KTX URLs to preload
    configure: SceneViewBuilder.() -> Unit = {},
    onError: ((Throwable) -> Unit)? = null,    // init failed — wire to your reject path
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
sceneView.addGeometry(config: GeometryConfig)  // returns FilamentAsset?
sceneView.enableCameraControls(distance, targetX, targetY, targetZ, autoRotate)
sceneView.fitToModels()
sceneView.resize(width, height)
sceneView.startRendering(); sceneView.stopRendering(); sceneView.destroy()

// Kotlin node factories — each returns a retained, transformable node
// mirroring the Android name. Since #2024 slice 3, addModelNode / addCubeNode /
// addSphereNode / addLightNode are ALSO reachable from plain JS as the
// `sv.*` → NodeHandle factories above (returning an opaque handle, not the
// Kotlin node). The rest below stay Kotlin-only — NOT in the plain-JS export:
sceneView.addModelNode(url, parent, autoAnimate, scale, onLoaded)  // ModelNode  (JS: sv.addModelNode(url))
sceneView.addGeometryNode(config)                                  // GeometryNode (Kotlin-only)
sceneView.addCubeNode(size); sceneView.addSphereNode(radius)       // (JS: sv.addCubeNode/addSphereNode)
sceneView.addCylinderNode(radius, height); sceneView.addPlaneNode(sizeX, sizeZ)  // Kotlin-only
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

`XRFeature`: `HIT_TEST`, `DOM_OVERLAY`, `LIGHT_ESTIMATION`, `HAND_TRACKING`,
`DEPTH_SENSING`, `IMAGE_TRACKING`, `ANCHORS`, `PLANE_DETECTION`,
`MESH_DETECTION`, `LAYERS`.
`XRSessionMode`: `IMMERSIVE_AR`, `IMMERSIVE_VR`.
`XRReferenceSpaceType`: `LOCAL_FLOOR`, `LOCAL`, `VIEWER`, `BOUNDED_FLOOR`,
`UNBOUNDED`.

## Threading

One JS main thread — all Filament calls run there. `create` / `loadModel` are
async; await them. Never `destroy()`/`dispose()` inside an animation-frame
callback.

## Other platforms

Android → `sceneview` skill. iOS/macOS/visionOS → `sceneview-ios` skill.
