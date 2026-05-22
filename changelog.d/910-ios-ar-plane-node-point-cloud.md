<!-- category: Added -->
- **iOS AR Plane Node demo** (`ar-plane-node`): detects ARKit horizontal and vertical planes, places a translucent blue marker cube at each plane centre, and displays a live plane-count pill. Mirrors Android `ARPlaneNodeDemo`. (#910)
- **iOS AR Point Cloud demo** (`ar-point-cloud`): renders ARKit live tracking feature points via `ARView.debugOptions.showFeaturePoints`, shows a live point-count pill, and offers a toggle to enable/disable the overlay. Mirrors Android `ARPointCloudDemo`. (#910)
- **Fix pre-existing pbxproj bug**: `ARPeopleOcclusionDemo`, `ARBodyTrackerDemo`, `ARSceneMeshDemo`, and their scene-registry files were not registered in the Xcode project's Sources build phase — now fixed alongside the new demos. (#910)
