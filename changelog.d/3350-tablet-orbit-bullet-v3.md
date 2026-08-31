<!-- category: Fixed -->
The Play Store graphics README's follow-up bullet on tablet Model Viewer framing
non-determinism no longer cites the retired v2 evidence. The v3 re-capture
([#3350](https://github.com/sceneview/sceneview/pull/3350)) replaced the frames the
bullet pointed at, and in the committed v3 set the helmet holds the same head-on pose
on both tablet classes (Model Viewer is slot 2 there, not slot 1) — verified by
comparing `tablet7-screenshot-2.png` and `tablet10-screenshot-2.png` by eye. The
bullet now records that the committed pair matches while keeping the standing lesson:
the hero orbit is free-running, so any re-capture rolls the pose lottery again and the
camera distance must survive the widest orbit pose.
