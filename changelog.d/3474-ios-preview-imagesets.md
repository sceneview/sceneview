<!-- category: Fixed -->
- **The iOS demo's home cards now show the helmet the app really loads
  ([#3474](https://github.com/sceneview/sceneview/issues/3474)).** The Showcase hero
  banner and the Model Viewer, Dynamic Sky, PBR Materials and Fog preview imagesets in
  `Assets.xcassets` were still drawn from the deleted `hero.webp` render or from unrelated
  helmets — the defect #3454 and #3461 fixed on Android and in the store listings, one
  platform further out. The five imagesets are regenerated from
  `tools/demo-previews/refs/damaged_helmet.webp` with the prompts recorded in
  `prompts.json` / `heroes.json` (`gen.py --format jpg`), so the iOS cards, the Android
  cards and the store art converge on the same teal-visor helmet.
