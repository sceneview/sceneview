import Foundation
import simd

// The home stage's dusk flight, as numbers: the terrain, the camera's path, the fog.
//
// A port of Android's `HomeHeroTerrain.kt` and `HomeHeroFlight.kt` (#3948), value
// for value, so the two stages fly the same valley on the same clock. Nothing here
// touches RealityKit — `HomeHeroScene.swift` turns it into entities — which keeps
// the maths readable next to its Kotlin twin.

// MARK: - Terrain

/// The terrain strip: a flat-shaded valley between two walls of ridges, tiling
/// seamlessly every `period` metres so it can scroll forever under the camera.
struct HeroTerrainSpec: Sendable {
    var columns: Int
    var rows: Int
    var width: Float = 84
    var period: Float = 40
    var periods: Int = 4
    var zNear: Float = 20

    var length: Float { period * Float(periods) }
    var zFar: Float { zNear - length }
    var faceCount: Int { columns * rows * 2 }

    /// Android's `HeroTerrainSpec.Full`.
    static let full = HeroTerrainSpec(columns: 56, rows: 160)
}

enum HeroTerrain {
    /// Ground height at `(x, z)`: a valley floor that rises into ridged walls
    /// away from the centre line. Periodic in `z` over `period`.
    static func height(x: Float, z: Float, period: Float) -> Float {
        let u = 2 * Float.pi * z / period
        var ridge = sin(x * 0.21 + 1.3) * cos(u + 0.4)
        ridge += sin(x * 0.47 - u * 2) * 0.7
        ridge += cos(x * 0.9 + u * 3 + 2.1) * 0.35
        ridge += sin(x * 1.7 + 0.5) * cos(u * 5) * 0.18
        let wall = smoothstep(4, 26, abs(x))
        let floor = -0.35 + ridge * 0.25
        return floor + wall * (2.5 + (ridge + 1.5) * 2.2)
    }

    /// Linear albedo by altitude — valley, rock, ochre, snow — brightened or
    /// darkened by `jitter` so neighbouring facets read apart.
    static func albedo(altitude: Float, jitter: Float = 0) -> SIMD3<Float> {
        let valley = SIMD3<Float>(0.030, 0.028, 0.075)
        let rock = SIMD3<Float>(0.145, 0.078, 0.125)
        let ochre = SIMD3<Float>(0.300, 0.150, 0.105)
        let snow = SIMD3<Float>(0.700, 0.560, 0.640)
        let a = mix(valley, rock, t: smoothstep(-0.6, 1.8, altitude))
        let b = mix(a, ochre, t: smoothstep(2.6, 6.0, altitude))
        let c = mix(b, snow, t: smoothstep(7.2, 9.2, altitude))
        return c * (1 + jitter)
    }

    /// ±6 % per facet, from the same integer hash as Android (32-bit wrapping).
    static func faceJitter(_ face: Int) -> Float {
        var h = Int32(truncatingIfNeeded: face) &* 374_761_393 &+ 668_265_263
        h = (h ^ Int32(bitPattern: UInt32(bitPattern: h) >> 13)) &* 1_274_126_177
        h ^= Int32(bitPattern: UInt32(bitPattern: h) >> 16)
        return (Float(h & 0xFFFF) / 65535 - 0.5) * 0.12
    }

    /// One flat-shaded facet: its three corners, its up-facing normal, its
    /// albedo and its centroid (where its fog is measured).
    struct Face {
        var a: SIMD3<Float>
        var b: SIMD3<Float>
        var c: SIMD3<Float>
        var normal: SIMD3<Float>
        var albedo: SIMD3<Float>
        var centroid: SIMD3<Float> { (a + b + c) / 3 }
    }

    /// Every facet of the strip, in Android's emission order — the quad
    /// diagonal alternates with `(row + column) % 2`, and the face index feeds
    /// the jitter hash, so the order is part of the look.
    static func faces(_ spec: HeroTerrainSpec) -> [Face] {
        var faces: [Face] = []
        faces.reserveCapacity(spec.faceCount)
        let dx = spec.width / Float(spec.columns)
        let dz = spec.length / Float(spec.rows)
        let x0 = -spec.width / 2

        func emit(_ ax: Float, _ az: Float, _ bx: Float, _ bz: Float, _ cx: Float, _ cz: Float) {
            let a = SIMD3(ax, height(x: ax, z: az, period: spec.period), az)
            let b = SIMD3(bx, height(x: bx, z: bz, period: spec.period), bz)
            let c = SIMD3(cx, height(x: cx, z: cz, period: spec.period), cz)
            var n = simd_normalize(simd_cross(b - a, c - a))
            if !n.x.isFinite { n = SIMD3(0, 1, 0) }
            if n.y < 0 { n = -n }
            let albedo = albedo(altitude: (a.y + b.y + c.y) / 3, jitter: faceJitter(faces.count))
            faces.append(Face(a: a, b: b, c: c, normal: n, albedo: albedo))
        }

        for row in 0..<spec.rows {
            let zA = spec.zFar + Float(row) * dz
            let zB = zA + dz
            for column in 0..<spec.columns {
                let xA = x0 + Float(column) * dx
                let xB = xA + dx
                if (row + column) % 2 == 0 {
                    emit(xA, zA, xA, zB, xB, zB)
                    emit(xA, zA, xB, zB, xB, zA)
                } else {
                    emit(xA, zA, xA, zB, xB, zA)
                    emit(xB, zA, xA, zB, xB, zB)
                }
            }
        }
        return faces
    }
}

// MARK: - Light and fog

/// The dusk the flight is lit and fogged by — Android's `DuskFlight` and the
/// `View.fogOptions` it sets, in the same units once exposed.
///
/// RealityKit has no view fog, so the terrain is shaded here, per facet, with
/// Filament's own equations: Lambert under the sun plus an ambient term, then
/// Filament's height fog and sun in-scattering, then its `Filmic` tone curve.
/// The fog itself runs per frame in `HeroTerrainShader.shade` (Filament's
/// `shading_fog`: the analytic integral of an exponential height density,
/// capped at `maximumOpacity`, off past the cut-off, plus the sun's
/// in-scattering lobe). The result is written as each facet's colour on an
/// unlit material, which is what makes the far ridges dissolve into the sky.
enum HeroDusk {
    /// The sun's direction of travel (Android `sunDirection`), and the unit
    /// vector back towards it.
    static let sunTravel = simd_normalize(SIMD3<Float>(0.55, -0.35, 0.76))
    static let toSun = -sunTravel
    /// SceneView Android's camera exposure (f/12, 1/200 s, ISO 200):
    /// 1 / (1.2 × 2^EV100) = 5.787e-5.
    static let exposure: Float = 5.787e-5
    /// Sun colour × 95 000 lux, pre-exposed: the sun as the shader sees it.
    static let sunExposed = SIMD3<Float>(1, 0.62, 0.38) * (95_000 * exposure)
    /// Sky light on the facets: the diffuse irradiance (E / π) of Android's
    /// `sunset_2k.hdr`, integrated offline towards each axis, × SceneView's
    /// 10 000 IBL intensity × `exposure` — an ambient cube. Blue from above,
    /// warm from the sun's side (−X, ahead), dim from below.
    static let ambientUp = SIMD3<Float>(0.416, 0.506, 0.669)
    static let ambientDown = SIMD3<Float>(0.173, 0.210, 0.275)
    static let ambientLeft = SIMD3<Float>(0.468, 0.465, 0.521)
    static let ambientRight = SIMD3<Float>(0.244, 0.366, 0.531)
    static let ambientAhead = SIMD3<Float>(0.554, 0.530, 0.568)
    static let ambientBehind = SIMD3<Float>(0.262, 0.378, 0.542)

    static func ambient(_ n: SIMD3<Float>) -> SIMD3<Float> {
        let sq = n * n
        return sq.x * (n.x < 0 ? ambientLeft : ambientRight)
            + sq.y * (n.y > 0 ? ambientUp : ambientDown)
            + sq.z * (n.z < 0 ? ambientAhead : ambientBehind)
    }

    static let fogColor = SIMD3<Float>(0.75, 0.17, 0.075)
    static let fogDensity: Float = 0.045
    static let fogHeight: Float = -1
    static let fogHeightFalloff: Float = 0.14
    static let fogStart: Float = 6
    static let fogCutOff: Float = 80
    static let fogMaxOpacity: Float = 0.9
    static let inScatteringStart: Float = 20
    static let inScatteringSize: Float = 14

    /// A facet's lit colour before fog, in exposed linear units.
    static func lit(albedo: SIMD3<Float>, normal: SIMD3<Float>) -> SIMD3<Float> {
        let sun = sunExposed * (max(simd_dot(normal, toSun), 0) / Float.pi)
        return albedo * (sun + ambient(normal))
    }

    /// Filament's `ToneMapper.Filmic` (Narkowicz ACES fit), the curve SceneView
    /// Android renders with.
    static func filmic(_ x: Float) -> Float {
        let v = max(x, 0)
        return min((v * (2.51 * v + 0.03)) / (v * (2.43 * v + 0.59) + 0.14), 1)
    }
}

// MARK: - Flight

/// Where everything is on one frame of the flight — Android's `HeroFlightPose`.
struct HeroFlightPose: Equatable, Sendable {
    var terrainOffsetZ: Float
    var eye: SIMD3<Float>
    var target: SIMD3<Float>
    var rollDegrees: Float
    var helmet: SIMD3<Float>
    var helmetYawDegrees: Float
    var helmetEntrance: Float
    var terrainRise: Float

    /// The camera's up vector, rolled about the view axis.
    var up: SIMD3<Float> {
        let roll = rollDegrees * .pi / 180
        return SIMD3(sin(roll), cos(roll), 0)
    }
}

enum HeroFlight {
    /// Metres per second the valley streams under the camera.
    static let speed: Float = 5.5
    /// Seconds for one full side-to-side sway.
    static let swayPeriod: Float = 27
    static let helmetDegreesPerSecond: Float = 9
    static let entranceSeconds: Double = 0.9
    static let terrainRiseSeconds: Double = 1.1
    static let eyeHeight: Float = 2.3
    /// How far the terrain starts below its rest height before it rises in.
    static let terrainRiseUnits: Float = 2.5
    /// The helmet's longest side, in metres (Android `scaleToUnits`).
    static let helmetUnits: Float = 0.8

    /// The pose at `seconds` of flight. `tilt` leans the camera a touch with the
    /// phone; `entranceStart` / `terrainStart` are the clock readings at which
    /// the helmet and the terrain first appeared (nil: not yet). With `motion`
    /// off the flight holds its first frame and both entrances are complete.
    static func pose(seconds: Double, period: Float, tilt: SIMD2<Float> = .zero,
                     entranceStart: Double? = nil, terrainStart: Double? = nil,
                     motion: Bool = true) -> HeroFlightPose {
        let t: Float = motion ? Float(seconds) : 0
        let sway = 2 * Float.pi / swayPeriod
        let eyeX = sin(t * sway) * 0.7
        let eyeY = eyeHeight + sin(t * 0.37) * 0.08
        let roll = -cos(t * sway) * 2.6 + tilt.x * 1.5
        let yaw = tilt.x * 0.09 + sin(t * sway) * 0.03
        let pitch = -0.055 + tilt.y * 0.04

        func eased(since start: Double?, over duration: Double) -> Float {
            guard let start else { return 0 }
            guard motion else { return 1 }
            return easeOutCubic(Float(min(max((seconds - start) / duration, 0), 1)))
        }

        let offset = (t * speed).truncatingRemainder(dividingBy: period)
        return HeroFlightPose(
            terrainOffsetZ: offset < 0 ? offset + period : offset,
            eye: SIMD3(eyeX, eyeY, 0),
            target: SIMD3(eyeX + yaw * 10, eyeY + pitch * 10, -10),
            rollDegrees: roll,
            helmet: SIMD3(eyeX + 0.85, eyeY - 0.2 + sin(t * 0.8 + 1) * 0.05, -4.2),
            helmetYawDegrees: -28 + t * helmetDegreesPerSecond,
            helmetEntrance: eased(since: entranceStart, over: entranceSeconds),
            terrainRise: eased(since: terrainStart, over: terrainRiseSeconds)
        )
    }

    static func easeOutCubic(_ x: Float) -> Float {
        let inv = 1 - x
        return 1 - inv * inv * inv
    }
}

/// The flight's clock: advances only while the flight moves, one frame at a
/// time, with a single long frame (a hitch, a return from the background)
/// clamped so the valley never jumps.
struct HeroClock {
    static let maxFrameSeconds: Double = 0.1
    private(set) var seconds: Double = 0

    mutating func advance(by delta: Double, moving: Bool) {
        guard moving else { return }
        seconds += min(max(delta, 0), Self.maxFrameSeconds)
    }
}

/// The phone's lean, as a small, self-centring offset — Android's `HeroTilt`.
/// A fast low-pass follows the hand and a slow one learns the resting grip; the
/// flight leans on their difference, so it answers a tilt, then settles back.
struct HeroTilt {
    private let fastTau: Float = 0.15
    private let slowTau: Float = 4
    private let gain: Float = 0.25
    private let settleRange: Float = 0.12

    private var raw = SIMD2<Float>.zero
    private var fast = SIMD2<Float>.zero
    private var slow = SIMD2<Float>.zero
    private var primed = false
    private(set) var value = SIMD2<Float>.zero

    /// Core Motion's gravity, in g. Android's gravity sensor reads the opposite
    /// sign, which is why both axes flip against its `feed`.
    mutating func feed(gravityX: Double, gravityZ: Double) {
        raw = SIMD2(Float(min(max(gravityX, -1), 1)), Float(min(max(-gravityZ, -1), 1)))
    }

    mutating func update(delta: Float) {
        if !primed {
            fast = raw
            slow = raw
            primed = true
        }
        let dt = min(max(delta, 0), 0.25)
        fast += (raw - fast) * (1 - exp(-dt / fastTau))
        slow += (fast - slow) * (1 - exp(-dt / slowTau))
        value = simd_clamp((fast - slow) * gain / settleRange, SIMD2(repeating: -1), SIMD2(repeating: 1))
    }

    mutating func reset() {
        primed = false
        value = .zero
    }
}

private func smoothstep(_ edge0: Float, _ edge1: Float, _ x: Float) -> Float {
    let t = min(max((x - edge0) / (edge1 - edge0), 0), 1)
    return t * t * (3 - 2 * t)
}
