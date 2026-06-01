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

    /// A plain symmetric triangle for the material/creation tests. Deliberately
    /// NOT `ShapePresets.triangle` (whose base sits at Y = -0.4, not -0.5) — the
    /// material tests don't care about the gallery silhouette, so this stays a
    /// local fixture rather than coupling them to a demo preset.
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
    //
    // The concave fixtures (`star`, `lShape`, `arrow`) are NOT re-declared here.
    // They come straight from ``ShapePresets`` — the single source of truth
    // shared with `ShapeExtrudeDemo` — so these tests always guard the exact
    // geometry the demo renders. Retuning a preset (e.g. the star's
    // inner/outer radius) updates the demo and this test together; the test
    // can no longer silently drift from the shipped shape.

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
        for (name, points) in [("star", ShapePresets.star), ("lShape", ShapePresets.lShape), ("arrow", ShapePresets.arrow)] {
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

    // MARK: - Preset single-source-of-truth (#2354 follow-up)

    /// Pins the canonical geometry of the shared ``ShapePresets/star`` so a
    /// careless retune of the single source (or of ``ShapeNode/starPoints``)
    /// is caught here instead of silently reshaping the shipped demo: ten
    /// vertices, alternating outer-0.5 / inner-0.22 radii, first point toward
    /// -Y, wound counter-clockwise. This is the explicit guard the dedup is for
    /// — the demo and this assertion now read the very same `ShapePresets.star`.
    func testStarPresetHasCanonicalGeometry() {
        let star = ShapePresets.star
        XCTAssertEqual(star.count, 10, "5-point star must have 10 vertices (outer + inner)")

        for (i, p) in star.enumerated() {
            let r = (p.x * p.x + p.y * p.y).squareRoot()
            let expected: Float = i % 2 == 0 ? 0.5 : 0.22
            XCTAssertEqual(
                r, expected, accuracy: 1e-4,
                "vertex \(i) radius \(r) must be \(expected) (even = outer, odd = inner)"
            )
        }

        // First vertex sits at angle -.pi / 2 -> straight down (-Y).
        XCTAssertEqual(star[0].x, 0, accuracy: 1e-4)
        XCTAssertEqual(star[0].y, -0.5, accuracy: 1e-4)

        // CCW winding (positive signed area) — what the mesh builder expects.
        XCTAssertGreaterThan(ShapeNode.signedArea(star), 0, "star preset must be CCW")
    }

    // MARK: - Vertex layout — XY plane facing +Z (#2354)

    /// The flat mesh lives in the XY plane (Z = 0) with +Z normals — mirroring
    /// SceneView Android's `ShapeGeometry`, NOT the old XZ (horizontal) plane
    /// that rendered the star edge-on as a thin ribbon.
    func testFlatMeshLivesInXYPlaneFacingCamera() throws {
        let shape = ShapeNode(points: ShapePresets.star, color: .yellow)
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
                XCTAssertEqual(p.z, 0, accuracy: 1e-4, "flat vertex must have Z == 0 (XY plane)")
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
        let shape = ShapeNode(points: ShapePresets.star, extrusionDepth: depth, color: .yellow)
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

    // MARK: - Triangle WINDING + outward normals (#2354 review — MAJOR 1/2)
    //
    // RealityKit single-sided materials back-face-cull by INDEX WINDING, not by
    // the normal attribute. The earlier tests assert positions / normals but
    // never the winding, so a winding regression (the exact class of bug the
    // PR's normal-only flip introduced for CW input) would pass CI undetected.
    // These read the *generated* index buffer back and assert the winding +
    // outward invariants directly, including a clockwise-input fixture that
    // exercises the formerly-broken `!ccw` path.

    /// Reads the first generated model's parts as `(positions, normals, indices)`.
    private func meshBuffers(
        _ shape: ShapeNode,
        line: UInt = #line
    ) throws -> [(positions: [SIMD3<Float>], normals: [SIMD3<Float>], indices: [UInt32])] {
        let mesh = try XCTUnwrap(shape.entity.model?.mesh, line: line)
        let model = try XCTUnwrap(mesh.contents.models.first, line: line)
        return model.parts.map { part in
            (part.positions.elements,
             part.normals?.elements ?? [],
             part.triangleIndices?.elements ?? [])
        }
    }

    /// Signed area of triangle (a, b, c) in the XY plane.
    /// > 0 ⟺ counter-clockwise viewed from +Z (front-facing for RealityKit).
    private func cross2D(_ a: SIMD3<Float>, _ b: SIMD3<Float>, _ c: SIMD3<Float>) -> Float {
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    }

    /// XY centroid of every vertex in the mesh. For a symmetric extrusion (and
    /// for the flat cap) this equals the polygon centroid — a winding-agnostic
    /// reference for "outward".
    private func xyCentroid(_ buffers: [(positions: [SIMD3<Float>], normals: [SIMD3<Float>], indices: [UInt32])]) -> SIMD2<Float> {
        var sum = SIMD2<Float>(0, 0)
        var count = 0
        for b in buffers {
            for p in b.positions { sum += SIMD2<Float>(p.x, p.y); count += 1 }
        }
        return count > 0 ? sum / Float(count) : .zero
    }

    /// Every FRONT-cap triangle (all three vertices on the +Z front face) must
    /// be wound CCW when viewed from +Z, so a single-sided material shows the
    /// cap to the camera instead of culling it.
    func testFrontCapTrianglesAreCCWFromPlusZ() throws {
        let depth: Float = 0.2
        let shape = ShapeNode(points: ShapePresets.star, extrusionDepth: depth, color: .yellow)
        let buffers = try meshBuffers(shape)

        var sawFrontTriangle = false
        for buf in buffers {
            let p = buf.positions
            let idx = buf.indices
            XCTAssertFalse(idx.isEmpty, "expected a non-empty index buffer")
            for t in stride(from: 0, to: idx.count, by: 3) {
                let a = p[Int(idx[t])], b = p[Int(idx[t + 1])], c = p[Int(idx[t + 2])]
                // Front cap = all three vertices at +depth/2.
                guard abs(a.z - depth / 2) < 1e-4,
                      abs(b.z - depth / 2) < 1e-4,
                      abs(c.z - depth / 2) < 1e-4 else { continue }
                sawFrontTriangle = true
                XCTAssertGreaterThan(
                    cross2D(a, b, c), 0,
                    "front-cap triangle must be CCW viewed from +Z (front-facing)"
                )
            }
        }
        XCTAssertTrue(sawFrontTriangle, "expected at least one front-cap triangle")
    }

    /// The flat (non-extruded) cap must likewise be wound CCW from +Z.
    func testFlatCapTrianglesAreCCWFromPlusZ() throws {
        let shape = ShapeNode(points: ShapePresets.star, color: .yellow)
        let buffers = try meshBuffers(shape)

        var sawTriangle = false
        for buf in buffers {
            let p = buf.positions
            let idx = buf.indices
            for t in stride(from: 0, to: idx.count, by: 3) {
                let a = p[Int(idx[t])], b = p[Int(idx[t + 1])], c = p[Int(idx[t + 2])]
                sawTriangle = true
                XCTAssertGreaterThan(
                    cross2D(a, b, c), 0,
                    "flat triangle must be CCW viewed from +Z (front-facing)"
                )
            }
        }
        XCTAssertTrue(sawTriangle, "expected at least one flat triangle")
    }

    /// A solid extrusion must have a real BACK face: the distinct-normals set
    /// includes a (0, 0, -1). (Front cap, back cap, and outward side walls.)
    func testExtrudedMeshHasBackFaceNormal() throws {
        let shape = ShapeNode(points: ShapePresets.star, extrusionDepth: 0.2, color: .yellow)
        let buffers = try meshBuffers(shape)

        var allNormals: [SIMD3<Float>] = []
        for buf in buffers { allNormals.append(contentsOf: buf.normals) }
        var backFacing = false
        for n in allNormals where abs(n.x) < 1e-3 && abs(n.y) < 1e-3 && abs(n.z + 1) < 1e-3 {
            backFacing = true
            break
        }
        XCTAssertTrue(backFacing, "extruded mesh must contain a (0,0,-1) back-face normal")
    }

    /// Core MAJOR-1 invariant, asserted on the GENERATED mesh: for every
    /// side-wall triangle (one vertex on each of +Z / −Z, i.e. spanning the
    /// thickness), the geometric face normal of the triangle (from its index
    /// winding) must point OUTWARD (positive dot with edge-midpoint − centroid)
    /// AND agree with the triangle's own stored normal attribute. Run this for
    /// BOTH a CCW polygon and a CW polygon — the CW case is what the PR's
    /// normal-only flip rendered inside-out.
    private func assertSideWallsOutwardAndConsistent(
        _ points: [SIMD2<Float>],
        depth: Float,
        _ label: String,
        line: UInt = #line
    ) throws {
        let shape = ShapeNode(points: points, extrusionDepth: depth, color: .systemPink)
        let buffers = try meshBuffers(shape, line: line)
        let centroid = xyCentroid(buffers)

        var sawSideTriangle = false
        for buf in buffers {
            let p = buf.positions
            let nrm = buf.normals
            let idx = buf.indices
            for t in stride(from: 0, to: idx.count, by: 3) {
                let i0 = Int(idx[t]), i1 = Int(idx[t + 1]), i2 = Int(idx[t + 2])
                let a = p[i0], b = p[i1], c = p[i2]
                // Side wall = triangle that spans the thickness (has both a +Z
                // and a −Z vertex). Caps live entirely on one Z plane.
                let zs = [a.z, b.z, c.z]
                let hasFront = zs.contains { abs($0 - depth / 2) < 1e-4 }
                let hasBack  = zs.contains { abs($0 + depth / 2) < 1e-4 }
                guard hasFront && hasBack else { continue }
                sawSideTriangle = true

                // Geometric face normal from index winding.
                let geo = simd_normalize(simd_cross(b - a, c - a))

                // Outward reference: triangle XY centroid − polygon centroid.
                let triMidXY = SIMD2<Float>((a.x + b.x + c.x) / 3, (a.y + b.y + c.y) / 3)
                let outward = triMidXY - centroid
                let outwardLen = simd_length(outward)
                if outwardLen > 1e-5 {
                    let dotOutward = geo.x * outward.x + geo.y * outward.y
                    XCTAssertGreaterThan(
                        dotOutward, 0,
                        "\(label): side-wall face normal must point outward (winding inside-out?)",
                        line: line
                    )
                }

                // Stored normal attribute must agree with the geometric winding.
                let storedAvg = simd_normalize((nrm[i0] + nrm[i1] + nrm[i2]) / 3)
                let dotStored = simd_dot(geo, storedAvg)
                XCTAssertGreaterThan(
                    dotStored, 0.5,
                    "\(label): side-wall winding must agree with its stored normal",
                    line: line
                )
            }
        }
        XCTAssertTrue(sawSideTriangle, "\(label): expected at least one side-wall triangle", line: line)
    }

    /// CCW square — must already be correct (matches the all-CCW demo presets).
    func testSideWallsOutward_CCWSquare() throws {
        let ccwSquare: [SIMD2<Float>] = [
            .init(-0.5, -0.5), .init(0.5, -0.5), .init(0.5, 0.5), .init(-0.5, 0.5),
        ]
        XCTAssertGreaterThan(ShapeNode.signedArea(ccwSquare), 0, "fixture must be CCW")
        try assertSideWallsOutwardAndConsistent(ccwSquare, depth: 0.2, "CCW square")
    }

    /// CLOCKWISE square — `signedArea < 0`. This is the input the PR's
    /// normal-only `!ccw` flip rendered inside-out (winding said one face,
    /// the flipped normal said the other). Locks in MAJOR 1: the CW-input
    /// side walls must still face outward with a consistent normal.
    func testSideWallsOutward_CWSquare() throws {
        let cwSquare: [SIMD2<Float>] = [
            .init(-0.5, -0.5), .init(-0.5, 0.5), .init(0.5, 0.5), .init(0.5, -0.5),
        ]
        XCTAssertLessThan(ShapeNode.signedArea(cwSquare), 0, "fixture must be CW")
        try assertSideWallsOutwardAndConsistent(cwSquare, depth: 0.2, "CW square")
    }

    /// A CLOCKWISE-wound L-shape (reversed preset) — a concave CW polygon
    /// through the full ear-clipper + side-wall path, the broadest exercise of
    /// the formerly-broken branch.
    func testSideWallsOutward_CWConcaveLShape() throws {
        let cwLShape = Array(ShapePresets.lShape.reversed())
        XCTAssertLessThan(ShapeNode.signedArea(cwLShape), 0, "reversed L-shape must be CW")
        try assertSideWallsOutwardAndConsistent(cwLShape, depth: 0.15, "CW L-shape")
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
