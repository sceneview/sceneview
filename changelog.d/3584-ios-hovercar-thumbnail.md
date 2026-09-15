<!-- category: Fixed -->
- **iOS demo: Cyberpunk Hovercar had no thumbnail in the Model Viewer list
  ([#3584](https://github.com/sceneview/sceneview/issues/3584)).** `ViewerAssetTests` now
  walks `ModelViewerDemo`'s own bundled-model and environment catalogs (instead of a
  hand-copied duplicate that could silently drift from them) so a future model added
  without its `model_thumb_<asset>` tile fails the suite instead of rendering a blank
  placeholder.
