# Website Filament materials

The three `.mat` sources in this directory back the **web** 3D viewer
(`sceneview-web`, Filament.js / WebGL2 + WASM). They are **intentionally
distinct** from the 11 Android `sceneview/src/main/materials/` sources — same
shading concept, leaner shader surface for the browser renderer.

| Material | Shading | Blending | Parameters |
|---|---|---|---|
| `lit_colored.mat` | lit (PBR) | opaque | `baseColor`, `metallic`, `roughness`, `reflectance` |
| `transparent_colored.mat` | lit (PBR) | transparent | `baseColor`, `metallic`, `roughness`, `reflectance` |
| `unlit_colored.mat` | unlit | opaque | `baseColor` |

## Why the web set diverges from Android

- **Parameter name.** The colour parameter is named `baseColor` here, vs `color`
  on the Android `opaque_colored.mat` / `transparent_colored.mat`. The web JS
  binding (`website-static/js/sceneview.js`) sets `baseColor` directly; Android
  goes through `MaterialInstance.setColor()` which writes `color`. Keep the two
  in sync **per platform** — do not rename one to match the other.
- **No external/video/view materials.** The web viewer has no `samplerExternal`
  camera feed, no Android `View` overlay, and no `ImageNode`/`VideoNode`. Only
  the colour materials are needed.
- **No `culling : none` / `doubleSided`.** The web demo geometry is closed, so
  the default single-sided culling is kept.

## Flag profile

All three blobs are compiled with **Profile D** — `matc -p mobile -a opengl` —
because the web renderer targets WebGL2 only (no Vulkan/Metal backend, mobile
feature level). This is deliberate and audited (issue #1918, Part A); do not
fold these into the Android profiles.

## Regenerating the `.filamat` blobs

These three blobs are part of the unified inventory in
[`tools/GenerateFilamat.sh`](../../tools/GenerateFilamat.sh). Regenerate with:

```bash
bash tools/GenerateFilamat.sh                        # all 20 blobs
bash tools/GenerateFilamat.sh --mat unlit_colored    # one web blob
```

The `--check` mode (wired into `quality-gate.sh`) fails CI if any committed
blob drifts from its `.mat` source. See CONTRIBUTING.md
"Filament runtime ↔ `.filamat` ABI invariant".
