<!-- category: Added -->
- **AR recovery cards accept host padding ([#4525](https://github.com/sceneview/sceneview/issues/4525)).** `ARCameraPermissionOverlay` and `ARCoreAvailabilityOverlay` accept `contentPadding`. `AutoPlacementScene` forwards `coachingContentPadding` to both cards even when coaching is disabled, so permission and session failures stay clear of host controls. Its `onCameraPermissionStateChanged` observer overload lets hosts track the permission card independently of session state while preserving existing positional callbacks and content.

<!-- category: Fixed -->
- **Demo app: One call keeps its AR failure card clear of the mode selector.** The AR Placement sample includes camera denial in recovery-card visibility and measures landscape corners inside the safe area. Recovery cards keep their actions usable when large text and system bars leave only a short band above the controls; settings and permission-request actions scroll together when both cannot fit.
