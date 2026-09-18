#if os(iOS) || os(macOS) || os(visionOS)
import XCTest
@testable import SceneViewSwift

/// The hand-offs between the three camera writers — finger, coast, turntable —
/// must be continuous in position AND in velocity.
final class CameraMotionContinuityTests: XCTestCase {

    // MARK: - Drag start

    func testFirstDragTickOnlyTakesTheBaseline() {
        var baseline = CameraDragBaseline()
        // A DragGesture's first translation already holds its minimum distance.
        XCTAssertEqual(baseline.delta(to: CGSize(width: 10, height: 0)), .zero)
        XCTAssertEqual(baseline.delta(to: CGSize(width: 13, height: -2)), CGSize(width: 3, height: -2))
    }

    func testResetBaselineResumesFromTheFinger() {
        var baseline = CameraDragBaseline()
        _ = baseline.delta(to: CGSize(width: 10, height: 0))
        _ = baseline.delta(to: CGSize(width: 120, height: 40))
        baseline.reset() // a pinch ended, the centroid moved
        XCTAssertEqual(baseline.delta(to: CGSize(width: 190, height: 40)), .zero)
        XCTAssertEqual(baseline.delta(to: CGSize(width: 192, height: 41)), CGSize(width: 2, height: 1))
    }

    // MARK: - Release

    func testReleaseKeepsTheFingerSpeed() {
        var c = CameraControls(mode: .orbit)
        // 600 pt/s, 60 Hz: the finger moved the camera 10 pt per frame.
        c.endDrag(velocity: CGSize(width: 600, height: 0))
        let before = c.azimuth
        c.advance(dt: 1.0 / 60.0)
        XCTAssertEqual(c.azimuth - before, -10 * c.sensitivity, accuracy: 1e-5)
    }

    func testAStoppedFingerLeavesNoCoast() {
        var c = CameraControls(mode: .orbit)
        c.endDrag(velocity: .zero)
        XCTAssertFalse(c.advance(dt: 1.0 / 60.0))
    }

    func testCoastIsFrameRateIndependent() {
        var at60 = CameraControls(mode: .orbit)
        var at120 = CameraControls(mode: .orbit)
        at60.endDrag(velocity: CGSize(width: 900, height: 300))
        at120.endDrag(velocity: CGSize(width: 900, height: 300))
        for _ in 0..<30 { at60.advance(dt: 1.0 / 60.0) }
        for _ in 0..<60 { at120.advance(dt: 1.0 / 120.0) }
        XCTAssertEqual(at60.azimuth, at120.azimuth, accuracy: 1e-4)
        XCTAssertEqual(at60.elevation, at120.elevation, accuracy: 1e-4)
    }

    func testCoastDecaysToAStop() {
        var c = CameraControls(mode: .orbit)
        c.endDrag(velocity: CGSize(width: 1200, height: 0))
        var steps = 0
        while c.advance(dt: 1.0 / 60.0), steps < 600 { steps += 1 }
        XCTAssertLessThan(steps, 180, "a coast is over within three seconds")
        XCTAssertEqual(c.inertiaVelocity, .zero)
    }

    func testReleaseVelocityIsBounded() {
        var c = CameraControls(mode: .orbit)
        c.endDrag(velocity: CGSize(width: 1_000_000, height: -1_000_000))
        XCTAssertEqual(c.inertiaVelocity.width, 50, accuracy: 0.001)
        XCTAssertEqual(c.inertiaVelocity.height, -50, accuracy: 0.001)
    }

    // MARK: - Auto-rotation resume

    func testAutoRotationPicksItsSpeedUpAfterAGesture() {
        var c = CameraControls(mode: .orbit)
        c.isAutoRotating = true
        c.autoRotateSpeed = 1
        c.suspendAutoRotation()

        let dt: Float = 1.0 / 60.0
        var previousStep: Float = 0
        var firstStep: Float = -1
        for _ in 0..<60 {
            let before = c.azimuth
            c.advance(dt: dt)
            let step = c.azimuth - before
            if firstStep < 0 { firstStep = step }
            XCTAssertGreaterThanOrEqual(step, previousStep - 1e-6, "speed never drops while resuming")
            previousStep = step
        }
        XCTAssertLessThan(firstStep, 0.01 * dt, "starts from a standstill")
        XCTAssertEqual(previousStep, dt, accuracy: 1e-5, "ends at full speed")
    }

    func testReleaseHandsOverWithoutAVelocityStep() {
        var c = CameraControls(mode: .orbit)
        c.isAutoRotating = true
        c.autoRotateSpeed = 0.3
        c.suspendAutoRotation()
        // Finger moving the way the turntable turns: azimuth grows by 10 pt/frame.
        c.endDrag(velocity: CGSize(width: -600, height: 0))
        let dt: Float = 1.0 / 60.0
        let fingerStep = 10 * c.sensitivity

        var previous = fingerStep
        var worstChange: Float = 0
        for _ in 0..<120 {
            let before = c.azimuth
            c.advance(dt: dt)
            let step = c.azimuth - before
            worstChange = max(worstChange, abs(step - previous))
            previous = step
        }
        // Frame to frame the speed never changes by more than the coast's own
        // decay (8 % of the finger's speed) — no dead stop, no kick.
        XCTAssertLessThanOrEqual(worstChange, fingerStep * 0.081)
        XCTAssertEqual(previous, 0.3 * dt, accuracy: 1e-4, "settles at the turntable's speed")
    }

    // MARK: - Hitches

    func testALateFramePausesInsteadOfLeaping() {
        var c = CameraControls(mode: .orbit)
        c.isAutoRotating = true
        c.autoRotateSpeed = 1
        let before = c.azimuth
        c.advance(dt: 0.8) // a model landed on the main thread
        XCTAssertEqual(c.azimuth - before, CameraControls.maxMotionStep, accuracy: 1e-5)
    }

    func testAHostPoseCancelsTheCoast() {
        var c = CameraControls(mode: .orbit)
        c.endDrag(velocity: CGSize(width: 900, height: 0))
        c.apply(pose: SceneCameraPose(azimuth: 1, elevation: 0.2, distance: 3, target: .zero))
        XCTAssertFalse(c.advance(dt: 1.0 / 60.0))
        XCTAssertEqual(c.azimuth, 1, accuracy: 1e-6)
    }
}
#endif
