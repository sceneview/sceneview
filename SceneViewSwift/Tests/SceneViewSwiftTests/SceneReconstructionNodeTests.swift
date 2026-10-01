#if os(iOS)
import XCTest
import ARKit
import RealityKit
@testable import SceneViewSwift

/// `SceneReconstructionNode.enableReconstruction` used to run a brand-new
/// `ARWorldTrackingConfiguration` — dropping the host's frame semantics,
/// person segmentation, detection images and plane settings — and to switch
/// RealityKit's `.showSceneUnderstanding` debug wireframe on in the app's view.
/// These tests pin the amend-not-replace contract and the opt-in debug overlay.
@MainActor
final class SceneReconstructionNodeTests: XCTestCase {

    // MARK: - Amending the live configuration

    func testAmendKeepsEveryOtherWorldTrackingOption() throws {
        let current = ARWorldTrackingConfiguration()
        current.planeDetection = .vertical
        current.environmentTexturing = .none
        current.isAutoFocusEnabled = false
        current.isLightEstimationEnabled = false
        current.worldAlignment = .gravityAndHeading
        current.maximumNumberOfTrackedImages = 3
        current.wantsHDREnvironmentTextures = false
        current.isCollaborationEnabled = true

        let amended = try XCTUnwrap(
            SceneReconstructionNode.amend(current, with: .mesh)
        ).configuration

        XCTAssertEqual(amended.sceneReconstruction, .mesh)
        XCTAssertEqual(amended.planeDetection, .vertical)
        XCTAssertEqual(amended.environmentTexturing, .none)
        XCTAssertFalse(amended.isAutoFocusEnabled)
        XCTAssertFalse(amended.isLightEstimationEnabled)
        XCTAssertEqual(amended.worldAlignment, .gravityAndHeading)
        XCTAssertEqual(amended.maximumNumberOfTrackedImages, 3)
        XCTAssertFalse(amended.wantsHDREnvironmentTextures)
        XCTAssertTrue(amended.isCollaborationEnabled)
    }

    func testAmendKeepsFrameSemantics() throws {
        // Person segmentation needs an A12+ device; the Simulator may refuse
        // it, in which case `.sceneDepth` / empty still proves the copy.
        let semantics: ARConfiguration.FrameSemantics =
            ARWorldTrackingConfiguration.supportsFrameSemantics(.personSegmentationWithDepth)
                ? .personSegmentationWithDepth
                : []
        let current = ARWorldTrackingConfiguration()
        current.frameSemantics = semantics

        let amended = try XCTUnwrap(
            SceneReconstructionNode.amend(current, with: .meshWithClassification)
        ).configuration

        XCTAssertEqual(amended.frameSemantics, semantics)
        XCTAssertEqual(amended.sceneReconstruction, .meshWithClassification)
    }

    func testAmendKeepsDetectionImages() throws {
        let image = try XCTUnwrap(Self.referenceImage())
        let current = ARWorldTrackingConfiguration()
        current.detectionImages = [image]

        let amended = try XCTUnwrap(
            SceneReconstructionNode.amend(current, with: .mesh)
        ).configuration

        XCTAssertEqual(amended.detectionImages, [image])
    }

    func testAmendsTheRunningConfigurationInPlaceAndAsksForARun() throws {
        // Apple's pattern: mutate `session.configuration` and run it again.
        // Never a fresh object, never `copy()` — both lose the host's options.
        let current = ARWorldTrackingConfiguration()

        let amendment = try XCTUnwrap(SceneReconstructionNode.amend(current, with: .mesh))

        XCTAssertTrue(amendment.configuration === current)
        XCTAssertTrue(amendment.needsRun)
        XCTAssertEqual(current.sceneReconstruction, .mesh)
    }

    func testAlreadyEnabledNeedsNoRun() throws {
        let current = ARWorldTrackingConfiguration()
        current.sceneReconstruction = .mesh

        let amendment = try XCTUnwrap(SceneReconstructionNode.amend(current, with: .mesh))

        XCTAssertTrue(amendment.configuration === current)
        XCTAssertFalse(amendment.needsRun)
    }

    func testSwitchingToClassificationNeedsARun() throws {
        let current = ARWorldTrackingConfiguration()
        current.sceneReconstruction = .mesh

        let amendment = try XCTUnwrap(
            SceneReconstructionNode.amend(current, with: .meshWithClassification)
        )

        XCTAssertTrue(amendment.needsRun)
        XCTAssertEqual(current.sceneReconstruction, .meshWithClassification)
    }

    func testNonWorldTrackingSessionIsLeftAlone() {
        // A face- or body-tracking session must not be swapped for world
        // tracking behind the host's back (different camera, no world map).
        let face = ARFaceTrackingConfiguration()
        XCTAssertNil(SceneReconstructionNode.amend(face, with: .mesh))

        let orientation = AROrientationTrackingConfiguration()
        XCTAssertNil(SceneReconstructionNode.amend(orientation, with: .mesh))
    }

    func testNoRunningSessionGetsFreshDefaults() throws {
        let amendment = try XCTUnwrap(SceneReconstructionNode.amend(nil, with: .mesh))
        let fresh = amendment.configuration

        XCTAssertTrue(amendment.needsRun)

        XCTAssertEqual(fresh.sceneReconstruction, .mesh)
        XCTAssertEqual(fresh.planeDetection, [.horizontal, .vertical])
        XCTAssertEqual(fresh.environmentTexturing, .automatic)
    }

    // MARK: - enableReconstruction on a real ARView

    func testEnableNeverTurnsDebugOptionsOnByDefault() {
        let arView = ARView(frame: .zero)
        arView.debugOptions = []

        SceneReconstructionNode.enableReconstruction(in: arView)
        SceneReconstructionNode.enableReconstruction(in: arView, classification: true)

        XCTAssertFalse(arView.debugOptions.contains(.showSceneUnderstanding))
    }

    func testEnableWithoutLiDARChangesNothing() throws {
        try XCTSkipIf(
            SceneReconstructionNode.isSupported,
            "LiDAR device: the no-LiDAR path is not reachable here"
        )
        let arView = ARView(frame: .zero)
        arView.debugOptions = []

        let enabled = SceneReconstructionNode.enableReconstruction(
            in: arView,
            showDebugMeshOverlay: true
        )

        XCTAssertFalse(enabled)
        XCTAssertNil(arView.session.configuration, "no session may be started on a device without LiDAR")
        XCTAssertTrue(arView.debugOptions.isEmpty)
    }

    func testHideMeshVisualizationRemovesTheDebugOverlay() {
        let arView = ARView(frame: .zero)
        arView.debugOptions = [.showSceneUnderstanding, .showStatistics]

        SceneReconstructionNode.hideMeshVisualization(in: arView)

        XCTAssertEqual(arView.debugOptions, [.showStatistics])
    }

    // MARK: - Helpers

    private static func referenceImage() -> ARReferenceImage? {
        let size = CGSize(width: 8, height: 8)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { context in
            UIColor.black.setFill()
            context.fill(CGRect(origin: .zero, size: size))
        }
        guard let cgImage = image.cgImage else { return nil }
        let reference = ARReferenceImage(cgImage, orientation: .up, physicalWidth: 0.1)
        reference.name = "marker"
        return reference
    }
}

#endif // os(iOS)
