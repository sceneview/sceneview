#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// A node that renders a line segment between two 3D points.
///
/// Mirrors SceneView Android's `LineNode` — creates a thin cylinder
/// mesh between two points as RealityKit has no native line primitive.
///
/// ```swift
/// SceneView { content in
///     let line = LineNode(
///         from: .init(x: 0, y: 0, z: 0),
///         to: .init(x: 1, y: 1, z: 0),
///         color: .red
///     )
///     content.add(line.entity)
/// }
/// ```
public struct LineNode: Sendable {
    /// The underlying RealityKit entity.
    public let entity: ModelEntity

    /// Creates a line segment between two points.
    ///
    /// Implemented as a thin cylinder oriented between the two endpoints.
    ///
    /// - Parameters:
    ///   - from: Start point in world space.
    ///   - to: End point in world space.
    ///   - thickness: Line thickness in meters. Default 0.005.
    ///   - color: Line color. Default white.
    ///   - unlit: When `true`, uses a flat unlit material instead of the default
    ///     physically-based material. Default `false`.
    public init(
        from: SIMD3<Float>,
        to: SIMD3<Float>,
        thickness: Float = 0.005,
        color: SimpleMaterial.Color = .white,
        unlit: Bool = false
    ) {
        let direction = to - from
        let length = simd_length(direction)

        // Create a thin cylinder as the line segment.
        let mesh = MeshResource.generateCylinder(
            height: length,
            radius: thickness / 2
        )
        let material = GeometryNode.defaultMaterial(color: color, unlit: unlit)
        let entity = ModelEntity(mesh: mesh, materials: [material])

        // Position at midpoint
        let midpoint = (from + to) / 2
        entity.position = midpoint

        // Orient along the direction vector
        if length > 0.0001 {
            let up = SIMD3<Float>(0, 1, 0)
            let normalized = direction / length
            // Cylinder default axis is Y-up, rotate to match direction
            let rotation = simd_quatf(from: up, to: normalized)
            entity.orientation = rotation
        }

        self.entity = entity
    }

    /// Creates an axis gizmo (X=red, Y=green, Z=blue) at the given position.
    ///
    /// Mirrors the axis visualization in SceneView Android's line-path sample.
    ///
    /// - Parameters:
    ///   - origin: Center position of the gizmo.
    ///   - length: Length of each axis line. Default 0.5.
    ///   - thickness: Line thickness. Default 0.005.
    /// - Returns: An array of 3 LineNode entities (X, Y, Z).
    public static func axisGizmo(
        at origin: SIMD3<Float> = .zero,
        length: Float = 0.5,
        thickness: Float = 0.005
    ) -> [LineNode] {
        [
            LineNode(from: origin, to: origin + .init(x: length, y: 0, z: 0),
                     thickness: thickness, color: .red),
            LineNode(from: origin, to: origin + .init(x: 0, y: length, z: 0),
                     thickness: thickness, color: .green),
            LineNode(from: origin, to: origin + .init(x: 0, y: 0, z: length),
                     thickness: thickness, color: .blue)
        ]
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
