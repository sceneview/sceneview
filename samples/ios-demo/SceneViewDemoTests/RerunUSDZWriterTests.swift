// RerunUSDZWriterTests.swift
//
// The Rerun showcase's `.usdz` export (`RerunUSDZWriter.swift`): the zip container AR Quick
// Look needs (stored, 64-byte aligned, root layer first), the USD layer's prims and metadata,
// the point colour atlas, the re-homed placed model — and RealityKit actually loading it.

#if DEBUG

import CoreGraphics
import ImageIO
import RealityKit
import simd
import XCTest
@testable import SceneViewDemo

final class RerunUSDZWriterTests: XCTestCase {

    // MARK: - Container

    func testArchiveIsStoredAlignedAndConsistent() throws {
        let archive = try RerunUSDZWriter.data(for: Self.scene(), models: ["cube": Self.cubeModel()])
        let entries = try ZipListing(archive)

        XCTAssertEqual(entries.local.map(\.path), entries.central.map(\.path), "central directory lists every file, in order")
        XCTAssertEqual(entries.local.map(\.offset), entries.central.map(\.localOffset))
        for entry in entries.local {
            XCTAssertEqual(entry.method, 0, "\(entry.path) must be stored")
            XCTAssertEqual(entry.dataOffset % 64, 0, "\(entry.path) data must start on a 64-byte boundary")
            XCTAssertEqual(entry.crc, RerunUSDZWriter.CRC32.checksum(entry.data), "\(entry.path) CRC")
        }
        for (local, central) in zip(entries.local, entries.central) {
            XCTAssertEqual(local.crc, central.crc)
            XCTAssertEqual(local.data.count, central.size)
        }
    }

    func testCRC32MatchesTheStandardCheckValue() {
        XCTAssertEqual(RerunUSDZWriter.CRC32.checksum(Data("123456789".utf8)), 0xCBF4_3926)
    }

    func testRootLayerComesFirstWithStageMetadata() throws {
        let entries = try ZipListing(RerunUSDZWriter.data(for: Self.scene())).local
        XCTAssertEqual(entries.first?.path, "scene.usda")
        let layer = try XCTUnwrap(entries.first.map { String(decoding: $0.data, as: UTF8.self) })
        XCTAssertTrue(layer.hasPrefix("#usda 1.0\n"))
        XCTAssertTrue(layer.contains("defaultPrim = \"world\""))
        XCTAssertTrue(layer.contains("upAxis = \"Y\""))
        XCTAssertTrue(layer.contains("metersPerUnit = 1"))
        XCTAssertTrue(layer.contains("string title = \"Living \\\"room\\\"\""), "title is escaped")
    }

    // MARK: - Prims

    func testPrimsFollowTheWireEntities() throws {
        let files = try ZipListing(RerunUSDZWriter.data(for: Self.scene(), models: ["cube": Self.cubeModel()])).files
        let layer = String(decoding: try XCTUnwrap(files["scene.usda"]), as: UTF8.self)

        for prim in ["def Xform \"world\"", "def Mesh \"points\"", "def Xform \"camera\"", "def Mesh \"path\"",
                     "def Xform \"planes\"", "def Mesh \"_1\"", "def Mesh \"_2\"",
                     "def Xform \"anchors\"", "def Xform \"_7\"", "def Xform \"_8\"", "def Xform \"cube\"",
                     "def Material \"points\"", "def Material \"plane_1\"", "def Material \"plane\""] {
            XCTAssertTrue(layer.contains(prim), "missing \(prim)")
        }
        XCTAssertTrue(layer.contains("prepend references = @models/cube/cube.usda@"))
        XCTAssertTrue(layer.contains("asset inputs:file = @textures/points.png@"))
        XCTAssertTrue(layer.contains("asset inputs:file = @textures/plane_1.jpg@"))
        XCTAssertFalse(layer.contains("def Points"), "Quick Look does not draw UsdGeomPoints")

        XCTAssertNotNil(files["textures/points.png"])
        XCTAssertNotNil(files["textures/plane_1.jpg"])
        XCTAssertNil(files["textures/plane_2.jpg"], "plane 2 has no photo")
    }

    func testPlacedModelIsPackagedOnceAndFittedToItsAnchor() throws {
        let model = Self.cubeModel()
        let files = try ZipListing(RerunUSDZWriter.data(for: Self.scene(), models: ["cube": model])).files
        let inner = try ZipListing(model).files
        for (path, data) in inner {
            XCTAssertEqual(files["models/cube/" + path], data, "\(path) copied byte for byte")
        }
        let layer = String(decoding: try XCTUnwrap(files["scene.usda"]), as: UTF8.self)
        XCTAssertEqual(layer.components(separatedBy: "@models/cube/cube.usda@").count - 1, 2, "both anchors reference it")

        // The cube spans x 1…3, y -1…1, z 0…2: largest side 2 → 0.3 m, base centre (2, -1, 1).
        let fit = RerunUSDZWriter.fitting(modelBytes: model)
        XCTAssertEqual(fit.scale, 0.15, accuracy: 1e-4)
        XCTAssertEqual(fit.offset.x, -2, accuracy: 1e-4)
        XCTAssertEqual(fit.offset.y, 1, accuracy: 1e-4)
        XCTAssertEqual(fit.offset.z, -1, accuracy: 1e-4)
    }

    func testAnchorWithoutItsModelStaysABareXform() throws {
        let files = try ZipListing(RerunUSDZWriter.data(for: Self.scene())).files
        let layer = String(decoding: try XCTUnwrap(files["scene.usda"]), as: UTF8.self)
        XCTAssertTrue(layer.contains("def Xform \"_7\""))
        XCTAssertFalse(layer.contains("references"))
        XCTAssertFalse(files.keys.contains { $0.hasPrefix("models/") })
    }

    func testCompressedModelIsRejected() {
        var zip = Self.cubeModel()
        zip[8] = 8 // first local header's method → deflate
        // The central directory is what the reader trusts: patch it too.
        let listing = try? ZipListing(zip)
        if let central = listing?.centralStart { zip[central + 10] = 8 }
        XCTAssertThrowsError(try RerunUSDZWriter.data(for: Self.scene(), models: ["cube": zip])) { error in
            XCTAssertEqual(error as? RerunUSDZWriter.Failure,
                           .compressedModelEntry(model: "cube", path: "cube.usda"))
        }
    }

    // MARK: - Geometry

    func testEveryPointGetsItsOwnColourTexel() throws {
        let scene = Self.scene()
        let cloud = RerunUSDZWriter.pointCloud(scene)
        XCTAssertEqual(cloud.mesh.triangles.count, scene.points.count * 12)
        let files = try ZipListing(RerunUSDZWriter.data(for: scene)).files
        let png = try XCTUnwrap(files["textures/points.png"])
        let pixels = try XCTUnwrap(Self.rgba(png))
        XCTAssertEqual(pixels.width, RerunUSDZWriter.atlasWidth)

        for index in [0, 1, 127, 128, scene.points.count - 1] {
            let uv = cloud.mesh.uvs[index * 4]
            let x = Int(uv.x * Float(pixels.width))
            let y = Int((1 - uv.y) * Float(pixels.height)) // USD's t runs bottom-up
            let at = (y * pixels.width + x) * 4
            XCTAssertEqual(Array(pixels.bytes[at..<(at + 3)]),
                           [scene.pointColors[index].x, scene.pointColors[index].y, scene.pointColors[index].z],
                           "point \(index)")
            XCTAssertEqual(cloud.mesh.uvs[index * 4 + 3], uv, "one texel per point")
        }
    }

    func testLargeCloudsAreSubsampledUnderTheTriangleBudget() {
        var scene = Self.scene()
        scene.points = (0..<25_000).map { SIMD3(Float($0 % 100) * 0.02, 0, Float($0 / 100) * 0.02) }
        scene.pointColors = Array(repeating: SIMD3(10, 20, 30), count: scene.points.count)
        let cloud = RerunUSDZWriter.pointCloud(scene)
        XCTAssertEqual(cloud.mesh.triangles.count / 3, RerunUSDZWriter.maxPoints * 4)
        XCTAssertLessThanOrEqual(cloud.mesh.triangles.count / 3, 50_000)
        XCTAssertEqual(RerunUSDZWriter.subsampledIndices(count: 25_000, max: 10_000).prefix(3), [0, 2, 5])
        XCTAssertEqual(RerunUSDZWriter.subsampledIndices(count: 25_000, max: 10_000).last, 24_997)
    }

    func testPlaneTextureCoordinatesFollowThePhotoFrame() {
        let plane = Self.scene().planes[0]
        let mesh = RerunUSDZWriter.fan(plane, textured: true)
        XCTAssertEqual(mesh.points.count, plane.polygon.count + 1)
        XCTAssertEqual(mesh.triangles.count, plane.polygon.count * 6, "a fan per side")
        // polygon[0] is the frame's origin (top-left of the photo): USD st (0, 1).
        XCTAssertEqual(mesh.uvs[1].x, 0, accuracy: 1e-5)
        XCTAssertEqual(mesh.uvs[1].y, 1, accuracy: 1e-5)
        // polygon[2] is origin + u + v (bottom-right): st (1, 0).
        XCTAssertEqual(mesh.uvs[3].x, 1, accuracy: 1e-5)
        XCTAssertEqual(mesh.uvs[3].y, 0, accuracy: 1e-5)
    }

    func testPhotosUSDZCannotHoldAreTranscodedKeepingAlpha() throws {
        // TIFF stands in for WebP (ImageIO decodes both but cannot write WebP).
        let withAlpha = try XCTUnwrap(RerunUSDZWriter.textureFile(Self.tiff(alpha: true)))
        XCTAssertEqual(withAlpha.ext, "png")
        let decoded = try XCTUnwrap(CGImageSourceCreateWithData(withAlpha.data as CFData, nil)
            .flatMap { CGImageSourceCreateImageAtIndex($0, 0, nil) })
        XCTAssertFalse([CGImageAlphaInfo.none, .noneSkipFirst, .noneSkipLast].contains(decoded.alphaInfo))
        XCTAssertEqual(try XCTUnwrap(RerunUSDZWriter.textureFile(Self.tiff(alpha: false))).ext, "jpg")
        XCTAssertNil(RerunUSDZWriter.textureFile(Data("not an image".utf8)))

        var scene = Self.scene()
        scene.planes[0].texture?.imageData = Self.tiff(alpha: true)
        let files = try ZipListing(RerunUSDZWriter.data(for: scene)).files
        XCTAssertNotNil(files["textures/plane_1.png"])
        let layer = String(decoding: try XCTUnwrap(files["scene.usda"]), as: UTF8.self)
        // Quick Look drops an emissive texture under an opacity threshold: photos are diffuse.
        XCTAssertTrue(layer.contains("color3f inputs:diffuseColor.connect = </world/Looks/plane_1/Texture.outputs:rgb>"))
        XCTAssertTrue(layer.contains("float inputs:opacity.connect = </world/Looks/plane_1/Texture.outputs:a>"))
        XCTAssertTrue(layer.contains("float inputs:opacityThreshold = 0.5"))
    }

    func testCameraPathIsATube() {
        let mesh = RerunUSDZWriter.tube(through: Self.scene().cameraPath.map(\.position), radius: 0.01)
        let samples = Self.scene().cameraPath.count
        XCTAssertEqual(mesh.points.count, samples * 4)
        XCTAssertEqual(mesh.triangles.count / 3, (samples - 1) * 8)
        XCTAssertTrue(RerunUSDZWriter.tube(through: [.zero, .zero], radius: 0.01).isEmpty)
    }

    func testIdentifiersAreValidPrimNames() {
        XCTAssertEqual(RerunUSDZWriter.identifier("points"), "points")
        XCTAssertEqual(RerunUSDZWriter.identifier("3"), "_3")
        XCTAssertEqual(RerunUSDZWriter.identifier("-1"), "_1")
        XCTAssertEqual(RerunUSDZWriter.identifier("my model.v2"), "my_model_v2")
        XCTAssertEqual(RerunUSDZWriter.identifier(""), "_")
        var taken = Set<String>()
        XCTAssertEqual(RerunUSDZWriter.unique("_1", in: &taken), "_1")
        XCTAssertEqual(RerunUSDZWriter.unique("_1", in: &taken), "_1_2")
    }

    // MARK: - RealityKit

    @MainActor
    func testRealityKitLoadsTheExport() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("rerun-export-test-\(UUID()).usdz")
        try RerunUSDZWriter.data(for: Self.scene(), models: ["cube": Self.cubeModel()]).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }

        let entity = try await Entity(contentsOf: url)
        var models = 0
        func count(_ e: Entity) {
            if e.components.has(ModelComponent.self) { models += 1 }
            e.children.forEach(count)
        }
        count(entity)
        // Points, camera path, two planes, one cube per anchor.
        XCTAssertGreaterThanOrEqual(models, 6)

        // Points span x, z 0…1.9 and y 0…0.3, the path climbs to y 1.5, a plane reaches z -1.
        let bounds = entity.visualBounds(relativeTo: nil)
        XCTAssertEqual(bounds.min.x, -0.2, accuracy: 0.1)
        XCTAssertEqual(bounds.max.x, 2.2, accuracy: 0.1)
        XCTAssertEqual(bounds.max.y, 1.5, accuracy: 0.1)
        XCTAssertEqual(bounds.min.z, -1, accuracy: 0.1)
    }

    // MARK: - Fixtures

    /// 400 coloured points on a 20 × 20 grid, a camera circling at 1.5 m, a photo-textured
    /// floor and a bare wall, two anchors carrying the `cube` model.
    static func scene() -> RerunExportScene {
        var points: [SIMD3<Float>] = []
        var colors: [SIMD3<UInt8>] = []
        for i in 0..<400 {
            points.append(SIMD3(Float(i % 20) * 0.1, Float(i % 7) * 0.05, Float(i / 20) * 0.1))
            colors.append(SIMD3(UInt8(i % 256), UInt8((i * 7) % 256), UInt8(255 - i % 256)))
        }
        let path = (0..<24).map { k -> RerunExportScene.CameraSample in
            let a = Float(k) / 24 * 2 * .pi
            return RerunExportScene.CameraSample(time: Double(k) * 0.1,
                                                 position: SIMD3(1 + cos(a), 1.5, 1 + sin(a)),
                                                 orientation: simd_quatf(angle: a, axis: SIMD3(0, 1, 0)))
        }
        let floor = RerunExportScene.Plane(
            id: 1, kind: "horizontal_upward",
            polygon: [SIMD3(-0.2, 0, -1), SIMD3(2.2, 0, -1), SIMD3(2.2, 0, 2), SIMD3(-0.2, 0, 2)],
            texture: RerunExportScene.PlaneTexture(imageData: jpeg(), origin: SIMD3(-0.2, 0, -1),
                                                   u: SIMD3(2.4, 0, 0), v: SIMD3(0, 0, 3)))
        let wall = RerunExportScene.Plane(
            id: 2, kind: "vertical",
            polygon: [SIMD3(0, 0, -0.5), SIMD3(1, 0, -0.5), SIMD3(1, 1, -0.5), SIMD3(0, 1, -0.5)],
            texture: nil)
        return RerunExportScene(
            title: "Living \"room\"", lens: nil, points: points, pointColors: colors,
            cameraPath: path, keyframes: [], images: [:], planes: [floor, wall],
            anchors: [
                RerunExportScene.Anchor(id: 7, position: SIMD3(0.5, 0, 0.5), orientation: simd_quatf(angle: 0.4, axis: SIMD3(0, 1, 0)), modelName: "cube"),
                RerunExportScene.Anchor(id: 8, position: SIMD3(1.5, 0, 1.5), orientation: simd_quatf(angle: 0, axis: SIMD3(0, 1, 0)), modelName: "cube"),
            ])
    }

    /// A USDZ holding a USDA cube spanning x 1…3, y -1…1, z 0…2 — off-centre, so fitting it
    /// must both scale and move it.
    static func cubeModel() -> Data {
        let layer = """
        #usda 1.0
        (
            defaultPrim = "cube"
            metersPerUnit = 1
            upAxis = "Y"
        )

        def Xform "cube"
        {
            def Mesh "box"
            {
                float3[] extent = [(1, -1, 0), (3, 1, 2)]
                int[] faceVertexCounts = [4, 4, 4, 4, 4, 4]
                int[] faceVertexIndices = [0, 1, 3, 2, 4, 6, 7, 5, 0, 4, 5, 1, 2, 3, 7, 6, 0, 2, 6, 4, 1, 5, 7, 3]
                point3f[] points = [(1, -1, 0), (3, -1, 0), (1, 1, 0), (3, 1, 0), (1, -1, 2), (3, -1, 2), (1, 1, 2), (3, 1, 2)]
                uniform token subdivisionScheme = "none"
            }
        }

        """
        return RerunUSDZWriter.Archive.write([(path: "cube.usda", data: Data(layer.utf8))])
    }

    /// A 16 × 16 JPEG, half red half blue.
    static func jpeg() -> Data {
        var rgba = [UInt8](repeating: 255, count: 16 * 16 * 4)
        for i in 0..<(16 * 16) {
            let left = i % 16 < 8
            rgba[i * 4] = left ? 220 : 30
            rgba[i * 4 + 1] = 40
            rgba[i * 4 + 2] = left ? 30 : 220
        }
        let png = try! RerunUSDZWriter.pngData(rgba: rgba, width: 16, height: 16)
        let source = CGImageSourceCreateWithData(png as CFData, nil)!
        let image = CGImageSourceCreateImageAtIndex(source, 0, nil)!
        let out = NSMutableData()
        let destination = CGImageDestinationCreateWithData(out as CFMutableData, "public.jpeg" as CFString, 1, nil)!
        CGImageDestinationAddImage(destination, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return out as Data
    }

    /// An 8 × 8 TIFF, its left half transparent when `alpha`.
    static func tiff(alpha: Bool) -> Data {
        var rgba = [UInt8](repeating: 200, count: 8 * 8 * 4)
        if alpha {
            for i in 0..<64 where i % 8 < 4 { rgba[i * 4 ..< i * 4 + 4] = [0, 0, 0, 0] }
        }
        let info = alpha ? CGImageAlphaInfo.premultipliedLast : .noneSkipLast
        let image = CGImage(width: 8, height: 8, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: 32,
                            space: CGColorSpace(name: CGColorSpace.sRGB)!,
                            bitmapInfo: CGBitmapInfo(rawValue: info.rawValue),
                            provider: CGDataProvider(data: Data(rgba) as CFData)!,
                            decode: nil, shouldInterpolate: false, intent: .defaultIntent)!
        let out = NSMutableData()
        let destination = CGImageDestinationCreateWithData(out as CFMutableData, "public.tiff" as CFString, 1, nil)!
        CGImageDestinationAddImage(destination, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return out as Data
    }

    /// Decoded RGBA8 bytes of an image.
    static func rgba(_ data: Data) -> (bytes: [UInt8], width: Int, height: Int)? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
        var bytes = [UInt8](repeating: 0, count: image.width * image.height * 4)
        let drawn = bytes.withUnsafeMutableBytes { raw -> Bool in
            guard let context = CGContext(data: raw.baseAddress, width: image.width, height: image.height,
                                          bitsPerComponent: 8, bytesPerRow: image.width * 4,
                                          space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                          bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
            return true
        }
        return drawn ? (bytes, image.width, image.height) : nil
    }
}

/// An independent zip reader: walks the local headers front to back, then the central
/// directory, so the tests do not trust the writer's own reader.
private struct ZipListing {
    struct Local {
        var path: String
        var offset: Int
        var method: Int
        var crc: UInt32
        var dataOffset: Int
        var data: Data
    }

    struct Central {
        var path: String
        var crc: UInt32
        var size: Int
        var localOffset: Int
    }

    var local: [Local] = []
    var central: [Central] = []
    var centralStart = 0
    var files: [String: Data] { Dictionary(uniqueKeysWithValues: local.map { ($0.path, $0.data) }) }

    init(_ zip: Data) throws {
        let b = [UInt8](zip)
        func u16(_ i: Int) -> Int { Int(b[i]) | Int(b[i + 1]) << 8 }
        func u32(_ i: Int) -> UInt32 { UInt32(u16(i)) | UInt32(u16(i + 2)) << 16 }
        var i = 0
        while i + 4 <= b.count, u32(i) == 0x0403_4B50 {
            let size = Int(u32(i + 18))
            XCTAssertEqual(Int(u32(i + 22)), size, "stored: compressed size == size")
            let name = String(decoding: b[(i + 30)..<(i + 30 + u16(i + 26))], as: UTF8.self)
            let start = i + 30 + u16(i + 26) + u16(i + 28)
            local.append(Local(path: name, offset: i, method: u16(i + 8), crc: u32(i + 14),
                               dataOffset: start, data: Data(b[start..<(start + size)])))
            i = start + size
        }
        centralStart = i
        while i + 4 <= b.count, u32(i) == 0x0201_4B50 {
            let nameLength = u16(i + 28)
            central.append(Central(path: String(decoding: b[(i + 46)..<(i + 46 + nameLength)], as: UTF8.self),
                                   crc: u32(i + 16), size: Int(u32(i + 24)), localOffset: Int(u32(i + 42))))
            i += 46 + nameLength + u16(i + 30) + u16(i + 32)
        }
        guard u32(i) == 0x0605_4B50, u16(i + 10) == central.count, Int(u32(i + 16)) == centralStart else {
            throw CocoaError(.fileReadCorruptFile)
        }
    }
}

#endif
