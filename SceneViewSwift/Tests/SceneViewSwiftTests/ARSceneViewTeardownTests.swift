#if os(iOS)
import XCTest
import ARKit
import RealityKit
@testable import SceneViewSwift

/// Regression guard for the AR teardown leaks fixed under audit #2402:
/// - #2407 — the translucent plane overlays leaked into `arView.scene` because
///   the coordinator had no teardown clearing `planeOverlays`.
/// - #2408 — the `ARSession` kept running and the dual light anchors were
///   orphaned because no `dismantleUIView(_:coordinator:)` existed.
///
/// Each test drives the **production** teardown entry point
/// `ARSceneView.dismantleUIView(_:coordinator:)` and asserts every anchor the
/// coordinator added to the AR scene is removed (`scene.anchors` empties) and
/// released (`weak` refs go `nil`), and that the session delegate is detached.
/// Mirrors the weak-ref reclamation pattern in `GestureSystemTests`
/// (`testEntityWithHandlerIsDeallocatedWhenReleased`).
///
/// `ARPlaneAnchor` has no public initializer, so the live `session(_:didAdd:)`
/// detection path cannot be exercised headlessly — the overlay is provisioned
/// directly via `PlaneVisualizer(anchor:)`, which is the same object the
/// detection path stores in `planeOverlays`.
@MainActor
final class ARSceneViewTeardownTests: XCTestCase {

    /// Builds a coordinator wired to a fresh `ARView`, exactly as `makeUIView`
    /// does (session delegate + `arView` back-reference).
    private func makeWiredCoordinator() -> (ARView, ARSceneView.Coordinator) {
        let arView = ARView(frame: .zero)
        let coordinator = ARSceneView.Coordinator(
            onTapOnPlane: nil,
            planeDetection: .horizontal
        )
        coordinator.arView = arView
        arView.session.delegate = coordinator
        return (arView, coordinator)
    }

    /// #2408 — the dual light anchors and the running session must not outlive
    /// the view. `dismantleUIView` removes the anchors from the scene, nils the
    /// cached refs, pauses the session and detaches the delegate; the anchor
    /// entities then deallocate once the local strong refs leave scope.
    func testDismantleReleasesLightAnchors() {
        weak var weakMain: AnchorEntity?
        weak var weakFill: AnchorEntity?
        let (arView, coordinator) = makeWiredCoordinator()

        autoreleasepool {
            let main = AnchorEntity(world: .zero)
            let fill = AnchorEntity(world: .zero)
            weakMain = main
            weakFill = fill
            arView.scene.addAnchor(main)
            arView.scene.addAnchor(fill)
            coordinator.mainLightAnchor = main
            coordinator.fillLightAnchor = fill
            XCTAssertEqual(arView.scene.anchors.count, 2)

            ARSceneView.dismantleUIView(arView, coordinator: coordinator)

            XCTAssertNil(coordinator.mainLightAnchor)
            XCTAssertNil(coordinator.fillLightAnchor)
            XCTAssertNil(
                arView.session.delegate,
                "dismantleUIView must detach the session delegate (#2408)"
            )
            XCTAssertTrue(
                arView.scene.anchors.isEmpty,
                "light anchors must be removed from the AR scene on teardown (#2408)"
            )
        }

        XCTAssertNil(weakMain, "main light anchor leaked past teardown (#2408)")
        XCTAssertNil(weakFill, "fill light anchor leaked past teardown (#2408)")
    }

    /// #2407 — translucent plane overlays must not stay parented in the AR
    /// scene after the view is dismantled.
    func testDismantleReleasesPlaneOverlays() {
        weak var weakOverlayAnchor: AnchorEntity?
        let (arView, coordinator) = makeWiredCoordinator()

        autoreleasepool {
            let overlayAnchor = AnchorEntity(world: .zero)
            weakOverlayAnchor = overlayAnchor
            let visualizer = PlaneVisualizer(anchor: overlayAnchor)
            coordinator.planeOverlays[UUID()] = visualizer
            arView.scene.addAnchor(visualizer.anchor)
            XCTAssertEqual(arView.scene.anchors.count, 1)

            ARSceneView.dismantleUIView(arView, coordinator: coordinator)

            XCTAssertTrue(
                coordinator.planeOverlays.isEmpty,
                "planeOverlays must be cleared on teardown (#2407)"
            )
            XCTAssertTrue(
                arView.scene.anchors.isEmpty,
                "plane overlay anchor must be removed from the AR scene (#2407)"
            )
        }

        XCTAssertNil(
            weakOverlayAnchor,
            "plane overlay anchor leaked past teardown (#2407)"
        )
    }

    /// Teardown must be idempotent — `dismantleUIView` runs first, then the
    /// coordinator's `deinit` runs the same release a second time. A second
    /// pass over already-empty collections / `nil` refs must not crash or
    /// double-free.
    func testDismantleIsIdempotent() {
        let (arView, coordinator) = makeWiredCoordinator()
        let main = AnchorEntity(world: .zero)
        let overlay = PlaneVisualizer(anchor: AnchorEntity(world: .zero))
        arView.scene.addAnchor(main)
        arView.scene.addAnchor(overlay.anchor)
        coordinator.mainLightAnchor = main
        coordinator.planeOverlays[UUID()] = overlay

        ARSceneView.dismantleUIView(arView, coordinator: coordinator)
        // Second call must be a safe no-op (mirrors deinit running after it).
        ARSceneView.dismantleUIView(arView, coordinator: coordinator)

        XCTAssertNil(coordinator.mainLightAnchor)
        XCTAssertTrue(coordinator.planeOverlays.isEmpty)
        XCTAssertTrue(arView.scene.anchors.isEmpty)
    }
}
#endif // os(iOS)
