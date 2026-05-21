#if os(iOS)
import XCTest
import RealityKit
@testable import SceneViewSwift

// Test classes run on the main actor: their RealityKit node factories
// (`LightNode.directional`, `node.entity`, …) are `@MainActor`. (#1054)
@MainActor
final class AugmentedImageNodeTests: XCTestCase {

    // MARK: - ReferenceImage

    func testReferenceImageInitWithCGImage() {
        // Create a 1x1 pixel CGImage for testing
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: nil, width: 1, height: 1, bitsPerComponent: 8,
            bytesPerRow: 4, space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ), let cgImage = context.makeImage() else {
            XCTFail("Could not create test CGImage")
            return
        }

        let ref = AugmentedImageNode.ReferenceImage(
            name: "test_image",
            cgImage: cgImage,
            physicalWidth: 0.3
        )

        XCTAssertEqual(ref.name, "test_image")
        XCTAssertEqual(ref.physicalWidth, 0.3)
        XCTAssertNotNil(ref.cgImage)
    }

    func testReferenceImageInitWithUIImage() throws {
        let uiImage = UIImage(systemName: "star.fill")!
        // `ReferenceImage(name:image:physicalWidth:)` is throwing since #883
        // (recoverable error when the UIImage has no cgImage representation).
        let ref = try AugmentedImageNode.ReferenceImage(
            name: "star",
            image: uiImage,
            physicalWidth: 0.15
        )

        XCTAssertEqual(ref.name, "star")
        XCTAssertEqual(ref.physicalWidth, 0.15)
        XCTAssertNotNil(ref.cgImage)
    }

    // MARK: - Image Database

    func testCreateImageDatabaseEmpty() {
        let database = AugmentedImageNode.createImageDatabase([])
        XCTAssertTrue(database.isEmpty)
    }

    func testCreateImageDatabaseMultipleImages() throws {
        let uiImage = UIImage(systemName: "star.fill")!

        let refs = [
            try AugmentedImageNode.ReferenceImage(name: "poster", image: uiImage, physicalWidth: 0.3),
            try AugmentedImageNode.ReferenceImage(name: "logo", image: uiImage, physicalWidth: 0.1),
        ]

        let database = AugmentedImageNode.createImageDatabase(refs)
        XCTAssertEqual(database.count, 2)

        let names = database.compactMap(\.name)
        XCTAssertTrue(names.contains("poster"))
        XCTAssertTrue(names.contains("logo"))
    }

    func testCreateImageDatabasePhysicalWidth() throws {
        let uiImage = UIImage(systemName: "star.fill")!
        let ref = try AugmentedImageNode.ReferenceImage(
            name: "card",
            image: uiImage,
            physicalWidth: 0.086 // credit card width
        )

        let database = AugmentedImageNode.createImageDatabase([ref])
        guard let arImage = database.first else {
            XCTFail("Database should contain one image")
            return
        }

        XCTAssertEqual(arImage.name, "card")
        XCTAssertEqual(arImage.physicalSize.width, 0.086, accuracy: 0.001)
    }

    // MARK: - TrackingState

    func testTrackingStateEnum() {
        // Verify all cases exist and are distinct
        let states: [AugmentedImageNode.TrackingState] = [.tracking, .limited, .notTracking]
        XCTAssertEqual(states.count, 3)
        XCTAssertNotEqual(AugmentedImageNode.TrackingState.tracking, .notTracking)
        XCTAssertNotEqual(AugmentedImageNode.TrackingState.tracking, .limited)
    }

    // MARK: - Child Management

    func testAddAndRemoveChildren() {
        // We can't create a real ARImageAnchor in tests, so test the
        // struct's child management via a manually constructed instance
        let anchor = AnchorEntity()
        let node = AugmentedImageNode(
            imageName: "test",
            estimatedSize: CGSize(width: 0.3, height: 0.2),
            anchorEntity: anchor
        )

        XCTAssertEqual(node.imageName, "test")
        XCTAssertEqual(node.estimatedSize.width, 0.3)
        XCTAssertEqual(node.estimatedSize.height, 0.2)

        // Add children
        let child1 = ModelEntity()
        let child2 = ModelEntity()
        node.add(child1)
        node.add(child2)
        XCTAssertEqual(anchor.children.count, 2)

        // Remove all
        node.removeAll()
        XCTAssertEqual(anchor.children.count, 0)
    }

    // MARK: - referenceImages(inGroupNamed:)

    func testReferenceImagesInGroupNamedReturnsNilForBadGroup() {
        // Non-existent group should return nil
        let result = AugmentedImageNode.referenceImages(inGroupNamed: "nonexistent_group_12345")
        XCTAssertNil(result)
    }
}
#endif // os(iOS)
