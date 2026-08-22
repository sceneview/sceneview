---
name: sceneview-web
description: Build 3D and WebXR (AR/VR) experiences in the browser with SceneView for Web — Filament.js (WebGL2/WASM) wrapped in a Kotlin/JS DSL and a plain-JavaScript API on `window.sceneview`. Use whenever the user asks for "3D in the browser", "a web model viewer", "WebXR AR/VR", or any browser 3D/AR app where the dependency is the `sceneview-web` npm package or its CDN script. For Jetpack Compose use the `sceneview` skill; for SwiftUI use `sceneview-ios`. Skip for raw Three.js / Babylon.js / model-viewer / A-Frame work.
license: Apache-2.0
metadata:
  author: SceneView
  source: https://github.com/sceneview/sceneview
  last-updated: '2026-07-07'
  keywords:
  - sceneview
  - sceneview-web
  - 3d
  - web
  - webxr
  - webgl
  - wasm
  - filament
  - filament.js
  - model viewer
  - augmented reality
  - virtual reality
  - kotlin/js
  - gltf
  - glb
---

## What SceneView for Web is

SceneView for Web is the browser half of the SceneView SDK. It renders with
**Filament.js** — the same Filament engine as SceneView Android, compiled to
**WebAssembly + WebGL2**. It ships two API surfaces:

- **Kotlin/JS DSL** — `SceneView.create(canvas, configure = { … })` with a
  type-safe builder, plus `ARSceneView` / `VRSceneView` / `WebXRSession` for
  WebXR. This is the source-of-truth API; the package is built from
  `sceneview-web/src/jsMain/`.
- **Plain-JavaScript API** — when loaded via a `<script>` tag the library
  registers itself on `window.sceneview`, exposing `createViewer`,
  `modelViewer`, etc. for use with no bundler and no Kotlin.

- **npm package** — `sceneview-web` (currently `4.31.0`).
- **Renderer** — Filament.js (WebGL2/WASM). Requires Chrome 79+, Edge 79+,
  Firefox 78+, Safari 15+.

## Authoritative API reference

**Always treat `llms.txt` in the repo root as the source of truth** — its
"SceneView Web (Kotlin/JS + Filament.js)" section carries the complete DSL,
the JS API, the WebXR `ARSceneView` / `VRSceneView` / `WebXRSession` surface,
and the threading rules.
<https://github.com/sceneview/sceneview/blob/main/llms.txt>

The Kotlin/JS source lives in `sceneview-web/src/jsMain/kotlin/io/github/sceneview/web/`
— `SceneView.kt` (the DSL + `SceneViewBuilder`), `SceneViewJS.kt` (the
JS-facing `SceneViewer` class), `Main.kt` (the `window.sceneview` bindings),
and `xr/` (WebXR). The `samples/web-demo/` app is a working reference.

## When to use this skill

Trigger on any of:

- "Render a glTF / GLB model in a browser."
- "Build a web 3D viewer / product configurator."
- "Add WebXR AR or VR to a web page."
- "Embed a 3D scene with no build step / just a `<script>` tag."
- "Use SceneView from Kotlin/JS."

Skip for raw Three.js, Babylon.js, `<model-viewer>`, A-Frame, or PlayCanvas
work that does NOT use `sceneview-web`.

## Setup — two ways

### Script tag (no bundler)

filament.js MUST load before sceneview-web.js:

```html
<canvas id="viewer" style="width:100%;height:100vh;display:block"></canvas>
<script src="https://sceneview.github.io/js/filament/filament.js"></script>
<script src="https://cdn.jsdelivr.net/npm/sceneview-web@4.31.0/sceneview-web.js"></script>
<script>
  sceneview.modelViewer('viewer', 'https://sceneview.github.io/models/platforms/DamagedHelmet.glb')
    .then(function (sv) { sv.setAutoRotate(true); });
</script>
```

### npm

```
npm install sceneview-web filament
```

## The minimal correct example — plain JS

The JS API is registered on `window.sceneview` after the script loads.
Verified against `Main.kt` (`jsModelViewer`, `jsCreateViewer`):

```js
// Simplest — creates a viewer and loads a model
sceneview.modelViewer('viewer', 'model.glb')
  .then(function (sv) {
    sv.setAutoRotate(true);
    sv.setBackgroundColor(0.05, 0.05, 0.12, 1.0);  // RGBA 0-1
    sv.setEnvironment('studio_ibl.ktx');
  });

// More control
sceneview.createViewer('viewer').then(function (sv) {
  sv.loadModel('model.glb').then(function () { sv.fitToModels(); });
}).catch(function (err) {
  // The Promise REJECTS if Filament fails to initialize — handle it,
  // don't assume createViewer always resolves.
  console.error('SceneView init failed', err);
});
```

`SceneViewer` instance methods (from `SceneViewJS.kt`): `loadModel(url)` →
`Promise`, `setEnvironment`, `setEnvironmentWithSkybox`, `setCameraOrbit`,
`setCameraTarget`, `setAutoRotate`, `setAutoRotateSpeed`, `setZoomLimits`,
`setBackgroundColor`, `fitToModels(margin?)` (margin = iOS-style multiplier on the fit
distance, `1` default, clamped `0.2…10`, #2946), `startRendering`, `stopRendering`,
`resize`, `dispose`.

Since #2024 slice 3 the viewer also exposes a minimal **imperative node
surface**: `addNode()`, `addModelNode(url)` (→ `Promise<NodeHandle>`),
`addSplatNode(url)` (→ `Promise<NodeHandle>`, 3D Gaussian Splatting, `.ply`/`.spz`, #2646 P2),
`addCubeNode(size)`, `addSphereNode(radius)`, `addLightNode("directional" |
"point" | "spot")` each return an opaque `NodeHandle` you keep to mutate content
after build (`setPosition`, `setRotation` in Euler degrees, `setScale`,
`setScaleUniform`, `setVisible`, `visible`, `addChild`, `removeChild`,
`getWorldPosition`, `destroy`); `removeNode(handle)` detaches and frees it. This
is the thing the builder DSL cannot do. See `references/cheatsheet.md` for the
full signatures. `CameraNode` and `addGeometryNode` remain Kotlin-only.

```js
const cube = sv.addCubeNode(0.2);
cube.setPosition(0, 1, 0);
```

Since #2024 P5c the viewer also picks nodes under a screen point:
`sv.hitTest(x, y)` (canvas pixels, (0,0) = top-left) unprojects through the
live camera and tests every node's real bounds (model/geometry: asset AABB;
splat: cloud bounds) at their current world transform. It returns the **same**
`NodeHandle` instances the factories returned, nearest-first — so `===`
against your kept references works:

```js
canvas.addEventListener('click', (e) => {
  // Scale CSS event coords to backing-store pixels (devicePixelRatio canvases).
  const px = canvas.width / canvas.clientWidth;
  const hits = sv.hitTest(e.offsetX * px, e.offsetY * px);
  if (hits[0] === cube) cube.setScaleUniform(1.5);
});
```

Model nodes become pickable once loaded; geometry and splat nodes
immediately; empty pivots, lights and cameras are never hit.

Since #2646 P2 the viewer also renders **3D Gaussian Splatting** (radiance-field
captures — Scaniverse / Polycam / Luma / INRIA): `addSplatNode(url)` fetches a
`.ply` (INRIA) or `.spz` (Niantic) file, parses it through the shared KMP
`sceneview-core` parsers, and returns a `Promise<NodeHandle>` that resolves once
the cloud is in the scene. Same Filament engine as Android, WebGL2 backend, same
rendering: camera-facing gaussian discs (instanced quads, per-splat data in
RGBA16F textures, premultiplied-alpha blending), with a back-to-front painter's
sort that re-runs on camera motion. Kotlin/JS additionally exposes
`SceneView.addSplatNode(splatCloud)` for an already-parsed
`io.github.sceneview.core.splat.SplatCloud`.

```js
sv.addSplatNode('models/splats/capture.ply').then((splat) => {
  splat.setPosition(0, 1, 0);   // it's a NodeHandle like any other
});
```

Current scope (P2): isotropic billboards (max-axis scale — anisotropic
screen-space ellipses are planned) and SH degree-0 colour, matching Android P1.

## The minimal correct example — Kotlin/JS DSL

Verified against `SceneView.kt` (`create`, `SceneViewBuilder`):

```kotlin
SceneView.create(
    canvas = canvas,                 // HTMLCanvasElement
    configure = {
        camera {
            eye(0.0, 1.5, 5.0)
            target(0.0, 0.0, 0.0)
            fov(45.0)
        }
        light {
            directional()
            intensity(10_000.0)   // lux — read under the photometric default
                                  // exposure (f/12, 1/200s, ISO 200)
            direction(0.6f, -1.0f, -0.8f)
        }
        model("models/helmet.glb") { autoAnimate(true) }
        cameraControls(true)
        autoRotate(true)
    },
    onError = { error -> console.error(error) },  // init failed — wire to your reject path
    onReady = { sceneView -> sceneView.startRendering() }
)
```

## The minimal correct WebXR AR example

Verified against `xr/ARSceneView.kt`. AR session creation MUST happen inside a
user-gesture handler (a click/tap listener):

```kotlin
ARSceneView.checkSupport { supported ->
    if (supported) {
        // call ARSceneView.create from a click handler
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

WebXR VR uses the same shape via `VRSceneView`; `WebXRSession` is the
lower-level unified AR+VR API. See `llms.txt § WebXR`.

### Anchoring content to the real world (`XRAnchorNode`)

Verified against `xr/XRAnchorNode.kt`. For AR content that must stay pinned to
a real-world spot across frames, create an anchor from a hit-test result and
`drive(...)` a **root** scene-graph node with it — the node's `worldTransform`
then follows the anchor's per-frame pose, and children parented under it
compose for free (requires the `XRFeature.ANCHORS` session feature):

```kotlin
hit.createAnchor().then { anchor: XRAnchor ->
    val anchorNode = XRAnchorNode(anchor)
    anchorNode.drive(sceneView.addModelNode("models/chair.glb"))
    anchors += anchorNode
}
// Per-frame — update() returns false once the anchor is lost:
webXrSession.onFrame = { frame, _ ->
    anchors.removeAll { !it.update(frame, webXrSession.referenceSpace) }
}
```

Contract: `drive` throws on a parented or destroyed node (anchor poses are
world-space — they must not compose through a parent) and on a detached
anchor; a node re-parented later is skipped with a one-time console warning;
a destroyed node is auto-released. `stopDriving()` releases the node and keeps
its last pose; `detach()` releases the underlying anchor.

## Critical rules (verified — do not break)

1. **Load filament.js BEFORE sceneview-web.js.** The library needs the WASM
   Filament module present at init. Use `<script>` tags, not ES imports, for
   the no-bundler path.

2. **The canvas must have non-zero pixel dimensions.** `createViewerImpl`
   falls back to `clientWidth`/`clientHeight` if `width`/`height` are 0, so the
   canvas must be laid out (e.g. `100vw`/`100vh` or fixed px) before
   `createViewer` runs.

3. **Everything is async.** `SceneView.create` and `loadModel` are
   Promise-based — `.then(...)`/`await` them before calling instance methods.
   `loadModel`'s `onLoaded` fires only once external textures are fetched.

4. **WebXR session creation must be in a user gesture.** Browsers reject
   `requestSession` outside a click/tap handler. Always `checkSupport` first,
   then call `ARSceneView.create` / `VRSceneView.create` from the handler.

5. **One JS main thread.** There are no background threads in browser JS — all
   Filament calls run on the main thread. Never call `destroy()`/`dispose()`
   inside an animation-frame callback; defer to the next microtask.

6. **WebXR support is partial.** AR: Chrome Android 79+, Meta Quest Browser,
   Safari iOS 18+. VR: Meta Quest Browser, desktop Chrome with a headset.
   Always gate on `checkSupport` and provide a non-XR fallback.

7. **In-app scene→MP4 recording is Android-only for now.** Android's
   `SurfaceMirrorer` / `rememberSurfaceMirrorer()` (#2626) has no Web port yet
   (coming soon). To record on Web today, capture the render `<canvas>` with the
   platform-native `canvas.captureStream()` + `MediaRecorder`.

## Haptic feedback

`sceneview-web` exposes a `SceneViewHaptic` class that wraps the browser
[Vibration API](https://developer.mozilla.org/docs/Web/API/Vibration_API)
(`navigator.vibrate(...)`) behind the same seven semantic presets as the
Android and iOS libraries, so cross-platform code paths stay symmetric.

```js
// Plain JS — `sceneview.haptic` is a ready-to-use singleton:
sceneview.haptic.light();              // tap
sceneview.haptic.success();            // confirmation
sceneview.haptic.continuous(1.0, 200); // 200 ms; intensity ignored on Web
sceneview.haptic.pattern([10, 50, 20]);// custom on/off durations (ms)
```

- Presets: `light()` `medium()` `heavy()` `success()` `warning()`
  `error()` `selection()`. Plus `continuous(intensity, durationMs)` and
  `pattern(durationsMs[])`.
- The Web Vibration API exposes **durations only** — there is no intensity
  knob — so the `intensity` argument is accepted for cross-platform parity
  but ignored at runtime.
- Desktop browsers and Safari on iOS do not expose `navigator.vibrate`;
  every call is then a silent no-op. Some browsers also restrict
  vibration to user-gesture handlers. See `llms.txt § Haptic Feedback`.

## Performance / hot paths

**Preallocate scratch arrays and mutate them in place — never build fresh
`[x, y, z]` / `float3(...)` / mat4 array literals inside a `requestAnimationFrame`
tick.** Filament.js reads the array synchronously, so reuse is safe, and the small
JS heap on iOS Safari turns per-frame allocation into a GC sawtooth that drops
frames. SceneView's own `OrbitCameraController` keeps `eyeScratch` / `centerScratch`
/ `upScratch` and rewrites them per frame instead of allocating — follow that
pattern in your render loop. Full cross-platform guidance:
[`docs/docs/performance.md` § Hot Paths & Allocation-Free APIs](https://github.com/sceneview/sceneview/blob/main/docs/docs/performance.md)
(audit umbrella [#2263](https://github.com/sceneview/sceneview/issues/2263)).

## Resources

- **[Cheat sheet](./references/cheatsheet.md)** — the Kotlin/JS DSL, the JS API,
  and the WebXR surface, with signatures pulled from `sceneview-web/src/`.
- **[Recipes](./references/recipes.md)** — model viewer, custom scene,
  procedural geometry, WebXR AR/VR — each with the verified entry point.
- **[Migration](./references/migration.md)** — Three.js / `<model-viewer>` →
  `sceneview-web`, and cross-platform parity notes.

## Workflow guidance

When the user asks for a SceneView-Web feature:

1. **Pick the API surface.** Plain JS (`window.sceneview`, script tag, no
   build) vs Kotlin/JS DSL (`SceneView.create`, bundler). Match the user's
   stack — don't give Kotlin to a vanilla-JS project.
2. **Load filament.js before sceneview-web.js** in any HTML you generate.
3. **Give the canvas explicit dimensions.**
4. **Treat creation and `loadModel` as async** — chain with `.then`/`await`.
5. **For WebXR**, `checkSupport` first, create inside a click handler, and
   provide a non-XR fallback path.
6. **Read `llms.txt § SceneView Web`** for the full surface before inventing an
   API. The DSL, JS API, and WebXR sections are exhaustive.
