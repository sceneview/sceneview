#if os(iOS) || os(macOS) || os(visionOS)
import SwiftUI
import RealityKit

/// Camera interaction mode for the 3D scene.
///
/// Mirrors SceneView Android's camera manipulator modes
/// (`CameraGestureDetector` + `FovZoomCameraManipulator`).
public enum CameraControlMode: Sendable {
    /// Orbit around a target point. Drag rotates the scene around the orbit
    /// pivot; pinch dollies in/out by scaling the scene root.
    case orbit

    /// Pan the camera in the view plane. Drag translates the orbit target
    /// laterally (the scene appears to slide), pinch keeps dollying in/out.
    /// Rotation is preserved so callers can pan first then orbit.
    ///
    /// **Gesture divergence from Android (#1034)**: Android disambiguates
    /// pan with a 2-finger strafe drag (see `CameraGestureDetector.kt`'s
    /// `isPanGesture()` confidence test); iOS uses a 1-finger drag here
    /// because SwiftUI's `DragGesture` does not expose multi-pointer state
    /// the same way as `MotionEvent`. Switch via `.cameraControls(.pan)`
    /// rather than expecting 2-finger detection.
    case pan

    /// First-person look-around. Drag yaws / pitches the perspective
    /// camera **about its own position** — the camera stands still and
    /// looks around, it does not orbit and does not move the scene root.
    /// Pinch adjusts the camera's field of view — mirrors Android's
    /// `FovZoomCameraManipulator`.
    ///
    /// Entering ``firstPerson`` keeps the camera at whatever world-space
    /// position it had in ``orbit`` / ``pan`` (no teleport): the look-around
    /// pivots around that fixed eye. Switching back to ``orbit`` re-derives
    /// the orbit angles from the look direction, so the transition is
    /// continuous in both directions. Closes #1236 / #1034.
    case firstPerson
}

/// Manages camera orbit, pan, and zoom via SwiftUI gestures.
///
/// Mirrors SceneView Android's `CameraManipulator` — handles touch-to-orbit
/// conversion with inertia and smooth damping.
///
/// ```swift
/// SceneView { content in
///     // ...
/// }
/// .cameraControls(.orbit)
/// ```
public struct CameraControls: Sendable {
    /// Current control mode.
    public var mode: CameraControlMode

    /// Target point the camera orbits around (orbit mode).
    public var target: SIMD3<Float> = .zero

    /// Distance from camera to target (orbit mode). Default `2.0`
    /// matches the camera-to-target distance of the pre-v4.4.0 fake-orbit
    /// (camera at `[0, 0.3, 2]` looking at origin) so the on-screen
    /// framing of existing demos is preserved when orbit became true
    /// camera motion. Bump when constructing a `CameraControls` directly
    /// for larger scenes — values >= `5.0` are typical for room-scale
    /// content.
    public var orbitRadius: Float = 2.0

    /// Horizontal orbit angle in radians.
    public var azimuth: Float = 0.0

    /// Vertical orbit angle in radians, clamped to avoid gimbal lock.
    public var elevation: Float = Float.pi / 6  // 30 degrees

    /// Minimum orbit radius (zoom-in limit). Default `1.0` matches the
    /// approximate bounding-sphere of the bundled demo content — pinching
    /// closer would put the perspective camera inside the model (since
    /// orbit is now true-camera motion via `cameraPosition()`, not a
    /// scene-scale hack). Bump explicitly if your content is smaller than
    /// ~1m extent. Pre-v4.4.0 this was `0.5`, which worked under the old
    /// fake-orbit `scale = 5.0 / radius` path but clips into geometry on
    /// the true-camera path.
    public var minRadius: Float = 1.0

    /// Maximum orbit radius (zoom-out limit).
    public var maxRadius: Float = 50.0

    /// Orbit drag sensitivity (radians per screen point).
    public var sensitivity: Float = 0.005

    /// Whether auto-rotation is active.
    public var isAutoRotating: Bool = false

    /// Auto-rotation speed in radians per second.
    public var autoRotateSpeed: Float = 0.3

    /// Inertia velocity for smooth deceleration after drag ends.
    public var inertiaVelocity: CGSize = .zero

    /// Inertia damping factor (0 = instant stop, 0.99 = very slow deceleration).
    public var inertiaDamping: Float = 0.92

    /// Minimum azimuth angle in radians. Nil means unconstrained.
    public var minAzimuth: Float? = nil

    /// Maximum azimuth angle in radians. Nil means unconstrained.
    public var maxAzimuth: Float? = nil

    /// Minimum elevation angle in radians. Default ~-85 degrees.
    public var minElevation: Float = -(Float.pi / 2 - 0.087)

    /// Maximum elevation angle in radians. Default ~85 degrees.
    public var maxElevation: Float = Float.pi / 2 - 0.087

    /// Pan speed multiplier (pan mode only).
    public var panSpeed: Float = 0.01

    /// First-person move speed (first-person mode only).
    public var moveSpeed: Float = 0.1

    /// Field-of-view in degrees for ``CameraControlMode/firstPerson`` (pinch zoom).
    /// Mirrors Android's `FovZoomCameraManipulator` (default range 10°..120°).
    public var fov: Float = 60.0

    /// Minimum FOV in degrees (firstPerson pinch-in limit).
    public var minFov: Float = 10.0

    /// Maximum FOV in degrees (firstPerson pinch-out limit).
    public var maxFov: Float = 120.0

    /// Per-pixel FOV delta in degrees (firstPerson pinch sensitivity).
    /// Mirrors Android `FovZoomCameraManipulator.DEFAULT_PINCH_FOV_SPEED`.
    public var pinchFovSpeed: Float = 0.05

    /// Whether touch/drag interaction is enabled.
    public var isEnabled: Bool = true

    public init(mode: CameraControlMode = .orbit) {
        self.mode = mode
    }

    /// Creates camera controls with customized orbit limits.
    ///
    /// - Parameters:
    ///   - mode: Camera control mode.
    ///   - minRadius: Minimum zoom distance.
    ///   - maxRadius: Maximum zoom distance.
    ///   - minElevation: Minimum vertical angle in radians.
    ///   - maxElevation: Maximum vertical angle in radians.
    ///   - sensitivity: Drag sensitivity.
    public init(
        mode: CameraControlMode = .orbit,
        minRadius: Float = 0.5,
        maxRadius: Float = 50.0,
        minElevation: Float = -(Float.pi / 2 - 0.087),
        maxElevation: Float = Float.pi / 2 - 0.087,
        sensitivity: Float = 0.005
    ) {
        self.mode = mode
        self.minRadius = minRadius
        self.maxRadius = maxRadius
        self.minElevation = minElevation
        self.maxElevation = maxElevation
        self.sensitivity = sensitivity
    }

    // MARK: - Fit-to-bounds framing (#1026 / #1041)

    /// Computes the orbit radius (camera-to-target distance) at which a
    /// content bounding box of the given extents exactly fits inside the
    /// perspective frustum, with a small margin.
    ///
    /// The previous behaviour (#1026) translated the content centroid to
    /// the orbit pivot but kept the orbit radius fixed at `2.0` — so small
    /// models rendered as a speck in a near-empty viewport and large models
    /// overflowed and clipped on every edge. This computes a radius that
    /// scales-to-fit, closing the #1041 follow-up.
    ///
    /// The box is treated as its bounding sphere (half its space diagonal)
    /// so the fit holds for every orbit angle, not just the front view.
    /// The camera looks at the box centre, so the required distance is
    /// `sphereRadius / sin(halfFov)`. The frustum is narrower on whichever
    /// axis has the smaller FOV — for portrait phones that is the
    /// horizontal axis (`aspect < 1`), so both the vertical FOV and the
    /// aspect-derived horizontal FOV are considered and the smaller half-FOV
    /// wins.
    ///
    /// - Parameters:
    ///   - boundsExtents: The full width / height / depth of the content
    ///     bounding box, in meters (RealityKit `BoundingBox.extents`).
    ///   - fovYDegrees: The perspective camera's vertical field of view in
    ///     degrees (SceneView's baseline is `60`).
    ///   - aspect: Viewport aspect ratio (`width / height`). Portrait phones
    ///     are `< 1`. Defaults to `0.46` — the iPhone 16e portrait ratio
    ///     (1206 × 2622 pt) — used when the live viewport size is unknown.
    ///   - margin: Extra padding factor applied to the fitted radius so the
    ///     content does not touch the viewport edges. Default `1.15`
    ///     (15 % breathing room).
    /// - Returns: The orbit radius that frames the box, clamped to
    ///   `[minRadius, maxRadius]`.
    public func fitRadius(
        boundsExtents: SIMD3<Float>,
        fovYDegrees: Float,
        aspect: Float = 0.46,
        margin: Float = 1.15
    ) -> Float {
        // Bounding-sphere radius — half the box's space diagonal — so the
        // fit is orbit-angle invariant.
        let half = boundsExtents * 0.5
        let sphereRadius = simd_length(half)
        guard sphereRadius.isFinite, sphereRadius > 0 else { return orbitRadius }

        let fovY = max(fovYDegrees, 1) * .pi / 180
        // Horizontal FOV from the vertical FOV and the viewport aspect.
        let safeAspect = (aspect.isFinite && aspect > 0) ? aspect : 0.46
        let fovX = 2 * atan(tan(fovY / 2) * safeAspect)
        // The limiting axis is the one with the *smaller* FOV.
        let halfFov = min(fovY, fovX) / 2
        let sinHalf = sin(halfFov)
        guard sinHalf > 0 else { return orbitRadius }

        let fitted = (sphereRadius / sinHalf) * margin
        return Swift.min(Swift.max(fitted, minRadius), maxRadius)
    }

    // MARK: - Computed Camera Position

    /// Computes the camera position from current orbit parameters.
    ///
    /// Converts spherical coordinates (azimuth, elevation, radius) to
    /// a Cartesian position relative to the target point.
    ///
    /// - Returns: World-space camera position.
    public func cameraPosition() -> SIMD3<Float> {
        let cosElev = cos(elevation)
        let x = target.x + orbitRadius * cosElev * sin(azimuth)
        let y = target.y + orbitRadius * sin(elevation)
        let z = target.z + orbitRadius * cosElev * cos(azimuth)
        return SIMD3<Float>(x, y, z)
    }

    /// Computes a look-at transform matrix from the camera to the target.
    ///
    /// - Returns: A 4x4 view matrix positioning the camera at the orbit
    ///   location and looking towards the target.
    public func cameraTransform() -> simd_float4x4 {
        let eye = cameraPosition()
        let up = SIMD3<Float>(0, 1, 0)

        let forward = simd_normalize(target - eye)
        let right = simd_normalize(simd_cross(forward, up))
        let correctedUp = simd_cross(right, forward)

        return simd_float4x4(columns: (
            SIMD4<Float>(right.x, correctedUp.x, -forward.x, 0),
            SIMD4<Float>(right.y, correctedUp.y, -forward.y, 0),
            SIMD4<Float>(right.z, correctedUp.z, -forward.z, 0),
            SIMD4<Float>(eye.x, eye.y, eye.z, 1)
        ))
    }

    /// Returns the inverse rotation to apply to the scene root entity
    /// (rotating content is equivalent to orbiting the camera).
    public func sceneRotation() -> simd_quatf {
        let yaw = simd_quatf(angle: -azimuth, axis: [0, 1, 0])
        let pitch = simd_quatf(angle: -elevation, axis: [1, 0, 0])
        return yaw * pitch
    }

    /// Fixed world-space eye position for ``CameraControlMode/firstPerson``.
    ///
    /// `nil` until the first time ``firstPerson`` is entered. While in
    /// firstPerson the camera stays pinned at this point and only its
    /// orientation changes (true "stand still and look around"). It is
    /// captured from the current orbit camera position at the mode switch
    /// so there is no teleport. Closes #1236.
    public var firstPersonEye: SIMD3<Float>? = nil

    /// World-space orientation of the perspective camera for the current
    /// look angles.
    ///
    /// RealityKit cameras look down their local `-Z`. A yaw of `azimuth`
    /// about `+Y` followed by a pitch of `elevation` about the camera's
    /// local `+X` reproduces the same forward vector that
    /// ``cameraPosition()`` / ``cameraTransform()`` look toward in orbit
    /// mode, so deriving orbit angles back from this orientation is exact
    /// (no drift across orbit ↔ firstPerson switches).
    public func lookOrientation() -> simd_quatf {
        let yaw = simd_quatf(angle: azimuth, axis: [0, 1, 0])
        let pitch = simd_quatf(angle: -elevation, axis: [1, 0, 0])
        return yaw * pitch
    }

    /// Unit forward vector (the direction the camera looks) for the
    /// current ``azimuth`` / ``elevation``.
    public func lookForward() -> SIMD3<Float> {
        let cosElev = cos(elevation)
        // -Z forward, consistent with `cameraPosition()`: in orbit the
        // camera sits at `+radius` along this axis and looks back at the
        // target, so the look direction is the negated offset.
        return SIMD3<Float>(
            -cosElev * sin(azimuth),
            -sin(elevation),
            -cosElev * cos(azimuth)
        )
    }

    /// Captures the current orbit camera position as the fixed
    /// ``firstPersonEye``. Call this when entering ``firstPerson`` so the
    /// look-around pivots around exactly where the camera already is —
    /// switching orbit → firstPerson never teleports. Idempotent within a
    /// firstPerson session: only the first call (when `firstPersonEye` is
    /// `nil`) takes effect.
    public mutating func enterFirstPerson() {
        guard firstPersonEye == nil else { return }
        firstPersonEye = cameraPosition()
    }

    /// Re-derives the orbit ``target`` from the fixed firstPerson eye and
    /// the current look direction, then clears ``firstPersonEye``. Call
    /// this when leaving ``firstPerson`` so orbit resumes around a pivot
    /// directly in front of the camera — the orbit camera position
    /// recomputed from `(target, azimuth, elevation, orbitRadius)` equals
    /// the firstPerson eye exactly, so the transition is continuous.
    public mutating func exitFirstPerson() {
        if let eye = firstPersonEye {
            // The camera looks *toward* the target, so the pivot sits one
            // `orbitRadius` ahead of the eye along the look direction.
            // `cameraPosition()` recomputed from this target then lands
            // back exactly on `eye` (the orbit offset is `-lookForward()`).
            target = eye + lookForward() * orbitRadius
        }
        firstPersonEye = nil
    }

    /// Resets the orbit ``target`` (pivot) to the given world point —
    /// typically the content centroid.
    ///
    /// Pan moves ``target`` laterally; over repeated pan-then-orbit cycles
    /// the pivot drifts away from the content centroid (#1236 limitation 2).
    /// Call this — e.g. from a "recenter" affordance — to snap the orbit
    /// pivot back. The camera keeps its current ``azimuth`` / ``elevation``
    /// / ``orbitRadius`` so only the framing recenters, not the viewing
    /// angle.
    ///
    /// - Parameter centroid: World-space point to orbit around. Defaults
    ///   to the world origin, which is where ``SceneView``'s auto-centering
    ///   places the content centroid (#1026).
    public mutating func recenterTarget(_ centroid: SIMD3<Float> = .zero) {
        target = centroid
        firstPersonEye = nil
    }

    // MARK: - Gesture Handling

    /// Updates orbit angles from a drag gesture delta.
    ///
    /// In orbit mode, rotates around the target. In pan mode, translates the target.
    /// In first-person mode, adjusts look direction.
    ///
    /// - Parameter delta: The drag delta in screen points (incremental, not total).
    public mutating func handleDrag(_ delta: CGSize) {
        guard isEnabled else { return }

        switch mode {
        case .orbit:
            azimuth -= Float(delta.width) * sensitivity
            elevation += Float(delta.height) * sensitivity
            clampElevation()
            clampAzimuth()

        case .pan:
            let right = SIMD3<Float>(cos(azimuth), 0, -sin(azimuth))
            let up = SIMD3<Float>(0, 1, 0)
            target += right * Float(delta.width) * panSpeed
            target += up * Float(-delta.height) * panSpeed

        case .firstPerson:
            azimuth -= Float(delta.width) * sensitivity
            elevation += Float(delta.height) * sensitivity
            clampElevation()
        }

        // Store velocity for inertia
        inertiaVelocity = delta
    }

    /// Updates orbit radius from a magnification gesture.
    ///
    /// In ``CameraControlMode/orbit`` and ``CameraControlMode/pan`` this scales
    /// the dolly radius (zoom). In ``CameraControlMode/firstPerson`` it
    /// adjusts the perspective camera's field-of-view instead — mirrors
    /// Android's `FovZoomCameraManipulator`.
    ///
    /// - Parameter scale: The pinch gesture magnification factor.
    public mutating func handlePinch(_ scale: CGFloat) {
        guard isEnabled else { return }
        switch mode {
        case .orbit, .pan:
            orbitRadius /= Float(scale)
            orbitRadius = Swift.min(Swift.max(orbitRadius, minRadius), maxRadius)
        case .firstPerson:
            // Pinch out (scale > 1) ⇒ user wants to zoom IN ⇒ smaller FOV.
            fov /= Float(scale)
            fov = Swift.min(Swift.max(fov, minFov), maxFov)
        }
    }

    /// Applies inertia deceleration. Call this on each frame after drag ends.
    ///
    /// Mode-gated so `.pan` doesn't see ghost rotation from the last drag's
    /// stored `inertiaVelocity` (drag in `.pan` mutates `target`, not
    /// `azimuth`/`elevation`, but the velocity is captured unconditionally
    /// at `handleDrag`'s tail; this guard prevents that velocity from
    /// leaking into rotation when the user releases a pan drag). Closes the
    /// Agent 1 MAJOR finding on PR #1038.
    ///
    /// - Returns: `true` while inertia is still active.
    @discardableResult
    public mutating func applyInertia() -> Bool {
        let threshold: CGFloat = 0.01
        guard abs(inertiaVelocity.width) > threshold
                || abs(inertiaVelocity.height) > threshold else {
            inertiaVelocity = .zero
            return false
        }

        switch mode {
        case .orbit, .firstPerson:
            azimuth -= Float(inertiaVelocity.width) * sensitivity
            elevation += Float(inertiaVelocity.height) * sensitivity
            clampElevation()
        case .pan:
            // Inertia in pan mode keeps translating the target so the scene
            // continues to glide after release — same semantics as
            // `handleDrag(.pan)`.
            let right = SIMD3<Float>(cos(azimuth), 0, -sin(azimuth))
            let up = SIMD3<Float>(0, 1, 0)
            target += right * Float(inertiaVelocity.width) * panSpeed
            target += up * Float(-inertiaVelocity.height) * panSpeed
        }

        inertiaVelocity.width *= CGFloat(inertiaDamping)
        inertiaVelocity.height *= CGFloat(inertiaDamping)
        return true
    }

    /// Advances auto-rotation by the given time delta.
    ///
    /// - Parameter dt: Time elapsed since last frame in seconds.
    public mutating func applyAutoRotation(dt: Float) {
        guard isAutoRotating else { return }
        azimuth += autoRotateSpeed * dt
    }

    // MARK: - Convenience builders

    /// Returns a copy with the orbit target changed.
    @discardableResult
    public func withTarget(_ target: SIMD3<Float>) -> CameraControls {
        var copy = self
        copy.target = target
        return copy
    }

    /// Returns a copy with the initial orbit radius changed.
    @discardableResult
    public func withRadius(_ radius: Float) -> CameraControls {
        var copy = self
        copy.orbitRadius = radius
        return copy
    }

    /// Returns a copy with azimuth limits set.
    @discardableResult
    public func withAzimuthLimits(min: Float, max: Float) -> CameraControls {
        var copy = self
        copy.minAzimuth = min
        copy.maxAzimuth = max
        return copy
    }

    /// Returns a copy with elevation limits set.
    @discardableResult
    public func withElevationLimits(min: Float, max: Float) -> CameraControls {
        var copy = self
        copy.minElevation = min
        copy.maxElevation = max
        return copy
    }

    // MARK: - Private

    private mutating func clampElevation() {
        elevation = Swift.min(Swift.max(elevation, minElevation), maxElevation)
    }

    private mutating func clampAzimuth() {
        if let minAz = minAzimuth, let maxAz = maxAzimuth {
            azimuth = Swift.min(Swift.max(azimuth, minAz), maxAz)
        }
    }
}

// MARK: - Content bounds union (#1391)

/// Pure helpers that compute the union axis-aligned bounding box of several
/// content `BoundingBox`es, so the fit-to-bounds camera pass frames the
/// *combined* extent of every loaded model rather than a single entity.
///
/// #1385 added the single-model fit; multi-model demos (e.g. `Multi-Model
/// Park`) still mis-framed because they query one entity's `visualBounds`,
/// which — when content lives under an `AnchorEntity` whose transform the
/// anchoring system has not resolved yet, or when only some of several
/// streamed models have populated — does not describe the union the camera
/// should frame. Walking every child and unioning their boxes is robust to
/// both cases. The functions are deliberately free of RealityKit *view*
/// state so they are unit-testable on any platform.
///
/// Kept `internal` (reachable from the `@testable import` test target) so
/// the public API surface stays minimal — the union is an implementation
/// detail of `SceneView`'s built-in framing, not a consumer-facing knob.
enum ContentBounds {

    /// Unions a sequence of `BoundingBox`es into one AABB.
    ///
    /// Empty / degenerate boxes (RealityKit reports `min = +∞`, `max = -∞`
    /// for an empty `BoundingBox`, so the per-axis extents are non-finite)
    /// are skipped — an async model that has not populated its mesh yet
    /// must not poison the union with infinities.
    ///
    /// - Parameter boxes: The per-entity world-space bounding boxes.
    /// - Returns: The union AABB, or `nil` if no box was finite & non-empty.
    static func union(of boxes: [BoundingBox]) -> BoundingBox? {
        var lo = SIMD3<Float>(repeating: .greatestFiniteMagnitude)
        var hi = SIMD3<Float>(repeating: -.greatestFiniteMagnitude)
        var found = false
        for box in boxes {
            let bmin = box.min
            let bmax = box.max
            // Reject empty / non-finite boxes. `bmax >= bmin` per axis also
            // filters RealityKit's inverted empty box (min +∞, max -∞).
            guard bmin.x.isFinite, bmin.y.isFinite, bmin.z.isFinite,
                  bmax.x.isFinite, bmax.y.isFinite, bmax.z.isFinite,
                  bmax.x >= bmin.x, bmax.y >= bmin.y, bmax.z >= bmin.z
            else { continue }
            lo = simd_min(lo, bmin)
            hi = simd_max(hi, bmax)
            found = true
        }
        guard found else { return nil }
        return BoundingBox(min: lo, max: hi)
    }
}

// MARK: - Framing stability tracker (#1391 retry)

/// Decides when the content-union AABB has been stable *long enough* that the
/// fit-to-bounds camera pass may stop re-framing and latch.
///
/// ### Why a duration-based latch (not "two consecutive ticks")
///
/// #1514's first attempt latched as soon as two consecutive framing ticks
/// (~33 ms apart) saw an unchanged union diagonal. `Multi-Model Park` streams
/// four glTF models concurrently over **30–70 s** — there is almost always a
/// multi-second gap between one model landing and the next. Two adjacent ticks
/// inside such a gap trivially see an unchanged diagonal, so #1514 latched on a
/// partial 1–2-model union and the camera then framed empty space while the
/// remaining models streamed in off-screen (issue #1391, reopened).
///
/// This tracker instead requires the diagonal to hold steady for a sustained
/// **wall-clock window** (`stableHoldSeconds`) that comfortably exceeds the
/// inter-model streaming gap. Any growth resets the hold timer, so every
/// streamed model that enlarges the union re-arms the latch. Once no model has
/// grown the union for the full hold window, the scene is genuinely settled.
///
/// Pure value type — no RealityKit *view* state — so it is unit-testable on any
/// platform. Kept `internal` for `@testable import`; it is an implementation
/// detail of `SceneView`'s built-in framing.
struct FramingStabilityTracker {

    /// Diagonal of the union AABB the most recent framing pass fitted to.
    /// `0` until the first valid sample.
    private(set) var lastDiagonal: Float = 0

    /// Wall-clock timestamp (seconds, monotonic source) at which `lastDiagonal`
    /// last changed materially — i.e. when a streamed model last grew or
    /// shrank the union. `nil` until the first sample.
    private(set) var lastChangeTime: Double?

    /// Relative diagonal change below which two samples count as "the same
    /// size". Tolerant of sub-millimetre jitter, tight enough that a freshly
    /// streamed model (which grows the union materially) always registers.
    let epsilon: Float

    /// How long (seconds) the union diagonal must hold steady before the
    /// scene counts as stable. Must exceed the worst-case gap between two
    /// streamed models landing — `Multi-Model Park` loads four models over
    /// 30–70 s, so a multi-second hold reliably spans the inter-model gaps
    /// without making single-model demos feel sluggish.
    let stableHoldSeconds: Double

    init(epsilon: Float = 0.01, stableHoldSeconds: Double = 2.5) {
        self.epsilon = epsilon
        self.stableHoldSeconds = stableHoldSeconds
    }

    /// Feeds a fresh union-diagonal sample taken at `now`.
    ///
    /// - Parameters:
    ///   - diagonal: The current content-union AABB diagonal length.
    ///   - now: Monotonic wall-clock time in seconds (e.g.
    ///     `CFAbsoluteTimeGetCurrent()`).
    /// - Returns: `true` once the diagonal has held steady (within `epsilon`)
    ///   for at least `stableHoldSeconds`. Any growth/shrink resets the hold
    ///   timer and returns `false`, so the framing pass keeps re-fitting as
    ///   each streamed model lands.
    mutating func register(diagonal: Float, now: Double) -> Bool {
        guard diagonal.isFinite, diagonal > 0 else { return false }
        defer { lastDiagonal = diagonal }

        guard let changedAt = lastChangeTime else {
            // First valid sample — start the hold window now.
            lastChangeTime = now
            return false
        }

        let changed = abs(diagonal - lastDiagonal)
            > epsilon * max(diagonal, lastDiagonal)
        if changed {
            // A streamed model grew/shrank the union — re-arm the hold timer.
            lastChangeTime = now
            return false
        }
        // Diagonal unchanged since `changedAt`; stable once the full hold
        // window has elapsed.
        return (now - changedAt) >= stableHoldSeconds
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
