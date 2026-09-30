import XCTest
import RealityKit

@testable import SceneViewDemo
@testable import SceneViewSwift

/// Pins the Lighting Lab's local-reflection path — the part that used to be a fake.
///
/// The screen this replaced built its probe from the global HDR and put every mesh inside it,
/// so switching the probe changed nothing (`parity: fake`). These tests hold the three things
/// that make the iOS probe real: the probe carries its own IBL at the slider's intensity, the
/// whole stage subtree receives it, and the enter/exit test is the camera-in-sphere rule.
@MainActor
final class LightingLabDemoTests: XCTestCase {

    func testProbeCarriesItsOwnImageBasedLightAtTheSliderIntensity() async throws {
        let environment = try await Self.makeEnvironmentResource(named: "lab-probe")
        let probe = LightingLabDemo.makeProbe(radius: 2.5, intensity: 2.0, environment: environment)

        let ibl = try XCTUnwrap(probe.entity.components[ImageBasedLightComponent.self])
        // Linear multiplier 2.0 -> RealityKit exponent 1 (#2956).
        XCTAssertEqual(ibl.intensityExponent, 1.0, accuracy: 1e-6)
        XCTAssertEqual(probe.entity.position, LightingLabDemo.probeCentre)
    }

    func testProbeWithoutALoadedEnvironmentHasNoLightYet() {
        let probe = LightingLabDemo.makeProbe(radius: 2.5, intensity: 1.0, environment: nil)
        XCTAssertNil(probe.entity.components[ImageBasedLightComponent.self])
    }

    func testTheWholeStageSubtreeReceivesTheProbeNotTheGlobalIBL() async throws {
        let environment = try await Self.makeEnvironmentResource(named: "lab-probe-receiver")
        let probe = LightingLabDemo.makeProbe(radius: 2.5, intensity: 1.0, environment: environment)
        let stage = Entity()
        let hero = Entity()
        let heroMesh = Entity()
        hero.addChild(heroMesh)
        stage.addChild(hero)

        LightingLabDemo.attach(stage, to: probe)

        for entity in [stage, hero, heroMesh] {
            let receiver = try XCTUnwrap(entity.components[ImageBasedLightReceiverComponent.self])
            XCTAssertTrue(receiver.imageBasedLight === probe.entity)
        }
    }

    func testCameraInsideTheSphereGetsTheOverrideAndOutsideDoesNot() {
        let camera = SIMD3<Float>(0, 0.6, 1.8)   // ~1.9 m from the centre
        XCTAssertTrue(LightingLabDemo.zoneContains(camera: camera, radius: 2.5))
        XCTAssertFalse(LightingLabDemo.zoneContains(camera: camera, radius: 1.5))
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
