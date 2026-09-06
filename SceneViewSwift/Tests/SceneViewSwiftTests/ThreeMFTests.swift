import XCTest
import simd
@testable import SceneViewSwift

#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
#endif

/// The 3MF reader: ZIP container, core-spec XML, transforms, materials, and the
/// Production extension's multi-part packages.
final class ThreeMFTests: XCTestCase {

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

    private func write(_ data: Data, as name: String = "model.3mf") throws -> URL {
        let url = directory.appendingPathComponent(name)
        try data.write(to: url)
        return url
    }

    // MARK: - Container

    func testReadsADeflatedPackage() throws {
        let url = try write(ThreeMFFixtures.simpleBox(deflate: true))
        let asset = try MeshAsset.load(contentsOf: url)

        XCTAssertEqual(asset.format, .threeMF)
        XCTAssertEqual(asset.unit, .millimeters)
        XCTAssertEqual(asset.triangleCount, 12)
        let bounds = try XCTUnwrap(asset.bounds)
        assertEqual(bounds.extents, SIMD3<Float>(10, 20, 30), accuracy: 1e-4)
    }

    /// Stored entries are legal and small writers emit them.
    func testReadsAStoredPackage() throws {
        let url = try write(ThreeMFFixtures.simpleBox(deflate: false), as: "stored.3mf")
        let asset = try MeshAsset.load(contentsOf: url)
        XCTAssertEqual(asset.triangleCount, 12)
    }

    func testTruncatedArchiveFailsWithAReason() throws {
        var data = ThreeMFFixtures.simpleBox()
        data.removeLast(30)  // eats the end-of-central-directory record
        let url = try write(data, as: "truncated.3mf")
        XCTAssertThrowsError(try MeshAsset.load(contentsOf: url)) { error in
            guard case .malformed = (error as? ModelLoadingError) else {
                return XCTFail("expected .malformed, got \(error)")
            }
        }
    }

    // MARK: - Sniffing

    /// USDZ and 3MF are both zip archives. The first entry's name is what tells them
    /// apart — get this wrong and every 3MF is handed to RealityKit, which cannot read it.
    func testZipIsToldApartFromUSDZ() throws {
        let threeMF = try write(ThreeMFFixtures.simpleBox(), as: "print.3mf")
        XCTAssertEqual(try ModelFormat.sniff(contentsOf: threeMF), .threeMF)

        // A zip whose first entry is a `.usdc` is a USDZ, whatever it is called.
        let fakeUSDZ = ThreeMFFixtures.package([
            ThreeMFFixtures.FileEntry("model.usdc", "not really usd", deflate: false)
        ])
        let usdzURL = try write(fakeUSDZ, as: "unlabelled.bin")
        XCTAssertEqual(try ModelFormat.sniff(contentsOf: usdzURL), .usdz)
    }

    func testThreeMFCarriesItsOwnUnit() {
        XCTAssertTrue(ModelFormat.threeMF.carriesUnit)
        XCTAssertEqual(ModelFormat.threeMF.defaultUnit, .millimeters)
        XCTAssertEqual(ModelFormat.threeMF.loader, .sceneView)
    }

    // MARK: - Units

    func testDeclaredUnitIsHonoured() throws {
        let url = try write(
            ThreeMFFixtures.simpleBox(size: SIMD3(1, 2, 3), unit: "inch"),
            as: "inch.3mf"
        )
        let asset = try MeshAsset.load(contentsOf: url)
        XCTAssertEqual(asset.unit, .inches)
        let metric = try XCTUnwrap(asset.boundsInMeters)
        assertEqual(metric.extents, SIMD3<Float>(0.0254, 0.0508, 0.0762), accuracy: 1e-5)
    }

    func testAnUnknownUnitFallsBackToTheSpecDefault() throws {
        let url = try write(
            ThreeMFFixtures.simpleBox(unit: "furlong"),
            as: "nonsense-unit.3mf"
        )
        // Forgiving on purpose: refusing to open a model over one misspelled word is
        // worse than assuming the spec's own default.
        XCTAssertEqual(try MeshAsset.load(contentsOf: url).unit, .millimeters)
    }

    func testExplicitUnitOverridesTheFile() throws {
        let url = try write(ThreeMFFixtures.simpleBox(), as: "override.3mf")
        let asset = try MeshAsset.load(contentsOf: url, unit: .centimeters)
        XCTAssertEqual(asset.unit, .centimeters)
    }

    // MARK: - Transforms

    /// A build item's transform is twelve numbers in **row-vector** order. Transposing
    /// them scatters every placed part, so this pins the convention with a matrix whose
    /// transpose would give a visibly different answer.
    func testBuildItemTransformIsRowVector() throws {
        let model = ThreeMFFixtures.modelXML(
            resources: """
                <object id="1" type="model">
            \(ThreeMFFixtures.boxMeshXML(size: SIMD3(2, 2, 2)))
                </object>
            """,
            build: """
                <item objectid="1" transform="2 0 0 0 1 0 0 0 1 100 5 0"/>
            """
        )
        let url = try write(ThreeMFFixtures.package([
            ThreeMFFixtures.contentTypes,
            ThreeMFFixtures.relationships(),
            ThreeMFFixtures.FileEntry("3D/3dmodel.model", model)
        ]), as: "placed.3mf")

        let bounds = try XCTUnwrap(MeshAsset.load(contentsOf: url).bounds)
        // x doubled then translated by 100; y translated by 5; z untouched.
        assertEqual(bounds.min, SIMD3<Float>(100, 5, 0), accuracy: 1e-4)
        assertEqual(bounds.max, SIMD3<Float>(104, 7, 2), accuracy: 1e-4)
    }

    /// Components compose with the item that placed them.
    func testComponentTransformsCompose() throws {
        let model = ThreeMFFixtures.modelXML(
            resources: """
                <object id="1" type="model">
            \(ThreeMFFixtures.boxMeshXML(size: SIMD3(1, 1, 1)))
                </object>
                <object id="2" type="model">
                  <components>
                    <component objectid="1" transform="1 0 0 0 1 0 0 0 1 10 0 0"/>
                  </components>
                </object>
            """,
            build: """
                <item objectid="2" transform="1 0 0 0 1 0 0 0 1 0 100 0"/>
            """
        )
        let url = try write(ThreeMFFixtures.package([
            ThreeMFFixtures.contentTypes,
            ThreeMFFixtures.relationships(),
            ThreeMFFixtures.FileEntry("3D/3dmodel.model", model)
        ]), as: "nested.3mf")

        let bounds = try XCTUnwrap(MeshAsset.load(contentsOf: url).bounds)
        assertEqual(bounds.min, SIMD3<Float>(10, 100, 0), accuracy: 1e-4)
        assertEqual(bounds.max, SIMD3<Float>(11, 101, 1), accuracy: 1e-4)
    }

    func testSelfReferencingComponentIsRejected() throws {
        let model = ThreeMFFixtures.modelXML(
            resources: """
                <object id="1" type="model">
                  <components><component objectid="1"/></components>
                </object>
            """,
            build: """
                <item objectid="1"/>
            """
        )
        let url = try write(ThreeMFFixtures.package([
            ThreeMFFixtures.contentTypes,
            ThreeMFFixtures.relationships(),
            ThreeMFFixtures.FileEntry("3D/3dmodel.model", model)
        ]), as: "cycle.3mf")

        XCTAssertThrowsError(try MeshAsset.load(contentsOf: url)) { error in
            guard case .malformed(let reason) = (error as? ModelLoadingError) else {
                return XCTFail("expected .malformed, got \(error)")
            }
            XCTAssertTrue(reason.contains("itself"), reason)
        }
    }

    // MARK: - Production extension

    /// Bambu Studio and Orca split a project across several `.model` parts and reference
    /// them with `p:path`. A reader that only ever opens `3D/3dmodel.model` shows an
    /// empty plate for every one of those files.
    func testProductionExtensionPathReferences() throws {
        let objectPart = ThreeMFFixtures.modelXML(
            resources: """
                <object id="7" type="model" name="lifted">
            \(ThreeMFFixtures.boxMeshXML(size: SIMD3(4, 4, 4)))
                </object>
            """,
            build: ""
        )
        let root = ThreeMFFixtures.modelXML(
            resources: "",
            build: """
                <item objectid="7" p:path="/3D/Objects/object_1.model"
                  transform="1 0 0 0 1 0 0 0 1 0 0 50"/>
            """,
            extraNamespaces: """

              xmlns:p="http://schemas.microsoft.com/3dmanufacturing/production/2015/06"
            """
        )
        let url = try write(ThreeMFFixtures.package([
            ThreeMFFixtures.contentTypes,
            ThreeMFFixtures.relationships(),
            ThreeMFFixtures.FileEntry("3D/3dmodel.model", root),
            ThreeMFFixtures.FileEntry("3D/Objects/object_1.model", objectPart)
        ]), as: "project.3mf")

        let asset = try MeshAsset.load(contentsOf: url)
        XCTAssertEqual(asset.triangleCount, 12)
        let bounds = try XCTUnwrap(asset.bounds)
        assertEqual(bounds.min, SIMD3<Float>(0, 0, 50), accuracy: 1e-4)
        XCTAssertEqual(asset.parts.first?.name, "lifted")
    }

    /// The root part is found through `_rels/.rels`, not by assuming a path.
    func testRootPartIsResolvedThroughRelationships() throws {
        let model = ThreeMFFixtures.modelXML(
            resources: """
                <object id="1" type="model">
            \(ThreeMFFixtures.boxMeshXML(size: SIMD3(1, 1, 1)))
                </object>
            """,
            build: """
                <item objectid="1"/>
            """
        )
        let url = try write(ThreeMFFixtures.package([
            ThreeMFFixtures.contentTypes,
            ThreeMFFixtures.relationships(target: "/3D/unusual-name.model"),
            ThreeMFFixtures.FileEntry("3D/unusual-name.model", model)
        ]), as: "relocated.3mf")

        XCTAssertEqual(try MeshAsset.load(contentsOf: url).triangleCount, 12)
    }

    // MARK: - Materials

    /// A multi-colour print must come back multi-colour: triangles are grouped by the
    /// colour their `pid`/`p1` resolves to, one mesh part each.
    func testTrianglesAreGroupedByMaterialColor() throws {
        let model = ThreeMFFixtures.modelXML(
            resources: """
                <basematerials id="5">
                  <base name="red" displaycolor="#FF0000"/>
                  <base name="blue" displaycolor="#0000FF"/>
                </basematerials>
                <object id="1" type="model" pid="5" pindex="0">
                  <mesh>
                    <vertices>
                      <vertex x="0" y="0" z="0"/>
                      <vertex x="1" y="0" z="0"/>
                      <vertex x="1" y="1" z="0"/>
                      <vertex x="0" y="1" z="0"/>
                    </vertices>
                    <triangles>
                      <triangle v1="0" v2="1" v3="2" pid="5" p1="0"/>
                      <triangle v1="0" v2="2" v3="3" pid="5" p1="1"/>
                    </triangles>
                  </mesh>
                </object>
            """,
            build: """
                <item objectid="1"/>
            """
        )
        let url = try write(ThreeMFFixtures.package([
            ThreeMFFixtures.contentTypes,
            ThreeMFFixtures.relationships(),
            ThreeMFFixtures.FileEntry("3D/3dmodel.model", model)
        ]), as: "twotone.3mf")

        let asset = try MeshAsset.load(contentsOf: url)
        XCTAssertEqual(asset.parts.count, 2, "two colours must not collapse into one part")
        XCTAssertEqual(asset.triangleCount, 2)

        let colors = asset.parts.compactMap(\.material?.baseColor)
        XCTAssertEqual(colors.count, 2)
        assertEqual(SIMD3(colors[0].x, colors[0].y, colors[0].z), SIMD3(1, 0, 0), accuracy: 0.01)
        assertEqual(SIMD3(colors[1].x, colors[1].y, colors[1].z), SIMD3(0, 0, 1), accuracy: 0.01)
        // Each group carries only the vertices it uses.
        XCTAssertEqual(asset.parts[0].positions.count, 3)
        XCTAssertEqual(asset.parts[1].positions.count, 3)
    }

    func testColorGroupFromTheMaterialsExtension() throws {
        let model = ThreeMFFixtures.modelXML(
            resources: """
                <m:colorgroup id="9">
                  <m:color color="#20C05FFF"/>
                </m:colorgroup>
                <object id="1" type="model" pid="9" pindex="0">
            \(ThreeMFFixtures.boxMeshXML(size: SIMD3(1, 1, 1)))
                </object>
            """,
            build: """
                <item objectid="1"/>
            """,
            extraNamespaces: """

              xmlns:m="http://schemas.microsoft.com/3dmanufacturing/material/2015/02"
            """
        )
        let url = try write(ThreeMFFixtures.package([
            ThreeMFFixtures.contentTypes,
            ThreeMFFixtures.relationships(),
            ThreeMFFixtures.FileEntry("3D/3dmodel.model", model)
        ]), as: "colorgroup.3mf")

        let color = try XCTUnwrap(MeshAsset.load(contentsOf: url).parts.first?.material?.baseColor)
        assertEqual(
            SIMD3(color.x, color.y, color.z),
            SIMD3(Float(0x20) / 255, Float(0xC0) / 255, Float(0x5F) / 255),
            accuracy: 0.01
        )
        XCTAssertEqual(color.w, 1, accuracy: 0.01)
    }

    // MARK: - Robustness

    /// A package with no `<build>` still has objects, and showing nothing for it is
    /// indistinguishable from a broken parser.
    func testEmptyBuildFallsBackToTheObjects() throws {
        let model = ThreeMFFixtures.modelXML(
            resources: """
                <object id="1" type="model">
            \(ThreeMFFixtures.boxMeshXML(size: SIMD3(1, 2, 3)))
                </object>
            """,
            build: ""
        )
        let url = try write(ThreeMFFixtures.package([
            ThreeMFFixtures.contentTypes,
            ThreeMFFixtures.relationships(),
            ThreeMFFixtures.FileEntry("3D/3dmodel.model", model)
        ]), as: "nobuild.3mf")

        XCTAssertEqual(try MeshAsset.load(contentsOf: url).triangleCount, 12)
    }

    func testOutOfRangeTriangleIndexIsRejected() throws {
        let model = ThreeMFFixtures.modelXML(
            resources: """
                <object id="1" type="model">
                  <mesh>
                    <vertices>
                      <vertex x="0" y="0" z="0"/>
                      <vertex x="1" y="0" z="0"/>
                      <vertex x="1" y="1" z="0"/>
                    </vertices>
                    <triangles>
                      <triangle v1="0" v2="1" v3="99"/>
                    </triangles>
                  </mesh>
                </object>
            """,
            build: """
                <item objectid="1"/>
            """
        )
        let url = try write(ThreeMFFixtures.package([
            ThreeMFFixtures.contentTypes,
            ThreeMFFixtures.relationships(),
            ThreeMFFixtures.FileEntry("3D/3dmodel.model", model)
        ]), as: "bad-index.3mf")

        XCTAssertThrowsError(try MeshAsset.load(contentsOf: url))
    }

    /// A 3MF is an untrusted file that arrives by AirDrop, email or a download. An XML
    /// parser that resolves external entities turns opening one into a file read.
    func testExternalEntitiesAreNotResolved() throws {
        let hostFile = directory.appendingPathComponent("secret.txt")
        try Data("TOP-SECRET-VALUE".utf8).write(to: hostFile)

        let model = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE model [
          <!ENTITY leak SYSTEM "file://\(hostFile.path)">
        ]>
        <model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
          <resources>
            <object id="1" type="model" name="&leak;">
        \(ThreeMFFixtures.boxMeshXML(size: SIMD3(1, 1, 1)))
            </object>
          </resources>
          <build><item objectid="1"/></build>
        </model>
        """
        let url = try write(ThreeMFFixtures.package([
            ThreeMFFixtures.contentTypes,
            ThreeMFFixtures.relationships(),
            ThreeMFFixtures.FileEntry("3D/3dmodel.model", model)
        ]), as: "xxe.3mf")

        // Either the parse fails (the entity cannot be resolved, so the reference is an
        // XML error) or it succeeds with the entity unresolved. Both are fine; what must
        // never happen is the host file's contents reaching the caller, through the model
        // *or* through the error message. Both branches assert, so neither is a free pass.
        do {
            for name in try MeshAsset.load(contentsOf: url).parts.map(\.name) {
                XCTAssertFalse(name.contains("TOP-SECRET"), "external entity was resolved")
            }
        } catch {
            XCTAssertFalse(
                "\(error)".contains("TOP-SECRET"),
                "external entity content leaked through the error"
            )
        }
    }

    /// An external DTD that is never fetched: with resolution off the declaration is
    /// simply ignored, so the model still opens normally. This is the half of the XXE
    /// defence that is easy to get wrong in the other direction — refusing every file
    /// that merely *mentions* a DTD.
    func testAnExternalDTDDoesNotBlockTheFile() throws {
        let model = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE model SYSTEM "http://example.invalid/3mf.dtd">
        <model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
          <resources>
            <object id="1" type="model">
        \(ThreeMFFixtures.boxMeshXML(size: SIMD3(1, 1, 1)))
            </object>
          </resources>
          <build><item objectid="1"/></build>
        </model>
        """
        let url = try write(ThreeMFFixtures.package([
            ThreeMFFixtures.contentTypes,
            ThreeMFFixtures.relationships(),
            ThreeMFFixtures.FileEntry("3D/3dmodel.model", model)
        ]), as: "external-dtd.3mf")

        XCTAssertEqual(try MeshAsset.load(contentsOf: url).triangleCount, 12)
    }

    // MARK: - Entity

    #if os(iOS) || os(macOS) || os(visionOS)
    /// End to end: a millimetre 3MF becomes a RealityKit mesh measured in metres.
    @MainActor
    func testModelNodeFrom3MFIsMetric() async throws {
        let url = try write(
            ThreeMFFixtures.simpleBox(size: SIMD3(210, 100, 50)),
            as: "print.3mf"
        )
        let node = try await ModelNode.load(contentsOf: url)
        let mesh = try XCTUnwrap(node.entity.model?.mesh)
        XCTAssertEqual(mesh.bounds.extents.x, 0.210, accuracy: 1e-4)
        XCTAssertEqual(mesh.bounds.extents.y, 0.100, accuracy: 1e-4)
        XCTAssertEqual(mesh.bounds.extents.z, 0.050, accuracy: 1e-4)
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
