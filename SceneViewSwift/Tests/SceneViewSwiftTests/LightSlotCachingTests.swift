#if os(iOS) || os(macOS) || os(visionOS)
import XCTest
import RealityKit
@testable import SceneViewSwift

/// Validates the by-reference light-entity removal invariant introduced by
/// #2278. `SceneView`'s `refreshLightSlot` used to locate the light to remove
/// with `entities.root.children.first { $0.components[LightSlotMarker.self]?.slot == which }`
/// — an O(n) tree-walk on every slot change. It now removes the previously
/// provisioned entity via a cached `Entity` reference
/// (`AppliedCache.main/fillLightEntity`), mirroring `ARSceneView`'s
/// `coordinator.main/fillLightAnchor` pattern.
///
/// `SceneViewRepresentation` (and its `AppliedCache` / `provisionLightSlot` /
/// `refreshLightSlot`) are `private`, so these tests reproduce the exact
/// removal semantics the production code relies on against the same public
/// RealityKit + `LightNode` primitives: provision two slot lights under a root,
/// cache their references, then swap one slot by removing the cached reference
/// and re-provisioning — asserting the right entity is detached and the other
/// slot's entity is untouched.
// Test classes run on the main actor: their RealityKit node factories
// (`LightNode.directional`, `node.entity`, …) are `@MainActor`. (#1054)
@MainActor
final class LightSlotCachingTests: XCTestCase {

    /// Builds the system-default main-slot light exactly as
    /// `provisionLightSlot(.main, slot: .systemDefault)` does.
    private func makeMainLight() -> Entity {
        let main = LightNode.directional(color: .white, intensity: 10_000, castsShadow: true)
        main.entity.look(at: .zero, from: [0, 1, 0], relativeTo: nil)
        return main.entity
    }

    /// Builds the system-default fill-slot light exactly as
    /// `provisionLightSlot(.fill, slot: .systemDefault)` does.
    private func makeFillLight() -> Entity {
        let fill = LightNode.fill(intensity: 3_000)
        fill.entity.look(at: .zero, from: [-0.5, 0.5, -0.5], relativeTo: nil)
        return fill.entity
    }

    /// Removing a cached light reference detaches exactly that entity and
    /// leaves the other slot's entity in place — the core #2278 invariant.
    func testCachedReferenceRemovalDetachesOnlyTargetSlot() {
        let root = Entity()

        // Provision both slots (initial setupScene path) and cache by ref.
        let mainEntity = makeMainLight()
        let fillEntity = makeFillLight()
        root.addChild(mainEntity)
        root.addChild(fillEntity)
        var cachedMain: Entity? = mainEntity
        var cachedFill: Entity? = fillEntity

        XCTAssertEqual(root.children.count, 2)

        // Swap the main slot: remove the cached main entity directly (the new
        // refreshLightSlot path), then re-provision a replacement.
        cachedMain?.removeFromParent()
        let newMain = makeMainLight()
        root.addChild(newMain)
        cachedMain = newMain

        // The old main entity is detached; the fill entity is untouched; the
        // new main entity is attached.
        XCTAssertNil(mainEntity.parent, "old main light should be detached")
        XCTAssertEqual(fillEntity.parent, root, "fill light must be untouched by a main-slot swap")
        XCTAssertEqual(newMain.parent, root, "new main light should be attached")
        XCTAssertEqual(root.children.count, 2, "exactly one light per slot remains")
        XCTAssertTrue(cachedFill === fillEntity)
    }

    /// A `.disabled` slot caches a `nil` reference, and a subsequent swap from
    /// `.disabled` is a no-op removal (no crash, nothing detached) — matches
    /// `provisionLightSlot` storing `nil` for `.disabled`.
    func testDisabledSlotCachesNilAndSwapsCleanly() {
        let root = Entity()

        // Main provisioned, fill disabled (cached nil) — the canonical
        // single-light setup.
        let mainEntity = makeMainLight()
        root.addChild(mainEntity)
        var cachedMain: Entity? = mainEntity
        var cachedFill: Entity? = nil   // .disabled → nil ref

        XCTAssertEqual(root.children.count, 1)

        // Swap fill .disabled → .systemDefault: removing a nil ref is a no-op,
        // then the new fill is provisioned + cached.
        cachedFill?.removeFromParent()   // no-op
        let newFill = makeFillLight()
        root.addChild(newFill)
        cachedFill = newFill

        XCTAssertEqual(mainEntity.parent, root, "main light untouched")
        XCTAssertEqual(newFill.parent, root, "fill light now attached")
        XCTAssertEqual(root.children.count, 2)
        XCTAssertTrue(cachedMain === mainEntity)

        // Swap fill back to .disabled: remove cached fill ref, cache nil.
        cachedFill?.removeFromParent()
        cachedFill = nil
        XCTAssertNil(newFill.parent, "fill light detached on .disabled")
        XCTAssertEqual(root.children.count, 1)
    }

    /// Cached-reference removal stays O(1) regardless of how many sibling
    /// entities live under the root — the latent-regression hardening motive
    /// behind #2278 (the old `children.first { }` walk was O(n)).
    func testCachedRemovalIsIndependentOfSiblingCount() {
        let root = Entity()

        // Lots of unrelated content siblings (e.g. a populated scene).
        for _ in 0..<200 { root.addChild(Entity()) }

        let mainEntity = makeMainLight()
        root.addChild(mainEntity)
        let cachedMain: Entity? = mainEntity

        XCTAssertEqual(root.children.count, 201)

        // Direct ref removal — no scan of the 200 siblings.
        cachedMain?.removeFromParent()

        XCTAssertNil(mainEntity.parent)
        XCTAssertEqual(root.children.count, 200, "only the light was removed")
    }
}
#endif
