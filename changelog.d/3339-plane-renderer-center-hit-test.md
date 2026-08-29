<!-- category: Fixed -->
- **The plane renderer no longer fires an ARCore raycast just to pick which plane to
  highlight ([#3339](https://github.com/sceneview/sceneview/issues/3339)).**
  `PlaneRendererMode.RENDER_CENTER` is the default mode and the plane renderer is on by
  default, so **every** AR screen ran a centre-screen `frame.hitTest()` continuously —
  gated by `maxHitTestPerSecond = 10`, which against a 30 fps camera stream admits one
  pass every fourth frame, 7.5 times a second, for the lifetime of the screen. ARCore
  attempts a depth sub-test inside every `hitTest`, and on devices whose motion-stereo
  depth pipeline is unavailable that sub-test fails and ARCore's **native** logger emits a
  four-line `FAILED_PRECONDITION` / `depth_hit_test.cc` / `motion_stereo_manager.cc` /
  `AR_ERROR_ILLEGAL_STATE` block per call — forever, on every AR screen, with no way to
  opt out short of disabling the plane renderer. Nothing on the Kotlin side can filter a
  native log (the `depthPoint = false` result filter runs *after* the native call) and no
  `Frame.hitTest` overload accepts a trackable-type filter, so the only fix is not to make
  the call. The centre plane is now found analytically, by intersecting the camera's
  optical-axis ray with each candidate plane, applying exactly the acceptance rules the
  discarded `firstByTypeOrNull(HORIZONTAL_UPWARD_FACING)` applied through its defaults:
  `TRACKING` only, `HORIZONTAL_UPWARD_FACING` only, hit point inside the plane polygon.
  Subsumed planes are additionally skipped, since they are never drawn and highlighting
  one selected a plane that is not on screen. The failing depth sub-test never removed
  anything the call site wanted — the result was narrowed to horizontal planes, so depth
  could only ever have contributed `DepthPoint` candidates that were discarded anyway, and
  the warning was benign: it did not prevent anchoring or placement. Public API is
  unchanged — `maxHitTestPerSecond` keeps its name, default and meaning as a rate limit on
  the whole update pass, and `PlaneRendererBase.viewSize` stays part of the contract.
  Applies to both `PlaneRenderer` (V1, the default) and `PlaneRendererV2`.
