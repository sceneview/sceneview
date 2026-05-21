# SceneView Web Demo

Browser-based 3D viewer using SceneView.js (Filament.js WASM engine).

## Features

The demo has four tabs in the top tab bar:

- **Models** — browse 40 curated CDN models across 5 categories (Showcase,
  Vehicles, Animated, Characters, Objects), or switch the source toggle to
  **Sketchfab Search** to search downloadable 3D models from Sketchfab.
- **Geometry** — create cubes, spheres, cylinders, and planes with color
  pickers, size sliders, and a per-shape `KHR_materials_unlit` toggle.
- **Physics** — a chaotic **Double Pendulum** simulation whose integrator math
  mirrors the shared `DoublePendulum` in `sceneview-core` (KMP). Sliders tune
  the upper/lower link lengths and gravity; **Reset & drop** re-seeds the run.
- **Settings** — rendering quality (low/medium/high), bloom toggle, auto-rotate
  toggle, and background color.

Also:

- **WebXR AR/VR** — enter immersive AR or VR sessions (when the browser
  supports WebXR).
- **Deep linking** — a `#double-pendulum` (or `#physics`) URL fragment opens
  the Physics tab directly, mirroring the `sceneview://demo/double-pendulum`
  deep link the Android and iOS demos honour.
- **Responsive dark theme** — works on desktop and mobile.
- **Update snackbar** — surfaces a "Reload to update" prompt when
  `sceneview.github.io/version.json` reports a newer build.

## Run

Open `index.html` directly in a browser, or serve the directory:

```bash
npx http-server samples/web-demo -p 8080
```

## Architecture

The web demo is a **single static `index.html`** — there is no build step and
no Kotlin/JS module. This is the one runtime: a future contributor's change to
`index.html` is what actually ships.

- `index.html` — self-contained single-file app (HTML + CSS + inline JS). It is
  the shipped demo; it loads `SceneView.js` from the CDN.
- Uses `SceneView.js` from CDN (`SceneView.modelViewer()`, `createBox()`,
  `setQuality()`, `setBloom()`, `setBackgroundColor()`, etc.).
- Filament.js WASM engine loaded from CDN.
- Sketchfab API: `GET /v3/search?type=models&downloadable=true&q={query}`.
- CDN models: `https://cdn.jsdelivr.net/gh/sceneview/sceneview@main/assets/models/glb/`.

The `sceneview-web` Kotlin/JS library is published independently to npm
(`sceneview-web`) and consumed via its own CDN bundle — it is not built or
exercised by this demo.

## Tests

Playwright tests live in `tests/render.spec.ts` — page load, canvas content,
tab switching, branding, and XR button presence. Run with:

```bash
npx playwright test
```

## Requirements

- WebGL2-compatible browser (~95% coverage)
- WebXR for AR/VR where available (Chrome Android, Quest Browser, etc.)
