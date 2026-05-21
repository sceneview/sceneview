#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// Displays an image on a 3D plane in the scene.
///
/// Mirrors SceneView Android's `ImageNode` — renders a texture on a flat quad
/// with configurable size, material, and collision.
///
/// ```swift
/// @State private var imageNode: ImageNode?
///
/// SceneView { content in
///     if let imageNode {
///         content.addChild(imageNode.entity)
///     }
/// }
/// .task {
///     imageNode = try? await ImageNode.load("textures/poster.png")
///         .position(.init(x: 0, y: 1, z: -2))
///         .size(width: 1.0, height: 0.75)
/// }
/// ```
public struct ImageNode: Sendable {
    /// The underlying RealityKit entity.
    public let entity: ModelEntity

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

    /// Scale factor.
    public var scale: SIMD3<Float> {
        get { entity.scale }
        nonmutating set { entity.scale = newValue }
    }

    // MARK: - Factory methods

    /// Creates an image node from a bundle resource name.
    ///
    /// Loads the image and applies it as an unlit texture on a plane.
    ///
    /// - Parameters:
    ///   - name: Bundle resource name (e.g. `"textures/poster.png"`).
    ///   - width: Plane width in meters. Default 1.0.
    ///   - height: Plane height in meters. If nil, computed from aspect ratio.
    ///   - isLit: Whether the image responds to scene lighting. Default false (unlit).
    /// - Returns: An `ImageNode` displaying the image.
    /// - Throws: If the image cannot be loaded.
    public static func load(
        _ name: String,
        width: Float = 1.0,
        height: Float? = nil,
        isLit: Bool = false
    ) async throws -> ImageNode {
        guard let texture = try? await TextureResource(named: name) else {
            throw ImageNodeError.textureLoadFailed(name)
        }
        return create(texture: texture, width: width, height: height, isLit: isLit)
    }

    /// Creates an image node from a URL.
    ///
    /// - Parameters:
    ///   - url: File URL to the image.
    ///   - width: Plane width in meters.
    ///   - height: Plane height in meters. If nil, uses 1:1 aspect.
    ///   - isLit: Whether the image responds to scene lighting.
    /// - Returns: An `ImageNode` displaying the image.
    /// - Throws: If the image cannot be loaded.
    public static func load(
        contentsOf url: URL,
        width: Float = 1.0,
        height: Float? = nil,
        isLit: Bool = false
    ) async throws -> ImageNode {
        let texture = try await TextureResource(contentsOf: url)
        return create(texture: texture, width: width, height: height, isLit: isLit)
    }

    /// Creates an image node with a solid color (useful for colored planes).
    ///
    /// - Parameters:
    ///   - color: The fill color.
    ///   - width: Plane width in meters.
    ///   - height: Plane height in meters.
    /// - Returns: An `ImageNode` with the colored plane.
    public static func color(
        _ color: SimpleMaterial.Color,
        width: Float = 1.0,
        height: Float = 1.0
    ) -> ImageNode {
        let mesh = MeshResource.generatePlane(width: width, height: height)
        let material = UnlitMaterial(color: color)
        let entity = ModelEntity(mesh: mesh, materials: [material])
        entity.generateCollisionShapes(recursive: false)
        return ImageNode(entity: entity)
    }

    // MARK: - Internal

    private static func create(
        texture: TextureResource,
        width: Float,
        height: Float?,
        isLit: Bool
    ) -> ImageNode {
        let resolvedHeight = height ?? width // Default 1:1 if no height specified
        let mesh = MeshResource.generatePlane(width: width, height: resolvedHeight)

        let material: any Material
        if isLit {
            var pbr = PhysicallyBasedMaterial()
            pbr.baseColor = .init(texture: .init(texture))
            pbr.metallic = .init(floatLiteral: 0.0)
            pbr.roughness = .init(floatLiteral: 0.5)
            material = pbr
        } else {
            var unlit = UnlitMaterial()
            unlit.color = .init(texture: .init(texture))
            material = unlit
        }

        let entity = ModelEntity(mesh: mesh, materials: [material])
        entity.generateCollisionShapes(recursive: false)
        return ImageNode(entity: entity)
    }

    // MARK: - Transform helpers

    /// Returns self positioned at the given coordinates.
    @discardableResult
    public func position(_ position: SIMD3<Float>) -> ImageNode {
        entity.position = position
        return self
    }

    /// Returns self rotated by the given quaternion.
    @discardableResult
    public func rotation(_ rotation: simd_quatf) -> ImageNode {
        entity.orientation = rotation
        return self
    }

    /// Returns self rotated by angle around axis.
    @discardableResult
    public func rotation(angle: Float, axis: SIMD3<Float>) -> ImageNode {
        entity.orientation = simd_quatf(angle: angle, axis: axis)
        return self
    }

    /// Returns self scaled uniformly.
    @discardableResult
    public func scale(_ uniform: Float) -> ImageNode {
        entity.scale = .init(repeating: uniform)
        return self
    }

    /// Resizes the image plane to the given dimensions.
    ///
    /// - Parameters:
    ///   - width: New width in meters.
    ///   - height: New height in meters.
    /// - Returns: Self with updated mesh.
    @discardableResult
    public func size(width: Float, height: Float) -> ImageNode {
        let mesh = MeshResource.generatePlane(width: width, height: height)
        entity.model?.mesh = mesh
        return self
    }

    /// Returns self with a grounding shadow.
    @discardableResult
    public func withGroundingShadow() -> ImageNode {
        if #available(iOS 18.0, visionOS 2.0, *) {
            entity.components.set(GroundingShadowComponent(castsShadow: true))
        }
        return self
    }

    // MARK: - Errors

    public enum ImageNodeError: Error, LocalizedError {
        case textureLoadFailed(String)

        public var errorDescription: String? {
            switch self {
            case .textureLoadFailed(let name):
                return "Failed to load texture: \(name)"
            }
        }
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
