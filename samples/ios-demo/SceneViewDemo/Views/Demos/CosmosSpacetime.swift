import Foundation
import simd

// The Star scene's Spacetime mode: the star and its planets resting in wells they dig into a
// fabric — the Swift twin of Android's `CosmosSpacetime.kt`, with the same constants and the
// same reference values (`CosmosSpacetimeTests`). Pure functions of time, mass weight and
// viewport, in doubles; the sheet's per-vertex fill (`CosmosSpacetimeSheet`) runs the same
// formulas in floats.
//
// Units are the Star scene's: the star sits at the origin, y is up, and the ringed world keeps
// `CosmosSystem.orbitAngle` so it carries on from where Starlight left it. A body at orbit
// angle φ is at (r cos φ, −r sin φ) — from +x towards −z, as `CosmosSystem.orbitPoint` does.

/// One mass on the sheet: its orbit (or its moon's orbit round `parent`) and its well.
struct CosmosSpacetimeBody: Sendable, Equatable {
    /// Orbit radius round the star, or round `parent` for a moon.
    let orbit: Double
    /// The body's radius (ρ).
    let radius: Double
    /// Well depth (D) and width (ε) of its Plummer profile.
    let depth: Double
    let width: Double
    /// Orbit angle at time zero, in degrees; nil for the ringed world, which follows
    /// `CosmosSystem.orbitAngle`.
    let phaseDegrees: Double?
    /// A moon's angular speed, in degrees per second; planets follow Kepler.
    let moonRate: Double?
    let parent: CosmosSpacetime.Body?
}

enum CosmosSpacetime {

    /// Every mass, in the field's order: the star, the planets inside out, then the moons.
    enum Body: Int, CaseIterable, Sendable {
        case star, ember, azure, ringed, ochre, ice, moonI, moonO

        var spec: CosmosSpacetimeBody {
            switch self {
            case .star: .init(orbit: 0, radius: 0.45, depth: 2.4, width: 1.2, phaseDegrees: 0, moonRate: nil, parent: nil)
            case .ember: .init(orbit: 1.3, radius: 0.10, depth: 0.60, width: 0.20, phaseDegrees: 40, moonRate: nil, parent: nil)
            case .azure: .init(orbit: 2.0, radius: 0.16, depth: 0.70, width: 0.26, phaseDegrees: 200, moonRate: nil, parent: nil)
            case .ringed: .init(orbit: 3.3, radius: 0.30, depth: 0.80, width: 0.50, phaseDegrees: nil, moonRate: nil, parent: nil)
            case .ochre: .init(orbit: 5.1, radius: 0.36, depth: 0.75, width: 0.50, phaseDegrees: 120, moonRate: nil, parent: nil)
            case .ice: .init(orbit: 6.9, radius: 0.20, depth: 0.50, width: 0.28, phaseDegrees: 300, moonRate: nil, parent: nil)
            case .moonI: .init(orbit: 0.60, radius: 0.07, depth: 0.40, width: 0.16, phaseDegrees: 0, moonRate: 36, parent: .ice)
            case .moonO: .init(orbit: 0.85, radius: 0.08, depth: 0.40, width: 0.20, phaseDegrees: 90, moonRate: 24, parent: .ochre)
            }
        }

        /// The bodies that move: all but the star.
        static let moving: [Body] = allCases.filter { $0 != .star }
    }

    // MARK: Constants (the shared contract)

    /// The sheet's rim: the field is windowed to zero between 0.85 R and R.
    static let rim: Double = 12
    static let windowStart: Double = 0.85
    /// A body rests with a quarter of its radius sunk below the sheet under it…
    static let sink: Double = 0.25
    /// …and a tenth of its radius clear of the sheet round its equator.
    static let clear: Double = 0.1
    /// How far the ringed world's rings stay above the sheet.
    static let ringClearance: Double = 0.014
    /// How far below its rest the flat sheet rises from at the start of the transition.
    static let lift: Double = 2.0
    /// The star's scale in Spacetime: its radius drops from 1 to the well's ρ★.
    static let starScale: Float = 0.45
    /// The QA capture's scene time.
    static let qaTime: Float = 9

    // Light: a grazing key from the right and behind.
    static let lightAzimuthDegrees: Double = 35
    static let lightElevationDegrees: Double = 14
    static let ambient: Double = 0.02
    static let wrap: Double = 0.10
    static let aoDepth: Double = 0.2
    static let aoReach: Double = 2.4
    static let horizonSteps = 8
    static let horizonFirstStep: Double = 0.25
    static let horizonGrowth: Double = 1.57
    static let horizonSoftness: Double = 0.03
    static let gain: Double = 0.6
    static let ceiling: Double = 1.25
    static let spotInner: Double = 3
    /// The sheet's base colour, #6E7680, in sRGB.
    static let baseColor = SIMD3<Double>(0x6E, 0x76, 0x80) / 255

    // Pose.
    static let cameraElevationDegrees: Double = 42
    /// The star's centre, as a share of the viewport height from the top.
    static let starHeight: Double = 0.58
    /// The star's diameter, as a share of the viewport width.
    static let starWidth: Double = 0.10
    /// The contract's lens: Android's 28 mm, half-tangent 12/28. iOS frames the same rectangle
    /// through SceneView's 60° lens (`CosmosFraming.tanHalfVerticalFov`).
    static let contractTanHalfVertical: Double = 12.0 / 28.0

    // The mode picker's fixed palette: the same in light and dark.
    static let pillContainer = SIMD3<Double>(0x1A, 0x1F, 0x28) / 255
    static let pillOutline = SIMD3<Double>(0xD1, 0xD2, 0xD4) / 255
    static let pillSelected = SIMD3<Double>(1, 1, 1)
    static let pillSelectedText = SIMD3<Double>(0x0B, 0x0F, 0x16) / 255

    // MARK: Orbits

    /// A planet's orbit angle at `time`, in degrees: Kepler's 3/2 law from the ringed world's
    /// 6°/s at 3.3, or the ringed world's own `CosmosSystem.orbitAngle`.
    static func orbitDegrees(_ body: Body, time: Double) -> Double {
        let spec = body.spec
        if let rate = spec.moonRate { return spec.phaseDegrees! + rate * time }
        guard let phase = spec.phaseDegrees else {
            return Double(CosmosSystem.orbitPhaseDegrees) + Double(CosmosSystem.orbitDegreesPerSecond) * time
        }
        if body == .star { return 0 }
        let rate = Double(CosmosSystem.orbitDegreesPerSecond) * pow(Double(CosmosSystem.orbitRadius) / spec.orbit, 1.5)
        return phase + rate * time
    }

    /// Where `body` is on the sheet at `time`: (x, z).
    static func position(_ body: Body, time: Double) -> SIMD2<Double> {
        let spec = body.spec
        let a = orbitDegrees(body, time: time) * .pi / 180
        let local = SIMD2(spec.orbit * cos(a), -spec.orbit * sin(a))
        guard let parent = spec.parent else { return body == .star ? .zero : local }
        return position(parent, time: time) + local
    }

    // MARK: Field

    /// The windowed Plummer field of every body at one time and mass weight, not yet offset.
    struct Field: Sendable {
        /// (x, z, D·w, ε) per body, in `Body` order.
        let wells: [SIMD4<Double>]

        init(time: Double, weight: Double) {
            wells = Body.allCases.map { body in
                let p = CosmosSpacetime.position(body, time: time)
                return SIMD4(p.x, p.y, body.spec.depth * weight, body.spec.width)
            }
        }

        init(wells: [SIMD4<Double>]) { self.wells = wells }

        /// −Σ Dε (1/√(d² + ε²) − 1/√(R² + ε²)), before the window.
        func raw(_ x: Double, _ z: Double) -> Double {
            var sum = 0.0
            let r2 = CosmosSpacetime.rim * CosmosSpacetime.rim
            for well in wells {
                let dx = x - well.x, dz = z - well.y, e2 = well.w * well.w
                sum -= well.z * well.w * (1 / (dx * dx + dz * dz + e2).squareRoot() - 1 / (r2 + e2).squareRoot())
            }
            return sum
        }

        func rawGradient(_ x: Double, _ z: Double) -> SIMD2<Double> {
            var g = SIMD2<Double>.zero
            for well in wells {
                let dx = x - well.x, dz = z - well.y
                let s = pow(dx * dx + dz * dz + well.w * well.w, -1.5)
                g += well.z * well.w * s * SIMD2(dx, dz)
            }
            return g
        }

        func height(_ x: Double, _ z: Double) -> Double {
            raw(x, z) * CosmosSpacetime.window((x * x + z * z).squareRoot())
        }

        /// The analytic gradient, window derivative included.
        func gradient(_ x: Double, _ z: Double) -> SIMD2<Double> {
            let r = (x * x + z * z).squareRoot()
            let w = CosmosSpacetime.window(r)
            var g = rawGradient(x, z) * w
            if r > 0 {
                let dw = -CosmosSpacetime.smoothstepSlope(CosmosSpacetime.windowStart * CosmosSpacetime.rim,
                                                          CosmosSpacetime.rim, r)
                g += raw(x, z) * dw * SIMD2(x, z) / r
            }
            return g
        }
    }

    static func window(_ r: Double) -> Double {
        1 - smoothstep(windowStart * rim, rim, r)
    }

    /// The sheet: the field, offset so the star's centre stays at the origin for any weight,
    /// and lowered by `lift`.
    struct Sheet: Sendable {
        let field: Field
        let weight: Double
        /// C(w) = −rest★, from the field before the offset.
        let offset: Double
        let lift: Double

        init(time: Double, weight: Double, lift: Double = 0) {
            self.init(field: Field(time: time, weight: weight), weight: weight, lift: lift)
        }

        init(field: Field, weight: Double, lift: Double = 0) {
            self.field = field
            self.weight = weight
            self.lift = lift
            let star = Body.star.spec
            offset = -CosmosSpacetime.rest(at: .zero, radius: star.radius) { field.height($0, $1) }
        }

        func height(_ x: Double, _ z: Double) -> Double { field.height(x, z) + offset - lift }
        func gradient(_ x: Double, _ z: Double) -> SIMD2<Double> { field.gradient(x, z) }

        /// The sheet's upward unit normal.
        func normal(_ x: Double, _ z: Double) -> SIMD3<Double> {
            let g = gradient(x, z)
            return simd_normalize(SIMD3(-g.x, 1, -g.y))
        }

        /// Where `body` rests: its centre's height. The ringed world is raised until its rings
        /// clear the sheet.
        func rest(_ body: Body, at p: SIMD2<Double>) -> Double {
            let plain = CosmosSpacetime.rest(at: p, radius: body.spec.radius) { height($0, $1) }
            guard body == .ringed else { return plain }
            let gap = ringGap(center: SIMD3(p.x, plain, p.y))
            return plain + max(0, CosmosSpacetime.ringClearance - gap)
        }

        /// The ringed world's ring plane at `p`: the sheet's normal averaged over the rings'
        /// inner and outer circles, and two axes in that plane.
        func ringPlane(at p: SIMD2<Double>) -> (normal: SIMD3<Double>, u: SIMD3<Double>, v: SIMD3<Double>) {
            var sum = SIMD2<Double>.zero
            var count = 0.0
            for radius in [Double(CosmosSystem.ringInner), Double(CosmosSystem.ringOuter)] {
                for i in 0..<24 {
                    let a = 2 * Double.pi * Double(i) / 24
                    sum += gradient(p.x + radius * cos(a), p.y + radius * sin(a))
                    count += 1
                }
            }
            let g = sum / count
            let n = simd_normalize(SIMD3(-g.x, 1, -g.y))
            let u = simd_normalize(SIMD3<Double>(1, 0, 0) - n.x * n)
            return (n, u, simd_cross(n, u))
        }

        /// The least height of the rings' circles above the sheet, for a ringed world centred
        /// at `center`.
        func ringGap(center: SIMD3<Double>) -> Double {
            let plane = ringPlane(at: SIMD2(center.x, center.z))
            var worst = Double.infinity
            for radius in [Double(CosmosSystem.ringInner), Double(CosmosSystem.ringOuter)] {
                for i in 0..<96 {
                    let a = 2 * Double.pi * Double(i) / 96
                    let q = center + radius * (cos(a) * plane.u + sin(a) * plane.v)
                    worst = min(worst, q.y - height(q.x, q.z))
                }
            }
            return worst
        }
    }

    /// The rest rule: y = max(F(c) + 0.75ρ, max over the circle |q − c| = ρ of F(q) + 0.1ρ).
    static func rest(at c: SIMD2<Double>, radius: Double, samples: Int = 48,
                     _ f: (Double, Double) -> Double) -> Double {
        var ring = -Double.infinity
        for i in 0..<samples {
            let a = 2 * Double.pi * Double(i) / Double(samples)
            ring = max(ring, f(c.x + radius * cos(a), c.y + radius * sin(a)))
        }
        return max(f(c.x, c.y) + radius * (1 - sink), ring + clear * radius)
    }

    /// Every body's resting centre at `time` and `weight`.
    static func layout(time: Double, weight: Double) -> (sheet: Sheet, centers: [Body: SIMD3<Double>]) {
        let sheet = Sheet(time: time, weight: weight)
        var centers: [Body: SIMD3<Double>] = [.star: .zero]
        for body in Body.moving {
            let p = position(body, time: time)
            centers[body] = SIMD3(p.x, sheet.rest(body, at: p), p.y)
        }
        return (sheet, centers)
    }

    // MARK: Grid

    /// The sheet's rings: 77 uniform ones every 0.1 out to 7.6, then 18 growing geometrically
    /// out to the rim.
    static let gridRadii: [Float] = {
        let inner = 76, outer = 18
        var radii = (0...inner).map { 7.6 * Double($0) / Double(inner) }
        let growth = pow(rim / 7.6, 1 / Double(outer))
        radii += (1...outer).map { 7.6 * pow(growth, Double($0)) }
        return radii.map { Float($0) }
    }()
    static let gridSectors = 416
    static var vertexCount: Int { gridRadii.count * gridSectors }
    static var triangleCount: Int { 2 * (gridRadii.count - 1) * gridSectors }

    // MARK: Light

    /// Towards the light: azimuth from +x towards −z, then up.
    static let light: SIMD3<Double> = {
        let e = lightElevationDegrees * .pi / 180, a = lightAzimuthDegrees * .pi / 180
        return SIMD3(cos(e) * cos(a), sin(e), -cos(e) * sin(a))
    }()

    /// The shade of a flat, open, unshadowed sheet: what `gain` is relative to.
    static let flatShade: Double = ambient + (1 - ambient) * (sin(lightElevationDegrees * .pi / 180) + wrap) / (1 + wrap)

    /// Horizon steps along the light's ground direction.
    static let horizonDistances: [Double] = (0..<horizonSteps).map { horizonFirstStep * pow(horizonGrowth, Double($0)) }

    /// How much of the light the star's well lets reach (x, z): 0 in the shadow of its rim.
    static func horizon(_ x: Double, _ z: Double, star: Field) -> Double {
        let ground = simd_normalize(SIMD2(light.x, light.z))
        let h0 = star.height(x, z)
        var slope = -Double.infinity
        for s in horizonDistances {
            slope = max(slope, (star.height(x + s * ground.x, z + s * ground.y) - h0) / s)
        }
        let t = tan(lightElevationDegrees * .pi / 180)
        return 1 - smoothstep(t - horizonSoftness, t + horizonSoftness, slope)
    }

    /// The star's field alone, at full weight: what the horizon is baked from.
    static let starField = Field(wells: [SIMD4(0, 0, Body.star.spec.depth, Body.star.spec.width)])

    static func ambientOcclusion(_ distance: Double) -> Double {
        1 - aoDepth * (1 - smoothstep(0, aoReach, distance))
    }

    static func spot(_ distance: Double) -> Double {
        1 - smoothstep(spotInner, rim, distance)
    }

    /// The light a sphere of `radius` at `center` lets through to `p` (iOS penumbra:
    /// smoothstep(0.4ρ, 2ρ) across the ray's miss distance).
    static func sphereShadow(_ p: SIMD3<Double>, center: SIMD3<Double>, radius: Double) -> Double {
        let oc = center - p
        let along = max(simd_dot(oc, light), 0)
        let miss = simd_length(oc - along * light)
        return smoothstep(0.4 * radius, 2 * radius, miss)
    }

    /// The sheet's shade at (x, z) without shadows: wrapped key light, horizon, occlusion.
    static func shade(_ x: Double, _ z: Double, sheet: Sheet, shadow: Double = 1) -> Double {
        let n = sheet.normal(x, z)
        let lambert = max(0, (simd_dot(n, light) + wrap) / (1 + wrap))
        let d = (x * x + z * z).squareRoot()
        return (ambient + (1 - ambient) * lambert * horizon(x, z, star: starField) * shadow) * ambientOcclusion(d)
    }

    /// The colour factor the sheet's base colour is scaled by: min(0.6 S/S_flat, 1.25)·spot.
    static func brightness(shade: Double, distance: Double) -> Double {
        min(gain * shade / flatShade, ceiling) * spot(distance)
    }

    // MARK: Pose

    /// The camera's distance for `aspect` through a lens of half-tangent `tanHalfVertical`:
    /// the star's diameter is `starWidth` of the viewport width.
    static func distance(aspect: Double, tanHalfVertical: Double) -> Double {
        Body.star.spec.radius / (starWidth * tanHalfVertical * aspect)
    }

    /// The fixed Spacetime camera: 42° above the sheet, looking a little above the star so it
    /// sits at `starHeight` from the top.
    static func pose(aspect: Double, tanHalfVertical: Double,
                     elevationDegrees: Double = cameraElevationDegrees) -> CosmosPose {
        let phi = elevationDegrees * .pi / 180
        let d = distance(aspect: aspect, tanHalfVertical: tanHalfVertical)
        let eye = SIMD3(0, d * sin(phi), d * cos(phi))
        let delta = atan((2 * starHeight - 1) * tanHalfVertical)
        let pitch = phi - delta
        let forward = SIMD3(0, -sin(pitch), -cos(pitch))
        let up = SIMD3(0, cos(pitch), -sin(pitch))
        return CosmosPose(eye: SIMD3<Float>(eye), target: SIMD3<Float>(eye + forward), up: SIMD3<Float>(up))
    }

    /// Where `p` lands on screen under `pose`: x in −1…1 across, y as a share of the height
    /// from the top, and the depth.
    static func project(_ p: SIMD3<Double>, pose: CosmosPose, aspect: Double,
                        tanHalfVertical: Double) -> SIMD3<Double> {
        let eye = SIMD3<Double>(pose.eye)
        let f = simd_normalize(SIMD3<Double>(pose.target) - eye)
        let r = simd_normalize(simd_cross(f, SIMD3<Double>(pose.up)))
        let u = simd_cross(r, f)
        let v = p - eye
        let depth = simd_dot(v, f)
        return SIMD3(simd_dot(v, r) / (depth * tanHalfVertical * aspect),
                     (1 - simd_dot(v, u) / (depth * tanHalfVertical)) / 2, depth)
    }

    // MARK: Transition

    /// The entry, in seconds from the tap; the way back plays it in reverse over `exitSeconds`.
    enum Timeline {
        static let duration: Double = 2.2
        static let exitSeconds: Double = 1.4
        static let flight: Double = 0.9
        static let halo: Double = 0.35
        static let sheetIn: ClosedRange<Double> = 0.2...0.9
        static let weightIn: ClosedRange<Double> = 0.6...2.2
        static let weightPeak: Double = 1.75
        static let weightOvershoot: Double = 1.05
        static let starsOut: ClosedRange<Double> = 0.9...1.9
        static let appearSeconds: Double = 0.2
        static let fallSeconds: Double = 0.5
        static let fallHeight: Double = 1.0

        /// When each body drops in.
        static func arrival(_ body: Body) -> Double? {
            switch body {
            case .ember: 0.8
            case .azure: 0.95
            case .ochre, .moonO: 1.10
            case .ice, .moonI: 1.25
            case .star, .ringed: nil
            }
        }

        /// The flight's progress, eased: the orbit plane lies down, the star shrinks, the
        /// sheet rises and the bloom drops with it.
        static func settle(_ s: Double) -> Double { ease(clamp01(s / flight)) }

        static func haloAlpha(_ s: Double) -> Double { 1 - smoothstep(0, halo, s) }

        static func sheetIntensity(_ s: Double) -> Double { smoothstep(sheetIn.lowerBound, sheetIn.upperBound, s) }

        static func starField(_ s: Double) -> Double { 1 - smoothstep(starsOut.lowerBound, starsOut.upperBound, s) }

        static func lift(_ s: Double) -> Double { CosmosSpacetime.lift * (1 - settle(s)) }

        /// The mass weight: 0 → 1.05 at `weightPeak` → 1.
        static func weight(_ s: Double) -> Double {
            if s <= weightIn.lowerBound { return 0 }
            if s >= weightIn.upperBound { return 1 }
            if s <= weightPeak {
                return weightOvershoot * smoothstep(weightIn.lowerBound, weightPeak, s)
            }
            return weightOvershoot - (weightOvershoot - 1) * smoothstep(weightPeak, weightIn.upperBound, s)
        }

        /// A planet's opacity and its height above its rest.
        static func drop(_ body: Body, _ s: Double) -> (alpha: Double, above: Double) {
            guard let start = arrival(body) else { return (1, 0) }
            let alpha = clamp01((s - start) / appearSeconds)
            let fall = clamp01((s - start) / fallSeconds)
            return (alpha, fallHeight * (1 - fall * fall))
        }
    }

    // MARK: Colour

    static func linear(_ c: Double) -> Double {
        c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
    }

    static func linear(_ c: SIMD3<Double>) -> SIMD3<Double> { SIMD3(linear(c.x), linear(c.y), linear(c.z)) }

    static func luminance(_ srgb: SIMD3<Double>) -> Double {
        let l = linear(srgb)
        return 0.2126 * l.x + 0.7152 * l.y + 0.0722 * l.z
    }

    static func contrast(_ a: SIMD3<Double>, _ b: SIMD3<Double>) -> Double {
        let la = luminance(a), lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    // MARK: Helpers

    static func smoothstep(_ a: Double, _ b: Double, _ x: Double) -> Double {
        let t = clamp01((x - a) / (b - a))
        return t * t * (3 - 2 * t)
    }

    /// d/dx smoothstep(a, b, x).
    static func smoothstepSlope(_ a: Double, _ b: Double, _ x: Double) -> Double {
        guard x > a, x < b else { return 0 }
        let t = (x - a) / (b - a)
        return 6 * t * (1 - t) / (b - a)
    }

    static func clamp01(_ x: Double) -> Double { min(max(x, 0), 1) }

    /// Ease in-out, cubic.
    static func ease(_ t: Double) -> Double {
        t < 0.5 ? 4 * t * t * t : 1 - pow(-2 * t + 2, 3) / 2
    }
}
