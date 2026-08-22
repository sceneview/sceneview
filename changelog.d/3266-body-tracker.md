<!-- category: Fixed -->
- **android-demo**: `ar-body-tracker` now actually detects a body. The CPU camera image ARCore
  hands back is in raw sensor (landscape) orientation regardless of the device's display
  orientation, but the demo's YUV→bitmap conversion never corrected for it — on a portrait
  phone, MediaPipe's pose model was handed a person lying sideways and almost never found one.
  `PoseLandmarker.detect` is now called with an `ImageProcessingOptions` rotation hint (the same
  rotation-degrees mapping `ar-ml-object-label`'s ML Kit pipeline already used), so the model
  sees an upright frame and the existing 2D skeleton overlay — plus the "point the camera at a
  person" hint shown while nothing is tracked — now actually render the detection (#3266).
