<!-- category: Fixed -->
- **AR occlusion was inverted wherever ARCore had no depth, and the demo never said what it
  was showing ([#3340](https://github.com/sceneview/sceneview/issues/3340)).**
  `camera_stream_depth.mat` fed ARCore's "no depth here" sentinel (`depth_mm == 0`) straight
  into the projection, so `view.w` was `0` and `gl_FragDepth` became `+inf` — clamped to
  `1.0`, which under Filament's reverse-Z is the **near** plane. The camera quad therefore
  occluded every virtual fragment exactly where the depth image had no data: sky, thin
  edges, moving subjects, whole frames when depth estimation drops out mid-session, and
  every frame before the first depth image lands. The model simply disappeared with
  occlusion on. Invalid texels now project a far-but-finite distance instead, so "no depth
  data" degrades to "occludes nothing".
  `camera_stream_person_occlusion.mat` carried the same hole plus its own sign error — it
  wrote `0.0` for PERSON-mask pixels, the reverse-Z **far** plane, pushing people behind
  virtual objects rather than in front of them; it now writes the near plane.
  `ARDepthOcclusionDemo` also stopped explaining itself: its `DEPTH ON` / `DEPTH OFF` chip
  named an ARCore setting rather than an effect, and the toggle that *is* the demo lived
  two taps deep in the Settings sheet. The on-screen pill now states the consequence of
  each state ("real objects in front of the model hide it"), a coaching line names the one
  gesture that reveals it, and the toggle sits under the thumb next to Clear. The chip's
  hardcoded green/red is gone, replaced by the shared `ar-scrim` overlay tokens.
