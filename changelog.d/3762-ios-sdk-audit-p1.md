<!-- category: Fixed -->
- **iOS AR sessions keep their configuration across an interruption.** People occlusion, mesh classification and extra plane alignments survive a background → foreground cycle instead of being reset to a stock world-tracking configuration, and ARKit relocalizes into the existing map rather than restarting tracking.
- **The SDK tap recognizer no longer shadows your own.** `ARSceneView` installs its tap recognizer only while an `onTapOnPlane` callback exists, so a host recognizer on the same view gets the tap.
- **Face tracking fails visibly instead of switching cameras.** On a device without a TrueDepth camera, `ARSceneView(faceTracking: true)` reports `ARSceneViewError.faceTrackingUnsupported` instead of silently running a rear-camera world-tracking session, and face sessions no longer show the horizontal-plane coaching overlay.
- **The AR exposure post-process can no longer drop frames.** A failed filter now passes the unprocessed frame through instead of leaving the destination texture unwritten (a black flicker or frozen black screen), and its `CIContext` is built once per view instead of once per frame. The parameter is documented for what it is: rendered-frame brightness, not capture exposure.
- **`showPlaneOverlay` and `showCoachingOverlay` are reactive.** Toggling them from SwiftUI now takes effect on the next render, so hosts no longer need to rekey the view — which restarted the AR session and dropped everything placed in it.
- **`ModelNode.scaleToUnits` respects the model's existing scale.** The correction is multiplied into the current scale instead of replacing it, so assets with authored root scaling come out the requested size, and normalising twice is a no-op. Non-finite or non-positive target sizes are rejected instead of collapsing or mirroring the model.
- **`PhysicsNode.applyImpulse` applies an impulse.** It used `addForce`, whose effect scaled with frame duration; it now delivers the requested N·s via `applyLinearImpulse(_:relativeTo:)`.
- **`VideoNode.load("sample")` finds the video.** An extensionless name is tried against `mp4`, `mov` and `m4v` in the bundle and on disk; a missing resource says so instead of pointing a player at a nonexistent path, and a failed `AVPlayerItem` is reported instead of showing a silent black plane.
- **Reference images are tracked by anchor identifier.** A target that leaves and re-enters the camera is detected again, and two prints of the same image get two anchors.

<!-- category: Added -->
- **`ARSceneView.onSessionError { error, arView in }`.** Additive modifier mirroring `onSessionStarted`, called on ARKit session failures (camera permission denial included) and on unsupported configurations, so a dead AR screen can explain itself. The console print stays as the fallback.

<!-- category: Changed -->
- **`FogNode` is deprecated.** RealityKit has no depth-based fog and this node only draws a large translucent sphere: nothing attenuates with distance and the sphere is picked up by automatic content framing. It keeps compiling and behaving for all of 4.x.
