import ARKit
import AVFoundation
import RealityKit
import SceneViewSwift
import XCTest
@testable import SceneViewDemo

/// Routing of the AR container model: which card each entry lands on, and
/// which session events move the "Starting camera…" state on.
@MainActor
final class ARExperienceModelTests: XCTestCase {

    private func model(
        requirement: ARExperienceRequirement = .worldTracking,
        isSupported: Bool = true,
        status: AVAuthorizationStatus = .authorized,
        grant: Bool = true
    ) -> ARExperienceModel {
        ARExperienceModel(
            requirement: requirement,
            isSupported: isSupported,
            authorizationStatus: { status },
            requestAccess: { completion in completion(grant) }
        )
    }

    // MARK: - Session events

    func testInterruptionEndedWhileStartingKeepsStartingUntilTheNextFirstFrame() {
        let model = model()
        model.resolve()
        XCTAssertEqual(model.phase, .starting)

        model.arSession(didEmit: .interruptionEnded, in: ARView(frame: .zero))
        XCTAssertEqual(model.phase, .starting, "ARKit resumed, but no frame is on screen yet")

        model.arSession(didEmit: .firstFrame, in: ARView(frame: .zero))
        XCTAssertEqual(model.phase, .live)
    }

    func testOnlyTheFirstFrameFlipsStartingToLive() {
        let arView = ARView(frame: .zero)
        let model = model()
        model.resolve()
        for event: ARSessionEvent in [
            .started(ARSessionConfiguration()),
            .trackingStateChanged(.limited(.initializing)),
            .interrupted,
            .interruptionEnded,
        ] {
            model.arSession(didEmit: event, in: arView)
            XCTAssertEqual(model.phase, .starting, "\(event) is not a frame")
        }
        model.arSession(didEmit: .firstFrame, in: arView)
        XCTAssertEqual(model.phase, .live)
    }

    func testFailedShowsTheErrorCard() {
        struct Boom: Error {}
        let model = model()
        model.resolve()
        model.arSession(didEmit: .failed(Boom()), in: ARView(frame: .zero))
        XCTAssertEqual(model.phase, .error("Camera couldn't start."))
    }

    func testAnUnsupportedFailureUsesTheUnsupportedCopy() {
        let model = model()
        model.resolve()
        model.arSession(didEmit: .failed(ARSceneViewError.unsupported(.lidar)), in: ARView(frame: .zero))
        XCTAssertEqual(model.phase, .error("This feature isn't available on this device."))
    }

    // MARK: - Routing

    func testUnsupportedWinsOverPermission() {
        let model = model(requirement: .lidar, isSupported: false, status: .notDetermined)
        model.resolve()
        XCTAssertEqual(model.phase, .unsupported(.lidar))
    }

    func testDeniedAndRestrictedLandOnTheDeniedCard() {
        for status: AVAuthorizationStatus in [.denied, .restricted] {
            let model = model(status: status)
            model.resolve()
            XCTAssertEqual(model.phase, .denied)
        }
    }

    func testNotDeterminedAsksFirstThenStartsTheCameraWhenGranted() {
        let model = model(status: .notDetermined, grant: true)
        model.resolve()
        XCTAssertEqual(model.phase, .permissionPrompt)

        model.requestPermission()
        let settled = expectation(description: "hop back to the main actor")
        Task { @MainActor in settled.fulfill() }
        wait(for: [settled], timeout: 1)
        XCTAssertEqual(model.phase, .starting)
    }

    func testARefusedPromptLandsOnTheDeniedCard() {
        let model = model(status: .notDetermined, grant: false)
        model.resolve()
        model.requestPermission()
        let settled = expectation(description: "hop back to the main actor")
        Task { @MainActor in settled.fulfill() }
        wait(for: [settled], timeout: 1)
        XCTAssertEqual(model.phase, .denied)
    }

    func testAuthorizedGoesStraightToStarting() {
        let model = model()
        model.resolve()
        XCTAssertEqual(model.phase, .starting)
    }

    func testBodyTrackingReportsNoSessionSoItIsLiveAtOnce() {
        let model = model(requirement: .bodyTracking)
        model.resolve()
        XCTAssertEqual(model.phase, .live)
    }

    func testReturningFromSettingsWithTheSwitchOnStartsTheCamera() {
        var status: AVAuthorizationStatus = .denied
        let model = ARExperienceModel(
            requirement: .worldTracking,
            isSupported: true,
            authorizationStatus: { status },
            requestAccess: { $0(false) }
        )
        model.resolve()
        XCTAssertEqual(model.phase, .denied)
        status = .authorized
        model.sceneBecameActive()
        XCTAssertEqual(model.phase, .starting)
    }

    func testRetryRekeysTheCameraViewAndStartsAgain() {
        let model = model()
        model.resolve()
        model.arSession(didEmit: .failed(URLError(.unknown)), in: ARView(frame: .zero))
        XCTAssertEqual(model.generation, 0)

        model.retry()

        XCTAssertEqual(model.generation, 1, "a new generation mounts a fresh camera view")
        XCTAssertEqual(model.phase, .starting)
    }

    func testAForcedPhaseIsNeverOverridden() {
        let model = ARExperienceModel(
            requirement: .worldTracking, isSupported: true,
            authorizationStatus: { .authorized }, requestAccess: { $0(true) },
            forcedPhase: .denied
        )
        model.resolve()
        model.sceneBecameActive()
        XCTAssertEqual(model.phase, .denied)
    }
}
