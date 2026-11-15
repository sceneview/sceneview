<!-- category: Added -->
- **AR recovery cards accept host padding ([#4525](https://github.com/sceneview/sceneview/issues/4525)).** `ARCameraPermissionOverlay` and `ARCoreAvailabilityOverlay` accept `contentPadding`. `AutoPlacementScene` forwards `coachingContentPadding` to both cards even when coaching is disabled, so permission and session failures stay clear of host controls.

<!-- category: Fixed -->
- **Demo app: One call keeps its AR failure card clear of the mode selector.** The AR Placement sample reserves room for the title and controls, using the free corner in landscape so the explanation and retry action remain visible.
