#if os(iOS)
import XCTest
import ARKit
import RealityKit
import UIKit
@testable import SceneViewSwift

/// Run on an iOS Simulator. Decision races are independent of a live AR session;
/// entity assertions exercise RealityKit's real bounds, hierarchy and components.
@MainActor
final class ARPlacementControllerTests: XCTestCase {
    func testWallContactFacesCameraForBothSignsOfNormalWithoutFloor() {
        let point = SIMD3<Float>(0.4, 1.7, -2)
        let normals: [SIMD3<Float>] = [[0, 0, 1], [0, 0, -1], simd_normalize([1, 0.08, 1])]
        for normal in normals {
            for sign in [Float(-1), 1] {
                let towardCamera = normal * sign
                let transform = ARPlacementController.wallContactTransform(point: point, normal: normal,
                                                                           towardCamera: towardCamera)
                let front = SIMD3<Float>(transform.columns.2.x, transform.columns.2.y, transform.columns.2.z)
                let up = SIMD3<Float>(transform.columns.1.x, transform.columns.1.y, transform.columns.1.z)
                XCTAssertEqual(simd_distance(front, towardCamera), 0, accuracy: 0.00001)
                XCTAssertGreaterThan(up.y, 0.99)
                XCTAssertEqual(simd_dot(up, front), 0, accuracy: 0.00001)
                XCTAssertEqual(transform.columns.3, SIMD4<Float>(point, 1))
            }
        }
    }

    func testWallBackStaysInContactThroughTwistScaleAndMovement() throws {
        let (model, leaf) = nestedModel()
        let wrapper = try XCTUnwrap(ARPlacementController.prepare(model, alignment: .vertical, previewSize: 0.3))
        XCTAssertTrue(leaf.components.has(CollisionComponent.self))
        XCTAssertNil(leaf.components[GroundingShadowComponent.self], "Downward grounding is not wall shading")
        let bounds = wrapper.visualBounds(relativeTo: wrapper)
        let point = SIMD3<Float>(1, 1.6, -2)
        let normals: [SIMD3<Float>] = [[0, 0, 1], [-1, 0, 0], simd_normalize([1, 0.1, -1])]
        for normal in normals {
            let contact = ARPlacementController.wallContactTransform(point: point, normal: normal, towardCamera: normal)
            let delta = ARPlacementController.tangentOffset([0.2, -0.1, 0.3], normal: normal)
            XCTAssertEqual(simd_dot(delta, normal), 0, accuracy: 0.00001)
            for angle in [Float(-2.3), 0, 0.6, .pi] {
                for scale in [Float(0.25), 1, 4] {
                    let orientation = simd_quatf(contact) * simd_quatf(angle: angle, axis: [0, 0, 1])
                    for x in [bounds.min.x, bounds.max.x] {
                        for y in [bounds.min.y, bounds.max.y] {
                            let back = point + delta + orientation.act(SIMD3<Float>(x, y, bounds.min.z) * scale)
                            let front = point + delta + orientation.act(SIMD3<Float>(x, y, bounds.max.z) * scale)
                            XCTAssertEqual(simd_dot(back - point, normal), 0, accuracy: 0.00001)
                            XCTAssertGreaterThan(simd_dot(front - point, normal), 0)
                        }
                    }
                    XCTAssertEqual(orientation.act(.zero), .zero)
                }
            }
        }
    }

    func testVerticalControllerPreparesTVAndArmsExactlyOnePlacementWithoutFloor() {
        let controller = ARPlacementController(alignment: .vertical)
        let ticket = controller.selectModel()
        let (model, _) = nestedModel()
        XCTAssertTrue(controller.setModel(model, ticket: ticket, previewSize: 0.3))
        XCTAssertEqual(controller.alignment, .vertical)
        // Anchoring still requires live ARKit; the decision state requires no floor fact.
        var state = armedLifecycle()
        XCTAssertTrue(state.frame(now: 0, tracking: true, anchorTracking: false, assetReady: true))
        state.commit()
        for tick in 1...30 {
            XCTAssertFalse(state.frame(now: Double(tick), tracking: true, anchorTracking: true, assetReady: true))
        }
        XCTAssertTrue(state.hasPlacement)
        XCTAssertFalse(state.requested)
        _ = state.frame(now: 31, tracking: false, anchorTracking: false, assetReady: true)
        XCTAssertEqual(state.phase, .trackingLost)
        _ = state.frame(now: 32, tracking: true, anchorTracking: false, assetReady: true)
        XCTAssertEqual(state.phase, .recovering)
        XCTAssertFalse(state.requested)
    }

    private func armedLifecycle() -> ARPlacementLifecycle {
        var state = ARPlacementLifecycle()
        _ = state.select()
        state.requested = true
        return state
    }

    func testReadyFramesDoNotReportSuccessUntilAnchorCommit() {
        var state = armedLifecycle()
        for _ in 0..<3 {
            XCTAssertTrue(state.frame(now: 1, tracking: true, anchorTracking: false, assetReady: true))
            XCTAssertFalse(state.hasPlacement)
            XCTAssertTrue(state.requested)
            XCTAssertEqual(state.phase, .scanning)
        }
        state.commit()
        state.commit()
        XCTAssertTrue(state.hasPlacement)
        XCTAssertFalse(state.requested)
        XCTAssertEqual(state.phase, .placed)
        for time in 2...5 {
            XCTAssertFalse(state.frame(now: Double(time), tracking: true, anchorTracking: true, assetReady: true))
        }
    }

    /// A slow device needs several frames before RealityKit resolves `AnchorEntity(anchor:)`.
    /// Those frames must not each create and discard an anchor: one request, one anchor, one commit.
    func testUnresolvedAnchorIsKeptAcrossFramesAndCommitsExactlyOnce() {
        var state = armedLifecycle()
        var anchorsCreated = 0
        var commits = 0
        var pendingSince: TimeInterval?
        let resolvesAtStep = 24

        for step in 0..<60 {
            let now = 100 + Double(step) / 60
            let searching = state.frame(now: now,
                                        tracking: true,
                                        anchorTracking: state.hasPlacement,
                                        assetReady: true)
            guard searching else { continue }
            guard let since = pendingSince else {
                anchorsCreated += 1
                pendingSince = now
                continue
            }
            switch ARPlacementLifecycle.pendingAnchorDecision(anchored: step >= resolvesAtStep,
                                                              surfaceUsable: true,
                                                              now: now,
                                                              since: since) {
            case .commit:
                state.commit()
                commits += 1
                pendingSince = nil
            case .wait:
                break
            case .reject:
                pendingSince = nil
            }
        }

        XCTAssertEqual(anchorsCreated, 1)
        XCTAssertEqual(commits, 1)
        XCTAssertTrue(state.hasPlacement)
        XCTAssertFalse(state.requested)
        XCTAssertEqual(state.phase, .placed)
    }

    func testPendingAnchorIsRejectedOnlyWhenSurfaceDiesOrBudgetExpires() {
        // Still resolving, surface still usable, inside the budget: keep it.
        XCTAssertEqual(ARPlacementLifecycle.pendingAnchorDecision(anchored: false, surfaceUsable: true,
                                                                  now: 100.5, since: 100), .wait)
        // The surface stopped being usable: reject immediately, whatever the clock says.
        XCTAssertEqual(ARPlacementLifecycle.pendingAnchorDecision(anchored: false, surfaceUsable: false,
                                                                  now: 100.5, since: 100), .reject)
        XCTAssertEqual(ARPlacementLifecycle.pendingAnchorDecision(anchored: true, surfaceUsable: false,
                                                                  now: 100, since: 100), .reject)
        // Resolved on a usable surface: commit.
        XCTAssertEqual(ARPlacementLifecycle.pendingAnchorDecision(anchored: true, surfaceUsable: true,
                                                                  now: 100, since: 100), .commit)
        // Never resolved within the documented budget: reject and offer another candidate.
        let expired = 100 + ARPlacementLifecycle.pendingAnchorTimeout
        XCTAssertEqual(ARPlacementLifecycle.pendingAnchorDecision(anchored: false, surfaceUsable: true,
                                                                  now: expired, since: 100), .reject)
        XCTAssertEqual(ARPlacementLifecycle.pendingAnchorDecision(anchored: false, surfaceUsable: true,
                                                                  now: expired - 0.001, since: 100), .wait)
    }

    func testDelayedAssetDoesNotConsumeRequestOrStartSearchDeadline() {
        var state = armedLifecycle()
        XCTAssertFalse(state.frame(now: 1, tracking: true, anchorTracking: false, assetReady: false))
        XCTAssertFalse(state.frame(now: 30, tracking: true, anchorTracking: false, assetReady: false))
        XCTAssertNil(state.searchSince)
        XCTAssertFalse(state.hasPlacement)
        XCTAssertTrue(state.frame(now: 31, tracking: true, anchorTracking: false, assetReady: true))
        XCTAssertEqual(state.phase, .scanning)
        XCTAssertTrue(state.frame(now: 41, tracking: true, anchorTracking: false, assetReady: true))
        XCTAssertEqual(state.phase, .noSurface)
        // The help deadline never stops detection while the card is visible.
        state.commit()
        XCTAssertEqual(state.phase, .placed)
    }

    func testObsoleteSelectionAndDismissedSessionRejectAssetResults() {
        var state = ARPlacementLifecycle()
        let first = state.select()
        let second = state.select()
        XCTAssertFalse(state.accepts(first))
        XCTAssertTrue(state.accepts(second))
        state.dismiss()
        XCTAssertFalse(state.accepts(second))
        XCTAssertFalse(state.frame(now: 100, tracking: true, anchorTracking: true, assetReady: true))
        state.reset()
        state.commit()
        XCTAssertFalse(state.hasPlacement)
        let reopened = state.select()
        XCTAssertNotEqual(reopened.session, second.session)
        XCTAssertTrue(state.accepts(reopened))
        XCTAssertFalse(state.accepts(second))
    }

    func testResetArmsExactlyOneNewRequestAndKeepsSelectedAssetTicket() {
        var state = armedLifecycle()
        let selected = state.ticket
        state.commit()
        state.reset()
        XCTAssertFalse(state.hasPlacement)
        XCTAssertTrue(state.requested)
        XCTAssertEqual(state.ticket, selected)
        XCTAssertTrue(state.frame(now: 2, tracking: true, anchorTracking: false, assetReady: true))
        state.commit()
        XCTAssertFalse(state.frame(now: 3, tracking: true, anchorTracking: true, assetReady: true))
        XCTAssertEqual(state.phase, .placed)
    }

    func testTrackingLossDuringGestureKeepsPlacementAndRequiresAnchorRecovery() {
        var state = armedLifecycle()
        state.commit()
        state.phase = .adjusting
        XCTAssertFalse(state.frame(now: 1, tracking: false, anchorTracking: false, assetReady: true))
        XCTAssertEqual(state.phase, .trackingLost)
        XCTAssertTrue(state.hasPlacement)
        XCTAssertFalse(state.requested)
        XCTAssertFalse(state.frame(now: 2, tracking: true, anchorTracking: false, assetReady: true))
        XCTAssertEqual(state.phase, .recovering)
        XCTAssertFalse(state.frame(now: 12, tracking: true, anchorTracking: false, assetReady: true))
        XCTAssertEqual(state.phase, .recoveryFailed)
        XCTAssertFalse(state.frame(now: 13, tracking: true, anchorTracking: true, assetReady: true))
        XCTAssertEqual(state.phase, .placed)
        XCTAssertNil(state.recoverySince)
        XCTAssertFalse(state.requested)
    }

    func testSelectingReplacementDoesNotArmSecondPlacementDuringRecovery() {
        var state = armedLifecycle()
        state.commit()
        _ = state.frame(now: 1, tracking: false, anchorTracking: false, assetReady: true)
        _ = state.select()
        XCTAssertTrue(state.hasPlacement)
        XCTAssertFalse(state.requested)
        XCTAssertFalse(state.frame(now: 2, tracking: true, anchorTracking: false, assetReady: false))
        XCTAssertEqual(state.phase, .recovering)
    }

    func testCameraFailureCannotBeOverriddenByLaterFrames() {
        var state = armedLifecycle()
        state.phase = .cameraError
        XCTAssertFalse(state.frame(now: 1, tracking: true, anchorTracking: true, assetReady: true))
        state.commit()
        XCTAssertEqual(state.phase, .cameraError)
        XCTAssertFalse(state.hasPlacement)
    }

    func testUntrackedStartupFramesAreNotATrackingLoss() {
        var state = armedLifecycle()
        for tick in 0..<30 {
            XCTAssertFalse(state.frame(now: Double(tick) / 30, tracking: false, anchorTracking: false, assetReady: true))
            XCTAssertEqual(state.phase, .initializing)
        }
        XCTAssertTrue(state.frame(now: 1, tracking: true, anchorTracking: false, assetReady: true))
        XCTAssertEqual(state.phase, .scanning)
        // A real loss after the first tracked frame is still reported.
        XCTAssertFalse(state.frame(now: 2, tracking: false, anchorTracking: false, assetReady: true))
        XCTAssertEqual(state.phase, .trackingLost)
    }

    func testSessionRestartRewindsToInitializingAndArmsOnePlacement() {
        var state = armedLifecycle()
        _ = state.frame(now: 0, tracking: true, anchorTracking: false, assetReady: true)
        state.commit()
        XCTAssertEqual(state.phase, .placed)
        state.restartSession()
        XCTAssertEqual(state.phase, .initializing)
        XCTAssertFalse(state.hasPlacement)
        XCTAssertTrue(state.requested)
        // The restarted session's untracked frames are start-up, not a loss.
        _ = state.frame(now: 1, tracking: false, anchorTracking: false, assetReady: true)
        XCTAssertEqual(state.phase, .initializing)
    }

    func testPolygonRejectsConcaveCutoutInsteadOfUsingRectangularExtent() {
        let polygon: [SIMD2<Float>] = [[0, 0], [2, 0], [2, 1], [1, 1], [1, 2], [0, 2]]
        XCTAssertTrue(ARPlacementController.contains([0.5, 1.5], polygon: polygon))
        XCTAssertTrue(ARPlacementController.contains([1, 1.5], polygon: polygon))
        XCTAssertFalse(ARPlacementController.contains([1.5, 1.5], polygon: polygon))
        XCTAssertFalse(ARPlacementController.contains([3, 0], polygon: polygon))
        XCTAssertFalse(ARPlacementController.contains([.nan, 0], polygon: polygon))
        XCTAssertFalse(ARPlacementController.contains([0, 0], polygon: []))
        XCTAssertFalse(ARPlacementController.contains([0, 0], polygon: [[0, 0], [1, 0]]))
        XCTAssertFalse(ARPlacementController.contains([1, 0], polygon: [[0, 0], [1, 0], [2, 0]]))
    }

    private func nestedModel() -> (Entity, ModelEntity) {
        let root = Entity()
        let group = Entity()
        group.position = [1, 2, 3]
        let mesh = ModelEntity(mesh: .generateBox(size: 2), materials: [SimpleMaterial()])
        mesh.position = [0.5, -0.25, 0.75]
        group.addChild(mesh)
        root.addChild(group)
        return (root, mesh)
    }

    func testNestedPreviewUsesLongestDimensionAndGroundsBaseAtPivot() throws {
        let (root, mesh) = nestedModel()
        let wrapper = try XCTUnwrap(ARPlacementController.prepare(root, alignment: .horizontal, previewSize: 0.3))
        let bounds = wrapper.visualBounds(relativeTo: wrapper)
        XCTAssertEqual(max(bounds.extents.x, max(bounds.extents.y, bounds.extents.z)), 0.3, accuracy: 0.0001)
        XCTAssertEqual(bounds.min.y, 0, accuracy: 0.0001)
        XCTAssertEqual(bounds.center.x, 0, accuracy: 0.0001)
        XCTAssertEqual(bounds.center.z, 0, accuracy: 0.0001)
        XCTAssertTrue(mesh.components.has(CollisionComponent.self))
        XCTAssertEqual(mesh.components[GroundingShadowComponent.self]?.castsShadow, true)
    }

    func testActualSizePreservesAuthoredDimensionsAndWallContact() throws {
        let (root, _) = nestedModel()
        let wrapper = try XCTUnwrap(ARPlacementController.prepare(root, alignment: .vertical, previewSize: nil))
        let bounds = wrapper.visualBounds(relativeTo: wrapper)
        XCTAssertEqual(bounds.extents.x, 2, accuracy: 0.0001)
        XCTAssertEqual(bounds.extents.y, 2, accuracy: 0.0001)
        XCTAssertEqual(bounds.extents.z, 2, accuracy: 0.0001)
        XCTAssertEqual(bounds.min.z, 0, accuracy: 0.0001)
        XCTAssertEqual(bounds.center.x, 0, accuracy: 0.0001)
        XCTAssertEqual(bounds.min.y, 0, accuracy: 0.0001)
    }

    func testStaleOrInvalidReplacementKeepsPreviousHierarchy() {
        let controller = ARPlacementController()
        let (original, leaf) = nestedModel()
        let first = controller.selectModel()
        XCTAssertTrue(controller.setModel(original, ticket: first))
        XCTAssertTrue(controller.owns(leaf))
        let current = controller.selectModel()
        let (obsolete, _) = nestedModel()
        XCTAssertFalse(controller.setModel(obsolete, ticket: first))
        XCTAssertNil(obsolete.parent)
        XCTAssertFalse(controller.setModel(Entity(), ticket: current))
        XCTAssertTrue(controller.owns(leaf))
        let (replacement, newLeaf) = nestedModel()
        XCTAssertTrue(controller.setModel(replacement, ticket: current))
        XCTAssertFalse(controller.owns(leaf))
        XCTAssertTrue(controller.owns(newLeaf))
        XCTAssertFalse(controller.owns(Entity()))
        XCTAssertFalse(controller.owns(nil))
    }

    func testInvalidPreviewSizeIsRejectedWithoutReparentingAsset() {
        let invalidSizes: [Float] = [0, -1, .infinity, .nan]
        for size in invalidSizes {
            let (root, _) = nestedModel()
            XCTAssertNil(ARPlacementController.prepare(root, alignment: .horizontal, previewSize: size))
            XCTAssertNil(root.parent)
            XCTAssertEqual(root.scale, SIMD3<Float>(repeating: 1))
        }
    }

    func testOnlyPinchAndTwistMayRecognizeSimultaneously() {
        let controller = ARPlacementController()
        let pinch = UIPinchGestureRecognizer()
        let twist = UIRotationGestureRecognizer()
        let pan = UIPanGestureRecognizer()
        XCTAssertTrue(controller.gestureRecognizer(pinch, shouldRecognizeSimultaneouslyWith: twist))
        XCTAssertTrue(controller.gestureRecognizer(twist, shouldRecognizeSimultaneouslyWith: pinch))
        XCTAssertFalse(controller.gestureRecognizer(pan, shouldRecognizeSimultaneouslyWith: pinch))
        XCTAssertFalse(controller.gestureRecognizerShouldBegin(pinch), "unplaced content cannot be manipulated")
        controller.scale(to: 2)
        XCTAssertEqual(controller.scale, 1)
    }

    func testDismissCancelsLoadingAndReleasesOwnedModelAndRecognizers() {
        let controller = ARPlacementController()
        let view = ARView(frame: .zero)
        let existing = UITapGestureRecognizer()
        view.addGestureRecognizer(existing)
        let initialCount = view.gestureRecognizers?.count ?? 0
        controller.attach(to: view)
        controller.attach(to: view)
        XCTAssertEqual(view.gestureRecognizers?.count, initialCount + 4)
        let ticket = controller.selectModel()
        let (root, leaf) = nestedModel()
        XCTAssertTrue(controller.setModel(root, ticket: ticket))
        controller.dismiss()
        controller.dismiss()
        XCTAssertFalse(controller.acceptsAsset(ticket))
        XCTAssertFalse(controller.owns(leaf))
        XCTAssertNil(controller.result)
        XCTAssertFalse(controller.hasPlacement)
        XCTAssertEqual(view.gestureRecognizers?.count, initialCount)
        XCTAssertTrue(view.gestureRecognizers?.contains(existing) == true)
        XCTAssertFalse(controller.setModel(root, ticket: ticket))
    }
}
#endif
