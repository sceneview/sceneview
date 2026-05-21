#if os(iOS) || os(visionOS) || os(macOS)
import SwiftUI
import RealityKit
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// A placeholder node for embedding a SwiftUI view as a 3D entity.
///
/// > Warning: **The SwiftUI content is not yet rendered.** This node
/// > currently displays a plain white plane regardless of the `content`
/// > you pass — the UIView/NSView → `MTLTexture` pipeline that would turn
/// > a SwiftUI view into a 3D surface is not implemented. The `content`
/// > closure is stored but never displayed, and there is **no gesture
/// > forwarding** into the view. Building that pipeline is tracked by
/// > [#1035](https://github.com/sceneview/sceneview/issues/1035).
///
/// `ViewNode` is the planned Apple counterpart of SceneView Android's
/// `ViewNode`. It ships now only so the type and its transform API are
/// stable for callers; treat it as **"Coming soon"** for content
/// rendering. Use a textured ``ImageNode`` if you need a static 2D
/// surface today.
///
/// ```swift
/// // Currently renders a blank white plane — the SwiftUI content is
/// // not yet displayed. See #1035 for the rendering pipeline.
/// SceneView { root in
///     let viewNode = ViewNode {
///         Text("Coming soon")
///     }
///     viewNode.position = SIMD3<Float>(0, 1.5, -2)
///     root.addChild(viewNode.entity)
/// }
/// ```
@available(*, deprecated, message: "ViewNode renders a placeholder plane only — the SwiftUI content is not yet displayed. Tracked by #1035.")
public struct ViewNode<Content: View>: @unchecked Sendable {
    /// The underlying RealityKit entity.
    public let entity: Entity

    /// The SwiftUI content to render.
    public let content: Content

    /// Pixels per meter for rendering resolution. Higher values produce sharper text.
    public let pixelsPerMeter: Float

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

    /// Creates a `ViewNode` placeholder plane.
    ///
    /// > Warning: The `content` you pass is **stored but not rendered** —
    /// > this produces a plain white plane. SwiftUI-content rendering is
    /// > tracked by #1035.
    ///
    /// - Parameters:
    ///   - pixelsPerMeter: Intended rendering resolution. Stored for the
    ///     future #1035 pipeline; has no effect today.
    ///   - width: Plane width in meters. Default 0.5.
    ///   - height: Plane height in meters. Default 0.3.
    ///   - content: The SwiftUI view to display once #1035 lands. Not
    ///     displayed by the current placeholder implementation.
    public init(
        pixelsPerMeter: Float = 500,
        width: Float = 0.5,
        height: Float = 0.3,
        @ViewBuilder content: () -> Content
    ) {
        self.content = content()
        self.pixelsPerMeter = pixelsPerMeter

        let containerEntity = Entity()
        containerEntity.name = "ViewNode"

        // Create a plane to display the view content
        let mesh = MeshResource.generatePlane(width: width, height: height)
        let material = SimpleMaterial(color: .white, isMetallic: false)
        let planeEntity = ModelEntity(mesh: mesh, materials: [material])
        planeEntity.generateCollisionShapes(recursive: false)
        containerEntity.addChild(planeEntity)

        self.entity = containerEntity
    }

    // MARK: - Transform helpers

    /// Returns self positioned at the given coordinates.
    @discardableResult
    public func position(_ position: SIMD3<Float>) -> ViewNode {
        entity.position = position
        return self
    }

    /// Returns self scaled uniformly.
    @discardableResult
    public func scale(_ uniform: Float) -> ViewNode {
        entity.scale = .init(repeating: uniform)
        return self
    }

    /// Returns self rotated by the given quaternion.
    @discardableResult
    public func rotation(_ rotation: simd_quatf) -> ViewNode {
        entity.orientation = rotation
        return self
    }

    /// Returns self rotated by angle around axis.
    @discardableResult
    public func rotation(angle: Float, axis: SIMD3<Float>) -> ViewNode {
        entity.orientation = simd_quatf(angle: angle, axis: axis)
        return self
    }
}

#endif // os(iOS) || os(visionOS) || os(macOS)
