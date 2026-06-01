import XCTest
@testable import SceneViewSwift

#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Combine

// Test classes run on the main actor: their RealityKit node factories
// (`LightNode.directional`, `node.entity`, …) are `@MainActor`. (#1054)
@MainActor
final class SceneObservationTests: XCTestCase {

    // MARK: - EntityObserver

    func testEntityObserverInitialValues() {
        let entity = Entity()
        entity.position = [1, 2, 3]
        entity.scale = [0.5, 0.5, 0.5]

        let observer = EntityObserver(entity: entity)
        XCTAssertEqual(observer.currentPosition.x, 1.0, accuracy: 0.001)
        XCTAssertEqual(observer.currentPosition.y, 2.0, accuracy: 0.001)
        XCTAssertEqual(observer.currentScale.x, 0.5, accuracy: 0.001)
        XCTAssertTrue(observer.isEnabled)
    }

    func testEntityObserverDetectsPositionChange() {
        let entity = Entity()
        entity.position = [0, 0, 0]

        let observer = EntityObserver(entity: entity)
        XCTAssertEqual(observer.currentPosition.x, 0.0, accuracy: 0.001)

        entity.position = [5, 5, 5]
        observer.update()
        XCTAssertEqual(observer.currentPosition.x, 5.0, accuracy: 0.001)
    }

    func testEntityObserverDetectsScaleChange() {
        let entity = Entity()
        let observer = EntityObserver(entity: entity)

        entity.scale = [2, 2, 2]
        observer.update()
        XCTAssertEqual(observer.currentScale.x, 2.0, accuracy: 0.001)
    }

    func testEntityObserverDetectsEnabledChange() {
        let entity = Entity()
        let observer = EntityObserver(entity: entity)

        entity.isEnabled = false
        observer.update()
        XCTAssertFalse(observer.isEnabled)
    }

    // MARK: - SceneObserver

    func testSceneObserverInitialState() {
        let observer = SceneObserver()
        XCTAssertEqual(observer.entityCount, 0)
        XCTAssertNil(observer.rootEntity)
    }

    func testSceneObserverObserve() {
        let root = Entity()
        let observer = SceneObserver()
        observer.observe(root)
        XCTAssertNotNil(observer.rootEntity)
    }

    func testSceneObserverCountsEntities() {
        let root = Entity()
        let child1 = Entity()
        let child2 = Entity()
        let grandchild = Entity()
        root.addChild(child1)
        root.addChild(child2)
        child1.addChild(grandchild)

        let observer = SceneObserver()
        observer.observe(root)
        observer.update()

        // root + child1 + child2 + grandchild = 4
        XCTAssertEqual(observer.entityCount, 4)
    }

    func testSceneObserverFPS() {
        let root = Entity()
        let observer = SceneObserver()
        observer.observe(root)

        // First update sets the baseline
        observer.update()
        // Second update computes FPS
        observer.update()

        // FPS should be positive (though it may be very high due to fast execution)
        XCTAssertGreaterThan(observer.estimatedFPS, 0)
    }

    // MARK: - #2331 iOS9 — gate @Published entityCount on real change

    /// A static scene graph must not re-publish `entityCount` every frame.
    /// `@Published` fires on every *assignment*, so the `$entityCount`
    /// publisher emits once on subscription plus once per genuine change.
    /// With change-gating, repeated `update()` calls over an unchanging graph
    /// publish `entityCount` exactly once (the first count), not once per
    /// frame — the redundant per-frame wake of bound SwiftUI views is gone.
    func testSceneObserverDoesNotRepublishEntityCountWhenUnchanged() {
        let root = Entity()
        root.addChild(Entity())
        let observer = SceneObserver()
        observer.observe(root)

        var emissions: [Int] = []
        let cancellable = observer.$entityCount.sink { emissions.append($0) }
        defer { cancellable.cancel() }

        // Drive several frames over a graph that never changes.
        for _ in 0..<5 { observer.update() }

        // One emission on subscription (initial 0) + one for the real change
        // to 2 (root + child). No extra emissions for the 4 redundant frames.
        XCTAssertEqual(emissions, [0, 2],
                       "entityCount must publish only on a real change, not every frame")
        XCTAssertEqual(observer.entityCount, 2)
    }

    /// Gating must still publish when the entity count genuinely changes
    /// (e.g. a node is added mid-session).
    func testSceneObserverRepublishesEntityCountWhenItChanges() {
        let root = Entity()
        let observer = SceneObserver()
        observer.observe(root)

        var emissions: [Int] = []
        let cancellable = observer.$entityCount.sink { emissions.append($0) }
        defer { cancellable.cancel() }

        observer.update()              // count = 1 (root only)
        root.addChild(Entity())
        observer.update()              // count = 2 (root + child)
        observer.update()              // unchanged → no new emission

        // subscription(0) + change to 1 + change to 2.
        XCTAssertEqual(emissions, [0, 1, 2])
        XCTAssertEqual(observer.entityCount, 2)
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
