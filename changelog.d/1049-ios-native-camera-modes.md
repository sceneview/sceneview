<!-- category: Added -->
- **iOS — native camera modes** (`CameraControlMode`): three new native cases
  (`.none`, `.tilt`, `.dolly`) delegate directly to Apple's
  `realityViewCameraControls(_:)` modifier (iOS 18+, macOS 15+, visionOS 2+)
  instead of SceneView's custom gesture math. The existing cross-platform modes
  (`.orbit`, `.pan`, `.firstPerson`) are unchanged — they keep orbit inertia,
  auto-rotate, and fit-to-bounds framing. Closes #1049 (Phase 2 — exposing
  the native Apple camera modes as verified in the Xcode SDK).
