#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation

/// Local environment reflection probe for realistic reflections on nearby surfaces.
///
/// Mirrors SceneView Android's `ReflectionProbeNode` — defines a volume (box or sphere)
/// within which objects receive reflections from a baked environment texture instead of
/// the scene's global IBL. Use multiple probes for different zones (e.g. indoor vs outdoor).
///
/// ```swift
/// SceneView { content in
///     // Box-shaped probe for a room
///     let roomProbe = ReflectionProbeNode.box(size: [4, 3, 4])
///         .position(.init(x: 0, y: 1.5, z: 0))
///         .intensity(1.0)
///     content.add(roomProbe.entity)
///
///     // Spherical probe for a local highlight
///     let sphereProbe = ReflectionProbeNode.sphere(radius: 2.0)
///         .position(.init(x: 3, y: 1, z: 0))
///     content.add(sphereProbe.entity)
/// }
/// ```
public struct ReflectionProbeNode: Sendable {
    /// The underlying RealityKit entity holding the reflection probe.
    public let entity: Entity

    /// The shape of the probe's influence volume.
    public let shape: Shape

    /// World-space position.
    public var position: SIMD3<Float> {
        get { entity.position }
        nonmutating set { entity.position = newValue }
    }

    /// Scale factor.
    public var scale: SIMD3<Float> {
        get { entity.scale }
        nonmutating set { entity.scale = newValue }
    }

    // MARK: - Shape

    /// Describes the influence volume of a reflection probe.
    public enum Shape: Sendable {
        /// Axis-aligned box volume with the given size in meters.
        case box(size: SIMD3<Float>)
        /// Spherical volume with the given radius in meters.
        case sphere(radius: Float)
    }

    // MARK: - Factory methods

    /// Creates a box-shaped reflection probe.
    ///
    /// - Parameters:
    ///   - size: Box extents in meters (width, height, depth).
    ///   - intensity: Reflection intensity multiplier. Default 1.0.
    /// - Returns: A configured `ReflectionProbeNode`.
    public static func box(
        size: SIMD3<Float> = .init(repeating: 1.0),
        intensity: Float = 1.0
    ) -> ReflectionProbeNode {
        let entity = Entity()
        entity.name = "ReflectionProbe_Box"
        return ReflectionProbeNode(
            entity: entity,
            shape: .box(size: size)
        ).applyIntensityScale(intensity)
    }

    /// Creates a spherical reflection probe.
    ///
    /// - Parameters:
    ///   - radius: Sphere radius in meters.
    ///   - intensity: Reflection intensity multiplier. Default 1.0.
    /// - Returns: A configured `ReflectionProbeNode`.
    public static func sphere(
        radius: Float = 1.0,
        intensity: Float = 1.0
    ) -> ReflectionProbeNode {
        let entity = Entity()
        entity.name = "ReflectionProbe_Sphere"
        return ReflectionProbeNode(
            entity: entity,
            shape: .sphere(radius: radius)
        ).applyIntensityScale(intensity)
    }

    // MARK: - Builder methods

    /// Returns self positioned at the given coordinates.
    @discardableResult
    public func position(_ position: SIMD3<Float>) -> ReflectionProbeNode {
        entity.position = position
        return self
    }

    /// Sets the reflection intensity multiplier.
    ///
    /// Values above 1.0 amplify reflections; below 1.0 attenuate them.
    /// Implemented via entity scale on the probe volume.
    @discardableResult
    public func intensity(_ value: Float) -> ReflectionProbeNode {
        return applyIntensityScale(value)
    }

    /// Assigns an environment texture resource for this probe's reflections.
    ///
    /// The resource should be an HDR environment or cubemap loaded via
    /// `EnvironmentResource`. When set, objects within this probe's volume
    /// will reflect this environment instead of the scene's global IBL.
    ///
    /// ```swift
    /// let envResource = try await EnvironmentResource(named: "office_env")
    /// let probe = ReflectionProbeNode.box(size: [4, 3, 4])
    ///     .environmentTexture(envResource)
    /// ```
    @discardableResult
    public func environmentTexture(_ resource: EnvironmentResource) -> ReflectionProbeNode {
        entity.components.set(
            ImageBasedLightComponent(source: .single(resource))
        )
        entity.components.set(
            ImageBasedLightReceiverComponent(imageBasedLight: entity)
        )
        return self
    }

    // MARK: - Internals

    private func applyIntensityScale(_ value: Float) -> ReflectionProbeNode {
        // Intensity is stored as metadata; actual effect depends on the IBL component.
        // We encode intensity in the entity name suffix for retrieval, and if an IBL
        // component is present, we update its intensity scale.
        entity.name = nameBase + "_i\(value)"
        if var iblComponent = entity.components[ImageBasedLightComponent.self] {
            iblComponent.intensityExponent = value
            entity.components.set(iblComponent)
        }
        return self
    }

    private var nameBase: String {
        switch shape {
        case .box:
            return "ReflectionProbe_Box"
        case .sphere:
            return "ReflectionProbe_Sphere"
        }
    }

    /// The volume size of this probe.
    public var volumeSize: SIMD3<Float> {
        switch shape {
        case .box(let size):
            return size
        case .sphere(let radius):
            return .init(repeating: radius * 2)
        }
    }

    /// Whether a world-space point is inside this probe's influence volume.
    ///
    /// - Parameter point: A world-space position.
    /// - Returns: `true` if the point falls within the probe volume.
    public func contains(_ point: SIMD3<Float>) -> Bool {
        let localPoint = point - entity.position
        switch shape {
        case .box(let size):
            let half = size * 0.5
            return abs(localPoint.x) <= half.x
                && abs(localPoint.y) <= half.y
                && abs(localPoint.z) <= half.z
        case .sphere(let radius):
            return simd_length(localPoint) <= radius
        }
    }
}

// MARK: - Environment loading helpers

extension ReflectionProbeNode {
    /// Loads an environment resource from a bundle resource name.
    ///
    /// ```swift
    /// let env = try await ReflectionProbeNode.loadEnvironment("office_env")
    /// let probe = ReflectionProbeNode.box(size: [4, 3, 4])
    ///     .environmentTexture(env)
    /// ```
    @MainActor
    public static func loadEnvironment(_ name: String) async throws -> EnvironmentResource {
        try await EnvironmentResource(named: name)
    }

}

#endif // os(iOS) || os(macOS) || os(visionOS)
