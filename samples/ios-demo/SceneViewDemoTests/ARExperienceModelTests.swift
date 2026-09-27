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

    /// #3912 — a session that ran and never delivered a frame is a camera
    /// that did not start, not an unsupported device: the transient card,
    /// whose "Try again" reruns the camera.
    func testNoCameraFramesUsesTheTransientErrorCopy() {
        let model = model()
        model.resolve()
        model.arSession(didEmit: .failed(ARSceneViewError.noCameraFrames), in: ARView(frame: .zero))
        XCTAssertEqual(model.phase, .error("Camera couldn't start."))
    }

    // MARK: - Routing

    func testPlacementFeatureRoutesRejectUnsupportedDevicesBeforeCameraPermission() {
        let routes: [(String, ARExperienceRequirement)] = [
            ("ar-depth-occlusion", .lidar),
            ("ar-people-occlusion", .peopleOcclusion),
            ("ar-record-playback", .recording)
        ]
        for (id, requirement) in routes {
            XCTAssertEqual(ARExperienceRequirement.forScene(id: id), requirement)
            let model = model(requirement: requirement, isSupported: false, status: .notDetermined)
            model.resolve()
            XCTAssertEqual(model.phase, .unsupported(requirement))
        }
    }

    func testOcclusionTogglesRetainTheSameAnchorAndSubjectTransform() {
        let view = ARView(frame: .zero, cameraMode: .nonAR, automaticallyConfigureSession: false)
        let anchor = AnchorEntity(world: SIMD3<Float>(0.1, 0.2, -1))
        let subject = ModelEntity(mesh: .generateBox(size: 0.3))
        subject.scale = SIMD3<Float>(repeating: 1.7)
        subject.orientation = simd_quatf(angle: .pi / 4, axis: [0, 1, 0])
        anchor.addChild(subject)
        view.scene.addAnchor(anchor)
        let pose = anchor.transformMatrix(relativeTo: nil)
        let subjectTransform = subject.transform

        for effect: ARPlacementExperience.Occlusion in [.depth, .people] {
            let configuration = effect.configuration
            for enabled in [false, true, false, true] {
                effect.apply(enabled: enabled, to: view)
                XCTAssertTrue(view.scene.anchors.first === anchor)
                XCTAssertEqual(anchor.transformMatrix(relativeTo: nil), pose)
                XCTAssertEqual(subject.transform, subjectTransform)
                XCTAssertEqual(effect.configuration, configuration)
                switch effect {
                case .depth:
                    // RealityKit refuses to hold `.occlusion` on a renderer without scene
                    // reconstruction — the simulator — so the "on" direction is only asserted
                    // where the option can exist at all. The "off" direction always holds.
                    if ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh) || !enabled {
                        XCTAssertEqual(
                            view.environment.sceneUnderstanding.options.contains(.occlusion), enabled
                        )
                    }
                case .people:
                    XCTAssertEqual(view.renderOptions.contains(.disablePersonOcclusion), !enabled)
                }
            }
        }
    }

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

/// Automatic placement raises `hasPlacement` and `selection` in the same update. One AR event
/// must produce one vibration: the placement haptic, never that one plus the selection one.
final class PlacementFeedbackTests: XCTestCase {

    func testAutomaticPlacementPlaysOnlyThePlacementHaptic() {
        let before = PlacementFeedback(placed: false, selected: false)
        let after = PlacementFeedback(placed: true, selected: true)
        XCTAssertEqual(PlacementFeedback.haptic(from: before, to: after), .placed)
    }

    func testExplicitTapOnAStandingObjectStillPlaysTheSelectionHaptic() {
        let deselected = PlacementFeedback(placed: true, selected: false)
        let tapped = PlacementFeedback(placed: true, selected: true)
        XCTAssertEqual(PlacementFeedback.haptic(from: deselected, to: tapped), .selected)
    }

    func testNoHapticWithoutATransition() {
        let placed = PlacementFeedback(placed: true, selected: true)
        XCTAssertEqual(PlacementFeedback.haptic(from: placed, to: placed), PlacementFeedback.Haptic.none)
        XCTAssertEqual(
            PlacementFeedback.haptic(from: placed, to: PlacementFeedback(placed: true, selected: false)),
            PlacementFeedback.Haptic.none
        )
    }

    // MARK: - Face accessories ring (#4014)

    /// The ring must sit in front of the face, not inside the head: ARKit's
    /// face anchor is at the centre of the head with +Z toward the camera, and
    /// the face surface is a few centimetres in front of it.
    func testFaceRingSitsInFrontOfTheFace() {
        let positions = ARAugmentedFacesDemo.ringPositions()
        XCTAssertEqual(positions.count, 8)
        for position in positions {
            XCTAssertGreaterThanOrEqual(position.z, 0.05, "a sphere is inside the head")
        }
    }

    /// And it must frame the face rather than cover it: every sphere clears a
    /// 9 cm by 11 cm face oval.
    func testFaceRingFramesTheFace() {
        for position in ARAugmentedFacesDemo.ringPositions() {
            let normalised = (position.x / 0.09) * (position.x / 0.09) + (position.y / 0.11) * (position.y / 0.11)
            XCTAssertGreaterThan(normalised, 1, "a sphere sits over the face at \(position)")
        }
        XCTAssertGreaterThanOrEqual(ARAugmentedFacesDemo.sphereRadius, 0.015)
    }
}
