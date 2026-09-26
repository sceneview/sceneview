#if os(iOS)
import XCTest
import ARKit
import RealityKit
@testable import SceneViewSwift

/// #3912 — an AR session that runs and never delivers a frame used to leave
/// the host on "starting" over a black view for good. The watchdog is the
/// missing signal: one re-run with a tracking reset, then a `noCameraFrames`
/// failure. The policy is pure and tested on its own; the coordinator tests
/// cover the wiring that can run headlessly (world tracking cannot run on the
/// Simulator, so the retry's `session.run` is exercised through the policy
/// only).
@MainActor
final class ARSessionStartWatchdogTests: XCTestCase {

    // MARK: - Policy

    func testOneStartGetsOneRetryThenFails() {
        var watchdog = ARSessionStartWatchdog()

        XCTAssertEqual(watchdog.budgetExpired(), .retry)
        XCTAssertEqual(watchdog.budgetExpired(), .fail)
        XCTAssertEqual(watchdog.budgetExpired(), .fail, "a failed start never retries again on its own")
    }

    func testAFreshStartGetsItsRetryBack() {
        var watchdog = ARSessionStartWatchdog()
        _ = watchdog.budgetExpired()
        _ = watchdog.budgetExpired()

        watchdog.startedFresh()

        XCTAssertEqual(watchdog.budgetExpired(), .retry)
    }

    func testNoRetriesFailsOnTheFirstExpiry() {
        var watchdog = ARSessionStartWatchdog(retriesAllowed: 0)
        XCTAssertEqual(watchdog.budgetExpired(), .fail)
    }

    func testDefaultBudgetLeavesRoomForAColdStart() {
        // Sub-second on every supported iPhone; the budget must never turn a
        // slow cold start into a reset, nor keep a dead one on screen for
        // long: two budgets is the longest wait before the error state.
        XCTAssertEqual(ARSessionStartWatchdog.defaultBudget, 4)
        XCTAssertEqual(ARSessionStartWatchdog.defaultRetries, 1)
        XCTAssertEqual(ARSessionStartWatchdog().budget, 4)
    }

    // MARK: - The view

    func testTheViewIsCameraCompositedAndOwnsItsSession() {
        let arView = ARSceneView.makeARView()

        XCTAssertEqual(arView.cameraMode, .ar,
                       "the camera feed is stated on the view, not inherited from the process")
        XCTAssertFalse(arView.automaticallyConfigureSession,
                       "the coordinator runs the session, not RealityKit")
    }

    func testNoCameraFramesHasACopy() {
        XCTAssertEqual(
            ARSceneViewError.noCameraFrames.errorDescription,
            "The camera delivered no frames after the AR session started."
        )
    }

    // MARK: - Coordinator wiring

    private func makeStartedCoordinator(
        watchdog: ARSessionStartWatchdog = ARSessionStartWatchdog()
    ) -> (ARView, ARSceneView.Coordinator) {
        let arView = ARView(frame: .zero)
        let coordinator = ARSceneView.Coordinator(configuration: ARSessionConfiguration())
        coordinator.arView = arView
        coordinator.startWatchdog = watchdog
        // What `run` leaves behind, minus the `session.run` the Simulator
        // cannot perform.
        coordinator.sessionDidStart = true
        coordinator.awaitingFirstFrame = true
        return (arView, coordinator)
    }

    func testExpiryWithNoRetryLeftReportsNoCameraFrames() {
        let (arView, coordinator) = makeStartedCoordinator(
            watchdog: ARSessionStartWatchdog(retriesAllowed: 0)
        )
        var states: [ARSessionState] = []
        var failures: [ARSceneViewError] = []
        var legacy: [ARSceneViewError] = []
        coordinator.onSessionStateChange = { state, _ in states.append(state) }
        coordinator.onSessionEvent = { event, _ in
            if case .failed(let error) = event, let e = error as? ARSceneViewError { failures.append(e) }
        }
        coordinator.onSessionError = { error, _ in
            if let e = error as? ARSceneViewError { legacy.append(e) }
        }

        coordinator.startWatchdogExpired(in: arView)

        XCTAssertEqual(states, [.failed])
        XCTAssertEqual(failures, [.noCameraFrames])
        XCTAssertEqual(legacy, [.noCameraFrames], "the pre-existing closure hears it too")
        XCTAssertTrue(coordinator.awaitingFirstFrame,
                      "the session is left running: a late frame still flips the view to running")
        XCTAssertFalse(coordinator.isWatchingForFirstFrame)
    }

    func testALateFrameAfterTheReportStillRuns() {
        let (arView, coordinator) = makeStartedCoordinator(
            watchdog: ARSessionStartWatchdog(retriesAllowed: 0)
        )
        var states: [ARSessionState] = []
        coordinator.onSessionStateChange = { state, _ in states.append(state) }

        coordinator.startWatchdogExpired(in: arView)
        coordinator.noteFrame(trackingState: .normal, in: arView)

        XCTAssertEqual(states, [.failed, .running])
    }

    func testExpiryIsANoOpOnceAFrameArrived() {
        let (arView, coordinator) = makeStartedCoordinator(
            watchdog: ARSessionStartWatchdog(retriesAllowed: 0)
        )
        var states: [ARSessionState] = []
        var events: [String] = []
        coordinator.onSessionStateChange = { state, _ in states.append(state) }
        coordinator.onSessionEvent = { event, _ in events.append(String(describing: event)) }
        coordinator.noteFrame(trackingState: .normal, in: arView)

        coordinator.startWatchdogExpired(in: arView)

        XCTAssertEqual(states, [.running])
        XCTAssertEqual(events, [
            "firstFrame",
            "trackingStateChanged(SceneViewSwift.ARTrackingStatus.normal)",
        ], "a frame that beat the timer is the whole story")
    }

    func testExpiryIsANoOpWhenNoSessionRan() {
        let arView = ARView(frame: .zero)
        let coordinator = ARSceneView.Coordinator(configuration: ARSessionConfiguration())
        coordinator.startWatchdog = ARSessionStartWatchdog(retriesAllowed: 0)
        var events: [String] = []
        coordinator.onSessionEvent = { event, _ in events.append(String(describing: event)) }

        coordinator.startWatchdogExpired(in: arView)

        XCTAssertEqual(events, [], "a refused configuration is reported by startSession, not by the watchdog")
    }

    func testTheFirstFrameDisarmsTheWatchdog() {
        let (arView, coordinator) = makeStartedCoordinator()
        coordinator.armStartWatchdog(in: arView)
        XCTAssertTrue(coordinator.isWatchingForFirstFrame)

        coordinator.noteFrame(trackingState: .limited(.initializing), in: arView)

        XCTAssertFalse(coordinator.isWatchingForFirstFrame)
    }

    func testAnInterruptionDisarmsTheWatchdog() {
        let (arView, coordinator) = makeStartedCoordinator()
        coordinator.armStartWatchdog(in: arView)

        coordinator.noteInterruption(in: arView)

        XCTAssertFalse(coordinator.isWatchingForFirstFrame,
                       "no frame is expected while interrupted; the budget restarts when it ends")
    }

    func testAFailureDisarmsTheWatchdog() {
        let (arView, coordinator) = makeStartedCoordinator()
        coordinator.armStartWatchdog(in: arView)

        coordinator.reportFailure(ARSceneViewError.unsupported(.worldTracking), in: arView)

        XCTAssertFalse(coordinator.isWatchingForFirstFrame)
    }

    func testTeardownDisarmsTheWatchdog() {
        let (arView, coordinator) = makeStartedCoordinator()
        coordinator.armStartWatchdog(in: arView)

        ARSceneView.dismantleUIView(arView, coordinator: coordinator)

        XCTAssertFalse(coordinator.isWatchingForFirstFrame)
    }

    func testArmingAgainReplacesThePendingBudget() {
        let (arView, coordinator) = makeStartedCoordinator()
        coordinator.armStartWatchdog(in: arView)
        coordinator.armStartWatchdog(in: arView)

        coordinator.disarmStartWatchdog()

        XCTAssertFalse(coordinator.isWatchingForFirstFrame)
    }
}
#endif
