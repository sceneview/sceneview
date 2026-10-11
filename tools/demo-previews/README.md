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
| `raccoon_stump_scan.webp` | The raccoon-stump Gaussian splat `splat-preview` loads | The `splat-preview` card below at q90: a crop of the real CI render. It fed the generated `ar-splat-room` card until that demo became the dollhouse of #4075. |
| `toy_car.webp` | The Toy Car AR Placement opens on (`BUNDLED_PLACEMENT_MODELS.first()`, 0.3 m) | A copy of `model_thumb_khronos_toy_car.webp`, a Filament render of the exact GLB (see "Model thumbnails"). It feeds the `ar-placement` card, which until 2026-09-30 was a generated fox, a model the demo has not placed since #3324. |
| `fox_animation.webp` | The fox Animation opens on | The `animationphysics_default.png` render golden, centre 500, 1414, width 760, 5:4 — the crop the old card used. |
| `double_pendulum_rig.webp` | The Double Pendulum rig and its trail | Emulator capture (Pixel_7a, 1080×2400, light, QA mode off so it swings), window y 950–1950. |
| `contact_shadow_scene.webp` | The two Contact Shadow cubes and the TV | Emulator capture (Pixel_7a, light, `--ez qa_mode true`), window y 420–1640. |
| `secondary_camera_pip.webp` | The Secondary Camera stage with its picture-in-picture | Emulator capture (Pixel_7a, dark, `--ez qa_mode true`), window y 345–1700. |
| `billboard_labels.webp`, `occlusion_sphere.webp`, `video_screen.webp` | The iOS Billboard, Occlusion Material and Video Texture scenes | The previous iOS cards at q90 — the simulator captures of #3786 listed below. |

The last two are rendered from the demos' own generator code rather than captured, so the
card shows the exact curve the app computes rather than an invented knot or loop.

`ar-placement` is an `"ar": true` item: the Toy Car on its draped cloth stand, on a real
living-room floor at its true size, with no placement ring or plane grid, since the demo
places the model automatically with neither (`TapToPlaceExperience`). One generation per theme, the dark one
an evening room. `backdrops.py` still crops QA backdrop 3 from the old fox card, read from
git history (`b1dc1dc59`), so the backdrop does not change.

`damaged_helmet.webp` is the reference for the helmet cards that are still generated:
`model-viewer` and `fog` (iOS-only since #3464, see below). The original stylised `hero.webp` render — a helmet the GLB does not look like — fed
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
| `debug-overlay` | — | Superseded 2026-09-30: the card is now the iOS pair, see "Cards shared with Android" below. Was 540, 800, 1400, black-padded — the stats HUD over its sphere |
| `camera-gestures` | `cameragestures_default.png` | 575, 1065, 1000 — the whole stage |
| `lighting` | `lighting_default.png` | 540, 1080, 1080 — helmet and probes under the photo environment |
| `lighting-lab` | `lightinglab_default.png` | 540, 990, 1080 — the lit floor with its sun disc |
| `secondary-camera` | — | Superseded 2026-09-30: generated from a real capture, see "Cards generated from real captures" below. Was `secondarycamera_default.png`, 540, 870, 1600, black-padded — the picture-in-picture inset |
| `animation-physics` | `animationphysics_default.png` | Light card superseded 2026-09-30: generated from this golden crop (500, 1414, 760), see "Cards generated from real captures" below. The dark card is still its own capture: Pixel_7a in night mode, `--ez qa_mode true`, crop 500, 1508, 760 (the screen is 96 px taller than the golden); since #4223 its stage is the dark stage (`SceneViewTokens.Stage.background`), so the dark card is the fox on that dark stage |
| `splat-preview` | `splatpreview_default.png` | 540, 1010, 1500, black-padded, then an elliptical vignette to black (radii 600 × 900 px, fade from 0.62) so the splat's soft fringe does not end on a hard crop edge (#4073) |

Black padding is used only where the stage background is pure black, so the fill cannot be
told apart from the frame.

`video-recording` (superseded 2026-09-30, see "Cards generated from real captures" below) had no render golden, so its card was an emulator capture (Pixel_7a,
1080×2400) taken 3 s into a recording: the helmet in the recorded frame over the
"Recording the moving scene to MP4…" banner. Window: y 817–1846, the full 1080 px width plus
103 px on each side filled by stretching the screen's own edge column — pure black beside the
render, a horizontally uniform grey beside the banner — to reach 5:4.

`ar-rerun` has no render golden either (#3993): its card is an emulator capture (Pixel_7a,
1080×2400) of the bundled replay in the `replay` QA state (3D view, paused at 0:11), one per
theme. Crop: centre 540, 1250, width 1040 — the rebuilt room with its keyframe photos, point
cloud and the two placed models, the corner of the camera card top-right.

`ar-splat-room` stands the user's own Rerun recording on a table (#4075), so its card is that
room as a miniature: an emulator capture (Pixel_7a, 1080×2400) of the dollhouse's 3D view
(`dollhouse-3d` QA state) after a `record` QA take of the bundled session, one per theme — the
AR half cannot run on the emulator (#2754). Crop: the full-width 1080×864 band from y = 958
(5:4), resized to 800×640, WebP q82.

`rolling-balls` became its own demo in #4083 and has no render golden: its card is an
emulator capture (Pixel_7a, 1080×2400) of the opening shot a few seconds after launch — the
wooden board (maple field, walnut frame, since the redesign) and the rubber, glass and steel
balls come to rest. Crop: x 40–1040, y 580–1380 (5:4, the whole frame of the board), resized
to 800×640, WebP q85, one capture per theme: its stage is the themed stage sky, so the dark
card is the dark stage.

`cosmos` had no card and fell back to its icon, although it leads the Featured shelf. Its
card is an emulator capture (Pixel_7a, 1080×2400) of `--es demo cosmos` once the spiral
galaxy has rendered: the full-width 1080×864 band centred on the galaxy's bright pixels
(window y 779–1643), resized to 800×640, WebP q80. Space is black
in both themes, so light and dark are the same pixels. The iOS `preview_cosmos` imageset
carries the same crop as JPEG q90.

`geometry` showed a generated picture of four shapes in red, blue, green and silver, while
the demo draws seven in the two brand tints. Its card is an emulator capture (Pixel_7a,
1280×2856) of `sceneview://demo/geometry?cameraDistance=2.7` with `--ez qa_mode true` (spin
parked), once the studio light has landed — the default framing fills a portrait band
and leaves no room for a 5:4 crop, hence the distance. Crop: the full-width 1280×1024 band
from y = 710, resized to 800×640, WebP q85. One capture was taken per theme, but the stage
does not follow the theme and the crop holds no chrome: the two crops came out pixel for
pixel the same, so `preview_geometry_dark.webp` and `preview_geometry_light.webp` are the
same bytes (same MD5), like `cosmos` above. The iOS `preview_geometry`
imageset is not replaced: its scene is not this one yet.

`two-d-in-three-d` showed a generated helmet with three floating cards, a scene the demo no
longer has: it now opens on a procedural rocket whose tapped part gets a live Compose card.
Its card is an emulator capture (Pixel_7a, 1280×2856) of
`sceneview://demo/two-d-in-three-d?cameraDistance=2.5` with `--ez qa_mode true`, after a tap
on the body (640, 1450) and one on the Gold swatch of the card that opens (1097, 1340) — the
default framing fills a portrait band, hence the distance. Crop: the full-width 1280×1024
band from y = 794, resized to 800×640, WebP q85 (`cwebp -crop 0 794 1280 1024 -resize 800
640`). One capture per theme: the stage and the card both follow the app theme, so the dark
card is the dark stage with the dark card surface. Its prompt is gone from `prompts.json`.

### Cards generated from real captures (2026-09-30)

Eight cards were still weak after #4235: a flat side-on fox on a grey stage, a black frame
with a tiny inset, an empty contact-shadow room, a pendulum that read as sticks. They are now
`gen.py` image-to-image edits of the real capture in the References table — the same objects,
colours and camera as the demo, relit and recomposed on the light (#EEF0F3) and dark (#0E1218)
fields, so the home rows keep the Cosmos-card look in both themes. Model
`gemini-3.1-flash-image`, `crop_save` to 800×640 (WebP q80 on Android, JPEG q90 on iOS). Every
raw, kept or rejected, with its exact prompt, ref and reason is archived outside the repo
(Drive `QA/demo-previews-gemini-2026-09-30/`, `manifest.json`).

| Card | Platforms | Ref | Kept raws, and why |
|---|---|---|---|
| `animation-physics` | Android, iOS `preview_animation` (light only) | `fox_animation.webp` | The light raw, first run. The dark card stays the real night-mode capture of #4223 (table above); the dark raw is archived but not shipped. |
| `double-pendulum` | Android, iOS `preview_double_pendulum` | `double_pendulum_rig.webp` | Dark from the committed prompt. Light from the first run, whose prompt lacked the sentence "There are exactly three balls in the image…": with it the light run still drew a second bob, without it the first light run did not. |
| `contact-shadow-preview` | Android, iOS `preview_contact_shadow_preview` (JPEG q90 re-encode of the two WebPs) | `contact_shadow_scene.webp` | Fourth prompt, both themes. Earlier runs drew the contact shadow as a hole, a light room box inside the dark field or an inverted halo; the committed prompt edits the capture instead of describing a scene. |
| `secondary-camera` | Android | `secondary_camera_pip.webp` | Light and dark, first run. |
| `video-recording` | Android | `damaged_helmet.webp` | Light and dark, first run: the helmet mid-turn with a slight motion blur under the red recording dot. |
| `billboard` | iOS, universal | `billboard_labels.webp` | The dark raw only: on the light field the golden "Treasure" label measured about 2.2:1 (< 3:1). |
| `occlusion-material` | iOS, universal | `occlusion_sphere.webp` | The dark raw of the second run: both light runs lost the cut (a whole sphere, then a mushroom), and the demo's own stage is dark. |
| `video` | iOS, light and dark (`preview_video_dark.jpg` is new) | `video_screen.webp` | Light and dark, first run. The screen shows the violet-to-blue gradient `sample.mp4` really plays. |

Regenerating one of these from `prompts.json` gives a new sample, not these pixels: check the
new pair on the home rows in both themes before committing it.

### Dark AR cards re-cropped out of a baked frame (#4351)

Two dark cards carried a frame inside the picture, which the art direction forbids and which
showed as a light band on a dark row: `preview_ar_scene_mesh_dark.webp` was a phone mock-up
on a cream field, `preview_ar_raw_depth_point_cloud_dark.webp` a picture on a beige mat. Both
are now a 5:4 crop of the committed picture taken inside that frame, with no new generation —
the scene, light and colours are the ones already shipped, enlarged about 1.5×:

```
cwebp -crop 215 125 485 388 -resize 800 640 -q 85   # ar_scene_mesh, dark
cwebp -crop 118 94 565 452 -resize 800 640 -q 85    # ar_raw_depth_point_cloud, dark
```

The source of each crop is the file as it was before that change (`git show
b1dc1dc59:samples/android-demo/src/main/res/drawable-nodpi/<name>.webp`, decoded with
`dwebp`). The light halves had no frame and are untouched.

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

The iOS cards of demos Android also lists are copies of the Android cards (see "Cards shared
with Android" below). The other iOS imagesets were
not produced by this pipeline and are not in the table; regenerate one only once its prompt is
recorded here, so the recorded prompt is always the one that produced the committed image
(#3474).

### iOS cards cropped from simulator captures (#3786)

Thirteen iOS scenes have no Android twin in `drawable-nodpi/` and showed the SF Symbol tile.
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
| `preview_billboard` | `billboard` — superseded 2026-09-30, generated from this capture (see "Cards generated from real captures") | 652, 1449, 1240 |
| `preview_image` | `image` | 655, 1399, 1280 |
| `preview_texture_streaming` | `texture-streaming`, Gold preset | 673, 1478, 1000 |
| `preview_gesture_editing` | `gesture-editing` | 660, 1110, 1200 |
| `preview_occlusion_material` | `occlusion-material` — superseded 2026-09-30, generated from this capture | 660, 1307, 960 |
| `preview_reflection_probes` | `reflection-probes` | 652, 1412, 1000 |
| `preview_shape` | `shape`, Star | 639, 1400, 1100 |
| `preview_multi_model` | `multi-model`, keyless stand-ins (what the App Store build shows) | 650, 1458, 1300 |
| `preview_ar_lighting` | Not an AR capture: the Simulator has no ARKit. The same `phoenix_bird.usdz` the demo lights, opened in the app's own file viewer | 759, 1353, 880 |
| `preview_video` | Superseded 2026-09-30, generated from this capture, now a light and dark pair. Not a video capture: RealityKit has no video texture allocator on the Simulator, so the quad stays empty. Captured with the clip's own frame (2 s into `sample.mp4`) bound as an unlit `ImageNode` on the demo's 2.4 × 1.35 m quad at the video node's position, a local patch that was not committed | 660, 1470, 1000 |

### Cards shared with Android (home rows, 2026-09-30)

A demo both apps list shows **the same picture on both**: same source, same 5:4 crop, same
light and dark pair. The home rows now dissolve the picture into a tint taken from it
(`home-row-ambient`, `DESIGN.md`), so a different picture on each platform is a different
row colour, not only a different thumbnail. Twelve iOS imagesets therefore carry the Android
card, re-encoded from its two WebPs as JPEG q90 (`preview_<id>.jpg` light,
`preview_<id>_dark.jpg` dark):

| Imageset | Android card | Replaced |
|---|---|---|
| `preview_animation` | `animation-physics` (light: the generated fox, 2026-09-30; dark: the #4223 night capture) | a simulator capture of the bundled `cyberpunk_character` (Featured, #3907), light only |
| `preview_double_pendulum` | `double-pendulum` (the generated pair, 2026-09-30) | the previous generated pair; hidden from the iOS home until #3907 is fixed, the card shows in search and deep links |
| `preview_ar_placement` | `ar-placement` (the generated Toy Car pair, 2026-09-30) | the Toy Car opened in `model-viewer` (no ARKit on the Simulator), light only |
| `preview_ar_record_playback` | `ar-record-playback` | the Damaged Helmet opened in `model-viewer`, light only |
| `preview_ar_rerun` | `ar-rerun` | a capture of the bundled replay 7 s in, light only |
| `preview_camera_controls` | `camera-gestures` | a helmet-only render |
| `preview_custom_mesh` | `custom-geometry` | a sphere-and-axes render |
| `preview_lighting` | `lighting` | a spot-lit helmet render |
| `preview_lines_paths` | `lines-paths` | a wireframe arch render |
| `preview_materials` | `materials` | a helmet render (the `gen.py` row above is superseded) |
| `preview_model_viewer` | `model-viewer` | the same helmet, a different crop |
| `preview_rolling_balls` | `rolling-balls` | its own simulator capture (#4083) |
| `preview_splat_preview` | `splat-preview` | its own simulator capture of the dot rendering (#4073) |

The other way round, Android's `debug-overlay` card is the iOS pair (the `gen.py`
`debug-overlay` prompt of #3308): the golden crop in the table above was a black frame with
one small sphere, which the dissolving row turned into a black smear. WebP q85.

The replaced files are in git history; bring one back only with a capture of the home next
to it.

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

### HD pack thumbnails

`model_thumb_hd_<id>.webp` (Apollo 11 exterior and interior, woolly mammoth, Perseverance) are
the cards of the sheet's "Museum & Space" section. These models ship no bundled stand-in, so the
viewer also shows the thumbnail on the stage until the downloaded GLB is on screen. They are
Blender 4.4 renders of the exact GLB the pack serves (`assets/hd-pack/android.json`), same light,
lens and angles as above (30° yaw, 18° pitch, bounding sphere fit, transparent film, 80 % of
600×480, `cwebp -q 90`). Cycles for all but the Apollo interior, which is rendered with EEVEE
and back-face culling on its single-sided materials: that is what Filament does, and what opens
the capsule's walls so the cabin shows. Perseverance is posed at the start of its first clip,
the pose the viewer holds. The mammoth and the rover open turned by `frontYaw = -30°`, so their
30° thumbnails are the very angle the viewer opens on (the camera turned rather than the model).

### On iOS

`samples/ios-demo/SceneViewDemo/Assets.xcassets/model_thumb_<asset>.imageset` uses
the same files: a model shared with Android ships Android's WebP decoded with `dwebp` and
re-encoded as HEIC with alpha (`sips -s format heic -s formatOptions 85`; an asset catalog
takes no WebP). A HEIC source is a sixth of the PNG, but `actool` also keeps an ARGB
fallback copy of each HEIF, so the compiled `Assets.car` grows by about 870 KB (+17 %)
where PNG sources would cost about 200 KB more. The two iOS-only models, Cyberpunk Hovercar
and Butterfly, have no GLB on Android: they are rendered from their USDZ by an offline
SceneKit pass under the same `chinese_garden` light (exposure adaptation off, +0.7 EV), with
the same 50 mm lens, 30°/18° view, trim and 80 % fit on a transparent 600×480 canvas. The
Image Planes demo keeps its own opaque square pictures (`image_plane_*`): its unlit planes
draw no alpha.

### Scene cards

The sheet's "Scenes" row holds one card, the Park, in two files: the sheet shows the one this
build will load (`SketchfabConfig.apiKey`), so the card is what the scene opens on (#4039).

- `model_picker_park.webp`: the bundled fallback models only (soldier, sheen chair, lantern,
  shiba), what a **keyless** build loads. Emulator capture (Pixel_7a, 1080×2400) of
  `--es demo multi-model --ez qa_mode true` (orbit frozen) after `pm clear` with Wi-Fi and data
  off, so a keyed build falls back too. Window x 0–1080, y 790–1654, resized to 600×480 and
  encoded with `cwebp -q 85`.
- `model_picker_park_streamed.webp`: a **keyed** debug build, so the four streamed `park`
  registry models. Same capture online, window x 0–1080, y 880–1744, same resize and encoding.
  The image shows CC-BY 4.0 models, credited where the app credits them, under "Park
  (Multi-model)" in the Credits sheet: "Oak Trees" by bumstrum, "Simple Park Bench" by Planetrix23,
  "Street Lamp" by bez_glaza and "Plant Bush" by Batuhan13 (`SampleAssets.kt`).

Re-capture both when the `park` category or the Park's lawn changes.

The Scene Gallery card (`model_picker_gallery.webp`, a collage of four bundled fallbacks) left
with the Scene Gallery in #4039.

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
