# Bundled Asset Credits — SceneView Android demo

Every asset bundled in the Play Store APK is listed below with its author,
license and original source, in compliance with the attribution clause of each
license (CC-BY 4.0 §3a in particular).

SceneView itself is Apache 2.0. Third-party assets listed here are NOT covered
by that license — each keeps the license granted by its original author.

<!-- GENERATED FILE — DO NOT EDIT BY HAND. -->
Generated from [`assets/catalog.json`](../../../../../assets/catalog.json) and the
contents of `samples/android-demo/src/main/assets` by
[`.claude/scripts/generate-credits.py`](../../../../../.claude/scripts/generate-credits.py).
Re-run that script after adding, removing or re-compressing a bundled asset;
`ci.yml` → `build` (step "Check asset credits") fails if this file and the
assets disagree.

Assets bundled: **42**.

> **Optimization note (#934, #2305).** The bundled GLBs and HDRs are compressed
> for a lean APK while preserving on-device visual quality:
> - **GLB models** — geometry is `KHR_draco_mesh_compression`. Textures are
>   **PNG/JPEG**: Filament's Android prebuilt ships `gltfio` with WebP support
>   compiled out (`isWebpSupported() == false`), so `EXT_texture_webp` textures
>   render untextured on Android (#2305). The models are therefore bundled with
>   PNG/JPEG textures the `gltfio` `StbProvider` decodes natively (Draco geometry
>   is decoded natively too). KTX2/Basis is a worthwhile future optimization to
>   reclaim the size PNG costs vs WebP.
> - **HDR environments** — equirect maps are downsampled 2048×1024 → 1024×512
>   in linear-radiance space (2×2 box average, energy-preserving). The `_2k`
>   suffix is kept as a stable filename only; resolution is now 1K.

---

## 3D models

- `models/khronos_damaged_helmet.glb` — **[Damaged Helmet](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/DamagedHelmet)** by theblueturtle_ (original, CC-BY-NC-4.0); ctxwing (rebuild, CC-BY-4.0) — [CC-BY-NC-4.0](https://creativecommons.org/licenses/by-nc/4.0/) (3.5 MB)
- `models/khronos_fox.glb` — **[Fox](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/Fox)** by PixelMannen (model), tomkranis (rigging & animation), @AsoboStudio and @scurest (glTF conversion) — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (96 KB)
- `models/khronos_glam_velvet_sofa.glb` — **[Glam Velvet Sofa](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/GlamVelvetSofa)** by Wayfair, LLC (Eric Chadwick) — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (3.1 MB)
- `models/khronos_iridescent_dish.glb` — **[Iridescent Dish with Olives](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/IridescentDishWithOlives)** by Wayfair, LLC (Eric Chadwick) — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (5.7 MB)
- `models/khronos_lantern.glb` — **[Lantern](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/Lantern)** by Microsoft — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (9.0 MB)
- `models/khronos_sheen_chair.glb` — **[Sheen Chair](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/SheenChair)** by Wayfair, LLC (Eric Chadwick) — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (4.1 MB)
- `models/khronos_toy_car.glb` — **[Toy Car](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/ToyCar)** by Guido Odendahl (model); Eric Chadwick (extensions and scene composition) — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (2.2 MB)
- `models/night_circuit.glb` — **[Night circuit (Khronos Car Concept on a SceneView set)](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/CarConcept)** by Darmstadt Graphics Group GmbH (Eric Chadwick); set by SceneView — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (4.2 MB)  
  The Car Concept on the night circuit built by `tools/scenes/circuit/build.sh`, the android-demo home hero (#3948)
- `models/quaternius_ranger.glb` — **[Ranger](https://quaternius.com/packs/modularcharacteroutfitsfantasy.html)** by Quaternius — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (5.7 MB)
- `models/shiba.glb` — **[Shiba](https://sketchfab.com/3d-models/shiba-faef9fe5ace445e7b2989d1c1ece361c)** by zixisun51 — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (669 KB)
- `splats/rainbow_sphere.ply` — **[rainbow_sphere.ply](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (448 KB)  
  Generated procedurally by `tools/generate-splat-sphere.py`

## HDR environments

- `environments/chinese_garden_2k.hdr` — **[Chinese Garden](https://polyhaven.com/a/chinese_garden)** by Sergej Majboroda — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (1.8 MB)
- `environments/circuit_ibl.ktx` — **[Night circuit environment](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (2.1 MB)  
  Rendered from the night circuit's set description and prefiltered with cmgen
- `environments/night_sky_2k.hdr` — **[Night Sky (Dikhololo Night)](https://polyhaven.com/a/dikhololo_night)** by Greg Zaal — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (1.7 MB)
- `environments/rooftop_night_2k.hdr` — **[Rooftop Night](https://polyhaven.com/a/rooftop_night)** by Greg Zaal — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (1.6 MB)  
  Verified 2026-10-10 against the Poly Haven download: a 1024x512 downsample of rooftop_night_2k.hdr (median pixel difference 0.3%) (#4453)
- `environments/sky_on_fire_2k.hdr` — **[The Sky Is On Fire](https://polyhaven.com/a/the_sky_is_on_fire)** by Greg Zaal — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (1.5 MB)  
  Poly Haven 1k HDR, rolled so the sunset sits behind the viewer's model and scaled to 0.45x radiance so the sky keeps its colour under the viewer's exposure
- `environments/studio_2k.hdr` — **[Studio](https://polyhaven.com/a/christmas_photo_studio_07)** by Sergej Majboroda — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (1.7 MB)  
  Verified 2026-10-10 against the Poly Haven download: pixel-identical to christmas_photo_studio_07_1k.hdr (#4453)
- `environments/studio_showroom_dark_2k.hdr` — **[Studio showroom, dark (synthetic photo studio)](https://github.com/sceneview/sceneview/tree/main/tools/scenes/studio)** by SceneView project — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (240 KB)  
  Generated deterministically by `tools/scenes/studio/build-hdr.mjs` (variant `dark`): a charcoal room with an overhead softbox and strip lights, no photographic source
- `environments/studio_showroom_light_2k.hdr` — **[Studio showroom, light (synthetic photo studio)](https://github.com/sceneview/sceneview/tree/main/tools/scenes/studio)** by SceneView project — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (240 KB)  
  Generated deterministically by `tools/scenes/studio/build-hdr.mjs` (variant `light`): a white cyclorama with an overhead softbox and strip lights, no photographic source
- `environments/studio_warm_2k.hdr` — **[Studio Warm](https://polyhaven.com/a/studio_small_08)** by Sergej Majboroda — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (1.5 MB)  
  Verified 2026-10-10 against the Poly Haven download: pixel-identical to studio_small_08_1k.hdr (#4453)

## Other bundled assets

- `audio/bell.wav` — **[bell.wav](https://creativecommons.org/publicdomain/zero/1.0/)** by SceneView project — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (27 KB)  
  Generated locally with ffmpeg (880 Hz sine, 0.6 s) — see `assets/audio/CREDITS.md`
- `augmented_images/qrcode.png` — **[qrcode.png](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (2 KB)  
  QR-like reference pattern drawn for `ARImageDemo`
- `materials/cosmos_dust.filamat` — **[cosmos_dust.filamat](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (40 KB)  
  Compiled from `samples/android-demo/src/main/materials/cosmos_dust.mat` (Cosmos demo, #4152)
- `materials/cosmos_planet.filamat` — **[cosmos_planet.filamat](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (57 KB)  
  Compiled from `samples/android-demo/src/main/materials/cosmos_planet.mat` (Cosmos ringed world, #4192)
- `materials/cosmos_plasma.filamat` — **[cosmos_plasma.filamat](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (56 KB)  
  Compiled from `samples/android-demo/src/main/materials/cosmos_plasma.mat` (Cosmos demo, #4152)
- `materials/cosmos_ribbon.filamat` — **[cosmos_ribbon.filamat](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (47 KB)  
  Compiled from `samples/android-demo/src/main/materials/cosmos_ribbon.mat` (Cosmos demo, #4152)
- `materials/cosmos_ring.filamat` — **[cosmos_ring.filamat](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (44 KB)  
  Compiled from `samples/android-demo/src/main/materials/cosmos_ring.mat` (Cosmos ringed world, #4192)
- `materials/cosmos_spacetime.filamat` — **[cosmos_spacetime.filamat](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (52 KB)  
  Compiled from `samples/android-demo/src/main/materials/cosmos_spacetime.mat` (Cosmos Spacetime view)
- `materials/cosmos_sprite.filamat` — **[cosmos_sprite.filamat](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (43 KB)  
  Compiled from `samples/android-demo/src/main/materials/cosmos_sprite.mat` (Cosmos demo, #4152)
- `materials/showroom_floor.filamat` — **[showroom_floor.filamat](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (631 KB)  
  Compiled from `samples/android-demo/src/main/materials/showroom_floor.mat` (Model Viewer showroom floor)
- `materials/studio_glass.filamat` — **[studio_glass.filamat](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (536 KB)  
  Compiled from `samples/android-demo/src/main/materials/studio_glass.mat`
- `materials/studio_pbr.filamat` — **[studio_pbr.filamat](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (676 KB)  
  Compiled from `samples/android-demo/src/main/materials/studio_pbr.mat`
- `materials/tray_wood.filamat` — **[tray_wood.filamat](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (733 KB)  
  Compiled from `samples/android-demo/src/main/materials/tray_wood.mat` (Rolling Balls board)
- `mediapipe/pose_landmarker_lite.task` — **[MediaPipe Pose Landmarker (lite)](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker)** by Google LLC — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (5.8 MB)  
  On-device pose model bundle used by `ARBodyTrackerDemo`
- `rerun/sample-session.jsonl` — **[sample-session.jsonl](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (176 KB)  
  Synthetic room walk in the Rerun bridge wire format — QA fixture of `ARRerunDemo`'s in-app 3D view (#3950)
- `rerun/showcase/showcase-manifest.json` — **[showcase-manifest.json](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (11 KB)  
  Lens, plane textures and media index of `ARRerunDemo`'s replay
- `rerun/showcase/showcase-media.bin` — **[showcase-media.bin](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (885 KB)  
  Camera frames and plane photos (WebP) of the same Pixel 9 recording, packed for `ARRerunDemo`
- `rerun/showcase/showcase-session.jsonl` — **[showcase-session.jsonl](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (1.4 MB)  
  Replay of a room recorded on a Pixel 9, camera path and points reconstructed with COLMAP — `ARRerunDemo`
- `splats/raccoon_family.spz` — **[Raccoon family (SPZ sample capture)](https://github.com/nianticlabs/spz/blob/main/samples/racoonfamily.spz)** by Niantic Labs — [MIT](https://github.com/nianticlabs/spz/blob/main/LICENSE) (3.3 MB)  
  Real phone capture shipped with the SPZ format; cropped to the subject (932 560 → 233 808 splats) by `tools/crop-spz.py` for `SplatPreviewDemo`
- `textures/sceneview_logo.png` — **[sceneview_logo.png](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (45 KB)  
  SceneView brand mark, exported from `branding/exports/logo/logo-1024.png`

## Poly Haven — blanket CC0-1.0

[Poly Haven](https://polyhaven.com) publishes its entire library under
[CC0-1.0](https://polyhaven.com/license); no attribution is required. The
per-asset author is shown where `assets/catalog.json` records one.

- `environments/outdoor_cloudy_2k.hdr` — **Outdoor Cloudy** (1.6 MB)
- `environments/sunset_2k.hdr` — **Seascape** (1.2 MB)

## HD pack (downloaded after install)

Not bundled: each model downloads from the `hd-pack-v1` GitHub Release when the user asks for it; only the Flight Helmet is fetched ahead, on Wi-Fi.
Listed from [`assets/hd-pack/android.json`](../../../../../assets/hd-pack/android.json).

- `adidas-high-top` — **[adidas high-top sneaker](https://sketchfab.com/3d-models/sneakers-adidas-yellow-black-89b7408463b144d0bd157cc788dd496d)** by mr.pinoux — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (4.3 MB)
- `air-jordan-1` — **[Nike Air Jordan 1](https://sketchfab.com/3d-models/air-jordan-1-a4b434181fbb48008ad460722fd53725)** by makoto — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (3.5 MB)
- `airpods-pro` — **[AirPods Pro](https://sketchfab.com/3d-models/airpods-pro-4478dddbfa244f6f9b93eebbd027a73b)** by JY_JY_ — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (3.1 MB)
- `amazonbasics-portable-ac` — **[AmazonBasics portable AC](https://amazon-berkeley-objects.s3.amazonaws.com/3dmodels/original/7/B07N6RTLP7.glb)** by Amazon.com — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (3.1 MB)
- `apollo11-exterior` — **[Apollo 11 Command Module](http://n2t.net/ark:/65665/nv9ce74610f-62de-46b6-904f-58abfecb555c)** by Smithsonian National Air and Space Museum — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (9.1 MB)
- `apollo11-interior` — **[Apollo 11 Interior](http://n2t.net/ark:/65665/nv9ce74610f-62de-46b6-904f-58abfecb555c)** by Smithsonian National Air and Space Museum — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (19.5 MB)
- `apple-watch-series-7` — **[Apple Watch Series 7](https://sketchfab.com/3d-models/apple-watch-series-7-free-watch-face-sdctm-cf15ecbf159f42a28df5e56bdfa666bb)** by SDC PERFORMANCE™️ — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (5.9 MB)
- `bell-x1` — **[Bell X-1 (1947)](http://n2t.net/ark:/65665/nv99a205dd3-3969-43d2-971c-d3d3e6c6b1f2)** by Smithsonian National Air and Space Museum — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (12.6 MB)
- `canon-at1` — **[Canon AT-1 (1977)](https://sketchfab.com/3d-models/canon-at-1-retro-camera-9de66868d0f240e985da00c9480bfc82)** by AleixoAlonso — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (3.1 MB)
- `canon-eos-rp-v2` — **[Canon EOS RP camera](https://sketchfab.com/3d-models/canon-eos-rp-mirrorless-camera-3d-model-cd470005397c48ec8b68bb8a4609ecd1)** by fahadratul — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (8.3 MB)
- `citroen-h-van` — **[Citroën H-style van](https://sketchfab.com/3d-models/french-delivery-van-low-poly-model-a350a422d200485ea57778dc42139edc)** by Daniel Zhabotinsky — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (3.7 MB)
- `city-bike` — **[City bike](https://sketchfab.com/3d-models/bicycle-197dd882a69846e0825e80566996cbba)** by TheDrone — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (4.2 MB)
- `converse-chuck-taylor` — **[Converse Chuck Taylor](https://sketchfab.com/3d-models/converse-free-bc791439d26f4dd9974819c168e12385)** by Yaroslav Karas — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (2.4 MB)
- `desktop-3d-printer` — **[Desktop 3D printer](https://sketchfab.com/3d-models/3d-printer-0e825a8d48b14bee883a131fe029d4fc)** by Gregsterius — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (1.6 MB)
- `dji-mavic-3-v2` — **[DJI Mavic 3 drone](https://sketchfab.com/3d-models/mavic-3-cine-60d4a042a6eb4a1e944b1af2d4e9368b)** by aurumjuda747 — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (6.4 MB)
- `dyson-hot-cool-fan` — **[Dyson Hot+Cool fan](https://sketchfab.com/3d-models/dyson-hotcool-fan-f31f9a62d4f64d2293d3e3969a30737e)** by Mr. Max — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (278 KB)
- `fangyi-bronze` — **[Fangyi bronze vessel](https://asia.si.edu/object/F1930.54a-b/)** by Smithsonian National Museum of Asian Art — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (12.7 MB)
- `fiat-500-classic` — **[Fiat 500 (classic)](https://sketchfab.com/3d-models/fiat-500-4606659ff31142e3b8080f995514452f)** by Francesco Coldesina — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (12.0 MB)
- `flight-helmet` — **[Flight Helmet](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/FlightHelmet)** by Gary Hsu (Microsoft) — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (48.4 MB)
- `galaxy-s21-ultra` — **[Samsung Galaxy S21 Ultra](https://sketchfab.com/3d-models/samsung-galaxy-s21-ultra-cd962832be7744efb6b37fe0ee2027e7)** by DatSketch — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (1.5 MB)
- `glass-door-fridge` — **[Glass-door fridge](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/CommercialRefrigerator)** by Darmstadt Graphics Group GmbH; based on "Commercial Fridge" by Sean Thomas — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (10.1 MB)
- `glass-kettle` — **[Glass kettle](https://sketchfab.com/3d-models/glass-kettle-3f646a36c0a142cfa451af70357f4235)** by Halil Kantarci — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (2.2 MB)
- `gopro-hero-8` — **[GoPro HERO8](https://sketchfab.com/3d-models/gopro-hero-8-3b38ac33b19d49d4b23b8125c6e1912b)** by Umezawa — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (2.6 MB)
- `ikea-kallax-shelf` — **[IKEA KALLAX shelf](https://sketchfab.com/3d-models/kallax-shelf-e2d30124563947608a500df26f77b659)** by CN Entertainment — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (10 KB)
- `ikea-linnmon-alex-desk` — **[IKEA LINNMON / ALEX desk](https://sketchfab.com/3d-models/ikea-linnmonalex-desk-902aa1b8083741bdbc2b09e092c2b39a)** by janicezhao — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (437 KB)
- `ikea-nockeby-sofa` — **[IKEA NOCKEBY sofa](https://sketchfab.com/3d-models/sofa-ikea-nockeby-db5d6bbf7f34482ca1be89456fcc4070)** by Filipe.Soares — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (739 KB)
- `ikea-trensum-mirror` — **[IKEA TRENSUM mirror](https://sketchfab.com/3d-models/mirror-ikea-trensum-258799ba04874be4a972b13eaf180fa0)** by YouniqueĪdeaStudio — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (1.2 MB)
- `instax-mini-8` — **[Instax Mini 8 camera](https://sketchfab.com/3d-models/instax-mini-8-instant-camera-606912cf09074dcbb35f6c4a3842d528)** by mariainggrid — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (1.3 MB)
- `ipad-mini-6` — **[iPad mini 6](https://sketchfab.com/3d-models/ipad-mini-6-2021-f363c2b1c74a462588db184bd4fb024e)** by DatSketch — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (2.0 MB)
- `iphone-13-pro-max` — **[iPhone 13 Pro Max](https://sketchfab.com/3d-models/apple-iphone-13-pro-max-4328dea00e47497dbeac73c556121bc9)** by DatSketch — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (1.3 MB)
- `jansport-backpack` — **[JanSport backpack](https://app.gazebosim.org/GoogleResearch/fuel/models/Jansport_School_Backpack_Blue_Streak)** by Google LLC — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (1.3 MB)
- `jbl-charge-speaker` — **[JBL Charge speaker](https://app.gazebosim.org/GoogleResearch/fuel/models/JBL_Charge_Speaker_portable_wireless_wired_Green)** by Google LLC — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (547 KB)
- `jbl-tour-one-m3-v2` — **[JBL Tour One M3 headphones](https://sketchfab.com/3d-models/jbl-tour-one-m3-black-45f0b3ccbee84492a05998d5daecff16)** by cubemodelex — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (4.4 MB)
- `kitchenaid-stand-mixer` — **[KitchenAid stand mixer](https://sketchfab.com/3d-models/kitchenaid-stand-mixer-716dfa11eb8f4020833584a3c3d09d5d)** by MelissaVerdonck — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (3.1 MB)
- `macbook-pro-13` — **[MacBook Pro 13"](https://sketchfab.com/3d-models/macbook-pro-13-inch-2020-efab224280fd4c3993c808107f7c0b38)** by timblewee — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (3.6 MB)
- `mech-drone` — **[Mech drone (sci-fi)](https://sketchfab.com/3d-models/mech-drone-8d06874aac5246c59edb4adbe3606e0e)** by Willy Decarpentrie — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (4.7 MB)
- `mini-cooper-s` — **[MINI Cooper S](https://sketchfab.com/3d-models/mini-cooper-s-b262744901a04cda823f17289d1a4847)** by cenobyte — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (4.5 MB)
- `mongoose-tyax-mtb` — **[Mongoose Tyax mountain bike](https://sketchfab.com/3d-models/mtb-mongoose-tyax-d5c7ab157aa84eb08e2deaee6165e764)** by Saushkin — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (6.8 MB)
- `moto-helmet-v2` — **[Motorcycle helmet](https://sketchfab.com/3d-models/moto-helmet-6c75ac1b13a047bd86742f37ce25adac)** by sayedgamal655 — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (3.1 MB)
- `nike-pegasus-36` — **[Nike Air Zoom Pegasus 36](https://sketchfab.com/3d-models/nike-air-zoom-pegasus-36-00fd99e778c244c3bd3b65f99dad7cb2)** by quaz30 — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (3.8 MB)
- `nintendo-switch-oled` — **[Nintendo Switch OLED](https://sketchfab.com/3d-models/nintendo-switch-oled-model-9b8721e10a4f4a689fc4f72cdcee4b01)** by rtql8d — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (2.6 MB)
- `perseverance` — **[Perseverance Rover](https://github.com/nasa/NASA-3D-Resources/tree/master/3D%20Models/Mars%202020%20Perseverance%20Rover)** by NASA/JPL-Caltech — [NASA Media Usage Guidelines (no endorsement implied; insignia removed)](https://www.nasa.gov/nasa-brand-center/images-and-media/) (4.9 MB)
- `peugeot-2008` — **[Peugeot 2008](https://sketchfab.com/3d-models/2020-peugeot-2008-93efd164d3374295a8ac04c36e329429)** by David_Holiday — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (10.1 MB)
- `photorealistic-guitar-v2` — **[Electric guitar](https://sketchfab.com/3d-models/photorealistic-western-guitar-f8d68a2c328345418db71b36f44168b9)** by Keandir — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (942 KB)
- `reebok-fueltrain` — **[Reebok FUELTRAIN](https://app.gazebosim.org/GoogleResearch/fuel/models/Reebok_FUELTRAIN)** by Google LLC — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (1.2 MB)
- `rivet-aiden-armchair` — **[Rivet Aiden armchair](https://amazon-berkeley-objects.s3.amazonaws.com/3dmodels/original/7/B075X2WN27.glb)** by Amazon.com — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (2.6 MB)
- `rivet-anne-desk` — **[Rivet Anne desk](https://amazon-berkeley-objects.s3.amazonaws.com/3dmodels/original/P/B07B7DL32P.glb)** by Amazon.com — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (2.2 MB)
- `rivet-arc-floor-lamp` — **[Rivet arc floor lamp](https://amazon-berkeley-objects.s3.amazonaws.com/3dmodels/original/1/B073772DH1.glb)** by Amazon.com — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (3.2 MB)
- `rivet-media-console` — **[Rivet media console](https://amazon-berkeley-objects.s3.amazonaws.com/3dmodels/original/R/B084QWW4LR.glb)** by Amazon.com — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (7.3 MB)
- `rivet-sloane-sofa` — **[Rivet Sloane sofa](https://amazon-berkeley-objects.s3.amazonaws.com/3dmodels/original/S/B072PZC1KS.glb)** by Amazon.com — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (2.9 MB)
- `rolex-watch-v2` — **[Rolex watch](https://sketchfab.com/3d-models/rolex-watch-0fde5c5f56d841cda53ae4a01f66dfaf)** by sudo-self — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (13.5 MB)
- `skullcandy-crusher` — **[Skullcandy Crusher headphones](https://sketchfab.com/3d-models/headphones-skullcandy-crusher-wireless-4e902017e4624547b440da0a6658a689)** by Ankledot — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (1.6 MB)
- `smeg-toaster` — **[SMEG toaster](https://sketchfab.com/3d-models/smeg-toaster-646838d76f204c83ae952e049f2948b1)** by The Fresh Lab — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (3.0 MB)
- `sony-mdr-7506` — **[Sony MDR-7506 headphones](https://sketchfab.com/3d-models/sony-mdr-7506-headphones-05735b74d3524f00b648231138122a28)** by AndreiVNK — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (1.2 MB)
- `stone-beam-dalton-sofa` — **[Stone & Beam Dalton sofa](https://amazon-berkeley-objects.s3.amazonaws.com/3dmodels/original/1/B07HZ6HH61.glb)** by Amazon.com — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (4.8 MB)
- `tennis-racket` — **[Tennis racket](https://sketchfab.com/3d-models/tennis-racket-c314dcce06ba488ca624957f579b8196)** by Yanez Designs — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (647 KB)
- `tesla-model-3` — **[Tesla Model 3](https://sketchfab.com/3d-models/tesla-model-3-123c10f376ec4f18b93c73afc382808b)** by David_Holiday — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (549 KB)
- `tier-escooter` — **[TIER e-scooter](https://sketchfab.com/3d-models/tier-scooter-9479a6d3f1474971afde26b33fe96c55)** by Niilo Poutanen — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (3.1 MB)
- `triceratops-skeleton` — **[Triceratops skeleton](http://n2t.net/ark:/65665/305d36697-5158-4947-8d0b-521db04bae9b)** by Smithsonian National Museum of Natural History — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (7.8 MB)
- `ue-boom-speaker` — **[Ultimate Ears BOOM speaker](https://app.gazebosim.org/GoogleResearch/fuel/models/Logitech_Ultimate_Ears_Boom_Wireless_Speaker_Night_Black)** by Google LLC — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (367 KB)
- `ugg-classic-tall` — **[UGG Classic Tall boot](https://app.gazebosim.org/GoogleResearch/fuel/models/UGG_Classic_Tall_Womens_Boots_Chestnut_7)** by Google LLC — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (844 KB)
- `vespa-et3` — **[Vespa Primavera ET3](https://sketchfab.com/3d-models/old-scooter-5e9b5072b2224ba982366490ad5f31d9)** by Nadia Ribitis — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (2.6 MB)
- `wishbone-chair` — **[Wishbone dining chair](https://amazon-berkeley-objects.s3.amazonaws.com/3dmodels/original/B/B07QJ1X2QB.glb)** by Amazon.com — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (2.3 MB)
- `woolly-mammoth` — **[Woolly Mammoth](http://n2t.net/ark:/65665/3a0f8f423-9320-45fc-93c0-6f5cabd957bf)** by Smithsonian National Museum of Natural History — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (5.1 MB)
- `xbox-wireless-controller` — **[Xbox Wireless Controller](https://sketchfab.com/3d-models/xbox-inalambric-controller-black-29cc9624894a4f2cae7d2a675044db45)** by Chistodrako._. — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (1.4 MB)

## Streamed media (played from a URL, never stored)

Not bundled: the app reads each file from the `assets-v1` GitHub Release while the demo that plays it is open.

- `big_buck_bunny_excerpt_720p.mp4` — **[Big Buck Bunny (excerpt)](https://www.bigbuckbunny.org)** by Blender Foundation (© 2008 Blender Foundation) — [CC-BY-3.0](https://creativecommons.org/licenses/by/3.0/) (3.3 MB)
  - 16 s cut from the film (1:48–2:04), resized to 1280×720, sound removed — played by `TwoDInThreeDDemo` (Media).

