# Demo preview images

`gen.py` generates the light/dark 5:4 preview WebPs in
`samples/android-demo/src/main/res/drawable-nodpi/` with Gemini image-to-image,
following the art direction in `DESIGN.md` ("Preview Image Art Direction").

```bash
GEMINI_ENV_FILE=~/path/to/env-with-GEMINI_API_KEY python3 tools/demo-previews/gen.py \
  tools/demo-previews/prompts.json /tmp/previews --refs tools/demo-previews/refs --only lighting,fog
cp /tmp/previews/webp/*.webp samples/android-demo/src/main/res/drawable-nodpi/
```

`prompts.json` holds one prompt per demo id (`ref` = a real capture or the hero render
used as the image-to-image reference). Every generated image must be reviewed next to
its source capture before merge; never invent models or reshape assets.

### References

`refs/` holds the image-to-image sources named by each item's `ref`. A reference is never a
mood board: it is what the demo really renders, so the card cannot promise a model the app
does not load (#3438).

| Ref | What it is | How it was made |
|---|---|---|
| `damaged_helmet.webp` | The helmet the app actually loads | Cropped from `samples/android-demo/src/androidTest/assets/render-goldens/modelviewer_default.png`, i.e. a real capture of the demo on the pinned CI profile. |
| `torus_knot.webp` | The Custom Geometry ribbon knot | Offline render of `TorusKnot.vertices()` at its default parameters (168 segments, 2.5 turns, 0.3 ripple) under the demo's own camera and tilt. |
| `lines_paths_route.webp` | The Lines & Paths route | Offline render of `LinesPathsScene` — the eight control points, the Smooth route, the marker, the trail and the dashed ground track — under the demo's own camera. |

The last two are rendered from the demos' own generator code rather than captured, so the
card shows the exact curve the app computes rather than an invented knot or loop.

`damaged_helmet.webp` is the reference for the helmet cards that are still generated:
`model-viewer`, `two-d-in-three-d` and `fog` (iOS-only since #3464, see below). The original stylised `hero.webp` render — a helmet the GLB does not look like — fed
eight of these cards until #3454 and the store listings until #3461; it is deleted, so
nothing can be generated from it again.

### Cards cropped from real captures (#3836)

Ten near-identical helmet cards made the Showcase read as one demo repeated. Each demo whose
own result is not the helmet now shows that result instead, cropped 5:4 from a real capture —
its render golden in `samples/android-demo/src/androidTest/assets/render-goldens/` (a real capture on
the pinned CI profile) and resized to 800×640. The demo stage does not follow the app theme,
so light and dark are the same pixels. The Android cards are no longer generated for these
ids, so do not pass them to `gen.py --only` for Android (the iOS imagesets still are):

| Card | Golden | Crop (centre x, centre y, width, in golden pixels) |
|---|---|---|
| `materials` | `materials_default.png` | 540, 1102, 1080 — the nine-sphere grid |
| `debug-overlay` | `debugoverlay_default.png` | 540, 800, 1400, black-padded — the stats HUD over its sphere |
| `camera-gestures` | `cameragestures_default.png` | 575, 1065, 1000 — the whole stage |
| `lighting` | `lighting_default.png` | 540, 1080, 1080 — helmet and probes under the photo environment |
| `lighting-lab` | `lightinglab_default.png` | 540, 990, 1080 — the lit floor with its sun disc |
| `secondary-camera` | `secondarycamera_default.png` | 540, 870, 1600, black-padded — the picture-in-picture inset |

Black padding is used only where the stage background is pure black, so the fill cannot be
told apart from the frame.

`video-recording` has no render golden, so its card is an emulator capture (Pixel_7a,
1080×2400) taken 3 s into a recording: the helmet in the recorded frame over the
"Recording the moving scene to MP4…" banner. Window: y 817–1846, the full 1080 px width plus
103 px on each side filled by stretching the screen's own edge column — pure black beside the
render, a horizontally uniform grey beside the banner — to reach 5:4.

## iOS imagesets

The iOS demo reads the same art from `samples/ios-demo/SceneViewDemo/Assets.xcassets/
preview_<scene_id>.imageset/` — one universal JPEG per appearance (`preview_<id>.jpg` for
light, `preview_<id>_dark.jpg` for dark; no 1x/2x/3x scales), 800×640 like the Android cards,
plus the dark-only `preview_hero_model_viewer.jpg` at 1600×1000. `--format jpg` writes the
same crop of the same raw as a JPEG (q85) instead of a WebP, so an iOS imageset is one
`gen.py` run plus a rename. iOS scene ids do not all match Android demo ids — the table is
the mapping, and it is the only place it is written down:

| Imageset | Files | `gen.py` item | Notes |
|---|---|---|---|
| `preview_hero_model_viewer` | `preview_hero_model_viewer.jpg` (1600×1000, dark only) | `heroes.json` → `model-viewer`, `--kind hero` | Also the `BrowseOnlineModelsCard` artwork. |
| `preview_model_viewer` | `preview_model_viewer.jpg`, `_dark.jpg` | `prompts.json` → `model-viewer` | Same scene as the Android card. |
| `preview_dynamic_sky` | `preview_dynamic_sky.jpg`, `_dark.jpg` | `prompts.json` → `lighting-lab` | iOS `dynamic-sky` is Android's Lighting Lab Sky tab (`DemoDeepLinkRegistry`), so it takes the Lighting Lab card. |
| `preview_materials` | `preview_materials.jpg`, `_dark.jpg` | `prompts.json` → `materials` | Same scene as the Android card. |
| `preview_fog` | `preview_fog.jpg`, `_dark.jpg` | `prompts.json` → `fog` | iOS still lists `fog` as its own card (`FogScene.swift`); Android folded it into Lighting Lab in #3464, so `fog` is an iOS-only item and nothing under `drawable-nodpi/` consumes it. |

```bash
GEMINI_ENV_FILE=~/path/to/env-with-GEMINI_API_KEY python3 tools/demo-previews/gen.py \
  tools/demo-previews/prompts.json /tmp/ios --refs tools/demo-previews/refs --format jpg \
  --only model-viewer,materials,lighting-lab,fog
GEMINI_ENV_FILE=~/path/to/env-with-GEMINI_API_KEY python3 tools/demo-previews/gen.py \
  tools/demo-previews/heroes.json /tmp/ios --kind hero --refs tools/demo-previews/refs --format jpg
X=samples/ios-demo/SceneViewDemo/Assets.xcassets
cp /tmp/ios/jpg/preview_model_viewer_light.jpg  $X/preview_model_viewer.imageset/preview_model_viewer.jpg
cp /tmp/ios/jpg/preview_model_viewer_dark.jpg   $X/preview_model_viewer.imageset/preview_model_viewer_dark.jpg
cp /tmp/ios/jpg/preview_lighting_lab_light.jpg  $X/preview_dynamic_sky.imageset/preview_dynamic_sky.jpg
cp /tmp/ios/jpg/preview_lighting_lab_dark.jpg   $X/preview_dynamic_sky.imageset/preview_dynamic_sky_dark.jpg
cp /tmp/ios/jpg/preview_materials_light.jpg     $X/preview_materials.imageset/preview_materials.jpg
cp /tmp/ios/jpg/preview_materials_dark.jpg      $X/preview_materials.imageset/preview_materials_dark.jpg
cp /tmp/ios/jpg/preview_fog_light.jpg           $X/preview_fog.imageset/preview_fog.jpg
cp /tmp/ios/jpg/preview_fog_dark.jpg            $X/preview_fog.imageset/preview_fog_dark.jpg
cp /tmp/ios/jpg/preview_hero_model_viewer.jpg   $X/preview_hero_model_viewer.imageset/
```

The other iOS imagesets (`preview_lighting`, `preview_camera_controls`, the AR cards, …) were
not produced by this pipeline and are not in the table; regenerate one only once its prompt is
recorded here, so the recorded prompt is always the one that produced the committed image
(#3474).

### iOS cards cropped from simulator captures (#3786)

Fourteen iOS scenes have no Android twin in `drawable-nodpi/` and showed the SF Symbol tile.
Their cards are real captures of the demo, not generated: the keyless Debug build on the
iPhone 17 Pro Max simulator (iOS 26.3, 1320×2868), opened through `sceneview://demo/<id>`
with QA mode on so the orbit is frozen, cropped 5:4 around the subject and resized to
800×640 (JPEG q85). The demo stage does not follow the app theme, so each imageset holds one
universal JPEG and no dark variant. Crop = centre x, centre y, width, in capture pixels.

| Imageset | Capture | Crop |
|---|---|---|
| `preview_scene_gallery` | `scene-gallery`, "PBR Low-Poly Fox" chip (the bundled fox) | 660, 1500, 1100 |
| `preview_environment` | `environment`, default HDR | 840, 1386, 850 |
| `preview_movable_light` | `movable-light` | 662, 1505, 1000 |
| `preview_billboard` | `billboard` | 652, 1449, 1240 |
| `preview_image` | `image` | 655, 1399, 1280 |
| `preview_texture_streaming` | `texture-streaming`, Gold preset | 673, 1478, 1000 |
| `preview_gesture_editing` | `gesture-editing` | 660, 1110, 1200 |
| `preview_occlusion_material` | `occlusion-material` | 660, 1307, 960 |
| `preview_physics` | `physics`, bundled cubes at rest | 680, 1480, 600 |
| `preview_reflection_probes` | `reflection-probes` | 652, 1412, 1000 |
| `preview_shape` | `shape`, Star | 639, 1400, 1100 |
| `preview_multi_model` | `multi-model`, keyless stand-ins (what the App Store build shows) | 650, 1458, 1300 |
| `preview_ar_lighting` | Not an AR capture: the Simulator has no ARKit. The same `phoenix_bird.usdz` the demo lights, opened in the app's own file viewer | 759, 1353, 880 |
| `preview_video` | Not a video capture: RealityKit has no video texture allocator on the Simulator, so the quad stays empty. Captured with the clip's own frame (2 s into `sample.mp4`) bound as an unlit `ImageNode` on the demo's 2.4 × 1.35 m quad at the video node's position, a local patch that was not committed | 660, 1470, 1000 |

## Home hero banner

`heroes.json` + `--kind hero` generates the wide `preview_hero_<demo_id>.webp` (1600×1000,
16:10) that `HomeHero` shows at the top of the Showcase tab. Dark-only, and the prompt is
used **verbatim** — no field or style suffix — because the hero has a Compose caption
overlaid on its left third and therefore needs its own framing and lighting directions.
16:10 is not an aspect the API offers, so the image is generated at 16:9 and centre-cropped.

```bash
GEMINI_ENV_FILE=~/path/to/env-with-GEMINI_API_KEY python3 tools/demo-previews/gen.py \
  tools/demo-previews/heroes.json /tmp/hero --kind hero --refs tools/demo-previews/refs
cp /tmp/hero/webp/*.webp samples/android-demo/src/main/res/drawable-nodpi/
```

The hero and the `model-viewer` card must always show the same model: they sit on the same
screen, a thumb's width apart, and #3438 was filed because they did not.

## Model thumbnails

`model_thumb_<asset-stem>.webp` — the cards of the Model Viewer's Models sheet and of the AR
placement picker, looked up by `ModelThumbnails.resourceFor()` with the bundled GLB's file
stem — are **renders of that exact GLB, not generated images**. The image-generated set drew
models that were not the bundled ones (a green toy soldier for the three.js Soldier, #3828),
so this kind is no longer produced by `gen.py`.

How a thumbnail is made (#3828):

1. A throwaway screen in the demo app renders the GLB through the app's own Filament stack:
   `chinese_garden_2k.hdr` as the only light (IBL at 30,000, no skybox), bloom off, the
   default Filmic tone mapping, a 50 mm lens looking at the bounding-box centre from 30°
   of yaw and 18° of pitch (Damaged Helmet 15°/15° so the visor reads; Soldier 30°/12°),
   far enough for the bounding sphere to fit. The model is turned by its `frontYaw` first,
   so the thumbnail shows the side the viewer opens on. An animated model (Soldier, Fox) is
   posed half a second into its first clip rather than on a bind pose.
2. The same frame is captured twice on the QA emulator, over a solid black and a solid white
   skybox. The difference of the two gives each pixel's coverage
   (`alpha = 1 - (white - black) / (white bg - black bg)`), so the subject comes out with
   clean anti-aliased edges and no background at all.
3. The matte is trimmed to the subject, fitted at 80 % of a 600×480 (5:4, the card's
   `mediaAspect`) transparent canvas, centred, and saved as lossy WebP with alpha (q90).

The card paints its own `surface-container-high` fill behind the image, so one file serves
both themes. The throwaway screen is not committed: re-render by rebuilding it from the steps
above, and review every thumbnail over both card fills next to the viewer's first frame of
the same model before it ships.

### Scene cards

`model_picker_park.webp` and `model_picker_gallery.webp` (the sheet's "Scenes" row) are
emulator captures (Pixel_7a, 1080×2400) of a **keyless** debug build, so they show the bundled
fallback models only — never a streamed Sketchfab model, whose CC-BY licence would then have to
be credited for the image. Park: `--es demo multi-model --ef camera_distance 6.5`, window
x 0–1080, y 780–1644, resized to 600×480. Gallery: a 2×2 collage of the four chips' fallbacks
(Toy Car, Fox, Lantern, Damaged Helmet), each subject cropped 5:4 from its own capture.

## Store AR visuals

`store.json` + `--kind store` generates the AR marketing visuals that lead both store
listings (#2844): the Play feature graphic and slot 1 of every screenshot class, i.e. the
helmet anchored in a real photographed room. Prompts are used verbatim, dark-only, and
each item names its own `aspect`, `size` (`2K`, so the iPad slot is not upscaled from a
1K raw) and the store `outputs` it feeds. One raw feeds every slot that shares its aspect —
`ar-phone` is cut to Play's phone slot and the iPhone 6.9" class, `ar-tablet` to both Play
tablet slots and the iPad 13" class — so a listing never shows two different rooms.
`crop_save` centre-crops to each slot's exact pixel spec, which is what App Store Connect
and the Play Console reject when off by a pixel.

```bash
GEMINI_ENV_FILE=~/path/to/env-with-GEMINI_API_KEY python3 tools/demo-previews/gen.py \
  tools/demo-previews/store.json /tmp/store-art --kind store --refs tools/demo-previews/refs
cp -R /tmp/store-art/store/samples .   # the out dir mirrors the repo paths
```

Look at every output before committing (the graphics READMEs next to the files record the
source, prompt and date of what ships). Committing is not uploading: the Play listing sync
runs on minor releases (`play-store.yml`), the App Store screenshots only through
`app-store-screenshots.yml` with `confirm=true`.
