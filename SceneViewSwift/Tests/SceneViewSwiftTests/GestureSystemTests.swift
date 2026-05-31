import XCTest
@testable import SceneViewSwift

#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit

// Test classes run on the main actor: their RealityKit node factories
// (`LightNode.directional`, `node.entity`, …) are `@MainActor`. (#1054)
@MainActor
final class GestureSystemTests: XCTestCase {

    // MARK: - Registration

    @MainActor
    func testOnTapRegistersHandler() {
        let entity = ModelEntity(
            mesh: .generateBox(size: 1.0),
            materials: [SimpleMaterial()]
        )
        NodeGesture.onTap(entity) { }
        XCTAssertTrue(NodeGesture.hasHandlers(for: entity))
    }

    @MainActor
    func testOnDragRegistersHandler() {
        let entity = ModelEntity(
            mesh: .generateBox(size: 1.0),
            materials: [SimpleMaterial()]
        )
        NodeGesture.onDrag(entity) { _ in }
        XCTAssertTrue(NodeGesture.hasHandlers(for: entity))
    }

    @MainActor
    func testOnScaleRegistersHandler() {
        let entity = ModelEntity(
            mesh: .generateBox(size: 1.0),
            materials: [SimpleMaterial()]
        )
        NodeGesture.onScale(entity) { _ in }
        XCTAssertTrue(NodeGesture.hasHandlers(for: entity))
    }

    @MainActor
    func testOnRotateRegistersHandler() {
        let entity = ModelEntity(
            mesh: .generateBox(size: 1.0),
            materials: [SimpleMaterial()]
        )
        NodeGesture.onRotate(entity) { _ in }
        XCTAssertTrue(NodeGesture.hasHandlers(for: entity))
    }

    @MainActor
    func testOnLongPressRegistersHandler() {
        let entity = ModelEntity(
            mesh: .generateBox(size: 1.0),
            materials: [SimpleMaterial()]
        )
        NodeGesture.onLongPress(entity) { }
        XCTAssertTrue(NodeGesture.hasHandlers(for: entity))
    }

    // MARK: - Dispatch

    @MainActor
    func testDispatchTapCallsHandler() {
        let entity = ModelEntity(
            mesh: .generateBox(size: 1.0),
            materials: [SimpleMaterial()]
        )
        var tapped = false
        NodeGesture.onTap(entity) { tapped = true }
        NodeGesture.dispatchTap(on: entity)
        XCTAssertTrue(tapped)
    }

    @MainActor
    func testDispatchDragCallsHandler() {
        let entity = ModelEntity(
            mesh: .generateBox(size: 1.0),
            materials: [SimpleMaterial()]
        )
        var receivedTranslation: SIMD3<Float>?
        NodeGesture.onDrag(entity) { t in receivedTranslation = t }
        NodeGesture.dispatchDrag(on: entity, translation: [1, 2, 3])
        XCTAssertEqual(receivedTranslation?.x ?? 0, 1.0, accuracy: 0.001)
    }

    // MARK: - Per-frame drag delta (#2283)

    /// Reproduces the cumulative→delta conversion `entityDragGesture`
    /// performs and asserts the `onDrag` contract: a natural
    /// `entity.position += delta` handler must track the pointer 1:1, i.e.
    /// the entity's final offset equals the gesture's *final cumulative*
    /// translation — NOT the sum of every intermediate cumulative value
    /// (the #2283 bug, which made the entity accelerate off-screen).
    @MainActor
    func testEntityDragHandlerTracksPointerWithPerFrameDelta() {
        let entity = ModelEntity(
            mesh: .generateBox(size: 1.0),
            materials: [SimpleMaterial()]
        )
        entity.position = .zero

        // The documented capture pattern: integrate the delta into position.
        NodeGesture.onDrag(entity) { delta in entity.position += delta }

        // Same per-entity baseline the gesture keeps (keyed by ObjectIdentifier
        // since Entity isn't Hashable), and the same `current − previous`
        // dispatch the fixed `entityDragGesture.onChanged` performs.
        var lastTranslation: [ObjectIdentifier: SIMD3<Float>] = [:]
        let key = ObjectIdentifier(entity)
        func dispatchCumulative(_ cumulative: SIMD3<Float>) {
            let previous = lastTranslation[key] ?? .zero
            NodeGesture.dispatchDrag(on: entity, translation: cumulative - previous)
            lastTranslation[key] = cumulative
        }

        // SwiftUI emits *cumulative* translation each tick.
        dispatchCumulative([0.10, 0.0, 0])
        dispatchCumulative([0.25, 0.0, 0])
        dispatchCumulative([0.40, 0.0, 0]) // final cumulative = 0.40

        // 1:1 tracking — final position == final cumulative translation.
        XCTAssertEqual(entity.position.x, 0.40, accuracy: 0.0001)
        XCTAssertEqual(entity.position.y, 0.0, accuracy: 0.0001)

        // The buggy code dispatched the cumulative value every tick, so the
        // position would have summed 0.10+0.25+0.40 = 0.75 — guard against it.
        XCTAssertNotEqual(entity.position.x, 0.75, accuracy: 0.0001)
    }

    /// A second gesture on the same entity must start from a zero baseline —
    /// `.onEnded` clears the per-entity entry, so the next drag's first delta
    /// equals its first cumulative value rather than jumping by the previous
    /// gesture's final offset.
    @MainActor
    func testEntityDragBaselineResetsBetweenGestures() {
        let entity = ModelEntity(
            mesh: .generateBox(size: 1.0),
            materials: [SimpleMaterial()]
        )
        entity.position = .zero
        NodeGesture.onDrag(entity) { delta in entity.position += delta }

        var lastTranslation: [ObjectIdentifier: SIMD3<Float>] = [:]
        let key = ObjectIdentifier(entity)
        func dispatchCumulative(_ cumulative: SIMD3<Float>) {
            let previous = lastTranslation[key] ?? .zero
            NodeGesture.dispatchDrag(on: entity, translation: cumulative - previous)
            lastTranslation[key] = cumulative
        }
        func endGesture() { lastTranslation[key] = nil } // mirrors .onEnded

        // First gesture drags to +0.30.
        dispatchCumulative([0.30, 0.0, 0])
        endGesture()
        XCTAssertEqual(entity.position.x, 0.30, accuracy: 0.0001)

        // Second gesture's first cumulative tick is +0.05 — must move by 0.05,
        // not snap back by the previous gesture's 0.30 baseline.
        dispatchCumulative([0.05, 0.0, 0])
        XCTAssertEqual(entity.position.x, 0.35, accuracy: 0.0001)
    }

    @MainActor
    func testDispatchScaleCallsHandler() {
        let entity = ModelEntity(
            mesh: .generateBox(size: 1.0),
            materials: [SimpleMaterial()]
        )
        var receivedScale: Float?
        NodeGesture.onScale(entity) { s in receivedScale = s }
        NodeGesture.dispatchScale(on: entity, magnification: 2.0)
        XCTAssertEqual(receivedScale ?? 0, 2.0, accuracy: 0.001)
    }

    // MARK: - Removal

    @MainActor
    func testRemoveAllFromEntity() {
        let entity = ModelEntity(
            mesh: .generateBox(size: 1.0),
            materials: [SimpleMaterial()]
        )
        NodeGesture.onTap(entity) { }
        NodeGesture.onDrag(entity) { _ in }
        XCTAssertTrue(NodeGesture.hasHandlers(for: entity))

        NodeGesture.removeAll(from: entity)
        XCTAssertFalse(NodeGesture.hasHandlers(for: entity))
    }

    @MainActor
    func testRemoveAllHandlersUnderRootClearsSubtree() {
        let root = Entity()
        let child1 = ModelEntity(
            mesh: .generateBox(size: 1.0),
            materials: [SimpleMaterial()]
        )
        let child2 = ModelEntity(
            mesh: .generateBox(size: 1.0),
            materials: [SimpleMaterial()]
        )
        root.addChild(child1)
        child1.addChild(child2)
        NodeGesture.onTap(child1) { }
        NodeGesture.onTap(child2) { }

        NodeGesture.removeAllHandlers(under: root)
        XCTAssertFalse(NodeGesture.hasHandlers(for: child1))
        XCTAssertFalse(NodeGesture.hasHandlers(for: child2))
    }

    @MainActor
    func testHasHandlersReturnsFalseForUnregistered() {
        let entity = Entity()
        XCTAssertFalse(NodeGesture.hasHandlers(for: entity))
    }

    // MARK: - Memory reclamation (#2038)

    /// Registering a gesture handler must not extend the entity's lifetime
    /// via a process-global registry. Storage is a per-entity component, so
    /// an entity whose handler does not capture itself is deallocated as
    /// soon as the last external reference is dropped — the old `static`
    /// dictionaries kept it (and its resources) alive for the whole
    /// process lifetime.
    @MainActor
    func testEntityWithHandlerIsDeallocatedWhenReleased() {
        weak var weakEntity: Entity?

        autoreleasepool {
            let entity = ModelEntity(
                mesh: .generateBox(size: 1.0),
                materials: [SimpleMaterial()]
            )
            weakEntity = entity
            var sideEffect = 0
            // Handler captures only a value, not the entity → no cycle.
            NodeGesture.onDrag(entity) { _ in sideEffect += 1 }
            XCTAssertTrue(NodeGesture.hasHandlers(for: entity))
            XCTAssertNotNil(weakEntity)
            _ = sideEffect
        }

        // No global registry retains the entity → it is gone once the
        // local strong reference leaves scope.
        XCTAssertNil(
            weakEntity,
            "Entity with a registered gesture handler leaked — handler "
                + "storage must not outlive the entity"
        )
    }

    /// The documented capture pattern — a handler that retains the node it
    /// is registered on — forms an entity ↔ component ↔ closure cycle.
    /// `removeAll(from:)` (the teardown path `SceneEntities.deinit` runs
    /// for the whole scene subtree) must break it so the entity is then
    /// deallocated. This is the regression guard for #2038's leak.
    @MainActor
    func testSelfCapturingHandlerIsReclaimedAfterTeardown() {
        weak var weakEntity: Entity?

        autoreleasepool {
            let entity = ModelEntity(
                mesh: .generateBox(size: 1.0),
                materials: [SimpleMaterial()]
            )
            weakEntity = entity
            // The exact capture pattern from the documented API example:
            // the closure strongly references the entity it drives.
            NodeGesture.onDrag(entity) { translation in
                entity.position += translation
            }
            XCTAssertNotNil(weakEntity)
            // Teardown — equivalent to what `SceneEntities.deinit` does
            // for every entity under the scene root.
            NodeGesture.removeAll(from: entity)
        }

        XCTAssertNil(
            weakEntity,
            "Self-capturing gesture handler leaked the entity even after "
                + "teardown — removeAll(from:) must break the cycle"
        )
    }

    /// Two entities with the same gesture API usage are independent — one
    /// being torn down must not affect the other's handlers (no shared
    /// global table = no cross-scene contamination).
    @MainActor
    func testHandlersAreIsolatedPerEntity() {
        let surviving = ModelEntity(
            mesh: .generateBox(size: 1.0),
            materials: [SimpleMaterial()]
        )
        var survivingTapped = false
        NodeGesture.onTap(surviving) { survivingTapped = true }

        autoreleasepool {
            let transient = ModelEntity(
                mesh: .generateBox(size: 1.0),
                materials: [SimpleMaterial()]
            )
            NodeGesture.onTap(transient) { }
            NodeGesture.removeAll(from: transient)
        }

        // Tearing down `transient` did not touch `surviving`.
        XCTAssertTrue(NodeGesture.hasHandlers(for: surviving))
        NodeGesture.dispatchTap(on: surviving)
        XCTAssertTrue(survivingTapped)
    }

    /// `removeAll(from:)` drops the handler component entirely so nothing
    /// the closures captured is retained afterwards.
    @MainActor
    func testRemoveAllFromEntityFreesCapturedReferences() {
        weak var weakCaptured: Entity?
        let host = ModelEntity(
            mesh: .generateBox(size: 1.0),
            materials: [SimpleMaterial()]
        )

        autoreleasepool {
            let captured = Entity()
            weakCaptured = captured
            NodeGesture.onTap(host) {
                // Closure strongly captures `captured`.
                _ = captured.name
            }
            XCTAssertNotNil(weakCaptured)
            NodeGesture.removeAll(from: host)
        }

        XCTAssertNil(
            weakCaptured,
            "removeAll(from:) must release the handler closure and "
                + "everything it captured"
        )
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
