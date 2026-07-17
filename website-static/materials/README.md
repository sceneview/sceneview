# Website Filament materials

The three `.mat` sources in this directory are the **web-profile material set**
(`sceneview-web`, Filament.js / WebGL2 + WASM — see the runtime-status note
below for what actually loads them). They are **intentionally distinct** from
the 13 Android `sceneview/src/main/materials/` sources — same shading concept,
leaner shader surface for the browser renderer.

## Runtime status — source-of-record only (read before wiring the blobs)

**The current web viewers do NOT load these `.filamat` blobs at runtime.**
`website-static/js/sceneview.js` builds its materials through gltfio's
ubershader provider (glTF `baseColorFactor` / `baseColorMap`), and
`sceneview-web` deliberately avoids custom `.filamat` files (see
`GeometryGLBBuilder.kt`). The blobs are kept compiled as **source-of-record**,
tracked by the unified `tools/GenerateFilamat.sh` inventory and drift gate, and
follow the Android `filament` toolchain in `gradle/libs.versions.toml`
(currently matc 1.72.1 → MATERIAL_VERSION 72).

⚠️ Because Filament requires an **exact MATERIAL_VERSION match**, these v72
blobs are NOT loadable by the web runtimes as shipped today (`sceneview-web`
pins npm `filament` 1.52.3; `website-static` vendors Filament.js 1.70.2).
Before wiring any of these blobs into a web viewer, bump that viewer's
Filament.js runtime to a release whose material version matches the blobs.

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
bash tools/GenerateFilamat.sh                        # all 22 blobs
bash tools/GenerateFilamat.sh --mat unlit_colored    # one web blob
```

The `--check` mode (wired into `quality-gate.sh`) fails CI if any committed
blob drifts from its `.mat` source. See CONTRIBUTING.md
"Filament runtime ↔ `.filamat` ABI invariant".
