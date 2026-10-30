import XCTest
@testable import SceneViewSwift

#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit

@MainActor
final class TubeNodeTests: XCTestCase {

    private static let straight: [SIMD3<Float>] = [[0, 0, 0], [0, 0.5, 0], [0, 1, 0]]

    private static func ring(_ count: Int, radius: Float = 0.5) -> [SIMD3<Float>] {
        (0..<count).map { i in
            let a = Float(i) / Float(count) * 2 * .pi
            return [cos(a) * radius, sin(2 * a) * 0.1, sin(a) * radius]
        }
    }

    // MARK: - Counts

    func testOpenCappedTubeCounts() throws {
        let geometry = try XCTUnwrap(TubeGeometry(points: Self.straight, radius: 0.1,
                                                  radialSegments: 8, closed: false, caps: true))
        // 3 rings × 9 + 2 caps × (centre + 9 rim) = 27 + 20.
        XCTAssertEqual(geometry.positions.count, 47)
        XCTAssertEqual(geometry.positions.count,
                       TubeGeometry.vertexCount(pointCount: 3, radialSegments: 8, closed: false, caps: true))
        // 2 spans × 8 quads × 2 + 2 caps × 8 fans.
        XCTAssertEqual(geometry.indices.count / 3, 48)
        XCTAssertEqual(geometry.indices.count / 3,
                       TubeGeometry.triangleCount(pointCount: 3, radialSegments: 8, closed: false, caps: true))
        XCTAssertEqual(geometry.normals.count, geometry.positions.count)
        XCTAssertEqual(geometry.uvs.count, geometry.positions.count)
        XCTAssertTrue(geometry.indices.allSatisfy { Int($0) < geometry.positions.count })
    }

    func testOpenUncappedTubeCounts() throws {
        let geometry = try XCTUnwrap(TubeGeometry(points: Self.straight, radius: 0.1,
                                                  radialSegments: 6, closed: false, caps: false))
        XCTAssertEqual(geometry.positions.count, 3 * 7)
        XCTAssertEqual(geometry.indices.count / 3, 2 * 6 * 2)
    }

    func testClosedTubeRepeatsFirstRingAndIgnoresCaps() throws {
        let points = Self.ring(16)
        let geometry = try XCTUnwrap(TubeGeometry(points: points, radius: 0.02,
                                                  radialSegments: 8, closed: true, caps: true))
        XCTAssertEqual(geometry.positions.count, 17 * 9)
        XCTAssertEqual(geometry.indices.count / 3, 16 * 8 * 2)
        XCTAssertEqual(geometry.positions.count,
                       TubeGeometry.vertexCount(pointCount: 16, radialSegments: 8, closed: true, caps: true))
    }

    func testClosedTubeDropsRepeatedEndPoint() throws {
        var points = Self.ring(12)
        points.append(points[0])
        let geometry = try XCTUnwrap(TubeGeometry(points: points, radius: 0.02,
                                                  radialSegments: 4, closed: true, caps: false))
        XCTAssertEqual(geometry.positions.count, 13 * 5)
    }

    func testClosedTwoPointPathIsDrawnOpen() throws {
        // [A, B, A] closes onto itself: two distinct points, so a capped open tube.
        let geometry = try XCTUnwrap(TubeGeometry(points: [[0, 0, 0], [0, 1, 0], [0, 0, 0]], radius: 0.1,
                                                  radialSegments: 8, closed: true, caps: true))
        XCTAssertEqual(geometry.positions.count,
                       TubeGeometry.vertexCount(pointCount: 2, radialSegments: 8, closed: false, caps: true))
        XCTAssertEqual(geometry.indices.count / 3,
                       TubeGeometry.triangleCount(pointCount: 2, radialSegments: 8, closed: false, caps: true))
    }

    func testRadialSegmentsClampToThree() throws {
        let geometry = try XCTUnwrap(TubeGeometry(points: Self.straight, radius: 0.1,
                                                  radialSegments: 1, closed: false, caps: false))
        XCTAssertEqual(geometry.positions.count, 3 * 4)
    }

    // MARK: - Shape

    func testRingsSitOnTheRadiusAroundTheCentreLine() throws {
        let geometry = try XCTUnwrap(TubeGeometry(points: Self.straight, radius: 0.1,
                                                  radialSegments: 8, closed: false, caps: false))
        for position in geometry.positions {
            XCTAssertEqual(simd_length(SIMD2(position.x, position.z)), 0.1, accuracy: 1e-5)
        }
        let ys = geometry.positions.map(\.y)
        XCTAssertEqual(ys.min() ?? -1, 0, accuracy: 1e-6)
        XCTAssertEqual(ys.max() ?? -1, 1, accuracy: 1e-6)
    }

    func testSideNormalsAreUnitOutwardAndPerpendicularToThePath() throws {
        let points = Self.ring(32)
        let geometry = try XCTUnwrap(TubeGeometry(points: points, radius: 0.03,
                                                  radialSegments: 8, closed: true, caps: false))
        let stride = 9
        for (ring, centre) in (points + [points[0]]).enumerated() {
            let next = points[(ring + 1) % points.count]
            let previous = points[(ring - 1 + points.count) % points.count]
            let tangent = simd_normalize(next - previous)
            for j in 0..<stride {
                let index = ring * stride + j
                let normal = geometry.normals[index]
                XCTAssertEqual(simd_length(normal), 1, accuracy: 1e-4)
                XCTAssertEqual(simd_dot(normal, tangent), 0, accuracy: 0.05)
                XCTAssertGreaterThan(simd_dot(normal, geometry.positions[index] - centre), 0)
            }
        }
    }

    func testClosedSeamRingMatchesTheFirstRing() throws {
        let geometry = try XCTUnwrap(TubeGeometry(points: Self.ring(24), radius: 0.03,
                                                  radialSegments: 8, closed: true, caps: false))
        let stride = 9
        let seam = 24 * stride
        for j in 0..<stride {
            XCTAssertLessThan(simd_distance(geometry.positions[j], geometry.positions[seam + j]), 1e-5)
            XCTAssertEqual(geometry.uvs[j].y, 0, accuracy: 1e-6)
            XCTAssertEqual(geometry.uvs[seam + j].y, 1, accuracy: 1e-6)
        }
    }

    func testTrianglesFaceOutward() throws {
        let geometry = try XCTUnwrap(TubeGeometry(points: Self.straight, radius: 0.1,
                                                  radialSegments: 8, closed: false, caps: true))
        let sideTriangles = 2 * 8 * 2
        for t in 0..<(geometry.indices.count / 3) {
            let a = geometry.positions[Int(geometry.indices[3 * t])]
            let b = geometry.positions[Int(geometry.indices[3 * t + 1])]
            let c = geometry.positions[Int(geometry.indices[3 * t + 2])]
            let face = simd_cross(b - a, c - a)
            let centroid = (a + b + c) / 3
            if t < sideTriangles {
                XCTAssertGreaterThan(simd_dot(face, SIMD3(centroid.x, 0, centroid.z)), 0, "side \(t)")
            } else {
                // Start cap at y = 0 faces down, end cap at y = 1 faces up.
                XCTAssertGreaterThan(simd_dot(face, [0, centroid.y < 0.5 ? -1 : 1, 0]), 0, "cap \(t)")
            }
        }
    }

    func testUnusablePathsProduceNoGeometry() {
        XCTAssertNil(TubeGeometry(points: [], radius: 0.1, radialSegments: 8, closed: false, caps: true))
        XCTAssertNil(TubeGeometry(points: [[1, 1, 1]], radius: 0.1, radialSegments: 8, closed: false, caps: true))
        XCTAssertNil(TubeGeometry(points: [[1, 1, 1], [1, 1, 1]], radius: 0.1, radialSegments: 8,
                                  closed: false, caps: true))
        XCTAssertNil(TubeGeometry(points: Self.straight, radius: 0, radialSegments: 8, closed: false, caps: true))
    }

    func testHairpinStillProducesFiniteGeometry() throws {
        let geometry = try XCTUnwrap(TubeGeometry(points: [[0, 0, 0], [1, 0, 0], [0, 0, 0]], radius: 0.05,
                                                  radialSegments: 6, closed: false, caps: true))
        XCTAssertTrue(geometry.positions.allSatisfy { $0.x.isFinite && $0.y.isFinite && $0.z.isFinite })
    }

    // MARK: - Node

    func testNodeBuildsAMeshWithTheGivenBounds() throws {
        let node = TubeNode(points: Self.straight, radius: 0.1, radialSegments: 12)
        XCTAssertTrue(node.entity.isEnabled)
        let bounds = try XCTUnwrap(node.entity.model?.mesh.bounds)
        XCTAssertEqual(bounds.min.y, 0, accuracy: 1e-4)
        XCTAssertEqual(bounds.max.y, 1, accuracy: 1e-4)
        XCTAssertEqual(bounds.max.x, 0.1, accuracy: 1e-3)
        XCTAssertEqual(node.radialSegments, 12)
    }

    func testUpdateKeepsTheEntityAndMaterial() throws {
        let node = TubeNode(points: Self.straight, radius: 0.1, material: .unlit(color: .red))
        let entity = node.entity
        node.update(points: Self.ring(20), radius: 0.02)
        XCTAssertTrue(node.entity === entity)
        XCTAssertTrue(node.entity.model?.materials.first is UnlitMaterial)
        let bounds = try XCTUnwrap(node.entity.model?.mesh.bounds)
        XCTAssertEqual(bounds.max.x, 0.52, accuracy: 1e-2)
    }

    func testTooFewPointsDisablesUntilTheNextUpdate() {
        let node = TubeNode(points: [[0, 0, 0]], radius: 0.1)
        XCTAssertFalse(node.entity.isEnabled)
        XCTAssertNil(node.entity.model)
        node.update(points: Self.straight, radius: 0.1)
        XCTAssertTrue(node.entity.isEnabled)
        XCTAssertNotNil(node.entity.model)
        node.update(points: [], radius: 0.1)
        XCTAssertFalse(node.entity.isEnabled)
    }
}
#endif
