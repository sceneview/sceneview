<!-- category: Added -->
- **Splats:** `rememberSplatCloud("splats/scan.spz")` loads a `.spz` or `.ply` file off the main
  thread and returns `null` while it loads. The location can be an asset path, an absolute path,
  or a `file://`, `content://` or `http(s)://` URI. `SplatNode` now sorts its splats for the
  scene camera on its own, in `SceneView` and `ARSceneView`, so orbiting no longer pops without
  a `cameraPositionProvider`. The provider still works, to sort for another viewpoint (#4023).
