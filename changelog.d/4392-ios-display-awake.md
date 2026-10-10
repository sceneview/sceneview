<!-- category: Fixed -->
- **AR on iOS: the display no longer sleeps in the middle of a session ([#4392](https://github.com/sceneview/sceneview/issues/4392)).** `ARSceneView` disables the app's idle timer while it is on screen and hands the previous setting back when it goes away. No API change; a 3D `SceneView` is unaffected.
