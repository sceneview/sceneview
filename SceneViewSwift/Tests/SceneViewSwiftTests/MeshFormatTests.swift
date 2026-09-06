import XCTest
import simd
@testable import SceneViewSwift

#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
#endif

/// Format sniffing, unit arithmetic, and the ModelIO reader for STL / OBJ / PLY.
final class MeshFormatTests: XCTestCase {

    private var directory: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        directory = try MeshFixtures.makeDirectory()
    }

    override func tearDownWithError() throws {
        if let directory { try? FileManager.default.removeItem(at: directory) }
        directory = nil
        try super.tearDownWithError()
    }

    /// 10 × 20 × 30 in the file's own unit — deliberately unequal so a swapped axis
    /// cannot pass.
    private let boxSize = SIMD3<Float>(10, 20, 30)

    private func write(_ data: Data, as name: String) throws -> URL {
        let url = directory.appendingPathComponent(name)
        try data.write(to: url)
        return url
    }

    // MARK: - Units

    func testUnitConversionToMeters() {
        XCTAssertEqual(ModelUnit.millimeters.metersPerUnit, 0.001, accuracy: 1e-9)
        XCTAssertEqual(ModelUnit.inches.metersPerUnit, 0.0254, accuracy: 1e-9)
        XCTAssertEqual(ModelUnit.meters.convert(2, to: .centimeters), 200, accuracy: 1e-4)
        XCTAssertEqual(ModelUnit.inches.convert(1, to: .millimeters), 25.4, accuracy: 1e-3)
    }

    func testUnitFromThreeMFSpelling() {
        XCTAssertEqual(ModelUnit(threeMFUnit: "millimeter"), .millimeters)
        XCTAssertEqual(ModelUnit(threeMFUnit: "MICRON"), .micrometers)
        XCTAssertEqual(ModelUnit(threeMFUnit: " inch "), .inches)
        XCTAssertNil(ModelUnit(threeMFUnit: "furlong"))
    }

    func testUnitFromMetersPerUnit() {
        XCTAssertEqual(ModelUnit(metersPerUnit: 0.01), .centimeters)
        XCTAssertEqual(ModelUnit(metersPerUnit: 1), .meters)
        XCTAssertNil(ModelUnit(metersPerUnit: 0.3))
        XCTAssertNil(ModelUnit(metersPerUnit: 0))
    }

    // MARK: - Sniffing

    /// The regression this whole sniffer exists for: binary STLs whose 80-byte header
    /// starts with `solid`. Detected by size arithmetic, not by the prefix.
    func testBinarySTLWithSolidHeaderIsNotMistakenForASCII() throws {
        let data = MeshFixtures.binarySTL(triangles: MeshFixtures.boxTriangles(size: boxSize))
        XCTAssertTrue(String(data: data.prefix(5), encoding: .utf8) == "solid")
        let url = try write(data, as: "trap.stl")
        XCTAssertEqual(try ModelFormat.sniff(contentsOf: url), .stl)

        let asset = try MeshAsset.load(contentsOf: url)
        XCTAssertEqual(asset.triangleCount, 12, "an ASCII misread yields 0 triangles")
    }

    func testSniffsAsciiSTLAndPLYBySignature() throws {
        let ascii = try write(
            MeshFixtures.asciiSTL(triangles: MeshFixtures.boxTriangles(size: boxSize)),
            as: "ascii.stl"
        )
        XCTAssertEqual(try ModelFormat.sniff(contentsOf: ascii), .stl)

        let ply = try write(MeshFixtures.binaryPLY(), as: "scan.ply")
        XCTAssertEqual(try ModelFormat.sniff(contentsOf: ply), .ply)
    }

    /// Content beats the extension — the case that matters for a file arriving from a
    /// share sheet or a messaging app that renamed it.
    func testContentBeatsAMisleadingExtension() throws {
        let url = try write(
            MeshFixtures.binarySTL(triangles: MeshFixtures.boxTriangles(size: boxSize)),
            as: "actually-an-stl.obj"
        )
        XCTAssertEqual(try ModelFormat.sniff(contentsOf: url), .stl)
    }

    /// OBJ has no signature at all, so it is identified by its extension alone.
    func testOBJIsIdentifiedByExtension() throws {
        let url = try MeshFixtures.writeQuadOBJ(in: directory)
        XCTAssertEqual(try ModelFormat.sniff(contentsOf: url), .obj)
    }

    func testUnsupportedFormatErrorCarriesTheExtension() throws {
        let url = try write(Data("not a model".utf8), as: "drawing.fbx")
        XCTAssertThrowsError(try ModelFormat.sniff(contentsOf: url)) { error in
            XCTAssertEqual(error as? ModelLoadingError, .unsupportedFormat(fileExtension: "fbx"))
            XCTAssertTrue(
                (error as? ModelLoadingError)?.errorDescription?.contains(".fbx") == true,
                "the message must name the format the user tried to open"
            )
        }
    }

    func testDefaultUnitsMatchTheFormatsConventions() {
        XCTAssertEqual(ModelFormat.stl.defaultUnit, .millimeters)
        XCTAssertEqual(ModelFormat.obj.defaultUnit, .meters)
        XCTAssertEqual(ModelFormat.ply.defaultUnit, .meters)
        XCTAssertEqual(ModelFormat.usdz.defaultUnit, .meters)
        XCTAssertFalse(ModelFormat.stl.carriesUnit)
        XCTAssertTrue(ModelFormat.usdz.carriesUnit)
    }

    // MARK: - STL

    func testBinarySTLBoundsAndRealWorldSize() throws {
        let url = try write(
            MeshFixtures.binarySTL(triangles: MeshFixtures.boxTriangles(size: boxSize)),
            as: "part.stl"
        )
        let asset = try MeshAsset.load(contentsOf: url)

        XCTAssertEqual(asset.format, .stl)
        XCTAssertEqual(asset.unit, .millimeters, "STL has no unit; mm is the printing default")
        XCTAssertEqual(asset.triangleCount, 12)

        let bounds = try XCTUnwrap(asset.bounds)
        assertEqual(bounds.min, .zero, accuracy: 1e-4)
        assertEqual(bounds.max, boxSize, accuracy: 1e-4)

        // The point of the unit: 10 × 20 × 30 mm is 1 × 2 × 3 cm in the room.
        let metric = try XCTUnwrap(asset.boundsInMeters)
        assertEqual(metric.extents, SIMD3<Float>(0.01, 0.02, 0.03), accuracy: 1e-6)
    }

    func testExplicitUnitOverridesTheDefault() throws {
        let url = try write(
            MeshFixtures.binarySTL(triangles: MeshFixtures.boxTriangles(size: boxSize)),
            as: "inches.stl"
        )
        let asset = try MeshAsset.load(contentsOf: url, unit: .inches)
        XCTAssertEqual(asset.unit, .inches)
        let metric = try XCTUnwrap(asset.boundsInMeters)
        assertEqual(metric.extents, SIMD3<Float>(0.254, 0.508, 0.762), accuracy: 1e-5)
    }

    func testAsciiAndBinarySTLAgree() throws {
        let triangles = MeshFixtures.boxTriangles(size: boxSize)
        let binary = try MeshAsset.load(
            contentsOf: try write(MeshFixtures.binarySTL(triangles: triangles), as: "b.stl")
        )
        let ascii = try MeshAsset.load(
            contentsOf: try write(MeshFixtures.asciiSTL(triangles: triangles), as: "a.stl")
        )
        XCTAssertEqual(binary.triangleCount, ascii.triangleCount)
        assertEqual(
            try XCTUnwrap(binary.bounds).max,
            try XCTUnwrap(ascii.bounds).max,
            accuracy: 1e-4
        )
    }

    func testSTLNormalsArePresent() throws {
        let url = try write(
            MeshFixtures.binarySTL(triangles: MeshFixtures.boxTriangles(size: boxSize)),
            as: "normals.stl"
        )
        let asset = try MeshAsset.load(contentsOf: url)
        let part = try XCTUnwrap(asset.parts.first)
        let normals = try XCTUnwrap(part.normals, "a lit model needs normals")
        XCTAssertEqual(normals.count, part.positions.count)
        for normal in normals {
            XCTAssertEqual(simd_length(normal), 1, accuracy: 1e-3)
        }
    }

    // MARK: - OBJ

    /// A quad face must come back as two triangles — RealityKit draws nothing else.
    func testOBJQuadIsTriangulatedAndKeepsItsMaterial() async throws {
        let url = try MeshFixtures.writeQuadOBJ(in: directory)
        let asset = try MeshAsset.load(contentsOf: url)

        XCTAssertEqual(asset.format, .obj)
        XCTAssertEqual(asset.unit, .meters)
        XCTAssertEqual(asset.triangleCount, 2, "one quad = two triangles")

        let bounds = try XCTUnwrap(asset.bounds)
        assertEqual(bounds.extents, SIMD3<Float>(4, 5, 0), accuracy: 1e-4)

        let material = try XCTUnwrap(asset.parts.first?.material, "the .mtl sidecar was ignored")
        let baseColor = try XCTUnwrap(material.baseColor)
        XCTAssertEqual(baseColor.x, 0.8, accuracy: 0.01)
        XCTAssertEqual(baseColor.y, 0.2, accuracy: 0.01)
        XCTAssertEqual(baseColor.z, 0.1, accuracy: 0.01)
    }

    // MARK: - PLY

    func testBinaryPLYKeepsPerVertexColors() throws {
        let url = try write(MeshFixtures.binaryPLY(), as: "scan.ply")
        let asset = try MeshAsset.load(contentsOf: url)

        XCTAssertEqual(asset.format, .ply)
        XCTAssertEqual(asset.triangleCount, 2)
        let bounds = try XCTUnwrap(asset.bounds)
        assertEqual(bounds.extents, SIMD3<Float>(2, 3, 0), accuracy: 1e-4)

        let part = try XCTUnwrap(asset.parts.first)
        let colors = try XCTUnwrap(part.colors, "PLY vertex colours were dropped")
        XCTAssertEqual(colors.count, part.positions.count)

        // Every colour, not just the first: reading a 3-component colour attribute as
        // `.float4` makes ModelIO hand back tightly packed float3 data at a float4
        // stride, so vertex 1 onwards drifts a third of a vertex out of step and this
        // red/green/blue/white fixture comes back red/red/white/black.
        let expected: [SIMD3<Float>] = [
            SIMD3(1, 0, 0), SIMD3(0, 1, 0), SIMD3(0, 0, 1), SIMD3(1, 1, 1)
        ]
        for (index, want) in expected.enumerated() {
            assertEqual(
                SIMD3(colors[index].x, colors[index].y, colors[index].z),
                want,
                accuracy: 0.01
            )
            XCTAssertEqual(colors[index].w, 1, accuracy: 1e-5, "RGB sources are opaque")
        }
    }

    /// Vertices are not silently unwelded: `MDLMesh.addNormals` turns this 4-vertex quad
    /// into 6 vertices and rewrites the index buffer, which is why the reader generates
    /// missing normals itself instead.
    func testPLYVertexCountSurvivesNormalGeneration() throws {
        let url = try write(MeshFixtures.binaryPLY(), as: "welded.ply")
        let part = try XCTUnwrap(MeshAsset.load(contentsOf: url).parts.first)
        XCTAssertEqual(part.positions.count, 4)
        XCTAssertEqual(part.indices, [0, 1, 2, 0, 2, 3])

        let shaded = part.generatingNormals()
        XCTAssertEqual(shaded.positions.count, 4, "generating normals must not unweld")
        XCTAssertEqual(shaded.normals?.count, 4)
    }

    // MARK: - Geometry helpers

    func testGeneratedNormalsAreUnitLength() {
        let geometry = MeshGeometry(
            name: "tri",
            positions: [SIMD3(0, 0, 0), SIMD3(1, 0, 0), SIMD3(0, 1, 0)],
            indices: [0, 1, 2]
        ).generatingNormals()
        let normals = geometry.normals
        XCTAssertEqual(normals?.count, 3)
        for normal in normals ?? [] {
            XCTAssertEqual(simd_length(normal), 1, accuracy: 1e-5)
            assertEqual(normal, SIMD3<Float>(0, 0, 1), accuracy: 1e-5)
        }
    }

    func testInMetersBakesTheUnitIntoPositions() throws {
        let asset = MeshAsset(
            format: .stl,
            unit: .millimeters,
            parts: [MeshGeometry(
                name: "p",
                positions: [SIMD3(0, 0, 0), SIMD3(100, 0, 0), SIMD3(0, 100, 0)],
                indices: [0, 1, 2]
            )]
        )
        let metric = asset.inMeters()
        XCTAssertEqual(metric.unit, .meters)
        assertEqual(metric.parts[0].positions[1], SIMD3<Float>(0.1, 0, 0), accuracy: 1e-6)
        // Idempotent: converting an already-metric asset changes nothing.
        assertEqual(metric.inMeters().parts[0].positions[1], SIMD3<Float>(0.1, 0, 0), accuracy: 1e-6)
    }

    // MARK: - Entity

    #if os(iOS) || os(macOS) || os(visionOS)
    /// End to end: a millimetre STL becomes a RealityKit mesh measured in metres.
    @MainActor
    func testModelNodeFromSTLIsMetric() async throws {
        let url = try write(
            MeshFixtures.binarySTL(triangles: MeshFixtures.boxTriangles(size: boxSize)),
            as: "metric.stl"
        )
        let node = try await ModelNode.load(contentsOf: url)
        let mesh = try XCTUnwrap(node.entity.model?.mesh)
        let extents = mesh.bounds.extents
        XCTAssertEqual(extents.x, 0.01, accuracy: 1e-4)
        XCTAssertEqual(extents.y, 0.02, accuracy: 1e-4)
        XCTAssertEqual(extents.z, 0.03, accuracy: 1e-4)
    }

    /// One RealityKit material per part, so a multi-material OBJ does not collapse to a
    /// single colour.
    @MainActor
    func testModelNodeFromOBJHasAMaterial() async throws {
        let url = try MeshFixtures.writeQuadOBJ(in: directory)
        let node = try await ModelNode.load(contentsOf: url)
        let model = try XCTUnwrap(node.entity.model)
        XCTAssertEqual(model.materials.count, 1)
        XCTAssertEqual(model.mesh.expectedMaterialCount, 1)
    }
    #endif

    // MARK: -

    private func assertEqual(
        _ lhs: SIMD3<Float>,
        _ rhs: SIMD3<Float>,
        accuracy: Float,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertEqual(lhs.x, rhs.x, accuracy: accuracy, file: file, line: line)
        XCTAssertEqual(lhs.y, rhs.y, accuracy: accuracy, file: file, line: line)
        XCTAssertEqual(lhs.z, rhs.z, accuracy: accuracy, file: file, line: line)
    }
}
