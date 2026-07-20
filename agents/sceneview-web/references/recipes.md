# SceneView for Web recipes

**Do not improvise.** Each pattern below maps to verified source in
`sceneview-web/src/jsMain/` and the `samples/web-demo/` app. The exhaustive API
is in the "SceneView Web" section of
[`llms.txt`](https://github.com/sceneview/sceneview/blob/main/llms.txt).

## 1. Model viewer (plain JS, no bundler)

```html
<canvas id="viewer" style="width:100%;height:100vh;display:block"></canvas>
<script src="https://sceneview.github.io/js/filament/filament.js"></script>
<script src="https://cdn.jsdelivr.net/npm/sceneview-web@4.23.0/sceneview-web.js"></script>
<script>
  sceneview.modelViewer('viewer', 'https://sceneview.github.io/models/platforms/DamagedHelmet.glb')
    .then(function (sv) {
      sv.setAutoRotate(true);
      sv.setBackgroundColor(0.05, 0.05, 0.12, 1.0);
    });
</script>
```

Entry point: `jsModelViewer` in `Main.kt`. filament.js loads first.

## 2. Custom scene (plain JS)

```js
sceneview.createViewer('viewer').then(function (sv) {
  sv.loadModel('Fox.glb').then(function () { sv.fitToModels(); });
  sv.setEnvironmentWithSkybox('studio_ibl.ktx', 'studio_skybox.ktx');
  sv.setCameraOrbit(0.6, 1.2, 4.0);
  sv.setZoomLimits(1.0, 12.0);
}).catch(function (err) {
  // createViewer's Promise rejects if Filament fails to init — always handle it.
  console.error('SceneView init failed', err);
});
```

## 3. Model viewer (Kotlin/JS DSL)

```kotlin
SceneView.create(
    canvas = canvas,
    configure = {
        camera { eye(0.0, 1.5, 5.0); target(0.0, 0.0, 0.0); fov(45.0) }
        model("models/helmet.glb") { autoAnimate(true) }
        cameraControls(true)
        autoRotate(true)
    },
    onReady = { sceneView -> sceneView.startRendering() }
)
```

Entry point: `SceneView.create` + `SceneViewBuilder` in `SceneView.kt`.

## 4. Procedural geometry (Kotlin/JS DSL)

```kotlin
SceneView.create(canvas, configure = {
    geometry { cube();     size(1.0, 1.0, 1.0); color(1.0, 0.0, 0.0, 1.0); position(0.0, 0.5, -2.0) }
    geometry { sphere();   radius(0.5);          color(0.0, 0.5, 1.0, 1.0) }
    geometry { cylinder(); radius(0.3); height(1.5); color(0.0, 1.0, 0.5, 1.0) }
    geometry { plane();    size(5.0, 5.0, 0.0);  color(0.3, 0.3, 0.3, 1.0) }
}) { sceneView -> sceneView.startRendering() }
```

## 5. Lighting and environment

```kotlin
SceneView.create(canvas, configure = {
    light {
        directional()
        intensity(100_000.0)
        color(1.0f, 1.0f, 1.0f)
        direction(0.6f, -1.0f, -0.8f)
    }
    environment("https://…/ibl.ktx", skyboxUrl = "https://…/sky.ktx")
})
```

## 6. WebXR AR — render a glTF on a detected surface

```kotlin
ARSceneView.checkSupport { supported ->
    if (supported) {
        // inside a button click handler:
        ARSceneView.create(
            canvas = canvas,
            features = WebXRSession.Features(
                required = arrayOf(XRFeature.HIT_TEST),
                optional = arrayOf(XRFeature.DOM_OVERLAY, XRFeature.LIGHT_ESTIMATION)
            ),
            onError = { msg -> console.error(msg) },
            onReady = { arView ->
                arView.onHitTest = { pose -> arView.loadModel("models/chair.glb") }
                arView.onSelect = { source -> /* user tapped */ }
                arView.start()
            }
        )
    }
}
```

Verified against `xr/ARSceneView.kt`. Session creation MUST be in a user
gesture; always provide a non-XR fallback when `checkSupport` is false.

## 7. WebXR VR

```kotlin
VRSceneView.checkSupport { supported ->
    if (supported) {
        VRSceneView.create(
            canvas = canvas,
            features = WebXRSession.Features(optional = arrayOf(XRFeature.HAND_TRACKING)),
            referenceSpaceType = XRReferenceSpaceType.LOCAL_FLOOR,
            onError = { msg -> },
            onReady = { vrView ->
                vrView.sceneView.loadModel("models/room.glb")
                vrView.onInputSelect = { source, pose -> /* trigger */ }
                vrView.start()
            }
        )
    }
}
```

Verified against `xr/ARSceneView.kt` (`VRSceneView`).

## CDN models

`https://sceneview.github.io/models/platforms/` hosts a catalog of GLBs
(DamagedHelmet.glb, Fox.glb, Avocado.glb, ToyCar.glb, …) — see `llms.txt` for
the full list. Use absolute URLs for models.

## Cross-platform parity

Android (`sceneview` skill) and iOS (`sceneview-ios` skill) expose the same node
concepts with platform-idiomatic shapes. **Don't copy-paste between platforms.**

Web now exposes a minimal `NodeHandle` imperative surface since #2024 slice 3 —
plain-JS callers create a node with `sv.addNode()` / `sv.addModelNode(url)` /
`sv.addSplatNode(url)` (3D Gaussian Splatting, `.ply`/`.spz`, #2646 P2) /
`sv.addCubeNode(size)` / `sv.addSphereNode(radius)` / `sv.addLightNode(type)`,
keep the returned handle, and mutate it after build (`setPosition`,
`setRotation` in Euler degrees, `setScale`, `setVisible`, `addChild`,
`getWorldPosition`, `destroy`). This is a first, minimal slice: no gestures, no
collision, no transform object, and `CameraNode` / `addGeometryNode` remain
Kotlin-only. Full Android/iOS node parity continues under #2024.
