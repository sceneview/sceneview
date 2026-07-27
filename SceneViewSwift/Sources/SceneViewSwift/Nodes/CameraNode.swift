#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation

/// A programmatic camera viewpoint in the scene.
///
/// Mirrors SceneView Android's `CameraNode` — defines a virtual camera position and
/// orientation that can be used to set the active viewpoint or as a reference point
/// for secondary rendering.
///
/// ```swift
/// let camera = CameraNode()
///     .position(.init(x: 0, y: 1.5, z: 3))
///     .lookAt(.zero)
///
/// SceneView { content in
///     content.addChild(camera.entity)
///     // Use camera.transform for view matrix
/// }
/// ```
public struct CameraNode: Sendable {
    /// The underlying RealityKit entity representing the camera viewpoint.
    public let entity: Entity

    /// Creates a camera node at the origin looking along -Z.
    public init() {
        let cameraEntity = Entity()
        cameraEntity.name = "CameraNode"
        // Add a PerspectiveCamera component (available on iOS 15+, macOS 15+, visionOS 1+)
        // with deterministic clip planes matching the documented API defaults
        // (near 0.01 m, far 1000 m). RealityKit's `PerspectiveCameraComponent()`
        // ships with `far = .infinity`, so without setting it here the `farClip`
        // getter's `?? 1000` fallback was unreachable and the default far plane
        // was silently infinite instead of the advertised 1000 m. See #2878.
        var camera = PerspectiveCameraComponent()
        camera.near = 0.01
        camera.far = 1000.0
        cameraEntity.components.set(camera)
        self.entity = cameraEntity
    }

    /// Wraps an existing entity as a camera node.
    public init(_ entity: Entity) {
        self.entity = entity
    }

    /// World-space position.
    public var position: SIMD3<Float> {
        get { entity.position }
        nonmutating set { entity.position = newValue }
    }

    /// Orientation as a quaternion.
    public var rotation: simd_quatf {
        get { entity.orientation }
        nonmutating set { entity.orientation = newValue }
    }

    /// The full 4x4 transform matrix of this camera.
    public var transform: simd_float4x4 {
        entity.transform.matrix
    }

    // MARK: - Configuration

    /// Near clipping plane distance in meters.
    public var nearClip: Float {
        get {
            return entity.components[PerspectiveCameraComponent.self]?.near ?? 0.01
        }
        nonmutating set {
            var camera = entity.components[PerspectiveCameraComponent.self] ?? PerspectiveCameraComponent()
            camera.near = newValue
            entity.components.set(camera)
        }
    }

    /// Far clipping plane distance in meters.
    public var farClip: Float {
        get {
            return entity.components[PerspectiveCameraComponent.self]?.far ?? 1000.0
        }
        nonmutating set {
            var camera = entity.components[PerspectiveCameraComponent.self] ?? PerspectiveCameraComponent()
            camera.far = newValue
            entity.components.set(camera)
        }
    }

    // MARK: - Transform helpers

    /// Returns self positioned at the given coordinates.
    @discardableResult
    public func position(_ position: SIMD3<Float>) -> CameraNode {
        entity.position = position
        return self
    }

    /// Points the camera toward a target position.
    ///
    /// - Parameters:
    ///   - target: The world-space point to look at.
    ///   - up: The up vector. Default is Y-up.
    /// - Returns: Self configured to look at the target.
    @discardableResult
    public func lookAt(
        _ target: SIMD3<Float>,
        up: SIMD3<Float> = SIMD3<Float>(0, 1, 0)
    ) -> CameraNode {
        // Forward `up` to RealityKit's `look(at:from:upVector:relativeTo:)` —
        // pre-fix this overload silently dropped the parameter and called
        // `look(at:from:relativeTo:)` which always assumes Y-up. (#883)
        entity.look(at: target, from: entity.position, upVector: up, relativeTo: nil)
        return self
    }

    /// Returns self rotated by the given quaternion.
    @discardableResult
    public func rotation(_ rotation: simd_quatf) -> CameraNode {
        entity.orientation = rotation
        return self
    }

    /// Returns self with the given clipping planes.
    @discardableResult
    public func clipPlanes(near: Float, far: Float) -> CameraNode {
        self.nearClip = near
        self.farClip = far
        return self
    }

    // MARK: - Field of view

    /// Sets the vertical field of view in degrees.
    ///
    /// - Parameter degrees: Vertical FOV in degrees. Typical values are 30-90.
    /// - Returns: Self for chaining.
    @discardableResult
    public func fieldOfView(_ degrees: Float) -> CameraNode {
        var camera = entity.components[PerspectiveCameraComponent.self] ?? PerspectiveCameraComponent()
        camera.fieldOfViewInDegrees = degrees
        entity.components.set(camera)
        return self
    }

    // MARK: - Depth of field

    /// Configures depth-of-field blur.
    ///
    /// - Important: **This method is a no-op on iOS.** RealityKit's `PerspectiveCameraComponent`
    ///   does not expose focus/depth-of-field settings as of Xcode 26.x. The method is kept for
    ///   Android API parity but performs no operation. Use post-processing effects in your
    ///   custom render pipeline if depth of field is required.
    ///
    /// - Parameters:
    ///   - focusDistance: Distance in meters to the in-focus plane (ignored).
    ///   - aperture: Aperture diameter in f-stops (ignored).
    /// - Returns: Self for chaining.
    @available(*, deprecated, message: "No-op on iOS — RealityKit's PerspectiveCameraComponent does not support depth of field. Kept for Android API parity.")
    @discardableResult
    public func depthOfField(focusDistance: Float, aperture: Float) -> CameraNode {
        // PerspectiveCameraComponent does not have a `focus` property in current RealityKit.
        // This method is reserved for future use when the API becomes available.
        return self
    }

    // MARK: - Exposure

    /// Sets the exposure compensation value (no-op on iOS today).
    ///
    /// - Important: **This method is a no-op on iOS.** RealityKit's
    ///   `PerspectiveCameraComponent` does not expose an exposure or
    ///   exposure-compensation property as of Xcode 26.x (the closest available
    ///   knob is `ImageBasedLightComponent.intensityExponent` on the IBL receiver,
    ///   which `SceneView.renderQuality(_:)` already tunes per-preset — see
    ///   `RenderQuality.swift`). The method is kept for Android API parity.
    ///
    /// For control over scene brightness on iOS, use one of:
    /// - `ARSceneView(cameraExposure:)` for AR scenes (real exposure)
    /// - `SceneView { ... }.renderQuality(.cinematic / .performance)` to tune IBL intensity
    /// - Per-light `LightNode.directional(intensity:)` to adjust the key/fill ratio
    ///
    /// Investigated as part of the #928 silent-stub batch. Confirmed via direct build
    /// against Xcode 26.x that no `PerspectiveCameraComponent.exposureCompensation`
    /// property exists despite an earlier audit suggestion otherwise.
    ///
    /// - Parameter value: Exposure compensation in EV stops (ignored).
    /// - Returns: Self for chaining.
    @available(*, deprecated, message: "No-op on iOS — PerspectiveCameraComponent does not expose exposure. Use ARSceneView(cameraExposure:) for AR, SceneView.renderQuality(_:) to tune IBL, or per-light intensity for 3D scenes.")
    @discardableResult
    public func exposure(_ value: Float) -> CameraNode {
        // PerspectiveCameraComponent does not have an `exposure` property in current
        // RealityKit. Investigation note: verified via direct Xcode 26.x build that
        // `camera.exposureCompensation` is not a member. The IBL receiver's
        // `ImageBasedLightComponent.intensityExponent` is the practical knob and is
        // already tuned per RenderQuality preset.
        return self
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
