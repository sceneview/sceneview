#if os(iOS) || os(macOS) || os(visionOS)
import XCTest
import simd
import RealityKit
@testable import SceneViewSwift

// Test classes run on the main actor: their RealityKit node factories
// (`LightNode.directional`, `node.entity`, …) are `@MainActor`. (#1054)
@MainActor
final class ShapeNodeTests: XCTestCase {

    // MARK: - Basic creation

    func testTriangleCreation() {
        let shape = ShapeNode(
            points: [
                SIMD2<Float>(0, 0.5),
                SIMD2<Float>(-0.5, -0.5),
                SIMD2<Float>(0.5, -0.5)
            ],
            color: .red
        )
        XCTAssertNotNil(shape.entity)
        XCTAssertEqual(shape.points.count, 3)
    }

    func testSquareCreation() {
        let shape = ShapeNode(
            points: [
                SIMD2<Float>(-0.5, -0.5),
                SIMD2<Float>(0.5, -0.5),
                SIMD2<Float>(0.5, 0.5),
                SIMD2<Float>(-0.5, 0.5)
            ]
        )
        XCTAssertNotNil(shape.entity)
        XCTAssertEqual(shape.points.count, 4)
    }

    func testExtrudedShape() {
        let shape = ShapeNode(
            points: [
                SIMD2<Float>(0, 0),
                SIMD2<Float>(1, 0),
                SIMD2<Float>(0.5, 1)
            ],
            extrusionDepth: 0.2,
            color: .blue
        )
        XCTAssertNotNil(shape.entity)
    }

    func testDegeneratePolygon_LessThan3Points() {
        let shape = ShapeNode(
            points: [SIMD2<Float>(0, 0), SIMD2<Float>(1, 0)]
        )
        // Should not crash, produces a minimal entity
        XCTAssertNotNil(shape.entity)
    }

    func testEmptyPoints() {
        let shape = ShapeNode(points: [])
        XCTAssertNotNil(shape.entity)
    }

    // MARK: - Presets

    func testRegularPolygon_Hexagon() {
        let hex = ShapeNode.regularPolygon(sides: 6, radius: 0.5)
        XCTAssertEqual(hex.points.count, 6)
    }

    func testRegularPolygon_Triangle() {
        let tri = ShapeNode.regularPolygon(sides: 3, radius: 1.0, color: .green)
        XCTAssertEqual(tri.points.count, 3)
    }

    func testRegularPolygon_MinimumSides() {
        // Requesting 2 sides should clamp to 3
        let shape = ShapeNode.regularPolygon(sides: 2, radius: 0.5)
        XCTAssertEqual(shape.points.count, 3)
    }

    func testStar() {
        let star = ShapeNode.star(pointCount: 5, outerRadius: 0.5, innerRadius: 0.2)
        XCTAssertEqual(star.points.count, 10) // 5 outer + 5 inner
    }

    func testExtrudedStar() {
        let star = ShapeNode.star(
            pointCount: 6,
            outerRadius: 0.4,
            innerRadius: 0.15,
            extrusionDepth: 0.1,
            color: .yellow
        )
        XCTAssertNotNil(star.entity)
    }

    // MARK: - Transform helpers

    func testPosition() {
        let shape = ShapeNode.regularPolygon(sides: 4, radius: 0.3)
            .position(SIMD3<Float>(1, 2, 3))
        XCTAssertEqual(shape.entity.position.x, 1, accuracy: 0.001)
        XCTAssertEqual(shape.entity.position.y, 2, accuracy: 0.001)
        XCTAssertEqual(shape.entity.position.z, 3, accuracy: 0.001)
    }

    func testScaleUniform() {
        let shape = ShapeNode.regularPolygon(sides: 5, radius: 0.5)
            .scale(2.0)
        XCTAssertEqual(shape.entity.scale.x, 2.0, accuracy: 0.001)
        XCTAssertEqual(shape.entity.scale.y, 2.0, accuracy: 0.001)
        XCTAssertEqual(shape.entity.scale.z, 2.0, accuracy: 0.001)
    }

    func testScalePerAxis() {
        let shape = ShapeNode.regularPolygon(sides: 4, radius: 0.5)
            .scale(SIMD3<Float>(1, 2, 3))
        XCTAssertEqual(shape.entity.scale.x, 1, accuracy: 0.001)
        XCTAssertEqual(shape.entity.scale.y, 2, accuracy: 0.001)
        XCTAssertEqual(shape.entity.scale.z, 3, accuracy: 0.001)
    }

    func testRotation() {
        let shape = ShapeNode.regularPolygon(sides: 4, radius: 0.5)
            .rotation(angle: .pi / 4, axis: SIMD3<Float>(0, 1, 0))
        XCTAssertNotNil(shape.entity.orientation)
    }

    func testRotationQuaternion() {
        let q = simd_quatf(angle: .pi / 2, axis: SIMD3<Float>(0, 0, 1))
        let shape = ShapeNode.regularPolygon(sides: 3, radius: 0.5)
            .rotation(q)
        XCTAssertEqual(shape.entity.orientation.angle, q.angle, accuracy: 0.001)
    }

    // MARK: - Material

    func testGeometryMaterial() {
        let shape = ShapeNode(
            points: [
                SIMD2<Float>(0, 0),
                SIMD2<Float>(1, 0),
                SIMD2<Float>(0.5, 1)
            ],
            material: .pbr(color: .gray, metallic: 1.0, roughness: 0.2)
        )
        XCTAssertNotNil(shape.entity)
    }

    // MARK: - EntityProvider

    func testEntityProvider() {
        let shape = ShapeNode.regularPolygon(sides: 6, radius: 0.3)
        XCTAssertNotNil(shape.sceneEntity)
        XCTAssert(shape.sceneEntity === shape.entity)
    }

    // MARK: - `unlit:` parameter (#1359)

    private static let triangle: [SIMD2<Float>] = [
        SIMD2<Float>(0, 0.5),
        SIMD2<Float>(-0.5, -0.5),
        SIMD2<Float>(0.5, -0.5)
    ]

    /// `unlit: true` must produce an `UnlitMaterial` — a flat fill that does
    /// not respond to image-based lighting — not a lit `SimpleMaterial`.
    func testUnlitParameterYieldsUnlitMaterial() {
        let shape = ShapeNode(points: Self.triangle, color: .red, unlit: true)
        let material = shape.entity.model?.materials.first
        XCTAssertNotNil(material)
        XCTAssertTrue(
            material is UnlitMaterial,
            "unlit: true must yield an UnlitMaterial (no lighting response), got \(type(of: material))"
        )
        XCTAssertFalse(material is SimpleMaterial, "unlit material must not be a lit SimpleMaterial")
    }

    /// The `unlit:` contract holds for extruded shapes too.
    func testExtrudedUnlitParameterYieldsUnlitMaterial() {
        let shape = ShapeNode(
            points: Self.triangle,
            extrusionDepth: 0.1,
            color: .red,
            unlit: true
        )
        XCTAssertTrue(shape.entity.model?.materials.first is UnlitMaterial)
    }

    /// The default (`unlit: false`) path stays physically-based.
    func testDefaultParameterYieldsPBRMaterial() {
        let shape = ShapeNode(points: Self.triangle, color: .red)
        XCTAssertTrue(
            shape.entity.model?.materials.first is PhysicallyBasedMaterial,
            "default material must be physically-based"
        )
    }

    // MARK: - Concave polygon

    func testConcavePolygon_LShape() {
        let shape = ShapeNode(
            points: [
                SIMD2<Float>(0, 0),
                SIMD2<Float>(0.5, 0),
                SIMD2<Float>(0.5, 0.2),
                SIMD2<Float>(0.2, 0.2),
                SIMD2<Float>(0.2, 0.8),
                SIMD2<Float>(0, 0.8)
            ],
            color: .blue
        )
        XCTAssertNotNil(shape.entity)
        XCTAssertEqual(shape.points.count, 6)
    }

    // MARK: - Triangulation correctness (#2354)

    /// A 5-point star with a concave (reflex) inner ring — the default preset
    /// of `ShapeExtrudeDemo`.
    private static let star: [SIMD2<Float>] = {
        var pts: [SIMD2<Float>] = []
        let n = 5
        let outerR: Float = 0.5
        let innerR: Float = 0.22
        for i in 0..<(n * 2) {
            let angle = Float(i) / Float(n * 2) * 2 * .pi - .pi / 2
            let r: Float = i % 2 == 0 ? outerR : innerR
            pts.append(.init(r * cos(angle), r * sin(angle)))
        }
        return pts
    }()

    private static let lShape: [SIMD2<Float>] = [
        .init(-0.4, -0.4),
        .init(0.1, -0.4),
        .init(0.1, 0.0),
        .init(-0.1, 0.0),
        .init(-0.1, 0.4),
        .init(-0.4, 0.4),
    ]

    private static let arrow: [SIMD2<Float>] = [
        .init(-0.35, -0.1),
        .init(0.05, -0.1),
        .init(0.05, -0.3),
        .init(0.4,  0.0),
        .init(0.05, 0.3),
        .init(0.05, 0.1),
        .init(-0.35, 0.1),
    ]

    /// Sum of the unsigned areas of every triangle emitted by the triangulator.
    private func triangulatedArea(_ points: [SIMD2<Float>]) -> Float {
        let indices = ShapeNode.earClipTriangulate(points)
        var total: Float = 0
        for t in stride(from: 0, to: indices.count, by: 3) {
            let a = points[Int(indices[t])]
            let b = points[Int(indices[t + 1])]
            let c = points[Int(indices[t + 2])]
            total += abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) / 2
        }
        return total
    }

    /// The ear-clipping triangulator handles concave (reflex-vertex) polygons:
    /// each preset must triangulate to exactly `n - 2` triangles whose combined
    /// area equals the polygon's own area (deviation ≈ 0).
    ///
    /// Guards the concave case #2354 worried about — even though the existing
    /// triangulator already handles it (the real bug was the build plane).
    func testConcavePresetsTriangulateWithoutAreaLoss() {
        for (name, points) in [("star", Self.star), ("lShape", Self.lShape), ("arrow", Self.arrow)] {
            let indices = ShapeNode.earClipTriangulate(points)
            XCTAssertEqual(
                indices.count, (points.count - 2) * 3,
                "\(name): expected \(points.count - 2) triangles, got \(indices.count / 3)"
            )

            let polygonArea = abs(ShapeNode.signedArea(points))
            let triArea = triangulatedArea(points)
            XCTAssertEqual(
                triArea, polygonArea, accuracy: 1e-4,
                "\(name): triangulated area \(triArea) deviates from polygon area \(polygonArea)"
            )
        }
    }

    // MARK: - Vertex layout — XY plane facing +Z (#2354)

    /// The flat mesh lives in the XY plane (Z = 0) with +Z normals — mirroring
    /// SceneView Android's `ShapeGeometry`, NOT the old XZ (horizontal) plane
    /// that rendered the star edge-on as a thin ribbon.
    func testFlatMeshLivesInXYPlaneFacingCamera() throws {
        let shape = ShapeNode(points: Self.star, color: .yellow)
        let mesh = try XCTUnwrap(shape.entity.model?.mesh)
        let model = try XCTUnwrap(mesh.contents.models.first)

        var sawVertex = false
        for part in model.parts {
            let positions = part.positions.elements
            let normals = part.normals?.elements ?? []
            XCTAssertFalse(positions.isEmpty, "flat mesh must have positions")

            for p in positions {
                sawVertex = true
                // Flat shape: every vertex sits on the XY plane (Z == 0).
                XCTAssertEqual(p.z, 0, accuracy: 1e-5, "flat vertex must have Z == 0 (XY plane)")
            }
            for n in normals {
                // Front-facing normal points at the camera (+Z), not +Y.
                XCTAssertEqual(n.z, 1, accuracy: 1e-4, "flat normal must point +Z")
                XCTAssertEqual(n.x, 0, accuracy: 1e-4)
                XCTAssertEqual(n.y, 0, accuracy: 1e-4)
            }
        }
        XCTAssertTrue(sawVertex, "expected at least one vertex in the flat mesh")
    }

    /// The extruded mesh is symmetric about Z = 0 (front at +depth/2, back at
    /// −depth/2) — extruded along the Z axis, not the Y axis.
    func testExtrudedMeshIsSymmetricAboutZ() throws {
        let depth: Float = 0.2
        let shape = ShapeNode(points: Self.star, extrusionDepth: depth, color: .yellow)
        let mesh = try XCTUnwrap(shape.entity.model?.mesh)
        let model = try XCTUnwrap(mesh.contents.models.first)

        var minZ: Float = .greatestFiniteMagnitude
        var maxZ: Float = -.greatestFiniteMagnitude
        var minY: Float = .greatestFiniteMagnitude
        var maxY: Float = -.greatestFiniteMagnitude
        for part in model.parts {
            for p in part.positions.elements {
                minZ = min(minZ, p.z); maxZ = max(maxZ, p.z)
                minY = min(minY, p.y); maxY = max(maxY, p.y)
            }
        }

        // Thickness lives on Z (front/back faces at ±depth/2).
        XCTAssertEqual(maxZ, depth / 2, accuracy: 1e-4, "front face at +depth/2 on Z")
        XCTAssertEqual(minZ, -depth / 2, accuracy: 1e-4, "back face at -depth/2 on Z")
        // The polygon spans Y (it is NOT collapsed onto a single Y line).
        XCTAssertGreaterThan(maxY - minY, 0.5, "polygon must span the Y axis, not the depth axis")
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
