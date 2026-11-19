<!-- category: Fixed -->
- A TextureView scene torn down while its view still owns its SurfaceTexture (a scene re-keyed in place, or `SceneRenderer` used directly) now releases the `Surface` UiHelper created for it instead of leaving it to the finalizer ([#4337](https://github.com/sceneview/sceneview/issues/4337)).
