<!-- category: Added -->
- **iOS — native camera modes** (`CameraControlMode`): four new iOS-only cases
  (`.none`, `.tilt`, `.dolly`, `.gimbal`) delegate directly to Apple's
  `realityViewCameraControls(_:)` modifier instead of SceneView's custom gesture
  math. The existing cross-platform modes (`.orbit`, `.pan`, `.firstPerson`) are
  unchanged — they keep orbit inertia, auto-rotate, and fit-to-bounds framing.
  Closes #1049 (Phase 2 — exposing the 4 Apple-only modes).
