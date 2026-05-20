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

## JavaScript API (sceneview.js v4.11.1)

For browser usage without Kotlin, use `sceneview.js` directly:

```html
<script src="https://cdn.jsdelivr.net/npm/sceneview-web@4.11.1/sceneview.js"></script>
<script>
  SceneView.modelViewer("canvas", "model.glb", {
    backgroundColor: [0.05, 0.05, 0.08, 1],
    lightIntensity: 150000,
    fov: 35
  });
</script>
```

### Full API

| Method | Description |
|---|---|
| `SceneView.modelViewer(canvas, url, options?)` | One-line 3D model viewer |
| `SceneView.create(canvas, options?)` | Create instance for full API |
| `instance.loadModel(url)` | Load glTF/GLB model |
| `instance.setAutoRotate(enabled)` | Toggle auto-rotation |
| `instance.setCameraDistance(d)` | Set orbit camera distance |
| `instance.setBackgroundColor(r, g, b, a?)` | Set clear color |
| `instance.setQuality('low'\|'medium'\|'high')` | AO + anti-aliasing quality |
| `instance.setBloom(true\|false\|options)` | Bloom post-processing |
| `instance.addLight(options)` | Add directional/point/spot light |
| `instance.createText(options)` | Render text as 3D quad |
| `instance.createImage(options)` | Render image as 3D quad |
| `instance.createVideo(options)` | Stream video to 3D quad |
| `instance.removeNode(entity)` | Remove node from scene |
| `instance.dispose()` | Clean up resources |

## Features

- Same Filament PBR renderer as Android (compiled to WASM)
- glTF 2.0 / GLB model loading
- IBL environment lighting (KTX)
- Camera configuration (FOV, position, exposure)
- Directional, point, and spot lights
- Animation playback
- Quality presets (low/medium/high)
- Bloom post-processing
- Text, image, and video nodes
- Billboard mode (always face camera)
- Kotlin/JS DSL API + vanilla JavaScript API

## Requirements

- WebGL2 browser (~95% coverage)
- No AR support (requires native sensors)

## Part of SceneView

SceneView is a declarative 3D/AR SDK for Android, iOS, macOS, visionOS, Web, and Desktop.

- [GitHub](https://github.com/sceneview/sceneview)
- [Documentation](https://sceneview.github.io)
