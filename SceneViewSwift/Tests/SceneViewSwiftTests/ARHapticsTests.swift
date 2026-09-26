import XCTest
@testable import SceneViewSwift
#if os(iOS)
import UIKit
#endif

/// Pins the 100 % snap math, shared with Android's `ScaleSnapTest`.
final class ARScaleSnapTests: XCTestCase {

    func testInsideTheWindowSnapsToExactly100Percent() {
        for raw: Float in [0.965, 0.99, 1.0, 1.03, 1.039] {
            let step = ARScaleSnap.step(previousDisplayed: 1.5, raw: raw)
            XCTAssertEqual(step.displayed, 1, "\(raw)")
            XCTAssertTrue(step.snapped)
        }
    }

    func testOutsideTheWindowFollowsThePinch() {
        let step = ARScaleSnap.step(previousDisplayed: 1, raw: 1.05)
        XCTAssertEqual(step.displayed, 1.05)
        XCTAssertFalse(step.snapped)
        XCTAssertFalse(step.enteredSnap)
    }

    func testEnteringTheDetentFiresOnce() {
        XCTAssertTrue(ARScaleSnap.step(previousDisplayed: 1.2, raw: 1.02).enteredSnap)
        XCTAssertTrue(ARScaleSnap.step(previousDisplayed: 0.8, raw: 0.97).enteredSnap)
        XCTAssertFalse(ARScaleSnap.step(previousDisplayed: 1, raw: 1.01).enteredSnap)
    }

    func testLimitsClampAndFireOnEntryOnly() {
        let max = ARScaleSnap.step(previousDisplayed: 3.5, raw: 9)
        XCTAssertEqual(max.displayed, 4)
        XCTAssertTrue(max.enteredLimit)
        XCTAssertFalse(ARScaleSnap.step(previousDisplayed: 4, raw: 5).enteredLimit)
        let min = ARScaleSnap.step(previousDisplayed: 0.3, raw: 0.1)
        XCTAssertEqual(min.displayed, 0.25)
        XCTAssertTrue(min.enteredLimit)
    }

    func testReboundContinuesThePinchDirectionThenSettlesOnExactlyOne() {
        XCTAssertEqual(ARScaleSnap.rebound(elapsedMs: 0, fromAbove: true), 1)
        XCTAssertEqual(ARScaleSnap.rebound(elapsedMs: ARScaleSnap.reboundMs, fromAbove: false), 1)
        let quarter = ARScaleSnap.reboundPeriodMs / 4
        XCTAssertLessThan(ARScaleSnap.rebound(elapsedMs: quarter, fromAbove: true), 1)
        XCTAssertGreaterThan(ARScaleSnap.rebound(elapsedMs: quarter, fromAbove: false), 1)
        var peak: Float = 0
        var t: Float = 0
        while t < ARScaleSnap.reboundMs {
            peak = Swift.max(peak, abs(ARScaleSnap.rebound(elapsedMs: t, fromAbove: false) - 1))
            t += 1
        }
        XCTAssertLessThanOrEqual(peak, ARScaleSnap.reboundAmplitude)
        XCTAssertGreaterThanOrEqual(peak, 0.02)
    }

    func testEventNamesMatchAndroid() {
        XCTAssertEqual(ARHapticEvent.allCases.map(\.rawValue), [
            "placed", "selected", "scaleSnapped", "limitReached",
            "invalidMove", "trackingLost", "recovered", "helpNeeded",
        ])
    }
}

#if os(iOS)
/// Pins the transition table shared with Android's `ARHapticTransitionsTest`, and the iOS
/// generator mapping.
@MainActor
final class ARHapticTransitionsTests: XCTestCase {

    private var transitions = ARHapticTransitions()
    private var clock: TimeInterval = 0

    private func feed(_ phase: ARPlacementPhase, placements: Int = 0,
                      selected: Bool = false, invalid: Bool = false) -> [ARHapticEvent] {
        clock += 1
        return transitions.next(.init(phase: phase, placements: placements,
                                      selected: selected, invalidMovement: invalid), now: clock)
    }

    func testFirstSnapshotOnlyRecords() {
        XCTAssertEqual(feed(.placed, placements: 1, selected: true), [])
    }

    func testPlacementPlaysPlacedOnceAndSwallowsItsSelection() {
        _ = feed(.initializing)
        _ = feed(.scanning)
        XCTAssertEqual(feed(.placed, placements: 1, selected: true), [.placed])
        XCTAssertEqual(feed(.placed, placements: 1, selected: true), [])
    }

    /// Drives the real lifecycle one frame and feeds the phase it publishes.
    private func frame(_ lifecycle: inout ARPlacementLifecycle, tracking: Bool) -> [ARHapticEvent] {
        clock += 1
        _ = lifecycle.frame(now: clock, tracking: tracking, anchorTracking: false, assetReady: true)
        return feed(lifecycle.phase, placements: lifecycle.placementsCreated)
    }

    func testTrackingLostAtSessionStartIsSilent() {
        // Untracked start-up frames keep the lifecycle `.initializing` (the coaching overlay's
        // start-up too), so no warning haptic plays before the first tracked frame.
        var lifecycle = ARPlacementLifecycle()
        lifecycle.requested = true
        _ = feed(lifecycle.phase)
        for _ in 0..<10 { XCTAssertEqual(frame(&lifecycle, tracking: false), []) }
        XCTAssertEqual(lifecycle.phase, .initializing)
        XCTAssertEqual(frame(&lifecycle, tracking: true), [])
        XCTAssertEqual(frame(&lifecycle, tracking: false), [.trackingLost])
    }

    func testStartOverRestartIsSilentToo() {
        // Coaching "Start Over" rewinds to `.initializing`: tracking was established before,
        // yet the restarted session's untracked frames play nothing.
        var lifecycle = ARPlacementLifecycle()
        lifecycle.requested = true
        _ = feed(lifecycle.phase)
        _ = frame(&lifecycle, tracking: true)
        lifecycle.restartSession()
        _ = feed(lifecycle.phase)
        for _ in 0..<5 { XCTAssertEqual(frame(&lifecycle, tracking: false), []) }
    }

    func testTrackingLostAfterTrackingWasEstablishedPlays() {
        _ = feed(.scanning)
        XCTAssertEqual(feed(.trackingLost), [.trackingLost])
    }

    func testRecoveryPlaysRecoveredFromEveryLostPhase() {
        _ = feed(.scanning)
        _ = feed(.placed, placements: 1, selected: true)
        for lost in [ARPlacementPhase.trackingLost, .recovering, .recoveryFailed] {
            _ = feed(lost, placements: 1, selected: true)
            XCTAssertEqual(feed(.placed, placements: 1, selected: true), [.recovered], "\(lost)")
        }
    }

    func testHelpCardsPlayHelpNeeded() {
        _ = feed(.scanning)
        XCTAssertEqual(feed(.noSurface), [.helpNeeded])
        _ = feed(.placed, placements: 1, selected: true)
        _ = feed(.recovering, placements: 1, selected: true)
        XCTAssertEqual(feed(.recoveryFailed, placements: 1, selected: true), [.helpNeeded])
    }

    func testTapAndInvalidMove() {
        _ = feed(.scanning)
        _ = feed(.placed, placements: 1, selected: true)
        _ = feed(.placed, placements: 1, selected: false)
        XCTAssertEqual(feed(.placed, placements: 1, selected: true), [.selected])
        _ = feed(.adjusting, placements: 1, selected: true)
        XCTAssertEqual(feed(.adjusting, placements: 1, selected: true, invalid: true), [.invalidMove])
        XCTAssertEqual(feed(.adjusting, placements: 1, selected: true, invalid: true), [])
    }

    func testSameEventWithin400msIsThrottled() {
        XCTAssertTrue(transitions.accept(.scaleSnapped, now: 1))
        XCTAssertFalse(transitions.accept(.scaleSnapped, now: 1.399))
        XCTAssertTrue(transitions.accept(.limitReached, now: 1.399))
        XCTAssertTrue(transitions.accept(.scaleSnapped, now: 1.5))
    }

    func testGeneratorTable() {
        XCTAssertEqual(SceneViewHaptic.feedback(for: .placed), .impact(.soft, intensity: 0.8))
        XCTAssertEqual(SceneViewHaptic.feedback(for: .selected), .selection)
        XCTAssertEqual(SceneViewHaptic.feedback(for: .scaleSnapped), .impact(.rigid, intensity: 0.7))
        XCTAssertEqual(SceneViewHaptic.feedback(for: .limitReached), .impact(.rigid, intensity: 0.5))
        XCTAssertEqual(SceneViewHaptic.feedback(for: .invalidMove), .impact(.rigid, intensity: 0.5))
        XCTAssertEqual(SceneViewHaptic.feedback(for: .trackingLost), .notification(.warning))
        XCTAssertEqual(SceneViewHaptic.feedback(for: .recovered), .notification(.success))
        XCTAssertEqual(SceneViewHaptic.feedback(for: .helpNeeded), .notification(.warning))
    }

    func testEveryEventPlaysAndPreparesWithoutThrowing() {
        let haptic = SceneViewHaptic()
        for event in ARHapticEvent.allCases {
            haptic.prepare(for: event)
            haptic.play(event)
        }
    }

    func testLifecycleCountsCommittedPlacementsOnly() {
        var lifecycle = ARPlacementLifecycle()
        lifecycle.commit()
        XCTAssertEqual(lifecycle.placementsCreated, 0, "no request, no placement")
        lifecycle.requested = true
        lifecycle.commit()
        lifecycle.commit()
        XCTAssertEqual(lifecycle.placementsCreated, 1)
        lifecycle.reset()
        lifecycle.commit()
        XCTAssertEqual(lifecycle.placementsCreated, 2)
    }
}
#endif
