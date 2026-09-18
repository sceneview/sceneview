import XCTest
@testable import SceneViewSwift

#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit

// Test classes run on the main actor: their RealityKit node factories
// (`LightNode.directional`, `node.entity`, …) are `@MainActor`. (#1054)
@MainActor
final class TextNodeTests: XCTestCase {

    // MARK: - Creation

    func testTextNodeCreatesEntity() {
        let node = TextNode(text: "Hello")
        XCTAssertNotNil(node.entity)
        XCTAssertNotNil(node.entity.model)
    }

    func testTextPropertyStored() {
        let node = TextNode(text: "Test String")
        XCTAssertEqual(node.text, "Test String")
    }

    func testTextWithCustomFontSize() {
        let node = TextNode(text: "Big", fontSize: 0.2)
        XCTAssertNotNil(node.entity.model)
        XCTAssertEqual(node.text, "Big")
    }

    func testTextWithCustomFont() {
        let font = MeshResource.Font.systemFont(ofSize: 0.1)
        let node = TextNode(text: "Custom Font", font: font, color: .blue)
        XCTAssertNotNil(node.entity.model)
        XCTAssertEqual(node.text, "Custom Font")
    }

    // MARK: - Transform helpers

    func testPositionSetsEntityPosition() {
        let node = TextNode(text: "Pos")
            .position(.init(x: 1, y: 2, z: -3))

        XCTAssertEqual(node.entity.position.x, 1.0, accuracy: 0.001)
        XCTAssertEqual(node.entity.position.y, 2.0, accuracy: 0.001)
        XCTAssertEqual(node.entity.position.z, -3.0, accuracy: 0.001)
    }

    func testScaleSetsUniformScale() {
        let node = TextNode(text: "Scale")
            .scale(3.0)

        XCTAssertEqual(node.entity.scale.x, 3.0, accuracy: 0.001)
        XCTAssertEqual(node.entity.scale.y, 3.0, accuracy: 0.001)
        XCTAssertEqual(node.entity.scale.z, 3.0, accuracy: 0.001)
    }

    func testPositionPropertyGetSet() {
        var node = TextNode(text: "Props")
        node.position = SIMD3<Float>(4, 5, 6)

        XCTAssertEqual(node.position.x, 4.0, accuracy: 0.001)
        XCTAssertEqual(node.position.y, 5.0, accuracy: 0.001)
        XCTAssertEqual(node.position.z, 6.0, accuracy: 0.001)
    }

    // MARK: - withText

    func testWithTextCreatesNewNodeWithUpdatedText() {
        let original = TextNode(text: "Original")
            .position(.init(x: 1, y: 2, z: 3))
            .scale(2.0)

        let updated = original.withText("Updated")

        XCTAssertEqual(updated.text, "Updated")
        // Position should be preserved
        XCTAssertEqual(updated.entity.position.x, 1.0, accuracy: 0.001)
        XCTAssertEqual(updated.entity.position.y, 2.0, accuracy: 0.001)
        XCTAssertEqual(updated.entity.position.z, 3.0, accuracy: 0.001)
    }

    // MARK: - Centering

    func testCenteredDoesNotCrash() {
        let node = TextNode(text: "Center me")
            .centered()
        XCTAssertNotNil(node.entity)
    }

    func testCenteredPutsTheTextOnItsPosition() {
        let target = SIMD3<Float>(0.5, 1.0, -2.0)
        // Both orders: the documented one used to leave the text uncentred, the
        // other one used to send it back to the origin.
        let nodes = [
            TextNode(text: "Center me", fontSize: 0.1).centered().position(target),
            TextNode(text: "Center me", fontSize: 0.1).position(target).centered(),
        ]
        for node in nodes {
            let center = node.entity.visualBounds(relativeTo: nil).center
            XCTAssertEqual(center.x, target.x, accuracy: 0.001)
            XCTAssertEqual(center.y, target.y, accuracy: 0.001)
            XCTAssertEqual(center.z, target.z, accuracy: 0.001)
            XCTAssertEqual(node.position, target)
        }
    }

    func testCenteredIsIdempotent() {
        let node = TextNode(text: "Center me", fontSize: 0.1).centered()
        let once = node.entity.visualBounds(relativeTo: nil)
        node.centered()
        let twice = node.entity.visualBounds(relativeTo: nil)
        XCTAssertEqual(twice.center.x, once.center.x, accuracy: 0.001)
        XCTAssertEqual(twice.extents.x, once.extents.x, accuracy: 0.001)
    }

    // MARK: - Chaining

    func testChainingTransforms() {
        let node = TextNode(text: "Chain", fontSize: 0.08, color: .red)
            .position(.init(x: 0, y: 1, z: -2))
            .scale(0.5)

        XCTAssertEqual(node.position.y, 1.0, accuracy: 0.001)
        XCTAssertEqual(node.scale.x, 0.5, accuracy: 0.001)
    }

    // MARK: - Scale property get/set

    func testScalePropertyGetSet() {
        var node = TextNode(text: "Scale")
        node.scale = SIMD3<Float>(2, 3, 4)
        XCTAssertEqual(node.scale.x, 2.0, accuracy: 0.001)
        XCTAssertEqual(node.scale.y, 3.0, accuracy: 0.001)
        XCTAssertEqual(node.scale.z, 4.0, accuracy: 0.001)
    }

    // MARK: - Depth parameter

    func testTextWithCustomDepth() {
        let node = TextNode(text: "Deep", fontSize: 0.1, depth: 0.05)
        XCTAssertNotNil(node.entity.model)
    }

    func testTextWithZeroDepth() {
        let node = TextNode(text: "Flat", fontSize: 0.1, depth: 0.0)
        XCTAssertNotNil(node.entity.model)
    }

    // MARK: - Alignment parameter

    func testTextWithLeftAlignment() {
        let node = TextNode(text: "Left", alignment: .left)
        XCTAssertNotNil(node.entity.model)
        XCTAssertEqual(node.text, "Left")
    }

    func testTextWithRightAlignment() {
        let node = TextNode(text: "Right", alignment: .right)
        XCTAssertNotNil(node.entity.model)
    }

    // MARK: - Collision

    func testTextNodeHasCollisionComponent() {
        let node = TextNode(text: "Collision")
        XCTAssertNotNil(node.entity.components[CollisionComponent.self])
    }

    func testTextNodeCustomFontHasCollisionComponent() {
        let font = MeshResource.Font.systemFont(ofSize: 0.08)
        let node = TextNode(text: "Font", font: font)
        XCTAssertNotNil(node.entity.components[CollisionComponent.self])
    }

    // MARK: - withText preserves scale

    func testWithTextPreservesScale() {
        let original = TextNode(text: "A")
            .scale(3.0)
        let updated = original.withText("B")
        XCTAssertEqual(updated.scale.x, 3.0, accuracy: 0.001)
    }
}
#endif
