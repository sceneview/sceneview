# Pavilion source scene — pass 3

Original CC0 architecture, furniture and split-shell sculpture. Photographic stone
and velvet textures supplied by Thomas were generated with ChatGPT Image; see
[LICENSES.md](LICENSES.md). No recognisable designer furniture or helmet.

```sh
tools/scenes/pavilion/build.sh tools/scenes/pavilion/work tools/scenes/pavilion/work/assets
```

Requires Blender **5.2**, Python 3, curl and Filament `cmgen` on PATH. Thomas runs
Blender outside the sandbox. No Blender process was launched for pass 3.

`PREVIEW_THEMES = ('dusk',)` limits rendering to `preview_dusk.png` (1080×1350,
96 Cycles samples, denoised). Morning HDR grading, lighting values, AO/indirect
bakes, point colours and the GLB's initial morning state retain their existing
code path. Architecture and materials are shared by both themes.

Pass 3 uses a level 25 mm architectural camera, 1.2 m above water, looking 32°
along the facade toward the right. Its lens shifts are recorded in `report.json`;
core glTF cameras cannot encode off-axis lens shifts, so a consumer must apply
those separately to match the Cycles preview. The other six cameras have updated
aims and distances for their subjects; their centre projections were checked
numerically, not rendered.

The canopy is 12.8 m deep and 0.18 m thick, with 1.5 m overhangs beyond the outer
column grid. Chrome columns have 0.09 m radii and 0.05 roughness. The floor-to-roof
marble is nearer than the onyx, which sits behind glazing. The 0.9 m tall, 1.2 m
wide plinth carries an original roughly 0.5 m split-shell sculpture. The chair
keeps its original human-scale straight sled frame and plain cushions.

The terrace extends right beyond the shot and uses 1.2×0.8 m slabs, 4 mm joints,
and polished travertine at 0.15 roughness. Water is 5 cm below the terrace. The
basin has closed overlapping corners and no near coping across the foreground;
only a 6 cm flush edge follows the terrace. Water remains a separate replaceable
core-alpha material, without transmission, volume, specular, dispersion or IOR
extensions. Its dark glossy surface approximates reflection without refraction.

The crimson curtain uses `velvet_crimson.jpg` on continuous cloth UVs, sheen,
full-height deep pleats and a sideways wind displacement toward the right at the
hem; the fold depth remains constant down its height. The four photographic
textures retain procedural stone fallbacks for the three stone materials.

Dusk uses the landscape version of Poly Haven's CC0 **Qwantani Dusk 2**, with a
real distant horizon, moderate blue grading and preserved warm sunset colours.
Downloads are cached by asset ID so a changed HDRI cannot reuse an old mood cache.
Four slender cypresses sit beyond the roof end at camera-right; low hedges and
smaller trees close the left horizon over dark green lawn.

Twelve regularly spaced visible warm downlight discs have matching `lamp_*`
spots. An additional exportable `marble_graze` spot lights the marble's front.
The onyx's photographic colour map also drives its stronger dusk emission.
A preview-only onyx area light approximates the spill; runtime uses punctual
lights and emission. Cycles reflections/GI still need comparison with Filament.

The build runs Blender before cmgen. Both use the same graded `morning.hdr` and
`dusk.hdr`, with original sources preserved. At least 8 GiB free is required for
bakes. Output mirrors the Android assets tree:

- `models/pavilion.glb`
- `models/pavilion_points.bin`
- `environments/pavilion_morning_ibl.ktx`, `pavilion_morning_skybox.ktx`
- `environments/pavilion_dusk_ibl.ktx`, `pavilion_dusk_skybox.ktx`

Generated files stay in workDir for review. No app registration or distribution
is performed. `pavilion.blend` is the editable source. `report.json` records actual
instanced triangles, GLB bytes, extensions, camera shifts and dusk light settings.
The export retains the existing extension allowlist and 200k triangle ceiling.
All artistic controls are named constants in `build_pavilion.py`.

The 2048² AO/indirect atlas uses TEXCOORD_1; original material UVs use TEXCOORD_0.
Indirect-only diffuse with the morning sun on modulates AO by 35%; direct lights
remain live. Core occlusion cannot encode coloured or additive GI. The separate
morning diffuse bake supplies surface-point colours. Transparent panes, terrain,
vegetation, water and luminous discs stay outside the architectural atlas.

The GLB defaults to morning. For dusk, select the dusk KTXs, set sun and `lamp_*`
values from `report.json`, and enable `marble_graze` using its `dusk_watts` extra.
Set the onyx and downlight `emissiveFactor` and
`KHR_materials_emissive_strength.emissiveStrength` from their material extras.

## Point binary, version 1

Little endian, 16-byte header, then exactly count × 15 bytes; no padding:

| Offset | Type | Meaning |
|---|---|---|
| 0 | char[4] | ASCII `PAVP` |
| 4 | uint32 | Version `1` |
| 8 | uint32 | Point count `200000` |
| 12 | uint32 | Record stride `15` |
| 16+15i | float32[3] | xyz, metres, glTF right-handed Y up |
| 28+15i | uint8[3] | sRGB baked colour |

Sampling is area weighted and deterministic, with visibility from any of the seven
cameras checked against the surface BVH. Water is excluded. Positions already
include the source object's world transform and Blender-to-glTF axis conversion.

## Validation state

Pass 2's existing export report records **67,682 triangles / 20,014,308 bytes**.
Pass 3 is source-only: Python compilation and shell syntax checks passed. No
Blender build or new capture exists yet. Analytical topology based on the pass-2
export gives **approximately 104,882 triangles**: +32,524 for terrace/edge geometry,
+4,124 for vegetation, +552 for downlight discs. This assumes identical Blender
bevel tessellation and export splitting. Expected GLB size: **24–34 MB**, with
photographic normal-map and atlas compression the main uncertainty.

Thomas's next build must confirm `report.json`, extension compatibility and the
single dusk preview against `ref/pavillon-dark.jpg`. Visual approval and actual
export count/size remain pending that render.
