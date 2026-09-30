import XCTest
import RealityKit
import CoreGraphics
@testable import SceneViewSwift

#if os(iOS) || os(visionOS)
// Test classes run on the main actor: their RealityKit node factories
// (`LightNode.directional`, `node.entity`, …) are `@MainActor`. (#1054)
@MainActor
final class SceneEnvironmentTests: XCTestCase {

    // MARK: - Presets

    func testAllPresetsCount() {
        // 7 presets since the "Night Sky" starfield HDRI was added (#2878
        // updated this from the stale 6; the count is the authoritative guard).
        XCTAssertEqual(SceneEnvironment.allPresets.count, 7)
    }

    func testPresetNames() {
        let names = SceneEnvironment.allPresets.map { $0.name }
        XCTAssertEqual(names, [
            "Studio", "Outdoor", "Sunset", "Night", "Warm", "Autumn", "Night Sky"
        ])
    }

    func testPresetHDRResources() {
        XCTAssertEqual(SceneEnvironment.studio.hdrResource, "studio.hdr")
        XCTAssertEqual(SceneEnvironment.outdoor.hdrResource, "outdoor_cloudy.hdr")
        XCTAssertEqual(SceneEnvironment.sunset.hdrResource, "sunset.hdr")
        XCTAssertEqual(SceneEnvironment.night.hdrResource, "rooftop_night.hdr")
        XCTAssertEqual(SceneEnvironment.warm.hdrResource, "studio_warm.hdr")
        XCTAssertEqual(SceneEnvironment.autumn.hdrResource, "autumn_field.hdr")
        XCTAssertEqual(SceneEnvironment.nightSky.hdrResource, "night_sky.hdr")
    }

    func testPresetIntensities() {
        XCTAssertEqual(SceneEnvironment.studio.intensity, 1.0)
        XCTAssertEqual(SceneEnvironment.outdoor.intensity, 1.2)
        XCTAssertEqual(SceneEnvironment.sunset.intensity, 0.8)
        XCTAssertEqual(SceneEnvironment.night.intensity, 0.4)
        XCTAssertEqual(SceneEnvironment.warm.intensity, 1.0)
        XCTAssertEqual(SceneEnvironment.autumn.intensity, 0.9)
        XCTAssertEqual(SceneEnvironment.nightSky.intensity, 0.5)
    }

    func testPresetShowsSkyboxByDefault() {
        for preset in SceneEnvironment.allPresets {
            XCTAssertTrue(preset.showSkybox, "\(preset.name) should show skybox by default")
        }
    }

    // MARK: - Custom Environment

    func testCustomEnvironment() {
        let custom = SceneEnvironment.custom(
            name: "My Env",
            hdrFile: "my_env.hdr",
            intensity: 0.7,
            showSkybox: false
        )
        XCTAssertEqual(custom.name, "My Env")
        XCTAssertEqual(custom.hdrResource, "my_env.hdr")
        XCTAssertEqual(custom.intensity, 0.7)
        XCTAssertFalse(custom.showSkybox)
    }

    func testInitWithoutHDR() {
        let env = SceneEnvironment(name: "Default")
        XCTAssertNil(env.hdrResource)
        XCTAssertEqual(env.intensity, 1.0)
        XCTAssertTrue(env.showSkybox)
    }

    // MARK: - Cache

    func testEnvironmentCacheStartsEmpty() {
        let cache = EnvironmentCache()
        XCTAssertNil(cache.get("nonexistent"))
    }

    func testEnvironmentCacheClear() {
        let cache = EnvironmentCache()
        // Just verify clear doesn't crash on empty cache
        cache.clear()
        XCTAssertNil(cache.get("anything"))
    }

    // MARK: - Mutability

    func testIntensityIsMutable() {
        var env = SceneEnvironment.studio
        env.intensity = 2.0
        XCTAssertEqual(env.intensity, 2.0)
    }

    func testShowSkyboxIsMutable() {
        var env = SceneEnvironment.studio
        env.showSkybox = false
        XCTAssertFalse(env.showSkybox)
    }

    // MARK: - visionOS immersive skybox (#1235)

    func testImmersiveSpaceModifierDefaultsOff() {
        // Without the modifier, a SceneView keeps passthrough on visionOS.
        let view = SceneView { _ in }
        XCTAssertFalse(view.immersiveSpace)
    }

    func testImmersiveSpaceModifierOptIn() {
        let view = SceneView { _ in }.immersiveSpace()
        XCTAssertTrue(view.immersiveSpace)
    }

    func testImmersiveSpaceModifierExplicitFalse() {
        let view = SceneView { _ in }.immersiveSpace(false)
        XCTAssertFalse(view.immersiveSpace)
    }

    func testImmersiveSpaceModifierIsCopyOnWrite() {
        // The modifier must not mutate the receiver — it returns a copy.
        let base = SceneView { _ in }
        _ = base.immersiveSpace()
        XCTAssertFalse(base.immersiveSpace)
    }

    // MARK: - intensity → intensityExponent (#2897)

    /// The whole point of the fix: `intensity` is authored and documented as a
    /// LINEAR multiplier, RealityKit's parameter is a power-of-two exponent, and
    /// the old code fed one straight into the other. Pin the round-trip
    /// `2^exponent == multiplier` rather than the exponent's literal value, so
    /// this fails if anyone ever passes the multiplier through again.
    func testIntensityExponentRoundTripsToTheAuthoredMultiplier() {
        for multiplier in [Float(0.4), 0.5, 0.8, 0.9, 1.0, 1.2, 2.0] {
            let exponent = SceneEnvironment.intensityExponent(forMultiplier: multiplier)
            XCTAssertEqual(
                pow(Float(2), exponent), multiplier, accuracy: 1e-5,
                "2^exponent must reproduce the authored multiplier \(multiplier)"
            )
        }
    }

    /// `1.0` — the default, and `.studio`'s value — must be a true no-op. Passing
    /// the multiplier through gave exponent 1.0 here, i.e. ×2.0 on the DEFAULT
    /// preset; the fix makes it exponent 0.
    func testUnitIntensityIsANoOp() {
        XCTAssertEqual(
            SceneEnvironment.intensityExponent(forMultiplier: 1.0), 0.0, accuracy: 1e-6
        )
    }

    /// `.night` is the case that was not merely wrong but inverted: authored 0.4
    /// to DIM, it brightened ×1.32 under the old code. The exponent must be
    /// negative for every sub-1.0 preset.
    func testSubUnitPresetsProduceNegativeExponents() {
        for preset in SceneEnvironment.allPresets where preset.intensity < 1.0 {
            XCTAssertLessThan(
                SceneEnvironment.intensityExponent(forMultiplier: preset.intensity), 0.0,
                "\(preset.name) (intensity \(preset.intensity)) must dim, not brighten"
            )
        }
    }

    /// `log2(0)` is `-inf`, which RealityKit rejects. Zero, negatives and `-inf`
    /// must clamp to something finite and effectively black.
    func testNonPositiveIntensityStaysFinite() {
        for multiplier in [Float(0.0), -0.0, -1.0, -Float.leastNormalMagnitude, -Float.infinity] {
            let exponent = SceneEnvironment.intensityExponent(forMultiplier: multiplier)
            XCTAssertTrue(exponent.isFinite, "exponent for \(multiplier) must be finite")
            XCTAssertLessThan(exponent, -100.0, "exponent for \(multiplier) must be ~black")
        }
    }

    /// `intensity` is a `public var`, so nothing stops a caller writing `.nan` or
    /// `.infinity` into it — and RealityKit rejects a non-finite exponent. The
    /// naive `log2(max(multiplier, .leastNormalMagnitude))` passed both straight
    /// through: Swift's generic `max` is `y >= x ? y : x` and NaN compares false
    /// against everything, so `max(.nan, tiny)` is `.nan` (measured). Every input
    /// must produce a finite exponent.
    func testNonFiniteIntensityStaysFinite() {
        XCTAssertLessThan(
            SceneEnvironment.intensityExponent(forMultiplier: .nan), -100.0,
            "NaN must map to black, not propagate"
        )
        XCTAssertLessThan(
            SceneEnvironment.intensityExponent(forMultiplier: .signalingNaN), -100.0,
            "signalling NaN must map to black, not propagate"
        )
        let positiveInfinity = SceneEnvironment.intensityExponent(forMultiplier: .infinity)
        XCTAssertTrue(positiveInfinity.isFinite, "+infinity must clamp to a finite exponent")
        XCTAssertGreaterThan(positiveInfinity, 0.0, "+infinity must stay on the bright side")
    }
}

#if os(visionOS)
@MainActor
final class VisionOSSkyboxTests: XCTestCase {

    func testMakeHostCarriesWorldComponentAndMarker() async throws {
        // A 1×1 white texture stands in for the equirectangular HDR — the
        // host-construction path is texture-agnostic.
        let texture = try await TextureResource(
            image: makeWhitePixel(),
            withName: "skybox-test-pixel",
            options: .init(semantic: .hdrColor)
        )
        let host = VisionOSSkybox.makeHost(texture: texture, hdrResource: "studio.hdr")
        // Marker stamps the HDR resource for the update: diff.
        let marker = host.components[VisionOSSkybox.Marker.self]
        XCTAssertEqual(marker?.hdrResource, "studio.hdr")
        // WorldComponent places the subtree in absolute world space.
        XCTAssertNotNil(host.components[WorldComponent.self])
        // Exactly one inverted-sphere child.
        XCTAssertEqual(host.children.count, 1)
        XCTAssertEqual(host.children.first?.scale, [-1, 1, 1])
    }

    func testLoadEquirectangularTextureMissingFileReturnsNil() async {
        // Graceful degradation: an unresolvable HDR returns nil instead of
        // throwing, so the immersive background just stays neutral.
        let texture = await VisionOSSkybox.loadEquirectangularTexture(
            named: "does-not-exist-\(UUID().uuidString).hdr"
        )
        XCTAssertNil(texture)
    }

    private func makeWhitePixel() -> CGImage {
        let space = CGColorSpaceCreateDeviceRGB()
        let ctx = CGContext(
            data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
            space: space,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )!
        ctx.setFillColor(red: 1, green: 1, blue: 1, alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
        return ctx.makeImage()!
    }
}
#endif
#endif
