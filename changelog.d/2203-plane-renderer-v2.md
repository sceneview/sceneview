<!-- category: Added -->

- **Plane Renderer V2** — detected ARCore planes now render as a depth-driven PBR mesh
  lit by ARCore's HDR estimate ([#2203](https://github.com/sceneview/sceneview/issues/2203)).
  Floors, ceilings and walls each carry a distinct material identity, a brief scan-in
  animation runs the first time a plane is detected, and the reflection ramps in over
  ~1 s to mask the HDR estimate stabilisation. The legacy flat-polygon renderer remains
  available via `ARScene(planeRendererVersion = PlaneRendererBase.Version.V1)` for one
  release cycle and is now `@Deprecated`. Includes a new `ar-plane-renderer-v2` demo in
  `samples/android-demo` with a live V1 ↔ V2 toggle so the difference reads instantly.
