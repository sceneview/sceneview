import Foundation
import simd

/*
 * How the replay's camera moves: an orbit around the recorded room that frames it by itself —
 * a crane-in entrance, then a slow turntable drift — until the first touch, and back on a
 * double tap. The iOS twin of Android's `ArDebugOrbitCamera`, `ArDebugFraming`, `CameraRig`
 * and `ReplayIntro` (#4059): the same angles and the same entrance. Home is fitted to what
 * is drawn of the room, in the rectangle the chrome leaves, not to its bounding sphere.
 *
 * Pure Swift: the stage feeds it frame times and gestures and reads back a pose.
 */

/// A camera on a sphere around `target`: degrees, metres.
struct RerunOrbitPose: Equatable, Sendable {
    var target: SIMD3<Float>
    var azimuth: Float
    var elevation: Float
    var distance: Float

    /// Where the camera sits for this pose — the single spherical-to-world conversion.
    var eye: SIMD3<Float> {
        let a = azimuth * .pi / 180
        let e = elevation * .pi / 180
        let horizontal = distance * cos(e)
        return target + SIMD3(horizontal * sin(a), distance * sin(e), horizontal * cos(a))
    }

    /// Wraps `degrees` into (-180, 180].
    static func normalize(_ degrees: Float) -> Float {
        guard degrees.isFinite else { return 0 }
        var wrapped = degrees.truncatingRemainder(dividingBy: 360)
        if wrapped > 180 { wrapped -= 360 }
        if wrapped <= -180 { wrapped += 360 }
        return wrapped
    }

    /// Signed shortest way round from `from` to `to`.
    static func shortestDelta(_ from: Float, _ to: Float) -> Float { normalize(to - from) }

    /// `fraction` of the way from `from` to `to`: angles by the shortest arc, target in a
    /// straight line, distance geometrically (equal ratios per unit time read as constant speed).
    static func lerp(_ from: RerunOrbitPose, _ to: RerunOrbitPose, _ fraction: Float) -> RerunOrbitPose {
        let t = min(max(fraction, 0), 1)
        let d0 = from.distance.isFinite && from.distance > 1e-4 ? from.distance : to.distance
        let d1 = to.distance.isFinite && to.distance > 1e-4 ? to.distance : d0
        let distance = d0 * exp(log(d1 / d0) * t)
        return RerunOrbitPose(
            target: from.target + (to.target - from.target) * t,
            azimuth: from.azimuth + shortestDelta(from.azimuth, to.azimuth) * t,
            elevation: from.elevation + (to.elevation - from.elevation) * t,
            distance: distance.isFinite ? distance : d1
        )
    }

    /// Metres one screen pixel is worth at `distance` through a `verticalFov`-degree lens on a
    /// `heightPixels`-tall view.
    static func worldPerPixel(distance: Float, verticalFov: Float, heightPixels: Float) -> Float {
        guard heightPixels > 0, distance.isFinite, distance > 0 else { return 0 }
        let perPixel = 2 * distance * tan(verticalFov * .pi / 360) / heightPixels
        return perPixel.isFinite ? perPixel : 0
    }
}

/// What the replay frames: the take's path, planes and anchors.
struct RerunSubject: Equatable, Sendable {
    /// The middle of their bounds.
    var centre: SIMD3<Float>
    /// Every point the picture has to hold.
    var points: [SIMD3<Float>]
}

/// One run of the picture — side to side, or bottom to top — and the points that must stand in
/// it. A point is where it stands along the run, over the tangent of that half field of view,
/// and how deep it is, both from the subject's middle; the run's ends are in the picture's own
/// units, 1 being half the view.
private struct RerunSpan {
    let low: Float
    let high: Float
    private var over = -Float.greatestFiniteMagnitude
    private var under = -Float.greatestFiniteMagnitude

    init(low: Float, high: Float) {
        self.low = low
        self.high = high
    }

    mutating func hold(_ along: Float, _ depth: Float) {
        over = max(over, along - high * depth)
        under = max(under, low * depth - along)
    }

    /// The distance from which the points run exactly from one end to the other.
    var reach: Float { (over + under) / (high - low) }

    /// How far the picture is moved along the run, from `distance`, to put the points midway
    /// between its ends. `axis` picks the run in `seen`, whose `z` is the depth.
    func shift(_ seen: [SIMD3<Float>], axis: Int, distance: Float) -> Float {
        // The shifts that keep every point inside one end and the other.
        var least = under + low * distance
        var most = high * distance - over
        guard least.isFinite, most.isFinite else { return (low + high) / 2 * distance }
        guard least < most else { return (least + most) / 2 }
        for _ in 0..<RerunFraming.centringSteps {
            let shift = (least + most) / 2
            var first = Float.greatestFiniteMagnitude
            var last = -Float.greatestFiniteMagnitude
            for point in seen {
                let at = (point[axis] + shift) / max(distance + point.z, RerunFraming.minDistance)
                first = min(first, at)
                last = max(last, at)
            }
            if first + last < low + high { least = shift } else { most = shift }
        }
        return (least + most) / 2
    }
}

/// Where "home" is, and how the camera eases there.
enum RerunFraming {
    static let minDistance: Float = 0.25
    static let maxDistance: Float = 40
    static let minElevation: Float = -8
    static let maxElevation: Float = 88
    /// The three-quarter view from above-behind the Rerun viewer opens a spatial view with.
    static let homeElevation: Float = 32
    static let homeAzimuth: Float = 35
    /// The map's near-vertical view: a floor plan, with just enough tilt to keep depth.
    static let mapElevation: Float = 84
    /// The least of the view's height a chrome may leave to the room before the framing stops
    /// backing away from it.
    static let minBand: Float = 0.3
    /// A small session (the phone barely moved) still gets a room-sized view.
    static let minSubjectRadius: Float = 0.8
    /// Halvings that settle where the room sits in the band: past a ten-thousandth of it.
    static let centringSteps = 14
    /// Exponential approach to home, per second: ~95 % in one second.
    static let approachRate: Float = 3
    /// The SDK's default lens — 28 mm on a 24 mm-tall sensor, ~46.4° vertical.
    static let verticalFov: Float = 2 * atan(12 / 28) * 180 / .pi

    static let defaultPose = RerunOrbitPose(target: SIMD3(0, -0.4, -0.5), azimuth: homeAzimuth,
                                            elevation: homeElevation, distance: 3.2)

    static func clamp(_ pose: RerunOrbitPose) -> RerunOrbitPose {
        var out = pose
        out.elevation = min(max(pose.elevation, minElevation), maxElevation)
        out.distance = pose.distance.isFinite ? min(max(pose.distance, minDistance), maxDistance) : defaultPose.distance
        return out
    }

    /// The pose that fits `subject` in the clear rectangle of the view from `azimuth` and
    /// `elevation`, and the lift — a share of the view's height — that centres it there.
    ///
    /// The rectangle is the view less `inset` on every side (shares of its width and of its
    /// height) and, in height, only the `band` no chrome stands on, whose middle sits `bandLift`
    /// of the view's height above the view's own. What is fitted is every point of the subject
    /// as this camera sees it, not a sphere around them: the camera stands as close as the
    /// first pair of edges the room reaches allows — the sides on a tall window, the band's top
    /// and bottom on a wide one — and the room is centred between the other pair. Side to side
    /// that moves the pose's target; in height it is the lift, so the room keeps turning around
    /// its own middle. (A room seen from above shows more near floor than far ceiling: looked
    /// at through its centre, it sits low.)
    static func fit(_ subject: RerunSubject?, azimuth: Float, elevation: Float = homeElevation,
                    verticalFov: Float = verticalFov, aspect: Float, band: Float = 1, bandLift: Float = 0,
                    inset: SIMD2<Float> = .zero) -> (pose: RerunOrbitPose, lift: Float) {
        guard let subject, !subject.points.isEmpty else {
            var pose = defaultPose
            pose.azimuth = azimuth
            pose.elevation = elevation
            return (pose, bandLift)
        }
        var pose = clamp(RerunOrbitPose(target: subject.centre, azimuth: azimuth, elevation: elevation, distance: 1))
        let forward = simd_normalize(pose.target - pose.eye)
        let right = simd_normalize(simd_cross(forward, SIMD3(0, 1, 0)))
        let up = simd_cross(right, forward)
        let tanVertical = tan(verticalFov * .pi / 360)
        let tanHorizontal = tanVertical * min(max(aspect, 0.2), 5)
        let halfWidth = max(1 - 2 * inset.x, minBand)
        let halfHeight = max(min(max(band, minBand), 1) - 2 * inset.y, minBand / 2)
        var across = RerunSpan(low: -halfWidth, high: halfWidth)
        var upward = RerunSpan(low: 2 * bandLift - halfHeight, high: 2 * bandLift + halfHeight)

        var seen: [SIMD3<Float>] = []
        seen.reserveCapacity(subject.points.count)
        var near = -Float.greatestFiniteMagnitude
        for point in subject.points {
            let offset = point - subject.centre
            let depth = simd_dot(offset, forward)
            let at = SIMD3(simd_dot(offset, right) / tanHorizontal, simd_dot(offset, up) / tanVertical, depth)
            seen.append(at)
            across.hold(at.x, depth)
            upward.hold(at.y, depth)
            near = max(near, -depth)
        }
        // A session where the phone barely moved is framed as a room would be.
        let least = minSubjectRadius / min(halfWidth * tanHorizontal, halfHeight * tanVertical)
        pose.distance = max(max(across.reach, upward.reach), max(near + minDistance, least))
        pose = clamp(pose)
        let distance = pose.distance
        pose.target = subject.centre - right * (across.shift(seen, axis: 0, distance: distance) * tanHorizontal)
        return (pose, upward.shift(seen, axis: 1, distance: distance) / (2 * distance))
    }

    /// One frame of the ease from `pose` to `home`, snapping once close.
    static func approach(_ pose: RerunOrbitPose, home: RerunOrbitPose, delta: Float) -> RerunOrbitPose {
        let next = RerunOrbitPose.lerp(pose, home, 1 - exp(-approachRate * delta))
        let angleSettled = abs(RerunOrbitPose.shortestDelta(next.azimuth, home.azimuth)) < 0.01
            && abs(next.elevation - home.elevation) < 0.01
        let offset = abs(next.target.x - home.target.x) + abs(next.target.y - home.target.y) + abs(next.target.z - home.target.z)
        let reachSettled = abs(next.distance - home.distance) < 1e-4 && offset < 1e-4
        return angleSettled && reachSettled ? home : next
    }
}

/// The replay's entrance: a crane from high, far and turned onto the home framing.
enum RerunIntro {
    static let duration: Float = 2.8
    static let distanceFactor: Float = 2.3
    static let startElevation: Float = 68
    static let turn: Float = -75

    static func start(for home: RerunOrbitPose) -> RerunOrbitPose {
        RerunOrbitPose(target: home.target, azimuth: home.azimuth + turn, elevation: startElevation,
                       distance: home.distance * distanceFactor)
    }

    static func pose(from: RerunOrbitPose, to: RerunOrbitPose, progress: Float) -> RerunOrbitPose {
        RerunOrbitPose.lerp(from, to, ease(min(max(progress, 0), 1)))
    }

    /// `ease-expressive` from DESIGN.md — cubic-bezier(0.2, 0, 0, 1): leaves gently, lands softly.
    static func ease(_ t: Float) -> Float {
        if t <= 0 { return 0 }
        if t >= 1 { return 1 }
        let x1: Float = 0.2, x2: Float = 0, y1: Float = 0, y2: Float = 1
        func bx(_ s: Float) -> Float { 3 * (1 - s) * (1 - s) * s * x1 + 3 * (1 - s) * s * s * x2 + s * s * s }
        func by(_ s: Float) -> Float { 3 * (1 - s) * (1 - s) * s * y1 + 3 * (1 - s) * s * s * y2 + s * s * s }
        func dx(_ s: Float) -> Float { 3 * (1 - s) * (1 - s) * x1 + 6 * (1 - s) * s * (x2 - x1) + 3 * s * s * (1 - x2) }
        var s = t
        for _ in 0..<8 {
            let d = dx(s)
            if abs(d) < 1e-6 { continue }
            s = min(max(s - (bx(s) - t) / d, 0), 1)
        }
        if abs(bx(s) - t) > 1e-4 {
            var lo: Float = 0, hi: Float = 1
            s = t
            for _ in 0..<30 {
                if bx(s) < t { lo = s } else { hi = s }
                s = (lo + hi) / 2
            }
        }
        return by(s)
    }
}

/// Sizes in screen pixels turned into metres through `metresPerPixel` — the world size of one
/// pixel at the orbit target — so a point stays a point and a line a line at any zoom.
struct RerunStyle: Equatable, Sendable {
    var metresPerPixel: Float

    private func px(_ pixels: Float, _ lo: Float, _ hi: Float) -> Float { min(max(pixels * metresPerPixel, lo), hi) }

    var mapPointRadius: Float { px(2.4, 0.004, 0.05) }
    var livePointRadius: Float { px(3.8, 0.006, 0.07) }
    var trailRadius: Float { px(2.2, 0.004, 0.05) }
    var trailHeadRadius: Float { px(3.4, 0.006, 0.08) }
    var frustumEdge: Float { px(1.3, 0.002, 0.03) }
    var keyframeEdge: Float { px(0.9, 0.0015, 0.02) }
    var outlineHalfWidth: Float { px(1.3, 0.002, 0.03) }
    var gridMinorHalfWidth: Float { px(0.55, 0.001, 0.015) }
    var gridMajorHalfWidth: Float { px(0.9, 0.0015, 0.025) }
    var axisRadius: Float { px(1.6, 0.003, 0.04) }
    var anchorHalfWidth: Float { px(1.8, 0.003, 0.04) }

    static let bucket: Float = 1.25

    /// Quantised to steps of ×1.25: every step rebuilds meshes, so a pinch must not change it
    /// on every frame.
    static func forOrbit(distance: Float, verticalFov: Float, heightPixels: Float) -> RerunStyle {
        let raw = RerunOrbitPose.worldPerPixel(distance: distance, verticalFov: verticalFov, heightPixels: heightPixels)
        guard raw > 0, raw.isFinite else { return RerunStyle(metresPerPixel: 0.002) }
        let step = (log(raw) / log(bucket)).rounded(.down)
        return RerunStyle(metresPerPixel: pow(bucket, step))
    }
}

/// The orbit camera: follows `home` by itself until touched, with inertia after a fling.
struct RerunOrbitController: Sendable {
    static let degreesPerPixel: Float = 0.28
    static let maxSpin: Float = 360
    static let driftDegreesPerSecond: Float = 6
    static let inertiaDecayPerSecond: Float = 0.06
    static let inertiaStop: Float = 1.2

    private(set) var pose: RerunOrbitPose
    var home: RerunOrbitPose
    private(set) var following = true
    /// Slow turntable drift while following. Off in QA so captures are deterministic.
    var drift: Bool
    var hasFramedContent = false

    /// The map's near-vertical view. Switching hands the camera back to the automatic framing.
    var overhead = false {
        didSet { if overhead != oldValue { recenter() } }
    }

    var homeElevation: Float { overhead ? RerunFraming.mapElevation : RerunFraming.homeElevation }

    private var grabbing = false
    private var dragAzimuth: Float = 0
    private var dragElevation: Float = 0
    private var azimuthVelocity: Float = 0
    private var elevationVelocity: Float = 0
    private var followSeconds: Float = 0
    private var introFrom: RerunOrbitPose?
    private var introSeconds: Float = 0
    private var pinchStartDistance: Float?

    var introPlaying: Bool { introFrom != nil }

    init(pose: RerunOrbitPose = RerunFraming.defaultPose, drift: Bool = true) {
        self.pose = pose
        self.home = pose
        self.drift = drift
    }

    mutating func playIntro(from: RerunOrbitPose) {
        following = true
        azimuthVelocity = 0
        elevationVelocity = 0
        followSeconds = 0
        introSeconds = 0
        let start = RerunFraming.clamp(from)
        introFrom = start
        pose = start
    }

    /// Back to the automatic framing — the double tap, and the 3D button tapped again.
    mutating func recenter() {
        following = true
        followSeconds = 0
        azimuthVelocity = 0
        elevationVelocity = 0
    }

    mutating func snap(to target: RerunOrbitPose) { pose = RerunFraming.clamp(target) }

    // MARK: Gestures

    mutating func dragBegan() {
        takeOver()
        grabbing = true
    }

    /// A one-finger drag by (`dx`, `dy`) screen pixels since the last update.
    mutating func dragged(dx: Float, dy: Float) {
        guard dx != 0 || dy != 0 else { return }
        var next = pose
        next.azimuth -= dx * Self.degreesPerPixel
        next.elevation -= dy * Self.degreesPerPixel
        next = RerunFraming.clamp(next)
        dragAzimuth += next.azimuth - pose.azimuth
        dragElevation += next.elevation - pose.elevation
        pose = next
    }

    mutating func dragEnded() { grabbing = false }

    mutating func pinchBegan() {
        takeOver()
        pinchStartDistance = pose.distance
    }

    /// `magnification` is relative to the pinch's start: 2 = fingers twice as far apart.
    mutating func pinched(magnification: Float) {
        if pinchStartDistance == nil { pinchBegan() }
        guard let start = pinchStartDistance, magnification > 0 else { return }
        pose.distance = min(max(start / magnification, RerunFraming.minDistance), RerunFraming.maxDistance)
    }

    mutating func pinchEnded() { pinchStartDistance = nil }

    // MARK: Frame

    /// Integrates one frame of `delta` seconds.
    mutating func update(delta: Float) {
        guard delta.isFinite, delta > 0, delta < 0.25 else { return }
        if grabbing {
            azimuthVelocity = min(max(dragAzimuth / delta, -Self.maxSpin), Self.maxSpin)
            elevationVelocity = min(max(dragElevation / delta, -Self.maxSpin), Self.maxSpin)
        }
        dragAzimuth = 0
        dragElevation = 0
        if grabbing || pinchStartDistance != nil { return }

        if following {
            if let from = introFrom {
                introSeconds += delta
                let progress = introSeconds / RerunIntro.duration
                pose = RerunFraming.clamp(RerunIntro.pose(from: from, to: home, progress: progress))
                if progress >= 1 { introFrom = nil }
                return
            }
            followSeconds += delta
            if drift && !overhead {
                // Ramp in over two seconds so recentering does not lurch into a spin.
                let ramp = min(followSeconds / 2, 1)
                home.azimuth += Self.driftDegreesPerSecond * ramp * delta
            }
            pose = RerunFraming.approach(pose, home: home, delta: delta)
            return
        }

        if azimuthVelocity != 0 || elevationVelocity != 0 {
            var next = pose
            next.azimuth += azimuthVelocity * delta
            next.elevation += elevationVelocity * delta
            pose = RerunFraming.clamp(next)
            azimuthVelocity = Self.decay(azimuthVelocity, delta)
            elevationVelocity = Self.decay(elevationVelocity, delta)
        }
    }

    static func decay(_ velocity: Float, _ delta: Float) -> Float {
        guard velocity.isFinite, delta > 0 else { return 0 }
        let decayed = velocity * exp(log(inertiaDecayPerSecond) * delta)
        return abs(decayed) < inertiaStop ? 0 : decayed
    }

    private mutating func takeOver() {
        following = false
        introFrom = nil
        azimuthVelocity = 0
        elevationVelocity = 0
        // The home angle becomes the current one, so a recenter swings back from here.
        home.azimuth = pose.azimuth
    }

    /// Eye and look-at target with the picture lifted by `lift` of the view's height — a pedestal
    /// move along the camera's own up, so the room sits in the middle of the clear band.
    func eyeAndTarget(lift: Float, heightPixels: Float, verticalFov: Float = RerunFraming.verticalFov)
        -> (eye: SIMD3<Float>, target: SIMD3<Float>) {
        let eye = pose.eye
        let mpp = RerunOrbitPose.worldPerPixel(distance: pose.distance, verticalFov: verticalFov, heightPixels: heightPixels)
        guard lift != 0, mpp > 0 else { return (eye, pose.target) }
        let forward = simd_normalize(pose.target - eye)
        let right = simd_normalize(simd_cross(forward, SIMD3(0, 1, 0)))
        let up = simd_cross(right, forward)
        let drop = up * (lift * heightPixels * mpp)
        return (eye - drop, pose.target - drop)
    }
}
