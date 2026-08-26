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
`ci.yml` → `repo-hygiene` fails if this file and the assets disagree.

Assets bundled: **22**.

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

- `models/khronos_damaged_helmet.glb` — **[Damaged Helmet](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/DamagedHelmet)** by KhronosGroup (theblueturtle_) — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (3.5 MB)
- `models/khronos_fox.glb` — **[Fox](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/Fox)** by PixelMannen (model), tomkranis (rigging & animation) — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (96 KB)
- `models/khronos_glam_velvet_sofa.glb` — **[Glam Velvet Sofa](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/GlamVelvetSofa)** by Wayfair, LLC (Eric Chadwick) — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (3.1 MB)
- `models/khronos_iridescent_dish.glb` — **[Iridescent Dish with Olives](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/IridescentDishWithOlives)** by Wayfair, LLC (Eric Chadwick) — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (5.7 MB)
- `models/khronos_lantern.glb` — **[Lantern](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/Lantern)** by Microsoft — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (9.0 MB)
- `models/khronos_sheen_chair.glb` — **[Sheen Chair](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/SheenChair)** by Wayfair, LLC (Eric Chadwick) — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (4.1 MB)
- `models/khronos_toy_car.glb` — **[Toy Car](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/ToyCar)** by KhronosGroup — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (2.2 MB)
- `models/shiba.glb` — **[Shiba](https://sketchfab.com/3d-models/shiba-faef9fe5ace445e7b2989d1c1ece361c)** by zixisun51 — [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/) (669 KB)
- `models/threejs_soldier.glb` — **[Soldier](https://github.com/mrdoob/three.js/blob/dev/examples/models/gltf/Soldier.glb)** by Tomás Laulhé (modified by Don McCurdy) — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (2.3 MB)
- `splats/rainbow_sphere.ply` — **[rainbow_sphere.ply](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (448 KB)  
  Generated procedurally by `tools/generate-splat-sphere.py`

## HDR environments

- `environments/chinese_garden_2k.hdr` — **[Chinese Garden](https://polyhaven.com/a/chinese_garden)** by Sergej Majboroda — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (1.8 MB)
- `environments/night_sky_2k.hdr` — **[Night Sky (Dikhololo Night)](https://polyhaven.com/a/dikhololo_night)** by Greg Zaal — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (1.7 MB)

## Other bundled assets

- `audio/bell.wav` — **[bell.wav](https://creativecommons.org/publicdomain/zero/1.0/)** by SceneView project — [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/) (27 KB)  
  Generated locally with ffmpeg (880 Hz sine, 0.6 s) — see `assets/audio/CREDITS.md`
- `augmented_images/qrcode.png` — **[qrcode.png](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (2 KB)  
  QR-like reference pattern drawn for `ARImageDemo`
- `mediapipe/pose_landmarker_lite.task` — **[MediaPipe Pose Landmarker (lite)](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker)** by Google LLC — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (5.8 MB)  
  On-device pose model bundle used by `ARBodyTrackerDemo`
- `textures/sceneview_logo.png` — **[sceneview_logo.png](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (45 KB)  
  SceneView brand mark, exported from `branding/exports/logo/logo-1024.png`
- `videos/sample.mp4` — **[sample.mp4](https://github.com/sceneview/sceneview/blob/main/LICENSE)** by SceneView project — [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) (529 KB)  
  Generated with ffmpeg — 10 s / 1280×720 / H.264 brand animation for `TwoDInThreeDDemo`

## Poly Haven — blanket CC0-1.0

[Poly Haven](https://polyhaven.com) publishes its entire library under
[CC0-1.0](https://polyhaven.com/license); no attribution is required. The
per-asset author is shown where `assets/catalog.json` records one.

- `environments/outdoor_cloudy_2k.hdr` — **Outdoor Cloudy** (1.6 MB)
- `environments/rooftop_night_2k.hdr` — **Rooftop Night** (1.6 MB)
- `environments/studio_2k.hdr` — **Studio** (1.7 MB)
- `environments/studio_warm_2k.hdr` — **Studio Warm** (1.5 MB)
- `environments/sunset_2k.hdr` — **Sunset** (1.2 MB)

