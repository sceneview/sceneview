# SceneView Web Demo

Browser-based 3D viewer using SceneView.js (Filament.js WASM engine).

## Features

The demo has eight tabs in the top tab bar:

- **Models** — a source-agnostic catalog picker (#2722, the web leg of the
  Android #2685 / iOS #2721 `ModelSource` trio): **SceneView** (12 curated,
  self-hosted models across 5 categories — bundled in the distribution, not a
  third-party CDN, issue #1573), **Icosa Gallery** and **Poly Haven** (keyless
  CC catalogs — browse, search, and render in-app), plus **Sketchfab** when an
  API key is configured (hidden otherwise). Switching sources resets browse +
  search state; the selection persists in `localStorage`; one degraded source
  never blanks the tab.
- **Geometry** — create cubes, spheres, cylinders, and planes with color
  pickers, size sliders, and a per-shape `KHR_materials_unlit` toggle.
- **Lighting** — add and remove directional, point, and spot lights via
  `addLight()` / `removeNode()`, with per-type color and intensity controls.
  Web counterpart of Android's `LightNode` demos.
- **Animation** — load an animated glTF model and drive its keyframe/skinning
  playback via `playAnimation()` / `stopAnimation()`, with a model picker and
  loop toggle.
- **Text** — render billboarded 3D text nodes via `createText()`, with
  text/color/size controls and removal.
- **Environment** — image-based lighting via `setEnvironmentSH()`
  spherical-harmonic presets (Warm / Cool / Dramatic), background color, and
  bloom strength. The bloom and background controls stay in sync with Settings.
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

This demo is a **plain static site** — no Gradle, no build step. Open
`site/index.html` directly in a browser, or serve the folder:

```bash
npx http-server site -p 8080
```

## Architecture

- `site/index.html` — the app shell (HTML + CSS + inline JS). It loads a
  self-hosted `sceneview.js`. The runtime wiring lives in TWO source files:
  the inline `<script>` (issue #1946 removed the dead Kotlin/JS `Main.kt`
  that never executed in the browser) and `site/js/model-sources.js` — the
  source-agnostic catalog layer (#2722). ⚠️ `model-sources.js` sits under the
  broad `site/` gitignore rule, so `git status` never shows edits to it:
  always `git add -f` after touching it (see the note in `.gitignore`).
- Uses `SceneView.js` (`SceneView.modelViewer()`, `createBox()`,
  `setQuality()`, `setBloom()`, `setBackgroundColor()`, `addLight()`,
  `removeNode()`, `playAnimation()`, `stopAnimation()`, `createText()`,
  `setEnvironmentSH()`, etc.).
- Engine: `filament.js`, `filament.wasm`, and `sceneview.js` are self-hosted
  under `site/js/` and referenced by relative path, so the demo never depends
  on a third-party CDN for its engine (issue #1586).
- Catalog APIs: Sketchfab `GET /v3/search?type=models&downloadable=true&q={query}`
  (key-gated) · Icosa Gallery `GET api.icosa.gallery/v1/assets` (keyless;
  legacy Poly-era `web.archive.org` mirrors are deprioritized — they 404
  without CORS) · Poly Haven `api.polyhaven.com` (keyless, single-flight
  TTL index). All JSON reads are size-capped and streamed.
- Curated models: self-hosted GLBs under `site/models/`, loaded from the
  relative `models/` path. The whole `site/` folder is copied verbatim to the
  deployed `/web-demo/` route by `docs.yml` and served by the Playwright dev
  server, so the demo never depends on a third-party CDN for its assets.
  jsDelivr's gh-proxy returns HTTP 403 for large GLB blobs under `assets/`,
  which is why the catalog is self-hosted (issue #1573).
- IBL environment: `site/environments/neutral_ibl.ktx`, self-hosted so
  `SceneView.js` finds it via the relative `environments/` path with no 404 —
  works on both domain-root and subpath deploys (issues #1586, #1631).

## Tests

Playwright tests run headless against the shipped `site/index.html` (served
by `http-server` straight from the `site/` folder — no build step):

- `tests/render.spec.ts` — load + branding + tab-regression smoke layer.
- `tests/catalog.spec.ts` — full per-tab / per-demo QA: exercises every Models
  / Geometry / Physics / Settings demo, drives camera orbit + zoom, samples the
  WebGL canvas to assert a non-blank render, and asserts no console errors or
  unhandled rejections (slice 3 of the device-QA harness, issue #1564).
- `tests/helpers.ts` — shared canvas-sampling, console-capture and interaction
  helpers.

Run with:

```bash
npx playwright test
```

The run emits `test-results/web-qa-summary.json` — a flat machine-readable
result consumed by the autonomous device-QA orchestrator runner (issue #1566).

## Requirements

- WebGL2-compatible browser (~95% coverage)
- WebXR for AR/VR where available (Chrome Android, Quest Browser, etc.)
