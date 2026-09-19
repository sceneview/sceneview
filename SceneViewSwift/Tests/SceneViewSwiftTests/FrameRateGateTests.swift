#if os(iOS) || os(macOS) || os(visionOS)
import XCTest
@testable import SceneViewSwift

/// Covers ``FrameRateGate`` — the per-tick "keep running or park?" decision behind
/// ``FrameRatePolicy``.
///
/// The gate was extracted from the camera-motion loop precisely so this file could exist: the
/// loop itself needs a `RealityView`, a display link and a clock, and none of the three belong
/// in a unit test. Everything asserted here is what the loop actually runs — the
/// ``AppliedCameraState`` (#2412) / ``EntityDragState`` (#2313) precedent.
final class FrameRateGateTests: XCTestCase {

    /// Spend `count` idle ticks and return the last decision.
    @discardableResult
    private func idle(_ gate: inout FrameRateGate, ticks count: Int) -> FrameRateDecision {
        var decision = FrameRateDecision(isRunning: true, requestedMaxFps: nil)
        for _ in 0..<count {
            decision = gate.tick(FrameRateActivity())
        }
        return decision
    }

    // MARK: - FrameRateActivity

    func testActivityIsIdleOnlyWhenEverySourceIsQuiet() {
        XCTAssertFalse(FrameRateActivity().isActive)
        XCTAssertTrue(FrameRateActivity(isGestureInFlight: true).isActive)
        XCTAssertTrue(FrameRateActivity(isCameraMoving: true).isActive)
        XCTAssertTrue(FrameRateActivity(isFramingPending: true).isActive)
    }

    // MARK: - Parking

    /// The core of `.onDemand`: an untouched scene stops doing per-frame work.
    func testOnDemandParksAfterTheSettleBudget() {
        var gate = FrameRateGate(policy: .onDemand())

        // The budget is owed in full from construction, so the opening ticks all run.
        for _ in 0..<(FrameRateGate.settleFrames - 1) {
            XCTAssertTrue(gate.tick(FrameRateActivity()).isRunning)
        }
        XCTAssertFalse(idle(&gate, ticks: 1).isRunning)
    }

    /// The one behavioural difference between the two cases, asserted against the same
    /// input that parks `.onDemand`.
    func testContinuousNeverParks() {
        var gate = FrameRateGate(policy: .continuous())
        XCTAssertTrue(idle(&gate, ticks: FrameRateGate.settleFrames * 4).isRunning)
    }

    /// A parked driver must stop asking for cadence too, or `.onDemand` would leave a
    /// variable-refresh-rate panel pinned at the rate of a loop that is no longer running.
    func testParkingDropsTheCadenceRequest() {
        var gate = FrameRateGate(policy: .onDemand(maxFps: 60))
        let parked = idle(&gate, ticks: FrameRateGate.settleFrames)
        XCTAssertFalse(parked.isRunning)
        XCTAssertNil(parked.requestedMaxFps)
    }

    // MARK: - Re-arming

    func testEachActivitySourceRearmsTheFullBudget() {
        let sources: [(String, FrameRateActivity)] = [
            ("gesture", FrameRateActivity(isGestureInFlight: true)),
            ("camera", FrameRateActivity(isCameraMoving: true)),
            ("framing", FrameRateActivity(isFramingPending: true))
        ]
        for (name, activity) in sources {
            var gate = FrameRateGate(policy: .onDemand())
            // Burn the budget down to its last tick, then let this source speak.
            idle(&gate, ticks: FrameRateGate.settleFrames - 1)
            XCTAssertTrue(gate.tick(activity).isRunning, "\(name) should keep the driver running")
            XCTAssertEqual(gate.settleDebt, FrameRateGate.settleFrames, "\(name) should re-arm in full")
            // And the full budget really is available afterwards.
            for _ in 0..<(FrameRateGate.settleFrames - 1) {
                XCTAssertTrue(gate.tick(FrameRateActivity()).isRunning, "\(name) tail")
            }
            XCTAssertFalse(idle(&gate, ticks: 1).isRunning, "\(name) should park eventually")
        }
    }

    /// The push source. `requestRender()` has to survive to the next tick even though the
    /// activity struct says nothing is happening — that is the entire point of
    /// ``SceneRenderInvalidator``.
    func testRequestRenderRearmsAParkingGate() {
        var gate = FrameRateGate(policy: .onDemand())
        idle(&gate, ticks: FrameRateGate.settleFrames - 1)
        gate.requestRender()
        XCTAssertTrue(gate.tick(FrameRateActivity()).isRunning)
        XCTAssertEqual(gate.settleDebt, FrameRateGate.settleFrames)
    }

    /// A flag, not a counter: several requests between two ticks mean the same as one. If this
    /// ever became a counter, an app calling `requestRender()` in a tight loop would build a
    /// backlog it could never spend and the driver would stop parking at all.
    func testRepeatedRequestsBetweenTicksCollapseToOne() {
        var gate = FrameRateGate(policy: .onDemand())
        for _ in 0..<50 { gate.requestRender() }
        XCTAssertTrue(gate.tick(FrameRateActivity()).isRunning)
        XCTAssertFalse(gate.hasPendingRequest)
        XCTAssertEqual(gate.settleDebt, FrameRateGate.settleFrames)
        // And the gate parks on the normal schedule afterwards.
        XCTAssertFalse(idle(&gate, ticks: FrameRateGate.settleFrames).isRunning)
    }

    func testRequestIsConsumedByExactlyOneTick() {
        var gate = FrameRateGate(policy: .onDemand())
        gate.requestRender()
        XCTAssertTrue(gate.hasPendingRequest)
        _ = gate.tick(FrameRateActivity())
        XCTAssertFalse(gate.hasPendingRequest)
    }

    /// A long gesture holds the driver open for as long as the finger is down, not just for
    /// one settle budget.
    func testSustainedActivityHoldsTheDriverIndefinitely() {
        var gate = FrameRateGate(policy: .onDemand())
        let dragging = FrameRateActivity(isGestureInFlight: true)
        for _ in 0..<(FrameRateGate.settleFrames * 10) {
            XCTAssertTrue(gate.tick(dragging).isRunning)
        }
    }

    // MARK: - Cadence

    func testRunningGateRequestsThePolicyCeiling() {
        var uncapped = FrameRateGate(policy: .onDemand())
        XCTAssertNil(uncapped.tick(FrameRateActivity(isCameraMoving: true)).requestedMaxFps)

        var capped = FrameRateGate(policy: .onDemand(maxFps: 30))
        XCTAssertEqual(capped.tick(FrameRateActivity(isCameraMoving: true)).requestedMaxFps, 30)
    }

    /// `.continuous` never parks, so it never drops its request either — the `nil` in
    /// `FrameRateDecision.requestedMaxFps` must mean "no cap", never "no request", while the
    /// driver runs.
    func testContinuousKeepsRequestingItsCeilingWhileIdle() {
        var gate = FrameRateGate(policy: .continuous(maxFps: 60))
        let decision = idle(&gate, ticks: FrameRateGate.settleFrames * 2)
        XCTAssertTrue(decision.isRunning)
        XCTAssertEqual(decision.requestedMaxFps, 60)
    }

    /// The clamp reaches the driver through the gate, not only through direct reads of
    /// ``FrameRatePolicy/resolvedMaxFps``.
    func testGateAppliesTheMaxFpsClamp() {
        var gate = FrameRateGate(policy: .continuous(maxFps: 0))
        XCTAssertEqual(gate.tick(FrameRateActivity()).requestedMaxFps, 1)
    }

    // MARK: - Opening budget

    /// A fresh gate starts with the budget full. A scene has framing, lights and an
    /// environment to settle even if the user never touches it, and a gate that parked on its
    /// first idle tick would stop the camera-motion loop before the fit-to-bounds pass that
    /// depends on it had a chance to run.
    func testFreshGateOpensWithTheFullBudget() {
        let gate = FrameRateGate(policy: .onDemand())
        XCTAssertEqual(gate.settleDebt, FrameRateGate.settleFrames)
        XCTAssertFalse(gate.hasPendingRequest)
    }
}

#endif
