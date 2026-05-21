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
