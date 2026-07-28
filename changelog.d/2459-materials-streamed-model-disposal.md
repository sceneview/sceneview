<!-- category: Fixed -->
- **Demo: the Materials demo no longer leaks a streamed model per chip switch
  (#2459 class).** The PBR section's `rememberFileModelInstance` produced a
  `ModelInstance` through `produceState`, which cancels its producer on a key
  change but never destroys what it already produced — so every chip switch left
  the previous streamed `Model` GPU-resident in `ModelLoader.models` until the
  section's engine was torn down. It now mirrors the library's
  `rememberModelInstance` disposal contract (`DisposableEffect(instance)` →
  `destroyModel`), registered before the consuming `ModelNode` so the node
  detaches before the buffers are freed (#2424 ordering). Found by the
  adversarial review of [#2926](https://github.com/sceneview/sceneview/pull/2926).
