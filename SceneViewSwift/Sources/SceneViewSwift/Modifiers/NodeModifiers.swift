#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation

/// SwiftUI-style modifiers for common entity operations.
///
/// Provides a fluent, chainable API for configuring entities similar
/// to SwiftUI view modifiers. Works on any `Entity` type.
///
/// ```swift
/// let entity = ModelEntity()
/// entity
///     .positioned(at: [0, 1, -2])
///     .scaled(to: 0.5)
///     .rotated(by: .pi / 4, around: [0, 1, 0])
///     .named("myEntity")
///     .enabled(true)
/// ```
extension Entity {

    /// Sets the position and returns self for chaining.
    ///
    /// - Parameter position: World-space position.
    /// - Returns: Self for chaining.
    @discardableResult
    public func positioned(at position: SIMD3<Float>) -> Self {
        self.position = position
        return self
    }

    /// Sets uniform scale and returns self for chaining.
    ///
    /// - Parameter factor: Uniform scale factor.
    /// - Returns: Self for chaining.
    @discardableResult
    public func scaled(to factor: Float) -> Self {
        self.scale = .init(repeating: factor)
        return self
    }

    /// Sets per-axis scale and returns self for chaining.
    ///
    /// - Parameter scale: Per-axis scale factors.
    /// - Returns: Self for chaining.
    @discardableResult
    public func scaled(to scale: SIMD3<Float>) -> Self {
        self.scale = scale
        return self
    }

    /// Rotates by angle around axis and returns self for chaining.
    ///
    /// - Parameters:
    ///   - angle: Rotation angle in radians.
    ///   - axis: Rotation axis (unit vector).
    /// - Returns: Self for chaining.
    @discardableResult
    public func rotated(by angle: Float, around axis: SIMD3<Float>) -> Self {
        self.orientation = simd_quatf(angle: angle, axis: axis)
        return self
    }

    /// Sets the orientation quaternion and returns self for chaining.
    ///
    /// - Parameter quaternion: Orientation quaternion.
    /// - Returns: Self for chaining.
    @discardableResult
    public func oriented(to quaternion: simd_quatf) -> Self {
        self.orientation = quaternion
        return self
    }

    /// Sets the entity name and returns self for chaining.
    ///
    /// - Parameter name: The entity name.
    /// - Returns: Self for chaining.
    @discardableResult
    public func named(_ name: String) -> Self {
        self.name = name
        return self
    }

    /// Sets the enabled state and returns self for chaining.
    ///
    /// - Parameter enabled: Whether the entity is enabled.
    /// - Returns: Self for chaining.
    @discardableResult
    public func enabled(_ enabled: Bool) -> Self {
        self.isEnabled = enabled
        return self
    }

    /// Points the entity at a target position and returns self for chaining.
    ///
    /// - Parameters:
    ///   - target: World-space target position.
    ///   - from: Position to look from. Default is current position.
    /// - Returns: Self for chaining.
    @discardableResult
    public func looking(at target: SIMD3<Float>, from: SIMD3<Float>? = nil) -> Self {
        self.look(at: target, from: from ?? self.position, relativeTo: nil)
        return self
    }

    /// Adds a child entity and returns self for chaining.
    ///
    /// - Parameter child: The child entity to add.
    /// - Returns: Self for chaining.
    @discardableResult
    public func withChild(_ child: Entity) -> Self {
        self.addChild(child)
        return self
    }

    /// Adds multiple children and returns self for chaining.
    ///
    /// - Parameter children: The child entities to add.
    /// - Returns: Self for chaining.
    @discardableResult
    public func withChildren(_ children: [Entity]) -> Self {
        for child in children {
            self.addChild(child)
        }
        return self
    }
}

// MARK: - ModelEntity convenience modifiers

extension ModelEntity {

    /// Generates collision shapes and returns self for chaining.
    ///
    /// - Parameter recursive: Whether to generate shapes for children too.
    /// - Returns: Self for chaining.
    @discardableResult
    public func withCollision(recursive: Bool = true) -> Self {
        self.generateCollisionShapes(recursive: recursive)
        return self
    }

    /// Adds a grounding shadow component and returns self for chaining.
    ///
    /// - Returns: Self for chaining.
    @discardableResult
    public func withShadow() -> Self {
        if #available(iOS 18.0, visionOS 2.0, *) {
            self.components.set(GroundingShadowComponent(castsShadow: true))
        }
        return self
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
