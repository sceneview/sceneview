<!-- category: Fixed -->
- `GestureDetector`: move/rotate/scale listeners dispatched the gesture-BEGIN
  `MotionEvent` (a stale, framework-recycled reference) to nodes on every
  mid-gesture callback — a destructured binding shadowed the live event. In AR
  this froze drag entirely: `PoseNode.onMove` re-hit-tested the finger-DOWN
  pixel every frame, so a dragged `AnchorNode`/model never moved. Gestures now
  pin the begin node and forward the live event ([#2629](https://github.com/sceneview/sceneview/issues/2629)).
