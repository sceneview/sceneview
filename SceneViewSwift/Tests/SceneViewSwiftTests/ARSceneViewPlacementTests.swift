#if os(iOS)
import XCTest
import ARKit
import RealityKit
@testable import SceneViewSwift

/// Regression guard for the #894 iOS placement quick-wins (the iOS half of the
/// #2241 Sprint-1 design, §3.A):
/// - grounding shadows — anchors placed via `onTapOnPlane` get
///   `GroundingShadowComponent(castsShadow: true)` on their model entities
///   (Android `ShadowReceiverPlane` analogue),
/// - placement reticle — the coordinator-owned reticle anchor must obey the
///   same lifecycle rules as the plane overlays (#2407) and light anchors
///   (#2408): removed when the flag turns off and on `dismantleUIView`.
///
/// A headless `ARView` never produces a raycast hit, so the reticle's
/// hit-driven path (entity creation, pose smoothing) is not testable here —
/// these tests provision the anchor directly, exactly like
/// `ARSceneViewTeardownTests` provisions overlays and lights.
@MainActor
final class ARSceneViewPlacementTests: XCTestCase {

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

    // MARK: - Grounding shadows (#894)

    /// Anchors added after the snapshot get the component on every descendant
    /// with a model; pre-existing anchors are left untouched.
    func testGroundingShadowAppliedOnlyToNewlyPlacedAnchors() {
        let (arView, coordinator) = makeWiredCoordinator()

        // Pre-existing content (e.g. a light anchor) — must NOT be touched.
        let existingAnchor = AnchorEntity(world: .zero)
        let existingModel = ModelEntity(
            mesh: .generateBox(size: 0.1), materials: [SimpleMaterial()]
        )
        existingAnchor.addChild(existingModel)
        arView.scene.addAnchor(existingAnchor)

        let before = Set(arView.scene.anchors.map(\.id))

        // "Placed" content — a nested hierarchy, model entity one level down,
        // mirroring the documented AnchorNode.world + add(child) flow.
        let placedAnchor = AnchorEntity(world: .zero)
        let group = Entity()
        let placedModel = ModelEntity(
            mesh: .generateBox(size: 0.1), materials: [SimpleMaterial()]
        )
        group.addChild(placedModel)
        placedAnchor.addChild(group)
        arView.scene.addAnchor(placedAnchor)

        coordinator.applyGroundingShadows(in: arView, addedSince: before)

        XCTAssertTrue(
            placedModel.components.has(GroundingShadowComponent.self),
            "placed model entities must receive the grounding shadow (#894)"
        )
        XCTAssertFalse(
            group.components.has(GroundingShadowComponent.self),
            "entities without a ModelComponent must not get the component"
        )
        XCTAssertFalse(
            existingModel.components.has(GroundingShadowComponent.self),
            "pre-existing anchors must not be mutated by a later placement"
        )
    }

    /// The recursive helper sets `castsShadow` — the value RealityKit needs to
    /// project a contact shadow onto detected surfaces.
    func testGroundingShadowCastsShadow() {
        let model = ModelEntity(
            mesh: .generateBox(size: 0.1), materials: [SimpleMaterial()]
        )
        ARSceneView.Coordinator.applyGroundingShadow(to: model)
        let component = model.components[GroundingShadowComponent.self]
        XCTAssertEqual(component?.castsShadow, true)
    }

    // MARK: - Placement reticle lifecycle (#894)

    /// Turning the flag off must remove the coordinator-owned reticle anchor
    /// from the scene on the next per-frame update.
    func testReticleRemovedWhenFlagTurnsOff() {
        let (arView, coordinator) = makeWiredCoordinator()
        let anchor = AnchorEntity(world: .zero)
        arView.scene.addAnchor(anchor)
        coordinator.reticleAnchor = anchor
        coordinator.showPlacementReticle = false

        coordinator.updatePlacementReticle(in: arView)

        XCTAssertNil(coordinator.reticleAnchor)
        XCTAssertTrue(
            arView.scene.anchors.isEmpty,
            "reticle anchor must leave the scene when the flag turns off (#894)"
        )
    }

    /// With the flag on but no raycast hit (always the case headlessly), the
    /// reticle must stay hidden and no anchor may be created.
    func testReticleHiddenOnRaycastMiss() {
        let (arView, coordinator) = makeWiredCoordinator()
        coordinator.showPlacementReticle = true

        coordinator.updatePlacementReticle(in: arView)

        XCTAssertNil(
            coordinator.reticleAnchor,
            "no reticle entity may be provisioned while the ray misses (#894)"
        )
        XCTAssertTrue(arView.scene.anchors.isEmpty)
    }

    /// `dismantleUIView` must release the reticle anchor like every other
    /// coordinator-owned anchor (#2407/#2408 teardown contract).
    func testDismantleReleasesReticleAnchor() {
        weak var weakReticle: AnchorEntity?
        let (arView, coordinator) = makeWiredCoordinator()

        autoreleasepool {
            let reticle = AnchorEntity(world: .zero)
            weakReticle = reticle
            arView.scene.addAnchor(reticle)
            coordinator.reticleAnchor = reticle
            coordinator.showPlacementReticle = true
            XCTAssertEqual(arView.scene.anchors.count, 1)

            ARSceneView.dismantleUIView(arView, coordinator: coordinator)

            XCTAssertNil(coordinator.reticleAnchor)
            XCTAssertTrue(
                arView.scene.anchors.isEmpty,
                "reticle anchor must be removed from the AR scene on teardown (#894)"
            )
        }

        XCTAssertNil(weakReticle, "reticle anchor leaked past teardown (#894)")
    }
}
#endif // os(iOS)
