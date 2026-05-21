#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// Custom mesh node for creating geometry from raw vertex data.
///
/// For advanced users who need full control over mesh geometry — supply
/// positions, normals, UVs, and triangle indices directly, or pass a
/// RealityKit `MeshDescriptor` for maximum flexibility.
///
/// ```swift
/// let triangle = MeshNode.fromVertices(
///     positions: [
///         SIMD3<Float>(0, 0.5, 0),
///         SIMD3<Float>(-0.5, -0.5, 0),
///         SIMD3<Float>(0.5, -0.5, 0)
///     ],
///     normals: [
///         SIMD3<Float>(0, 0, 1),
///         SIMD3<Float>(0, 0, 1),
///         SIMD3<Float>(0, 0, 1)
///     ],
///     indices: [0, 1, 2],
///     material: .simple(color: .red)
/// )
/// content.addChild(triangle.entity)
/// ```
public struct MeshNode: Sendable {
    /// The underlying RealityKit entity.
    public let entity: ModelEntity

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

    /// Orientation as a quaternion.
    public var rotation: simd_quatf {
        get { entity.orientation }
        nonmutating set { entity.orientation = newValue }
    }

    // MARK: - Initializer

    /// Wraps an existing `ModelEntity`.
    public init(_ entity: ModelEntity) {
        self.entity = entity
    }

    // MARK: - Factory: raw vertex data

    /// Creates a mesh from raw vertex data and triangle indices.
    ///
    /// - Parameters:
    ///   - positions: Vertex positions in local space.
    ///   - normals: Per-vertex normals. Must match `positions` count, or pass `nil` to skip.
    ///   - uvs: Per-vertex texture coordinates. Must match `positions` count, or pass `nil` to skip.
    ///   - indices: Triangle indices (every 3 values form a triangle).
    ///   - material: Material to apply. Uses `GeometryMaterial` from `GeometryNode`.
    /// - Returns: A `MeshNode` containing the custom geometry.
    /// - Throws: If the mesh cannot be generated from the provided data.
    public static func fromVertices(
        positions: [SIMD3<Float>],
        normals: [SIMD3<Float>]? = nil,
        uvs: [SIMD2<Float>]? = nil,
        indices: [UInt32],
        material: GeometryMaterial = .simple(color: .white)
    ) throws -> MeshNode {
        var descriptor = MeshDescriptor(name: "CustomMesh")
        descriptor.positions = MeshBuffers.Positions(positions)

        if let normals = normals {
            descriptor.normals = MeshBuffers.Normals(normals)
        }

        if let uvs = uvs {
            descriptor.textureCoordinates = MeshBuffers.TextureCoordinates(uvs)
        }

        descriptor.primitives = .triangles(indices)

        let mesh = try MeshResource.generate(from: [descriptor])
        let entity = ModelEntity(mesh: mesh, materials: [material.rkMaterial])
        entity.generateCollisionShapes(recursive: false)
        return MeshNode(entity)
    }

    // MARK: - Factory: MeshDescriptor

    /// Creates a mesh from a RealityKit `MeshDescriptor` directly.
    ///
    /// Use this when you need full control over the mesh descriptor configuration,
    /// such as custom primitive types or multiple submeshes.
    ///
    /// - Parameters:
    ///   - descriptor: A configured `MeshDescriptor`.
    ///   - material: Material to apply.
    /// - Returns: A `MeshNode` containing the custom geometry.
    /// - Throws: If the mesh cannot be generated from the descriptor.
    public static func fromDescriptor(
        _ descriptor: MeshDescriptor,
        material: GeometryMaterial = .simple(color: .white)
    ) throws -> MeshNode {
        let mesh = try MeshResource.generate(from: [descriptor])
        let entity = ModelEntity(mesh: mesh, materials: [material.rkMaterial])
        entity.generateCollisionShapes(recursive: false)
        return MeshNode(entity)
    }

    // MARK: - Transform helpers

    /// Returns self positioned at the given coordinates.
    @discardableResult
    public func position(_ position: SIMD3<Float>) -> MeshNode {
        entity.position = position
        return self
    }

    /// Returns self scaled uniformly.
    @discardableResult
    public func scale(_ uniform: Float) -> MeshNode {
        entity.scale = .init(repeating: uniform)
        return self
    }

    /// Returns self scaled per-axis.
    @discardableResult
    public func scale(_ scale: SIMD3<Float>) -> MeshNode {
        entity.scale = scale
        return self
    }

    /// Returns self rotated by the given quaternion.
    @discardableResult
    public func rotation(_ rotation: simd_quatf) -> MeshNode {
        entity.orientation = rotation
        return self
    }

    /// Returns self rotated by angle around axis.
    @discardableResult
    public func rotation(angle: Float, axis: SIMD3<Float>) -> MeshNode {
        entity.orientation = simd_quatf(angle: angle, axis: axis)
        return self
    }

    /// Returns self with a grounding shadow.
    @discardableResult
    public func withGroundingShadow() -> MeshNode {
        if #available(iOS 18.0, visionOS 2.0, *) {
            entity.components.set(GroundingShadowComponent(castsShadow: true))
        }
        return self
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
