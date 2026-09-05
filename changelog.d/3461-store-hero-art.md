<!-- category: Fixed -->
- **The Play Store and App Store listings now lead with the helmet the app really renders
  ([#3461](https://github.com/sceneview/sceneview/issues/3461)).** The generated AR visual
  that opens every screenshot class (#2844) and the Play feature graphic were still
  image-to-image from `tools/demo-previews/refs/hero.webp`, a stylised rusty helmet that
  `khronos_damaged_helmet.glb` does not render — the defect #3454 fixed on the catalog
  cards, one surface further out. All six store files are regenerated from
  `refs/damaged_helmet.webp`, the crop of the real `modelviewer_default` render golden, so
  the store, the Showcase hero banner and the Model Viewer card converge on the same
  teal-visor helmet. The prompts now live in `tools/demo-previews/store.json` and
  `gen.py --kind store` cuts each slot to its exact store pixel spec, so the art is
  reproducible the way the cards are; `refs/hero.webp` is deleted, nothing references it
  any more.
