#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation

/// Gesture types that can be recognized on individual entities.
///
/// Mirrors SceneView Android's gesture detection system — provides per-entity
/// tap, drag, pinch-scale, and rotation gesture handling.
///
/// ```swift
/// let cube = GeometryNode.cube(size: 0.3, color: .blue)
/// NodeGesture.onTap(cube.entity) {
///     print("Cube tapped!")
/// }
/// NodeGesture.onDrag(cube.entity) { translation in
///     cube.position += translation
/// }
/// ```
///
/// ## Memory ownership
///
/// Handlers are stored in a RealityKit `Component` on the **target entity
/// itself**, so they live exactly as long as that entity does and never
/// outlive the scene that owns it (unlike a process-global registry).
///
/// A handler that captures the node it is registered on — as the example
/// above does — forms a retain cycle (entity → component → closure →
/// node → entity). ``SceneView`` breaks this automatically when the scene
/// is torn down (it calls ``removeAllHandlers(under:)`` on its content
/// root). If you register handlers on entities **outside** a `SceneView`
/// lifecycle, either `[weak]`-capture or call ``removeAll(from:)`` when
/// done to avoid leaking the entity.
public enum NodeGesture {

    // MARK: - Gesture state storage

    /// Per-entity gesture handler storage.
    ///
    /// The handlers are kept in a RealityKit `Component` **attached to the
    /// target entity itself** rather than in process-global `static`
    /// dictionaries. This is the fix for the leak described in #2038:
    ///
    /// - **No leak.** A component is owned by its entity, so the handler
    ///   closures live exactly as long as the entity does. The common
    ///   capture pattern — `onDrag(cube.entity) { cube.position += … }`,
    ///   where the closure retains the node that owns the entity — no
    ///   longer creates an immortal global → closure → entity → resources
    ///   chain. When the scene is torn down and drops its last reference
    ///   to the entity, the component (and its closures) deallocate with it.
    /// - **No cross-scene contamination.** Storage is per-entity, so two
    ///   `SceneView` instances can never share a handler table or have one
    ///   `removeAllHandlers()` wipe the other's gestures.
    struct GestureHandlers: Component {
        var tap: (() -> Void)?
        var drag: ((SIMD3<Float>) -> Void)?
        var scale: ((Float) -> Void)?
        var rotate: ((Float) -> Void)?
        var longPress: (() -> Void)?

        var isEmpty: Bool {
            tap == nil && drag == nil && scale == nil
                && rotate == nil && longPress == nil
        }
    }

    // MARK: - Registration

    /// Registers a tap handler for an entity.
    ///
    /// The entity must have collision shapes for hit testing.
    ///
    /// - Parameters:
    ///   - entity: The entity to detect taps on.
    ///   - handler: Closure invoked when the entity is tapped.
    @MainActor
    public static func onTap(_ entity: Entity, handler: @escaping () -> Void) {
        ensureCollision(entity)
        mutateHandlers(of: entity) { $0.tap = handler }
    }

    /// Registers a drag handler for an entity.
    ///
    /// - Parameters:
    ///   - entity: The entity to detect drags on.
    ///   - handler: Closure invoked with the translation delta (in world space).
    @MainActor
    public static func onDrag(_ entity: Entity, handler: @escaping (SIMD3<Float>) -> Void) {
        ensureCollision(entity)
        mutateHandlers(of: entity) { $0.drag = handler }
    }

    /// Registers a pinch-to-scale handler for an entity.
    ///
    /// - Parameters:
    ///   - entity: The entity to detect scale gestures on.
    ///   - handler: Closure invoked with the magnification factor.
    @MainActor
    public static func onScale(_ entity: Entity, handler: @escaping (Float) -> Void) {
        ensureCollision(entity)
        mutateHandlers(of: entity) { $0.scale = handler }
    }

    /// Registers a two-finger rotation handler for an entity.
    ///
    /// - Parameters:
    ///   - entity: The entity to detect rotation gestures on.
    ///   - handler: Closure invoked with the rotation angle in radians.
    @MainActor
    public static func onRotate(_ entity: Entity, handler: @escaping (Float) -> Void) {
        ensureCollision(entity)
        mutateHandlers(of: entity) { $0.rotate = handler }
    }

    /// Registers a long press handler for an entity.
    ///
    /// - Parameters:
    ///   - entity: The entity to detect long presses on.
    ///   - handler: Closure invoked when the entity is long-pressed.
    @MainActor
    public static func onLongPress(_ entity: Entity, handler: @escaping () -> Void) {
        ensureCollision(entity)
        mutateHandlers(of: entity) { $0.longPress = handler }
    }

    // MARK: - Deregistration

    /// Removes all gesture handlers for an entity.
    @MainActor
    public static func removeAll(from entity: Entity) {
        entity.components.remove(GestureHandlers.self)
    }

    /// Removes all registered gesture handlers reachable from a scene root.
    ///
    /// Walks `root` and all of its descendants, removing the gesture
    /// handler component from each. Pass the scene's content root so the
    /// teardown is scoped to that scene — gesture handlers registered in a
    /// different `SceneView` are untouched (handler storage is per-entity,
    /// so there is no longer a shared global table to corrupt).
    ///
    /// - Parameter root: The scene root whose subtree should be cleared.
    @MainActor
    public static func removeAllHandlers(under root: Entity) {
        root.components.remove(GestureHandlers.self)
        for child in root.children {
            removeAllHandlers(under: child)
        }
    }

    // MARK: - Dispatch (called by scene implementation)

    /// Dispatches a tap event to the entity's registered handler.
    @MainActor
    public static func dispatchTap(on entity: Entity) {
        entity.components[GestureHandlers.self]?.tap?()
    }

    /// Dispatches a drag event to the entity's registered handler.
    @MainActor
    public static func dispatchDrag(on entity: Entity, translation: SIMD3<Float>) {
        entity.components[GestureHandlers.self]?.drag?(translation)
    }

    /// Dispatches a scale event to the entity's registered handler.
    @MainActor
    public static func dispatchScale(on entity: Entity, magnification: Float) {
        entity.components[GestureHandlers.self]?.scale?(magnification)
    }

    /// Dispatches a rotation event to the entity's registered handler.
    @MainActor
    public static func dispatchRotate(on entity: Entity, angle: Float) {
        entity.components[GestureHandlers.self]?.rotate?(angle)
    }

    /// Dispatches a long press event to the entity's registered handler.
    @MainActor
    public static func dispatchLongPress(on entity: Entity) {
        entity.components[GestureHandlers.self]?.longPress?()
    }

    /// Whether the entity has any registered gesture handlers.
    @MainActor
    public static func hasHandlers(for entity: Entity) -> Bool {
        guard let handlers = entity.components[GestureHandlers.self] else {
            return false
        }
        return !handlers.isEmpty
    }

    // MARK: - Private helpers

    private static func ensureCollision(_ entity: Entity) {
        if entity.components[CollisionComponent.self] == nil {
            if let modelEntity = entity as? ModelEntity {
                modelEntity.generateCollisionShapes(recursive: true)
            }
        }
        // A collision shape alone never made the entity reachable: SwiftUI's
        // `targetedToAnyEntity()` gestures — which is how every `NodeGesture` handler is
        // dispatched — also require an `InputTargetComponent`. Without it, registering a
        // handler here succeeded and the handler simply never fired, with no error and no
        // warning. See ``Entity/makeInputTargetable()``.
        entity.makeInputTargetable()
    }

    /// Reads, mutates, and writes back the entity's `GestureHandlers`
    /// component, removing it entirely if the mutation left it empty.
    @MainActor
    private static func mutateHandlers(
        of entity: Entity,
        _ body: (inout GestureHandlers) -> Void
    ) {
        var handlers = entity.components[GestureHandlers.self] ?? GestureHandlers()
        body(&handlers)
        if handlers.isEmpty {
            entity.components.remove(GestureHandlers.self)
        } else {
            entity.components.set(handlers)
        }
    }
}

// MARK: - Entity convenience extensions

extension Entity {
    /// Registers a tap handler on this entity. Returns self for chaining.
    ///
    /// ```swift
    /// let cube = GeometryNode.cube(size: 0.3, color: .blue)
    ///     .entity
    ///     .onTap { print("Tapped!") }
    /// ```
    @MainActor
    @discardableResult
    public func onTap(_ handler: @escaping () -> Void) -> Entity {
        NodeGesture.onTap(self, handler: handler)
        return self
    }

    /// Registers a drag handler on this entity. Returns self for chaining.
    @MainActor
    @discardableResult
    public func onDrag(_ handler: @escaping (SIMD3<Float>) -> Void) -> Entity {
        NodeGesture.onDrag(self, handler: handler)
        return self
    }

    /// Registers a scale handler on this entity. Returns self for chaining.
    @MainActor
    @discardableResult
    public func onScale(_ handler: @escaping (Float) -> Void) -> Entity {
        NodeGesture.onScale(self, handler: handler)
        return self
    }

    /// Registers a rotation handler on this entity. Returns self for chaining.
    @MainActor
    @discardableResult
    public func onRotate(_ handler: @escaping (Float) -> Void) -> Entity {
        NodeGesture.onRotate(self, handler: handler)
        return self
    }

    /// Registers a long press handler on this entity. Returns self for chaining.
    @MainActor
    @discardableResult
    public func onLongPress(_ handler: @escaping () -> Void) -> Entity {
        NodeGesture.onLongPress(self, handler: handler)
        return self
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
