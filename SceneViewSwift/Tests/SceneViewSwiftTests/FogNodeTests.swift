import XCTest
@testable import SceneViewSwift

#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit

// Test classes run on the main actor: their RealityKit node factories
// (`LightNode.directional`, `node.entity`, …) are `@MainActor`. (#1054)
@MainActor
final class FogNodeTests: XCTestCase {

    // MARK: - Factory methods

    func testLinearFogCreatesEntity() {
        let fog = FogNode.linear(start: 1.0, end: 20.0)
        XCTAssertNotNil(fog.entity)
        XCTAssertEqual(fog.entity.name, "FogNode")
        XCTAssertNotNil(fog.entity.model)
    }

    func testExponentialFogCreatesEntity() {
        let fog = FogNode.exponential(density: 0.1)
        XCTAssertNotNil(fog.entity)
        XCTAssertNotNil(fog.entity.model)
    }

    @available(*, deprecated)
    func testHeightBasedFogCreatesEntity() {
        let fog = FogNode.heightBased(density: 0.1, height: 2.0)
        XCTAssertNotNil(fog.entity)
        XCTAssertNotNil(fog.entity.model)
    }

    // MARK: - Default values

    func testLinearDefaultValues() {
        let fog = FogNode.linear()
        XCTAssertEqual(fog.startDistance, 1.0, accuracy: 0.001)
        XCTAssertEqual(fog.endDistance, 20.0, accuracy: 0.001)
        XCTAssertEqual(fog.density, 0.5, accuracy: 0.001)
    }

    func testExponentialDefaultValues() {
        let fog = FogNode.exponential()
        XCTAssertEqual(fog.density, 0.05, accuracy: 0.001)
        XCTAssertEqual(fog.startDistance, 0.5, accuracy: 0.001)
        XCTAssertEqual(fog.endDistance, 40.0, accuracy: 0.001)
    }

    @available(*, deprecated)
    func testHeightBasedDefaultValues() {
        let fog = FogNode.heightBased()
        XCTAssertEqual(fog.density, 0.05, accuracy: 0.001)
        XCTAssertEqual(fog.heightFalloff, 1.0, accuracy: 0.001)
    }

    // MARK: - Density clamping

    func testDensityClampedToZero() {
        let fog = FogNode.exponential(density: -0.5)
        XCTAssertEqual(fog.density, 0.0, accuracy: 0.001)
    }

    func testDensityClampedToOne() {
        let fog = FogNode.exponential(density: 2.0)
        XCTAssertEqual(fog.density, 1.0, accuracy: 0.001)
    }

    func testDensityWithinRange() {
        let fog = FogNode.exponential(density: 0.3)
        XCTAssertEqual(fog.density, 0.3, accuracy: 0.001)
    }

    // MARK: - Color enum

    func testColorEnumWhite() {
        let fog = FogNode.linear().color(.white)
        XCTAssertNotNil(fog.entity)
    }

    func testColorEnumCool() {
        let fog = FogNode.linear().color(.cool)
        XCTAssertNotNil(fog.entity)
    }

    func testColorEnumWarm() {
        let fog = FogNode.linear().color(.warm)
        XCTAssertNotNil(fog.entity)
    }

    func testColorEnumCustom() {
        let fog = FogNode.linear().color(.custom(r: 0.5, g: 0.6, b: 0.7))
        XCTAssertNotNil(fog.entity)
    }

    func testFactoryWithColor() {
        let fog = FogNode.linear(start: 2.0, end: 15.0, color: .cool)
        XCTAssertNotNil(fog.entity)
    }

    // MARK: - Builder methods

    func testDensityBuilder() {
        let fog = FogNode.linear().density(0.8)
        XCTAssertEqual(fog.density, 0.8, accuracy: 0.001)
    }

    func testStartDistanceBuilder() {
        let fog = FogNode.linear().startDistance(5.0)
        XCTAssertEqual(fog.startDistance, 5.0, accuracy: 0.001)
    }

    func testEndDistanceBuilder() {
        let fog = FogNode.linear().endDistance(50.0)
        XCTAssertEqual(fog.endDistance, 50.0, accuracy: 0.001)
    }

    func testPositionBuilder() {
        let fog = FogNode.exponential(density: 0.1)
            .position(.init(x: 1, y: 2, z: 3))

        XCTAssertEqual(fog.entity.position.x, 1.0, accuracy: 0.001)
        XCTAssertEqual(fog.entity.position.y, 2.0, accuracy: 0.001)
        XCTAssertEqual(fog.entity.position.z, 3.0, accuracy: 0.001)
    }

    // MARK: - Builder chaining

    func testChainingMultipleBuilders() {
        let fog = FogNode.linear()
            .color(.cool)
            .density(0.2)
            .startDistance(2.0)
            .endDistance(30.0)
            .position(.init(x: 0, y: 1, z: 0))

        XCTAssertEqual(fog.density, 0.2, accuracy: 0.001)
        XCTAssertEqual(fog.startDistance, 2.0, accuracy: 0.001)
        XCTAssertEqual(fog.endDistance, 30.0, accuracy: 0.001)
        XCTAssertEqual(fog.entity.position.y, 1.0, accuracy: 0.001)
    }

    // MARK: - Position property

    func testPositionPropertyGetSet() {
        var fog = FogNode.exponential()
        fog.position = SIMD3<Float>(3, 2, 1)

        XCTAssertEqual(fog.position.x, 3.0, accuracy: 0.001)
        XCTAssertEqual(fog.position.y, 2.0, accuracy: 0.001)
        XCTAssertEqual(fog.position.z, 1.0, accuracy: 0.001)
    }

    // MARK: - Height-based specific (deprecated, see #1380)

    // `heightFalloff` and `heightBased(...)` are deprecated on iOS — RealityKit has
    // no per-pixel fog gradient equivalent to Filament's `View.fogOptions.heightFalloff`.
    // The setter remains as a no-op pass-through purely so the getter round-trips
    // (source compatibility). These tests pin that store-only contract.

    @available(*, deprecated)
    func testHeightBasedFogSetsHeightFalloff() {
        let fog = FogNode.heightBased(density: 0.1, height: 5.0)
        XCTAssertEqual(fog.heightFalloff, 5.0, accuracy: 0.001)
    }

    @available(*, deprecated)
    func testHeightFalloffPropertySet() {
        var fog = FogNode.heightBased(density: 0.1, height: 1.0)
        fog.heightFalloff = 3.0
        XCTAssertEqual(fog.heightFalloff, 3.0, accuracy: 0.001)
    }

    /// Height-based fog renders identically to exponential fog on iOS: the height
    /// gradient is a documented RealityKit parity gap (#1380). Verifies that
    /// changing `heightFalloff` does not alter the rendered material/mesh, so the
    /// API is honestly a deprecated store-only no-op rather than silently pretending.
    @available(*, deprecated)
    func testHeightFalloffDoesNotAffectRenderedMaterial() {
        let exponential = FogNode.exponential(density: 0.1)
        let heightBased = FogNode.heightBased(density: 0.1, height: 5.0)

        // Same density -> same translucent-sphere approximation on iOS.
        XCTAssertEqual(
            exponential.entity.model?.materials.count,
            heightBased.entity.model?.materials.count
        )
        XCTAssertEqual(exponential.endDistance, heightBased.endDistance, accuracy: 0.001)
        XCTAssertEqual(exponential.startDistance, heightBased.startDistance, accuracy: 0.001)

        // Mutating heightFalloff must not change the entity scale (no updateScale call).
        var fog = heightBased
        let scaleBefore = fog.entity.scale
        fog.heightFalloff = 42.0
        XCTAssertEqual(fog.entity.scale.x, scaleBefore.x, accuracy: 0.001)
        XCTAssertEqual(fog.entity.scale.y, scaleBefore.y, accuracy: 0.001)
        XCTAssertEqual(fog.entity.scale.z, scaleBefore.z, accuracy: 0.001)
    }

    // MARK: - Material

    func testEntityHasOneMaterial() {
        let fog = FogNode.linear()
        XCTAssertEqual(fog.entity.model?.materials.count, 1)
    }

    func testEntityMeshExists() {
        let fog = FogNode.exponential(density: 0.5)
        XCTAssertNotNil(fog.entity.model?.mesh)
    }

    // MARK: - Edge cases

    func testStartDistanceNegativeClampedToZero() {
        let fog = FogNode.linear().startDistance(-5.0)
        XCTAssertEqual(fog.startDistance, 0.0, accuracy: 0.001)
    }

    func testEndDistanceNegativeClampedToZero() {
        let fog = FogNode.linear().endDistance(-10.0)
        XCTAssertEqual(fog.endDistance, 0.0, accuracy: 0.001)
    }

    func testZeroDensityProducesEntity() {
        let fog = FogNode.exponential(density: 0.0)
        XCTAssertNotNil(fog.entity)
        XCTAssertEqual(fog.density, 0.0, accuracy: 0.001)
    }

    func testMaxDensityProducesEntity() {
        let fog = FogNode.exponential(density: 1.0)
        XCTAssertNotNil(fog.entity)
        XCTAssertEqual(fog.density, 1.0, accuracy: 0.001)
    }

    // MARK: - Density mutable property

    func testDensityMutablePropertyClamps() {
        var fog = FogNode.exponential(density: 0.5)
        fog.density = 1.5
        XCTAssertEqual(fog.density, 1.0, accuracy: 0.001)

        fog.density = -0.5
        XCTAssertEqual(fog.density, 0.0, accuracy: 0.001)
    }

    func testDensityMutablePropertyWithinRange() {
        var fog = FogNode.exponential(density: 0.5)
        fog.density = 0.7
        XCTAssertEqual(fog.density, 0.7, accuracy: 0.001)
    }

    // MARK: - Start/end distance mutable properties

    func testStartDistanceMutableProperty() {
        var fog = FogNode.linear()
        fog.startDistance = 10.0
        XCTAssertEqual(fog.startDistance, 10.0, accuracy: 0.001)
    }

    func testStartDistanceMutablePropertyClampsNegative() {
        var fog = FogNode.linear()
        fog.startDistance = -5.0
        XCTAssertEqual(fog.startDistance, 0.0, accuracy: 0.001)
    }

    func testEndDistanceMutableProperty() {
        var fog = FogNode.linear()
        fog.endDistance = 100.0
        XCTAssertEqual(fog.endDistance, 100.0, accuracy: 0.001)
    }

    func testEndDistanceMutablePropertyClampsNegative() {
        var fog = FogNode.linear()
        fog.endDistance = -10.0
        XCTAssertEqual(fog.endDistance, 0.0, accuracy: 0.001)
    }

    // MARK: - Equal start and end distance

    func testEqualStartAndEndDistance() {
        let fog = FogNode.linear(start: 5.0, end: 5.0)
        XCTAssertEqual(fog.startDistance, 5.0, accuracy: 0.001)
        XCTAssertEqual(fog.endDistance, 5.0, accuracy: 0.001)
        XCTAssertNotNil(fog.entity)
    }

    // MARK: - Height falloff mutable property

    @available(*, deprecated)
    func testHeightFalloffNegativeValue() {
        var fog = FogNode.heightBased()
        fog.heightFalloff = -5.0
        XCTAssertEqual(fog.heightFalloff, -5.0, accuracy: 0.001)
    }

    @available(*, deprecated)
    func testHeightFalloffZero() {
        var fog = FogNode.heightBased()
        fog.heightFalloff = 0.0
        XCTAssertEqual(fog.heightFalloff, 0.0, accuracy: 0.001)
    }
}
#endif
