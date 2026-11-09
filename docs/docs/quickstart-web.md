---
title: Web Quickstart — show a 3D model in the browser with SceneView
description: "Show a glTF / GLB model in a web page with sceneview-web (Filament.js, WebGL2/WASM): npm install or two script tags, then SceneView.modelViewer, from plain JavaScript or Kotlin/JS."
---

# Web Quickstart

SceneView Web uses **Filament.js** — the same Filament rendering engine as SceneView Android, compiled to WebAssembly for browsers (WebGL2).

## Install

```bash
npm install sceneview-web
```

Or use the Kotlin/JS module directly in your Gradle project:

```kotlin
// build.gradle.kts
kotlin {
    js(IR) { browser() }
    sourceSets {
        jsMain.dependencies {
            implementation(project(":sceneview-web"))
        }
    }
}
```

## Minimal Example

### HTML

```html
<!DOCTYPE html>
<html>
<head><title>SceneView Web</title></head>
<body>
    <canvas id="scene-canvas" style="width:100%;height:100vh"></canvas>
    <script src="your-app.js"></script>
</body>
</html>
```

### Kotlin/JS

```kotlin
import io.github.sceneview.web.SceneView
import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement

fun main() {
    val canvas = document.getElementById("scene-canvas") as HTMLCanvasElement
    canvas.width = canvas.clientWidth
    canvas.height = canvas.clientHeight

    SceneView.create(
        canvas = canvas,
        configure = {
            camera {
                eye(0.0, 1.5, 5.0)
                target(0.0, 0.0, 0.0)
                fov(45.0)
            }
            light {
                directional()
                intensity(10_000.0)   // lux — read under the photometric
                                      // default exposure (f/12, 1/200s, ISO 200)
            }
            model("models/ToyCar.glb")
        },
        onError = { error ->
            // Init runs async — a thrown error can't propagate to the caller.
            // Handle it here (the createViewer JS wrapper rejects its Promise).
            console.error("SceneView init failed", error)
        },
        onReady = { sceneView ->
            sceneView.startRendering()
        }
    )
}
```

## API Overview

| Class | Purpose |
|---|---|
| `SceneView` | Main entry — `create(canvas, configure, onReady)` |
| `SceneViewBuilder` | DSL: `camera {}`, `light {}`, `model()`, `environment()` |
| `CameraConfig` | Position, FOV, clip planes, exposure |
| `LightConfig` | Type (directional/point/spot), intensity, color |
| `ModelConfig` | URL, scale, animation |

## Imperative node API (plain JavaScript)

The `configure {}` builder DSL is fire-and-forget: it describes a static scene
once. Since [#2024](https://github.com/sceneview/sceneview/issues/2024) slice 3
the plain-JavaScript viewer (`window.sceneview`) also exposes a minimal
**imperative node surface** for scenes that mutate at runtime. Each factory adds
a retained node and returns an opaque `NodeHandle` you keep to move, rotate,
scale, hide, re-parent, or destroy the content **after** it is built:

```js
sceneview.createViewer("scene-canvas").then(function (sv) {
  const cube = sv.addCubeNode(0.2);        // → NodeHandle, content already in scene
  cube.setPosition(0, 1, 0);
  cube.setRotation(0, 45, 0);              // Euler degrees (ZYX)
  cube.setScaleUniform(1.5);

  sv.addModelNode("models/ToyCar.glb").then(function (model) {
    model.addChild(cube);                  // cube now follows the model
  });
});
```

Factories: `addNode()` (empty pivot), `addModelNode(url)` (→ `Promise<NodeHandle>`),
`addCubeNode(size)`, `addSphereNode(radius)`,
`addLightNode("directional" | "point" | "spot")`, `addSplatNode(url)`
(→ `Promise<NodeHandle>`, see below), and `removeNode(handle)`.
`NodeHandle` methods: `setPosition` / `setRotation` (Euler degrees) / `setScale`
/ `setScaleUniform` / `setVisible` / `visible` / `addChild` / `removeChild` /
`getWorldPosition` / `destroy`. This is a first, minimal slice — no gestures or
collision yet, and `CameraNode` / `addGeometryNode` stay Kotlin-only. Full
Android/iOS node parity continues under #2024.

## Gaussian Splatting

Since [#2646](https://github.com/sceneview/sceneview/issues/2646) P2 the web
viewer renders **3D Gaussian Splatting** captures (Scaniverse, Polycam, Luma,
INRIA trainers) with the same Filament engine and the same rendering design as
Android — camera-facing gaussian discs, per-splat data in `RGBA16F` textures,
premultiplied-alpha blending, and a back-to-front painter's sort that re-runs as
the camera moves.

```js
sceneview.createViewer("scene-canvas").then(function (sv) {
  sv.addSplatNode("models/splats/capture.ply").then(function (splat) {
    splat.setPosition(0, 1, 0);   // a NodeHandle like any other
  });
});
```

`.ply` (INRIA binary little-endian) and `.spz` (Niantic) are auto-detected and
parsed by the shared KMP `sceneview-core` parsers — the same code path Android
uses. Kotlin/JS additionally exposes `SceneView.addSplatNode(splatCloud)` for an
already-parsed `io.github.sceneview.core.splat.SplatCloud`.

Current web scope: **isotropic** billboards (each gaussian renders as the
circumscribed disc of its largest axis) and SH degree-0 colour. Android already
renders oriented ellipses; the web port is tracked in
[#4046](https://github.com/sceneview/sceneview/issues/4046).

## Environment Lighting

```kotlin
environment(
    iblUrl = "environments/pillars_2k_ibl.ktx",
    skyboxUrl = "environments/pillars_2k_skybox.ktx"
)
```

## A scene of several models on a phone budget

The script-tag viewer (`https://sceneview.github.io/js/sceneview.js`, global `SceneView`,
not the `sceneview-web` package above) loads a whole set — one primary model, props, an
environment — and keeps it smooth on a mid-range phone:

```html
<canvas id="viewer" style="width:100%;aspect-ratio:4/3"></canvas>
<script src="https://sceneview.github.io/js/filament/filament.js"></script>
<script src="https://sceneview.github.io/js/sceneview.js"></script>
<script>
  SceneView.create('viewer', { autoRotate: false }).then(function (sv) {
    var scene = sv.loadScene({
      environment: { url: '/environments/studio_showroom_light_ibl.ktx', intensity: 30000 },
      camera: { target: [0, 0.45, 0], angle: 0.5, height: 1.5, radius: 4.2 },
      models: [
        { id: 'fox', role: 'primary', src: '/models/fox.glb', scale: 0.008,
          position: [0.2, 0, 0.7], rotation: [0, 60, 0], animation: 'Survey',
          bytes: 162852, triangles: 576 },
        { id: 'sofa', src: '/models/sofa.glb',
          lod: { mid: '/models/sofa-mid.glb', low: '/models/sofa-low.glb' },
          position: [0, 0, -0.7], bytes: 3149844, triangles: 4196 },
        { id: 'lantern', src: '/models/lantern.glb', optional: true, position: [-1.2, 0, 1] }
      ]
    }, { onProgress: function (e) { console.log(e.type, e.id); } });
    scene.complete.then(function (stats) { console.log(stats); });
  });
</script>
```

- **Order.** `loadScene()` returns at once. The environment and the primary model load
  first; the rest waits until the canvas is on screen and the page is idle, and each model
  is built in its own idle period. Keep a poster image in the HTML until `scene.primary`
  resolves. Loading stops when the visitor leaves the page or the canvas leaves the
  document; `scene.abort()` and `scene.remove()` do it by hand.
- **Budget.** `sv.deviceTier()` reads the device memory, cores, pixel ratio, Save-Data and
  the GPU, and picks a tier:

| Tier | Typical device | Download | Triangles | Texture edge | Pixel ratio | `optional` |
|---|---|---|---|---|---|---|
| low | 2 GB, Save-Data, 3G, old GPU | 5 MB | 100k | 1024 | 1.25 | skipped |
| mid | Pixel 4a, Moto G | 12 MB | 300k | 2048 | 1.5 | loaded |
| high | recent flagship, desktop | 40 MB | 1.5M | 4096 | 2 | loaded |

An entry that would pass what is left of the budget is skipped (`stats.skipped`). The
`bytes` and `triangles` hints let it be skipped before it downloads. `lod.low` and
`lod.mid` are smaller files for those tiers. Embedded PNG and JPEG textures above the
tier's edge are downscaled before upload.
- **One model at a time.** `sv.addModel(url, { position, rotation, scale, animation })`
  adds a model next to the others and resolves with a handle: `setTransform()`,
  `playAnimation()`, `stopAnimation()`, `boundingBox`, `ready`, `remove()`.
  `loadModel()` still replaces the model.
- **Preparing the files.** Filament.js decodes Draco, meshopt, `KHR_mesh_quantization`,
  `KHR_texture_transform`, WebP and KTX2 Basis textures. A good default is
  `npx gltfpack -i in.glb -o out.glb -cc -tc` (meshopt geometry, KTX2 textures that stay
  compressed on the GPU). Make the low-tier file with
  `npx @gltf-transform/cli resize in.glb low.glb --width 1024 --height 1024`, then
  `simplify`, and write each file's size and triangle count into the descriptor.

The test page `website-static/scene-lab.html` shows the tier, the timings, the bytes, the
triangles and the frame time of such a scene; `?tier=low|mid|high` forces a tier.

## Limitations

- **No AR** — requires native sensors (camera, compass, accelerometer)
- **WebGL2 required** — ~95% of browsers support it
- **glTF 2.0 / GLB for models** — same format as Android (plus `.ply` / `.spz`
  Gaussian-Splatting captures, see above)
- **Cross-origin** — assets need CORS headers if hosted on a different domain
