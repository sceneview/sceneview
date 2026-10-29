import XCTest
@testable import SceneViewSwift

#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit

@MainActor
final class WireframeTests: XCTestCase {

    /// A unit quad split into two triangles along its 0–2 diagonal.
    private static let quad: [SIMD3<Float>] = [[0, 0, 0], [1, 0, 0], [1, 1, 0], [0, 1, 0]]
    private static let quadTriangles: [UInt32] = [0, 1, 2, 0, 2, 3]

    private static func triangleCount(of node: MeshNode) throws -> Int {
        let mesh = try XCTUnwrap(node.entity.model?.mesh)
        var indices = 0
        for model in mesh.contents.models {
            for part in model.parts { indices += part.triangleIndices?.count ?? 0 }
        }
        return indices / 3
    }

    // MARK: - Edges

    func testSharedEdgeIsListedOnce() {
        let edges = WireframeGeometry.edges(triangleIndices: Self.quadTriangles, positions: Self.quad,
                                            hideQuadDiagonals: false)
        XCTAssertEqual(edges.count, 5)
        XCTAssertEqual(Set(edges).count, 5)
        XCTAssertTrue(edges.contains(WireframeGeometry.Edge(2, 0)))
    }

    func testQuadDiagonalIsHidden() {
        let edges = WireframeGeometry.edges(triangleIndices: Self.quadTriangles, positions: Self.quad,
                                            hideQuadDiagonals: true)
        XCTAssertEqual(Set(edges), [.init(0, 1), .init(1, 2), .init(2, 3), .init(3, 0)])
    }

    func testCreaseIsNeverTreatedAsADiagonal() {
        // The same two triangles folded 90° along the diagonal: two faces, not one quad.
        let folded: [SIMD3<Float>] = [[0, 0, 0], [1, 0, 0], [1, 1, 0], [0.5, 0.5, 0.7]]
        let edges = WireframeGeometry.edges(triangleIndices: Self.quadTriangles, positions: folded,
                                            hideQuadDiagonals: true)
        XCTAssertTrue(edges.contains(.init(0, 2)))
    }

    func testDegenerateTrianglesAreIgnored() {
        let edges = WireframeGeometry.edges(triangleIndices: [0, 1, 1, 0, 1, 2],
                                            positions: Self.quad, hideQuadDiagonals: false)
        XCTAssertEqual(edges.count, 3)
    }

    func testWeldMergesCoincidentVertices() {
        let welded = WireframeGeometry.weld([[0, 0, 0], [1, 0, 0], [0, 0, 0], [1, 0, 0.000001]])
        XCTAssertEqual(welded.positions.count, 2)
        XCTAssertEqual(welded.remap, [0, 1, 0, 1])
    }

    // MARK: - Prisms

    func testEachEdgeIsAFourSidedPrism() {
        let geometry = WireframeGeometry(positions: Self.quad,
                                         edges: [.init(0, 1), .init(1, 2)], thickness: 0.01)
        XCTAssertEqual(geometry.positions.count, 2 * 2 * WireframeGeometry.sides)
        XCTAssertEqual(geometry.indices.count / 3, 2 * 2 * WireframeGeometry.sides)
        XCTAssertEqual(geometry.normals.count, geometry.positions.count)
        for (position, normal) in zip(geometry.positions.prefix(8), geometry.normals.prefix(8)) {
            // Edge 0–1 runs along X: every vertex is 5 mm off the axis, normal pointing away.
            XCTAssertEqual(simd_length(SIMD2(position.y, position.z)), 0.005, accuracy: 1e-6)
            XCTAssertEqual(simd_length(normal), 1, accuracy: 1e-5)
            XCTAssertEqual(normal.x, 0, accuracy: 1e-6)
        }
    }

    func testPrismTrianglesFaceOutward() {
        let geometry = WireframeGeometry(positions: Self.quad, edges: [.init(0, 1)], thickness: 0.02)
        for t in 0..<(geometry.indices.count / 3) {
            let a = geometry.positions[Int(geometry.indices[3 * t])]
            let b = geometry.positions[Int(geometry.indices[3 * t + 1])]
            let c = geometry.positions[Int(geometry.indices[3 * t + 2])]
            let centroid = (a + b + c) / 3
            XCTAssertGreaterThan(simd_dot(simd_cross(b - a, c - a), [0, centroid.y, centroid.z]), 0)
        }
    }

    func testZeroLengthAndOutOfRangeEdgesAreSkipped() {
        let geometry = WireframeGeometry(positions: [[0, 0, 0], [0, 0, 0]],
                                         edges: [.init(0, 1), .init(0, 9)], thickness: 0.01)
        XCTAssertTrue(geometry.positions.isEmpty)
        XCTAssertTrue(geometry.indices.isEmpty)
    }

    // MARK: - MeshNode factories

    func testCubeWireframeShowsItsTwelveEdges() throws {
        let cube = MeshResource.generateBox(size: 0.2)
        let wire = try MeshNode.wireframe(of: cube, thickness: 0.002)
        let triangles = try Self.triangleCount(of: wire)
        XCTAssertEqual(triangles, 12 * 2 * WireframeGeometry.sides)
        let bounds = try XCTUnwrap(wire.entity.model?.mesh.bounds)
        XCTAssertEqual(bounds.max.x, 0.101, accuracy: 1e-3)
        XCTAssertEqual(bounds.min.y, -0.101, accuracy: 1e-3)
    }

    func testTorusWireframeKeepsItsRingsAndDropsDiagonals() throws {
        let torus = GeometryNode.torus(majorRadius: 0.2, minorRadius: 0.05,
                                       majorSegments: 12, minorSegments: 6)
        let mesh = try XCTUnwrap(torus.entity.model?.mesh)
        let wire = try MeshNode.wireframe(of: mesh, thickness: 0.002, material: .unlit(color: .white))
        let triangles = try Self.triangleCount(of: wire)
        // A 12 × 6 grid on a torus has 12 × 6 edges each way once its seams are welded.
        XCTAssertEqual(triangles / (2 * WireframeGeometry.sides), 2 * 12 * 6)
        XCTAssertTrue(wire.entity.model?.materials.first is UnlitMaterial)
    }

    func testLineIndicesDrawExactlyThoseEdges() throws {
        let wire = try MeshNode.wireframe(positions: Self.quad, lineIndices: [0, 1, 1, 2, 2, 3],
                                          thickness: 0.004)
        let triangles = try Self.triangleCount(of: wire)
        XCTAssertEqual(triangles, 3 * 2 * WireframeGeometry.sides)
    }

    func testAMeshWithoutEdgesThrows() {
        XCTAssertThrowsError(try MeshNode.wireframe(positions: [], triangleIndices: []))
    }
}
#endif
