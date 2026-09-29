import Foundation
import simd

// CPU-side geometry of the Cosmos demo — a line-for-line port of the Android demo's
// `CosmosMeshes.kt`, down to its random number generator, so both apps draw the very same
// galaxy, burst, loops and streamlines from the same seeds.
//
// Two vertex layouts, one per shader family (`CosmosShaders.metal`):
//
// - **Sprites** (`CosmosMeshes.spriteStride` floats: position 3, colour 4, corner 4) —
//   four corners per sprite sharing one centre, pushed apart on the GPU so they face the
//   camera.
// - **Ribbons** (`CosmosMeshes.ribbonStride` floats: position 3, colour 4, tangent+side 4,
//   width/t/seed/arc 4) — two vertices per curve point, one on each side of the stroke.
//
// Colours are linear HDR radiance: values above 1.0 are the point, since that is what the
// bloom pass bleeds from.

/// Kotlin's `kotlin.random.Random(seed)` — the XorWow generator of `XorWowRandom` — so a
/// seed gives the same sequence of numbers here as on Android.
struct KotlinRandom {
    private var x: Int32
    private var y: Int32
    private var z: Int32
    private var w: Int32
    private var v: Int32
    private var addend: Int32

    init(seed: Int32) {
        let seed1 = seed
        let seed2 = seed >> 31
        x = seed1
        y = seed2
        z = 0
        w = 0
        v = ~seed1
        addend = (seed1 &<< 10) ^ Int32(bitPattern: UInt32(bitPattern: seed2) >> 4)
        for _ in 0..<64 { _ = nextInt() }
    }

    mutating func nextInt() -> Int32 {
        var t = x
        t = t ^ Int32(bitPattern: UInt32(bitPattern: t) >> 2)
        x = y
        y = z
        z = w
        let v0 = v
        w = v0
        t = (t ^ (t &<< 1)) ^ v0 ^ (v0 &<< 4)
        v = t
        addend = addend &+ 362_437
        return t &+ addend
    }

    mutating func nextBits(_ bitCount: Int) -> Int32 {
        let bits = UInt32(bitPattern: nextInt()) >> UInt32(32 - bitCount)
        return Int32(bitPattern: bits)
    }

    /// `nextFloat()`: 24 random bits over 2^24.
    mutating func nextFloat() -> Float {
        Float(nextBits(24)) / Float(1 << 24)
    }

    mutating func nextBoolean() -> Bool { nextBits(1) != 0 }

    /// `nextInt(until)` for a positive bound.
    mutating func nextInt(_ until: Int32) -> Int32 {
        let n = until
        if n & -n == n {
            return nextBits(Int(31 - n.leadingZeroBitCount))
        }
        var value: Int32
        var bits: Int32
        repeat {
            bits = Int32(bitPattern: UInt32(bitPattern: nextInt()) >> 1)
            value = bits % n
        } while bits &- value &+ (n - 1) < 0
        return value
    }

    /// Box–Muller; one of the pair is enough here.
    mutating func gaussian() -> Float {
        let u = max(nextFloat(), 1e-7)
        let v = nextFloat()
        return sqrt(-2 * log(u)) * cos(2 * Float.pi * v)
    }
}

/// Flat interleaved geometry ready to be copied into a `LowLevelMesh`.
struct GlowMesh: Sendable {
    let vertices: [Float]
    let indices: [UInt32]
    let stride: Int
    /// Axis-aligned bounds of the drawn geometry, including sprite radii and stroke widths.
    let boundsMin: SIMD3<Float>
    let boundsMax: SIMD3<Float>

    var vertexCount: Int { vertices.count / stride }
}

private struct Bounds {
    var min = SIMD3<Float>(repeating: .greatestFiniteMagnitude)
    var max = SIMD3<Float>(repeating: -.greatestFiniteMagnitude)

    mutating func include(_ x: Float, _ y: Float, _ z: Float, pad: Float) {
        min = simd_min(min, SIMD3(x - pad, y - pad, z - pad))
        max = simd_max(max, SIMD3(x + pad, y + pad, z + pad))
    }
}

/// Accumulates camera-facing sprites.
struct SpriteBuilder {
    private var vertices: [Float] = []
    private var indices: [UInt32] = []
    private var bounds = Bounds()
    private(set) var count = 0

    init(expected: Int = 1024) {
        vertices.reserveCapacity(expected * 4 * CosmosMeshes.spriteStride)
        indices.reserveCapacity(expected * 6)
    }

    // swiftlint:disable:next function_parameter_count
    mutating func add(_ x: Float, _ y: Float, _ z: Float, r: Float, g: Float, b: Float, radius: Float, phase: Float) {
        let base = UInt32(count * 4)
        for corner in 0..<4 {
            let cx: Float = (corner == 0 || corner == 3) ? -1 : 1
            let cy: Float = corner < 2 ? -1 : 1
            vertices.append(contentsOf: [x, y, z, r, g, b, 1, cx, cy, radius, phase])
        }
        indices.append(contentsOf: [base, base + 1, base + 2, base, base + 2, base + 3])
        bounds.include(x, y, z, pad: radius)
        count += 1
    }

    func build() -> GlowMesh {
        GlowMesh(vertices: vertices, indices: indices, stride: CosmosMeshes.spriteStride,
                 boundsMin: bounds.min, boundsMax: bounds.max)
    }
}

/// Accumulates camera-facing strokes along polylines.
struct RibbonBuilder {
    private var vertices: [Float] = []
    private var indices: [UInt32] = []
    private var bounds = Bounds()
    private(set) var curves = 0

    init(expectedPoints: Int = 4096) {
        vertices.reserveCapacity(expectedPoints * 2 * CosmosMeshes.ribbonStride)
        indices.reserveCapacity(expectedPoints * 6)
    }

    /// Adds one stroke through `points` (xyz triples, at least two points), with one rgb
    /// triple per point in `colors`. `halfWidth` is in model units; `seed` in [0, 1]
    /// staggers the per-curve animation in the shader.
    mutating func addCurve(_ points: ArraySlice<Float>, _ colors: ArraySlice<Float>, halfWidth: Float, seed: Float) {
        let p = Array(points)
        let c = Array(colors)
        let n = p.count / 3
        precondition(n >= 2 && c.count == n * 3)
        let first = UInt32(vertices.count / CosmosMeshes.ribbonStride)
        var arc: Float = 0
        for i in 0..<n {
            let x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2]
            if i > 0 {
                let dx = x - p[i * 3 - 3], dy = y - p[i * 3 - 2], dz = z - p[i * 3 - 1]
                arc += sqrt(dx * dx + dy * dy + dz * dz)
            }
            let prev = Swift.max(i - 1, 0) * 3
            let next = Swift.min(i + 1, n - 1) * 3
            var tx = p[next] - p[prev], ty = p[next + 1] - p[prev + 1], tz = p[next + 2] - p[prev + 2]
            let tl = sqrt(tx * tx + ty * ty + tz * tz)
            if tl > 1e-9 {
                tx /= tl; ty /= tl; tz /= tl
            } else {
                tx = 1; ty = 0; tz = 0
            }
            let t = Float(i) / Float(n - 1)
            for side in 0..<2 {
                vertices.append(contentsOf: [
                    x, y, z,
                    c[i * 3], c[i * 3 + 1], c[i * 3 + 2], 1,
                    tx, ty, tz, side == 0 ? -1 : 1,
                    halfWidth, t, seed, arc,
                ])
            }
            bounds.include(x, y, z, pad: halfWidth)
        }
        for i in 0..<(n - 1) {
            let a = first + UInt32(i * 2)
            let b = a + 2
            indices.append(contentsOf: [a, a + 1, b, a + 1, b + 1, b])
        }
        curves += 1
    }

    func build() -> GlowMesh {
        GlowMesh(vertices: vertices, indices: indices, stride: CosmosMeshes.ribbonStride,
                 boundsMin: bounds.min, boundsMax: bounds.max)
    }
}

private func mix(_ a: Float, _ b: Float, _ t: Float) -> Float { a + (b - a) * t }

private func smoothstep(_ edge0: Float, _ edge1: Float, _ x: Float) -> Float {
    let t = min(max((x - edge0) / (edge1 - edge0), 0), 1)
    return t * t * (3 - 2 * t)
}

/// The four procedural scenes of the Cosmos demo. Each builder is deterministic for a given
/// seed, and the seeds match Android's.
enum CosmosMeshes {
    /// Floats per sprite vertex: position (3), colour (4), corner (4).
    static let spriteStride = 11
    /// Floats per ribbon vertex: position (3), colour (4), tangent+side (4), width/t/seed/arc (4).
    static let ribbonStride = 15

    static let galaxyRadius: Float = 1
    private static let galaxyArmStart: Float = 0.14
    private static let galaxyPitchTan: Float = 0.27

    /// Where arm `arm` of two sits at radius `r`, in radians (logarithmic spiral).
    static func galaxyArmAngle(_ r: Float, _ arm: Int32) -> Float {
        log(max(r, galaxyArmStart) / galaxyArmStart) / galaxyPitchTan + Float(arm) * .pi
    }

    /// A barred two-arm spiral galaxy in the XZ plane.
    // swiftlint:disable:next function_body_length
    static func galaxy(seed: Int32 = 7, armStars: Int = 34_000) -> GlowMesh {
        var rnd = KotlinRandom(seed: seed)
        var out = SpriteBuilder(expected: armStars + 16_000)
        let twoPi = 2 * Float.pi

        // Bulge and bar — warm, dense, the white-hot core once additive blending stacks it up.
        for _ in 0..<(armStars / 6) {
            let bar = rnd.nextFloat() < 0.55
            let x: Float, y: Float, z: Float
            if bar {
                x = rnd.gaussian() * 0.16
                z = rnd.gaussian() * 0.045
                y = rnd.gaussian() * 0.03
            } else {
                let r = abs(rnd.gaussian()) * 0.09
                let a = rnd.nextFloat() * twoPi
                x = cos(a) * r
                z = sin(a) * r
                y = rnd.gaussian() * 0.045
            }
            let heat = rnd.nextFloat()
            let radius = 0.010 + rnd.nextFloat() * 0.012
            out.add(x, y, z, r: 1.0 * 0.34, g: mix(0.70, 0.80, heat) * 0.34, b: mix(0.42, 0.58, heat) * 0.34,
                    radius: radius, phase: rnd.nextFloat())
        }

        // Arms.
        for _ in 0..<armStars {
            let arm = rnd.nextInt(2)
            let spur = rnd.nextFloat() < 0.2
            let disc = min(galaxyArmStart + (-log(1 - rnd.nextFloat() * 0.96)) / 2.8, galaxyRadius * 1.05)
            let r = spur ? max(disc, 0.42) : disc
            let spread = (0.034 + 0.11 * r) * (spur ? 1.3 : 1)
            let angle = galaxyArmAngle(r, arm) + (spur ? 1.15 : 0)
            let along = rnd.gaussian() * spread
            let across = rnd.gaussian() * spread * 0.9
            let x = cos(angle) * (r + along) - sin(angle) * across
            let z = sin(angle) * (r + along) + cos(angle) * across
            let y = rnd.gaussian() * (0.018 * (1.1 - r))
            let crest = exp(-(along * along + across * across) / (spread * spread * 0.5))
            let outer = smoothstep(0.1, 0.75, r)
            let red = mix(1.0, 0.56, outer)
            let green = mix(0.84, 0.64, outer)
            let blue = mix(0.70, 1.0, outer)
            let boost: Float = rnd.nextFloat() < 0.04 ? 3.2 : 1
            let brightness = (0.13 + 0.46 * crest) * boost * (spur ? 0.5 : 1)
            let radius = 0.0045 + rnd.nextFloat() * 0.007
            out.add(x, y, z, r: red * brightness, g: green * brightness, b: blue * brightness,
                    radius: radius, phase: rnd.nextFloat())
        }

        // A soft lavender glow under the arms.
        for _ in 0..<(armStars / 12) {
            let arm = rnd.nextInt(2)
            let r = galaxyArmStart + rnd.nextFloat() * (galaxyRadius - galaxyArmStart)
            let angle = galaxyArmAngle(r, arm) + rnd.gaussian() * 0.12
            let fade = 1 - 0.7 * r
            let y = rnd.gaussian() * 0.01
            let radius = 0.05 + rnd.nextFloat() * 0.05
            out.add(cos(angle) * r, y, sin(angle) * r, r: 0.026 * fade, g: 0.03 * fade, b: 0.056 * fade,
                    radius: radius, phase: rnd.nextFloat())
        }

        // Pink star-forming knots along the outer arms.
        for _ in 0..<48 {
            let arm = rnd.nextInt(2)
            let r = 0.55 + rnd.nextFloat() * 0.55
            let angle = galaxyArmAngle(r, arm) + rnd.gaussian() * 0.3
            let cx = cos(angle) * r
            let cz = sin(angle) * r
            let knots = 4 + Int(rnd.nextInt(7))
            for _ in 0..<knots {
                let x = cx + rnd.gaussian() * 0.012
                let y = rnd.gaussian() * 0.006
                let z = cz + rnd.gaussian() * 0.012
                let radius = 0.006 + rnd.nextFloat() * 0.007
                out.add(x, y, z, r: 1.5, g: 0.2, b: 0.38, radius: radius, phase: rnd.nextFloat())
            }
        }

        // Reddish dust haze on the inner (trailing) edge of the arms.
        for _ in 0..<1_800 {
            let arm = rnd.nextInt(2)
            let r = 0.2 + rnd.nextFloat() * 0.8
            let angle = galaxyArmAngle(r, arm) - 0.22 + rnd.gaussian() * 0.06
            let x = cos(angle) * r + rnd.gaussian() * 0.02
            let y = rnd.gaussian() * 0.01
            let z = sin(angle) * r + rnd.gaussian() * 0.02
            let radius = 0.015 + rnd.nextFloat() * 0.02
            out.add(x, y, z, r: 0.018, g: 0.005, b: 0.006, radius: radius, phase: rnd.nextFloat())
        }

        // Field stars between the arms, and a sparse halo.
        for _ in 0..<5_000 {
            let r = sqrt(rnd.nextFloat()) * galaxyRadius * 1.15
            let a = rnd.nextFloat() * twoPi
            let b = 0.12 + 0.2 * rnd.nextFloat()
            let y = rnd.gaussian() * 0.03
            let radius = 0.004 + rnd.nextFloat() * 0.004
            out.add(cos(a) * r, y, sin(a) * r, r: 0.75 * b, g: 0.82 * b, b: 1.0 * b,
                    radius: radius, phase: rnd.nextFloat())
        }

        // The glow of the core itself — one big soft sprite the bloom turns into a halo.
        out.add(0, 0, 0, r: 0.95, g: 0.62, b: 0.34, radius: 0.24, phase: 0)
        out.add(0, 0, 0, r: 0.09, g: 0.1, b: 0.17, radius: 1.4, phase: 0)
        return out.build()
    }

    /// A background star field on a shell of radius `radius` around the origin.
    static func starField(seed: Int32 = 3, count: Int = 2_600, radius: Float = 40) -> GlowMesh {
        var rnd = KotlinRandom(seed: seed)
        var out = SpriteBuilder(expected: count)
        for _ in 0..<count {
            let z = rnd.nextFloat() * 2 - 1
            let a = rnd.nextFloat() * 2 * Float.pi
            let s = sqrt(1 - z * z)
            let d = radius * (0.9 + 0.2 * rnd.nextFloat())
            let u = rnd.nextFloat()
            let brightness = 0.25 + 5 * u * u * u * u * u * u * u * u
            let kind = rnd.nextFloat()
            let rgb: (Float, Float, Float)
            if kind < 0.35 {
                rgb = (0.72, 0.82, 1.0)
            } else if kind < 0.8 {
                rgb = (1.0, 0.97, 0.94)
            } else if kind < 0.95 {
                rgb = (1.0, 0.86, 0.64)
            } else {
                rgb = (1.0, 0.62, 0.42)
            }
            let spriteRadius = 0.07 + 0.08 * rnd.nextFloat()
            out.add(cos(a) * s * d, z * d, sin(a) * s * d,
                    r: rgb.0 * brightness, g: rgb.1 * brightness, b: rgb.2 * brightness,
                    radius: spriteRadius, phase: rnd.nextFloat())
        }
        return out.build()
    }

    /// The particle tracks of a detonation: charged particles curling in a magnetic field.
    // swiftlint:disable:next function_body_length
    static func burst(seed: Int32 = 11, tracks: Int = 460, steps: Int = 96) -> GlowMesh {
        var rnd = KotlinRandom(seed: seed)
        var out = RibbonBuilder(expectedPoints: tracks * steps)
        var points = [Float](repeating: 0, count: steps * 3)
        var colors = [Float](repeating: 0, count: steps * 3)
        for index in 0..<tracks {
            let positive = rnd.nextFloat() < 0.62
            let sideways: Float = rnd.nextBoolean() ? 1 : -1
            var vx = sideways * (positive ? 0.6 + rnd.nextFloat() : 0.15 + 0.6 * rnd.nextFloat())
            var vy = rnd.gaussian() * (positive ? 0.32 : 0.95)
            var vz = rnd.gaussian() * 0.35
            let len = sqrt(vx * vx + vy * vy + vz * vz)
            let s1 = rnd.nextFloat()
            let s2 = rnd.nextFloat()
            let s3 = rnd.nextFloat()
            let speed = 0.9 + 2.1 * s1 * s2 + s3 * 0.6
            vx = vx / len * speed
            vy = vy / len * speed
            vz = vz / len * speed
            let curl = rnd.nextFloat()
            let charge: Float = (positive ? 1 : -1) * (0.5 + 3.1 * curl * curl * curl)
            let bx: Float = 0.18, by: Float = 0.3, bz: Float = 1
            let drag = 0.25 + 0.9 * rnd.nextFloat()
            let dt: Float = 0.022
            var x = rnd.gaussian() * 0.01
            var y = rnd.gaussian() * 0.01
            var z = rnd.gaussian() * 0.01
            let hue = rnd.nextFloat()
            let base: (Float, Float, Float) = positive
                ? (1.0, 0.04 + 0.08 * hue, 0.08 + 0.35 * hue * hue)
                : (0.12 + 0.4 * hue, 0.16 + 0.12 * hue, 1.0)
            let gain = 0.9 + 1.6 * rnd.nextFloat()
            for i in 0..<steps {
                points[i * 3] = x
                points[i * 3 + 1] = y
                points[i * 3 + 2] = z
                let t = Float(i) / Float(steps - 1)
                let white = 1 - smoothstep(0, 0.2, t)
                let boost = gain * (1 + 2.5 * white)
                colors[i * 3] = mix(base.0, 1, white) * boost
                colors[i * 3 + 1] = mix(base.1, 0.85, white) * boost
                colors[i * 3 + 2] = mix(base.2, 1, white) * boost
                let ax = charge * (vy * bz - vz * by) - drag * vx
                let ay = charge * (vz * bx - vx * bz) - drag * vy
                let az = charge * (vx * by - vy * bx) - drag * vz
                vx += ax * dt
                vy += ay * dt
                vz += az * dt
                x += vx * dt
                y += vy * dt
                z += vz * dt
            }
            let halfWidth = 0.0035 + 0.003 * rnd.nextFloat()
            out.addCurve(points[...], colors[...], halfWidth: halfWidth,
                         seed: (Float(index) * 0.618034).truncatingRemainder(dividingBy: 1))
        }
        return out.build()
    }

    /// Stray sparks around the burst: small red and violet points that twinkle.
    static func burstSparks(seed: Int32 = 13, count: Int = 520) -> GlowMesh {
        var rnd = KotlinRandom(seed: seed)
        var out = SpriteBuilder(expected: count)
        for _ in 0..<count {
            let a = rnd.nextFloat() * 2 * Float.pi
            let r = 0.3 + 2.4 * sqrt(rnd.nextFloat())
            let red = rnd.nextFloat() < 0.75
            let b = 0.6 + 2.2 * rnd.nextFloat()
            let z = rnd.gaussian() * 0.5
            let radius = 0.006 + 0.01 * rnd.nextFloat()
            out.add(cos(a) * r, sin(a) * r * 1.3, z,
                    r: (red ? 1 : 0.45) * b, g: 0.06 * b, b: (red ? 0.12 : 1) * b,
                    radius: radius, phase: rnd.nextFloat())
        }
        return out.build()
    }

    /// One point vortex of the flow field: centre, circulation, inflow and core radius.
    struct Vortex: Sendable {
        let x: Float, y: Float, circulation: Float, inflow: Float, core: Float
    }

    /// Half extents of the flow field's domain in the XY plane — portrait, like the phone.
    static let flowHalfWidth: Float = 1.25
    static let flowHalfHeight: Float = 2.2

    static let flowVortices: [Vortex] = [
        Vortex(x: 0.12, y: 0.1, circulation: 1.25, inflow: 0.18, core: 0.05),
        Vortex(x: -0.6, y: 1.25, circulation: -0.9, inflow: 0.1, core: 0.06),
        Vortex(x: 0.7, y: 1.55, circulation: 0.75, inflow: 0.12, core: 0.05),
        Vortex(x: -0.55, y: -1.2, circulation: 0.95, inflow: 0.14, core: 0.06),
        Vortex(x: 0.72, y: -0.55, circulation: -0.7, inflow: 0.08, core: 0.06),
        Vortex(x: 0.25, y: -1.85, circulation: 0.6, inflow: 0.1, core: 0.05),
    ]

    private static func insideVortexCore(_ x: Float, _ y: Float) -> Bool {
        flowVortices.contains { v in
            let dx = x - v.x, dy = y - v.y
            return dx * dx + dy * dy < (v.core * 0.9) * (v.core * 0.9)
        }
    }

    /// The velocity of the flow field at (x, y): a meandering drift plus the vortices.
    static func flowVelocity(_ x: Float, _ y: Float) -> SIMD2<Float> {
        var vx = 0.08 + 0.22 * sin(1.4 * y + 0.6)
        var vy = -0.32 + 0.12 * sin(1.7 * x - 0.4)
        for v in flowVortices {
            let dx = x - v.x, dy = y - v.y
            let r2 = dx * dx + dy * dy + v.core * v.core
            let k = v.circulation / (2 * Float.pi * r2)
            let sink = v.inflow / (2 * Float.pi * r2)
            vx += -dy * k - dx * sink
            vy += dx * k - dy * sink
        }
        return SIMD2(vx, vy)
    }

    /// How far below the plane the field dips at (x, y): a funnel into each vortex.
    static func flowDepth(_ x: Float, _ y: Float) -> Float {
        var z: Float = 0
        for v in flowVortices {
            let dx = x - v.x, dy = y - v.y
            z -= 0.35 * abs(v.circulation) * exp(-(dx * dx + dy * dy) / 0.06)
        }
        return z
    }

    /// Streamlines of `flowVelocity`, seeded on a jittered grid and integrated with RK2.
    static func flowField(seed: Int32 = 5, columns: Int = 30, rows: Int = 52, maxSteps: Int = 110) -> GlowMesh {
        var rnd = KotlinRandom(seed: seed)
        var out = RibbonBuilder(expectedPoints: columns * rows * maxSteps / 2)
        var points = [Float](repeating: 0, count: maxSteps * 3)
        var colors = [Float](repeating: 0, count: maxSteps * 3)
        let h: Float = 0.02
        let margin: Float = 0.15
        for row in 0..<rows {
            for column in 0..<columns {
                var x = -flowHalfWidth + (Float(column) + rnd.nextFloat()) * (2 * flowHalfWidth / Float(columns))
                var y = -flowHalfHeight + (Float(row) + rnd.nextFloat()) * (2 * flowHalfHeight / Float(rows))
                let layer = rnd.gaussian() * 0.06
                let lineGain = 0.55 + 0.9 * rnd.nextFloat()
                var n = 0
                var tracing = true
                while tracing && n < maxSteps {
                    let v1 = flowVelocity(x, y)
                    let speed = simd_length(v1)
                    points[n * 3] = x
                    points[n * 3 + 1] = y
                    points[n * 3 + 2] = flowDepth(x, y) + layer
                    let glow = smoothstep(0.15, 2.2, speed)
                    let bright = (0.22 + 1.9 * glow) * lineGain
                    colors[n * 3] = mix(0.12, 0.7, glow) * bright
                    colors[n * 3 + 1] = mix(0.42, 0.9, glow) * bright
                    colors[n * 3 + 2] = 1.0 * bright
                    n += 1
                    let mx = x + 0.5 * h * v1.x / max(speed, 1e-6)
                    let my = y + 0.5 * h * v1.y / max(speed, 1e-6)
                    let v2 = flowVelocity(mx, my)
                    let s2 = simd_length(v2)
                    if speed < 1e-4 || s2 < 1e-4 {
                        tracing = false
                    } else {
                        x += h * v2.x / s2
                        y += h * v2.y / s2
                        tracing = abs(x) <= flowHalfWidth + margin
                            && abs(y) <= flowHalfHeight + margin
                            && !insideVortexCore(x, y)
                    }
                }
                if n >= 12 {
                    out.addCurve(points[0..<(n * 3)], colors[0..<(n * 3)], halfWidth: 0.0032, seed: rnd.nextFloat())
                }
            }
        }
        return out.build()
    }

    /// Glinting dust carried by the flow.
    static func flowDust(seed: Int32 = 9, count: Int = 1_400) -> GlowMesh {
        var rnd = KotlinRandom(seed: seed)
        var out = SpriteBuilder(expected: count)
        for _ in 0..<count {
            let x = (rnd.nextFloat() * 2 - 1) * flowHalfWidth
            let y = (rnd.nextFloat() * 2 - 1) * flowHalfHeight
            let b1 = rnd.nextFloat()
            let b2 = rnd.nextFloat()
            let b = 0.5 + 2.5 * b1 * b2
            let z = flowDepth(x, y) + rnd.gaussian() * 0.08
            let radius = 0.005 + 0.006 * rnd.nextFloat()
            out.add(x, y, z, r: 0.7 * b, g: 0.85 * b, b: 1.0 * b, radius: radius, phase: rnd.nextFloat())
        }
        return out.build()
    }

    /// Magnetic loops and wisps around the plasma star.
    // swiftlint:disable:next function_body_length
    static func prominences(seed: Int32 = 17, loops: Int = 150, plumes: Int = 90, starRadius: Float = 1) -> GlowMesh {
        var rnd = KotlinRandom(seed: seed)
        let steps = 48
        var out = RibbonBuilder(expectedPoints: (loops + plumes) * steps)
        var points = [Float](repeating: 0, count: steps * 3)
        var colors = [Float](repeating: 0, count: steps * 3)
        func randomUnit() -> SIMD3<Float> {
            let z = rnd.nextFloat() * 2 - 1
            let a = rnd.nextFloat() * 2 * Float.pi
            let s = sqrt(1 - z * z)
            return SIMD3(cos(a) * s, z, sin(a) * s)
        }
        for _ in 0..<loops {
            let a = randomUnit()
            let t = randomUnit()
            let dot = simd_dot(a, t)
            let p = t - dot * a
            let pl = max(simd_length(p), 1e-4)
            let hop = 0.25 + 0.6 * rnd.nextFloat()
            let h1 = rnd.nextFloat()
            let h2 = rnd.nextFloat()
            let height = 0.08 + 0.45 * h1 * h2
            let bright = 0.14 + 0.36 * rnd.nextFloat()
            for i in 0..<steps {
                let u = Float(i) / Float(steps - 1)
                let ang = hop * u
                let d = a * cos(ang) + p / pl * sin(ang)
                let lift = starRadius * (1.005 + height * sin(Float.pi * u))
                points[i * 3] = d.x * lift
                points[i * 3 + 1] = d.y * lift
                points[i * 3 + 2] = d.z * lift
                colors[i * 3] = 0.08 * bright
                colors[i * 3 + 1] = 0.38 * bright
                colors[i * 3 + 2] = 1.4 * bright
            }
            let halfWidth = 0.006 + 0.01 * rnd.nextFloat()
            out.addCurve(points[...], colors[...], halfWidth: halfWidth, seed: rnd.nextFloat())
        }
        for _ in 0..<plumes {
            let a = randomUnit()
            let twist = randomUnit()
            let reach = 0.4 + 1.4 * rnd.nextFloat()
            let bright = 0.12 + 0.35 * rnd.nextFloat()
            for i in 0..<steps {
                let u = Float(i) / Float(steps - 1)
                let out1 = starRadius * (1.0 + reach * u)
                let curl = 0.5 * u * u
                let d = a + twist * curl
                let l = simd_length(d)
                points[i * 3] = d.x / l * out1
                points[i * 3 + 1] = d.y / l * out1
                points[i * 3 + 2] = d.z / l * out1
                let fade = 1 - u
                colors[i * 3] = 0.06 * bright * fade
                colors[i * 3 + 1] = 0.34 * bright * fade
                colors[i * 3 + 2] = 1.5 * bright * fade
            }
            let halfWidth = 0.012 + 0.02 * rnd.nextFloat()
            out.addCurve(points[...], colors[...], halfWidth: halfWidth, seed: rnd.nextFloat())
        }
        return out.build()
    }

    /// The glow around the plasma star: nested soft sprites and a wide blue nebula.
    static func starHalo(starRadius: Float = 1) -> GlowMesh {
        var out = SpriteBuilder(expected: 4)
        out.add(0, 0, 0, r: 0.35, g: 0.75, b: 1.6, radius: starRadius * 1.5, phase: 0)
        out.add(0, 0, 0, r: 0.12, g: 0.35, b: 1.0, radius: starRadius * 2.6, phase: 0)
        out.add(0, 0, 0, r: 0.03, g: 0.1, b: 0.35, radius: starRadius * 5.5, phase: 0)
        return out.build()
    }

    /// A wide, dim blue glow behind the flow field.
    static func flowBackdrop() -> GlowMesh {
        var out = SpriteBuilder(expected: 2)
        out.add(0, 0.2, -1.6, r: 0.02, g: 0.07, b: 0.2, radius: 3.6, phase: 0)
        out.add(0.1, 0.1, -0.8, r: 0.02, g: 0.06, b: 0.16, radius: 1.5, phase: 0)
        return out.build()
    }

    /// The flash at the heart of the burst.
    static func burstCore() -> GlowMesh {
        var out = SpriteBuilder(expected: 2)
        out.add(0, 0, 0, r: 1.0, g: 0.8, b: 1.0, radius: 0.35, phase: 0)
        out.add(0, 0, 0, r: 0.55, g: 0.2, b: 0.6, radius: 1.1, phase: 0)
        return out.build()
    }

    /// The burst's loop, as (head, fade, flash) at `phase` in [0, 1).
    static func burstEnvelope(_ phase: Float) -> SIMD3<Float> {
        let grow = min(max(phase / 0.42, 0), 1)
        let head = 1 - (1 - grow) * (1 - grow) * (1 - grow)
        let fade = 1 - smoothstep(0.7, 0.97, phase)
        let flash = exp(-phase * 14) * 6 + 0.35 * fade
        return SIMD3(head * 1.25, fade, flash)
    }
}

/// The four procedural scenes of the Cosmos demo, in tour order.
enum CosmosSceneKind: Int, CaseIterable, Sendable {
    case galaxy, star, burst, flow

    var label: String {
        switch self {
        case .galaxy: "Galaxy"
        case .star: "Star"
        case .burst: "Burst"
        case .flow: "Flow"
        }
    }
}

/// Camera framing of each Cosmos scene, as pure functions of time and viewport aspect.
enum CosmosFraming {
    /// tan(half vertical FOV) of SceneView's 60° camera. Android's 28 mm lens is 12 / 28
    /// (a 46° field); `fitDistance` frames the same rectangle whatever the lens.
    static let tanHalfVerticalFov: Float = tan(30 * .pi / 180)

    /// Distance at which a `halfWidth` × `halfHeight` rectangle just fits the viewport.
    static func fitDistance(_ halfWidth: Float, _ halfHeight: Float, aspect: Float) -> Float {
        let tanH = tanHalfVerticalFov * max(aspect, 0.1)
        return max(halfWidth / tanH, halfHeight / tanHalfVerticalFov)
    }

    /// Fade-in of a scene after a switch, 0 → 1 over `duration` seconds.
    static func reveal(_ sceneTime: Float, duration: Float) -> Float {
        smoothstep(0, duration, sceneTime)
    }

    /// Camera orbit (distance, elevation, yaw) for `scene` at `time` seconds; the camera
    /// always looks at the origin.
    static func pose(_ scene: CosmosSceneKind, time: Float, aspect: Float) -> (distance: Float, elevation: Float, yaw: Float) {
        let deg = Float.pi / 180
        switch scene {
        case .galaxy:
            let elevation = 58 * deg
            let yaw = (25 + 12 * sin(time * 0.07)) * deg
            return (fitDistance(0.98, 0.98 * sin(elevation) + 0.1, aspect: aspect), elevation, yaw)
        case .star:
            return (fitDistance(1.3, 1.3, aspect: aspect), (6 + 3 * sin(time * 0.09)) * deg, (8 * sin(time * 0.11)) * deg)
        case .burst:
            return (fitDistance(1.6, 1.6, aspect: aspect), (8 + 4 * sin(time * 0.13)) * deg, (16 * sin(time * 0.1)) * deg)
        case .flow:
            return (fitDistance(1.12, 2.05, aspect: aspect), (12 + 2 * sin(time * 0.08)) * deg, (4 * sin(time * 0.1)) * deg)
        }
    }
}
