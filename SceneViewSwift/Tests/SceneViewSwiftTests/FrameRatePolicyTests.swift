#if os(iOS) || os(macOS) || os(visionOS)
import XCTest
import QuartzCore
@testable import SceneViewSwift

/// Covers ``FrameRatePolicy`` and the pure half of `FrameRateDriver`.
///
/// The parity claim this file defends is narrow and deliberate: the Swift enum must carry the
/// same cases, the same names and the same `maxFps` accessor as Android's sealed interface, and
/// must differ from it in exactly one documented place — a non-positive `maxFps` is clamped
/// here where Kotlin's `require(fps > 0)` throws, because a Swift `enum` case has no failable
/// construction point and trapping inside a display-link callback would turn a caller's typo
/// into a crash in a shipped app.
final class FrameRatePolicyTests: XCTestCase {

    // MARK: - Case shape and defaults

    /// The default policy. Named explicitly because `SceneView`'s own default has to stay in
    /// step with Android's `FrameRatePolicy.OnDemand()`; if someone flips the library default
    /// to `.continuous`, this is the test that says so.
    func testOnDemandIsTheUncappedDefaultShape() {
        let policy = FrameRatePolicy.onDemand()
        XCTAssertNil(policy.maxFps)
        XCTAssertNil(policy.resolvedMaxFps)
        XCTAssertFalse(policy.ticksWhenIdle)
    }

    func testContinuousIsTheOnlyCaseThatTicksWhenIdle() {
        XCTAssertTrue(FrameRatePolicy.continuous().ticksWhenIdle)
        XCTAssertTrue(FrameRatePolicy.continuous(maxFps: 30).ticksWhenIdle)
        XCTAssertFalse(FrameRatePolicy.onDemand().ticksWhenIdle)
        XCTAssertFalse(FrameRatePolicy.onDemand(maxFps: 30).ticksWhenIdle)
    }

    /// Both cases expose the ceiling through the same property, which is what lets call sites
    /// read `policy.maxFps` without switching — the point of the Kotlin `val maxFps: Int?` on
    /// the sealed interface.
    func testMaxFpsIsReadableWithoutSwitchingOnTheCase() {
        XCTAssertEqual(FrameRatePolicy.onDemand(maxFps: 24).maxFps, 24)
        XCTAssertEqual(FrameRatePolicy.continuous(maxFps: 24).maxFps, 24)
    }

    // MARK: - Clamping (the documented Android divergence)

    func testNonPositiveMaxFpsIsClampedRatherThanRejected() {
        // Android throws on these. Swift cannot, so the contract is "clamped to 1" — asserted
        // here so the divergence table on `FrameRatePolicy` stays true.
        XCTAssertEqual(FrameRatePolicy.onDemand(maxFps: 0).resolvedMaxFps, 1)
        XCTAssertEqual(FrameRatePolicy.onDemand(maxFps: -60).resolvedMaxFps, 1)
        XCTAssertEqual(FrameRatePolicy.continuous(maxFps: 0).resolvedMaxFps, 1)
    }

    func testMaxFpsIsPreservedVerbatimWhenItIsSane() {
        // `maxFps` reports what the caller wrote, `resolvedMaxFps` what the driver applies.
        // They agree everywhere except on the nonsense above, and a test that conflated them
        // would hide a clamp that fired when it should not have.
        for fps in [1, 24, 30, 60, 90, 120, 240] {
            XCTAssertEqual(FrameRatePolicy.onDemand(maxFps: fps).maxFps, fps)
            XCTAssertEqual(FrameRatePolicy.onDemand(maxFps: fps).resolvedMaxFps, fps)
        }
    }

    // MARK: - Identity

    /// `CameraMotionKey` holds a policy and drives a SwiftUI `.task(id:)`, so an over-eager
    /// `==` silently refuses to restart the driver when the host changes the cap, and an
    /// under-eager one restarts it on every body evaluation.
    func testEqualityDistinguishesCaseAndCeiling() {
        XCTAssertEqual(FrameRatePolicy.onDemand(maxFps: 30), .onDemand(maxFps: 30))
        XCTAssertNotEqual(FrameRatePolicy.onDemand(maxFps: 30), .onDemand(maxFps: 60))
        XCTAssertNotEqual(FrameRatePolicy.onDemand(maxFps: 30), .continuous(maxFps: 30))
        XCTAssertNotEqual(FrameRatePolicy.onDemand(), .onDemand(maxFps: 60))
    }

    func testPolicyChangeChangesTheCameraMotionKey() {
        let rotation = AutoRotatePolicy(isEnabled: true, speed: 0.2, mode: .orbit)
        let onDemand = CameraMotionKey(policy: rotation, coast: 0, frameRate: .onDemand())
        let continuous = CameraMotionKey(policy: rotation, coast: 0, frameRate: .continuous())
        XCTAssertNotEqual(onDemand, continuous)
    }

    /// The invalidator's whole mechanism: a parked driver has returned from its `.task`, and
    /// only a change of identity starts a new one.
    func testRenderRequestChangesTheCameraMotionKey() {
        let rotation = AutoRotatePolicy(isEnabled: false, speed: 0, mode: .orbit)
        let before = CameraMotionKey(policy: rotation, coast: 3, renderRequest: 7)
        let after = CameraMotionKey(policy: rotation, coast: 3, renderRequest: 8)
        XCTAssertNotEqual(before, after)
    }

    // MARK: - Cadence request (pure half of FrameRateDriver)

    func testUncappedRequestAsksForTheCeilingWithRoomToDrop() {
        let range = FrameRateDriver.frameRateRange(maxFps: nil)
        XCTAssertEqual(range.maximum, Float(FrameRateDriver.uncappedCeiling))
        XCTAssertEqual(range.preferred, Float(FrameRateDriver.uncappedCeiling))
        // Apple's guidance is a wide range, not a pinned value: a minimum equal to the maximum
        // forbids exactly the power saving the policy exists to enable.
        XCTAssertEqual(range.minimum, Float(FrameRateDriver.requestFloor))
        XCTAssertLessThan(range.minimum, range.maximum)
    }

    func testCappedRequestCarriesTheCap() {
        let range = FrameRateDriver.frameRateRange(maxFps: 60)
        XCTAssertEqual(range.maximum, 60)
        XCTAssertEqual(range.preferred, 60)
        XCTAssertEqual(range.minimum, Float(FrameRateDriver.requestFloor))
    }

    /// You cannot ask CoreAnimation for "at most 10 fps, at least 30". Below the floor the
    /// range has to collapse onto the ceiling rather than invert.
    func testCapBelowTheFloorCollapsesTheRangeInsteadOfInverting() {
        for fps in [1, 5, 10, 24, 29] {
            let range = FrameRateDriver.frameRateRange(maxFps: fps)
            XCTAssertEqual(range.maximum, Float(fps))
            XCTAssertEqual(range.minimum, Float(fps))
            XCTAssertLessThanOrEqual(range.minimum, range.maximum)
        }
    }

    func testFallbackIntervalMatchesTheCap() {
        XCTAssertEqual(FrameRateDriver.fallbackInterval(maxFps: 60), 1.0 / 60.0, accuracy: 1e-9)
        XCTAssertEqual(FrameRateDriver.fallbackInterval(maxFps: 30), 1.0 / 30.0, accuracy: 1e-9)
        // No cap: the timer fallback has no display to ask, so it runs at 60.
        XCTAssertEqual(FrameRateDriver.fallbackInterval(maxFps: nil), 1.0 / 60.0, accuracy: 1e-9)
    }

    /// Neither pure function may divide by zero or produce an inverted range, whatever it is
    /// handed — they sit on the path a caller's `maxFps: 0` takes if `resolvedMaxFps` is ever
    /// bypassed.
    func testPureFunctionsSurviveNonPositiveInput() {
        XCTAssertEqual(FrameRateDriver.frameRateRange(maxFps: 0).maximum, 1)
        XCTAssertEqual(FrameRateDriver.frameRateRange(maxFps: -30).maximum, 1)
        XCTAssertEqual(FrameRateDriver.fallbackInterval(maxFps: 0), 1.0, accuracy: 1e-9)
        XCTAssertEqual(FrameRateDriver.fallbackInterval(maxFps: -30), 1.0, accuracy: 1e-9)
    }
}

#endif
