<!-- category: Fixed -->
- **The Augmented Faces mesh is visible again, and it is lit
  ([#3575](https://github.com/sceneview/sceneview/issues/3575),
  [#3576](https://github.com/sceneview/sceneview/issues/3576)).** Face detection was never
  broken — the demo banner truthfully read "Tracking 1 face(s)" while the screen showed
  nothing. Augmented Faces only runs on a `Session.Feature.FRONT_CAMERA` session, and ARCore
  documents that such a session never tracks the device pose: `Camera.getTrackingState()`
  always returns `PAUSED`. `PoseNode` hides any node whose camera tracking state falls
  outside `visibleCameraTrackingStates`, which defaults to `{TRACKING}`. `AugmentedFaceNode`
  builds its mesh inside its own constructor, while that field still holds its initial
  value, so the mesh appeared for a frame or two and was then hidden — with its children —
  for the rest of the session. `AugmentedFaceNode` now opts out of the *camera* gate
  entirely; the face's own `TrackingState`, which is the one that actually means "there is a
  face here", still gates the mesh.
  With the mesh back on screen, its shading was the second half of the report: the demo
  painted it with an **unlit** flat colour and `computeTangents = false`, one uniform blue
  with no highlight and no falloff — a filter, not a fitted mesh. ARCore force-disables
  light estimation on a front-camera session, which is why the demo had drifted to unlit,
  but "no estimate" argues for a deterministic rig rather than for no shading. The face is
  now a lit PBR material with per-frame tangent quaternions, and the demo installs its own
  key and fill lights instead of inheriting the SDK's straight-down `(0, -1, 0)` default —
  the overhead angle that buries the eyes, the base of the nose and the mouth. A
  front-camera session pins world space to the device, so a fixed direction out of the
  screen is a stable, camera-anchored portrait key light.
