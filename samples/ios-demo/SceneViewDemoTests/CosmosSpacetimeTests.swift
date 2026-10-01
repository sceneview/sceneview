#if DEBUG
import XCTest
import simd
@testable import SceneViewDemo

/// The Spacetime mode's shared contract with Android (`CosmosSpacetimeTest.kt`): the same field,
/// the same reference heights and rests at t = 9 s, the same grid, and the same framing.
final class CosmosSpacetimeTests: XCTestCase {

    private typealias S = CosmosSpacetime
    private let qa = 9.0
    private let tolerance = 1e-3

    // MARK: Reference values

    func testGoldenHeights() {
        let sheet = S.Sheet(time: qa, weight: 1)
        let golden: [(Double, Double, Double)] = [
            (0, 0, -0.3375), (1, 0, 0.2812), (0, -2, 0.8139), (-3, 1.5, 1.2977),
            (4, 4, 1.7000), (6, -6, 1.9835), (10.5, 0, 2.0882), (12, 0, 2.1321),
        ]
        for (x, z, h) in golden {
            XCTAssertEqual(sheet.height(x, z), h, accuracy: tolerance, "H(\(x), \(z))")
        }
        // At the rim the field is windowed to zero: the sheet is the offset alone.
        XCTAssertEqual(sheet.height(12, 0), sheet.offset, accuracy: 1e-12)
    }

    func testGoldenRests() {
        let sheet = S.Sheet(time: qa, weight: 1)
        let golden: [(S.Body, SIMD2<Double>, Double)] = [
            (.ember, SIMD2(-1.2185, 0.4531), -0.0599),
            (.azure, SIMD2(-1.649, 1.131), 0.4144),
            (.ringed, SIMD2(1.600, -2.886), 1.0843),
            (.ochre, SIMD2(-4.330, -2.695), 1.2130),
            (.ice, SIMD2(5.116, 4.629), 1.4937),
            (.moonI, SIMD2(5.602, 4.982), 1.4382),
            (.moonO, SIMD2(-3.830, -2.007), 0.9509),
        ]
        for (body, p, y) in golden {
            let at = S.position(body, time: qa)
            XCTAssertEqual(at.x, p.x, accuracy: tolerance, "\(body) x")
            XCTAssertEqual(at.y, p.y, accuracy: tolerance, "\(body) z")
            XCTAssertEqual(sheet.rest(body, at: at), y, accuracy: tolerance, "\(body) rest")
        }
    }

    /// The star's centre stays at the origin whatever the mass weight: the sheet moves, not it.
    func testStarStaysAtOrigin() {
        for w in stride(from: 0.0, through: 1.05, by: 0.05) {
            let sheet = S.Sheet(time: qa, weight: w)
            XCTAssertEqual(sheet.rest(.star, at: .zero), 0, accuracy: 1e-12, "w \(w)")
        }
        XCTAssertEqual(S.Sheet(time: qa, weight: 1).offset, 2.1321, accuracy: tolerance)
    }

    /// The ringed world keeps Starlight's orbit: entering Spacetime does not make it jump.
    func testRingedWorldKeepsStarlightOrbit() {
        for t in stride(from: 0.0, through: 120, by: 7.5) {
            let a = Double(CosmosSystem.orbitAngle(Float(t)))
            let p = S.position(.ringed, time: t)
            XCTAssertEqual(p.x, 3.3 * cos(a), accuracy: 1e-4)
            XCTAssertEqual(p.y, -3.3 * sin(a), accuracy: 1e-4)
        }
    }

    // MARK: Field

    func testGradientMatchesFiniteDifferences() {
        let field = S.Field(time: qa, weight: 1)
        let h = 1e-5
        let points: [SIMD2<Double>] = [
            SIMD2(1, 0), SIMD2(0, -2), SIMD2(-3, 1.5), SIMD2(4, 4), SIMD2(6, -6),
            SIMD2(10.5, 0), SIMD2(-7, 8.1), SIMD2(0.3, 0.2),
        ]
        for p in points {
            let g = field.gradient(p.x, p.y)
            let gx = (field.height(p.x + h, p.y) - field.height(p.x - h, p.y)) / (2 * h)
            let gz = (field.height(p.x, p.y + h) - field.height(p.x, p.y - h)) / (2 * h)
            XCTAssertEqual(g.x, gx, accuracy: 1e-6, "∂x at \(p)")
            XCTAssertEqual(g.y, gz, accuracy: 1e-6, "∂z at \(p)")
        }
    }

    /// Every body sits in its own dip: walking downhill from its centre ends within its radius,
    /// at every moment of ten minutes.
    func testEveryBodySitsInItsOwnWell() {
        var worst: [S.Body: Double] = [:]
        for step in 0...1200 {
            let t = Double(step) * 0.5
            let field = S.Field(time: t, weight: 1)
            for body in S.Body.moving {
                let rho = body.spec.radius
                let c = S.position(body, time: t)
                let end = descend(field, from: c, radius: rho)
                let off = simd_length(end - c) / rho
                worst[body] = max(worst[body] ?? 0, off)
            }
        }
        for (body, off) in worst {
            XCTAssertLessThan(off, 1, "\(body)'s minimum drifts \(off) radii")
        }
    }

    /// Steepest descent with a halving line search.
    private func descend(_ field: S.Field, from c: SIMD2<Double>, radius: Double) -> SIMD2<Double> {
        var p = c
        var h = field.height(p.x, p.y)
        var step = 0.05 * radius
        for _ in 0..<4000 {
            let g = field.gradient(p.x, p.y)
            let length = simd_length(g)
            if length < 1e-9 { break }
            let next = p - step * g / length
            let hn = field.height(next.x, next.y)
            if hn < h {
                p = next
                h = hn
            } else {
                step /= 2
                if step < 1e-4 * radius { break }
            }
            if simd_length(p - c) > 2 * radius { break }
        }
        return p
    }

    func testRingsClearTheSheet() {
        var worst = Double.infinity
        for step in 0..<240 {
            let t = Double(step) * 0.5
            let sheet = S.Sheet(time: t, weight: 1)
            let p = S.position(.ringed, time: t)
            let y = sheet.rest(.ringed, at: p)
            worst = min(worst, sheet.ringGap(center: SIMD3(p.x, y, p.y)))
        }
        XCTAssertGreaterThan(worst, 0.004)
    }

    func testBodiesNeverTouch() {
        let bodies = S.Body.moving
        var gap = Double.infinity
        for step in 0...2400 {
            let t = Double(step) * 0.25
            for (i, a) in bodies.enumerated() {
                for b in bodies[(i + 1)...] {
                    let rings = (a == .ringed || b == .ringed) ? Double(CosmosSystem.ringOuter) - 0.3 : 0
                    let d = simd_length(S.position(a, time: t) - S.position(b, time: t))
                    gap = min(gap, d - a.spec.radius - b.spec.radius - rings)
                }
            }
        }
        XCTAssertGreaterThan(gap, 0)
    }

    // MARK: Grid and light

    func testGrid() {
        XCTAssertEqual(S.gridRadii.count, 95)
        XCTAssertEqual(S.vertexCount, 39_520)
        XCTAssertEqual(S.triangleCount, 78_208)
        XCTAssertEqual(S.gridRadii.first, 0)
        XCTAssertEqual(S.gridRadii[76], 7.6, accuracy: 1e-5)
        XCTAssertEqual(S.horizonDistances.count, 200)
        XCTAssertEqual(S.horizonDistances.last!, 6, accuracy: 1e-9)
        XCTAssertEqual(S.gridRadii.last!, 12, accuracy: 1e-4)
        XCTAssertLessThan(S.vertexCount, Int(UInt16.max) + 1, "indices fit in 16 bits")
        // The horizon overlay repeats the rings out to the map's corners, and still fits.
        XCTAssertGreaterThanOrEqual(Double(S.gridRadii[CosmosSpacetimeFill.overlayRings - 1]), 4 * 2.0.squareRoot())
        XCTAssertLessThan(Double(S.gridRadii[CosmosSpacetimeFill.overlayRings - 2]), 4 * 2.0.squareRoot())
        XCTAssertLessThan(S.vertexCount + CosmosSpacetimeFill.overlayCount, Int(UInt16.max) + 1)
    }

    // MARK: Horizon map

    /// Android's map: 512² over ±4, texel centres at ±(4 − 4/512).
    func testHorizonMapLayout() {
        XCTAssertEqual(S.horizonMapSize, 512)
        XCTAssertEqual(S.horizonMapExtent, 4)
        XCTAssertEqual(S.horizonTexel(0), -4 + 4.0 / 512, accuracy: 1e-6)
        XCTAssertEqual(S.horizonTexel(511), 4 - 4.0 / 512, accuracy: 1e-6)
    }

    /// The baked march (floats, the star's tabulated well, coarse steps then fine round the
    /// peak, early exits) is the plain every-step march on the exact well, within 1/255.
    func testHorizonBakeMatchesDenseMarch() {
        var worst: Double = 0
        var shadowed = 0
        for j in stride(from: 0, to: S.horizonMapSize, by: 3) {
            for i in stride(from: 0, to: S.horizonMapSize, by: 3) {
                let x = S.horizonTexel(i), z = S.horizonTexel(j)
                let baked = Double(S.horizonVisibility(x, z))
                let exact = S.horizon(Double(x), Double(z), star: S.starField)
                worst = max(worst, abs(baked - exact))
                if exact < 0.5 { shadowed += 1 }
            }
        }
        XCTAssertLessThan(worst, 1.0 / 255)
        XCTAssertGreaterThan(shadowed, 500, "the samples cross the lobe")
    }

    /// The lobe lies inside the map: its border texels are lit.
    func testHorizonMapBorderIsLit() {
        let n = S.horizonMapSize
        for k in stride(from: 0, to: n, by: 4) {
            for (i, j) in [(k, 0), (k, n - 1), (0, k), (n - 1, k)] {
                XCTAssertEqual(S.horizonVisibility(S.horizonTexel(i), S.horizonTexel(j)), 1, accuracy: 1e-6)
            }
        }
    }

    /// `horizonMap()` itself — the concurrent bands and the byte packing — is the march, texel
    /// for texel, and clear on its border: Android's test, on the same texels.
    func testHorizonMapIsTheMarchTexelForTexel() {
        let n = S.horizonMapSize
        let map = S.horizonMap()
        XCTAssertEqual(map.count, n * n)
        func texel(_ i: Int, _ j: Int) -> Float { Float(map[j * n + i]) / 255 }
        for (i, j) in [(0, 0), (300, 220), (330, 200), (260, 270), (511, 400), (150, 256)] {
            let expected = S.horizonVisibility(S.horizonTexel(i), S.horizonTexel(j))
            XCTAssertEqual(texel(i, j), expected, accuracy: 1 / 255, "texel (\(i), \(j))")
        }
        // Clamp-to-edge repeats the border past the map: it must be fully lit.
        for k in 0..<n {
            for (i, j) in [(k, 0), (k, n - 1), (0, k), (n - 1, k)] {
                XCTAssertEqual(texel(i, j), 1, accuracy: 1e-6)
            }
        }
        // Row j is z = texel(j), column i is x = texel(i): the hollow on the light's side
        // (+x, −z) is dark, the wall facing the light (−x, +z) is lit.
        let ground = simd_normalize(SIMD2(Float(S.light.x), Float(S.light.z)))
        func at(_ p: SIMD2<Float>) -> Float {
            let i = Int((p.x / S.horizonMapExtent + 1) * Float(n) / 2)
            let j = Int((p.y / S.horizonMapExtent + 1) * Float(n) / 2)
            return texel(i, j)
        }
        XCTAssertLessThan(at(1.2 * ground), 0.01)
        XCTAssertGreaterThan(at(-1.2 * ground), 0.99)
    }

    /// The overlay's `u, v` land on the texel baked for that (x, z), and the overlay image
    /// covers the dark side and leaves the lit side clear: a flipped or mirrored axis fails.
    func testOverlayUVAddressesTheBakedTexel() {
        let n = S.horizonMapSize
        // RealityKit samples image row (1 − v) n: v runs up from the last row.
        func texel(_ uv: SIMD2<Float>) -> (i: Int, j: Int) {
            (Int((uv.x * Float(n)).rounded(.down)), Int(((1 - uv.y) * Float(n)).rounded(.down)))
        }
        for (i, j) in [(0, 0), (300, 220), (330, 200), (511, 400), (17, 498)] {
            let uv = CosmosSpacetimeFill.horizonUV(S.horizonTexel(i), S.horizonTexel(j))
            XCTAssertEqual(uv.x, (Float(i) + 0.5) / Float(n), accuracy: 1e-6)
            XCTAssertEqual(uv.y, 1 - (Float(j) + 0.5) / Float(n), accuracy: 1e-6)
            let hit = texel(uv)
            XCTAssertEqual(hit.i, i)
            XCTAssertEqual(hit.j, j)
        }
        let image = CosmosSpacetimeScene.horizonImage()
        XCTAssertEqual(image.width, n)
        XCTAssertEqual(image.height, n)
        func alpha(_ x: Float, _ z: Float) -> Float {
            let t = texel(CosmosSpacetimeFill.horizonUV(x, z))
            return image.pixels[(t.j * n + t.i) * 4 + 3]
        }
        let ground = simd_normalize(SIMD2(Float(S.light.x), Float(S.light.z)))
        // In the hollow's shadow the overlay is opaque; on the lit wall it is clear.
        XCTAssertGreaterThan(alpha(1.2 * ground.x, 1.2 * ground.y), 0.99)
        XCTAssertLessThan(alpha(-1.2 * ground.x, -1.2 * ground.y), 0.01)
        // Premultiplied: colour = base × b₀ × (1 − v), alpha = 1 − v, on every sampled texel.
        let base = SIMD3<Float>(S.linear(S.baseColor))
        let map = S.horizonMap()
        for j in stride(from: 0, to: n, by: 37) {
            for i in stride(from: 0, to: n, by: 29) {
                let x = Double(S.horizonTexel(i)), z = Double(S.horizonTexel(j))
                let cover = 1 - Float(map[j * n + i]) / 255
                let k = (j * n + i) * 4
                XCTAssertEqual(image.pixels[k + 3], cover, accuracy: 1e-6)
                let b0 = Float(S.hiddenBrightness(distance: (x * x + z * z).squareRoot()))
                XCTAssertEqual(image.pixels[k], base.x * b0 * cover, accuracy: 1e-5)
                XCTAssertEqual(image.pixels[k + 2], base.z * b0 * cover, accuracy: 1e-5)
            }
        }
    }

    func testLight() {
        XCTAssertEqual(S.light.x, 0.7948, accuracy: 1e-4)
        XCTAssertEqual(S.light.y, 0.2419, accuracy: 1e-4)
        XCTAssertEqual(S.light.z, -0.5565, accuracy: 1e-4)
        XCTAssertEqual(S.flatShade, 0.3246, accuracy: 1e-4)
    }

    /// The Sun's hollow is in its own shadow: the far wall facing away from the light is dark.
    func testHollowIsDark() {
        let sheet = S.Sheet(time: qa, weight: 1)
        // Just past the star on the light's side, the slope faces away from it and the rim hides it.
        let ground = simd_normalize(SIMD2(S.light.x, S.light.z))
        let p = 0.6 * ground
        let shade = S.shade(p.x, p.y, sheet: sheet)
        XCTAssertLessThan(S.brightness(shade: shade, distance: simd_length(p)), 0.6 / 12 * 1.5)
        // Far out on open ground the sheet sits near its base level.
        let far = SIMD2(-3.0, 2.0)
        let open = S.brightness(shade: S.shade(far.x, far.y, sheet: sheet), distance: simd_length(far))
        XCTAssertGreaterThan(open, 0.4)
    }

    // MARK: Framing

    /// The contract's own lens: the star is 10 % of the width at 58 % of the height, Ochre's
    /// front clears the bottom band, and the far side of its orbit stays on screen.
    func testContractFraming() {
        let tanV = S.contractTanHalfVertical
        let tall = frame(aspect: 9 / 19.5, tanV: tanV)
        XCTAssertEqual(tall.star, 0.1002, accuracy: 1e-3)
        XCTAssertEqual(tall.height, 0.58, accuracy: 1e-3)
        XCTAssertEqual(tall.front, 0.7097, accuracy: 1e-3)
        XCTAssertEqual(tall.back, 0.358, accuracy: 1e-3)
        XCTAssertEqual(S.distance(aspect: 9 / 19.5, tanHalfVertical: tanV), 22.75, accuracy: 1e-2)
        let short = frame(aspect: 9 / 16, tanV: tanV)
        XCTAssertEqual(short.front, 0.7491, accuracy: 1e-3)
        XCTAssertEqual(S.distance(aspect: 9 / 16, tanHalfVertical: tanV), 18.667, accuracy: 1e-2)
    }

    /// iOS frames the same rectangle through SceneView's 60° lens.
    func testIOSFraming() {
        let tanV = Double(CosmosFraming.tanHalfVerticalFov)
        let tall = frame(aspect: 9 / 19.5, tanV: tanV)
        XCTAssertEqual(tall.star, 0.1004, accuracy: 1e-3)
        XCTAssertEqual(tall.height, 0.58, accuracy: 1e-3)
        XCTAssertEqual(tall.front, 0.7259, accuracy: 1e-3)
        XCTAssertEqual(tall.back, 0.366, accuracy: 1e-3)
        XCTAssertEqual(frame(aspect: 9 / 16, tanV: tanV).front, 0.7775, accuracy: 1e-3)
        let pro = frame(aspect: 1206.0 / 2622.0, tanV: tanV)
        XCTAssertEqual(pro.front, 0.725, accuracy: 1e-3)
        XCTAssertEqual(pro.star, 0.10, accuracy: 2e-3)
        XCTAssertLessThanOrEqual(pro.star, 0.12)
        XCTAssertEqual(pro.height, 0.58, accuracy: 0.02)
    }

    private func frame(aspect: Double, tanV: Double) -> (star: Double, height: Double, front: Double, back: Double) {
        let pose = S.pose(aspect: aspect, tanHalfVertical: tanV)
        let sheet = S.Sheet(time: qa, weight: 1)
        let star = S.project(.zero, pose: pose, aspect: aspect, tanHalfVertical: tanV)
        let diameter = S.Body.star.spec.radius / (star.z * tanV * aspect)
        let r = 5.1
        let front = S.project(SIMD3(0, sheet.height(0, r) + 0.36 * 0.75, r), pose: pose, aspect: aspect, tanHalfVertical: tanV)
        let back = S.project(SIMD3(0, sheet.height(0, -r) + 0.3, -r), pose: pose, aspect: aspect, tanHalfVertical: tanV)
        return (diameter, star.y, front.y, back.y)
    }

    // MARK: Transition

    func testTimeline() {
        typealias T = S.Timeline
        XCTAssertEqual(T.weight(0.6), 0)
        XCTAssertEqual(T.weight(T.weightPeak), 1.05, accuracy: 1e-12)
        XCTAssertEqual(T.weight(2.2), 1)
        XCTAssertEqual(T.sheetIntensity(0.2), 0)
        XCTAssertEqual(T.sheetIntensity(0.9), 1)
        XCTAssertEqual(T.haloAlpha(0.35), 0)
        XCTAssertEqual(T.lift(0), S.lift)
        XCTAssertEqual(T.lift(0.9), 0)
        XCTAssertEqual(T.starField(1.9), 0)
        XCTAssertEqual(T.drop(.ember, 0.79).alpha, 0)
        XCTAssertEqual(T.drop(.ember, 1.3).above, 0)
        XCTAssertEqual(T.drop(.moonI, 1.25).above, 1)
        XCTAssertEqual(T.drop(.ringed, 0).alpha, 1)
    }

    // MARK: Picker

    /// The mode picker's palette is fixed: the container stands out from both themes' chrome
    /// and every label reads at ≥ 4.5:1, the outline at ≥ 3:1.
    func testPickerContrast() {
        typealias Pill = SceneViewTokens.ModePill
        XCTAssertGreaterThanOrEqual(S.contrast(Pill.rgb(Pill.selectedContainerRGB), Pill.rgb(Pill.onSelectedRGB)), 4.5)
        XCTAssertGreaterThanOrEqual(S.contrast(Pill.rgb(Pill.containerRGB), Pill.rgb(Pill.onContainerRGB)), 4.5)
        XCTAssertGreaterThanOrEqual(S.contrast(Pill.rgb(Pill.outlineRGB), Pill.rgb(Pill.containerRGB)), 3)
        XCTAssertGreaterThanOrEqual(S.contrast(Pill.rgb(Pill.selectedContainerRGB), Pill.rgb(Pill.containerRGB)), 3)
        // Android's `SceneViewTokens.ModePill`, value for value.
        XCTAssertEqual(Pill.containerRGB, 0x1A1F28)
        XCTAssertEqual(Pill.outlineRGB, 0xD1D2D4)
        XCTAssertEqual(Pill.onSelectedRGB, 0x0B0F16)
        // Segment plus inset = the 48 pt touch target, so the whole pill height is tappable.
        XCTAssertEqual(Pill.segmentHeight + 2 * SceneViewTokens.Space.xs, SceneViewTokens.Layout.touchTarget)
    }

    // MARK: Fill

    /// The sheet's float fill reproduces the double model, the horizon left to the overlay.
    func testFillMatchesModel() {
        let fill = CosmosSpacetimeFill()
        let weight = 1.0
        fill.fill(time: qa, weight: weight, lift: 0, casters: [], ring: nil, into: nil)
        let sheet = S.Sheet(time: qa, weight: weight)
        let sectors = S.gridSectors
        var worstHeight = 0.0, worstShade = 0.0
        for ring in stride(from: 0, to: S.gridRadii.count, by: 3) {
            for sector in stride(from: 0, to: sectors, by: 7) {
                let i = ring * sectors + sector
                let x = Double(fill.x[i]), z = Double(fill.z[i])
                worstHeight = max(worstHeight, abs(Double(fill.height[i]) - sheet.height(x, z)))
                let d = (x * x + z * z).squareRoot()
                let k = S.brightness(shade: S.shade(x, z, sheet: sheet, visibility: 1), distance: d)
                worstShade = max(worstShade, abs(Double(fill.brightness[i]) - k))
            }
        }
        XCTAssertLessThan(worstHeight, 2e-3)
        XCTAssertLessThan(worstShade, 1e-2)
    }

    // MARK: Deep link

    /// `?tab=` goes through the router to Cosmos once, in Android's names or indices, and a tab
    /// meant for another demo is left for that demo — Cosmos watches the key while on screen.
    @MainActor
    func testTabDeepLink() throws {
        let key = DeepLinkRouter.tabDefaultsKey
        defer { UserDefaults.standard.removeObject(forKey: key) }
        let url = try XCTUnwrap(URL(string: "sceneview://demo/cosmos?tab=Spacetime"))
        XCTAssertEqual(DeepLinkRouter.parse(url, allowedDemos: ["cosmos"]), "cosmos")
        XCTAssertEqual(UserDefaults.standard.string(forKey: key), "cosmos:Spacetime")
        XCTAssertEqual(CosmosDemo.consumeRequestedSpacetime(), true)
        XCTAssertNil(UserDefaults.standard.string(forKey: key), "taken once")
        XCTAssertNil(CosmosDemo.consumeRequestedSpacetime())

        for (tab, spacetime) in [("starlight", false), ("0", false), ("1", true), ("SPACETIME", true)] {
            DeepLinkRouter.setTab(tab, for: "cosmos")
            XCTAssertEqual(CosmosDemo.consumeRequestedSpacetime(), spacetime, tab)
        }
        DeepLinkRouter.setTab("orbit", for: "cosmos")
        XCTAssertNil(CosmosDemo.consumeRequestedSpacetime(), "an unknown tab opens the default view")

        // A link without `tab` clears one left over.
        DeepLinkRouter.setTab("spacetime", for: "cosmos")
        XCTAssertEqual(DeepLinkRouter.parse(URL(string: "sceneview://demo/cosmos"), allowedDemos: ["cosmos"]), "cosmos")
        XCTAssertNil(DeepLinkRouter.consumeTab(for: "cosmos"))

        // Another demo's tab survives Cosmos's read.
        DeepLinkRouter.setTab("depth", for: "ar-debug")
        XCTAssertNil(DeepLinkRouter.consumeTab(for: "cosmos"))
        XCTAssertEqual(DeepLinkRouter.consumeTab(for: "ar-debug"), "depth")
    }
}
#endif
