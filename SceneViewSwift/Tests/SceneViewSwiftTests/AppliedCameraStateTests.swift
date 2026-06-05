import XCTest
import simd
@testable import SceneViewSwift

#if os(iOS) || os(macOS) || os(visionOS)
/// Unit coverage for the #2331 per-frame camera diff-guard, driving the
/// **production** ``AppliedCameraState/approximatelyMatches(_:)`` directly (it
/// was extracted from the `private` `SceneViewRepresentation` to a top-level
/// `internal` type for exactly this, #2412 — mirroring the #2313
/// ``EntityDragState`` extraction). These tests would fail on a regression to:
/// exact `==` (idle frames would repaint forever), a loosened `eps`, a dropped
/// `mode` exact-compare, or a missing nil ↔ non-nil `firstPersonEye` check.
final class AppliedCameraStateTests: XCTestCase {

    /// A change comfortably above `eps = 1e-5` — large enough to be immune to
    /// Float round-off near the threshold while unambiguously a real move.
    private static let bigDelta: Float = 0.01

    private func baseState(
        mode: CameraControlMode = .orbit,
        azimuth: Float = 0.5,
        elevation: Float = 0.4,
        orbitRadius: Float = 2.0,
        target: SIMD3<Float> = SIMD3<Float>(0.1, 0.2, 0.3),
        fov: Float = 60,
        firstPersonEye: SIMD3<Float>? = nil
    ) -> AppliedCameraState {
        AppliedCameraState(
            mode: mode,
            azimuth: azimuth,
            elevation: elevation,
            orbitRadius: orbitRadius,
            target: target,
            fov: fov,
            firstPersonEye: firstPersonEye
        )
    }

    // MARK: - Identity / idle

    func testIdenticalStateMatches() {
        XCTAssertTrue(baseState().approximatelyMatches(baseState()))
    }

    /// A drift far below `eps` — the common idle-frame / re-eval case — must be
    /// treated as identical so the diff-guard actually skips the entity write.
    /// Guards against a regression to exact `==` that would defeat #2331.
    func testSubEpsilonScalarDeltaStillMatches() {
        let a = baseState(azimuth: 0.5)
        let b = baseState(azimuth: 0.5 + 1e-6)
        XCTAssertTrue(a.approximatelyMatches(b))
    }

    func testFirstPersonEyeEqualMatches() {
        let eye = SIMD3<Float>(1, 2, 3)
        let a = baseState(mode: .firstPerson, firstPersonEye: eye)
        let b = baseState(mode: .firstPerson, firstPersonEye: eye)
        XCTAssertTrue(a.approximatelyMatches(b))
    }

    // MARK: - Per-scalar re-apply (delta above eps)

    func testAzimuthDeltaReapplies() {
        let a = baseState()
        let b = baseState(azimuth: 0.5 + Self.bigDelta)
        XCTAssertFalse(a.approximatelyMatches(b))
    }

    func testElevationDeltaReapplies() {
        let a = baseState()
        let b = baseState(elevation: 0.4 + Self.bigDelta)
        XCTAssertFalse(a.approximatelyMatches(b))
    }

    func testOrbitRadiusDeltaReapplies() {
        let a = baseState()
        let b = baseState(orbitRadius: 2.0 + Self.bigDelta)
        XCTAssertFalse(a.approximatelyMatches(b))
    }

    func testFovDeltaReapplies() {
        let a = baseState()
        let b = baseState(fov: 60 + Self.bigDelta)
        XCTAssertFalse(a.approximatelyMatches(b))
    }

    // MARK: - Target vector re-apply (each axis)

    func testTargetDeltaReapplies() {
        let a = baseState()
        let dx = baseState(target: SIMD3<Float>(0.1 + Self.bigDelta, 0.2, 0.3))
        let dy = baseState(target: SIMD3<Float>(0.1, 0.2 + Self.bigDelta, 0.3))
        let dz = baseState(target: SIMD3<Float>(0.1, 0.2, 0.3 + Self.bigDelta))
        XCTAssertFalse(a.approximatelyMatches(dx))
        XCTAssertFalse(a.approximatelyMatches(dy))
        XCTAssertFalse(a.approximatelyMatches(dz))
    }

    // MARK: - Mode

    func testModeChangeReapplies() {
        let a = baseState(mode: .orbit)
        let b = baseState(mode: .pan)
        XCTAssertFalse(a.approximatelyMatches(b))
    }

    // MARK: - firstPersonEye nil ↔ non-nil

    func testFirstPersonEyeNilToNonNilReapplies() {
        let none = baseState(firstPersonEye: nil)
        let some = baseState(firstPersonEye: SIMD3<Float>(1, 2, 3))
        XCTAssertFalse(none.approximatelyMatches(some))
        XCTAssertFalse(some.approximatelyMatches(none))
    }

    func testFirstPersonEyeDeltaReapplies() {
        let a = baseState(firstPersonEye: SIMD3<Float>(1, 2, 3))
        let b = baseState(firstPersonEye: SIMD3<Float>(1 + Self.bigDelta, 2, 3))
        XCTAssertFalse(a.approximatelyMatches(b))
    }
}
#endif
