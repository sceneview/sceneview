<!-- category: Tests -->
- **iOS demo: an opt-in measurement rig for the intermittent black viewport of
  [#3008](https://github.com/sceneview/sceneview/issues/3008).** A `SceneView`
  re-created by `.id()` sometimes renders nothing at all — no model, no skybox —
  and it does so on roughly a quarter to three-quarters of subject switches
  depending on the session, which makes any fix impossible to sign off by eye.
  `testBlackViewportProbe` drives the `AnimationDemo` subject row and attaches
  two samples per switch, so a viewport counts as black only when it is *still*
  black on the second one — a frame that has not rendered yet is not a black
  viewport, and the first calibration run caught exactly that case (black at
  +12 s, rendered at +20 s) which a single-sample method scores as a failure.
  It skips unless `SV_BLACK_PROBE=1` is set, so it never runs in CI; it also
  deliberately does **not** pass `-qa_mode 1`, because a zero auto-rotate speed
  short-circuits `SceneView`'s auto-rotate task (#2896) and would exercise a
  different render path from the one the defect lives on.
