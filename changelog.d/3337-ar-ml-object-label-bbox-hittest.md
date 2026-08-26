<!-- category: Fixed -->
- **AR object-label detections now anchor on the actual object, not a fixed corner of the
  screen ([#3337](https://github.com/sceneview/sceneview/issues/3337), follow-up from
  [#3322](https://github.com/sceneview/sceneview/issues/3322)).**
  `ARMLObjectLabelDemo` maps each ML Kit detection's bounding-box centre — reported in CPU
  image pixel space — to a screen-space point for `Frame.hitTest`. That mapping scaled the
  centre onto a hardcoded 1000×1000 square instead of the AR surface's real pixel size. On
  a tall portrait phone (e.g. a Pixel 9 at 1080×2424) that square only spans the top ~41% of
  the screen, so any object detected in the lower half of the camera frame hit-tested
  against the wrong point and its label landed off the object. The demo now measures its
  real surface size via Compose layout and scales against that instead.
