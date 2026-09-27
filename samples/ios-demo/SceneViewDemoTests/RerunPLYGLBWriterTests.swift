import CoreGraphics
import Foundation
import ImageIO
import simd
import XCTest

@testable import SceneViewDemo

/// Round-trips the `.ply` and `.glb` exporters: the files are parsed back byte by byte and
/// checked against the scene they came from.
final class RerunPLYGLBWriterTests: XCTestCase {
    // MARK: PLY

    func testPLYHeaderIsExact() {
        let data = RerunPLYWriter.data(for: Self.scene())
        let expected = """
        ply
        format binary_little_endian 1.0
        comment SceneView capture "Test room", Y up, metres
        element vertex 40
        property float x
        property float y
        property float z
        property uchar red
        property uchar green
        property uchar blue
        end_header

        """
        XCTAssertEqual(String(decoding: data.prefix(expected.utf8.count), as: UTF8.self), expected)
    }

    func testPLYByteSizeIsHeaderPlusFifteenBytesPerPoint() {
        let scene = Self.scene()
        let data = RerunPLYWriter.data(for: scene)
        let header = RerunPLYWriter.header(title: scene.title, count: scene.points.count)
        XCTAssertEqual(data.count, header.utf8.count + scene.points.count * 15)
    }

    func testPLYRoundTripsEveryPointAndColour() throws {
        let scene = Self.scene()
        let (count, records) = try Self.parsePLY(RerunPLYWriter.data(for: scene))
        XCTAssertEqual(count, scene.points.count)
        for index in scene.points.indices {
            let record = records.subdata(in: index * 15..<(index + 1) * 15)
            let point = SIMD3<Float>(Self.float(record, 0), Self.float(record, 4), Self.float(record, 8))
            XCTAssertEqual(point, scene.points[index])
            XCTAssertEqual(SIMD3(record[12], record[13], record[14]), scene.pointColors[index])
        }
    }

    func testPLYEmptyCloudIsAHeaderOnly() throws {
        var scene = Self.scene()
        scene.points = []
        scene.pointColors = []
        let data = RerunPLYWriter.data(for: scene)
        let (count, records) = try Self.parsePLY(data)
        XCTAssertEqual(count, 0)
        XCTAssertTrue(records.isEmpty)
        XCTAssertTrue(String(decoding: data, as: UTF8.self).hasSuffix("end_header\n"))
    }

    func testPLYTitleLineBreaksStayInTheComment() throws {
        var scene = Self.scene()
        scene.title = "Two\nlines"
        let (count, _) = try Self.parsePLY(RerunPLYWriter.data(for: scene))
        XCTAssertEqual(count, scene.points.count)
        XCTAssertTrue(RerunPLYWriter.header(title: scene.title, count: 1).contains("\"Two lines\""))
    }

    // MARK: GLB container

    func testGLBContainerIsWellFormed() throws {
        let data = try RerunGLBWriter.data(for: Self.scene())
        XCTAssertEqual(Self.word(data, 0), 0x4654_6C67)
        XCTAssertEqual(Self.word(data, 4), 2)
        XCTAssertEqual(Int(Self.word(data, 8)), data.count)
        let jsonLength = Int(Self.word(data, 12))
        XCTAssertEqual(Self.word(data, 16), 0x4E4F_534A)
        XCTAssertEqual(jsonLength % 4, 0)
        let binStart = 20 + jsonLength
        let binLength = Int(Self.word(data, binStart))
        XCTAssertEqual(Self.word(data, binStart + 4), 0x004E_4942)
        XCTAssertEqual(binLength % 4, 0)
        XCTAssertEqual(binStart + 8 + binLength, data.count)
        // JSON padding is spaces: the chunk still parses, and its tail is whitespace.
        let json = data.subdata(in: 20..<binStart)
        XCTAssertNoThrow(try JSONSerialization.jsonObject(with: json))
        let text = String(decoding: json, as: UTF8.self)
        XCTAssertTrue(text.hasSuffix("}") || text.hasSuffix(" "))
        XCTAssertFalse(json.contains(0))

        let (document, bin) = try Self.parseGLB(data)
        let buffers = try XCTUnwrap(document["buffers"] as? [[String: Any]])
        XCTAssertEqual(buffers.count, 1)
        XCTAssertEqual(buffers[0]["byteLength"] as? Int, bin.count)
        XCTAssertNil(buffers[0]["uri"])
        let asset = try XCTUnwrap(document["asset"] as? [String: Any])
        XCTAssertEqual(asset["version"] as? String, "2.0")
        XCTAssertEqual(asset["generator"] as? String, "SceneView")
    }

    func testGLBBufferViewsAreAlignedAndInRange() throws {
        let (document, bin) = try Self.parseGLB(RerunGLBWriter.data(for: Self.scene()))
        let views = try XCTUnwrap(document["bufferViews"] as? [[String: Any]])
        XCTAssertFalse(views.isEmpty)
        for view in views {
            let offset = try XCTUnwrap(view["byteOffset"] as? Int)
            let length = try XCTUnwrap(view["byteLength"] as? Int)
            XCTAssertEqual(view["buffer"] as? Int, 0)
            XCTAssertEqual(offset % 4, 0)
            XCTAssertLessThanOrEqual(offset + length, bin.count)
        }
    }

    func testGLBLensAndTitleAreInAssetExtras() throws {
        let (document, _) = try Self.parseGLB(RerunGLBWriter.data(for: Self.scene()))
        let asset = try XCTUnwrap(document["asset"] as? [String: Any])
        let extras = try XCTUnwrap(asset["extras"] as? [String: Any])
        XCTAssertEqual(extras["title"] as? String, "Test room")
        let lens = try XCTUnwrap(extras["lens"] as? [String: Any])
        XCTAssertEqual(lens["width"] as? Int, 480)
        XCTAssertEqual(lens["height"] as? Int, 640)
        XCTAssertEqual(lens["fx"] as? Double, 463.5)
    }

    func testGLBEmptySceneHasNoBuffer() throws {
        let scene = RerunExportScene(
            title: "Empty", lens: nil, points: [], pointColors: [], cameraPath: [],
            keyframes: [], images: [:], planes: [], anchors: []
        )
        let data = try RerunGLBWriter.data(for: scene)
        XCTAssertEqual(Int(Self.word(data, 8)), data.count)
        XCTAssertEqual(20 + Int(Self.word(data, 12)), data.count)
        let (document, bin) = try Self.parseGLB(data)
        XCTAssertTrue(bin.isEmpty)
        XCTAssertNil(document["buffers"])
        XCTAssertEqual(Self.nodeNames(document), ["world"])
    }

    // MARK: GLB content

    func testGLBNodesCarryTheWireFormatNames() throws {
        let (document, _) = try Self.parseGLB(RerunGLBWriter.data(for: Self.scene()))
        let names = Set(Self.nodeNames(document))
        for name in [
            "world", "world/points", "world/camera/path", "world/camera/keyframes",
            "world/planes/1", "world/planes/2", "world/anchors/7",
        ] {
            XCTAssertTrue(names.contains(name), "missing node \(name)")
        }
        let scenes = try XCTUnwrap(document["scenes"] as? [[String: Any]])
        let nodes = try XCTUnwrap(document["nodes"] as? [[String: Any]])
        let root = try XCTUnwrap((scenes[0]["nodes"] as? [Int])?.first)
        XCTAssertEqual(nodes[root]["name"] as? String, "world")
        XCTAssertEqual((nodes[root]["children"] as? [Int])?.count, 6)
    }

    func testGLBAccessorsMatchTheInput() throws {
        let scene = Self.scene()
        let (document, bin) = try Self.parseGLB(RerunGLBWriter.data(for: scene))

        // Points: POSITION with exact bounds, COLOR_0 in linear space.
        let points = try Self.primitive(document, node: "world/points")
        XCTAssertEqual(points["mode"] as? Int, 0)
        let attributes = try XCTUnwrap(points["attributes"] as? [String: Int])
        let positions = try Self.vectors(document, bin, attributes["POSITION"])
        XCTAssertEqual(positions.map { SIMD3($0[0], $0[1], $0[2]) }, scene.points)
        try Self.assertBounds(document, attributes["POSITION"], of: scene.points)
        let colors = try Self.vectors(document, bin, attributes["COLOR_0"])
        XCTAssertEqual(colors.count, scene.points.count)
        for (color, source) in zip(colors, scene.pointColors) {
            for channel in 0..<3 {
                let srgb = Float(source[channel]) / 255
                let linear = srgb <= 0.04045 ? srgb / 12.92 : powf((srgb + 0.055) / 1.055, 2.4)
                XCTAssertEqual(color[channel], linear, accuracy: 1e-6)
            }
        }

        // Camera path: a line strip through every sample.
        let path = try Self.primitive(document, node: "world/camera/path")
        XCTAssertEqual(path["mode"] as? Int, 3)
        let pathAccessor = (path["attributes"] as? [String: Int])?["POSITION"]
        let pathPositions = try Self.vectors(document, bin, pathAccessor)
        XCTAssertEqual(pathPositions.map { SIMD3($0[0], $0[1], $0[2]) }, scene.cameraPath.map(\.position))
        try Self.assertBounds(document, pathAccessor, of: scene.cameraPath.map(\.position))

        // Keyframes: 8 segments, 16 vertices, apex at the photo's camera.
        let frustums = try Self.primitive(document, node: "world/camera/keyframes")
        XCTAssertEqual(frustums["mode"] as? Int, 1)
        let frustumVertices = try Self.vectors(
            document, bin, (frustums["attributes"] as? [String: Int])?["POSITION"]
        )
        XCTAssertEqual(frustumVertices.count, 16 * scene.keyframes.count)
        XCTAssertEqual(
            SIMD3(frustumVertices[0][0], frustumVertices[0][1], frustumVertices[0][2]),
            scene.keyframes[0].pose.position
        )

        // Every accessor that declares bounds: they match its own data, and counts fit.
        let accessors = try XCTUnwrap(document["accessors"] as? [[String: Any]])
        for index in accessors.indices {
            let accessor = accessors[index]
            guard let low = accessor["min"] as? [Double], let high = accessor["max"] as? [Double] else {
                continue
            }
            let values = try Self.vectors(document, bin, index)
            for axis in low.indices {
                XCTAssertEqual(low[axis], Double(values.map { $0[axis] }.min() ?? .nan))
                XCTAssertEqual(high[axis], Double(values.map { $0[axis] }.max() ?? .nan))
            }
        }
    }

    func testGLBTexturedPlaneCarriesItsPhotoAndUVs() throws {
        let scene = Self.scene()
        let (document, bin) = try Self.parseGLB(RerunGLBWriter.data(for: scene))
        let primitive = try Self.primitive(document, node: "world/planes/1")
        XCTAssertEqual(primitive["mode"] as? Int, 4)
        let attributes = try XCTUnwrap(primitive["attributes"] as? [String: Int])
        let positions = try Self.vectors(document, bin, attributes["POSITION"])
        let uvs = try Self.vectors(document, bin, attributes["TEXCOORD_0"])
        let texture = try XCTUnwrap(scene.planes[0].texture)
        XCTAssertEqual(positions.count, scene.planes[0].polygon.count + 1)
        for (position, uv) in zip(positions, uvs) {
            let offset = SIMD3(position[0], position[1], position[2]) - texture.origin
            XCTAssertEqual(uv[0], simd_dot(offset, texture.u) / simd_dot(texture.u, texture.u), accuracy: 1e-6)
            XCTAssertEqual(uv[1], simd_dot(offset, texture.v) / simd_dot(texture.v, texture.v), accuracy: 1e-6)
        }
        // The polygon's corners land on the texture's corners.
        XCTAssertEqual(uvs[1], [0, 0])
        XCTAssertEqual(uvs[3], [1, 1])

        // Upward plane: the fan faces +Y.
        let normals = try Self.vectors(document, bin, attributes["NORMAL"])
        XCTAssertEqual(normals[0], [0, 1, 0])
        let indices = try Self.indices(document, bin, primitive["indices"] as? Int)
        let a = positions[indices[0]], b = positions[indices[1]], c = positions[indices[2]]
        let face = simd_cross(
            SIMD3(b[0] - a[0], b[1] - a[1], b[2] - a[2]), SIMD3(c[0] - a[0], c[1] - a[1], c[2] - a[2])
        )
        XCTAssertGreaterThan(face.y, 0)

        // The material samples an embedded, decodable PNG or JPEG.
        let materials = try XCTUnwrap(document["materials"] as? [[String: Any]])
        let material = materials[try XCTUnwrap(primitive["material"] as? Int)]
        let pbr = try XCTUnwrap(material["pbrMetallicRoughness"] as? [String: Any])
        let textureIndex = try XCTUnwrap((pbr["baseColorTexture"] as? [String: Any])?["index"] as? Int)
        let textures = try XCTUnwrap(document["textures"] as? [[String: Any]])
        let images = try XCTUnwrap(document["images"] as? [[String: Any]])
        let image = images[try XCTUnwrap(textures[textureIndex]["source"] as? Int)]
        XCTAssertTrue(["image/png", "image/jpeg"].contains(image["mimeType"] as? String ?? ""))
        let bytes = try Self.bufferView(document, bin, image["bufferView"] as? Int)
        let source = try XCTUnwrap(CGImageSourceCreateWithData(bytes as CFData, nil))
        let decoded = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
        XCTAssertEqual(decoded.width, 8)
        XCTAssertNotNil(material["extensions"] as? [String: Any])
        XCTAssertTrue((document["extensionsUsed"] as? [String] ?? []).contains("KHR_materials_unlit"))
    }

    func testImageTranscoderKeepsCoreFormatsAndReencodesTheRest() throws {
        let opaque = try XCTUnwrap(RerunImageTranscoder.encode(Self.png(alpha: 255)))
        XCTAssertEqual(opaque.mimeType, "image/png")
        XCTAssertFalse(opaque.isTranslucent)
        let translucent = try XCTUnwrap(RerunImageTranscoder.encode(Self.png(alpha: 128)))
        XCTAssertEqual(translucent.mimeType, "image/png")
        XCTAssertTrue(translucent.isTranslucent)
        // Anything else (the capture's WebP, here a TIFF) is re-encoded to a core format.
        let tiff = try XCTUnwrap(RerunImageTranscoder.encode(Self.encode(Self.image(alpha: 255), as: "public.tiff")))
        XCTAssertEqual(tiff.mimeType, "image/jpeg")
        XCTAssertTrue(tiff.data.starts(with: [0xFF, 0xD8, 0xFF]))
        XCTAssertNil(RerunImageTranscoder.encode(Data([1, 2, 3])))
    }

    func testGLBUntexturedPlaneIsATranslucentTint() throws {
        let (document, _) = try Self.parseGLB(RerunGLBWriter.data(for: Self.scene()))
        let primitive = try Self.primitive(document, node: "world/planes/2")
        XCTAssertNil((primitive["attributes"] as? [String: Int])?["TEXCOORD_0"])
        let materials = try XCTUnwrap(document["materials"] as? [[String: Any]])
        let material = materials[try XCTUnwrap(primitive["material"] as? Int)]
        XCTAssertEqual(material["alphaMode"] as? String, "BLEND")
        let color = try XCTUnwrap((material["pbrMetallicRoughness"] as? [String: Any])?["baseColorFactor"] as? [Double])
        XCTAssertEqual(color.count, 4)
        XCTAssertLessThan(color[3], 1)
    }

    func testGLBBareAnchorGetsAPoseAndAMarker() throws {
        let scene = Self.scene()
        let (document, _) = try Self.parseGLB(RerunGLBWriter.data(for: scene))
        let anchor = try Self.node(document, named: "world/anchors/7")
        XCTAssertEqual(anchor["translation"] as? [Double], [0.5, 0, -1])
        let rotation = try XCTUnwrap(anchor["rotation"] as? [Double])
        let expected = scene.anchors[0].orientation.vector
        for axis in 0..<4 { XCTAssertEqual(rotation[axis], Double(expected[axis]), accuracy: 1e-6) }
        XCTAssertNotNil(anchor["mesh"] as? Int)
        XCTAssertNil(anchor["children"])
    }

    func testGLBMissingModelFallsBackToTheMarker() throws {
        var scene = Self.scene()
        scene.anchors[0].modelName = "shiba"
        let (document, _) = try Self.parseGLB(RerunGLBWriter.data(for: scene))
        let anchor = try Self.node(document, named: "world/anchors/7")
        XCTAssertNotNil(anchor["mesh"] as? Int)
        let extras = try XCTUnwrap(anchor["extras"] as? [String: Any])
        XCTAssertEqual(extras["model"] as? String, "shiba")
        XCTAssertEqual(extras["modelEmbedded"] as? Bool, false)
    }

    func testGLBMergesAModelUnderEveryAnchorSharingItsData() throws {
        var scene = Self.scene()
        scene.anchors = [
            .init(id: 1, position: SIMD3(1, 0, 0), orientation: simd_quatf(ix: 0, iy: 0, iz: 0, r: 1), modelName: "toy"),
            .init(id: 2, position: SIMD3(-1, 0, 0), orientation: simd_quatf(ix: 0, iy: 0, iz: 0, r: 1), modelName: "toy"),
        ]
        let model = try Self.syntheticModel()
        let plain = try Self.parseGLB(RerunGLBWriter.data(for: scene)).0
        let (document, bin) = try Self.parseGLB(RerunGLBWriter.data(for: scene, models: ["toy": model.glb]))

        // Resources merged once: one mesh, one image, one material.
        func count(_ key: String, _ json: [String: Any]) -> Int { (json[key] as? [Any])?.count ?? 0 }
        XCTAssertEqual(count("meshes", document), count("meshes", plain) - 1 + 1)  // marker out, toy in
        XCTAssertEqual(count("images", document), count("images", plain) + 1)
        XCTAssertEqual(count("materials", document), count("materials", plain) - 1 + 1)  // anchor out, toy in

        // Two copies of the hierarchy, one per anchor, pointing at the same mesh.
        let nodes = try XCTUnwrap(document["nodes"] as? [[String: Any]])
        var meshesSeen: [Int] = []
        for id in [1, 2] {
            let anchor = try Self.node(document, named: "world/anchors/\(id)")
            XCTAssertNil(anchor["mesh"])
            XCTAssertEqual((anchor["extras"] as? [String: Any])?["modelEmbedded"] as? Bool, true)
            let wrapper = nodes[try XCTUnwrap((anchor["children"] as? [Int])?.first)]
            XCTAssertEqual(wrapper["name"] as? String, "toy")
            let body = nodes[try XCTUnwrap((wrapper["children"] as? [Int])?.first)]
            XCTAssertEqual(body["name"] as? String, "Body")
            XCTAssertEqual(body["translation"] as? [Double], [0, 0.25, 0])
            let head = nodes[try XCTUnwrap((body["children"] as? [Int])?.first)]
            XCTAssertEqual(head["name"] as? String, "Head")
            meshesSeen.append(try XCTUnwrap(head["mesh"] as? Int))
        }
        XCTAssertEqual(meshesSeen[0], meshesSeen[1])

        // Its data survives byte for byte: positions, bounds, image.
        let meshes = try XCTUnwrap(document["meshes"] as? [[String: Any]])
        let primitive = try XCTUnwrap((meshes[meshesSeen[0]]["primitives"] as? [[String: Any]])?.first)
        let attributes = try XCTUnwrap(primitive["attributes"] as? [String: Int])
        let positions = try Self.vectors(document, bin, attributes["POSITION"])
        XCTAssertEqual(positions, model.positions)
        // The model's own bounds are copied as it wrote them.
        let accessors = try XCTUnwrap(document["accessors"] as? [[String: Any]])
        let toyAccessor = accessors[try XCTUnwrap(attributes["POSITION"])]
        XCTAssertEqual(toyAccessor["max"] as? [Double], [0.1, 0.2, 0.05])
        let materials = try XCTUnwrap(document["materials"] as? [[String: Any]])
        let material = materials[try XCTUnwrap(primitive["material"] as? Int)]
        XCTAssertEqual(material["name"] as? String, "ToyMaterial")
        let pbr = try XCTUnwrap(material["pbrMetallicRoughness"] as? [String: Any])
        let textureInfo = try XCTUnwrap(pbr["baseColorTexture"] as? [String: Any])
        XCTAssertNotNil((textureInfo["extensions"] as? [String: Any])?["KHR_texture_transform"])
        let textures = try XCTUnwrap(document["textures"] as? [[String: Any]])
        let texture = textures[try XCTUnwrap(textureInfo["index"] as? Int)]
        let images = try XCTUnwrap(document["images"] as? [[String: Any]])
        let image = images[try XCTUnwrap(texture["source"] as? Int)]
        XCTAssertEqual(image["name"] as? String, "ToyImage")
        XCTAssertEqual(try Self.bufferView(document, bin, image["bufferView"] as? Int), model.image)
        let samplers = try XCTUnwrap(document["samplers"] as? [[String: Any]])
        XCTAssertEqual(samplers[try XCTUnwrap(texture["sampler"] as? Int)]["wrapS"] as? Int, 33648)

        // Its extensions: mergeable ones kept (required too), unknown optional ones stripped.
        let used = try XCTUnwrap(document["extensionsUsed"] as? [String])
        XCTAssertTrue(used.contains("KHR_texture_transform"))
        XCTAssertFalse(used.contains("EXT_unknown_thing"))
        XCTAssertEqual(document["extensionsRequired"] as? [String], ["KHR_texture_transform"])
        XCTAssertNil(material["extensions"])
        let credits = try XCTUnwrap((document["asset"] as? [String: Any]).flatMap { ($0["extras"] as? [String: Any])?["credits"] as? [[String: Any]] })
        XCTAssertEqual(credits.first?["model"] as? String, "toy")
    }

    func testGLBModelRequiringAnUnknownExtensionFallsBackToTheMarker() throws {
        var scene = Self.scene()
        scene.anchors[0].modelName = "toy"
        let model = try Self.syntheticModel(required: ["EXT_unknown_thing"])
        let (document, _) = try Self.parseGLB(RerunGLBWriter.data(for: scene, models: ["toy": model.glb]))
        let anchor = try Self.node(document, named: "world/anchors/7")
        XCTAssertNotNil(anchor["mesh"] as? Int)
        XCTAssertEqual((anchor["extras"] as? [String: Any])?["modelEmbedded"] as? Bool, false)
        XCTAssertNil(document["extensionsRequired"])
        XCTAssertFalse(Self.nodeNames(document).contains("Head"))
    }

    func testGLBRejectsAMalformedModel() {
        var scene = Self.scene()
        scene.anchors[0].modelName = "toy"
        XCTAssertThrowsError(try RerunGLBWriter.data(for: scene, models: ["toy": Data("nope".utf8)])) { error in
            guard case .invalidModel(let name, _)? = error as? RerunGLBWriter.Failure else {
                return XCTFail("unexpected \(error)")
            }
            XCTAssertEqual(name, "toy")
        }
    }

    // MARK: Fixtures

    /// 40 coloured points, a 3-sample path, 2 keyframes, a textured floor, an untextured wall
    /// and a bare anchor.
    static func scene() -> RerunExportScene {
        let points = (0..<40).map { index in
            SIMD3<Float>(Float(index) * 0.1 - 2, sin(Float(index)) * 0.5, Float(index % 7) * -0.3)
        }
        let colors = (0..<40).map { (index: Int) -> SIMD3<UInt8> in
            let red = UInt8(index * 6)
            let green = UInt8(255 - index * 3)
            let blue = UInt8((index * 37) % 256)
            return SIMD3(red, green, blue)
        }
        let identity = simd_quatf(ix: 0, iy: 0, iz: 0, r: 1)
        let path = [
            RerunExportScene.CameraSample(time: 0, position: SIMD3(0, 1.4, 0), orientation: identity),
            RerunExportScene.CameraSample(time: 0.5, position: SIMD3(0.2, 1.5, -0.3), orientation: identity),
            RerunExportScene.CameraSample(
                time: 1, position: SIMD3(0.4, 1.45, -0.7),
                orientation: simd_quatf(angle: 0.4, axis: SIMD3(0, 1, 0))
            ),
        ]
        let floor: [SIMD3<Float>] = [SIMD3(-1, 0, -2), SIMD3(1, 0, -2), SIMD3(1, 0, 0), SIMD3(-1, 0, 0)]
        let wall: [SIMD3<Float>] = [SIMD3(-1, 0, -2), SIMD3(-1, 2, -2), SIMD3(1, 2, -2), SIMD3(1, 0, -2)]
        return RerunExportScene(
            title: "Test room",
            lens: .init(width: 480, height: 640, fx: 463.5, fy: 463.5, cx: 240, cy: 320),
            points: points,
            pointColors: colors,
            cameraPath: path,
            keyframes: [
                .init(time: 0, imagePath: "frames/000.webp", pose: path[0]),
                .init(time: 1, imagePath: "frames/001.webp", pose: path[2]),
            ],
            images: [:],
            planes: [
                .init(
                    id: 1, kind: "horizontal_upward", polygon: floor,
                    texture: .init(
                        imageData: png(alpha: 255), origin: SIMD3(-1, 0, -2),
                        u: SIMD3(2, 0, 0), v: SIMD3(0, 0, 2)
                    )
                ),
                .init(id: 2, kind: "vertical", polygon: wall, texture: nil),
            ],
            anchors: [
                .init(
                    id: 7, position: SIMD3(0.5, 0, -1),
                    orientation: simd_quatf(angle: 0.8, axis: SIMD3(0, 1, 0)), modelName: nil
                ),
            ]
        )
    }

    /// A GLB with its own buffer, an embedded PNG, a `Body > Head` hierarchy, a texture
    /// transform, and an unknown optional extension on its material.
    static func syntheticModel(
        required: [String] = ["KHR_texture_transform"]
    ) throws -> (glb: Data, positions: [[Float]], image: Data) {
        let positions: [[Float]] = [[0, 0, 0], [0.1, 0, 0], [0, 0.2, 0.05]]
        var bin = Data()
        for vector in positions {
            for value in vector { withUnsafeBytes(of: value.bitPattern.littleEndian) { bin.append(contentsOf: $0) } }
        }
        let positionLength = bin.count
        bin.append(contentsOf: [0, 0, 0])  // Deliberately misaligns the next view.
        let imageOffset = bin.count
        let image = png(alpha: 255)
        bin.append(image)
        while bin.count % 4 != 0 { bin.append(0) }
        let json: [String: Any] = [
            "asset": ["version": "2.0", "generator": "test", "extras": ["license": "CC0"]],
            "scene": 0,
            "scenes": [["nodes": [0]]],
            "nodes": [
                ["name": "Body", "translation": [0, 0.25, 0], "children": [1]],
                ["name": "Head", "mesh": 0],
            ],
            "meshes": [["primitives": [["attributes": ["POSITION": 0], "material": 0, "mode": 4]]]],
            "materials": [[
                "name": "ToyMaterial",
                "pbrMetallicRoughness": [
                    "baseColorTexture": [
                        "index": 0,
                        "extensions": ["KHR_texture_transform": ["scale": [2, 2]]],
                    ],
                ],
                "extensions": ["EXT_unknown_thing": ["value": 1]],
            ]],
            "textures": [["source": 0, "sampler": 0]],
            "samplers": [["wrapS": 33648, "wrapT": 33648]],
            "images": [["name": "ToyImage", "mimeType": "image/png", "bufferView": 1]],
            "accessors": [[
                "bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3",
                "min": [0, 0, 0], "max": [0.1, 0.2, 0.05],
            ]],
            "bufferViews": [
                ["buffer": 0, "byteOffset": 0, "byteLength": positionLength],
                ["buffer": 0, "byteOffset": imageOffset, "byteLength": image.count],
            ],
            "buffers": [["byteLength": bin.count]],
            "extensionsUsed": ["KHR_texture_transform", "EXT_unknown_thing"] + required,
            "extensionsRequired": required,
        ]
        var chunk = try JSONSerialization.data(withJSONObject: json)
        while chunk.count % 4 != 0 { chunk.append(0x20) }
        var glb = Data()
        func word(_ value: UInt32) { withUnsafeBytes(of: value.littleEndian) { glb.append(contentsOf: $0) } }
        word(0x4654_6C67)
        word(2)
        word(UInt32(12 + 8 + chunk.count + 8 + bin.count))
        word(UInt32(chunk.count))
        word(0x4E4F_534A)
        glb.append(chunk)
        word(UInt32(bin.count))
        word(0x004E_4942)
        glb.append(bin)
        return (glb, positions, image)
    }

    /// An 8×8 RGBA image, every pixel at `alpha`.
    static func image(alpha: UInt8) -> CGImage {
        let space = CGColorSpace(name: CGColorSpace.sRGB)!
        let context = CGContext(
            data: nil, width: 8, height: 8, bitsPerComponent: 8, bytesPerRow: 32, space: space,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )!
        context.setFillColor(CGColor(srgbRed: 0.9, green: 0.4, blue: 0.1, alpha: CGFloat(alpha) / 255))
        context.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
        return context.makeImage()!
    }

    static func png(alpha: UInt8) -> Data { encode(image(alpha: alpha), as: "public.png") }

    static func encode(_ image: CGImage, as type: String) -> Data {
        let output = NSMutableData()
        let destination = CGImageDestinationCreateWithData(output as CFMutableData, type as CFString, 1, nil)!
        CGImageDestinationAddImage(destination, image, nil)
        CGImageDestinationFinalize(destination)
        return output as Data
    }

    // MARK: Parsing

    struct ParseError: Error, CustomStringConvertible { var description: String }

    static func word(_ data: Data, _ offset: Int) -> UInt32 {
        let bytes = [UInt8](data[data.startIndex + offset..<data.startIndex + offset + 4])
        return UInt32(bytes[0]) | UInt32(bytes[1]) << 8 | UInt32(bytes[2]) << 16 | UInt32(bytes[3]) << 24
    }

    static func float(_ data: Data, _ offset: Int) -> Float { Float(bitPattern: word(data, offset)) }

    /// The vertex count and the binary records after `end_header`.
    static func parsePLY(_ data: Data) throws -> (count: Int, records: Data) {
        guard let end = data.range(of: Data("end_header\n".utf8)) else {
            throw ParseError(description: "no end_header")
        }
        let header = String(decoding: data[..<end.upperBound], as: UTF8.self)
        guard header.hasPrefix("ply\nformat binary_little_endian 1.0\n"),
              let line = header.split(separator: "\n").first(where: { $0.hasPrefix("element vertex ") }),
              let count = Int(line.dropFirst("element vertex ".count)) else {
            throw ParseError(description: "bad header")
        }
        let records = Data(data[end.upperBound...])
        guard records.count == count * 15 else { throw ParseError(description: "bad body size") }
        return (count, records)
    }

    /// The JSON document and the BIN chunk (empty when absent).
    static func parseGLB(_ data: Data) throws -> ([String: Any], Data) {
        let jsonLength = Int(word(data, 12))
        let json = data.subdata(in: 20..<20 + jsonLength)
        guard let document = try JSONSerialization.jsonObject(with: json) as? [String: Any] else {
            throw ParseError(description: "JSON is not an object")
        }
        let binStart = 20 + jsonLength
        guard binStart < data.count else { return (document, Data()) }
        let binLength = Int(word(data, binStart))
        return (document, data.subdata(in: binStart + 8..<binStart + 8 + binLength))
    }

    static func nodeNames(_ document: [String: Any]) -> [String] {
        (document["nodes"] as? [[String: Any]] ?? []).compactMap { $0["name"] as? String }
    }

    static func node(_ document: [String: Any], named name: String) throws -> [String: Any] {
        let nodes = document["nodes"] as? [[String: Any]] ?? []
        guard let node = nodes.first(where: { $0["name"] as? String == name }) else {
            throw ParseError(description: "no node \(name)")
        }
        return node
    }

    static func primitive(_ document: [String: Any], node name: String) throws -> [String: Any] {
        let mesh = try XCTUnwrap(try node(document, named: name)["mesh"] as? Int)
        let meshes = try XCTUnwrap(document["meshes"] as? [[String: Any]])
        return try XCTUnwrap((meshes[mesh]["primitives"] as? [[String: Any]])?.first)
    }

    static func bufferView(_ document: [String: Any], _ bin: Data, _ index: Int?) throws -> Data {
        let views = try XCTUnwrap(document["bufferViews"] as? [[String: Any]])
        let view = views[try XCTUnwrap(index)]
        let offset = view["byteOffset"] as? Int ?? 0
        let length = try XCTUnwrap(view["byteLength"] as? Int)
        return bin.subdata(in: offset..<offset + length)
    }

    /// A float accessor's values, one array per element.
    static func vectors(_ document: [String: Any], _ bin: Data, _ index: Int?) throws -> [[Float]] {
        let accessors = try XCTUnwrap(document["accessors"] as? [[String: Any]])
        let accessor = accessors[try XCTUnwrap(index)]
        XCTAssertEqual(accessor["componentType"] as? Int, 5126)
        let width = ["SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4][accessor["type"] as? String ?? ""] ?? 0
        let count = try XCTUnwrap(accessor["count"] as? Int)
        let data = try bufferView(document, bin, accessor["bufferView"] as? Int)
        let start = accessor["byteOffset"] as? Int ?? 0
        XCTAssertLessThanOrEqual(start + count * width * 4, data.count)
        return (0..<count).map { element in
            (0..<width).map { component in float(data, start + (element * width + component) * 4) }
        }
    }

    static func indices(_ document: [String: Any], _ bin: Data, _ index: Int?) throws -> [Int] {
        let accessors = try XCTUnwrap(document["accessors"] as? [[String: Any]])
        let accessor = accessors[try XCTUnwrap(index)]
        let count = try XCTUnwrap(accessor["count"] as? Int)
        let data = try bufferView(document, bin, accessor["bufferView"] as? Int)
        let bytes = [UInt8](data)
        switch accessor["componentType"] as? Int {
        case 5123: return (0..<count).map { Int(bytes[$0 * 2]) | Int(bytes[$0 * 2 + 1]) << 8 }
        case 5125: return (0..<count).map { Int(word(data, $0 * 4)) }
        default: throw ParseError(description: "not an index accessor")
        }
    }

    static func assertBounds(
        _ document: [String: Any], _ index: Int?, of values: [SIMD3<Float>],
        file: StaticString = #filePath, line: UInt = #line
    ) throws {
        let accessors = try XCTUnwrap(document["accessors"] as? [[String: Any]])
        let accessor = accessors[try XCTUnwrap(index)]
        XCTAssertEqual(accessor["count"] as? Int, values.count, file: file, line: line)
        let low = values.reduce(values[0]) { simd_min($0, $1) }
        let high = values.reduce(values[0]) { simd_max($0, $1) }
        XCTAssertEqual(accessor["min"] as? [Double], [low.x, low.y, low.z].map(Double.init), file: file, line: line)
        XCTAssertEqual(accessor["max"] as? [Double], [high.x, high.y, high.z].map(Double.init), file: file, line: line)
    }
}
