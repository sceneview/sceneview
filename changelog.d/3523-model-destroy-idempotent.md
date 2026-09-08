<!-- category: Fixed -->
- **Leaving a screen while a model was still loading could kill the process
  ([#3523](https://github.com/sceneview/sceneview/issues/3523)).** A cancelled `loadModel`
  coroutine and `ModelLoader.clear()` could both reach `destroyAsset`/`releaseSourceData` for
  the same glTF asset, and the second one dereferenced a freed native pointer — a `SIGSEGV` in
  `libgltfio`, not an exception, so the `runCatching` around those calls never had a chance to
  help. It reproduced about twice per ten QA runs on the demo app, always by navigating back
  before the model finished loading. `ModelLoader.destroyModel` now *claims* the model out of
  its live-asset registry and only destroys it if the claim succeeded, so of any number of
  concurrent callers for one asset exactly one reaches Filament and the rest are no-ops.
  Registration into that registry also moved inside the same main-thread hop as the asset
  creation itself, closing the window where a model existed but was not yet claimable.
