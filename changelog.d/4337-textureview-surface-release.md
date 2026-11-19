<!-- category: Fixed -->
- TextureView scenes now release UiHelper's Surface during teardown when the view still owns its SurfaceTexture, preventing the Surface.release finalizer warning ([#4337](https://github.com/sceneview/sceneview/issues/4337)).
