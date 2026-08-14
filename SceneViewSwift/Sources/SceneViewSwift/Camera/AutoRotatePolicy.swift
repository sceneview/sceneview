#if os(iOS) || os(macOS) || os(visionOS)

/// The full set of inputs that decide whether ``SceneView``'s turntable loop
/// should be running, and how fast.
///
/// `SceneViewRepresentation` keys its auto-rotation `.task(id:)` on this value,
/// which is what makes ``SceneView/autoRotate(speed:)`` **reactive**: a host
/// that flips a "Spin scene" toggle changes `speed`, the identity changes, and
/// SwiftUI cancels the old loop and starts one matching the new policy.
///
/// Before #2935 the loop was an un-keyed `.task`, so it read `speed` exactly
/// once — at view appear — and a later change did nothing. The only way to make
/// a spin toggle work was to re-key the whole `SceneView` with SwiftUI's
/// `.id(_:)`, which is precisely the renderer-teardown anti-pattern #3008
/// documented against (an iOS 26 Simulator `RealityView` rebuilt that way
/// intermittently renders nothing at all — no model, no skybox — permanently).
/// Making the policy the task's identity removes the reason to re-key.
///
/// Extracted to a top-level `internal` type, rather than living inline in the
/// `private` representation, so the "should a loop run at all" predicate is
/// unit-testable against the **same** code the view runs — the ``AppliedCameraState``
/// (#2412) / ``EntityDragState`` (#2313) precedent.
struct AutoRotatePolicy: Hashable {
    /// `true` once any ``SceneView/autoRotate(speed:)`` call has been made.
    let isEnabled: Bool

    /// Requested rotation speed in radians per second. May be negative
    /// (rotates the other way) or zero (freeze).
    let speed: Float

    /// Whether the camera mode is one SceneView drives itself. Native modes
    /// (`.none` / `.tilt` / `.dolly` / `.gimbal`) hand the transform to Apple's
    /// `realityViewCameraControls(_:)`, so an azimuth mutation would fight it.
    let modeIsCustom: Bool

    init(isEnabled: Bool, speed: Float, modeIsCustom: Bool) {
        self.isEnabled = isEnabled
        self.speed = speed
        self.modeIsCustom = modeIsCustom
    }

    init(isEnabled: Bool, speed: Float, mode: CameraControlMode) {
        self.init(isEnabled: isEnabled, speed: speed, modeIsCustom: mode.isCustom)
    }

    /// Whether a rotation loop should be running.
    ///
    /// A zero speed is *not* "enabled at 0 rad/s": it must exit rather than
    /// spin a 60 Hz timer advancing the azimuth by zero every frame, because
    /// callers freeze a scene by passing `autoRotate(speed: 0)` — the QA-capture
    /// path does exactly that (#2896).
    var isActive: Bool { isEnabled && speed != 0 && modeIsCustom }
}

#endif
