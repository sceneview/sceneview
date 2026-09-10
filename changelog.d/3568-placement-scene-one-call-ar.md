<!-- category: Changed -->
- **The `placement-scene` demo is now "One-Call AR", and says what it demonstrates before the
  camera opens ([#3568](https://github.com/sceneview/sceneview/issues/3568)).** Named "Placement
  Scene" and sitting next to "Tap to Place" and "Wall Placement", it opened straight into a
  camera with its only explanation buried in a Settings sheet — so it read as a second, worse
  copy of its neighbour, and the one person who wrote the SDK could not name its subject after a
  minute of use. Its subject is not that you can tap to place; it is that the entire camera
  screen is a **single `PlacementScene { anchor -> … }` call**, where `Tap to Place` hand-writes
  the same flow out of the low-level primitives to show what they are. The demo now opens on a
  still, themed screen that states exactly that, shows the snippet, introduces the cursor by
  rendering the real reticle in two non-AR scenes — one over a pale ground, one over a dark one —
  and only then opens the camera; Back returns there rather than leaving the demo. The deep link
  `sceneview://demo/placement-scene` is unchanged. It stays in the catalogue because it is the
  only demo that exercises the public one-call composable, which is also the first snippet
  `llms.txt` offers for AR placement.
