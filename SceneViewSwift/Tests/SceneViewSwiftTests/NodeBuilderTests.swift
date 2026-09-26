import XCTest
@testable import SceneViewSwift

#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit

// Test classes run on the main actor: their RealityKit node factories
// (`LightNode.directional`, `node.entity`, …) are `@MainActor`. (#1054)
@MainActor
final class NodeBuilderTests: XCTestCase {

    // MARK: - EntityProvider conformances

    func testGeometryNodeConformsToEntityProvider() {
        let cube = GeometryNode.cube(size: 0.5, color: .red)
        XCTAssertNotNil(cube.sceneEntity)
        XCTAssertTrue(cube.sceneEntity === cube.entity)
    }

    func testModelNodeConformsToEntityProvider() {
        let node = ModelNode(ModelEntity())
        XCTAssertNotNil(node.sceneEntity)
        XCTAssertTrue(node.sceneEntity === node.entity)
    }

    func testLightNodeConformsToEntityProvider() {
        let light = LightNode.directional()
        XCTAssertNotNil(light.sceneEntity)
    }

    func testTextNodeConformsToEntityProvider() {
        let text = TextNode(text: "Hello")
        XCTAssertNotNil(text.sceneEntity)
    }

    func testLineNodeConformsToEntityProvider() {
        let line = LineNode(from: .zero, to: [1, 0, 0])
        XCTAssertNotNil(line.sceneEntity)
    }

    func testMeshNodeConformsToEntityProvider() {
        let mesh = try! MeshNode.fromVertices(
            positions: [.zero, [1, 0, 0], [0, 1, 0]],
            indices: [0, 1, 2]
        )
        XCTAssertNotNil(mesh.sceneEntity)
    }

    func testBillboardNodeConformsToEntityProvider() {
        let text = TextNode(text: "Test")
        let billboard = BillboardNode(child: text.entity)
        XCTAssertNotNil(billboard.sceneEntity)
    }

    func testCameraNodeConformsToEntityProvider() {
        let camera = CameraNode()
        XCTAssertNotNil(camera.sceneEntity)
    }

    func testPhysicsNodeConformsToEntityProvider() {
        let entity = ModelEntity(
            mesh: .generateBox(size: 1.0),
            materials: [SimpleMaterial()]
        )
        let physics = PhysicsNode.dynamic(entity)
        XCTAssertNotNil(physics.sceneEntity)
    }

    func testDynamicSkyNodeConformsToEntityProvider() {
        let sky = DynamicSkyNode(timeOfDay: 12)
        XCTAssertNotNil(sky.sceneEntity)
    }

    @available(*, deprecated)
    func testFogNodeConformsToEntityProvider() {
        let fog = FogNode.linear()
        XCTAssertNotNil(fog.sceneEntity)
    }

    // MARK: - NodeBuilder

    func testNodeBuilderCreatesEntities() {
        let entities = NodeBuilder.build {
            GeometryNode.cube(size: 0.3, color: .red)
            GeometryNode.sphere(radius: 0.2, color: .blue)
        }
        XCTAssertEqual(entities.count, 2)
    }

    func testNodeBuilderEmptyBlock() {
        let entities = NodeBuilder.build {
            GeometryNode.cube(size: 0.3, color: .red)
        }
        XCTAssertEqual(entities.count, 1)
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
