<!-- category: Changed -->
<!-- breaking: false -->
The Android demo's default models are no longer a glTF conformance suite. Both surfaces
that offer a bundled catalogue — the shared tap-to-place picker
(`BUNDLED_PLACEMENT_MODELS`, used by the AR View tab and the `ar-placement` demo) and the
Model Viewer's model sheet — led with two untextured low-poly rows, `khronos_fox` (flat
vertex colours, no material maps at all) and `shiba` (a single base-colour map). In a
full-screen PBR viewer those two are the models that make Filament look worse than it is,
and neither is something anyone would place in a room. They are replaced by three Khronos
glTF-Sample-Assets pieces that each drive a *different* material model: **Glam Velvet
Sofa** (`KHR_materials_sheen` + `KHR_materials_specular`, Wayfair, LLC — Eric Chadwick,
CC-BY-4.0, 3.1 MB), **Sheen Chair** (`KHR_materials_sheen`, same author, CC0-1.0, 4.1 MB)
and **Iridescent Dish with Olives** (`KHR_materials_iridescence` + `transmission` +
`volume` + `ior`, same author, CC-BY-4.0, 5.7 MB). All three were already in the repo — the
`android-tv-demo` bundles the same byte-identical GLBs — so nothing new was sourced, and
all three are already proven to load in Filament's Android `gltfio` (PNG/JPEG textures, not
`EXT_texture_webp`, which that prebuilt cannot decode — #2305).

Nothing was deleted. `khronos_fox.glb` and `shiba.glb` still ship: `SampleAssets` uses them
as offline fallbacks and `ARTerrainAnchorDemo` loads the fox directly. Their thumbnails stay
mapped in `ModelThumbnails` too, so any surface naming them by stem still gets an image.

Three knock-on fixes fell out of the same work:

- **The picker's cards now show the model.** Every card rendered the same generic
  `ViewInAr` glyph, so the grid was six identical tiles under six labels and the only way
  to learn what a row looked like was to place it. Bundled rows now render their generated
  `model_thumb_<stem>.webp`; streamed rows keep the glyph, because their bytes are not in
  the APK and there is nothing honest to show until they land.
- **`realWorldSizeMeters` is measured, not estimated,** for the three new rows (2.19 m,
  0.83 m, 0.53 m). They are authored in metres, Y-up, sitting on y = 0, so the number fed to
  `ModelNode(scaleToUnits = …)` — and therefore what 100 % means on the pinch read-out
  (#3326) — is the GLB's own bounding box.
- **Two `ar_placement` offline fallbacks finally resemble what they stand in for.**
  #2960 documented that "Coffee Mug" fell back to a toy car and "Wooden End Table" to a
  fox — silhouette-class matches only — and said closing that gap needed assets the APK did
  not ship. It ships them now, so they fall back to the dish and the chair. Pairwise
  distinctness (#2355) is unchanged and still pinned by `SampleAssetsTest`.

Attribution was wrong for two of the three in `assets/catalog.json`, which is what every
generated `CREDITS.md` is built from: the Sheen Chair was recorded as KhronosGroup /
CC-BY-4.0 when its upstream `README.md` says Wayfair, LLC (Eric Chadwick) / **CC0-1.0**, and
the Iridescent Dish carried a `sourceUrl` that 404s (`IridescenceDishWithOlives` — the
directory is `IridescentDishWithOlives`) plus the same wrong author. Both are corrected and
every CREDITS surface regenerated.

One caveat worth recording rather than hiding: on `emulator-5554` (Android Emulator
OpenGL ES translator, ES 3.0 over Metal) the sofa's and chair's sheen reads exactly as
intended, but the dish's iridescent shell renders as a dark glossy form with no
thin-film colour shift under either the studio or the outdoor IBL. The model, its
orientation, its scale, the brushed-metal dish and the olives are all correct; it is the
`KHR_materials_iridescence` contribution specifically that does not appear on that GL
path. Confirm on a real GPU before quoting iridescence as the reason for this pick.

The three GLBs **moved** out of `samples/android-tv-demo/src/main/assets/models/` rather
than being copied. The TV demo merges the phone demo's asset folder via
`sourceSets.main.assets.srcDirs`, so the same `models/x.glb` present in both folders is
`Error: Duplicate resources` at `mergeAssets` — a hard build failure, not a last-one-wins.
The TV demo reaches all three at the identical `models/...` paths through that same line;
its APK still ships them at the same paths and the same byte sizes, and `TvModelListTest`
searches both folders, which is what makes the move a no-op for the TV demo.

Bundled model assets grow 17.7 MB → 30.1 MB.
