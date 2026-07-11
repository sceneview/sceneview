import XCTest
@testable import SceneViewSwift

#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit

// Test classes run on the main actor: their RealityKit node factories
// (`LightNode.directional`, `node.entity`, …) are `@MainActor`. (#1054)
@MainActor
final class ModelNodeTests: XCTestCase {

    // MARK: - Initialization

    func testInitWithModelEntity() {
        let entity = ModelEntity()
        let node = ModelNode(entity)
        XCTAssertNotNil(node.entity)
    }

    func testInitSetsNilTapHandler() {
        let node = ModelNode(ModelEntity())
        XCTAssertNil(node.tapHandler)
    }

    // MARK: - Position

    func testPositionHelperSetsPosition() {
        let entity = ModelEntity()
        let node = ModelNode(entity)
            .position(.init(x: 1, y: 2, z: 3))

        XCTAssertEqual(node.entity.position.x, 1.0, accuracy: 0.001)
        XCTAssertEqual(node.entity.position.y, 2.0, accuracy: 0.001)
        XCTAssertEqual(node.entity.position.z, 3.0, accuracy: 0.001)
    }

    func testPositionPropertyGetSet() {
        var node = ModelNode(ModelEntity())
        node.position = SIMD3<Float>(4, 5, 6)

        XCTAssertEqual(node.position.x, 4.0, accuracy: 0.001)
        XCTAssertEqual(node.position.y, 5.0, accuracy: 0.001)
        XCTAssertEqual(node.position.z, 6.0, accuracy: 0.001)
    }

    // MARK: - Scale

    func testUniformScaleHelper() {
        let node = ModelNode(ModelEntity())
            .scale(2.0)

        XCTAssertEqual(node.entity.scale.x, 2.0, accuracy: 0.001)
        XCTAssertEqual(node.entity.scale.y, 2.0, accuracy: 0.001)
        XCTAssertEqual(node.entity.scale.z, 2.0, accuracy: 0.001)
    }

    func testPerAxisScaleHelper() {
        let node = ModelNode(ModelEntity())
            .scale(.init(x: 1, y: 2, z: 3))

        XCTAssertEqual(node.entity.scale.x, 1.0, accuracy: 0.001)
        XCTAssertEqual(node.entity.scale.y, 2.0, accuracy: 0.001)
        XCTAssertEqual(node.entity.scale.z, 3.0, accuracy: 0.001)
    }

    func testScalePropertyGetSet() {
        var node = ModelNode(ModelEntity())
        node.scale = SIMD3<Float>(0.5, 0.5, 0.5)

        XCTAssertEqual(node.scale.x, 0.5, accuracy: 0.001)
        XCTAssertEqual(node.scale.y, 0.5, accuracy: 0.001)
        XCTAssertEqual(node.scale.z, 0.5, accuracy: 0.001)
    }

    // MARK: - Rotation

    func testRotationWithQuaternion() {
        let quat = simd_quatf(angle: .pi / 2, axis: .init(x: 0, y: 1, z: 0))
        let node = ModelNode(ModelEntity())
            .rotation(quat)

        XCTAssertEqual(node.entity.orientation.angle, Float.pi / 2, accuracy: 0.01)
    }

    func testRotationWithAngleAndAxis() {
        let node = ModelNode(ModelEntity())
            .rotation(angle: .pi / 4, axis: .init(x: 1, y: 0, z: 0))

        XCTAssertEqual(node.entity.orientation.angle, Float.pi / 4, accuracy: 0.01)
    }

    func testRotationPropertyGetSet() {
        var node = ModelNode(ModelEntity())
        let quat = simd_quatf(angle: .pi, axis: .init(x: 0, y: 0, z: 1))
        node.rotation = quat

        XCTAssertEqual(node.rotation.angle, Float.pi, accuracy: 0.01)
    }

    // MARK: - scaleToUnits

    func testScaleToUnitsWithGeometry() {
        // Create a known-size box and wrap it in a ModelNode
        let mesh = MeshResource.generateBox(size: 2.0)
        let material = SimpleMaterial(color: .white, isMetallic: false)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        let node = ModelNode(entity)

        node.scaleToUnits(1.0)

        // The box was 2m, scaled to 1m means scale factor = 0.5
        XCTAssertEqual(node.entity.scale.x, 0.5, accuracy: 0.05)
        XCTAssertEqual(node.entity.scale.y, 0.5, accuracy: 0.05)
        XCTAssertEqual(node.entity.scale.z, 0.5, accuracy: 0.05)
    }

    // MARK: - Animation properties

    func testAnimationCountOnEmptyEntity() {
        let node = ModelNode(ModelEntity())
        XCTAssertEqual(node.animationCount, 0)
    }

    func testAnimationNamesOnEmptyEntity() {
        let node = ModelNode(ModelEntity())
        XCTAssertTrue(node.animationNames.isEmpty)
    }

    func testPlayAnimationNamedDoesNotCrashOnEmptyEntity() {
        let node = ModelNode(ModelEntity())
        // Should be a no-op, not crash
        node.playAnimation(named: "walk")
    }

    func testPlayAnimationAtIndexOutOfBoundsDoesNotCrash() {
        let node = ModelNode(ModelEntity())
        // Should be a no-op, not crash
        node.playAnimation(at: 99)
    }

    // MARK: - Material properties

    func testSetColorOnEntityWithSimpleMaterial() {
        let mesh = MeshResource.generateBox(size: 1.0)
        let material = SimpleMaterial(color: .white, isMetallic: false)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        let node = ModelNode(entity)

        let result = node.setColor(.red)

        // Verify chaining returns self
        XCTAssertTrue(result.entity === node.entity)
        // Verify material was updated
        XCTAssertNotNil(node.entity.model)
        XCTAssertEqual(node.entity.model?.materials.count, 1)
    }

    func testSetColorReturnsOnNoModel() {
        let entity = ModelEntity()
        let node = ModelNode(entity)

        // Should not crash — no model component
        let result = node.setColor(.blue)
        XCTAssertTrue(result.entity === node.entity)
    }

    func testSetMetallicOnEntityWithoutModel() {
        let node = ModelNode(ModelEntity())
        // Should be a no-op
        let result = node.setMetallic(1.0)
        XCTAssertTrue(result.entity === node.entity)
    }

    func testSetRoughnessOnEntityWithoutModel() {
        let node = ModelNode(ModelEntity())
        // Should be a no-op
        let result = node.setRoughness(0.5)
        XCTAssertTrue(result.entity === node.entity)
    }

    func testOpacityOnEntityWithSimpleMaterial() {
        let mesh = MeshResource.generateBox(size: 1.0)
        let material = SimpleMaterial(color: .white, isMetallic: false)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        let node = ModelNode(entity)

        let result = node.opacity(0.5)

        XCTAssertTrue(result.entity === node.entity)
        XCTAssertNotNil(node.entity.model)
    }

    func testOpacityOnEntityWithoutModel() {
        let node = ModelNode(ModelEntity())
        let result = node.opacity(0.5)
        XCTAssertTrue(result.entity === node.entity)
    }

    // MARK: - Collision

    func testCollisionBoundsIsNilWithoutCollision() {
        let entity = ModelEntity()
        let node = ModelNode(entity)
        XCTAssertNil(node.collisionBounds)
    }

    func testCollisionBoundsAvailableAfterEnableCollision() {
        let mesh = MeshResource.generateBox(size: 1.0)
        let material = SimpleMaterial(color: .white, isMetallic: false)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        let node = ModelNode(entity)

        node.enableCollision()

        // After generating collision shapes, bounds should be available
        XCTAssertNotNil(node.collisionBounds)
    }

    // MARK: - Tap handler

    func testOnTapStoresHandler() {
        var node = ModelNode(ModelEntity())
        XCTAssertNil(node.tapHandler)

        var tapped = false
        node.onTap { tapped = true }

        XCTAssertNotNil(node.tapHandler)
        node.tapHandler?()
        XCTAssertTrue(tapped)
    }

    func testOnTapReturnsSelfForChaining() {
        var node = ModelNode(ModelEntity())
        let result = node.onTap { }

        // Same underlying entity
        XCTAssertTrue(result.entity === node.entity)
    }

    // MARK: - Animation edge cases

    func testPlayAllAnimationsWithZeroAnimations() {
        let node = ModelNode(ModelEntity())
        // Should be a no-op when there are no animations
        node.playAllAnimations()
        XCTAssertEqual(node.animationCount, 0)
    }

    func testStopAllAnimationsWhenNotAnimating() {
        let node = ModelNode(ModelEntity())
        // Should not crash when stopping with no active animations
        node.stopAllAnimations()
        XCTAssertEqual(node.animationCount, 0)
    }

    func testPauseAllAnimationsWhenNotAnimating() {
        let node = ModelNode(ModelEntity())
        // Should not crash when pausing with no active animations
        node.pauseAllAnimations()
        XCTAssertEqual(node.animationCount, 0)
    }

    // MARK: - scaleToUnits edge cases

    func testScaleToUnitsWithZeroValue() {
        let mesh = MeshResource.generateBox(size: 2.0)
        let material = SimpleMaterial(color: .white, isMetallic: false)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        let node = ModelNode(entity)

        // Scale to zero units — should compute scaleFactor = 0
        node.scaleToUnits(0.0)
        XCTAssertEqual(node.entity.scale.x, 0.0, accuracy: 0.001)
        XCTAssertEqual(node.entity.scale.y, 0.0, accuracy: 0.001)
        XCTAssertEqual(node.entity.scale.z, 0.0, accuracy: 0.001)
    }

    func testScaleToUnitsWithNegativeValue() {
        let mesh = MeshResource.generateBox(size: 2.0)
        let material = SimpleMaterial(color: .white, isMetallic: false)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        let node = ModelNode(entity)

        // Negative units — should produce negative scale (mirror)
        node.scaleToUnits(-1.0)
        XCTAssertEqual(node.entity.scale.x, -0.5, accuracy: 0.05)
    }

    func testScaleToUnitsOnEmptyEntity() {
        let node = ModelNode(ModelEntity())
        // Entity with no mesh has zero extents — should be a no-op (guard maxExtent > 0)
        node.scaleToUnits(1.0)
        // Scale should remain at default (1, 1, 1)
        XCTAssertEqual(node.entity.scale.x, 1.0, accuracy: 0.001)
        XCTAssertEqual(node.entity.scale.y, 1.0, accuracy: 0.001)
        XCTAssertEqual(node.entity.scale.z, 1.0, accuracy: 0.001)
    }

    // MARK: - Loading invalid model path

    func testLoadInvalidModelPathThrows() async {
        do {
            _ = try await ModelNode.load("nonexistent_model_xyz.usdz")
            XCTFail("Should have thrown an error for invalid model path")
        } catch {
            // Expected: loading a nonexistent model should throw
            XCTAssertNotNil(error)
        }
    }

    // MARK: - Chaining

    func testChainingTransforms() {
        let node = ModelNode(ModelEntity())
            .position(.init(x: 1, y: 0, z: -1))
            .scale(0.5)
            .rotation(angle: .pi / 6, axis: .init(x: 0, y: 1, z: 0))

        XCTAssertEqual(node.position.x, 1.0, accuracy: 0.001)
        XCTAssertEqual(node.scale.x, 0.5, accuracy: 0.001)
        XCTAssertEqual(node.entity.orientation.angle, Float.pi / 6, accuracy: 0.01)
    }

    func testChainingMaterialMethods() {
        let mesh = MeshResource.generateBox(size: 1.0)
        let material = SimpleMaterial(color: .white, isMetallic: false)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        let node = ModelNode(entity)
            .setColor(.red)
            .opacity(0.8)

        XCTAssertNotNil(node.entity.model)
    }

    func testChainingTransformsAndMaterials() {
        let mesh = MeshResource.generateBox(size: 1.0)
        let material = SimpleMaterial(color: .white, isMetallic: false)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        let node = ModelNode(entity)
            .position(.init(x: 0, y: 1, z: -2))
            .scale(0.5)
            .setColor(.blue)

        XCTAssertEqual(node.position.y, 1.0, accuracy: 0.001)
        XCTAssertEqual(node.scale.x, 0.5, accuracy: 0.001)
    }

    // MARK: - Grounding shadow

    func testWithGroundingShadowDoesNotCrash() {
        let node = ModelNode(ModelEntity())
            .withGroundingShadow()
        XCTAssertNotNil(node.entity)
    }

    func testWithGroundingShadowChaining() {
        let mesh = MeshResource.generateBox(size: 1.0)
        let material = SimpleMaterial(color: .white, isMetallic: false)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        let node = ModelNode(entity)
            .position(.init(x: 0, y: 0, z: -2))
            .withGroundingShadow()
        XCTAssertEqual(node.position.z, -2.0, accuracy: 0.001)
    }

    // MARK: - isAnimating

    func testIsAnimatingPropertyOnEmptyEntity() {
        let node = ModelNode(ModelEntity())
        // With no animations, isAnimating relies on availableAnimations being empty
        // which means the current implementation checks !entity.availableAnimations.isEmpty
        XCTAssertEqual(node.animationCount, 0)
    }

    // MARK: - Animation with custom speed

    func testPlayAllAnimationsWithCustomSpeed() {
        let node = ModelNode(ModelEntity())
        // Should not crash when there are no animations
        node.playAllAnimations(loop: false, speed: 2.0)
        XCTAssertEqual(node.animationCount, 0)
    }

    func testPlayAnimationNamedWithCustomTransition() {
        let node = ModelNode(ModelEntity())
        // Should be a no-op, not crash
        node.playAnimation(named: "idle", loop: false, speed: 0.5, transitionDuration: 0.5)
        XCTAssertEqual(node.animationCount, 0)
    }

    // MARK: - Material chaining with PBR

    func testSetMetallicOnEntityWithPBRMaterial() {
        let mesh = MeshResource.generateBox(size: 1.0)
        var pbrMat = PhysicallyBasedMaterial()
        pbrMat.baseColor = .init(tint: .white)
        let entity = ModelEntity(mesh: mesh, materials: [pbrMat])
        let node = ModelNode(entity)
            .setMetallic(0.8)
        XCTAssertNotNil(node.entity.model)
    }

    func testSetRoughnessOnEntityWithPBRMaterial() {
        let mesh = MeshResource.generateBox(size: 1.0)
        var pbrMat = PhysicallyBasedMaterial()
        pbrMat.baseColor = .init(tint: .white)
        let entity = ModelEntity(mesh: mesh, materials: [pbrMat])
        let node = ModelNode(entity)
            .setRoughness(0.2)
        XCTAssertNotNil(node.entity.model)
    }

    func testOpacityOnPBRMaterial() {
        let mesh = MeshResource.generateBox(size: 1.0)
        var pbrMat = PhysicallyBasedMaterial()
        pbrMat.baseColor = .init(tint: .white)
        let entity = ModelEntity(mesh: mesh, materials: [pbrMat])
        let node = ModelNode(entity)
            .opacity(0.3)
        XCTAssertNotNil(node.entity.model)
    }

    // MARK: - scaleToUnits with non-cube geometry

    func testScaleToUnitsWithRectangularBox() {
        // Box 4 wide, 2 tall, 1 deep => max extent = 4
        let mesh = MeshResource.generateBox(width: 4.0, height: 2.0, depth: 1.0)
        let material = SimpleMaterial(color: .white, isMetallic: false)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        let node = ModelNode(entity)
            .scaleToUnits(2.0)
        // scaleFactor = 2.0 / 4.0 = 0.5
        XCTAssertEqual(node.entity.scale.x, 0.5, accuracy: 0.05)
    }

    // MARK: - centerOrigin(normalized:) — Android parity (#2632)
    //
    // The normalized overload takes a bounding-box point in normalized AABB
    // coordinates (-1...1 per axis, 0 = box centre) and aligns it with the node
    // origin, translating by `-(center + origin * extents/2)` — mirroring
    // Android's `-(center + origin * halfExtent) * scale` (scale is baked into
    // the RealityKit visual bounds). See ModelNodeCenterOriginFormulaTest on the
    // Android side. The pure-math helper is pinned first, then verified against a
    // real RealityKit box.

    /// `origin = (0,0,0)` must translate by `-center` — cancel the AABB centre.
    func testCenterOriginTranslationCentersNonCenteredBox() {
        // Non-centered fixture (mirrors the Android formula test): center=(1,2,1).
        let t = ModelNode.centerOriginTranslation(
            center: SIMD3(1, 2, 1), extents: SIMD3(2, 4, 2), origin: .zero
        )
        XCTAssertEqual(t.x, -1, accuracy: 1e-6)
        XCTAssertEqual(t.y, -2, accuracy: 1e-6)
        XCTAssertEqual(t.z, -1, accuracy: 1e-6)
    }

    /// `origin = (0,-1,0)` (bottom aligned) must lift the bottom-centre anchor onto
    /// the origin: `-(center + (0,-1,0) * extents/2)`.
    func testCenterOriginTranslationBottomAligns() {
        let center = SIMD3<Float>(1, 2, 1)
        let extents = SIMD3<Float>(2, 4, 2)
        let t = ModelNode.centerOriginTranslation(
            center: center, extents: extents, origin: SIMD3(0, -1, 0)
        )
        // anchor = center + (0,-1,0)*(1,2,1) = (1,0,1) => translation = (-1,0,-1)
        XCTAssertEqual(t.x, -1, accuracy: 1e-6)
        XCTAssertEqual(t.y, 0, accuracy: 1e-6)
        XCTAssertEqual(t.z, -1, accuracy: 1e-6)
        // Invariant: the selected AABB point, once translated, lands on the origin.
        let anchor = center + SIMD3<Float>(0, -1, 0) * (extents / 2) + t
        XCTAssertEqual(anchor.x, 0, accuracy: 1e-6)
        XCTAssertEqual(anchor.y, 0, accuracy: 1e-6)
        XCTAssertEqual(anchor.z, 0, accuracy: 1e-6)
    }

    /// `origin = (1,-1,0)` selects the +x / -y corner-edge — per-axis half extents.
    func testCenterOriginTranslationUsesPerAxisHalfExtents() {
        let t = ModelNode.centerOriginTranslation(
            center: .zero, extents: SIMD3(4, 2, 1), origin: SIMD3(1, -1, 0)
        )
        // anchor = (0,0,0) + (1,-1,0)*(2,1,0.5) = (2,-1,0) => translation = (-2,1,0)
        XCTAssertEqual(t.x, -2, accuracy: 1e-6)
        XCTAssertEqual(t.y, 1, accuracy: 1e-6)
        XCTAssertEqual(t.z, 0, accuracy: 1e-6)
    }

    /// A real, origin-centred RealityKit box: `.zero` must not move it (centre
    /// already on the origin), matching the absolute `centerOrigin(.zero)`.
    func testCenterOriginNormalizedZeroIsNoOpForCenteredBox() {
        let mesh = MeshResource.generateBox(size: 2.0)
        let material = SimpleMaterial(color: .white, isMetallic: false)
        let node = ModelNode(ModelEntity(mesh: mesh, materials: [material]))
            .centerOrigin(normalized: .zero)
        XCTAssertEqual(node.entity.position.x, 0, accuracy: 1e-4)
        XCTAssertEqual(node.entity.position.y, 0, accuracy: 1e-4)
        XCTAssertEqual(node.entity.position.z, 0, accuracy: 1e-4)
    }

    /// A real box of height 2 centred at origin (bottom at y = -1): bottom-aligning
    /// must raise the node so the bottom face rests on y = 0 (position.y = +1).
    func testCenterOriginNormalizedBottomAlignsRealBox() {
        let mesh = MeshResource.generateBox(size: 2.0)
        let material = SimpleMaterial(color: .white, isMetallic: false)
        let node = ModelNode(ModelEntity(mesh: mesh, materials: [material]))
            .centerOrigin(normalized: SIMD3(0, -1, 0))
        XCTAssertEqual(node.entity.position.x, 0, accuracy: 1e-4)
        XCTAssertEqual(node.entity.position.y, 1, accuracy: 1e-4)
        XCTAssertEqual(node.entity.position.z, 0, accuracy: 1e-4)
    }

    /// Top-aligning a real origin-centred box (top at y = +1) must hang it from the
    /// origin (position.y = -1) — the sign is opposite bottom alignment.
    func testCenterOriginNormalizedTopAlignsRealBox() {
        let mesh = MeshResource.generateBox(size: 2.0)
        let material = SimpleMaterial(color: .white, isMetallic: false)
        let node = ModelNode(ModelEntity(mesh: mesh, materials: [material]))
            .centerOrigin(normalized: SIMD3(0, 1, 0))
        XCTAssertEqual(node.entity.position.y, -1, accuracy: 1e-4)
    }

    /// Parity: for a centred box, `centerOrigin(normalized: .zero)` and the absolute
    /// `centerOrigin(.zero)` must land the model at the same position.
    func testCenterOriginNormalizedZeroMatchesAbsoluteZero() {
        let mesh = MeshResource.generateBox(width: 4.0, height: 2.0, depth: 1.0)
        let material = SimpleMaterial(color: .white, isMetallic: false)
        let normalized = ModelNode(ModelEntity(mesh: mesh, materials: [material]))
            .centerOrigin(normalized: .zero)
        let absolute = ModelNode(ModelEntity(mesh: mesh, materials: [material]))
            .centerOrigin(.zero)
        XCTAssertEqual(normalized.entity.position.x, absolute.entity.position.x, accuracy: 1e-4)
        XCTAssertEqual(normalized.entity.position.y, absolute.entity.position.y, accuracy: 1e-4)
        XCTAssertEqual(normalized.entity.position.z, absolute.entity.position.z, accuracy: 1e-4)
    }

    /// The normalized overload is `@discardableResult` and chains like the rest of
    /// the builder surface.
    func testCenterOriginNormalizedChaining() {
        let mesh = MeshResource.generateBox(size: 1.0)
        let material = SimpleMaterial(color: .white, isMetallic: false)
        let node = ModelNode(ModelEntity(mesh: mesh, materials: [material]))
            .scaleToUnits(1.0)
            .centerOrigin(normalized: SIMD3(0, -1, 0))
        XCTAssertNotNil(node.entity.model)
    }
}
#endif
