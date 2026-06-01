#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation
import Combine

/// Observable wrapper for entity transform changes.
///
/// Publishes position, rotation, and scale changes using Combine publishers.
/// Useful for syncing 3D state with SwiftUI or triggering side effects.
///
/// ```swift
/// let observer = EntityObserver(entity: model.entity)
///
/// // Observe position changes
/// observer.positionPublisher
///     .sink { position in
///         print("Moved to: \(position)")
///     }
///     .store(in: &cancellables)
///
/// // Poll for changes (call from a timer or frame update)
/// observer.update()
/// ```
public final class EntityObserver: ObservableObject {

    /// The observed entity.
    public let entity: Entity

    /// Current position (published).
    @Published public private(set) var currentPosition: SIMD3<Float>

    /// Current rotation (published).
    @Published public private(set) var currentRotation: simd_quatf

    /// Current scale (published).
    @Published public private(set) var currentScale: SIMD3<Float>

    /// Whether the entity is currently enabled (published).
    @Published public private(set) var isEnabled: Bool

    /// Publisher for position changes.
    public var positionPublisher: Published<SIMD3<Float>>.Publisher {
        $currentPosition
    }

    /// Publisher for rotation changes.
    public var rotationPublisher: Published<simd_quatf>.Publisher {
        $currentRotation
    }

    /// Publisher for scale changes.
    public var scalePublisher: Published<SIMD3<Float>>.Publisher {
        $currentScale
    }

    /// Creates an observer for the given entity.
    ///
    /// - Parameter entity: The entity to observe.
    public init(entity: Entity) {
        self.entity = entity
        self.currentPosition = entity.position
        self.currentRotation = entity.orientation
        self.currentScale = entity.scale
        self.isEnabled = entity.isEnabled
    }

    /// Checks the entity's current transform and publishes changes.
    ///
    /// Call this from a frame update loop or timer to detect changes.
    public func update() {
        let pos = entity.position
        let rot = entity.orientation
        let scl = entity.scale
        let enabled = entity.isEnabled

        if pos != currentPosition {
            currentPosition = pos
        }
        if rot != currentRotation {
            currentRotation = rot
        }
        if scl != currentScale {
            currentScale = scl
        }
        if enabled != isEnabled {
            self.isEnabled = enabled
        }
    }
}

/// Observable scene state for binding to SwiftUI.
///
/// Tracks commonly needed scene information and publishes changes.
///
/// ```swift
/// @StateObject private var sceneState = SceneObserver()
///
/// VStack {
///     Text("Entities: \(sceneState.entityCount)")
///     Text("FPS: \(sceneState.estimatedFPS)")
/// }
/// ```
public final class SceneObserver: ObservableObject {

    /// Number of entities in the scene.
    @Published public private(set) var entityCount: Int = 0

    /// Estimated frames per second (updated per frame).
    @Published public private(set) var estimatedFPS: Double = 0

    /// The scene root entity.
    public private(set) var rootEntity: Entity?

    /// Timestamp of last update.
    private var lastUpdateTime: CFAbsoluteTime = 0

    /// Creates a scene observer.
    public init() {}

    /// Associates this observer with a root entity.
    ///
    /// - Parameter root: The scene's root entity.
    public func observe(_ root: Entity) {
        self.rootEntity = root
        lastUpdateTime = CFAbsoluteTimeGetCurrent()
    }

    /// Updates metrics. Call from a per-frame update.
    ///
    /// Both `@Published` properties are gated on a real value change: a
    /// `@Published` setter fires `objectWillChange` even when the assigned
    /// value is identical, so a static scene graph would otherwise publish a
    /// redundant `entityCount` every frame and wake every bound SwiftUI view.
    /// Assigning only on inequality is observationally identical (an observer
    /// cannot distinguish "assigned the same value" from "not assigned"),
    /// matching the change-gating already used by ``EntityObserver/update()``.
    public func update() {
        guard let root = rootEntity else { return }

        // Count entities (only publishes when the count actually changed).
        var count = 0
        countEntities(root, count: &count)
        if count != entityCount {
            entityCount = count
        }

        // Estimate FPS (gated likewise; identical only when dt repeats exactly).
        let now = CFAbsoluteTimeGetCurrent()
        let dt = now - lastUpdateTime
        if dt > 0 {
            let fps = 1.0 / dt
            if fps != estimatedFPS {
                estimatedFPS = fps
            }
        }
        lastUpdateTime = now
    }

    private func countEntities(_ entity: Entity, count: inout Int) {
        count += 1
        for child in entity.children {
            countEntities(child, count: &count)
        }
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
