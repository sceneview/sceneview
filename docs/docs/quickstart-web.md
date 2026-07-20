# Web Quickstart

SceneView Web uses **Filament.js** — the same Filament rendering engine as SceneView Android, compiled to WebAssembly for browsers (WebGL2).

## Install

```bash
npm install @sceneview/sceneview-web
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
            model("models/DamagedHelmet.glb")
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

  sv.addModelNode("models/DamagedHelmet.glb").then(function (model) {
    model.addChild(cube);                  // cube now follows the model
  });
});
```

Factories: `addNode()` (empty pivot), `addModelNode(url)` (→ `Promise<NodeHandle>`),
`addCubeNode(size)`, `addSphereNode(radius)`,
`addLightNode("directional" | "point" | "spot")`, and `removeNode(handle)`.
`NodeHandle` methods: `setPosition` / `setRotation` (Euler degrees) / `setScale`
/ `setScaleUniform` / `setVisible` / `visible` / `addChild` / `removeChild` /
`getWorldPosition` / `destroy`. This is a first, minimal slice — no gestures or
collision yet, and `CameraNode` / `addGeometryNode` stay Kotlin-only. Full
Android/iOS node parity continues under #2024.

## Environment Lighting

```kotlin
environment(
    iblUrl = "environments/pillars_2k_ibl.ktx",
    skyboxUrl = "environments/pillars_2k_skybox.ktx"
)
```

## Limitations

- **No AR** — requires native sensors (camera, compass, accelerometer)
- **WebGL2 required** — ~95% of browsers support it
- **glTF 2.0 / GLB only** — same format as Android
- **Cross-origin** — assets need CORS headers if hosted on a different domain
