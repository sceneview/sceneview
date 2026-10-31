#if os(iOS) || os(macOS) || os(visionOS)
import Foundation
import RealityKit
import simd

// Keyframed camera paths: a pose timeline sampled by time, the three classic shots built on
// it (orbit, travelling, dolly) and a frame-drivable player. The Swift counterpart of
// SceneView Android's `CameraPose`, `CameraTimeline`, `CameraPosePath` and
// `CameraPathPlayer` (`io.github.sceneview.camera`): same sampling rules, same presets,
// same player state machine. A path built from the same keys samples the same poses on both
// platforms; the presets frame with each platform's own fit and defaults (below), so an
// orbit built with default arguments is not guaranteed to match Android's frame for frame.
//
// ## What differs from Android, and why
//
// - Swift spellings: `CameraPathInterpolation.linear` / `.catmullRom`, `seek(to:)` for
//   `seekTo`, and easing is the ``CameraEasing`` value type instead of a `(Float) -> Float`
//   lambda, so paths stay `Sendable`.
// - Kotlin's `require` failures are `precondition`s here: a non-finite keyframe position or
//   an invalid preset argument is a programming error, as it is on Android.
// - ``CameraPosePath/orbit(center:extents:verticalFovDegrees:aspect:durationSeconds:turns:elevationDegrees:startAzimuthDegrees:margin:loop:keyframesPerTurn:)``
//   fits its radius with ``CameraControls/fitRadius(boundsExtents:fovYDegrees:aspect:margin:)``,
//   the fit every iOS `SceneView` uses, so an orbit opens at the distance the scene's own
//   auto-framing would pick (Android uses its `fitDistanceForBounds`). Breathing room is the
//   iOS fit's `margin` multiplier (default ``CameraControls/defaultFitMargin``, 1.15) where
//   Android takes an additive `padding` (default 0.15).
// - `verticalFovDegrees` defaults to 60° here; Android requires it (its demo passes the
//   default camera's 28 mm lens). Pass the field of view your camera really uses.
// - Only the `center:extents:` orbit takes `keyframesPerTurn`; ``CameraPosePath/orbit(bounds:verticalFovDegrees:aspect:durationSeconds:turns:elevationDegrees:startAzimuthDegrees:margin:loop:)``
//   keeps the default density (``CameraPosePath/orbitKeyframesPerTurn``), like Android's
//   `orbit(bounds, …)`.
// - Laps are counted in a saturating `Int64`: a time or playhead so large it has no lap
//   number pins at the extreme instead of trapping, as Kotlin's `toLong()` saturates.
// - A ``SceneView`` camera is an orbit camera: it follows a free pose through
//   ``SceneCameraPose/init(_:focusDistance:)`` (or ``CameraControls/follow(_:focusDistance:)``),
//   which keeps the eye and the look direction exact but cannot represent roll, and clamps
//   the pitch to ``CameraControls/maxElevation`` (±85° by default).

// MARK: - CameraPose

/// A free camera pose: where the camera is and which way it faces.
///
/// The camera looks down its local `-Z` with `+Y` up — the RealityKit (and Filament)
/// convention — so ``forward`` is `quaternion` applied to `(0, 0, -1)`.
public struct CameraPose: Equatable, Sendable {
    /// World-space eye position, in scene units.
    public var position: SIMD3<Float>
    /// World-space orientation of the camera.
    public var quaternion: simd_quatf

    public init(
        position: SIMD3<Float> = .zero,
        quaternion: simd_quatf = simd_quatf(ix: 0, iy: 0, iz: 0, r: 1)
    ) {
        self.position = position
        self.quaternion = quaternion
    }

    /// The direction the camera looks: local `-Z` in world space.
    public var forward: SIMD3<Float> { quaternion.act(SIMD3<Float>(0, 0, -1)) }

    /// The camera's right-hand side: local `+X` in world space.
    public var right: SIMD3<Float> { quaternion.act(SIMD3<Float>(1, 0, 0)) }

    /// The camera's up: local `+Y` in world space.
    public var up: SIMD3<Float> { quaternion.act(SIMD3<Float>(0, 1, 0)) }

    /// The pose as a RealityKit transform, for a camera entity you place yourself.
    public var transform: Transform {
        Transform(scale: .one, rotation: quaternion, translation: position)
    }

    /// The world's up axis, the default `up` for ``lookingAt(eye:target:up:)``.
    public static let worldUp = SIMD3<Float>(0, 1, 0)

    /// A camera at `eye` looking at `target`, rolled so `up` stays up.
    ///
    /// When `eye` and `target` coincide the orientation is the identity; when the look
    /// direction is parallel to `up` (straight down, straight up) another up axis is used,
    /// so the pose is always defined.
    public static func lookingAt(
        eye: SIMD3<Float>,
        target: SIMD3<Float>,
        up: SIMD3<Float> = worldUp
    ) -> CameraPose {
        CameraPose(
            position: eye,
            quaternion: lookRotation(eye: eye, target: target, up: up)
                ?? simd_quatf(ix: 0, iy: 0, iz: 0, r: 1)
        )
    }

    /// Orientation of a camera at `eye` facing `target`, `nil` when they coincide.
    static func lookRotation(
        eye: SIMD3<Float>,
        target: SIMD3<Float>,
        up: SIMD3<Float>
    ) -> simd_quatf? {
        let toTarget = target - eye
        let distance = simd_length(toTarget)
        guard distance > degenerateLength else { return nil }
        let forward = toTarget / distance
        let safeUp: SIMD3<Float>
        if simd_length(simd_cross(forward, up)) > degenerateLength {
            safeUp = up
        } else if simd_length(simd_cross(forward, fallbackUp)) > degenerateLength {
            safeUp = fallbackUp
        } else {
            safeUp = SIMD3<Float>(1, 0, 0)
        }
        let right = simd_normalize(simd_cross(forward, safeUp))
        let trueUp = simd_cross(right, forward)
        // Columns are the camera's local axes in world space: +X right, +Y up, +Z back.
        let basis = simd_float3x3(columns: (right, trueUp, -forward))
        return simd_normalize(simd_quatf(basis))
    }

    private static let fallbackUp = SIMD3<Float>(0, 0, -1)
    private static let degenerateLength: Float = 1e-6
}

// MARK: - CameraTimeline

/// Anything that yields a camera pose for a time: a ``CameraPosePath``, or your own curve.
///
/// Implementations must be pure — the same `seconds` always gives the same pose — so a
/// player can seek, scrub and replay without keeping history.
public protocol CameraTimeline: Sendable {
    /// Length of one pass, in seconds. `0` for a timeline that holds a single pose.
    var durationSeconds: Float { get }
    /// Whether the timeline repeats after ``durationSeconds``.
    var loop: Bool { get }
    /// The pose at `seconds` from the start. Outside `0...durationSeconds` the timeline
    /// holds its end poses, or wraps when it loops.
    func poseAt(_ seconds: Float) -> CameraPose
}

// MARK: - CameraEasing

/// A timing curve mapping linear progress `0...1` to eased progress.
///
/// The same three curves as Android's `CameraEasing`. Paths clamp an easing's output to
/// `0...1` and pin it exactly at both ends, so an overshooting curve cannot push the
/// camera past a keyframe.
public struct CameraEasing: Sendable {
    private let curve: @Sendable (Float) -> Float

    /// An easing from any curve. It should map `0` to `0` and `1` to `1`.
    public init(_ curve: @escaping @Sendable (Float) -> Float) {
        self.curve = curve
    }

    public func callAsFunction(_ progress: Float) -> Float { curve(progress) }

    /// Constant speed.
    public static let linear = CameraEasing { $0 }

    /// Slow out, slow in — sine ease-in-out, Android's `CameraEasing.InOut`.
    public static let inOut = CameraEasing { -(cos(Float.pi * $0) - 1) / 2 }

    /// Fast out, gentle settle — `cubic-bezier(0.2, 0, 0, 1)`, Android's
    /// `CameraEasing.Expressive`.
    public static let expressive = CameraEasing.cubicBezier(0.2, 0, 0, 1)

    /// A CSS-style cubic Bézier timing curve through `(0, 0)`, `(x1, y1)`, `(x2, y2)`, `(1, 1)`.
    public static func cubicBezier(_ x1: Float, _ y1: Float, _ x2: Float, _ y2: Float) -> CameraEasing {
        let cx1 = Swift.min(Swift.max(x1, 0), 1)
        let cx2 = Swift.min(Swift.max(x2, 0), 1)
        return CameraEasing { x in
            guard x > 0 else { return 0 }
            guard x < 1 else { return 1 }
            func bezier(_ t: Float, _ p1: Float, _ p2: Float) -> Float {
                let u = 1 - t
                return 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t
            }
            func slope(_ t: Float, _ p1: Float, _ p2: Float) -> Float {
                let u = 1 - t
                return 3 * u * u * p1 + 6 * u * t * (p2 - p1) + 3 * t * t * (1 - p2)
            }
            // Newton from t = x, then bisection if it stalls on a flat stretch.
            var t = x
            for _ in 0..<8 {
                let error = bezier(t, cx1, cx2) - x
                if abs(error) < 1e-6 { return bezier(t, y1, y2) }
                let d = slope(t, cx1, cx2)
                if abs(d) < 1e-6 { break }
                t -= error / d
            }
            var low: Float = 0
            var high: Float = 1
            t = x
            for _ in 0..<32 {
                let value = bezier(t, cx1, cx2)
                if abs(value - x) < 1e-6 { break }
                if value < x { low = t } else { high = t }
                t = (low + high) / 2
            }
            return bezier(t, y1, y2)
        }
    }
}

// MARK: - Keyframes

/// One pose on a ``CameraPosePath``, reached at `seconds`.
public struct CameraPoseKeyframe: Sendable {
    /// The pose at this key.
    public var pose: CameraPose
    /// When the path reaches this key, in seconds on the path's own clock.
    public var seconds: Float
    /// Timing of the span that *starts* at this key. `nil` uses the path's
    /// ``CameraPosePath/easing``.
    public var easing: CameraEasing?

    public init(pose: CameraPose, seconds: Float, easing: CameraEasing? = nil) {
        self.pose = pose
        self.seconds = seconds
        self.easing = easing
    }
}

/// How a ``CameraPosePath`` moves the eye between keys.
public enum CameraPathInterpolation: Sendable {
    /// Straight segments: the eye changes direction at every key.
    case linear
    /// A smooth curve through every key (time-weighted Catmull-Rom tangents), so a camera
    /// glides through a key instead of kinking at it.
    case catmullRom
}

// MARK: - CameraPosePath

/// A camera timeline through keyframed poses.
///
/// ```swift
/// let path = CameraPosePath(keyframes: [
///     CameraPoseKeyframe(pose: .lookingAt(eye: [0, 1, 4], target: .zero), seconds: 0),
///     CameraPoseKeyframe(pose: .lookingAt(eye: [3, 1.5, 2], target: .zero), seconds: 3),
///     CameraPoseKeyframe(pose: .lookingAt(eye: [3, 0.5, -2], target: .zero), seconds: 6),
/// ], lookAt: .zero)
/// let pose = path.poseAt(1.5)
/// ```
///
/// - Keys are sorted by time; a key whose time is not finite is dropped. Time counts from
///   the first key, and ``durationSeconds`` runs from the first key to the last.
/// - Positions follow ``interpolation``; rotations take the shortest path between keys
///   (slerp), unless ``lookAt`` is set, which re-aims the camera at that point at every
///   instant — the way to keep a subject centred on a curved move.
/// - ``easing`` (or a key's own) times each span; ``pathEasing`` re-times the whole path,
///   so a multi-key move can start and stop gently as one gesture.
/// - With ``loop`` the path wraps; when the last key returns to the first (three keys or
///   more) the curve stays smooth across the seam.
///
/// ``poseAt(_:)`` is pure, and cheap: a binary search and one curve evaluation.
public struct CameraPosePath: CameraTimeline {
    /// The keys, sorted by time, non-finite times dropped.
    public let keyframes: [CameraPoseKeyframe]
    public let interpolation: CameraPathInterpolation
    /// Timing of every span whose key has no easing of its own.
    public let easing: CameraEasing
    /// Re-timing of the whole path, applied before the per-span easing. `nil` keeps time linear.
    public let pathEasing: CameraEasing?
    /// When set, the camera looks at this point at every instant and key rotations are ignored.
    public let lookAt: SIMD3<Float>?
    /// The up axis for ``lookAt``.
    public let up: SIMD3<Float>
    public let loop: Bool
    public let durationSeconds: Float

    /// Turns added per lap by a fractional orbit: a 1.5-turn orbit starts its second lap
    /// where the first one ended instead of jumping back half a turn. `0` for any other path.
    let lapTurns: Double
    private let rotations: [simd_quatf]
    /// Per-key Catmull-Rom tangents, in units per second (internal for tests).
    let tangents: [SIMD3<Float>]

    public init(
        keyframes: [CameraPoseKeyframe],
        interpolation: CameraPathInterpolation = .catmullRom,
        easing: CameraEasing = .linear,
        pathEasing: CameraEasing? = nil,
        lookAt: SIMD3<Float>? = nil,
        up: SIMD3<Float> = CameraPose.worldUp,
        loop: Bool = false
    ) {
        self.init(keyframes: keyframes, interpolation: interpolation, easing: easing,
                  pathEasing: pathEasing, lookAt: lookAt, up: up, loop: loop, lapTurns: 0)
    }

    init(
        keyframes: [CameraPoseKeyframe],
        interpolation: CameraPathInterpolation,
        easing: CameraEasing,
        pathEasing: CameraEasing?,
        lookAt: SIMD3<Float>?,
        up: SIMD3<Float>,
        loop: Bool,
        lapTurns: Double
    ) {
        let frames = keyframes
            .filter { $0.seconds.isFinite }
            .enumerated()
            .sorted { $0.element.seconds < $1.element.seconds
                || ($0.element.seconds == $1.element.seconds && $0.offset < $1.offset) }
            .map(\.element)
        precondition(frames.allSatisfy { Self.isFinite($0.pose.position) },
                     "keyframe positions must be finite")
        self.keyframes = frames
        self.interpolation = interpolation
        self.easing = easing
        self.pathEasing = pathEasing
        self.lookAt = lookAt
        self.up = up
        self.loop = loop
        self.lapTurns = lapTurns
        self.durationSeconds = frames.count < 2 ? 0 : frames[frames.count - 1].seconds - frames[0].seconds

        var rotations: [simd_quatf] = []
        rotations.reserveCapacity(frames.count)
        for frame in frames {
            var q = Self.normalizeOrIdentity(frame.pose.quaternion)
            if let previous = rotations.last, simd_dot(previous.vector, q.vector) < 0 {
                q = simd_quatf(vector: -q.vector)
            }
            rotations.append(q)
        }
        self.rotations = rotations

        let closed = loop && frames.count >= 3
            && simd_length(frames[frames.count - 1].pose.position - frames[0].pose.position)
                <= Self.closeTolerance
        let seamTurn: Float? = (!loop || lapTurns == 0 || lookAt == nil || frames.count < 3)
            ? nil
            : Float(Self.positiveRemainder(lapTurns, 1) * 360)
        self.tangents = (0..<frames.count).map {
            Self.tangent(at: $0, frames: frames, acrossSeam: closed || seamTurn != nil,
                         seamTurn: seamTurn, up: up)
        }
    }

    /// The pose at the first key, `nil` for an empty path.
    public var start: CameraPose? { keyframes.first.map { poseOf($0.pose) } }

    /// The pose at the last key, `nil` for an empty path.
    public var end: CameraPose? { keyframes.last.map { poseOf($0.pose) } }

    /// Whether a non-looping path has reached its end at `seconds`.
    public func isFinished(at seconds: Float) -> Bool { !loop && seconds >= durationSeconds }

    public func poseAt(_ seconds: Float) -> CameraPose {
        let time = seconds.isFinite ? seconds : 0
        let laps: Int64
        if loop && durationSeconds > 0 && !(0...durationSeconds).contains(time) {
            laps = Self.saturatingInt64(Double((time / durationSeconds).rounded(.down)))
        } else {
            laps = 0
        }
        return poseAt(lap: laps, seconds: localSeconds(time))
    }

    /// The pose `seconds` into lap `lap` — the player's entry point, so a fractional orbit
    /// keeps turning lap after lap.
    func poseAt(lap: Int64, seconds: Float) -> CameraPose {
        guard let first = keyframes.first else { return CameraPose() }
        if keyframes.count == 1 || durationSeconds <= 0 { return poseOf(first.pose) }
        let local = Swift.min(Swift.max(seconds, 0), durationSeconds)
        let eased = pathEasing.map { durationSeconds * Self.applyEasing($0, local / durationSeconds) } ?? local
        let pose = sample(first.seconds + eased)
        guard lap != 0, lapTurns != 0, let pivot = lookAt else { return pose }
        let degrees = Self.positiveRemainder(Double(lap) * lapTurns, 1) * 360
        if degrees == 0 { return pose }
        let turn = simd_quatf(angle: Float(degrees) * .pi / 180, axis: simd_normalize(up))
        return poseOf(CameraPose(position: pivot + turn.act(pose.position - pivot)))
    }

    private func localSeconds(_ seconds: Float) -> Float {
        guard loop else { return Swift.min(Swift.max(seconds, 0), durationSeconds) }
        if (0...durationSeconds).contains(seconds) { return seconds }
        let laps = (seconds / durationSeconds).rounded(.down)
        let wrapped = seconds - laps * durationSeconds
        return Swift.min(Swift.max(wrapped, 0), durationSeconds)
    }

    private func sample(_ t: Float) -> CameraPose {
        let last = keyframes.count - 1
        if t <= keyframes[0].seconds { return poseOf(keyframes[0].pose) }
        if t >= keyframes[last].seconds { return poseOf(keyframes[last].pose) }
        var low = 0
        var high = last
        while high - low > 1 {
            let mid = (low + high) / 2
            if keyframes[mid].seconds <= t { low = mid } else { high = mid }
        }
        let i = low
        let k0 = keyframes[i]
        let k1 = keyframes[i + 1]
        let span = k1.seconds - k0.seconds
        if span <= 0 { return poseOf(k1.pose) }
        let u = Self.applyEasing(k0.easing ?? easing, (t - k0.seconds) / span)
        if u <= 0 { return poseOf(k0.pose, rotation: rotations[i]) }
        if u >= 1 { return poseOf(k1.pose, rotation: rotations[i + 1]) }
        let position: SIMD3<Float>
        switch interpolation {
        case .linear:
            position = simd_mix(k0.pose.position, k1.pose.position, SIMD3<Float>(repeating: u))
        case .catmullRom:
            position = Self.hermite(k0.pose.position, tangents[i] * span,
                                    k1.pose.position, tangents[i + 1] * span, u)
        }
        let rotation = lookAt.flatMap { CameraPose.lookRotation(eye: position, target: $0, up: up) }
            ?? simd_slerp(rotations[i], rotations[i + 1], u)
        return CameraPose(position: position, quaternion: rotation)
    }

    private func poseOf(_ pose: CameraPose, rotation: simd_quatf? = nil) -> CameraPose {
        if let pivot = lookAt, let locked = CameraPose.lookRotation(eye: pose.position, target: pivot, up: up) {
            return CameraPose(position: pose.position, quaternion: locked)
        }
        return CameraPose(position: pose.position,
                          quaternion: rotation ?? Self.normalizeOrIdentity(pose.quaternion))
    }

    // MARK: Tangents

    /// Time-weighted Catmull-Rom tangent at a key, in units per second.
    ///
    /// The central difference of the two neighbouring velocities, weighted by their spans so
    /// uneven key spacing does not overshoot; capped at 3× the slower neighbour; zero where
    /// the path turns back by more than 120°, so a there-and-back move stops at the turn
    /// instead of looping past it. A span under 1 µs is a cut and contributes nothing.
    private static func tangent(
        at index: Int,
        frames: [CameraPoseKeyframe],
        acrossSeam: Bool,
        seamTurn: Float?,
        up: SIMD3<Float>
    ) -> SIMD3<Float> {
        let last = frames.count - 1
        guard last >= 1 else { return .zero }
        func spanOf(_ segment: (Int, Int)) -> Float { frames[segment.1].seconds - frames[segment.0].seconds }
        func velocityOf(_ segment: (Int, Int)) -> SIMD3<Float> {
            (frames[segment.1].pose.position - frames[segment.0].pose.position) / spanOf(segment)
        }
        func turned(_ velocity: SIMD3<Float>, by degrees: Float?) -> SIMD3<Float> {
            guard let degrees else { return velocity }
            return simd_quatf(angle: degrees * .pi / 180, axis: simd_normalize(up)).act(velocity)
        }

        var previous: (Int, Int)?
        if index > 0 { previous = (index - 1, index) } else if acrossSeam { previous = (last - 1, last) }
        if let segment = previous, !(spanOf(segment) > minSpan) { previous = nil }
        var next: (Int, Int)?
        if index < last { next = (index, index + 1) } else if acrossSeam { next = (0, 1) }
        if let segment = next, !(spanOf(segment) > minSpan) { next = nil }

        func velocityIn(_ segment: (Int, Int)) -> SIMD3<Float> {
            index == 0 ? turned(velocityOf(segment), by: seamTurn.map { -$0 }) : velocityOf(segment)
        }
        func velocityOut(_ segment: (Int, Int)) -> SIMD3<Float> {
            index == last ? turned(velocityOf(segment), by: seamTurn) : velocityOf(segment)
        }

        guard let inSegment = previous else { return next.map(velocityOut) ?? .zero }
        guard let outSegment = next else { return velocityIn(inSegment) }
        let inSpan = spanOf(inSegment)
        let outSpan = spanOf(outSegment)
        let inVelocity = velocityIn(inSegment)
        let outVelocity = velocityOut(outSegment)
        if simd_dot(inVelocity, outVelocity)
            < reversalCosine * simd_length(inVelocity) * simd_length(outVelocity) {
            return .zero
        }
        let central = (inVelocity * inSpan + outVelocity * outSpan) / (inSpan + outSpan)
        let limit = maxTangentRatio * Swift.min(simd_length(inVelocity), simd_length(outVelocity))
        let size = simd_length(central)
        return size > limit ? central * (limit / size) : central
    }

    // MARK: Presets

    /// Keys per full turn of an ``orbit(center:extents:verticalFovDegrees:aspect:durationSeconds:turns:elevationDegrees:startAzimuthDegrees:margin:loop:keyframesPerTurn:)``.
    public static let orbitKeyframesPerTurn = 32
    /// Default length of one orbit turn, in seconds.
    public static let defaultOrbitSeconds: Float = 12
    /// Default orbit height, in degrees above the subject's centre.
    public static let defaultOrbitElevationDegrees: Float = 20
    /// Closest a preset puts the camera to its subject, in scene units.
    public static let minDistance: Float = 0.01

    /// A turntable orbit that frames a box: the camera circles its centre at the distance
    /// that keeps the whole box in view, looking at the centre all the way round.
    ///
    /// Azimuth `0` looks from `+Z`; positive `turns` go toward `+X`, the ``SceneCameraPose``
    /// convention. A whole number of turns ends exactly where it started, so a looping orbit
    /// has no seam; a fractional one carries on from where the last lap ended.
    ///
    /// - Parameters:
    ///   - center: The point the camera circles and looks at.
    ///   - extents: The full size of the box to keep in frame.
    ///   - verticalFovDegrees: The camera's vertical field of view (`SceneView` uses 60°).
    ///   - aspect: Viewport width / height.
    ///   - durationSeconds: Length of the whole orbit.
    ///   - turns: Turns over `durationSeconds`, negative to go the other way.
    ///   - elevationDegrees: Height of the orbit, clamped to ±89°.
    ///   - startAzimuthDegrees: Where the orbit starts.
    ///   - margin: Framing breathing room, as for ``CameraControls/fitRadius(boundsExtents:fovYDegrees:aspect:margin:)``.
    ///   - loop: Whether the orbit repeats.
    ///   - keyframesPerTurn: Key density (at least 6).
    public static func orbit(
        center: SIMD3<Float>,
        extents: SIMD3<Float>,
        verticalFovDegrees: Float = 60,
        aspect: Float,
        durationSeconds: Float = defaultOrbitSeconds,
        turns: Float = 1,
        elevationDegrees: Float = defaultOrbitElevationDegrees,
        startAzimuthDegrees: Float = 0,
        margin: Float = CameraControls.defaultFitMargin,
        loop: Bool = true,
        keyframesPerTurn: Int = orbitKeyframesPerTurn
    ) -> CameraPosePath {
        precondition(durationSeconds.isFinite && durationSeconds > 0, "durationSeconds must be > 0")
        precondition(turns.isFinite && turns != 0 && abs(turns) <= maxOrbitTurns,
                     "turns must be finite, non-zero and at most \(maxOrbitTurns) either way")
        precondition(verticalFovDegrees.isFinite && verticalFovDegrees > 0, "verticalFovDegrees must be > 0")
        precondition(aspect.isFinite && aspect > 0, "aspect must be finite and > 0")
        precondition(elevationDegrees.isFinite && startAzimuthDegrees.isFinite && margin.isFinite,
                     "elevationDegrees, startAzimuthDegrees and margin must be finite")
        precondition(isFinite(center) && isFinite(extents), "bounds must be finite")

        let elevation = Swift.min(Swift.max(elevationDegrees, -maxElevationDegrees), maxElevationDegrees)
        var fit = CameraControls()
        fit.elevation = elevation * .pi / 180
        fit.minRadius = minDistance
        fit.maxRadius = .greatestFiniteMagnitude
        fit.orbitRadius = minDistance
        let fitted = fit.fitRadius(boundsExtents: extents, fovYDegrees: verticalFovDegrees,
                                   aspect: aspect, margin: margin)
        let radius = (fitted.isFinite && fitted > minDistance) ? fitted : minDistance

        let perTurn = Swift.max(keyframesPerTurn, minKeyframesPerTurn)
        let segments = Swift.max(2, Int((abs(turns) * Float(perTurn)).rounded(.up)))
        let wholeTurns = turns == turns.rounded(.down)
        var keys: [CameraPoseKeyframe] = []
        keys.reserveCapacity(segments + 1)
        for k in 0...segments {
            let fraction = Float(k) / Float(segments)
            let azimuth = (k == segments && wholeTurns)
                ? startAzimuthDegrees
                : startAzimuthDegrees + 360 * turns * fraction
            let eye = SceneCameraPose(azimuth: azimuth * .pi / 180, elevation: elevation * .pi / 180,
                                      distance: radius, target: center).cameraPosition()
            keys.append(CameraPoseKeyframe(pose: .lookingAt(eye: eye, target: center),
                                           seconds: durationSeconds * fraction))
        }
        return CameraPosePath(keyframes: keys, interpolation: .catmullRom, easing: .linear,
                              pathEasing: nil, lookAt: center, up: CameraPose.worldUp, loop: loop,
                              lapTurns: wholeTurns ? 0 : Double(turns))
    }

    /// ``orbit(center:extents:verticalFovDegrees:aspect:durationSeconds:turns:elevationDegrees:startAzimuthDegrees:margin:loop:keyframesPerTurn:)``
    /// around a RealityKit bounding box — `entity.visualBounds(relativeTo: nil)`, say.
    public static func orbit(
        bounds: BoundingBox,
        verticalFovDegrees: Float = 60,
        aspect: Float,
        durationSeconds: Float = defaultOrbitSeconds,
        turns: Float = 1,
        elevationDegrees: Float = defaultOrbitElevationDegrees,
        startAzimuthDegrees: Float = 0,
        margin: Float = CameraControls.defaultFitMargin,
        loop: Bool = true
    ) -> CameraPosePath {
        orbit(center: bounds.center, extents: bounds.extents, verticalFovDegrees: verticalFovDegrees,
              aspect: aspect, durationSeconds: durationSeconds, turns: turns,
              elevationDegrees: elevationDegrees, startAzimuthDegrees: startAzimuthDegrees,
              margin: margin, loop: loop)
    }

    /// A travelling (truck) shot: the camera slides sideways along its own right axis by
    /// `distance` (negative goes left), keeping its orientation.
    public static func travelling(
        from: CameraPose,
        distance: Float,
        durationSeconds: Float,
        easing: CameraEasing = .inOut
    ) -> CameraPosePath {
        precondition(durationSeconds.isFinite && durationSeconds > 0, "durationSeconds must be > 0")
        precondition(distance.isFinite, "distance must be finite")
        precondition(isFinite(from.position), "from must be finite")
        let start = CameraPose(position: from.position, quaternion: normalizeOrIdentity(from.quaternion))
        let end = CameraPose(position: start.position + start.right * distance, quaternion: start.quaternion)
        return CameraPosePath(
            keyframes: [CameraPoseKeyframe(pose: start, seconds: 0),
                        CameraPoseKeyframe(pose: end, seconds: durationSeconds)],
            interpolation: .linear,
            easing: easing
        )
    }

    /// A dolly shot: the camera moves straight toward (or away from) `target`, from `from` to
    /// `toDistance` away, looking at `target` throughout.
    public static func dolly(
        target: SIMD3<Float>,
        from: SIMD3<Float>,
        toDistance: Float,
        durationSeconds: Float,
        easing: CameraEasing = .inOut
    ) -> CameraPosePath {
        precondition(durationSeconds.isFinite && durationSeconds > 0, "durationSeconds must be > 0")
        precondition(toDistance.isFinite, "toDistance must be finite")
        precondition(isFinite(target) && isFinite(from), "target and from must be finite")
        let offset = from - target
        let startDistance = simd_length(offset)
        precondition(startDistance > 0, "from must differ from target")
        let end = target + offset / startDistance * Swift.max(toDistance, minDistance)
        return CameraPosePath(
            keyframes: [CameraPoseKeyframe(pose: .lookingAt(eye: from, target: target), seconds: 0),
                        CameraPoseKeyframe(pose: .lookingAt(eye: end, target: target), seconds: durationSeconds)],
            interpolation: .linear,
            easing: easing,
            lookAt: target
        )
    }

    // MARK: Helpers

    private static let closeTolerance: Float = 1e-4
    private static let minSpan: Float = 1e-6
    private static let maxTangentRatio: Float = 3
    private static let reversalCosine: Float = -0.5
    private static let minKeyframesPerTurn = 6
    private static let maxOrbitTurns: Float = 1000
    private static let maxElevationDegrees: Float = 89

    private static func isFinite(_ p: SIMD3<Float>) -> Bool { p.x.isFinite && p.y.isFinite && p.z.isFinite }

    private static func positiveRemainder(_ value: Double, _ modulus: Double) -> Double {
        let r = value.truncatingRemainder(dividingBy: modulus)
        return r < 0 ? r + modulus : r
    }

    /// `value` truncated to an `Int64`, pinned at `.min` / `.max` beyond their range and `0`
    /// for NaN — Kotlin's `Double.toLong()`, where Swift's `Int64(_:)` would trap.
    static func saturatingInt64(_ value: Double) -> Int64 {
        guard !value.isNaN else { return 0 }
        if value >= 9.2e18 { return .max }
        if value <= -9.2e18 { return .min }
        return Int64(value)
    }

    static func normalizeOrIdentity(_ q: simd_quatf) -> simd_quatf {
        let norm = simd_length(q.vector)
        return (norm.isFinite && norm > 0) ? simd_quatf(vector: q.vector / norm) : simd_quatf(ix: 0, iy: 0, iz: 0, r: 1)
    }

    static func applyEasing(_ easing: CameraEasing, _ u: Float) -> Float {
        guard u > 0 else { return 0 }
        guard u < 1 else { return 1 }
        let eased = easing(u)
        return eased.isFinite ? Swift.min(Swift.max(eased, 0), 1) : u
    }

    private static func hermite(
        _ p0: SIMD3<Float>, _ m0: SIMD3<Float>, _ p1: SIMD3<Float>, _ m1: SIMD3<Float>, _ u: Float
    ) -> SIMD3<Float> {
        let u2 = u * u
        let u3 = u2 * u
        let h00 = 2 * u3 - 3 * u2 + 1
        let h10 = u3 - 2 * u2 + u
        let h01 = -2 * u3 + 3 * u2
        let h11 = u3 - u2
        return p0 * h00 + m0 * h10 + p1 * h01 + m1 * h11
    }
}

// MARK: - CameraPathPlayer

/// Plays a ``CameraTimeline``: play, pause, seek, speed — and nothing moves until you
/// ``advance(_:)`` it, so the host owns the clock.
///
/// Drive it from the frame clock and hand its ``pose`` to the camera:
///
/// ```swift
/// @StateObject private var player = CameraPathPlayer(
///     timeline: CameraPosePath.orbit(center: .zero, extents: [1, 1, 1], aspect: 0.5))
///
/// var body: some View {
///     SceneView { root in … }
///         .cameraGesturesEnabled(false)
///         .autoCenterContent(false)
///         .cameraPose(SceneCameraPose(player.pose, focusDistance: 2))
///         .background {
///             TimelineView(.animation(paused: !player.isPlaying)) { context in
///                 Color.clear.onChange(of: context.date) { _, date in
///                     player.advanceFrame(at: date.timeIntervalSinceReferenceDate)
///                 }
///             }
///         }
///         .onAppear { player.play() }
/// }
/// ```
///
/// A loop wraps; otherwise playback stops at the end (or at the start when ``speed`` is
/// negative) and ``play()`` starts over. Seeking works while paused, which is what a scrub
/// bar needs.
@MainActor
public final class CameraPathPlayer: ObservableObject {

    /// The timeline played. Swapping it keeps the playhead (clamped or wrapped into the new
    /// one) and adopts the new timeline's ``CameraTimeline/loop``.
    public var timeline: any CameraTimeline {
        // `pose` is computed from the timeline, so a swap must tell SwiftUI itself — even a
        // paused player on a same-length timeline shows a different frame.
        willSet { objectWillChange.send() }
        didSet {
            if timeline.loop != oldValue.loop { loop = timeline.loop }
            durationSeconds = timeline.durationSeconds
            setPlayhead(local)
        }
    }

    /// Whether playback wraps at the ends. Starts as the timeline's own ``CameraTimeline/loop``.
    @Published public var loop: Bool

    /// Playback rate: `1` real time, `2` double speed, negative plays backwards. A
    /// non-finite value is ignored (Android throws) and publishes nothing.
    public var speed: Float {
        get { speedValue }
        set { if newValue.isFinite { speedValue = newValue } }
    }

    /// Storage behind ``speed``, validated before it publishes.
    @Published private var speedValue: Float = 1

    /// The playhead, in seconds within one pass: `0...durationSeconds`.
    @Published public private(set) var seconds: Float = 0

    /// Whether ``advance(_:)`` moves the playhead.
    @Published public private(set) var isPlaying = false

    /// The timeline's length, in seconds.
    @Published public private(set) var durationSeconds: Float

    /// Laps completed by a looping playback — keeps a fractional orbit turning on.
    private var laps: Int64 = 0
    /// The playhead in double precision, so hours of playback do not drift.
    private var local: Double = 0
    private var lastFrameTime: TimeInterval?

    public init(timeline: any CameraTimeline, loop: Bool? = nil) {
        self.timeline = timeline
        self.loop = loop ?? timeline.loop
        self.durationSeconds = timeline.durationSeconds
    }

    /// The playhead as a fraction of the timeline, `0...1`.
    public var progress: Float {
        durationSeconds > 0 ? Swift.min(Swift.max(seconds / durationSeconds, 0), 1) : 0
    }

    /// Whether a non-looping playback has reached its end in the direction of ``speed``.
    public var isFinished: Bool {
        !loop && (speed < 0 ? seconds <= 0 : seconds >= durationSeconds)
    }

    /// The camera pose at the playhead.
    public var pose: CameraPose {
        if laps != 0, let path = timeline as? CameraPosePath {
            return path.poseAt(lap: laps, seconds: seconds)
        }
        return timeline.poseAt(seconds)
    }

    /// Starts or resumes playback; a finished playback starts over.
    public func play() {
        if isFinished {
            laps = 0
            setPlayhead(speed < 0 ? Double(durationSeconds) : 0)
        }
        isPlaying = true
        lastFrameTime = nil
    }

    /// Plays from the start.
    public func restart() {
        laps = 0
        setPlayhead(0)
        isPlaying = true
        lastFrameTime = nil
    }

    /// Stops the playhead where it is.
    public func pause() {
        isPlaying = false
        lastFrameTime = nil
    }

    /// ``pause()`` when playing, ``play()`` otherwise.
    public func toggle() {
        if isPlaying { pause() } else { play() }
    }

    /// Moves the playhead to `seconds`, clamped to the timeline (wrapped when looping).
    /// Works while paused; a non-finite value is ignored.
    public func seek(to seconds: Float) {
        guard seconds.isFinite else { return }
        setPlayhead(Double(seconds))
    }

    /// Moves the playhead by `deltaSeconds` of wall time × ``speed`` when playing, and
    /// returns the pose there. A non-positive or non-finite step moves nothing.
    @discardableResult
    public func advance(_ deltaSeconds: TimeInterval) -> CameraPose {
        if isPlaying, deltaSeconds.isFinite, deltaSeconds > 0 {
            let duration = Double(durationSeconds)
            let next = local + deltaSeconds * Double(speed)
            if duration <= 0 {
                setPlayhead(0)
                if !loop { isPlaying = false }
            } else if loop {
                let wrapped = (next / duration).rounded(.down)
                let (sum, overflow) = laps.addingReportingOverflow(CameraPosePath.saturatingInt64(wrapped))
                laps = overflow ? (wrapped > 0 ? .max : .min) : sum
                setPlayhead(next - wrapped * duration)
            } else if speed > 0 && next >= duration {
                setPlayhead(duration)
                isPlaying = false
            } else if speed < 0 && next <= 0 {
                setPlayhead(0)
                isPlaying = false
            } else {
                setPlayhead(Swift.min(Swift.max(next, 0), duration))
            }
        }
        return pose
    }

    /// ``advance(_:)`` by the time since the previous call, from a frame clock in seconds
    /// (`CACurrentMediaTime()`, a `TimelineView` date). The first frame after ``play()``,
    /// ``pause()`` or a clock that went backwards moves nothing.
    @discardableResult
    public func advanceFrame(at time: TimeInterval) -> CameraPose {
        let previous = lastFrameTime
        lastFrameTime = time
        guard let previous, time > previous else { return advance(0) }
        return advance(time - previous)
    }

    private func setPlayhead(_ value: Double) {
        let duration = Double(durationSeconds)
        if duration <= 0 || !duration.isFinite {
            local = 0
        } else if (0...duration).contains(value) {
            local = value
        } else if loop {
            local = Swift.min(Swift.max(value - (value / duration).rounded(.down) * duration, 0), duration)
        } else {
            local = Swift.min(Swift.max(value, 0), duration)
        }
        let newSeconds = Float(local)
        if newSeconds != seconds { seconds = newSeconds }
    }
}

// MARK: - SceneView interop

extension SceneCameraPose {

    /// The orbit pose that puts a ``SceneView`` camera at `pose`.
    ///
    /// The eye lands exactly on `pose.position` and looks along `pose.forward`; the orbit
    /// target is the point `focusDistance` ahead of it, which is the pivot a gesture would
    /// orbit about if you hand the camera back. Use the distance to what the shot frames
    /// (for a ``CameraPosePath`` with a ``CameraPosePath/lookAt`` point, the distance to it).
    ///
    /// An orbit camera has no roll, and ``SceneView/cameraPose(_:)`` clamps the pitch to
    /// ±85° and the distance to the camera's radius limits — a pose outside them is
    /// approximated, not reproduced.
    public init(_ pose: CameraPose, focusDistance: Float) {
        // A zero or non-finite quaternion (a hand-built `CameraTimeline`) reads as the identity,
        // never as a NaN camera.
        let rotation = CameraPosePath.normalizeOrIdentity(pose.quaternion)
        let forward = simd_normalize(rotation.act(SIMD3<Float>(0, 0, -1)))
        let up = rotation.act(SIMD3<Float>(0, 1, 0))
        let distance = (focusDistance.isFinite && focusDistance > 0) ? focusDistance : 1
        let elevation = asin(Swift.min(Swift.max(-forward.y, -1), 1))
        // Straight up or down the heading is undefined: keep it from the camera's up.
        let horizontal = SIMD2<Float>(forward.x, forward.z)
        let heading = simd_length(horizontal) > 1e-6 ? horizontal : -SIMD2<Float>(up.x, up.z) * forward.y
        let azimuth = atan2(-heading.x, -heading.y)
        self.init(azimuth: azimuth, elevation: elevation, distance: distance,
                  target: pose.position + forward * distance)
    }
}
#endif
