<!-- category: Fixed -->
- **`ar-scene-semantics` overlay never appeared, on every device
  ([#3527](https://github.com/sceneview/sceneview/issues/3527),
  [#3396](https://github.com/sceneview/sceneview/issues/3396)).** The demo painted the
  per-pixel semantic raster on a Filament 3D quad parented to the AR camera, but the camera
  background is deliberately drawn *last* (`ARCameraStream` priority 7, so it can early-Z-reject
  pixels already covered by opaque virtual geometry — #1617) while the overlay material had to
  disable depth *write* so `UNLABELED` pixels could stay transparent instead of punching an
  opaque hole. With nothing left in the depth buffer for the camera pass to reject against, the
  camera silently overdrew the overlay every single frame, regardless of device or opacity.
  The demo now colours the raster into a bitmap and composites it as a Compose `Image` on top
  of the `ARSceneView`, the same architecture `ARDepthVisualizationDemo` already uses — sidestepping
  Filament's render-order bookkeeping entirely. The colouring + display-rotation logic is a
  pure function, `SemanticsOverlay.labelBufferToArgb`, pinned by JVM tests.
