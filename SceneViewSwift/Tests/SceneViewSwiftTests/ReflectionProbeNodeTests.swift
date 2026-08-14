import XCTest
@testable import SceneViewSwift

#if os(iOS) || os(macOS) || os(visionOS)
import CoreGraphics
import RealityKit

// Test classes run on the main actor: their RealityKit node factories
// (`LightNode.directional`, `node.entity`, …) are `@MainActor`. (#1054)
@MainActor
final class ReflectionProbeNodeTests: XCTestCase {

    // MARK: - Factory methods

    func testBoxFactoryCreatesEntity() {
        let probe = ReflectionProbeNode.box(size: [2, 3, 4])
        XCTAssertNotNil(probe.entity)
        XCTAssertTrue(probe.entity.name.contains("Box"))
    }

    func testBoxFactoryDefaultSize() {
        let probe = ReflectionProbeNode.box()
        XCTAssertNotNil(probe.entity)
        if case .box(let size) = probe.shape {
            XCTAssertEqual(size.x, 1.0, accuracy: 0.001)
            XCTAssertEqual(size.y, 1.0, accuracy: 0.001)
            XCTAssertEqual(size.z, 1.0, accuracy: 0.001)
        } else {
            XCTFail("Expected box shape")
        }
    }

    func testSphereFactoryCreatesEntity() {
        let probe = ReflectionProbeNode.sphere(radius: 3.0)
        XCTAssertNotNil(probe.entity)
        XCTAssertTrue(probe.entity.name.contains("Sphere"))
    }

    func testSphereFactoryDefaultRadius() {
        let probe = ReflectionProbeNode.sphere()
        if case .sphere(let radius) = probe.shape {
            XCTAssertEqual(radius, 1.0, accuracy: 0.001)
        } else {
            XCTFail("Expected sphere shape")
        }
    }

    // MARK: - Shape enum

    func testShapeBoxStoresSize() {
        let probe = ReflectionProbeNode.box(size: [5, 6, 7])
        if case .box(let size) = probe.shape {
            XCTAssertEqual(size.x, 5.0, accuracy: 0.001)
            XCTAssertEqual(size.y, 6.0, accuracy: 0.001)
            XCTAssertEqual(size.z, 7.0, accuracy: 0.001)
        } else {
            XCTFail("Expected box shape")
        }
    }

    func testShapeSphereStoresRadius() {
        let probe = ReflectionProbeNode.sphere(radius: 2.5)
        if case .sphere(let radius) = probe.shape {
            XCTAssertEqual(radius, 2.5, accuracy: 0.001)
        } else {
            XCTFail("Expected sphere shape")
        }
    }

    // MARK: - Position

    func testPositionBuilderSetsEntityPosition() {
        let probe = ReflectionProbeNode.box()
            .position(.init(x: 1, y: 2, z: 3))

        XCTAssertEqual(probe.entity.position.x, 1.0, accuracy: 0.001)
        XCTAssertEqual(probe.entity.position.y, 2.0, accuracy: 0.001)
        XCTAssertEqual(probe.entity.position.z, 3.0, accuracy: 0.001)
    }

    func testPositionPropertyGetSet() {
        var probe = ReflectionProbeNode.sphere(radius: 1.0)
        probe.position = SIMD3<Float>(4, 5, 6)

        XCTAssertEqual(probe.position.x, 4.0, accuracy: 0.001)
        XCTAssertEqual(probe.position.y, 5.0, accuracy: 0.001)
        XCTAssertEqual(probe.position.z, 6.0, accuracy: 0.001)
    }

    func testScalePropertyGetSet() {
        var probe = ReflectionProbeNode.box()
        probe.scale = SIMD3<Float>(2, 2, 2)

        XCTAssertEqual(probe.scale.x, 2.0, accuracy: 0.001)
        XCTAssertEqual(probe.scale.y, 2.0, accuracy: 0.001)
        XCTAssertEqual(probe.scale.z, 2.0, accuracy: 0.001)
    }

    // MARK: - Intensity

    func testIntensityBuilderUpdatesEntityName() {
        let probe = ReflectionProbeNode.box()
            .intensity(0.5)

        XCTAssertTrue(probe.entity.name.contains("0.5"))
    }

    func testDefaultIntensityIsOne() {
        let probe = ReflectionProbeNode.box()
        XCTAssertTrue(probe.entity.name.contains("1.0"))
    }

    // MARK: - Volume size

    func testVolumeSizeForBox() {
        let probe = ReflectionProbeNode.box(size: [2, 3, 4])
        XCTAssertEqual(probe.volumeSize.x, 2.0, accuracy: 0.001)
        XCTAssertEqual(probe.volumeSize.y, 3.0, accuracy: 0.001)
        XCTAssertEqual(probe.volumeSize.z, 4.0, accuracy: 0.001)
    }

    func testVolumeSizeForSphere() {
        let probe = ReflectionProbeNode.sphere(radius: 3.0)
        XCTAssertEqual(probe.volumeSize.x, 6.0, accuracy: 0.001)
        XCTAssertEqual(probe.volumeSize.y, 6.0, accuracy: 0.001)
        XCTAssertEqual(probe.volumeSize.z, 6.0, accuracy: 0.001)
    }

    // MARK: - Contains

    func testBoxContainsPointInside() {
        let probe = ReflectionProbeNode.box(size: [2, 2, 2])
        XCTAssertTrue(probe.contains(.init(x: 0.5, y: 0.5, z: 0.5)))
    }

    func testBoxDoesNotContainPointOutside() {
        let probe = ReflectionProbeNode.box(size: [2, 2, 2])
        XCTAssertFalse(probe.contains(.init(x: 2.0, y: 0, z: 0)))
    }

    func testBoxContainsPointOnBoundary() {
        let probe = ReflectionProbeNode.box(size: [2, 2, 2])
        XCTAssertTrue(probe.contains(.init(x: 1.0, y: 0, z: 0)))
    }

    func testBoxContainsAccountsForPosition() {
        let probe = ReflectionProbeNode.box(size: [2, 2, 2])
            .position(.init(x: 5, y: 0, z: 0))
        XCTAssertFalse(probe.contains(.init(x: 0, y: 0, z: 0)))
        XCTAssertTrue(probe.contains(.init(x: 5, y: 0, z: 0)))
    }

    func testSphereContainsPointInside() {
        let probe = ReflectionProbeNode.sphere(radius: 2.0)
        XCTAssertTrue(probe.contains(.init(x: 1.0, y: 0, z: 0)))
    }

    func testSphereDoesNotContainPointOutside() {
        let probe = ReflectionProbeNode.sphere(radius: 2.0)
        XCTAssertFalse(probe.contains(.init(x: 3.0, y: 0, z: 0)))
    }

    func testSphereContainsPointOnBoundary() {
        let probe = ReflectionProbeNode.sphere(radius: 2.0)
        XCTAssertTrue(probe.contains(.init(x: 2.0, y: 0, z: 0)))
    }

    func testSphereContainsAccountsForPosition() {
        let probe = ReflectionProbeNode.sphere(radius: 1.0)
            .position(.init(x: 10, y: 0, z: 0))
        XCTAssertFalse(probe.contains(.init(x: 0, y: 0, z: 0)))
        XCTAssertTrue(probe.contains(.init(x: 10.5, y: 0, z: 0)))
    }

    // MARK: - Builder chaining

    func testBuilderMethodsChain() {
        let probe = ReflectionProbeNode.sphere(radius: 2.0)
            .position(.init(x: 1, y: 2, z: 3))
            .intensity(0.8)

        XCTAssertEqual(probe.entity.position.x, 1.0, accuracy: 0.001)
        XCTAssertEqual(probe.entity.position.y, 2.0, accuracy: 0.001)
        XCTAssertEqual(probe.entity.position.z, 3.0, accuracy: 0.001)
        XCTAssertTrue(probe.entity.name.contains("0.8"))
    }

    func testBoxBuilderMethodsChain() {
        let probe = ReflectionProbeNode.box(size: [4, 3, 4])
            .position(.init(x: 0, y: 1.5, z: 0))
            .intensity(1.2)

        XCTAssertEqual(probe.entity.position.y, 1.5, accuracy: 0.001)
        XCTAssertTrue(probe.entity.name.contains("1.2"))
    }

    // MARK: - Zero and negative sizes

    func testBoxWithZeroSize() {
        let probe = ReflectionProbeNode.box(size: .zero)
        XCTAssertNotNil(probe.entity)
        if case .box(let size) = probe.shape {
            XCTAssertEqual(size, .zero)
        } else {
            XCTFail("Expected box shape")
        }
    }

    func testSphereWithZeroRadius() {
        let probe = ReflectionProbeNode.sphere(radius: 0.0)
        XCTAssertNotNil(probe.entity)
        if case .sphere(let radius) = probe.shape {
            XCTAssertEqual(radius, 0.0, accuracy: 0.001)
        } else {
            XCTFail("Expected sphere shape")
        }
    }

    func testBoxWithNegativeSize() {
        let probe = ReflectionProbeNode.box(size: .init(repeating: -1.0))
        XCTAssertNotNil(probe.entity)
    }

    func testSphereWithNegativeRadius() {
        let probe = ReflectionProbeNode.sphere(radius: -1.0)
        XCTAssertNotNil(probe.entity)
    }

    // MARK: - Contains with zero-size probe

    func testZeroSizeBoxContainsOrigin() {
        let probe = ReflectionProbeNode.box(size: .zero)
        // Only the exact origin should be "inside" (half = 0, abs(0) <= 0)
        XCTAssertTrue(probe.contains(.zero))
    }

    func testZeroSizeBoxDoesNotContainOffset() {
        let probe = ReflectionProbeNode.box(size: .zero)
        XCTAssertFalse(probe.contains(.init(x: 0.001, y: 0, z: 0)))
    }

    func testZeroRadiusSphereContainsOrigin() {
        let probe = ReflectionProbeNode.sphere(radius: 0.0)
        // simd_length(.zero) = 0 <= 0
        XCTAssertTrue(probe.contains(.zero))
    }

    func testZeroRadiusSphereDoesNotContainOffset() {
        let probe = ReflectionProbeNode.sphere(radius: 0.0)
        XCTAssertFalse(probe.contains(.init(x: 0.001, y: 0, z: 0)))
    }

    // MARK: - Intensity edge cases

    func testIntensityZero() {
        let probe = ReflectionProbeNode.box(intensity: 0.0)
        XCTAssertTrue(probe.entity.name.contains("0.0"))
    }

    func testIntensityNegative() {
        let probe = ReflectionProbeNode.sphere(intensity: -1.0)
        XCTAssertNotNil(probe.entity)
    }

    func testIntensityVeryHigh() {
        let probe = ReflectionProbeNode.box(intensity: 100.0)
        XCTAssertTrue(probe.entity.name.contains("100.0"))
    }

    // MARK: - Volume size edge cases

    func testVolumeSizeForZeroBox() {
        let probe = ReflectionProbeNode.box(size: .zero)
        XCTAssertEqual(probe.volumeSize, .zero)
    }

    func testVolumeSizeForZeroSphere() {
        let probe = ReflectionProbeNode.sphere(radius: 0.0)
        XCTAssertEqual(probe.volumeSize, .zero)
    }

    // MARK: - Contains with diagonal points

    func testBoxContainsDiagonalPointInside() {
        let probe = ReflectionProbeNode.box(size: [2, 2, 2])
        // Point at (0.9, 0.9, 0.9) is inside
        XCTAssertTrue(probe.contains(.init(x: 0.9, y: 0.9, z: 0.9)))
    }

    func testBoxDoesNotContainDiagonalPointOutside() {
        let probe = ReflectionProbeNode.box(size: [2, 2, 2])
        // Point at (1.1, 0, 0) is outside
        XCTAssertFalse(probe.contains(.init(x: 1.1, y: 0, z: 0)))
    }

    func testSphereContainsDiagonalPointInside() {
        let probe = ReflectionProbeNode.sphere(radius: 2.0)
        // Point at (1, 1, 1): distance = sqrt(3) ~= 1.73, < 2.0
        XCTAssertTrue(probe.contains(.init(x: 1, y: 1, z: 1)))
    }

    func testSphereDoesNotContainDiagonalPointOutside() {
        let probe = ReflectionProbeNode.sphere(radius: 1.0)
        // Point at (1, 1, 1): distance = sqrt(3) ~= 1.73, > 1.0
        XCTAssertFalse(probe.contains(.init(x: 1, y: 1, z: 1)))
    }

    // MARK: - intensity → intensityExponent (#2956)

    /// The regression pin: the authored value is a linear multiplier, and the
    /// documented `1.0` default must be a no-op. Assigning it straight to
    /// RealityKit's `intensityExponent` rendered it at 2^1 = ×2.0.
    func testDefaultIntensityIsANoOpNotDouble() {
        XCTAssertEqual(ReflectionProbeNode.box().intensityExponent, 0.0, accuracy: 1e-6)
        XCTAssertEqual(ReflectionProbeNode.sphere().intensityExponent, 0.0, accuracy: 1e-6)
    }

    func testIntensityMultiplierMapsToPowerOfTwoExponent() {
        for (multiplier, expected) in [
            (Float(0.25), Float(-2.0)),
            (0.5, -1.0),
            (1.0, 0.0),
            (2.0, 1.0),
            (4.0, 2.0)
        ] {
            let probe = ReflectionProbeNode.box().intensity(multiplier)
            XCTAssertEqual(
                probe.intensityExponent, expected, accuracy: 1e-6,
                "multiplier \(multiplier) should map to exponent \(expected)"
            )
        }
    }

    func testFactoryIntensityMapsThroughTheSameConversion() {
        XCTAssertEqual(
            ReflectionProbeNode.box(intensity: 0.5).intensityExponent, -1.0, accuracy: 1e-6
        )
        XCTAssertEqual(
            ReflectionProbeNode.sphere(intensity: 2.0).intensityExponent, 1.0, accuracy: 1e-6
        )
    }

    func testIntensityMultiplierRoundTripsThroughTheEntityName() {
        for multiplier in [Float(0.1), 0.8, 1.2, 3.0, 100.0] {
            XCTAssertEqual(
                ReflectionProbeNode.box(intensity: multiplier).intensityMultiplier,
                multiplier, accuracy: 1e-6
            )
        }
    }

    /// RealityKit rejects a non-finite exponent, and `intensity` takes anything.
    func testDegenerateIntensitiesStayFinite() {
        for multiplier in [Float(0.0), -1.0, -.infinity, .infinity, .nan] {
            let exponent = ReflectionProbeNode.box(intensity: multiplier).intensityExponent
            XCTAssertTrue(
                exponent.isFinite, "multiplier \(multiplier) produced \(exponent)"
            )
        }
    }

    func testIntensityFallsBackToNoOpWhenTheEntityIsRenamed() {
        let probe = ReflectionProbeNode.box(intensity: 2.0)
        probe.entity.name = "custom name with no marker"
        XCTAssertEqual(probe.intensityMultiplier, 1.0, accuracy: 1e-6)
        XCTAssertEqual(probe.intensityExponent, 0.0, accuracy: 1e-6)
    }

    // MARK: - intensity reaches RealityKit (#2956)

    /// End-to-end: the multiplier authored *before* the texture must survive the
    /// component swap. It used to be dropped — `environmentTexture(_:)` installed
    /// a fresh component at exponent 0, so `box(intensity:)` never rendered.
    func testIntensitySetBeforeEnvironmentTextureIsApplied() async throws {
        let resource = try await Self.makeEnvironmentResource(named: "probe-before")
        let probe = ReflectionProbeNode.box(intensity: 4.0)
            .environmentTexture(resource)

        let ibl = try XCTUnwrap(probe.entity.components[ImageBasedLightComponent.self])
        XCTAssertEqual(ibl.intensityExponent, 2.0, accuracy: 1e-6)
    }

    func testIntensitySetAfterEnvironmentTextureIsApplied() async throws {
        let resource = try await Self.makeEnvironmentResource(named: "probe-after")
        let probe = ReflectionProbeNode.box()
            .environmentTexture(resource)
            .intensity(0.5)

        let ibl = try XCTUnwrap(probe.entity.components[ImageBasedLightComponent.self])
        XCTAssertEqual(ibl.intensityExponent, -1.0, accuracy: 1e-6)
    }

    func testDefaultIntensityLeavesTheEnvironmentUntouched() async throws {
        let resource = try await Self.makeEnvironmentResource(named: "probe-default")
        let probe = ReflectionProbeNode.sphere().environmentTexture(resource)

        let ibl = try XCTUnwrap(probe.entity.components[ImageBasedLightComponent.self])
        XCTAssertEqual(ibl.intensityExponent, 0.0, accuracy: 1e-6)
    }

    /// A gray equirectangular image is enough to build a real
    /// `EnvironmentResource` headlessly — no bundled HDR asset needed.
    private static func makeEnvironmentResource(
        named name: String
    ) async throws -> EnvironmentResource {
        let width = 64, height = 32
        let colorSpace = try XCTUnwrap(CGColorSpace(name: CGColorSpace.linearSRGB))
        let context = try XCTUnwrap(
            CGContext(
                data: nil, width: width, height: height,
                bitsPerComponent: 8, bytesPerRow: 0, space: colorSpace,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            )
        )
        context.setFillColor(CGColor(srgbRed: 0.5, green: 0.5, blue: 0.5, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let image = try XCTUnwrap(context.makeImage())
        return try await EnvironmentResource(equirectangular: image, withName: name)
    }
}
#endif
