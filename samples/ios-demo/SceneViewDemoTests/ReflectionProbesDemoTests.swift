import XCTest
import RealityKit

@testable import SceneViewDemo
@testable import SceneViewSwift

/// Pins that the probe built through the demo path actually carries an IBL.
///
/// `ReflectionProbeNode.intensity` only reaches RealityKit through the
/// `ImageBasedLightComponent` that `environmentTexture(_:)` installs. The demo
/// used to build the probe without a texture, so the Intensity slider rebuilt
/// an identical scene on every step (#3158).
@MainActor
final class ReflectionProbesDemoTests: XCTestCase {

    func testDemoProbeCarriesAnImageBasedLightAtTheSliderIntensity() async throws {
        let environment = try await Self.makeEnvironmentResource(named: "demo-probe")
        let probe = ReflectionProbesDemo.makeProbe(intensity: 2.0, environment: environment)

        let ibl = try XCTUnwrap(probe.entity.components[ImageBasedLightComponent.self])
        // Linear multiplier 2.0 → RealityKit exponent 1 (#2956).
        XCTAssertEqual(ibl.intensityExponent, 1.0, accuracy: 1e-6)
        XCTAssertNotNil(probe.entity.components[ImageBasedLightReceiverComponent.self])
    }

    func testDemoProbeWithoutALoadedEnvironmentHasNoLightYet() {
        // Before the async load lands the probe is still an empty entity; the
        // view key flips once `probeEnvironment` is set so the scene rebuilds.
        let probe = ReflectionProbesDemo.makeProbe(intensity: 1.0, environment: nil)
        XCTAssertNil(probe.entity.components[ImageBasedLightComponent.self])
    }

    func testReflectiveGeometryReceivesTheProbeNotTheGlobalIBL() async throws {
        let environment = try await Self.makeEnvironmentResource(named: "demo-probe-receiver")
        let probe = ReflectionProbesDemo.makeProbe(intensity: 1.0, environment: environment)
        let sphere = Entity()

        ReflectionProbesDemo.attach(sphere, to: probe)

        let receiver = try XCTUnwrap(sphere.components[ImageBasedLightReceiverComponent.self])
        XCTAssertTrue(receiver.imageBasedLight === probe.entity)
    }

    /// A flat gray equirectangular image is enough to build a real
    /// `EnvironmentResource` headlessly — same trick as `ReflectionProbeNodeTests`.
    private static func makeEnvironmentResource(named name: String) async throws -> EnvironmentResource {
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
