<!-- category: Fixed -->
- **A tap on a `ViewNode` seen from behind now lands on the pixel you are looking at
  ([#3329](https://github.com/sceneview/sceneview/issues/3329)).** The view material is
  double-sided and un-mirrors its UVs on the back face, so a quad orbited past edge-on keeps
  reading correctly on screen — but the touch mapping stayed front-face, so every touch on a
  turned-away quad landed on the horizontally mirrored pixel and the button under the finger
  did nothing for half of every revolution. `CollisionSystem.hitTest` now stamps the picking
  ray direction onto its `HitResult` (`RayHit.getWorldDirection()`), and `ViewNode` uses it to
  pick the right mapping. New `worldToLocalDirection` / `localToWorldDirection` conversions
  transform a free vector with `w = 0`, so a node's translation cannot leak into a direction.
- **Demo app — Picking & Collision is one scene instead of two settings tabs, and is lit
  ([#3329](https://github.com/sceneview/sceneview/issues/3329)).** The ray hit-test shapes and
  the live Compose card now share a single `SceneView`, and the card reports the hit-test state
  so the two halves visibly share one picking pass. Both card faces are real `Card(onClick = …)`
  targets, so a tap always reaches a Compose component whichever side is turned towards you.
  The primitives moved from flat unlit fills on black to lit PBR instances under the studio IBL
  plus a warm key light, and the row was pulled in to `x = ±0.5` with the eye at 4.2 m so it
  stops being clipped by the portrait viewport edges.
