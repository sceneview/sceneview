#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation

/// Result builder for composing entities declaratively.
///
/// Enables a SwiftUI-like syntax for building 3D scene hierarchies:
///
/// ```swift
/// SceneView { root in
///     let scene = NodeBuilder.build {
///         GeometryNode.cube(size: 0.3, color: .red)
///             .position(.init(x: -1, y: 0, z: -2))
///         GeometryNode.sphere(radius: 0.2, color: .blue)
///             .position(.init(x: 1, y: 0, z: -2))
///         LightNode.directional(intensity: 1000)
///     }
///     for entity in scene {
///         root.addChild(entity)
///     }
/// }
/// ```
@resultBuilder
public struct NodeBuilder {

    /// Builds a single entity from a component.
    public static func buildBlock(_ components: EntityProvider...) -> [Entity] {
        components.map { $0.sceneEntity }
    }

    /// Builds optional components.
    public static func buildOptional(_ component: [Entity]?) -> [Entity] {
        component ?? []
    }

    /// Builds either-or components (if branch).
    public static func buildEither(first component: [Entity]) -> [Entity] {
        component
    }

    /// Builds either-or components (else branch).
    public static func buildEither(second component: [Entity]) -> [Entity] {
        component
    }

    /// Builds from an array of entities.
    public static func buildArray(_ components: [[Entity]]) -> [Entity] {
        components.flatMap { $0 }
    }

    /// Builds the final result.
    public static func buildFinalResult(_ component: [Entity]) -> [Entity] {
        component
    }

    /// Convenience to build a list of entities from the builder closure.
    public static func build(@NodeBuilder _ content: () -> [Entity]) -> [Entity] {
        content()
    }
}

/// Protocol for types that can provide a RealityKit entity.
///
/// Conform to this protocol to make your custom node types
/// composable with ``NodeBuilder``.
public protocol EntityProvider {
    /// The RealityKit entity for this node.
    var sceneEntity: Entity { get }
}

// MARK: - Conformances

extension GeometryNode: EntityProvider {
    public var sceneEntity: Entity { entity }
}

extension ModelNode: EntityProvider {
    public var sceneEntity: Entity { entity }
}

extension LightNode: EntityProvider {
    public var sceneEntity: Entity { entity }
}

extension MeshNode: EntityProvider {
    public var sceneEntity: Entity { entity }
}

extension TextNode: EntityProvider {
    public var sceneEntity: Entity { entity }
}

extension ImageNode: EntityProvider {
    public var sceneEntity: Entity { entity }
}

extension BillboardNode: EntityProvider {
    public var sceneEntity: Entity { entity }
}

extension CameraNode: EntityProvider {
    public var sceneEntity: Entity { entity }
}

extension LineNode: EntityProvider {
    public var sceneEntity: Entity { entity }
}

extension PathNode: EntityProvider {
    public var sceneEntity: Entity { entity }
}

extension PhysicsNode: EntityProvider {
    public var sceneEntity: Entity { entity }
}

extension DynamicSkyNode: EntityProvider {
    public var sceneEntity: Entity { entity }
}

extension FogNode: EntityProvider {
    public var sceneEntity: Entity { entity }
}

extension ReflectionProbeNode: EntityProvider {
    public var sceneEntity: Entity { entity }
}

extension VideoNode: EntityProvider {
    public var sceneEntity: Entity { entity }
}

extension ShapeNode: EntityProvider {
    public var sceneEntity: Entity { entity }
}

// `ViewNode` is deprecated (placeholder rendering only — see #1035 / #2042).
// The extension itself is marked deprecated so conforming the type does not
// emit a deprecation warning inside the library.
@available(*, deprecated, message: "ViewNode renders a placeholder plane only — see #1035.")
extension ViewNode: EntityProvider {
    public var sceneEntity: Entity { entity }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
