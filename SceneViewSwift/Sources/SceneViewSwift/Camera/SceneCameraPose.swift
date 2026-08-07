#if os(iOS) || os(macOS) || os(visionOS)
import simd

/// A complete orbit-camera pose: where the camera is, and what it looks at.
///
/// ``SceneView``'s published camera surface used to be write-once and one-way:
/// ``SceneView/cameraOrbit(azimuth:elevation:)`` *seeds* the pose during scene setup and
/// nothing reads it back, so a host that needs to mirror the camera into its own state
/// (a Compose `CameraState`, a Flutter/React-Native bridge, a "reset view" button) could
/// only ever report what it last wrote. This type is the value that closes both
/// directions — ``SceneView/cameraPose(_:)`` writes it, ``SceneView/onCameraChanged(_:)``
/// reads it back after every gesture.
///
/// **Angles are in radians**, matching ``CameraControls`` and
/// ``SceneView/cameraOrbit(azimuth:elevation:)``. Hosts whose own API speaks degrees
/// convert at their boundary — ``SceneViewerHostView`` does exactly that.
///
/// The orbit convention is the one ``CameraControls/cameraPosition()`` implements, and
/// is identical to SceneView Android's: the camera sits on the sphere of radius
/// ``distance`` around ``target``, at `+azimuth` measured from `+Z` toward `+X`, and
/// `+elevation` looking down from above.
public struct SceneCameraPose: Equatable, Sendable {

    /// Horizontal orbit angle, in **radians**. `0` faces the target from `+Z`.
    public var azimuth: Float

    /// Vertical orbit angle, in **radians**, positive above the target.
    ///
    /// Writing a pose through ``SceneView/cameraPose(_:)`` clamps this to the live
    /// ``CameraControls/minElevation`` / ``CameraControls/maxElevation`` (±85° by
    /// default), so a written pose can never start in gimbal lock. The clamped value is
    /// what ``SceneView/onCameraChanged(_:)`` reports back.
    public var elevation: Float

    /// Distance from ``target`` to the camera, in scene units.
    public var distance: Float

    /// The world-space point the camera orbits and looks at.
    public var target: SIMD3<Float>

    public init(
        azimuth: Float,
        elevation: Float,
        distance: Float,
        target: SIMD3<Float> = .zero
    ) {
        self.azimuth = azimuth
        self.elevation = elevation
        self.distance = distance
        self.target = target
    }

    /// The world-space camera position this pose implies.
    ///
    /// Deliberately the same spherical-to-Cartesian mapping as
    /// ``CameraControls/cameraPosition()`` — a host needing a camera-relative quantity
    /// (a tap's distance, for instance) must not re-derive it with a different
    /// convention, or the number it reports will disagree with what is on screen.
    public func cameraPosition() -> SIMD3<Float> {
        let cosElevation = cos(elevation)
        return SIMD3<Float>(
            target.x + distance * cosElevation * sin(azimuth),
            target.y + distance * sin(elevation),
            target.z + distance * cosElevation * cos(azimuth)
        )
    }

    /// Float-tolerant comparison, for deciding whether a pose is *news*.
    ///
    /// Two-way camera plumbing echoes: a gesture reports a pose to the host, the host
    /// writes it into its own state, and its next update hands that same pose straight
    /// back. Comparing with `==` makes every such echo look like a fresh write, because
    /// the value round-tripped through a degree conversion and came back off by an ULP.
    /// The tolerance is what lets a host tell "the app moved the camera" from "the app
    /// is repeating what I just told it".
    ///
    /// `1e-4` rad is ~0.006°, far below one pixel of orbit at any realistic drag
    /// sensitivity, and far above the round-off of a radians → degrees → radians trip.
    public func approximatelyMatches(
        _ other: SceneCameraPose,
        tolerance: Float = 1e-4
    ) -> Bool {
        func close(_ a: Float, _ b: Float) -> Bool { abs(a - b) <= tolerance }
        return close(azimuth, other.azimuth)
            && close(elevation, other.elevation)
            && close(distance, other.distance)
            && close(target.x, other.target.x)
            && close(target.y, other.target.y)
            && close(target.z, other.target.z)
    }
}

// MARK: - CameraControls interop

extension CameraControls {

    /// The current orbit pose, as a value a host can read back.
    var pose: SceneCameraPose {
        SceneCameraPose(
            azimuth: azimuth,
            elevation: elevation,
            distance: orbitRadius,
            target: target
        )
    }

    /// Applies a host-written pose, honouring the live clamps.
    ///
    /// ``CameraControls/elevation`` and ``CameraControls/orbitRadius`` are plain stored
    /// properties — assigning them directly bypasses the clamps that the gesture path
    /// goes through, so a host could park the camera at exactly ±90° (where the orbit
    /// basis degenerates) or at distance `0`. Writing through here keeps a written pose
    /// inside the same envelope a dragged one lives in.
    mutating func apply(pose: SceneCameraPose) {
        azimuth = pose.azimuth
        elevation = Swift.min(Swift.max(pose.elevation, minElevation), maxElevation)
        orbitRadius = Swift.min(Swift.max(pose.distance, minRadius), maxRadius)
        target = pose.target
    }
}
#endif
