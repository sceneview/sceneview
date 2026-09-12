<!-- category: Added -->
- **Double-tap to zoom in, two-finger tap to zoom out, on every `Scene` / `SceneView` ([#3608](https://github.com/sceneview/sceneview/issues/3608)).** The gesture
  convention of photo viewers and maps, animated over 300 ms and clamped by the same distance limits
  as the pinch. On by default; opt out with
  `CameraGestureDetector.DefaultCameraManipulator.isDoubleTapZoomEnabled = false`, and re-tune with
  `doubleTapZoomFactor` / `doubleTapZoomDurationSeconds`. A consumer's own `onDoubleTap` callback and
  tap-to-pick still fire — the camera does not steal the event.
- **Custom `CameraManipulator` implementations can opt into the same gesture.** Override `doubleTapZoom(x, y, zoomIn)`; the
  step and the easing are public (`zoomedDistanceForDoubleTap`, `animatedZoomDistance`) so they do
  not have to be reimplemented.
