<!-- category: Changed -->
- **Four catalog cards in the Android demo now show the demo they open
  ([#3437](https://github.com/sceneview/sceneview/issues/3437),
  [#3438](https://github.com/sceneview/sceneview/issues/3438)).** `custom-geometry`,
  `two-d-in-three-d` and `lines-paths` were rebuilt from scratch, but their home-screen art
  still advertised the scenes they replaced — a ball-and-stick molecule, a gallery of framed
  photos, a chain of beaded hairlines. `model-viewer` was wrong in a different way: its card
  showed a stylised helmet that `khronos_damaged_helmet.glb` does not render, so the first
  screen a user sees promised a different model than the one that loads. All four pairs are
  regenerated through `tools/demo-previews/`, light and dark, on the same studio backdrop as
  their neighbours; the two procedural demos are drawn from references rendered by their own
  generator code (`TorusKnot`, `LinesPathsScene`), so the card shows the exact curve the app
  computes. The `HomeHero` banner above the grid showed the same wrong helmet as the
  `model-viewer` card and is regenerated with it — the two sit a thumb's width apart on the
  first screen, which is what #3438 was filed about. `tools/demo-previews/gen.py` grew a
  `--kind hero` (`heroes.json`) so that banner has a recorded prompt like every other asset
  instead of being a one-off.
