import Foundation
import simd

// The ringed world of the Cosmos Star scene: its orbit, the camera poses that frame it and the
// eased flight between them — the Swift twin of Android's `CosmosSystem`, `CosmosRig` and
// `CosmosFlight` (#4192). Pure functions of time and viewport aspect, like `CosmosFraming`, so
// the choreography is covered by `CosmosSystemTests` and the QA captures are reproducible.
//
// Units are the star's: the plasma star is a unit sphere at the origin. The only difference
// from Android is the lens: SceneView's camera is 60° high where Android's 28 mm is 46°, so
// every fit goes through `CosmosFraming.tanHalfVerticalFov` and frames the same rectangle.

/// Where the Star scene's camera is looking: the whole system, the star up close, or the ringed
/// world it follows along its orbit.
enum CosmosFocus: CaseIterable, Sendable {
    case system, star, planet

    /// The caption above the dock while the Star scene looks here.
    var caption: String {
        switch self {
        case .system: "Tap the ringed world to fly to it"
        case .star: "A hot blue star and its magnetic loops"
        case .planet: "A ringed world in blue starlight"
        }
    }

    /// Where the autopilot flies from here: the ringed world, then the star, then back out to
    /// the whole system.
    var autopilotNext: CosmosFocus {
        switch self {
        case .system: .planet
        case .planet: .star
        case .star: .system
        }
    }
}

/// The Star scene's camera on its own: `hold` seconds after it lands, with no touch meanwhile,
/// it flies on to the next look (`CosmosFocus.autopilotNext`), round and round. A touch hands
/// the camera to the user, and the autopilot takes it back after `resume` idle seconds.
struct CosmosAutopilot: Sendable {
    static let hold: Float = 4
    static let resume: Float = 8

    private(set) var idle: Float = 0
    private(set) var wait: Float = hold

    mutating func touched() {
        idle = 0
        wait = Self.resume
    }

    mutating func reset() {
        idle = 0
        wait = Self.hold
    }

    /// Advances the idle clock by `dt` seconds; returns where to fly once due. A flight under
    /// way keeps the clock at zero, so the hold counts from the landing.
    mutating func advance(_ dt: Float, flying: Bool, focus: CosmosFocus) -> CosmosFocus? {
        if flying {
            idle = 0
            return nil
        }
        idle += max(dt, 0)
        guard idle >= wait else { return nil }
        reset()
        return focus.autopilotNext
    }
}

/// A camera pose: where it is, what it looks at, and which way is up.
struct CosmosPose: Equatable, Sendable {
    var eye: SIMD3<Float>
    var target: SIMD3<Float>
    var up: SIMD3<Float>
}

/// The system for one viewport aspect. What depends on the aspect alone — the orbit frame, its
/// normal, the orbit's sample points — is computed once, in `init`; the engine keeps one and
/// makes a new one when the viewport turns.
struct CosmosSystem: Sendable {
    static let orbitRadius: Float = 3.3
    static let planetRadius: Float = 0.3
    static let ringInner: Float = 0.42
    static let ringOuter: Float = 0.74

    /// Degrees the planet travels along its orbit per second.
    static let orbitDegreesPerSecond: Float = 6
    /// Where it is at time zero: right of the star and a little beyond it, so its day side shows.
    static let orbitPhaseDegrees: Float = 7
    /// Degrees the planet turns about its own axis per second.
    static let spinDegreesPerSecond: Float = 9
    /// How far the planet's spin axis leans from the orbit's normal, toward the camera.
    static let obliquityDegrees: Float = 22
    /// How far the orbit plane is tipped toward the camera, so its ellipse opens up.
    static let inclinationDegrees: Float = 9
    /// Arc of the orbit trail behind the planet, in degrees.
    static let trailDegrees: Float = 110

    /// The share of the viewport's half extents the system may fill: room for the chrome.
    static let systemCoverX: Float = 0.9
    static let systemCoverY: Float = 0.6
    /// A flight, start to settle, in seconds.
    static let flySeconds: Float = 1.4

    /// How far above the orbit plane the overview looks down: enough to open the ellipse.
    static let systemElevationDegrees: Float = 16
    /// The overview never comes closer than this, whatever the aspect.
    static let minSystemDistance: Float = 1.5
    /// A hair of slack over the exact fit, so float rounding never puts a point on the edge.
    static let fitMargin: Float = 1.001
    /// How far above the orbit plane the follow camera sits, in degrees.
    static let followElevationDegrees: Float = 25
    /// How far the follow camera swings from straight out (star behind the planet) to behind it.
    static let followSwingDegrees: Float = 55
    /// Where the planet sits under the follow camera, as a share of the half height below centre.
    static let followPlanetBelow: Float = 0.3
    /// The rings' share of the follow view: they fill 1 / this of its half extent.
    static let planetViewRoom: Float = 1.35
    /// The trail is gone within this many follow distances of the planet, whole beyond the next.
    static let trailHiddenWithin: Float = 1.3
    static let trailShownBeyond: Float = 2.2

    static let orbitSamples = 36
    static let deg = Float.pi / 180

    let aspect: Float
    /// The orbit plane's orientation: rolled about the view axis so the orbit's long axis runs
    /// along the viewport's diagonal (a portrait phone has more room corner to corner than
    /// across), then tipped toward the camera.
    let frame: simd_quatf
    /// The orbit plane's normal in world space.
    let normal: SIMD3<Float>
    private let orbitPoints: [SIMD3<Float>]

    init(aspect: Float) {
        self.aspect = aspect
        let roll = atan2(Self.systemCoverY, Self.systemCoverX * max(aspect, 0.1)) * 0.9
        frame = simd_quatf(angle: roll, axis: SIMD3(0, 0, 1))
            * simd_quatf(angle: Self.inclinationDegrees * Self.deg, axis: SIMD3(1, 0, 0))
        normal = frame.act(SIMD3(0, 1, 0))
        let frame = frame
        orbitPoints = (0..<Self.orbitSamples).map { i in
            let a = Float(i) * 2 * .pi / Float(Self.orbitSamples)
            return frame.act(SIMD3(Self.orbitRadius * cos(a), 0, -Self.orbitRadius * sin(a)))
        }
    }

    // MARK: Orbit

    /// The planet's angle along its orbit at `time`, in radians.
    static func orbitAngle(_ time: Float) -> Float {
        (orbitPhaseDegrees + orbitDegreesPerSecond * time) * deg
    }

    /// The planet's turn about its own axis at `time`, in radians.
    static func spinAngle(_ time: Float) -> Float {
        spinDegreesPerSecond * time * deg
    }

    /// The point of the orbit at angle `a`, in world space.
    func orbitPoint(_ a: Float) -> SIMD3<Float> {
        frame.act(SIMD3(Self.orbitRadius * cos(a), 0, -Self.orbitRadius * sin(a)))
    }

    /// The planet's centre in world space at `time`.
    func planetPosition(_ time: Float) -> SIMD3<Float> {
        orbitPoint(Self.orbitAngle(time))
    }

    /// The planet's frame without its spin: the spin axis (local Y) leans off the orbit normal
    /// and holds still; the rings lie in its local XZ plane. The spin itself turns the surface
    /// inside the baked texture (`CosmosWorld`), so the light baked on it stays on the star side.
    var tilt: simd_quatf {
        frame * simd_quatf(angle: Self.obliquityDegrees * Self.deg, axis: SIMD3(1, 0, 0))
    }

    /// The planet's full orientation at `time`, spin included — Android's `planetRotation`.
    func planetRotation(_ time: Float) -> simd_quatf {
        tilt * simd_quatf(angle: Self.spinAngle(time), axis: SIMD3(0, 1, 0))
    }

    // MARK: Camera

    /// The camera pose for `focus` at `time`.
    func pose(_ focus: CosmosFocus, time: Float) -> CosmosPose {
        switch focus {
        case .system: systemPose(time)
        case .star: starPose(time)
        case .planet: planetPose(time)
        }
    }

    /// The star up close: the Star scene's own framing.
    func starPose(_ time: Float) -> CosmosPose {
        let p = CosmosFraming.pose(.star, time: time, aspect: aspect)
        return CosmosPose(eye: CosmosFraming.orbit(p.distance, p.elevation, p.yaw), target: .zero, up: SIMD3(0, 1, 0))
    }

    /// The whole system, drifting a little, from the nearest distance at which every point of
    /// the orbit, rings included, lands inside the viewport's safe area. The camera looks at the
    /// origin, so its axes depend on the drift only: for each orbit point the fit is linear in
    /// the distance, and the nearest fitting distance is a max over the orbit's samples.
    func systemPose(_ time: Float) -> CosmosPose {
        let elevation = (Self.systemElevationDegrees + 2 * sin(time * 0.09)) * Self.deg
        let yaw = (5 * sin(time * 0.1)) * Self.deg
        let d = SIMD3(cos(elevation) * sin(yaw), sin(elevation), cos(elevation) * cos(yaw))
        let right = simd_normalize(SIMD3(d.z, 0, -d.x))
        let up = simd_cross(right, -d)
        let tan = CosmosFraming.tanHalfVerticalFov
        let needX = tan * aspect * Self.systemCoverX
        let needY = tan * Self.systemCoverY
        var distance = Self.minSystemDistance
        for p in orbitPoints {
            let alongForward = -simd_dot(p, d)
            let across = abs(simd_dot(p, right))
            let upward = abs(simd_dot(p, up))
            let depth = max((across + Self.ringOuter) / needX, (upward + Self.ringOuter) / needY)
            distance = max(distance, depth - alongForward)
        }
        distance *= Self.fitMargin
        return CosmosPose(eye: d * distance, target: .zero, up: SIMD3(0, 1, 0))
    }

    /// How far the follow camera stands from the planet: the rings with room around them.
    var planetViewDistance: Float {
        let half = Self.ringOuter * Self.planetViewRoom
        return CosmosFraming.fitDistance(half, half, aspect: aspect)
    }

    /// Beside the ringed world, following it round its orbit: behind it, outside the orbit and
    /// above its plane, rolled so the star's limb rises over the top of the frame and lights a
    /// crescent on the planet below it.
    func planetPose(_ time: Float) -> CosmosPose {
        let p = planetPosition(time)
        let outward = simd_normalize(p)
        let backward = simd_normalize(simd_cross(outward, normal))
        let swing = Self.followSwingDegrees * Self.deg
        let e = Self.followElevationDegrees * Self.deg
        let toEye = simd_normalize((outward * cos(swing) + backward * sin(swing)) * cos(e) + normal * sin(e))
        let d = planetViewDistance
        let eye = p + toEye * d
        // Roll so the star stands straight above the planet, and tip the view up until the
        // planet sits a little below centre: the star's limb rises over the top edge.
        let toPlanet = -toEye
        let toStar = simd_normalize(-eye)
        let apart = acos(min(max(simd_dot(toPlanet, toStar), -1), 1))
        let tip = atan(Self.followPlanetBelow * CosmosFraming.tanHalfVerticalFov)
        let look = Self.slerp(toPlanet, toStar, min(max(tip / apart, 0), 1))
        let up = simd_normalize(toStar - look * simd_dot(toStar, look))
        return CosmosPose(eye: eye, target: eye + look * d, up: up)
    }

    /// A pose between `from` and `to` at `t` in 0...1. The eye travels round the star, not
    /// through it: its direction from the origin turns (slerp) while its distance eases, so a
    /// flight from one side of the system to the other never crosses the plasma.
    static func blend(_ from: CosmosPose, _ to: CosmosPose, _ t: Float) -> CosmosPose {
        let r0 = max(simd_length(from.eye), 1e-9)
        let r1 = max(simd_length(to.eye), 1e-9)
        let direction = slerp(from.eye / r0, to.eye / r1, t)
        let r = r0 + (r1 - r0) * t
        return CosmosPose(eye: direction * r,
                          target: from.target + (to.target - from.target) * t,
                          up: simd_normalize(from.up + (to.up - from.up) * t))
    }

    /// The design system's `ease-expressive`, cubic-bezier(0.2, 0, 0, 1): a quick start that
    /// settles long and soft, like the app's camera fly-in.
    static func easeExpressive(_ t: Float) -> Float {
        if t <= 0 { return 0 }
        if t >= 1 { return 1 }
        // x(s) is monotonic on [0, 1]: bisect for the parameter whose x is t, return its y.
        var lo: Float = 0
        var hi: Float = 1
        for _ in 0..<24 {
            let s = 0.5 * (lo + hi)
            if bezier(s, 0.2, 0) < t { lo = s } else { hi = s }
        }
        return bezier(0.5 * (lo + hi), 0, 1)
    }

    private static func bezier(_ s: Float, _ p1: Float, _ p2: Float) -> Float {
        let u = 1 - s
        return 3 * u * u * s * p1 + 3 * u * s * s * p2 + s * s * s
    }

    /// How much of the orbit trail to draw with the camera's eye at `eye`: all of it from afar,
    /// none from the follow view, where the arc behind the planet runs past the lens and would
    /// cross the foreground as a bright streak.
    func trailVisibility(eye: SIMD3<Float>, time: Float) -> Float {
        let gap = simd_length(eye - planetPosition(time))
        let near = planetViewDistance
        return Self.smoothstep(near * Self.trailHiddenWithin, near * Self.trailShownBeyond, gap)
    }

    // MARK: Touch

    /// What a tap at (`x`, `y`) — y down — on a `width` × `height` viewport lands on, with the
    /// camera at `pose` at `time`: the planet (rings included) first, being in front, then the
    /// star; `nil` for empty space. A target smaller than `minRadius` on screen is hit within
    /// that radius, so the planet stays easy to tap from the far view.
    func hit(pose: CosmosPose, time: Float, width: Float, height: Float,
             x: Float, y: Float, minRadius: Float) -> CosmosFocus? {
        guard width > 0, height > 0 else { return nil }
        let aspect = width / height
        let targets: [(CosmosFocus, SIMD3<Float>, Float)] = [
            (.planet, planetPosition(time), Self.ringOuter),
            (.star, .zero, 1),
        ]
        for (focus, center, radius) in targets {
            guard let s = Self.project(pose, aspect: aspect, center) else { continue }
            let px = (s.x + 1) * 0.5 * width
            let py = (1 - s.y) * 0.5 * height
            let radiusOnScreen = radius / (s.z * CosmosFraming.tanHalfVerticalFov) * 0.5 * height
            if simd_length(SIMD2(x - px, y - py)) <= max(radiusOnScreen, minRadius) { return focus }
        }
        return nil
    }

    /// Where world point `p` lands for a camera at `pose`: normalised device x and y in −1...1
    /// across the viewport, and the depth along the view axis; `nil` behind the camera.
    static func project(_ pose: CosmosPose, aspect: Float, _ p: SIMD3<Float>) -> SIMD3<Float>? {
        let f = simd_normalize(pose.target - pose.eye)
        let r = simd_normalize(simd_cross(f, pose.up))
        let u = simd_cross(r, f)
        let rel = p - pose.eye
        let depth = simd_dot(rel, f)
        guard depth > 1e-3 else { return nil }
        let tanV = CosmosFraming.tanHalfVerticalFov
        return SIMD3(simd_dot(rel, r) / (depth * tanV * aspect), simd_dot(rel, u) / (depth * tanV), depth)
    }

    // MARK: Rings

    /// Ring opacity at a normalised radius `x` in 0...1 across the ring: a dim inner ring, a
    /// bright middle one, a dark gap (Cassini's) and a thinner outer ring, with fine ringlets.
    /// The profile of `cosmos_ring.mat`, and of the ring shadow in `cosmos_planet.mat`.
    static func ringDensity(_ x: Float) -> Float {
        let inside = smoothstep(0, 0.03, x) * (1 - smoothstep(0.97, 1, x))
        let body = mix(0.3, 1, smoothstep(0.12, 0.3, x)) * mix(1, 0.65, smoothstep(0.66, 0.72, x))
        let gap = smoothstep(0.012, 0.03, abs(x - 0.62))
        let fine = 0.68 + 0.32 * sin(x * 97) * sin(x * 41 + 1.3)
        return inside * body * gap * fine
    }

    // MARK: Helpers

    /// Spherical interpolation of unit vectors.
    static func slerp(_ a: SIMD3<Float>, _ b: SIMD3<Float>, _ t: Float) -> SIMD3<Float> {
        let angle = acos(min(max(simd_dot(a, b), -1), 1))
        if angle < 1e-4 { return simd_normalize(a + (b - a) * t) }
        let s = sin(angle)
        return a * (sin((1 - t) * angle) / s) + b * (sin(t * angle) / s)
    }

    static func smoothstep(_ e0: Float, _ e1: Float, _ x: Float) -> Float {
        let t = min(max((x - e0) / (e1 - e0), 0), 1)
        return t * t * (3 - 2 * t)
    }

    static func mix(_ a: Float, _ b: Float, _ t: Float) -> Float { a + (b - a) * t }
}

/// The Star scene's camera between two looks: the pose it left from, and how far along the
/// `CosmosSystem.flySeconds` flight it is. The target is re-evaluated every frame, so a flight
/// to the planet lands on it wherever its orbit has carried it meanwhile.
struct CosmosFlight: Sendable {
    /// The last pose drawn and the scene time it was drawn at: what a tap is tested against.
    private(set) var lastPose: CosmosPose?
    private(set) var lastTime: Float = 0

    private var from: CosmosPose?
    private var progress: Float = 1
    private var lastStamp: Double?

    /// Clamp a hitch, so a stall does not skip the flight.
    private static let maxStepSeconds: Double = 0.1

    /// Whether a flight is under way.
    var flying: Bool { from != nil }

    /// Takes off from the pose on screen now. Nothing drawn yet: there is no pose to leave
    /// from, so the first frame lands.
    mutating func start() {
        from = lastPose
        progress = 0
        lastStamp = nil
    }

    mutating func reset() {
        from = nil
        progress = 1
        lastStamp = nil
    }

    mutating func record(_ pose: CosmosPose, time: Float) {
        lastPose = pose
        lastTime = time
    }

    /// The pose for this frame, at `now` seconds: eased from the take-off pose toward `target`,
    /// or `target` once landed. `instant` lands at once: reduced motion, or QA captures that
    /// must show the end pose.
    mutating func advance(now: Double, target: CosmosPose, instant: Bool) -> CosmosPose {
        guard let from else { return target }
        if instant {
            progress = 1
        } else if let lastStamp {
            progress += Float(min(max(now - lastStamp, 0), Self.maxStepSeconds)) / CosmosSystem.flySeconds
        }
        lastStamp = now
        if progress >= 1 {
            self.from = nil
            return target
        }
        return CosmosSystem.blend(from, target, CosmosSystem.easeExpressive(progress))
    }
}
