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
        for j in stride(from: 3, to: S.horizonMapSize, by: 11) {
            for i in stride(from: 5, to: S.horizonMapSize, by: 11) {
                let x = S.horizonTexel(i), z = S.horizonTexel(j)
                let baked = Double(S.horizonVisibility(x, z))
                let exact = S.horizon(Double(x), Double(z), star: S.starField)
                worst = max(worst, abs(baked - exact))
                if exact < 0.5 { shadowed += 1 }
            }
        }
        XCTAssertLessThan(worst, 1.0 / 255)
        XCTAssertGreaterThan(shadowed, 50, "the samples cross the lobe")
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

    /// The overlay's blend, sheet b with visibility v over ambient-only b₀ — b v + b₀ (1 − v) —
    /// is Android's per-pixel shade wherever the ceiling does not bind.
    func testOverlayBlendIsAndroidShade() {
        let sheet = S.Sheet(time: qa, weight: 1)
        var checked = 0
        for x in stride(from: -3.9, through: 3.9, by: 0.37) {
            for z in stride(from: -3.9, through: 3.9, by: 0.41) {
                let d = (x * x + z * z).squareRoot()
                let v = Double(S.horizonVisibility(Float(x), Float(z)))
                let open = S.shade(x, z, sheet: sheet, visibility: 1)
                guard S.gain * open / S.flatShade < S.ceiling else { continue }
                let b = S.brightness(shade: open, distance: d)
                let android = S.brightness(shade: S.shade(x, z, sheet: sheet, visibility: v), distance: d)
                XCTAssertEqual(b * v + S.hiddenBrightness(distance: d) * (1 - v), android, accuracy: 1e-9)
                checked += 1
            }
        }
        XCTAssertGreaterThan(checked, 300)
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
        XCTAssertGreaterThanOrEqual(S.contrast(S.pillSelected, S.pillSelectedText), 4.5)
        XCTAssertGreaterThanOrEqual(S.contrast(S.pillContainer, SIMD3(1, 1, 1)), 4.5)
        XCTAssertGreaterThanOrEqual(S.contrast(S.pillOutline, S.pillContainer), 3)
        XCTAssertGreaterThanOrEqual(S.contrast(S.pillSelected, S.pillContainer), 3)
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
}
#endif
