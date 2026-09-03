<!-- category: Changed -->
- **The eight remaining helmet cards in the Android demo now show the helmet the app really
  loads ([#3454](https://github.com/sceneview/sceneview/issues/3454)).** #3438 fixed
  `model-viewer` and the `HomeHero` banner, but `lighting`, `lighting-lab`, `fog`,
  `camera-gestures`, `materials`, `debug-overlay`, `video-recording` and `secondary-camera`
  were still generated from `tools/demo-previews/refs/hero.webp` — a stylised rusty helmet
  that `khronos_damaged_helmet.glb` does not render, even though all ten cards open the same
  GLB. Their prompts now point at `refs/damaged_helmet.webp`, the reference cropped from the
  `modelviewer_default` render golden, and name the model's real features (teal-green glass
  visor, cyan HUD ring, orange triangle marker, scuffed off-white plates, gold-brass jaw), so
  the sixteen regenerated images carry the same helmet, field, key light and contact shadow
  as the cards around them. Each card also states its own effect more plainly than before —
  the three coloured lights of `lighting` and the fog gradient of `fog` were barely readable,
  and `camera-gestures` no longer draws written labels over the scene.
