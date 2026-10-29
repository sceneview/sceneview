import XCTest
import RealityKit
@testable import SceneViewSwift

/// `PlacementSize` math, `ModelNode.worldSize` and `worldBoundingBox`, on generated meshes —
/// no camera needed. Kotlin twins: `PlacementSizeTest`, `ModelWorldBoundsTest`.
@MainActor
final class PlacementSizeTests: XCTestCase {

    private func box(_ size: SIMD3<Float>) -> ModelEntity {
        ModelEntity(mesh: .generateBox(width: size.x, height: size.y, depth: size.z),
                    materials: [SimpleMaterial()])
    }

    private func assertEqual(_ a: SIMD3<Float>?, _ b: SIMD3<Float>, accuracy: Float = 0.0001,
                             file: StaticString = #filePath, line: UInt = #line) {
        guard let a else { return XCTFail("nil size", file: file, line: line) }
        XCTAssertEqual(a.x, b.x, accuracy: accuracy, file: file, line: line)
        XCTAssertEqual(a.y, b.y, accuracy: accuracy, file: file, line: line)
        XCTAssertEqual(a.z, b.z, accuracy: accuracy, file: file, line: line)
    }

    // MARK: PlacementSize

    func testRealWorldNeverRescales() {
        XCTAssertEqual(PlacementSize.realWorld.scaleFactor(longestSide: 0.07), 1)
        XCTAssertEqual(PlacementSize.realWorld.scaleFactor(longestSide: 25.6), 1)
    }

    func testFitIntoBringsTheLongestSideToTheTarget() throws {
        // A fox-sized asset authored in centimetres: 140 units fitted into 0.3 m.
        let factor = try XCTUnwrap(PlacementSize.fitInto(meters: 0.3).scaleFactor(longestSide: 140))
        XCTAssertEqual(140 * factor, 0.3, accuracy: 0.00001)
        XCTAssertEqual(PlacementSize.preview, .fitInto(meters: 0.3))
    }

    func testInvalidSizesAndExtentsHaveNoFactor() {
        for meters: Float in [0, -1, .nan, .infinity] {
            XCTAssertFalse(PlacementSize.fitInto(meters: meters).isValid)
            XCTAssertNil(PlacementSize.fitInto(meters: meters).scaleFactor(longestSide: 1))
        }
        for longest: Float in [0, -1, .nan, .infinity] {
            XCTAssertNil(PlacementSize.realWorld.scaleFactor(longestSide: longest))
            XCTAssertNil(PlacementSize.fitInto(meters: 1).scaleFactor(longestSide: longest))
        }
        XCTAssertTrue(PlacementSize.realWorld.isValid)
    }

    func testPreviewSizeSpellingMapsOntoPlacementSize() {
        XCTAssertEqual(PlacementSize(previewSize: nil), .realWorld)
        XCTAssertEqual(PlacementSize(previewSize: 0.3), .fitInto(meters: 0.3))
    }

    // MARK: ModelNode world size

    func testOneMetreAssetIsOneMetre() {
        let node = ModelNode(box([1, 1, 1]))
        assertEqual(node.worldSize, [1, 1, 1])
    }

    func testScaleToUnitsAndParentScaleCount() {
        let node = ModelNode(box([1, 1, 1]))
        node.scaleToUnits(0.3)
        assertEqual(node.worldSize, [0.3, 0.3, 0.3])

        let parent = Entity()
        parent.scale = [2, 2, 2]
        let child = ModelNode(box([1, 1, 1]))
        parent.addChild(child.entity)
        assertEqual(child.worldSize, [2, 2, 2])
    }

    func testWorldBoundingBoxFollowsRotationAndPositionWhileWorldSizeDoesNot() {
        let node = ModelNode(box([2, 1, 0.5]))
        node.entity.orientation = simd_quatf(angle: .pi / 2, axis: [0, 0, 1])
        node.entity.position = [1, 2, 3]
        assertEqual(node.worldSize, [2, 1, 0.5])
        let bounds = node.worldBoundingBox
        assertEqual(bounds.extents, [1, 2, 0.5])
        assertEqual(bounds.center, [1, 2, 3])
        XCTAssertEqual(bounds.min.y, 1, accuracy: 0.0001)
    }

    #if os(iOS)
    // MARK: Placement pivot and base size

    func testRealWorldKeepsAuthoredMetresWithTheBottomCentreOnThePivot() throws {
        let model = box([1, 1, 1])
        model.position = [3, 5, -2]
        let wrapper = try XCTUnwrap(ARPlacementController.prepare(model, alignment: .horizontal, size: .realWorld))
        let bounds = wrapper.visualBounds(relativeTo: wrapper)
        assertEqual(bounds.extents, [1, 1, 1])
        XCTAssertEqual(bounds.min.y, 0, accuracy: 0.0001)
        XCTAssertEqual(bounds.center.x, 0, accuracy: 0.0001)
        XCTAssertEqual(bounds.center.z, 0, accuracy: 0.0001)
        assertEqual(ARPlacementController.baseSize(of: wrapper), [1, 1, 1])
    }

    func testFitIntoPublishesTheFittedBaseSize() {
        let controller = ARPlacementController()
        XCTAssertNil(controller.baseSize)
        XCTAssertNil(controller.placementSize)
        let ticket = controller.selectModel()
        XCTAssertTrue(controller.setModel(box([2, 1, 0.5]), ticket: ticket, size: .fitInto(meters: 0.3)))
        assertEqual(controller.baseSize, [0.3, 0.15, 0.075])
        assertEqual(controller.placedSize, [0.3, 0.15, 0.075])
        XCTAssertEqual(controller.placementSize, .fitInto(meters: 0.3))
        XCTAssertFalse(controller.isAtRealWorldSize, "a preview is never the true size")
    }

    func testRealWorldFigureReportsItsHeightAndTrueSize() {
        let controller = ARPlacementController()
        let ticket = controller.selectModel()
        XCTAssertTrue(controller.setModel(box([0.5, 1.8, 0.3]), ticket: ticket, size: .realWorld))
        assertEqual(controller.baseSize, [0.5, 1.8, 0.3])
        XCTAssertTrue(controller.isAtRealWorldSize)
        controller.dismiss()
        XCTAssertNil(controller.baseSize)
        XCTAssertNil(controller.placementSize)
        XCTAssertFalse(controller.isAtRealWorldSize)
    }

    func testInvalidSizeKeepsThePreviousModelAndBaseSize() {
        let controller = ARPlacementController()
        let ticket = controller.selectModel()
        XCTAssertTrue(controller.setModel(box([1, 1, 1]), ticket: ticket, size: .realWorld))
        for meters: Float in [0, -1, .nan, .infinity] {
            let replacement = box([2, 2, 2])
            XCTAssertFalse(controller.setModel(replacement, ticket: ticket, size: .fitInto(meters: meters)))
            XCTAssertNil(replacement.parent)
        }
        assertEqual(controller.baseSize, [1, 1, 1])
        XCTAssertEqual(controller.placementSize, .realWorld)
    }

    func testPlacedSizeFollowsTheScale() {
        let base = SIMD3<Float>(0.5, 1, 0.25)
        assertEqual(ARPlacementController.placedSize(base: base, scale: 1.8), [0.9, 1.8, 0.45])
        XCTAssertNil(ARPlacementController.placedSize(base: nil, scale: 2))
    }

    func testIsAtRealWorldSizeTruthTable() {
        let base = SIMD3<Float>(1, 1, 1)
        XCTAssertTrue(ARPlacementController.isAtRealWorldSize(size: .realWorld, base: base, scale: 1))
        XCTAssertFalse(ARPlacementController.isAtRealWorldSize(size: .realWorld, base: base, scale: 1.8))
        XCTAssertFalse(ARPlacementController.isAtRealWorldSize(size: .fitInto(meters: 1), base: base, scale: 1))
        XCTAssertFalse(ARPlacementController.isAtRealWorldSize(size: .realWorld, base: nil, scale: 1))
        XCTAssertFalse(ARPlacementController.isAtRealWorldSize(size: nil, base: base, scale: 1))
    }
    #endif
}
