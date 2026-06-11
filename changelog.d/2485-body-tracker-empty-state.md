<!-- category: Fixed -->
- **AR Body Tracker**: replaced silent black screen with a camera-init scrim (spinner while
  ARCore starts) and a persistent in-viewport hint pill ("Point camera at a person — full body
  visible") that fades out once a skeleton is detected. Error states (model missing, ARCore
  tracking failure) now surface as a red pill directly in the viewport, matching the
  `ARFaceDemo` UX pattern. The live skeleton detection path is device-gated and unchanged.
