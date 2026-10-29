#if os(iOS) || os(macOS) || os(visionOS)
import XCTest
import RealityKit
@testable import SceneViewSwift

/// `ContactShadowNode` — the procedural contact shadow (Android `ContactShadowNode` parity):
/// the baked mask matches `contact_shadow.mat`, the presets match Android's
/// `ContactShadowContext`, settings round-trip through the entity, and a shadow fitted under a
/// model is sized from its bounds and rests at its lowest point.
@MainActor
final class ContactShadowNodeTests: XCTestCase {

    // MARK: - Presets and mask

    func testPresetsMatchAndroid() {
        XCTAssertEqual(ContactShadowContext.allCases, [.floor, .wall, .tableTop])
        XCTAssertEqual(ContactShadowContext.floor.intensity, 0.55)
        XCTAssertEqual(ContactShadowContext.wall.intensity, 0.38)
        XCTAssertEqual(ContactShadowContext.tableTop.intensity, 0.60)
        XCTAssertEqual(ContactShadowContext.wall.center, [0, -0.10])
        XCTAssertEqual(ContactShadowContext.wall.radius, [0.34, 0.20])
        XCTAssertEqual(ContactShadowContext.tableTop.radius, [0.26, 0.26])
        XCTAssertEqual(ContactShadowContext.floor.softness, 0.55)
        XCTAssertEqual(ContactShadowContext.wall.softness, 0.85)
        XCTAssertEqual(ContactShadowContext.tableTop.softness, 0.35)
    }

    func testMaskIsDarkInTheMiddleAndClearAtTheEdges() {
        for context in ContactShadowContext.allCases {
            let centre = ContactShadowMask.alpha(u: 0.5 + context.center.x, v: 0.5 + context.center.y,
                                                 context: context)
            XCTAssertEqual(centre, 1, accuracy: 1e-5, "\(context)")
            XCTAssertEqual(ContactShadowMask.alpha(u: 0, v: 0.5, context: context), 0, "\(context)")
            XCTAssertEqual(ContactShadowMask.alpha(u: 0.5, v: 1, context: context), 0, "\(context)")
            XCTAssertNotNil(ContactShadowMask.image(context, size: 16))
        }
        // The wall pool sits below the object's centre, where light from above would put it.
        let below = ContactShadowMask.alpha(u: 0.5, v: 0.25, context: .wall)
        let above = ContactShadowMask.alpha(u: 0.5, v: 0.75, context: .wall)
        XCTAssertGreaterThan(below, above)
        // The table-top patch is crisper than the floor pool: less left at the same distance.
        XCTAssertLessThan(ContactShadowMask.alpha(u: 0.5, v: 0.85, context: .tableTop),
                          ContactShadowMask.alpha(u: 0.5, v: 0.85, context: .floor))
    }

    func testMaskImageIsOneGreyChannel() throws {
        let image = try XCTUnwrap(ContactShadowMask.image(.floor, size: 32))
        XCTAssertEqual(image.width, 32)
        XCTAssertEqual(image.bitsPerPixel, 8, "an RGBA mask would be un-premultiplied into a hard cut-out")
        XCTAssertNil(ContactShadowMask.image(.floor, size: 0))
    }

    // MARK: - Node settings

    func testDefaultsAndRoundTrips() {
        let shadow = ContactShadowNode(width: 0.8, length: 0.5, context: .tableTop)
        XCTAssertEqual(shadow.context, .tableTop)
        XCTAssertEqual(shadow.intensity, ContactShadowContext.tableTop.intensity)
        XCTAssertEqual(shadow.size, [0.8, 0.5])
        XCTAssertEqual(shadow.plane, .horizontal)

        shadow.context(.wall).intensity(0.2).size(width: 2, length: 1).position([0, 1, -2])
        XCTAssertEqual(shadow.context, .wall)
        XCTAssertEqual(shadow.intensity, 0.2, accuracy: 1e-6, "changing the context keeps an explicit intensity")
        XCTAssertEqual(shadow.size, [2, 1])
        XCTAssertEqual(shadow.position, [0, 1, -2])
        XCTAssertEqual(shadow.entity.model?.materials.count, 1)
    }

    func testIntensityIsClampedToUnitRange() {
        let shadow = ContactShadowNode(intensity: 3)
        XCTAssertEqual(shadow.intensity, 1)
        shadow.intensity = -1
        XCTAssertEqual(shadow.intensity, 0)
        shadow.intensity = .nan
        XCTAssertEqual(shadow.intensity, 0)
    }

    func testVerticalQuadScalesAlongY() {
        let shadow = ContactShadowNode(width: 2.4, length: 1.6, context: .wall, plane: .vertical)
        XCTAssertEqual(shadow.entity.scale, [2.4, 1.6, 1])
        XCTAssertEqual(ContactShadowNode(shadow.entity)?.plane, .vertical)
        XCTAssertNil(ContactShadowNode(ModelEntity()), "only wraps entities it created")
    }

    // MARK: - Fitting under a model

    func testUnderRestsAtTheLowestPointAndSizesFromTheFootprint() {
        // 0.4 wide, 0.2 tall, 0.6 deep, centred at (1, 0.5, 0): lowest point y = 0.4.
        let model = ModelEntity(mesh: .generateBox(width: 0.4, height: 0.2, depth: 0.6))
        model.position = [1, 0.5, 0]
        let root = Entity()
        root.addChild(model)

        let shadow = ContactShadowNode.under(model, intensity: 0.4, spread: 2)

        XCTAssertTrue(shadow.entity.parent === model, "a child follows the model and fades with it")
        XCTAssertEqual(shadow.position.x, 0, accuracy: 1e-5)
        XCTAssertEqual(shadow.position.z, 0, accuracy: 1e-5)
        XCTAssertEqual(shadow.position.y, -0.1 + ContactShadowNode.surfaceOffset, accuracy: 1e-5)
        XCTAssertEqual(shadow.size.x, 0.8, accuracy: 1e-5)
        XCTAssertEqual(shadow.size.y, 1.2, accuracy: 1e-5)
        XCTAssertEqual(shadow.intensity, 0.4, accuracy: 1e-6)
    }

    func testOffsetIsMetresInWorldSpaceUnderAScaledModel() {
        let model = ModelEntity(mesh: .generateBox(size: 1))
        model.scale = [0.5, 0.5, 0.5]
        let shadow = ContactShadowNode.under(model)
        // Local -0.5 is the bottom; 5 mm in the world is 10 mm in the half-scaled model's space.
        XCTAssertEqual(shadow.position.y, -0.5 + 0.01, accuracy: 1e-5)
    }

    func testRefitIgnoresTheShadowItself() {
        let model = ModelEntity(mesh: .generateBox(size: 0.2))
        let shadow = ContactShadowNode.under(model, spread: 3)
        let first = shadow.size
        shadow.fit(under: model, spread: 3)
        XCTAssertEqual(shadow.size, first, "the 0.6 m quad must not widen the bounds it is fitted to")
        let bounds = model.contactShadowFreeBounds(relativeTo: model)
        XCTAssertEqual(bounds?.extents.x ?? 0, 0.2, accuracy: 1e-5)
    }

    func testFitWithoutGeometryLeavesTheShadowAlone() {
        let empty = Entity()
        let shadow = ContactShadowNode(width: 0.3, length: 0.3)
        empty.addChild(shadow.entity)
        shadow.fit(under: empty)
        XCTAssertEqual(shadow.size, [0.3, 0.3])
        XCTAssertNil(empty.contactShadowFreeBounds(relativeTo: empty))
    }

    func testFootprintNeverCollapses() {
        let flat = BoundingBox(min: [0, 0, 0], max: [0, 0, 0])
        let footprint = ContactShadowNode.footprint(of: flat, spread: .nan, lift: .infinity)
        XCTAssertEqual(footprint.position, [0, 0, 0])
        XCTAssertGreaterThan(footprint.size.x, 0)
        XCTAssertGreaterThan(footprint.size.y, 0)
    }

    func testFittedShadowIsAtLeastATenthOfAMetre() {
        let tiny = ModelEntity(mesh: .generateBox(size: 0.01))
        XCTAssertEqual(ContactShadowNode.under(tiny).size, [0.1, 0.1])
        // Measured in the world: under a half-scaled model 0.1 m is 0.2 in its space.
        let scaled = ModelEntity(mesh: .generateBox(size: 0.01))
        scaled.scale = [0.5, 0.5, 0.5]
        let shadow = ContactShadowNode.under(scaled)
        XCTAssertEqual(shadow.size.x, 0.2, accuracy: 1e-5)
        XCTAssertEqual(shadow.size.y, 0.2, accuracy: 1e-5)
    }

    func testShadowStaysLevelUnderATiltedRoot() {
        // A Z-up asset corrected by its root (-90° about X), then turned 30° about Y.
        let model = ModelEntity(mesh: .generateBox(width: 0.4, height: 0.2, depth: 0.6))
        let turn = simd_quatf(angle: .pi / 6, axis: [0, 1, 0])
        model.orientation = turn * simd_quatf(angle: -.pi / 2, axis: [1, 0, 0])
        let root = Entity()
        root.addChild(model)

        let shadow = ContactShadowNode.under(model)

        let world = shadow.entity.orientation(relativeTo: nil)
        let up = world.act([0, 1, 0])
        XCTAssertEqual(up.y, 1, accuracy: 1e-4, "the quad lies flat, not tilted with the root")
        let forward = world.act([0, 0, -1])
        let expected = turn.act([0, 0, -1])
        XCTAssertEqual(forward.x, expected.x, accuracy: 1e-4, "it keeps the model's turn")
        XCTAssertEqual(forward.z, expected.z, accuracy: 1e-4)
        // The 0.6 depth now stands vertical: the lowest point is 0.3 below the centre.
        let position = shadow.entity.position(relativeTo: nil)
        XCTAssertEqual(position.y, -0.3 + ContactShadowNode.surfaceOffset, accuracy: 1e-4)
        XCTAssertEqual(position.x, 0, accuracy: 1e-4)
        XCTAssertEqual(position.z, 0, accuracy: 1e-4)
        // Footprint measured in the level frame: 0.4 wide, 0.2 deep.
        XCTAssertEqual(shadow.size.x, 0.4 * ContactShadowNode.defaultSpread, accuracy: 1e-4)
        XCTAssertEqual(shadow.size.y, 0.2 * ContactShadowNode.defaultSpread, accuracy: 1e-4)
    }

    func testYawDropsTheTilt() {
        let turn = simd_quatf(angle: 0.7, axis: [0, 1, 0])
        let tilted = turn * simd_quatf(angle: 0.4, axis: [1, 0, 0])
        let yaw = ContactShadowNode.yaw(of: tilted)
        XCTAssertEqual(abs(simd_dot(yaw.vector, turn.vector)), 1, accuracy: 1e-5)
        // Facing straight down, the right vector still gives the turn.
        let down = turn * simd_quatf(angle: -.pi / 2, axis: [1, 0, 0])
        XCTAssertEqual(abs(simd_dot(ContactShadowNode.yaw(of: down).vector, turn.vector)), 1, accuracy: 1e-5)
    }

    // MARK: - Sizing code ignores the decal

    func testScaleToUnitsAndCenterOriginMeasureTheModelAlone() {
        let node = ModelNode(ModelEntity(mesh: .generateBox(size: 0.5))).withContactShadow()
        node.scaleToUnits(1)
        XCTAssertEqual(node.entity.scale.x, 2, accuracy: 1e-4, "the 1.6x quad must not shrink the model")

        // A shadow moved off-centre by the app must not pull the centring.
        let other = ModelNode(ModelEntity(mesh: .generateBox(size: 0.5)))
        ContactShadowNode.under(other.entity).position([3, 0, 0])
        other.centerOrigin()
        XCTAssertEqual(other.entity.position.x, 0, accuracy: 1e-5)
        other.centerOrigin(normalized: [0, -1, 0])
        XCTAssertEqual(other.entity.position.y, 0.25, accuracy: 1e-5)
    }

    func testCollisionShapesSkipTheShadow() {
        let node = ModelNode(ModelEntity(mesh: .generateBox(size: 0.3))).withContactShadow()
        node.enableCollision()
        let shadow = node.entity.children.compactMap { ContactShadowNode($0) }.first
        XCTAssertTrue(node.entity.components.has(CollisionComponent.self))
        XCTAssertNotNil(shadow)
        XCTAssertFalse(shadow?.entity.components.has(CollisionComponent.self) ?? true,
                       "the decal is not a tap target")
        XCTAssertEqual(node.collisionBounds?.extents.x ?? 0, 0.3, accuracy: 1e-5)
    }

    func testGestureAndWithCollisionSkipTheShadow() {
        let gestured = ModelNode(ModelEntity(mesh: .generateBox(size: 0.3))).withContactShadow()
        NodeGesture.onTap(gestured.entity) { }
        let chained = ModelEntity(mesh: .generateBox(size: 0.3))
        ContactShadowNode.under(chained)
        chained.withCollision()
        for model in [gestured.entity, chained] {
            let shadow = model.children.compactMap { ContactShadowNode($0) }.first
            XCTAssertTrue(model.components.has(CollisionComponent.self))
            XCTAssertNotNil(shadow)
            XCTAssertFalse(shadow?.entity.components.has(CollisionComponent.self) ?? true,
                           "the decal is not a tap target")
        }
    }

    func testFramingBoundsLeaveShadowsOut() {
        let root = Entity()
        let a = ModelEntity(mesh: .generateBox(size: 0.2))
        a.position = [-1, 0, 0]
        let b = ModelEntity(mesh: .generateBox(size: 0.2))
        b.position = [1, 0, 0]
        root.addChild(a)
        root.addChild(b)
        ContactShadowNode.under(b, spread: 20)        // 4 m wide quad
        let bounds = Entity.contactShadowFreeBounds(of: Array(root.children), relativeTo: root)
        XCTAssertEqual(bounds?.min.x ?? 0, -1.1, accuracy: 1e-5)
        XCTAssertEqual(bounds?.max.x ?? 0, 1.1, accuracy: 1e-5)
        XCTAssertEqual(bounds?.extents.z ?? 0, 0.2, accuracy: 1e-5)
        XCTAssertNil(Entity.contactShadowFreeBounds(of: [], relativeTo: root))
    }

    func testModelNodeConvenienceAddsOneShadow() {
        let node = ModelNode(ModelEntity(mesh: .generateBox(size: 0.3)))
            .withContactShadow(.tableTop, intensity: 0.5)
        let shadows = node.entity.children.compactMap { ContactShadowNode($0) }
        XCTAssertEqual(shadows.count, 1)
        XCTAssertEqual(shadows.first?.context, .tableTop)
        XCTAssertEqual(shadows.first?.intensity ?? 0, 0.5, accuracy: 1e-6)
    }
}

#if os(iOS)
import ARKit

/// ``ARPlacementShadow`` — the shadow `ARSceneView` gives tap-placed content.
@MainActor
final class ARPlacementShadowTests: XCTestCase {

    private func placedAnchor(in arView: ARView) -> (AnchorEntity, ModelEntity) {
        let anchor = AnchorEntity(world: .zero)
        let model = ModelEntity(mesh: .generateBox(size: 0.2), materials: [SimpleMaterial()])
        model.position = [0, 0.1, 0]
        anchor.addChild(model)
        arView.scene.addAnchor(anchor)
        return (anchor, model)
    }

    func testDefaultStyleIsUnchanged() {
        XCTAssertEqual(ARSceneView().resolvedPlacementShadow, .grounding)
        XCTAssertEqual(ARSceneView(groundingShadows: false).resolvedPlacementShadow, .none)
        XCTAssertEqual(ARSceneView(groundingShadows: false).placementShadow(.contact()).resolvedPlacementShadow,
                       .contact(.floor, intensity: nil))
        XCTAssertEqual(ARSceneView.Coordinator(onTapOnPlane: nil, planeDetection: .horizontal).placementShadow,
                       .grounding)
    }

    private func shadows(in entity: Entity) -> [ContactShadowNode] {
        var found: [ContactShadowNode] = []
        var stack: [Entity] = [entity]
        while let next = stack.popLast() {
            if let shadow = ContactShadowNode(next) { found.append(shadow) }
            stack.append(contentsOf: next.children)
        }
        return found
    }

    func testContactStyleGroundsOnlyNewAnchorsWithoutGroundingShadow() {
        let arView = ARView(frame: .zero)
        let coordinator = ARSceneView.Coordinator(onTapOnPlane: nil, planeDetection: .horizontal)
        let (existing, _) = placedAnchor(in: arView)
        let before = Set(arView.scene.anchors.map(\.id))
        let (placed, model) = placedAnchor(in: arView)

        coordinator.applyPlacementShadow(.contact(.floor, intensity: 0.3), in: arView, addedSince: before)

        let shadow = shadows(in: placed).first
        XCTAssertNotNil(shadow)
        XCTAssertTrue(shadow?.entity.parent === model, "a single model carries its shadow and moves with it")
        XCTAssertEqual(shadow?.intensity ?? 0, 0.3, accuracy: 1e-6)
        XCTAssertEqual(shadow?.entity.position(relativeTo: placed).y ?? 1, ContactShadowNode.surfaceOffset,
                       accuracy: 1e-5, "rests on the surface the model stands on")
        XCTAssertFalse(model.components.has(GroundingShadowComponent.self))
        XCTAssertTrue(shadows(in: existing).isEmpty)

        // Applying again (a second tap with nothing new) never stacks a second shadow.
        ARSceneView.Coordinator.applyContactShadow(to: placed, context: .floor, intensity: nil)
        XCTAssertEqual(shadows(in: placed).count, 1)
    }

    func testContentThatAlreadyHasAShadowGetsNoSecondOne() {
        // `ModelNode.withContactShadow()` then `clone(recursive:)` under the anchor: the
        // shadow sits one level below the anchor's child.
        let source = ModelNode(ModelEntity(mesh: .generateBox(size: 0.2))).withContactShadow()
        let anchor = AnchorEntity(world: .zero)
        let wrapper = Entity()
        wrapper.addChild(source.entity.clone(recursive: true))
        anchor.addChild(wrapper)
        ARSceneView.Coordinator.applyContactShadow(to: anchor, context: .floor, intensity: nil)
        XCTAssertEqual(shadows(in: anchor).count, 1, "a second pool would double the darkness")
    }

    func testSeveralChildrenShareOneShadowOnTheAnchor() {
        let anchor = AnchorEntity(world: .zero)
        let a = ModelEntity(mesh: .generateBox(size: 0.2))
        a.position = [-0.3, 0.1, 0]
        let b = ModelEntity(mesh: .generateBox(size: 0.2))
        b.position = [0.3, 0.1, 0]
        anchor.addChild(a)
        anchor.addChild(b)
        ARSceneView.Coordinator.applyContactShadow(to: anchor, context: .floor, intensity: nil)
        let all = shadows(in: anchor)
        XCTAssertEqual(all.count, 1)
        XCTAssertTrue(all.first?.entity.parent === anchor)
        XCTAssertEqual(all.first?.size.x ?? 0, 0.8 * ContactShadowNode.defaultSpread, accuracy: 1e-4)
    }

    func testPlacementPreviewSizeAndCollisionIgnoreTheShadow() throws {
        let model = ModelEntity(mesh: .generateBox(size: 1))
        ContactShadowNode.under(model)
        let wrapper = try XCTUnwrap(ARPlacementController.prepare(model, alignment: .horizontal, previewSize: 0.3))
        let bounds = model.visualBoundsExcludingContactShadows(relativeTo: wrapper)
        XCTAssertEqual(max(bounds.extents.x, max(bounds.extents.y, bounds.extents.z)), 0.3, accuracy: 1e-4,
                       "previewSize sizes the model, not its 1.6x shadow")
        XCTAssertEqual(bounds.min.y, 0, accuracy: 1e-4, "the model, not the quad, stands on the pivot")
        let shadow = try XCTUnwrap(shadows(in: model).first)
        XCTAssertFalse(shadow.entity.components.has(CollisionComponent.self))
        XCTAssertTrue(model.components.has(CollisionComponent.self))
    }

    func testGroundingSkipsContentThatBringsItsOwnContactShadow() throws {
        let arView = ARView(frame: .zero)
        let coordinator = ARSceneView.Coordinator(onTapOnPlane: nil, planeDetection: .horizontal)
        let (_, plain) = placedAnchor(in: arView)
        let (_, shadowed) = placedAnchor(in: arView)
        ContactShadowNode.under(shadowed)
        coordinator.applyPlacementShadow(.grounding, in: arView, addedSince: [])
        XCTAssertTrue(plain.components.has(GroundingShadowComponent.self))
        XCTAssertFalse(shadowed.components.has(GroundingShadowComponent.self), "one shadow, never two")

        let model = ModelEntity(mesh: .generateBox(size: 0.2), materials: [SimpleMaterial()])
        ContactShadowNode.under(model)
        _ = try XCTUnwrap(ARPlacementController.prepare(model, alignment: .horizontal, previewSize: nil))
        XCTAssertFalse(model.components.has(GroundingShadowComponent.self))
        let bare = ModelEntity(mesh: .generateBox(size: 0.2), materials: [SimpleMaterial()])
        _ = try XCTUnwrap(ARPlacementController.prepare(bare, alignment: .horizontal, previewSize: nil))
        XCTAssertTrue(bare.components.has(GroundingShadowComponent.self))
    }

    func testNoneStyleAddsNothing() {
        let arView = ARView(frame: .zero)
        let coordinator = ARSceneView.Coordinator(onTapOnPlane: nil, planeDetection: .horizontal)
        let (placed, model) = placedAnchor(in: arView)
        coordinator.applyPlacementShadow(.none, in: arView, addedSince: [])
        XCTAssertFalse(model.components.has(GroundingShadowComponent.self))
        XCTAssertTrue(shadows(in: placed).isEmpty)
    }

    func testChangingTheContactStyleRetunesPlacedShadowsOnly() {
        let arView = ARView(frame: .zero)
        let coordinator = ARSceneView.Coordinator(onTapOnPlane: nil, planeDetection: .horizontal)
        coordinator.updatePlacementShadow(.contact(), in: arView)
        let (placed, _) = placedAnchor(in: arView)
        coordinator.applyPlacementShadow(coordinator.placementShadow, in: arView, addedSince: [])
        let (own, ownModel) = placedAnchor(in: arView)
        let appShadow = ContactShadowNode.under(ownModel, intensity: 0.1)

        coordinator.updatePlacementShadow(.contact(.tableTop, intensity: 0.8), in: arView)

        let retuned = shadows(in: placed).first
        XCTAssertEqual(retuned?.context, .tableTop)
        XCTAssertEqual(retuned?.intensity ?? 0, 0.8, accuracy: 1e-6)
        XCTAssertEqual(appShadow.intensity, 0.1, accuracy: 1e-6, "the app's own shadows are left alone")
        XCTAssertEqual(appShadow.context, .floor)
        _ = own
    }

    func testGroundingShadowSkipsContactShadowQuads() {
        let model = ModelEntity(mesh: .generateBox(size: 0.2))
        let shadow = ContactShadowNode.under(model)
        ARSceneView.Coordinator.applyGroundingShadow(to: model)
        XCTAssertTrue(model.components.has(GroundingShadowComponent.self))
        XCTAssertFalse(shadow.entity.components.has(GroundingShadowComponent.self))
    }
}
#endif // os(iOS)

#endif // os(iOS) || os(macOS) || os(visionOS)
