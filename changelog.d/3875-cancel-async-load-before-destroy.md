<!-- category: Fixed -->

- **Destroying a model while its textures are still decoding no longer crashes ([#3868](https://github.com/sceneview/sceneview/issues/3868)).** `ModelLoader.destroyModel` freed the model without cancelling gltfio's pending texture load, so the next frame's `updateLoad()` wrote into freed textures (native `SIGSEGV` / use-after-free abort). `rememberModelInstance` hit it whenever its key changed right after a load. `destroyModel` now cancels that model's load first, the next load still completes, and `loadModelInstance` releases source data on the main thread.
