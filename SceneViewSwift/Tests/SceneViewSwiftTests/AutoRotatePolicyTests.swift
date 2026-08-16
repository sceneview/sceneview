#if os(iOS) || os(macOS) || os(visionOS)
import XCTest
@testable import SceneViewSwift

/// Covers ``AutoRotatePolicy`` — the identity `SceneViewRepresentation` keys its
/// turntable `.task(id:)` on.
///
/// Two properties matter and neither is observable from a `SceneView` unit test:
///
///  1. **`isActive` matches the guard the loop used to inline.** Same three
///     conditions, same zero-speed freeze semantics (#2896 / #1049).
///  2. **Equality tracks the speed.** This is the whole #2935 fix: as an un-keyed
///     `.task` the loop read the speed once at view appear, so a spin toggle was
///     inert and demos re-keyed the `SceneView` with SwiftUI's `.id(_:)` to force
///     it — the #3008 renderer-teardown anti-pattern that leaves an iOS 26
///     Simulator scene permanently blank. A policy that compares unequal across a
///     speed change is what makes SwiftUI restart the loop in place instead.
final class AutoRotatePolicyTests: XCTestCase {

    // MARK: - isActive

    func testActiveWhenEnabledNonZeroSpeedAndCustomMode() {
        let policy = AutoRotatePolicy(isEnabled: true, speed: 0.2, mode: .orbit)
        XCTAssertTrue(policy.isActive)
    }

    func testInactiveWhenNeverEnabled() {
        // No `.autoRotate(...)` call at all — the default.
        XCTAssertFalse(AutoRotatePolicy(isEnabled: false, speed: 0.3, mode: .orbit).isActive)
    }

    /// `autoRotate(speed: 0)` is the documented freeze idiom (QA capture,
    /// UI tests) — it must not spin a 60 Hz timer advancing by 0 rad (#2896).
    func testZeroSpeedIsInactiveEvenWhenEnabled() {
        XCTAssertFalse(AutoRotatePolicy(isEnabled: true, speed: 0, mode: .orbit).isActive)
    }

    func testNegativeSpeedIsActive() {
        // Negative = rotate the other way, not "disabled".
        XCTAssertTrue(AutoRotatePolicy(isEnabled: true, speed: -0.2, mode: .orbit).isActive)
    }

    /// Native camera modes hand the transform to Apple's
    /// `realityViewCameraControls(_:)`; our azimuth mutation would fight it (#1049).
    func testInactiveInNativeCameraModes() {
        for mode: CameraControlMode in [.none, .tilt, .dolly] {
            XCTAssertFalse(
                AutoRotatePolicy(isEnabled: true, speed: 0.3, mode: mode).isActive,
                "\(mode) delegates the camera to RealityKit — SceneView must not drive it"
            )
        }
    }

    func testActiveInEveryCustomCameraMode() {
        for mode: CameraControlMode in [.orbit, .pan, .firstPerson] {
            XCTAssertTrue(AutoRotatePolicy(isEnabled: true, speed: 0.3, mode: mode).isActive)
        }
    }

    // MARK: - Task identity (#2935)

    /// The regression guard: a spin toggle changes only the speed, so the
    /// policy MUST compare unequal or SwiftUI keeps the stale loop running and
    /// the scene never stops spinning without an `.id(_:)` re-key.
    func testTogglingSpinChangesIdentity() {
        let spinning = AutoRotatePolicy(isEnabled: true, speed: 0.2, mode: .orbit)
        let frozen = AutoRotatePolicy(isEnabled: true, speed: 0.0, mode: .orbit)
        XCTAssertNotEqual(spinning, frozen)
        XCTAssertTrue(spinning.isActive)
        XCTAssertFalse(frozen.isActive)
    }

    func testChangingSpeedChangesIdentity() {
        XCTAssertNotEqual(
            AutoRotatePolicy(isEnabled: true, speed: 0.2, mode: .orbit),
            AutoRotatePolicy(isEnabled: true, speed: 0.4, mode: .orbit)
        )
    }

    func testChangingCameraModeChangesIdentity() {
        XCTAssertNotEqual(
            AutoRotatePolicy(isEnabled: true, speed: 0.2, mode: .orbit),
            AutoRotatePolicy(isEnabled: true, speed: 0.2, mode: .dolly)
        )
    }

    /// Conversely, an unchanged policy must be `==` so a routine body
    /// re-evaluation (a light-slot diff, a framing tick, a sibling's state
    /// change) does not cancel and restart the loop — which would reset the
    /// per-frame `dt` baseline on every re-render.
    func testUnchangedPolicyKeepsIdentity() {
        XCTAssertEqual(
            AutoRotatePolicy(isEnabled: true, speed: 0.2, mode: .orbit),
            AutoRotatePolicy(isEnabled: true, speed: 0.2, mode: .orbit)
        )
    }

    /// Every native mode collapses to the same identity, so switching between
    /// two of them never restarts a loop that is not running anyway.
    func testNativeModesShareOneIdentity() {
        XCTAssertEqual(
            AutoRotatePolicy(isEnabled: true, speed: 0.2, mode: .tilt),
            AutoRotatePolicy(isEnabled: true, speed: 0.2, mode: .dolly)
        )
    }
}
#endif
