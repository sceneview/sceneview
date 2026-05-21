<!-- category: Added -->

- **sceneview-web:** WebXR feature parity composables — `XRDepthInfo` + `DepthOcclusionShader` (depth-sensing), `XRHandNode(handedness).joint(Joint.INDEX_TIP) { ... }` (hand-tracking, 25 joints), `XRImageTrackingNode(index = 0)` (image-tracking with the new `XRFeature.IMAGE_TRACKING` constant), `XRAnchorNode(xrAnchor)` (anchors). Mirrors the Android `arsceneview` composables. `XRFrame` gains the `getDepthInformation(view)` and `getImageTrackingResults()` extensions plus a `trackedAnchors` accessor (#1778, part of #1754).
