<!-- category: Fixed -->
- **AR camera permission recovery ([#4469](https://github.com/sceneview/sceneview/issues/4469)).** Repeated dismissals and fast ambiguous answers offer settings as the primary action with neutral copy while keeping another request available. The SDK card and Android demo share one recovery policy. Legacy scene hosts and direct ARCore callbacks retain their conservative settings recommendation and existing binary signatures.

<!-- category: Added -->
- **Android camera recovery API.** `ARCameraPermissionState.recovery` and its enum constructor expose the available actions without contradictory booleans. `ARCameraPermissionRecovery` and `resolveCameraPermissionRecovery` let custom gates use the shared Android policy; the rationale parameters follow Android's convention, the inverse of `ARPermissionHandler.shouldShowPermissionRationale()`.
