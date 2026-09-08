<!-- category: Changed -->
- **The Android demo's Materials section is rebuilt as a lit PBR studio
  ([#3495](https://github.com/sceneview/sceneview/issues/3495)).** It used to be a single
  sphere with a row of sliders. It is now a nine-material gallery staged on a studio wall —
  **Chrome · Gold · Copper · Brushed Aluminium · Glazed Ceramic · Car Paint · Velvet ·
  Crystal · Signature Glow** — under a swept key light, with three modes reachable from the
  dock: **Gallery** (tap any sphere), **Inspect** (one hero, live sliders, the glTF
  extension each parameter maps to spelled out under it — `KHR_materials_clearcoat`,
  `_sheen`, `_transmission`, `_emissive_strength`), and **Occlusion** (an occluder plane cut
  through the subject). A Compare control splits the stage into two spheres so a change reads
  against a reference instead of against memory, and four environments (Studio, Interior,
  Sunset, Night) show that every one of these parameters is a conversation with the IBL, not
  a colour picker.
<!-- RELEASE NOTE (maintainer-only):
     The demo now owns two Filament materials, `studio_pbr.mat` and `studio_glass.mat`,
     compiled through `tools/GenerateFilamat.sh` with the pinned matc (registered there, so
     `--check` covers them). It stopped using gltfio's ubershader, which was the cause of
     #3444's sibling symptom here: the ubershader declares the full glTF vertex layout as
     required (0x1f = position|tangents|color|uv0|uv1) and SceneView's procedural
     `SphereNode` supplies 0xb, so Filament shaded every sphere with a zero vertex colour
     and multiplied the base colour to black. Binding dummy white textures does not fix it —
     the missing input is a vertex attribute, not a sampler. The two demo materials require
     only `position` and `uv0`. They are split in two because Filament's sheen lobe and its
     refraction path cannot coexist in one material. -->
