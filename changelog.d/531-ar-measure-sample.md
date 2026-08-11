<!-- category: Added -->
- New Android demo **`ar-measure`** — tap points on the real world and read the distance between
  them in centimetres on a 3D label anchored at the segment midpoint. Keep tapping for a chain
  with a running total, close the loop for a perimeter, and read the bounding box (W · H · D) of
  every point placed. Points are resolved in accuracy order — a detected plane's polygon first,
  then a `DepthPoint`, then the depth image directly via `Frame.hitTestDepth` (so clutter, slopes
  and edges that never grow a plane are measurable too), then a raw feature point — and the demo
  names on screen which source produced each point. Answers
  [#531](https://github.com/sceneview/sceneview/issues/531), asked in 2024, closed unanswered by
  the stale bot, and re-asked by a second user in 2025.
- [`samples/android-demo/AR_MEASURE.md`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/AR_MEASURE.md)
  documents the use case (surveying a real space to size something you will build or 3D-print for
  it) and, explicitly, the accuracy ceiling: several centimetres without a ToF sensor, approaching
  one centimetre with ToF/LiDAR — enough for layout, never for a fitting dimension on a printed
  part.

<!-- Ships as DemoStatus.InReview on purpose: the demo's own accuracy table in AR_MEASURE.md is
     empty until someone runs the documented 5-reading protocol against a known reference on real
     ARCore hardware. Neither CI nor the ARCore emulator can produce that figure — the emulator
     replays a synthetic scene, so a number obtained there would describe the simulation. Flip the
     status to Working in the same PR that fills the table.

     The Rerun export path is documented in AR_MEASURE.md but deliberately not wired into the
     demo: RerunBridge already exists in arsceneview (TCP -> Python sidecar -> .rrd, no NDK), so
     it is ~6 lines whenever it is wanted. -->
