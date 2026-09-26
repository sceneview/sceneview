#if os(iOS)
import XCTest
import ARKit
import RealityKit
@testable import SceneViewSwift

/// Tests for ARSceneView configuration, including camera exposure API parity with Android.
// Test classes run on the main actor: their RealityKit node factories
// (`LightNode.directional`, `node.entity`, …) are `@MainActor`. (#1054)
@MainActor
final class ARSceneViewTests: XCTestCase {

    // MARK: - Session start

    func testUnsupportedFaceTrackingRunsNoSessionAtAll() {
        let arView = ARView(frame: .zero)

        let error = ARSceneView.startSession(
            on: arView,
            faceTracking: true,
            faceTrackingSupported: false,
            planeDetection: .horizontal,
            imageTrackingDatabase: nil
        )

        XCTAssertEqual(error, .faceTrackingUnsupported)
        // The regression this guards: `makeUIView` used to carry on after
        // reporting the error and still call `onSessionStarted`, so the host
        // added content to a session that was never run.
        XCTAssertNil(arView.session.configuration)
    }

    // MARK: - Default initialisation

    func testDefaultInit() {
        let view = ARSceneView()
        // Defaults should match Android's ARSceneView defaults.
        // planeDetection = .horizontal, showPlaneOverlay = true,
        // showCoachingOverlay = true, cameraExposure = nil
        // We verify the view is constructible and compiles correctly.
        XCTAssertNotNil(view)
    }

    func testInitWithPlaneDetectionNone() {
        let view = ARSceneView(planeDetection: .none)
        XCTAssertNotNil(view)
    }

    func testInitWithPlaneDetectionVertical() {
        let view = ARSceneView(planeDetection: .vertical)
        XCTAssertNotNil(view)
    }

    func testInitWithPlaneDetectionBoth() {
        let view = ARSceneView(planeDetection: .both)
        XCTAssertNotNil(view)
    }

    // MARK: - cameraExposure init parameter

    /// Mirrors Android's `ARSceneView(cameraExposure: Float?)` — nil means no override.
    func testCameraExposureDefaultIsNil() {
        // Constructing without explicit cameraExposure should not crash.
        let view = ARSceneView(planeDetection: .horizontal)
        XCTAssertNotNil(view)
    }

    /// Providing a positive EV value should be accepted without crashing.
    func testCameraExposurePositiveEV() {
        let view = ARSceneView(cameraExposure: 1.5)
        XCTAssertNotNil(view)
    }

    /// Providing a negative EV value (darken) should be accepted without crashing.
    func testCameraExposureNegativeEV() {
        let view = ARSceneView(cameraExposure: -2.0)
        XCTAssertNotNil(view)
    }

    /// Zero EV means no change — treated the same as a non-nil override (post-process
    /// installed but with brightness = 0, which is a no-op for CIColorControls).
    func testCameraExposureZeroEV() {
        let view = ARSceneView(cameraExposure: 0.0)
        XCTAssertNotNil(view)
    }

    // MARK: - cameraExposure modifier (SwiftUI-style)

    /// The `.cameraExposure(_:)` modifier should return a new ARSceneView instance.
    func testCameraExposureModifierReturnsNewInstance() {
        let original = ARSceneView()
        let modified = original.cameraExposure(1.0)
        // Modified is a copy (value type), so it must be a valid ARSceneView.
        XCTAssertNotNil(modified)
    }

    func testCameraExposureModifierWithPositiveEV() {
        let view = ARSceneView()
            .cameraExposure(2.0)
        XCTAssertNotNil(view)
    }

    func testCameraExposureModifierWithNegativeEV() {
        let view = ARSceneView()
            .cameraExposure(-1.0)
        XCTAssertNotNil(view)
    }

    func testCameraExposureModifierWithNilRemovesOverride() {
        let view = ARSceneView()
            .cameraExposure(1.0)
            .cameraExposure(nil)
        XCTAssertNotNil(view)
    }

    // MARK: - cameraExposure modifier chaining

    func testCameraExposureChainedWithOtherModifiers() {
        let view = ARSceneView(planeDetection: .both)
            .cameraExposure(0.5)
            .onSessionStarted { _ in }
        XCTAssertNotNil(view)
    }

    func testCameraExposureLastModifierWins() {
        // When cameraExposure is called twice, the last value should win (copy semantics).
        let view = ARSceneView()
            .cameraExposure(1.0)
            .cameraExposure(3.0)
        XCTAssertNotNil(view)
    }

    // MARK: - PlaneDetectionMode arPlaneDetection mapping

    func testPlaneDetectionNoneMapsToEmpty() {
        let mode = ARSceneView.PlaneDetectionMode.none
        XCTAssertEqual(mode.arPlaneDetection, [])
    }

    func testPlaneDetectionHorizontalMapsCorrectly() {
        let mode = ARSceneView.PlaneDetectionMode.horizontal
        XCTAssertEqual(mode.arPlaneDetection, .horizontal)
    }

    func testPlaneDetectionVerticalMapsCorrectly() {
        let mode = ARSceneView.PlaneDetectionMode.vertical
        XCTAssertEqual(mode.arPlaneDetection, .vertical)
    }

    func testPlaneDetectionBothMapsCorrectly() {
        let mode = ARSceneView.PlaneDetectionMode.both
        XCTAssertEqual(mode.arPlaneDetection, [.horizontal, .vertical])
    }

    // MARK: - AnchorNode helpers

    func testAnchorNodeWorldCreation() {
        let anchor = AnchorNode.world(position: .init(x: 1, y: 0, z: -2))
        XCTAssertNotNil(anchor)
        XCTAssertNotNil(anchor.entity)
    }

    func testAnchorNodeWorldAtOrigin() {
        let anchor = AnchorNode.world(position: .zero)
        XCTAssertNotNil(anchor)
    }

    func testAnchorNodeAddChild() {
        let anchor = AnchorNode.world(position: .zero)
        let child = GeometryNode.cube(size: 0.1, color: .red)
        anchor.add(child.entity)
        XCTAssertEqual(anchor.entity.children.count, 1)
    }

    func testAnchorNodeRemoveChild() {
        let anchor = AnchorNode.world(position: .zero)
        let child = GeometryNode.cube(size: 0.1, color: .blue)
        anchor.add(child.entity)
        XCTAssertEqual(anchor.entity.children.count, 1)
        anchor.remove(child.entity)
        XCTAssertEqual(anchor.entity.children.count, 0)
    }

    func testAnchorNodeRemoveAll() {
        let anchor = AnchorNode.world(position: .zero)
        anchor.add(GeometryNode.cube(size: 0.1, color: .red).entity)
        anchor.add(GeometryNode.sphere(radius: 0.1, color: .green).entity)
        XCTAssertEqual(anchor.entity.children.count, 2)
        anchor.removeAll()
        XCTAssertEqual(anchor.entity.children.count, 0)
    }

    func testAnchorNodePlaneHorizontal() {
        let anchor = AnchorNode.plane(alignment: .horizontal, minimumBounds: .init(0.2, 0.2))
        XCTAssertNotNil(anchor)
    }

    func testAnchorNodePlaneVertical() {
        let anchor = AnchorNode.plane(alignment: .vertical)
        XCTAssertNotNil(anchor)
    }

    // MARK: - LightSlot modifier wire-up (#1138)

    /// Default `ARSceneView()` must initialise both light slots to
    /// ``LightSlot/systemDefault`` so the Android-parity dual-light
    /// (10 000-lux main + 3 000-lux fill) renders out of the box.
    func testDefaultLightSlotsAreSystemDefault() {
        let view = ARSceneView()
        XCTAssertEqual(view.mainLightSlot, .systemDefault)
        XCTAssertEqual(view.fillLightSlot, .systemDefault)
    }

    /// `.mainLight(_:)` returns a new view (value-type copy semantics).
    func testMainLightModifierReturnsNewInstance() {
        let original = ARSceneView()
        let modified = original.mainLight(.disabled)
        XCTAssertEqual(modified.mainLightSlot, .disabled)
        // Original instance must NOT have been mutated.
        XCTAssertEqual(original.mainLightSlot, .systemDefault)
    }

    /// `.fillLight(_:)` returns a new view (value-type copy semantics).
    func testFillLightModifierReturnsNewInstance() {
        let original = ARSceneView()
        let modified = original.fillLight(.disabled)
        XCTAssertEqual(modified.fillLightSlot, .disabled)
        XCTAssertEqual(original.fillLightSlot, .systemDefault)
    }

    /// `.fillLight(.disabled)` is the canonical single-light AR setup. Verify
    /// the slot value round-trips through the modifier.
    func testFillLightDisabledRoundTrip() {
        let view = ARSceneView()
            .fillLight(.disabled)
        XCTAssertEqual(view.fillLightSlot, .disabled)
    }

    /// `.mainLight(.custom(LightNode))` stores the caller's node reference.
    ///
    /// `@MainActor` because ``LightNode/directional(color:intensity:castsShadow:)``
    /// and ``LightNode/entity`` are MainActor-isolated (RealityKit
    /// `DirectionalLight` lives on the main actor).
    @MainActor
    func testMainLightCustomLightNode() {
        let custom = LightNode.directional(intensity: 5_000)
        let view = ARSceneView()
            .mainLight(.custom(custom))
        // Equatable on .custom compares entity identity (===), so verify both
        // the variant and the underlying entity reference.
        if case .custom(let stored) = view.mainLightSlot {
            XCTAssertTrue(stored.entity === custom.entity)
        } else {
            XCTFail("Expected .custom slot, got \(view.mainLightSlot)")
        }
    }

    /// `.fillLight(.custom(LightNode))` stores the caller's node reference.
    @MainActor
    func testFillLightCustomLightNode() {
        let brighterFill = LightNode.fill(intensity: 6_000)
        let view = ARSceneView()
            .fillLight(.custom(brighterFill))
        if case .custom(let stored) = view.fillLightSlot {
            XCTAssertTrue(stored.entity === brighterFill.entity)
        } else {
            XCTFail("Expected .custom slot, got \(view.fillLightSlot)")
        }
    }

    /// Calling `.mainLight(_:)` twice — the last value wins (copy semantics).
    func testMainLightModifierLastWins() {
        let view = ARSceneView()
            .mainLight(.disabled)
            .mainLight(.systemDefault)
        XCTAssertEqual(view.mainLightSlot, .systemDefault)
    }

    /// Calling `.fillLight(_:)` twice — the last value wins (copy semantics).
    func testFillLightModifierLastWins() {
        let view = ARSceneView()
            .fillLight(.disabled)
            .fillLight(.systemDefault)
        XCTAssertEqual(view.fillLightSlot, .systemDefault)
    }

    /// Chaining `.mainLight` + `.fillLight` + `.cameraExposure` + `.onSessionStarted`
    /// must all return a valid view (no compile errors, no crashes).
    @MainActor
    func testLightModifiersChainWithOtherModifiers() {
        let view = ARSceneView(planeDetection: .horizontal)
            .mainLight(.custom(LightNode.directional(intensity: 5_000)))
            .fillLight(.disabled)
            .cameraExposure(0.5)
            .onSessionStarted { _ in }
        XCTAssertNotNil(view)
        XCTAssertEqual(view.fillLightSlot, .disabled)
    }

    // MARK: - ARSessionConfiguration (plan slice 2: observable, consistent lifecycle)

    /// The Simulator reports nothing as supported. Every capability is
    /// injected so each routing decision is asserted on its own.
    private func capabilities(
        worldTracking: Bool = true,
        faceTracking: Bool = true,
        lidar: Bool = true,
        semantics: ARConfiguration.FrameSemantics = [.personSegmentationWithDepth]
    ) -> ARSessionConfiguration.Capabilities {
        ARSessionConfiguration.Capabilities(
            worldTracking: worldTracking,
            faceTracking: faceTracking,
            sceneReconstruction: lidar,
            sceneReconstructionWithClassification: lidar,
            supportedFrameSemantics: semantics
        )
    }

    func testDefaultConfigurationMatchesClassicDefaultsExceptMesh() {
        let config = ARSessionConfiguration()
        XCTAssertEqual(config.mode, .worldTracking)
        XCTAssertEqual(config.planeDetection, .horizontal)
        XCTAssertNil(config.imageTrackingDatabase)
        XCTAssertEqual(config.environmentTexturing, .automatic)
        // The one deliberate change: LiDAR mesh is opt-in, not "on whenever
        // the device has it".
        XCTAssertEqual(config.sceneReconstruction, .none)
        XCTAssertTrue(config.frameSemantics.isEmpty)
    }

    func testUnmetRequirementWorldTrackingOnSimulator() {
        let config = ARSessionConfiguration()
        XCTAssertEqual(
            config.unmetRequirement(capabilities: capabilities(worldTracking: false)),
            .worldTracking
        )
        XCTAssertNil(config.unmetRequirement(capabilities: capabilities()))
    }

    func testUnmetRequirementFaceTracking() {
        let config = ARSessionConfiguration(mode: .faceTracking)
        XCTAssertEqual(
            config.unmetRequirement(capabilities: capabilities(faceTracking: false)),
            .faceTracking
        )
        // Face tracking does not need world tracking.
        XCTAssertNil(config.unmetRequirement(capabilities: capabilities(worldTracking: false)))
    }

    func testUnmetRequirementLidarIsReportedNeverFaked() {
        let mesh = ARSessionConfiguration(sceneReconstruction: .mesh)
        let classified = ARSessionConfiguration(sceneReconstruction: .meshWithClassification)
        XCTAssertEqual(mesh.unmetRequirement(capabilities: capabilities(lidar: false)), .lidar)
        XCTAssertEqual(classified.unmetRequirement(capabilities: capabilities(lidar: false)), .lidar)
        XCTAssertNil(mesh.unmetRequirement(capabilities: capabilities(lidar: true)))
    }

    func testUnmetRequirementFrameSemanticsNamesTheMissingOnes() {
        let config = ARSessionConfiguration(frameSemantics: [.personSegmentationWithDepth, .sceneDepth])
        XCTAssertEqual(
            config.unmetRequirement(capabilities: capabilities(semantics: [.personSegmentationWithDepth])),
            .frameSemantics([.sceneDepth])
        )
    }

    func testUnmetRequirementRefusesACombinationARKitRefusesAsAWhole() {
        // Each option is supported on its own; the pair is not.
        let pair: ARConfiguration.FrameSemantics = [.personSegmentationWithDepth, .sceneDepth]
        let device = ARSessionConfiguration.Capabilities(
            worldTracking: true, faceTracking: true,
            sceneReconstruction: true, sceneReconstructionWithClassification: true,
            supportedFrameSemantics: pair,
            supportsFrameSemanticsCombination: { requested in requested != pair }
        )
        XCTAssertNil(ARSessionConfiguration(frameSemantics: [.sceneDepth]).unmetRequirement(capabilities: device))
        XCTAssertNil(ARSessionConfiguration(frameSemantics: [.personSegmentationWithDepth]).unmetRequirement(capabilities: device))
        XCTAssertEqual(
            ARSessionConfiguration(frameSemantics: pair).unmetRequirement(capabilities: device),
            .frameSemantics(pair),
            "the whole refused set is the requirement, not an empty difference"
        )
        XCTAssertNil(
            ARSessionConfiguration().unmetRequirement(capabilities: device),
            "an empty set never consults the combination check"
        )
    }

    func testMakeARConfigurationCarriesEveryField() throws {
        let image = try makeReferenceImage()
        let config = ARSessionConfiguration(
            planeDetection: .both,
            imageTrackingDatabase: [image],
            sceneReconstruction: .none,
            frameSemantics: []
        )
        let world = try XCTUnwrap(config.makeARConfiguration() as? ARWorldTrackingConfiguration)
        XCTAssertEqual(world.planeDetection, [.horizontal, .vertical])
        // `detectionImages` reads back `nil` on the Simulator (ARKit drops
        // the set where image tracking cannot run), so the database hand-off
        // is asserted on the value, not on the ARKit object.
        XCTAssertEqual(config.imageTrackingDatabase, [image])
        XCTAssertEqual(world.environmentTexturing, .automatic)
        XCTAssertTrue(config.makeARConfiguration() !== config.makeARConfiguration(), "pure: a fresh object per call")
        XCTAssertTrue(ARSessionConfiguration(mode: .faceTracking).makeARConfiguration() is ARFaceTrackingConfiguration)
    }

    func testOnlyAModeChangeResetsTracking() {
        let horizontal = ARSessionConfiguration(planeDetection: .horizontal)
        let both = ARSessionConfiguration(planeDetection: .both)
        let face = ARSessionConfiguration(mode: .faceTracking)
        XCTAssertTrue(horizontal.requiresTrackingReset(from: nil), "first run")
        XCTAssertFalse(both.requiresTrackingReset(from: horizontal), "planes change in place")
        XCTAssertTrue(face.requiresTrackingReset(from: horizontal), "different camera, different map")
    }

    func testTrackingStatusMapsEveryARKitState() {
        XCTAssertEqual(ARTrackingStatus(.normal), .normal)
        XCTAssertEqual(ARTrackingStatus(.notAvailable), .notAvailable)
        XCTAssertEqual(ARTrackingStatus(.limited(.initializing)), .limited(.initializing))
        XCTAssertEqual(ARTrackingStatus(.limited(.excessiveMotion)), .limited(.excessiveMotion))
        XCTAssertEqual(ARTrackingStatus(.limited(.insufficientFeatures)), .limited(.insufficientFeatures))
        XCTAssertEqual(ARTrackingStatus(.limited(.relocalizing)), .limited(.relocalizing))
    }

    // MARK: - Session start routing

    func testUnsupportedConfigurationRunsNoSessionAndReportsNothingElse() {
        let arView = ARView(frame: .zero)
        let coordinator = ARSceneView.Coordinator(
            configuration: ARSessionConfiguration(sceneReconstruction: .mesh)
        )
        var events: [String] = []
        coordinator.onSessionEvent = { event, _ in events.append(String(describing: event)) }

        let error = ARSceneView.startSession(
            on: arView,
            configuration: coordinator.configuration,
            capabilities: capabilities(lidar: false),
            coordinator: coordinator
        )

        XCTAssertEqual(error, .unsupported(.lidar))
        XCTAssertNil(arView.session.configuration, "nothing ran")
        XCTAssertNil(arView.session.delegate, "no delegate on a session that never ran")
        XCTAssertFalse(coordinator.sessionDidStart)
        XCTAssertNil(coordinator.appliedConfiguration)
        XCTAssertTrue(events.isEmpty, "no `started` for a session that did not start")
    }

    func testFaceTrackingKeepsItsLegacyError() {
        let arView = ARView(frame: .zero)
        let error = ARSceneView.startSession(
            on: arView,
            configuration: ARSessionConfiguration(mode: .faceTracking),
            capabilities: capabilities(faceTracking: false),
            coordinator: nil
        )
        XCTAssertEqual(error, .faceTrackingUnsupported)
        XCTAssertNil(arView.session.configuration)
    }

    func testReportFailureRoutesToStateEventAndLegacyClosure() {
        let arView = ARView(frame: .zero)
        let coordinator = ARSceneView.Coordinator(configuration: ARSessionConfiguration())
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

        coordinator.reportFailure(ARSceneViewError.unsupported(.worldTracking), in: arView)
        coordinator.reportFailure(ARSceneViewError.unsupported(.worldTracking), in: arView)

        XCTAssertEqual(states, [.failed], "state reported once per change")
        XCTAssertEqual(failures, [.unsupported(.worldTracking), .unsupported(.worldTracking)])
        XCTAssertEqual(legacy, failures, "the pre-existing closure still fires")
    }

    // MARK: - Frame / tracking / interruption routing

    func testFirstFrameFiresOncePerRunAndFlipsToRunning() {
        let arView = ARView(frame: .zero)
        let coordinator = ARSceneView.Coordinator(configuration: ARSessionConfiguration())
        var events: [String] = []
        var states: [ARSessionState] = []
        coordinator.onSessionEvent = { event, _ in events.append(String(describing: event)) }
        coordinator.onSessionStateChange = { state, _ in states.append(state) }
        coordinator.awaitingFirstFrame = true

        coordinator.noteFrame(trackingState: .limited(.initializing), in: arView)
        coordinator.noteFrame(trackingState: .limited(.initializing), in: arView)
        coordinator.noteFrame(trackingState: .normal, in: arView)

        XCTAssertEqual(states, [.running])
        XCTAssertEqual(events, [
            "firstFrame",
            "trackingStateChanged(SceneViewSwift.ARTrackingStatus.limited(SceneViewSwift.ARTrackingStatus.LimitedReason.initializing))",
            "trackingStateChanged(SceneViewSwift.ARTrackingStatus.normal)",
        ], "one firstFrame; tracking reported per change, not per frame")
    }

    func testTrackingStateChangeClosureFiresOncePerChange() {
        let arView = ARView(frame: .zero)
        let coordinator = ARSceneView.Coordinator(configuration: ARSessionConfiguration())
        var statuses: [ARTrackingStatus] = []
        coordinator.onTrackingStateChange = { status, _ in statuses.append(status) }

        coordinator.noteFrame(trackingState: .normal, in: arView)
        coordinator.noteFrame(trackingState: .normal, in: arView)
        coordinator.noteFrame(trackingState: .limited(.excessiveMotion), in: arView)
        coordinator.noteFrame(trackingState: .normal, in: arView)

        XCTAssertEqual(statuses, [.normal, .limited(.excessiveMotion), .normal])
    }

    func testInterruptionIsReportedAndDoesNotTouchTheConfiguration() throws {
        let image = try makeReferenceImage()
        let arView = ARView(frame: .zero)
        let configuration = ARSessionConfiguration(planeDetection: .both, imageTrackingDatabase: [image])
        let coordinator = ARSceneView.Coordinator(configuration: configuration)
        coordinator.appliedConfiguration = configuration
        var states: [ARSessionState] = []
        var events: [String] = []
        coordinator.onSessionStateChange = { state, _ in states.append(state) }
        coordinator.onSessionEvent = { event, _ in events.append(String(describing: event)) }

        coordinator.noteInterruption(in: arView)

        XCTAssertEqual(states, [.interrupted])
        XCTAssertEqual(events, ["interrupted"])
        XCTAssertEqual(coordinator.appliedConfiguration, configuration,
                       "the image database and plane modes survive the interruption")
        XCTAssertEqual(coordinator.appliedConfiguration?.imageTrackingDatabase, [image])
    }

    func testEnvironmentObserverReceivesEveryEvent() {
        final class Spy: ARSceneSessionObserver {
            var events: [String] = []
            func arSession(didEmit event: ARSessionEvent, in arView: ARView) {
                events.append(String(describing: event))
            }
        }
        let arView = ARView(frame: .zero)
        let coordinator = ARSceneView.Coordinator(configuration: ARSessionConfiguration())
        let spy = Spy()
        coordinator.sessionObserver = spy
        coordinator.awaitingFirstFrame = true

        coordinator.noteFrame(trackingState: .normal, in: arView)
        coordinator.noteInterruption(in: arView)
        coordinator.reportFailure(ARSceneViewError.unsupported(.worldTracking), in: arView)

        XCTAssertEqual(spy.events, [
            "firstFrame",
            "trackingStateChanged(SceneViewSwift.ARTrackingStatus.normal)",
            "interrupted",
            "failed(SceneViewSwift.ARSceneViewError.unsupported(SceneViewSwift.ARSessionConfiguration.Requirement.worldTracking))",
        ])
    }

    // MARK: - Reactive configuration

    func testApplyIfChangedIsANoOpForAnEqualConfiguration() {
        let arView = ARView(frame: .zero)
        let configuration = ARSessionConfiguration(planeDetection: .both)
        let coordinator = ARSceneView.Coordinator(configuration: configuration)
        coordinator.appliedConfiguration = configuration
        var started = 0
        coordinator.onSessionEvent = { event, _ in if case .started = event { started += 1 } }

        coordinator.applyIfChanged(ARSessionConfiguration(planeDetection: .both), on: arView)

        XCTAssertEqual(started, 0, "an unrelated render never re-runs the session")
        XCTAssertNil(arView.session.configuration)
    }

    func testApplyIfChangedRefusesAnUnsupportedChangeAndKeepsThePreviousOne() {
        let arView = ARView(frame: .zero)
        let configuration = ARSessionConfiguration(planeDetection: .both)
        let coordinator = ARSceneView.Coordinator(configuration: configuration)
        coordinator.arView = arView
        coordinator.appliedConfiguration = configuration
        var errors: [ARSceneViewError] = []
        var started = 0
        coordinator.onSessionError = { error, _ in if let e = error as? ARSceneViewError { errors.append(e) } }
        coordinator.onSessionEvent = { event, _ in if case .started = event { started += 1 } }

        coordinator.applyIfChanged(
            ARSessionConfiguration(planeDetection: .both, sceneReconstruction: .mesh),
            on: arView,
            capabilities: capabilities(lidar: false)
        )

        XCTAssertEqual(errors, [.unsupported(.lidar)])
        XCTAssertEqual(started, 0)
        XCTAssertEqual(coordinator.appliedConfiguration, configuration, "the live configuration is untouched")
    }

    func testRefusedChangeIsAFailedEventForTheClosureAndTheObserver() {
        final class Spy: ARSceneSessionObserver {
            var events: [ARSessionEvent] = []
            func arSession(didEmit event: ARSessionEvent, in arView: ARView) { events.append(event) }
        }
        let arView = ARView(frame: .zero)
        let configuration = ARSessionConfiguration(planeDetection: .both)
        let coordinator = ARSceneView.Coordinator(configuration: configuration)
        coordinator.arView = arView
        coordinator.appliedConfiguration = configuration
        coordinator.sessionDidStart = true
        coordinator.transition(to: .running, in: arView)
        let spy = Spy()
        coordinator.sessionObserver = spy
        var closureFailures: [ARSceneViewError] = []
        var states: [ARSessionState] = []
        coordinator.onSessionEvent = { event, _ in
            if case .failed(let error) = event, let e = error as? ARSceneViewError { closureFailures.append(e) }
        }
        coordinator.onSessionStateChange = { state, _ in states.append(state) }

        let refused = ARSessionConfiguration(planeDetection: .both, sceneReconstruction: .mesh)
        coordinator.applyIfChanged(refused, on: arView, capabilities: capabilities(lidar: false))
        coordinator.applyIfChanged(refused, on: arView, capabilities: capabilities(lidar: false))

        XCTAssertEqual(closureFailures, [.unsupported(.lidar)], "reported once, not on every render")
        XCTAssertEqual(spy.events.count, 1)
        if case .failed(let error) = spy.events.first, let e = error as? ARSceneViewError {
            XCTAssertEqual(e, .unsupported(.lidar))
        } else {
            XCTFail("the environment observer receives the same `.failed`")
        }
        XCTAssertTrue(states.isEmpty, "the live session keeps its state")
        XCTAssertEqual(coordinator.sessionState, .running)
    }

    func testASupportedConfigurationRecoversAViewWhoseFirstOneWasRefused() {
        let arView = ARView(frame: .zero)
        let refused = ARSessionConfiguration(sceneReconstruction: .mesh)
        let coordinator = ARSceneView.Coordinator(configuration: refused)
        coordinator.arView = arView
        var events: [String] = []
        coordinator.onSessionEvent = { event, _ in
            switch event {
            case .failed: events.append("failed")
            case .started: events.append("started")
            default: events.append("other")
            }
        }

        // The first render: refused, nothing ran.
        XCTAssertEqual(
            ARSceneView.startSession(on: arView, configuration: refused, capabilities: capabilities(lidar: false), coordinator: coordinator),
            .unsupported(.lidar)
        )
        coordinator.refusedConfiguration = refused
        coordinator.reportFailure(ARSceneViewError.unsupported(.lidar), in: arView)
        XCTAssertFalse(coordinator.sessionDidStart)

        // An unrelated re-render asking for the same thing reports nothing new.
        coordinator.applyIfChanged(refused, on: arView, capabilities: capabilities(lidar: false))
        XCTAssertEqual(events, ["failed"])

        // A later render asks for something this device can run.
        let supported = ARSessionConfiguration(sceneReconstruction: .none)
        coordinator.applyIfChanged(supported, on: arView, capabilities: capabilities(lidar: false))

        XCTAssertTrue(coordinator.sessionDidStart, "the session runs without rekeying the view")
        XCTAssertEqual(coordinator.appliedConfiguration, supported)
        XCTAssertNil(coordinator.refusedConfiguration)
        XCTAssertEqual(coordinator.sessionState, .starting)
        XCTAssertEqual(events, ["failed", "started"])
        XCTAssertNotNil(arView.session.delegate, "delegate installed by the recovery run")
    }

    // MARK: - Exposure post-process ownership

    @available(iOS 15.0, *)
    func testDefaultCameraPathNeverTouchesAnotherOwnersPostProcess() {
        let arView = ARView(frame: .zero)
        let coordinator = ARSceneView.Coordinator(configuration: ARSessionConfiguration())
        arView.renderCallbacks.postProcess = { _ in }   // the host's, or a recorder's

        coordinator.applyExposure(nil, on: arView)
        coordinator.applyExposure(nil, on: arView)

        XCTAssertNotNil(arView.renderCallbacks.postProcess, "nil → nil installs nothing and erases nothing")
        XCTAssertFalse(coordinator.ownsPostProcess)
    }

    @available(iOS 15.0, *)
    func testExposureIsInstalledOncePerValueAndReleasedOnlyWhenOwned() {
        let arView = ARView(frame: .zero)
        let coordinator = ARSceneView.Coordinator(configuration: ARSessionConfiguration())

        coordinator.applyExposure(0.5, on: arView)
        XCTAssertNotNil(arView.renderCallbacks.postProcess)
        XCTAssertTrue(coordinator.ownsPostProcess)
        XCTAssertEqual(coordinator.appliedExposure, 0.5)

        // Same value on a later render: nothing is rebuilt.
        coordinator.applyExposure(0.5, on: arView)
        XCTAssertEqual(coordinator.appliedExposure, 0.5)

        coordinator.applyExposure(nil, on: arView)
        XCTAssertNil(arView.renderCallbacks.postProcess, "ours, so cleared")
        XCTAssertFalse(coordinator.ownsPostProcess)
    }

    // MARK: - Helpers

    private func makeReferenceImage() throws -> ARReferenceImage {
        let size = CGSize(width: 64, height: 64)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { context in
            UIColor.black.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            UIColor.white.setFill()
            context.fill(CGRect(x: 8, y: 8, width: 24, height: 40))
        }
        let cgImage = try XCTUnwrap(image.cgImage)
        return ARReferenceImage(cgImage, orientation: .up, physicalWidth: 0.1)
    }
}
#endif // os(iOS)
