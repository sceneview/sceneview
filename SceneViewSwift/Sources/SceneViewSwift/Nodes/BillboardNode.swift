#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// A node that always faces the camera (billboard behavior).
///
/// Mirrors SceneView Android's `BillboardNode` — wraps any entity
/// and applies a `BillboardComponent` so it always faces the viewer.
///
/// Useful for floating labels, health bars, icons, and HUD-like 3D elements.
///
/// ```swift
/// SceneView { content in
///     // Billboard text label
///     let label = TextNode(text: "Player 1", fontSize: 0.03)
///     let billboard = BillboardNode(child: label.entity)
///         .position(.init(x: 0, y: 1.5, z: -2))
///     content.addChild(billboard.entity)
///
///     // Billboard with TextNode convenience
///     let nameTag = BillboardNode.text("Hello!", fontSize: 0.05)
///         .position(.init(x: 1, y: 1, z: -2))
///     content.addChild(nameTag.entity)
/// }
/// ```
public struct BillboardNode: Sendable {
    /// The parent entity with billboard behavior.
    public let entity: Entity

    /// World-space position.
    public var position: SIMD3<Float> {
        get { entity.position }
        nonmutating set { entity.position = newValue }
    }

    /// Creates a billboard node wrapping a child entity.
    ///
    /// The child will always rotate to face the camera.
    ///
    /// - Parameter child: The entity to make face the camera.
    public init(child: Entity) {
        let container = Entity()
        container.addChild(child)
        if #available(iOS 18.0, visionOS 2.0, *) {
            container.components.set(BillboardComponent())
        }
        self.entity = container
    }

    /// Creates a billboard text label (convenience).
    ///
    /// - Parameters:
    ///   - text: The text to display.
    ///   - fontSize: Font size in meters.
    ///   - color: Text color.
    /// - Returns: A `BillboardNode` containing the text, always facing camera.
    public static func text(
        _ text: String,
        fontSize: Float = 0.05,
        color: SimpleMaterial.Color = .white
    ) -> BillboardNode {
        let font = MeshResource.Font.systemFont(ofSize: CGFloat(fontSize))
        let textNode = TextNode(text: text, font: font, color: color)
            .centered()
        return BillboardNode(child: textNode.entity)
    }

    // MARK: - Transform helpers

    /// Returns self positioned at the given coordinates.
    @discardableResult
    public func position(_ position: SIMD3<Float>) -> BillboardNode {
        entity.position = position
        return self
    }

    /// Returns self scaled uniformly.
    @discardableResult
    public func scale(_ uniform: Float) -> BillboardNode {
        entity.scale = .init(repeating: uniform)
        return self
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
