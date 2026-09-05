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

`damaged_helmet.webp` is the reference for every helmet card. All ten of them —
`model-viewer`, `two-d-in-three-d`, `lighting`, `lighting-lab`, `fog` (iOS-only since #3464,
see below), `camera-gestures`, `materials`, `debug-overlay`, `video-recording`,
`secondary-camera` — load the same GLB, so they must show the same helmet. The original stylised `hero.webp` render — a helmet the GLB
does not look like — fed eight of these cards until #3454 and the store listings until #3461;
it is deleted, so nothing can be generated from it again.

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

`model-thumbs.json` + `--kind thumb` generates the square 320×320 tiles the model picker
and the Model Viewer's model sheet show — `model_thumb_<asset-stem>.webp`, the exact key
`ModelThumbnails.resourceFor()` looks up, so **the id in the JSON is the bundled GLB's
file stem**. Dark-only: both sheets sit on a scrim in both app themes.

```bash
GEMINI_ENV_FILE=~/path/to/env-with-GEMINI_API_KEY python3 tools/demo-previews/gen.py \
  tools/demo-previews/model-thumbs.json /tmp/thumbs --kind thumb --only khronos_sheen_chair
cp /tmp/thumbs/webp/*.webp samples/android-demo/src/main/res/drawable-nodpi/
```

Entries use `refUrl` rather than a committed `ref`: the reference is each asset's own
upstream render (the Khronos `screenshot/screenshot.jpg`), itself a CC-BY work, and
caching it under `refs/` would add third-party binaries the repo would then owe an
attribution line for. It is fetched into the out dir instead, which is not tracked.

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
