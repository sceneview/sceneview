#if os(iOS)
import CoreGraphics
import Foundation
import simd

/// How a placed model moves under the fingers: drag smoothing, the glide after a throw, the twist
/// coast, the grow-in on first sight and the double-tap resize. Device-free, pinned by unit tests.
///
/// The behaviour and the constants follow ARMV, the SceneView Android AR model viewer app (same
/// names, Swift-cased). The double-tap and 100 % detent rules come from SceneView Android
/// `ScaleSnap`, which ARMV uses.
enum ARPlacementFeel {
    // MARK: Constants shared with ARMV

    /// How fast the model follows the finger during a drag, per second: each frame covers
    /// `1 − e^(−dragSpeed·dt)` of the way left, so touch jitter is smoothed without lag.
    static let dragSpeed: Float = 18
    /// The scale a first placement starts from before it grows in at
    /// ``ARInstantPlacement/settleSpeed``: the model appears where it stands instead of popping in.
    static let appearScale: Float = 0.01

    /// The glide after a thrown drag, in screen pixels per second (ARMV `moveCoast`). A drag
    /// released slower than 150 px/s stops where it is left.
    static func moveCoast() -> ARInertiaTracker {
        ARInertiaTracker(dimensions: 2, minSpeed: 8, maxSpeed: 4000, minReleaseSpeed: 150)
    }

    /// The spin after a flicked twist, in degrees per second (ARMV `twistCoast`).
    static func twistCoast() -> ARInertiaTracker {
        ARInertiaTracker(dimensions: 1, minSpeed: 5, maxSpeed: 900, minReleaseSpeed: 45)
    }

    /// Share of the remaining way covered in `delta` seconds at `speed` per second — SceneView
    /// Android's smooth transform. A stalled frame counts as 0.1 s at most; 1 for Reduce Motion.
    static func smoothing(delta: TimeInterval, speed: Float, reduceMotion: Bool = false) -> Float {
        if reduceMotion { return 1 }
        guard delta.isFinite, delta > 0, speed.isFinite, speed > 0 else { return 0 }
        return 1 - exp(-speed * Float(min(delta, 0.1)))
    }

    /// One frame of the glide: the pointer moves on by `step` pixels (`pixelScale` pixels per
    /// point); `nil` once it leaves `bounds`, which ends the glide (ARMV `glide`).
    static func glide(pointer: CGPoint, step: [Float], pixelScale: CGFloat, bounds: CGRect) -> CGPoint? {
        guard step.count == 2, pixelScale > 0, step.allSatisfy(\.isFinite) else { return nil }
        let next = CGPoint(x: pointer.x + CGFloat(step[0]) / pixelScale,
                           y: pointer.y + CGFloat(step[1]) / pixelScale)
        guard next.x >= bounds.minX, next.x <= bounds.maxX, next.y >= bounds.minY, next.y <= bounds.maxY else { return nil }
        return next
    }

    /// The yaw that makes +Z face `eye` from `point` (Android `yawToward`), in radians.
    static func yawToward(_ point: SIMD3<Float>, eye: SIMD3<Float>) -> Float {
        atan2(eye.x - point.x, eye.z - point.z)
    }

    /// The heading of `rotation` about +Y: where its +Z points on the floor, in the same
    /// convention as ``yawToward(_:eye:)``.
    static func heading(of rotation: simd_quatf) -> Float {
        let forward = rotation.act([0, 0, 1])
        return atan2(forward.x, forward.z)
    }

    /// `angle` wrapped to −π…π.
    static func wrap(_ angle: Float) -> Float {
        guard angle.isFinite else { return 0 }
        let turns = (angle / (2 * .pi)).rounded()
        return angle - turns * 2 * .pi
    }

    /// The user's twist (ARMV `twist`): how far a floor model's actual `heading` is turned from
    /// `facing`, the yaw that faced the user when it was last placed or recentered. A plane
    /// placement keeps the plane anchor's heading, so its twist starts at that gap, not at 0.
    static func twist(heading: Float, facing: Float) -> Float {
        wrap(heading - facing)
    }

    /// The world rotation a floor model gets when it is recentered to `point` (ARMV `recenter`):
    /// facing the user again, plus the user's own `twist` (radians about +Y) on top.
    static func recenteredRotation(at point: SIMD3<Float>, eye: SIMD3<Float>, twist: Float) -> simd_quatf {
        let yaw = yawToward(point, eye: eye) + (twist.isFinite ? twist : 0)
        return simd_quatf(angle: yaw.isFinite ? yaw : 0, axis: [0, 1, 0])
    }

    /// The scale the pivot shows: the pinch, with the 100 % rebound or the double-tap ease on top,
    /// times the grow-in of a first placement. Holds the user's own size for the double tap.
    struct ScaleDisplay {
        /// The pinch, 1 at 100 %: what ``ARPlacementController/scale`` publishes.
        private(set) var scale: Float = 1
        /// The last size the user chose away from 100 % (ARMV `rememberedScale`).
        private(set) var remembered: Float?
        private(set) var rebound: (elapsed: TimeInterval, fromAbove: Bool)?
        private(set) var ease: (from: Float, elapsed: TimeInterval)?
        /// The grow-in factor, ``appearScale`` to 1.
        private(set) var appear: Float = 1
        private var pinchStart: Float = 1

        /// What the pivot shows this frame.
        var shown: Float {
            let base: Float
            if let ease {
                base = ARScaleSnap.doubleTapProgress(from: ease.from, to: scale, elapsedMs: Float(ease.elapsed * 1000))
            } else if let rebound {
                base = scale * ARScaleSnap.rebound(elapsedMs: Float(rebound.elapsed * 1000), fromAbove: rebound.fromAbove)
            } else {
                base = scale
            }
            return base * appear
        }

        /// A pinch begins from the target size (``scale``), not the size shown: whatever still
        /// animates stops, so a pinch during the double-tap ease or the rebound jumps to it.
        mutating func pinchBegan() {
            pinchStart = scale
            rebound = nil
            ease = nil
        }

        /// One pinch update, `ratio` being the span over the span at the start (ARMV
        /// `pinchScale`, then ScaleSnap `step`). Returns the haptics to play.
        mutating func pinch(ratio: Float) -> [ARHapticEvent] {
            let raw = pinchStart * ratio
            guard raw.isFinite else { return [] }
            let previous = scale
            let step = ARScaleSnap.step(previousDisplayed: previous, raw: raw)
            scale = step.displayed
            var events: [ARHapticEvent] = []
            if step.enteredSnap {
                rebound = (0, previous > 1)
                events.append(.scaleSnapped)
            } else if !step.snapped {
                rebound = nil
                remembered = step.displayed
            }
            if step.enteredLimit { events.append(.limitReached) }
            return events
        }

        /// The double tap (ARMV `toggleScale`): back to 100 %, or from 100 % back to the user's
        /// own size; with nowhere to go, the 100 % rebound plays. Returns the haptics to play.
        mutating func toggle(reduceMotion: Bool = false) -> [ARHapticEvent] {
            guard let to = ARScaleSnap.doubleTapTarget(current: scale, remembered: remembered) else {
                ease = nil
                rebound = (0, false)
                return []
            }
            var events: [ARHapticEvent] = []
            if to == 1 {
                remembered = scale
                events.append(.scaleSnapped)
            }
            ease = reduceMotion ? nil : (scale, 0)
            rebound = nil
            scale = to
            return events
        }

        /// An exact size (``ARPlacementController/scale(to:)``), clamped to 25–400 %, no detent.
        mutating func set(_ value: Float) -> [ARHapticEvent] {
            guard value.isFinite else { return [] }
            let previous = scale
            rebound = nil
            ease = nil
            scale = Swift.max(ARScaleSnap.min, Swift.min(ARScaleSnap.max, value))
            if abs(scale - 1) > 0.001 { remembered = scale }
            var events: [ARHapticEvent] = []
            if (previous < 1 && scale >= 1) || (previous > 1 && scale <= 1) { events.append(.scaleSnapped) }
            if ARScaleSnap.isAtLimit(scale) && !ARScaleSnap.isAtLimit(previous) { events.append(.limitReached) }
            return events
        }

        /// A first placement: start at ``appearScale`` and grow in.
        mutating func beginAppear(reduceMotion: Bool = false) {
            appear = reduceMotion ? 1 : appearScale
        }

        /// Advances the animations by `delta` seconds. `true` while one still runs.
        @discardableResult
        mutating func step(by delta: TimeInterval, reduceMotion: Bool = false) -> Bool {
            guard delta.isFinite, delta > 0 else { return animating }
            if let current = ease {
                let elapsed = current.elapsed + delta
                ease = reduceMotion || Float(elapsed * 1000) >= ARScaleSnap.doubleTapMs ? nil : (current.from, elapsed)
            }
            if let current = rebound {
                let elapsed = current.elapsed + delta
                rebound = Float(elapsed * 1000) >= ARScaleSnap.reboundMs ? nil : (elapsed, current.fromAbove)
            }
            if appear < 1 {
                appear += (1 - appear) * smoothing(delta: delta, speed: ARInstantPlacement.settleSpeed,
                                                   reduceMotion: reduceMotion)
                if 1 - appear < 0.001 { appear = 1 }
            }
            return animating
        }

        var animating: Bool { ease != nil || rebound != nil || appear < 1 }

        /// A new placement: 100 %, nothing remembered, nothing animating.
        mutating func reset() { self = ScaleDisplay() }
    }
}

/// Measures the velocity of a gesture and coasts it after release — a Swift port of SceneView
/// Android's `InertiaTracker` (sceneview-core `gesture/Inertia.kt`), same maths and constants.
///
/// - Velocity: a least-squares line through the samples of the last ``velocityWindow`` before
///   release. A finger that rested more than ``stoppedGap`` before lifting releases at zero.
/// - Decay: exact exponential integration, `Δx = v·τ·(1 − e^(−Δt/τ))`, `v ← v·e^(−Δt/τ)`, so the
///   trajectory is the same at 30, 60 or 120 Hz.
/// - Cutoff: the coast stops under ``minSpeed`` and never starts from a release slower than
///   ``minReleaseSpeed``; the release speed is clamped to ``maxSpeed``.
/// - Interrupt: ``stop()`` (a new touch) or a new sample ends a coast at once.
struct ARInertiaTracker {
    /// Time for the coast speed to fall to ~37 % (Android `Inertia.DEFAULT_TIME_CONSTANT_SECONDS`).
    static let timeConstant: Float = 0.35
    /// How much of the end of the gesture the release velocity is measured over.
    static let velocityWindow: TimeInterval = 0.1
    /// A finger still for this long before lifting releases at zero velocity.
    static let stoppedGap: TimeInterval = 0.04
    static let minWindow: TimeInterval = 0.016
    static let capacity = 20

    let dimensions: Int
    let minSpeed: Float
    let maxSpeed: Float
    let minReleaseSpeed: Float

    private var times: [TimeInterval]
    private var values: [[Float]]
    private var count = 0
    private var head = 0
    private(set) var velocity: [Float]
    /// `true` while a released gesture still coasts.
    private(set) var isCoasting = false

    init(dimensions: Int, minSpeed: Float, maxSpeed: Float = .infinity, minReleaseSpeed: Float? = nil) {
        precondition(dimensions > 0)
        self.dimensions = dimensions
        self.minSpeed = minSpeed
        self.maxSpeed = maxSpeed
        self.minReleaseSpeed = minReleaseSpeed ?? minSpeed
        times = Array(repeating: 0, count: Self.capacity)
        values = Array(repeating: Array(repeating: 0, count: dimensions), count: Self.capacity)
        velocity = Array(repeating: 0, count: dimensions)
    }

    /// The coast speed (norm of the velocity), 0 when not coasting.
    var speed: Float { isCoasting ? Self.norm(velocity) : 0 }

    /// Forgets the previous gesture and stops any coast. Call it on touch-down.
    mutating func reset() {
        count = 0
        head = 0
        stop()
    }

    /// Ends a coast where it is.
    mutating func stop() {
        isCoasting = false
        velocity = Array(repeating: 0, count: dimensions)
    }

    /// Records the gesture's cumulative position at `time`; stops a coast still in flight.
    mutating func addSample(time: TimeInterval, _ position: [Float]) {
        precondition(position.count == dimensions)
        if isCoasting { stop() }
        values[head] = position
        times[head] = time
        head = (head + 1) % Self.capacity
        if count < Self.capacity { count += 1 }
    }

    /// The finger lifted at `time`: measures the release velocity and starts the coast.
    /// `true` when the movement coasts, `false` when it stops here.
    @discardableResult
    mutating func release(time: TimeInterval) -> Bool {
        stop()
        guard count >= 2 else { return false }
        let newest = index(count - 1)
        if time - times[newest] > Self.stoppedGap { return false }
        let window = max(Self.velocityWindow, Self.minWindow)
        var used = 0
        while used < count {
            let sample = index(count - 1 - used)
            if used >= 2 && times[newest] - times[sample] > window { break }
            used += 1
        }
        guard fitVelocity(used: used) else { return false }
        let speed = Self.norm(velocity)
        if speed > maxSpeed, speed > 0 { velocity = velocity.map { $0 * maxSpeed / speed } }
        isCoasting = velocity.allSatisfy(\.isFinite) && Self.norm(velocity) >= max(minSpeed, minReleaseSpeed)
        if !isCoasting { velocity = Array(repeating: 0, count: dimensions) }
        return isCoasting
    }

    /// The longest frame a coast advances by: a stalled frame counts as 0.1 s (ARMV clamps its
    /// frame delta to 0…0.1 s), so the model never jumps after a hitch.
    static let maxFrameDelta: Float = 0.1

    /// Advances the coast by `delta` seconds, clamped to 0…``maxFrameDelta``: the distance
    /// travelled during that step (zeros when not coasting) and whether the coast goes on after it.
    mutating func advance(by delta: Float) -> (step: [Float], goesOn: Bool) {
        var out = Array(repeating: Float(0), count: dimensions)
        guard isCoasting else { return (out, false) }
        let dt = delta.isFinite ? min(max(0, delta), Self.maxFrameDelta) : 0
        let tau = Self.timeConstant
        let decay = exp(-dt / tau)
        let travel = tau * (1 - decay)
        for axis in 0..<dimensions {
            out[axis] = velocity[axis] * travel
            velocity[axis] *= decay
        }
        if Self.norm(velocity) < minSpeed { stop() }
        return (out, isCoasting)
    }

    private func index(_ i: Int) -> Int { (head - count + i + Self.capacity * 2) % Self.capacity }

    /// Least-squares slope through the `used` newest samples, written to `velocity`.
    private mutating func fitVelocity(used: Int) -> Bool {
        guard used >= 2 else { return false }
        let range = (count - used)..<count
        let t0 = times[index(count - 1)]
        let meanT = range.reduce(0.0) { $0 + times[index($1)] - t0 } / Double(used)
        let varT = range.reduce(0.0) { sum, i in
            let d = times[index(i)] - t0 - meanT
            return sum + d * d
        }
        guard varT > 0 else { return false }
        for axis in 0..<dimensions {
            let meanV = range.reduce(0.0) { $0 + Double(values[index($1)][axis]) } / Double(used)
            let cov = range.reduce(0.0) { sum, i in
                let j = index(i)
                return sum + (times[j] - t0 - meanT) * (Double(values[j][axis]) - meanV)
            }
            velocity[axis] = Float(cov / varT)
        }
        return true
    }

    private static func norm(_ v: [Float]) -> Float { sqrt(v.reduce(0) { $0 + $1 * $1 }) }
}
#endif
