<!-- category: Fixed -->
- **`PlaneRendererV2`'s KDoc no longer claims V2 is the default plane renderer
  ([#3392](https://github.com/sceneview/sceneview/issues/3392)).** The class documentation
  still described the v4.16.0 state — "V2 is the default plane renderer as of this release"
  and "the legacy V1 `PlaneRenderer` ... is now `@Deprecated`" — while the code has said
  the opposite since v4.16.1: `ARSceneView`'s `planeRendererVersion` defaults to
  `PlaneRendererBase.Version.V1`, and `PlaneRenderer` carries no `@Deprecated` annotation.
  v4.16.0 briefly shipped V2 as the default, on-device QA showed the visual output not
  matching the design intent, and v4.16.1 reverted the default to V1 while V2 is polished
  ([#2203](https://github.com/sceneview/sceneview/issues/2203)) — that revert updated
  `PlaneRendererBase`, `PlaneRenderer`, `ARSceneView` and `llms.txt` but missed
  `PlaneRendererV2`, `PlaneVisualizerV2` and the `ARPlaneRendererV2Demo` sample, so a
  reader landing on the V2 class was told to expect V2 behaviour on a stock `ARSceneView`
  and that V1 was on its way out. All three now state that V2 is an experimental opt-in
  (`Version.V2`), that V1 is the default, and that V1 was never deprecated; the `#2203`
  sprint table records PR #5 as reverted instead of landed. The demo's KDoc distinguishes
  its own starting state (it opts into V2 explicitly) from the SDK default. Documentation
  only — no behaviour, no API and no default changed.
