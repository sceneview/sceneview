# Website Filament materials

The three `.mat` sources in this directory are the **web-profile material set**
(`sceneview-web`, Filament.js / WebGL2 + WASM — see the runtime-status note
below for what actually loads them). They are **intentionally distinct** from
the 13 Android `sceneview/src/main/materials/` sources — same shading concept,
leaner shader surface for the browser renderer.

## Runtime status — not loaded yet, but loadable (#2783)

**The current web viewers do NOT load these `.filamat` blobs at runtime.**
`website-static/js/sceneview.js` builds its materials through gltfio's
ubershader provider (glTF `baseColorFactor` / `baseColorMap`), and
`sceneview-web` deliberately avoids custom `.filamat` files (see
`GeometryGLBBuilder.kt`). The blobs are kept compiled as **source-of-record**,
tracked by the unified `tools/GenerateFilamat.sh` inventory and drift gate.

They are compiled with the **`filamentWebsite`** pin in
`gradle/libs.versions.toml` — the Filament.js build vendored at
`website-static/js/filament/` (currently matc 1.70.1 → MATERIAL_VERSION 70),
**not** the Android `filament` pin (1.72.1 → v72). Filament requires an *exact*
MATERIAL_VERSION match, so riding the Android pin made them unloadable by the
only runtime that would ever load them: #2783 found all three at v72 against a
v70 runtime — armed, never fired, because nothing calls `createMaterial()` yet.
Wiring one in now works; no runtime bump needed first.

Three pins, three runtimes, three material versions — keep them straight:

| Blob group | Pin | Runtime | MATERIAL_VERSION |
|---|---|---|---|
| `sceneview/`, `arsceneview/` assets | `filament` | Filament AAR (Android) | 72 |
| `website-static/materials/` | `filamentWebsite` | vendored `js/filament/` | 70 |
| `sceneview-web/materials/` | `filamentWeb` | npm `filament` | 52 |

`.claude/scripts/check-web-filamat-abi.sh` fails the quality gate if a web blob
and its runtime ever diverge again — including a runtime swapped without moving
its pin (the vendored bytes are sha256-pinned in
`website-static/js/filament/RUNTIME.json`).

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
