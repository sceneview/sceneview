# SceneView Web Demo

Browser-based 3D viewer using SceneView.js (Filament.js WASM engine).

## Features

The demo opens on a showpiece model in a photo studio and has ten tabs: a
navigation rail on desktop, a chip row above a bottom sheet on phones.

- **Models** — a source-agnostic catalog picker (#2722, the web leg of the
  Android #2685 / iOS #2721 `ModelSource` trio): **SceneView** (15 curated,
  self-hosted models across 4 categories, each with a thumbnail and a
  credit — served from the demo itself, not a third-party CDN, issue #1573), **Icosa Gallery** and **Poly Haven** (keyless
  CC catalogs — browse, search, and render in-app), plus **Sketchfab** when an
  API key is configured (hidden otherwise). Switching sources resets browse +
  search state; the selection persists in `localStorage`; one degraded source
  never blanks the tab.
- **Geometry** — create cubes, spheres, cylinders, and planes with color
  pickers, size sliders, and a per-shape `KHR_materials_unlit` toggle.
- **Lights** — add and remove directional, point, and spot lights via
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
- **Audio** — spatial audio sources attached to the scene.
- **Settings** — photo studio (seamless backdrop and contact shadow that follow
  light and dark mode), rendering quality (low/medium/high), bloom toggle,
  auto-rotate toggle, and background color (which turns the studio off).
- **Credits** — the author and licence of every bundled model.

Also:

- **WebXR AR/VR** — enter immersive AR or VR sessions (when the browser
  supports WebXR).
- **Deep linking** — a `#double-pendulum` (or `#physics`) URL fragment opens
  the Physics tab directly, mirroring the `sceneview://demo/double-pendulum`
  deep link the Android and iOS demos honour.
- **Material 3, light and dark** — system font, M3 colour roles from one
  source colour, following `prefers-color-scheme`; mobile-first at 412 px.
- **Analytics** — loads the site's `/assets/analytics.js` and sends
  `model_select`, `tab_click`, `ar_enter` and `cta_click` ("Use it in your
  app") events; a no-op where `gtag` is absent (local runs, tests).
- **Update snackbar** — surfaces a "Reload to update" prompt when
  `sceneview.github.io/version.json` reports a newer build.

## Run

This demo is a **plain static site** — no Gradle or compilation. From
`samples/web-demo`, stage the shared viewer before serving the folder:

```bash
bash ../../tools/fetch-assets.sh --scope web-demo   # the curated GLBs
cp ../../website-static/js/sceneview.js site/js/sceneview.js
npx http-server site -p 8080
```

Open <http://localhost:8080/>. Use `?studio=false` to start with the plain
background and lighting, without the studio backdrop, contact shadow or reflection
(for embeds and feature tests on software WebGL). The Settings **Photo studio**
switch reflects this initial state and can turn the studio back on. Omit the
parameter to keep the default studio. It also composes with other query parameters
and fragments, for example `?studio=false#double-pendulum`.

Repeat the copy after editing the viewer.
`site/js/sceneview.js` is an ignored local artifact; its only source is
`website-static/js/sceneview.js`. The `docs.yml` copy step stages that same
source under `/web-demo/js/sceneview.js` when deploying.

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
  under `site/js/` (the viewer is staged from `website-static/js/sceneview.js`)
  and referenced by relative path, so the demo never depends
  on a third-party CDN for its engine (issue #1586).
- Catalog APIs: Sketchfab `GET /v3/search?type=models&downloadable=true&q={query}`
  (key-gated) · Icosa Gallery `GET api.icosa.gallery/v1/assets` (keyless;
  legacy Poly-era `web.archive.org` mirrors are deprioritized — they 404
  without CORS) · Poly Haven `api.polyhaven.com` (keyless, single-flight
  TTL index). All JSON reads are size-capped and streamed.
- Curated models: GLBs under `site/models/`, loaded from the relative
  `models/` path. They are not committed: `assets/manifest.json` lists them
  under the `web-demo` scope and `tools/fetch-assets.sh` fetches them from the
  `assets-v1` release (CI and the docs deploy run it). Thumbnails are small
  WebP renders under `site/thumbs/`, made with the demo's own photo studio. The `site/` folder plus the shared viewer are copied to the
  deployed `/web-demo/` route by `docs.yml` and served by the Playwright dev
  server, so the demo never depends on a third-party CDN for its assets.
  jsDelivr's gh-proxy returns HTTP 403 for large GLB blobs under `assets/`,
  which is why the catalog is self-hosted (issue #1573).
- IBL environment: `site/environments/neutral_ibl.ktx`, self-hosted so
  the page explicitly passes `iblUrl: 'environments/neutral_ibl.ktx'` —
  works on both domain-root and subpath deploys (issues #1586, #1631).

## Shared viewer (#4316)

The hand-written viewer has one source: `website-static/js/sceneview.js`, the
file sceneview.dev itself loads. The demo no longer carries its own copy (it had
drifted to a 3.6 build); `site/js/sceneview.js` is staged from that source by
`docs.yml` for the deployed `/web-demo/` route, by the Playwright `webServer`
command for tests, and by the `cp` above for local runs.

What the page relies on:

- `SceneView.create('scene-canvas', { iblUrl: 'environments/neutral_ibl.ktx' })`
  — the viewer's default IBL URL is absolute (`/environments/…`, right for the
  website root), so the demo passes its own relative one.
- `addLight()` and `createText()` hand back Filament.js `Entity` objects, not
  numbers. The page keeps them and passes them to `removeNode()`.
- The far plane follows the zoom-out limit of the framed model, so a model
  authored in millimetres (Retro Piano, framed thousands of units away) is not
  culled.

Camera motion is the website viewer's: auto-rotation, drag inertia and wheel
zoom advance by a fixed amount per frame. The retired demo copy integrated them
over elapsed time (#3711, #3742); porting that to the shared viewer changes how
every viewer on sceneview.dev moves and is left to a separate change.

## Tests

Playwright tests run headless against the shipped `site/index.html` (served
by `http-server` from the `site/` folder after the configured server command
stages the shared viewer):

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
