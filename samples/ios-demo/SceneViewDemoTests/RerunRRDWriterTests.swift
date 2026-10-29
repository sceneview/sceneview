import CoreGraphics
import CryptoKit
import Foundation
import ImageIO
import XCTest
import simd
@testable import SceneViewDemo

/// The `.rrd` writer, read back with a small independent parser: stream header, message
/// framing, protobuf envelopes, Arrow IPC messages and the flatbuffer schemas inside.
final class RerunRRDWriterTests: XCTestCase {

    private let recordingId = UUID(uuidString: "3F2504E0-4F89-11D3-9A0C-0305E82C3301")!

    // MARK: - Framing

    func testStreamStartsWithTheRRF2HeaderForRerun0_38_1() throws {
        let data = try RerunRRDWriter.data(for: scene(), recordingId: recordingId)
        XCTAssertEqual(Array(data.prefix(12)), Array("RRF2".utf8) + [0, 38, 1, 0] + [0, 2, 0, 0])
    }

    /// The layout's store, its chunks and its activation, then the recording's store and
    /// chunks, then `End`: the order Android and the Rerun SDKs write.
    func testMessagesTileTheFileBlueprintFirstAndEndWithTheFooter() throws {
        let data = try RerunRRDWriter.data(for: scene(), recordingId: recordingId)
        let messages = try Self.messages(in: data)
        let kinds = messages.map(\.kind)
        let activation = try XCTUnwrap(kinds.firstIndex(of: 3))
        XCTAssertEqual(kinds.first, 1, "the blueprint's SetStoreInfo first")
        XCTAssertGreaterThan(activation, 1)
        XCTAssertTrue(kinds[1..<activation].allSatisfy { $0 == 2 }, "then the layout's chunks")
        XCTAssertEqual(kinds[activation + 1], 1, "then the recording's SetStoreInfo")
        XCTAssertTrue(kinds[(activation + 2)..<(kinds.count - 1)].allSatisfy { $0 == 2 }, "then its chunks")
        XCTAssertEqual(kinds.last, 0, "End last")
        let storeKinds = try messages.filter { $0.kind == 1 }.map { try Self.storeKind(ofStoreInfo: $0.payload) }
        XCTAssertEqual(storeKinds, [2, 1])
        let layoutStores = try messages[1..<activation].map { try Self.storeKind(ofArrowMessage: $0.payload) }
        XCTAssertEqual(Set(layoutStores), [2], "layout chunks go to the blueprint store")
    }

    /// `BlueprintActivationCommand`: the blueprint store, `make_default`, not `make_active`.
    func testBlueprintIsActivatedAsTheDefaultLayout() throws {
        let messages = try Self.messages(in: RerunRRDWriter.data(for: scene(), recordingId: recordingId))
        let command = try Self.fields(XCTUnwrap(messages.first { $0.kind == 3 }).payload)
        let storeId = try Self.fields(XCTUnwrap(command[1]?.first?.bytes))
        XCTAssertEqual(storeId[1]?.first?.varint, 2, "StoreKind.Blueprint")
        XCTAssertEqual(storeId[2]?.first?.string, RerunRRDBlueprint.storeId(recordingId))
        XCTAssertNil(command[2], "make_active unset")
        XCTAssertEqual(command[3]?.first?.varint, 1, "make_default")
        let entities = try messages.prefix { $0.kind != 3 }.filter { $0.kind == 2 }
            .map { try Self.chunk(from: $0.payload).entityPath }
        XCTAssertEqual(entities.filter { $0.hasPrefix("/view/") }.count, 4, "Room and Camera, each with its contents")
        XCTAssertTrue(entities.contains("/viewport"))
    }

    func testStoreInfoNamesTheApplicationAndRecording() throws {
        let messages = try Self.messages(in: RerunRRDWriter.data(
            for: scene(), applicationId: "my_app", recordingId: recordingId
        ))
        let info = try Self.fields(XCTUnwrap(messages.filter { $0.kind == 1 }.last).payload)
        let storeInfo = try Self.fields(XCTUnwrap(info[2]?.first?.bytes))
        let storeId = try Self.fields(XCTUnwrap(storeInfo[2]?.first?.bytes))
        XCTAssertEqual(storeId[1]?.first?.varint, 1, "StoreKind.Recording")
        XCTAssertEqual(storeId[2]?.first?.string, recordingId.uuidString.lowercased())
        let application = try Self.fields(XCTUnwrap(storeId[3]?.first?.bytes))
        XCTAssertEqual(application[1]?.first?.string, "my_app")
        let version = try Self.fields(XCTUnwrap(storeInfo[6]?.first?.bytes))
        XCTAssertEqual(version[1]?.first?.varint, 0x01_26_00, "crate_version_bits of 0.38.1")
    }

    func testOutputIsDeterministicForOneRecordingId() throws {
        let scene = scene()
        XCTAssertEqual(
            try RerunRRDWriter.data(for: scene, recordingId: recordingId),
            try RerunRRDWriter.data(for: scene, recordingId: recordingId)
        )
        XCTAssertNotEqual(
            try RerunRRDWriter.data(for: scene, recordingId: recordingId),
            try RerunRRDWriter.data(for: scene, recordingId: UUID())
        )
    }

    // MARK: - Chunks

    func testEveryChunkIsAWellFormedArrowStreamWithOneBatch() throws {
        for chunk in try chunks(of: scene()) {
            XCTAssertEqual(chunk.messageTypes, [1, 3], "schema then record batch")
            XCTAssertEqual(chunk.compression, 1, "COMPRESSION_NONE")
            XCTAssertEqual(chunk.encoding, 1, "ENCODING_ARROW_IPC")
            XCTAssertEqual(chunk.uncompressedSize, chunk.ipcLength)
            XCTAssertEqual(chunk.schema["sorbet:version"], "0.1.3")
            XCTAssertEqual(chunk.schema["rerun:id"], "chunk_\(chunk.chunkId)")
            XCTAssertEqual(chunk.fieldNames.first, "rerun.controls.RowId")
        }
    }

    func testEntitiesMatchTheSceneContents() throws {
        let chunks = try chunks(of: scene())
        XCTAssertEqual(Set(chunks.map(\.entityPath)), [
            "/__properties", "/world", "/world/points", "/world/camera", "/world/camera_path",
            "/world/planes/7", "/world/anchors/3",
        ])
        let fields = { (path: String) in Set(chunks.filter { $0.entityPath == path }.flatMap(\.fieldNames)) }
        XCTAssertTrue(fields("/world/points").isSuperset(of: ["Points3D:positions", "Points3D:colors", "Points3D:radii"]))
        XCTAssertTrue(fields("/world/camera").isSuperset(of: [
            "time", "Transform3D:translation", "Transform3D:quaternion", "Pinhole:image_from_camera",
            "EncodedImage:blob", "EncodedImage:media_type",
        ]))
        XCTAssertTrue(fields("/world/planes/7").isSuperset(of: ["LineStrips3D:strips", "Mesh3D:albedo_texture_buffer"]))
        XCTAssertTrue(fields("/world/anchors/3").isSuperset(of: ["Transform3D:translation", "Boxes3D:labels"]))
    }

    func testStaticChunksHaveNoTimelineAndTemporalOnesAreSorted() throws {
        for chunk in try chunks(of: scene()) {
            XCTAssertEqual(chunk.isStatic, !chunk.fieldNames.contains("time"), chunk.entityPath)
        }
        let poses = try XCTUnwrap(try chunks(of: scene()).first { $0.fieldNames.contains("Transform3D:translation") && !$0.isStatic })
        XCTAssertEqual(poses.rowCount, 4, "three path samples and one keyframe pose")
    }

    func testLivePointsHaveSortedNanosecondRowsAndAndroidComponentNames() throws {
        var recorded = scene()
        recorded.pointObservations = [
            .init(time: 1.25, points: [1, 2]),
            .init(time: 0.5, points: [-1, 0, 99]),
            .init(time: 0.75, points: []),
        ]
        let live = try XCTUnwrap(RerunRRDWriter.chunks(for: recorded).first { $0.entityPath == "/world/points/live" })
        XCTAssertEqual(live.times, [500_000_000, 1_250_000_000])
        let parsed = try XCTUnwrap(try chunks(of: recorded).first { $0.entityPath == "/world/points/live" })
        XCTAssertFalse(parsed.isStatic)
        XCTAssertEqual(parsed.rowCount, 2)
        XCTAssertEqual(parsed.fieldNames, ["rerun.controls.RowId", "time", "Points3D:colors", "Points3D:positions", "Points3D:radii"])
        XCTAssertEqual(live.components.map(\.componentType), ["Position3D", "Radius", "Color"])
    }

    func testEveryPhotoIsWrittenWithItsPoseAndKeyframesRemainAFallback() throws {
        var recorded = scene()
        let keyframe = try XCTUnwrap(recorded.keyframes.first)
        recorded.photos = [0.0, 0.25, 1.0].map { time in
            var photo = keyframe
            photo.time = time
            photo.pose.time = time
            return photo
        }
        let chunks = try chunks(of: recorded)
        let images = try XCTUnwrap(chunks.first { $0.fieldNames.contains("EncodedImage:blob") })
        XCTAssertEqual(images.rowCount, 3)
        let poses = try XCTUnwrap(chunks.first { $0.fieldNames.contains("Transform3D:translation") && !$0.isStatic })
        XCTAssertEqual(poses.rowCount, 6)
        recorded.photos = []
        XCTAssertEqual(try self.chunks(of: recorded).first { $0.fieldNames.contains("EncodedImage:blob") }?.rowCount, 1)
    }

    // MARK: - Byte compatibility with Android
    //
    // `Fixtures/android-cross-platform.rrd` is a file the Android writer produced
    // (`RerunRrdCrossPlatformFixtureTest`, which also fails when Android drifts from it) for
    // the scene `crossPlatformScene()` builds here. The iOS writer must give the same bytes,
    // the store source label aside.

    private static let fixtures = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .appendingPathComponent("Fixtures")

    private static func androidFixture() throws -> Data {
        try Data(contentsOf: fixtures.appendingPathComponent("android-cross-platform.rrd"))
    }

    /// `RerunRrdCrossPlatformFixtureTest.scene()`, value for value.
    static func crossPlatformScene() throws -> RerunExportScene {
        let identity = simd_quatf(ix: 0, iy: 0, iz: 0, r: 1)
        let turned = simd_quatf(ix: 0, iy: 0.6, iz: 0, r: 0.8)
        let photoPose = RerunExportScene.CameraSample(time: 0.75, position: SIMD3(0.25, 1.5, -0.5), orientation: turned)
        let photo = try Data(contentsOf: fixtures.appendingPathComponent("cross-platform-photo.png"))
        return RerunExportScene(
            title: "Cross-platform room",
            lens: .init(width: 960, height: 1280, fx: 1000, fy: 1001, cx: 480, cy: 640),
            points: [SIMD3(0, 0, 0), SIMD3(1, 0, 0), SIMD3(0, 1, 0)],
            pointColors: [SIMD3(255, 0, 0), SIMD3(0, 255, 0), SIMD3(0, 0, 255)],
            cameraPath: [
                .init(time: 0, position: SIMD3(0, 1.5, 0), orientation: identity),
                .init(time: 0.5, position: SIMD3(0.125, 1.5, -0.25), orientation: identity),
                .init(time: 1, position: SIMD3(0.5, 1.5, -1), orientation: turned),
            ],
            keyframes: [],
            images: ["frames/000.png": photo],
            planes: [.init(
                id: 1, kind: "horizontal_upward",
                polygon: [SIMD3(-1, 0, -1), SIMD3(1, 0, -1), SIMD3(1, 0, 1), SIMD3(-1, 0, 1)],
                texture: nil
            )],
            anchors: [.init(id: 7, position: SIMD3(0.5, 0, -0.5), orientation: identity, modelName: "shiba")],
            photos: [.init(time: 0.75, imagePath: "frames/000.png", pose: photoPose)],
            pointObservations: [.init(time: 1.25, points: [1, 2]), .init(time: 0.5, points: [0])],
            dense: RerunDenseCloud(
                positions: [SIMD3(0, 0, 0), SIMD3(1, 2, 3)], colors: [SIMD3(10, 20, 30), SIMD3(255, 0, 0)], voxelM: 0.03
            )
        )
    }

    /// The whole file — header, layout, recording, footer — byte for byte as Android wrote it.
    func testCrossPlatformSceneIsTheAndroidFileByteForByte() throws {
        let android = try Self.androidFixture()
        let ios = try RerunRRDWriter.data(for: Self.crossPlatformScene(), recordingId: recordingId, source: "SceneView Android")
        XCTAssertEqual(ios.count, android.count)
        if let first = zip(ios, android).enumerated().first(where: { $0.element.0 != $0.element.1 })?.offset {
            XCTFail("first differing byte at \(first) of \(android.count)")
        }
    }

    /// The store source label is the only difference iOS keeps: `SceneView iOS`.
    func testStoreSourceLabelIsSceneViewIOS() throws {
        let ios = try RerunRRDWriter.data(for: Self.crossPlatformScene(), recordingId: recordingId)
        let infos = try Self.messages(in: ios).filter { $0.kind == 1 }.map { try Self.fields($0.payload) }
        XCTAssertEqual(infos.count, 2)
        for message in infos {
            let storeInfo = try Self.fields(XCTUnwrap(message[2]?.first?.bytes))
            let source = try Self.fields(XCTUnwrap(storeInfo[5]?.first?.bytes))
            let other = try Self.fields(XCTUnwrap(source[2]?.first?.bytes))
            XCTAssertEqual(other[1]?.first?.string, "SceneView iOS")
        }
    }

    /// Both files, Android's and the iOS one, read back through the iOS reader as one recording.
    func testAndroidAndIOSFilesReadBackAsTheSameRecording() throws {
        let android = try RerunRRDReader.recording(from: Self.androidFixture())
        let ios = try RerunRRDReader.recording(from: RerunRRDWriter.data(for: Self.crossPlatformScene(), recordingId: recordingId))
        XCTAssertEqual(android, ios)
        XCTAssertEqual(android.title, "Cross-platform room")
        let pack = try RerunPack.load(manifest: android.pack.manifest, log: android.pack.log,
                                      media: android.pack.media, title: android.title ?? "")
        let replayed = RerunExportAdapter.scene(for: pack)
        XCTAssertEqual(replayed.cameraPath.map(\.time), [0, 0.5, 1])
        XCTAssertEqual(replayed.photos.map(\.time), [0.75])
        XCTAssertEqual(replayed.points, [SIMD3(0, 0, 0), SIMD3(1, 0, 0), SIMD3(0, 1, 0)])
        XCTAssertEqual(replayed.planes.map(\.id), [1])
        XCTAssertEqual(replayed.anchors.map(\.id), [7])
        // The pack stores the dense cloud quantised: positions come back within a micrometre.
        let dense = try XCTUnwrap(replayed.dense?.positions)
        XCTAssertEqual(dense.count, 2)
        for (a, b) in zip(dense, [SIMD3<Float>(0, 0, 0), SIMD3(1, 2, 3)]) { XCTAssertLessThan(simd_distance(a, b), 1e-6) }
    }

    // MARK: - Footer

    /// The `StreamFooter` points at the `End` payload and checks it; the `RrdFooter` holds
    /// the layout's manifest, then the recording's, each with its schema's SHA-256 and a row
    /// per chunk at the offset and size the chunk really has.
    func testFooterIndexesEveryChunkOfBothStores() throws {
        let data = try RerunRRDWriter.data(for: Self.crossPlatformScene(), recordingId: recordingId)
        let bytes = [UInt8](data)
        let messages = try Self.messages(in: data)
        let end = try XCTUnwrap(messages.last)
        XCTAssertEqual(end.kind, 0)

        let tail = Array(bytes.suffix(32))
        XCTAssertEqual(Int(Self.le(tail, 0, 8)), end.offset, "payload offset")
        XCTAssertEqual(Int(Self.le(tail, 8, 8)), end.payload.count, "payload length")
        XCTAssertEqual(UInt32(Self.le(tail, 16, 4)), RerunXxHash32.hash(end.payload, seed: 7_850_921), "checksum")
        XCTAssertEqual(Array(tail[20..<28]), Array("RRF2FOOT".utf8))
        XCTAssertEqual(Self.le(tail, 28, 4), 1, "one entry")

        let manifests = try XCTUnwrap(Self.fields(end.payload)[1]).compactMap(\.bytes).map { try Self.fields($0) }
        XCTAssertEqual(manifests.count, 2)
        let kinds = try manifests.map { try Self.fields(XCTUnwrap($0[1]?.first?.bytes))[1]?.first?.varint }
        XCTAssertEqual(kinds, [2, 1], "the layout's, then the recording's")

        let arrows = messages.filter { $0.kind == 2 }
        let stores = try arrows.map { try Self.storeKind(ofArrowMessage: $0.payload) }
        for (manifest, kind) in zip(manifests, [UInt64(2), 1]) {
            let schema = try Self.fields(XCTUnwrap(manifest[2]?.first?.bytes))
            let stream = try XCTUnwrap(schema[1]?.first?.bytes)
            XCTAssertEqual(Array(stream.suffix(8)), [0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 0, 0], "end of stream")
            let sha = try XCTUnwrap(manifest[3]?.first?.bytes)
            XCTAssertEqual(sha, Data(SHA256.hash(data: stream.dropLast(8))), "sorbet_schema_sha256")

            let part = try Self.fields(XCTUnwrap(manifest[4]?.first?.bytes))
            let frame = try XCTUnwrap(part[2]?.first?.bytes)
            XCTAssertEqual(part[4]?.first?.varint, UInt64(frame.count))
            let mine = zip(arrows, stores).filter { $0.1 == kind }.map(\.0)
            XCTAssertFalse(mine.isEmpty)
            // The offset and size columns: one u64 per chunk, contiguous, in file order.
            let offsets = mine.flatMap { Self.le64(UInt64($0.offset)) }
            let sizes = mine.flatMap { Self.le64(UInt64($0.payload.count)) }
            XCTAssertNotNil(frame.range(of: Data(offsets)), "chunk_byte_offset of store \(kind)")
            XCTAssertNotNil(frame.range(of: Data(sizes)), "chunk_byte_size of store \(kind)")
        }
    }

    func testXxHash32GivesTheReferenceValues() {
        func hash(_ text: String, seed: UInt32 = 0) -> UInt32 { RerunXxHash32.hash(Data(text.utf8), seed: seed) }
        XCTAssertEqual(hash(""), 0x02CC_5D05)
        XCTAssertEqual(hash("a"), 0x550D_7456)
        XCTAssertEqual(hash("abc"), 0x32D1_53FF)
        XCTAssertEqual(hash("Nobody inspects the spammish repetition"), 0xE229_3B2F)
        XCTAssertEqual(hash("xxhash", seed: 20_141_025), 0x67BB_40DD)
    }

    /// A plane photo with a texel that is not opaque is written as straight RGBA
    /// (`ColorModel` 3); an opaque one stays RGB (2). Both are 8 bits per channel (6).
    func testPlaneTexturesAreStraightRGBAOnlyWhenATexelIsNotOpaque() throws {
        let texels: [UInt8] = [10, 20, 30, 255, 0, 0, 0, 0, 100, 150, 200, 51, 240, 80, 40, 255]
        var translucent = scene()
        translucent.planes[0].texture = .init(imageData: try Self.png(rgba: texels, width: 2, height: 2),
                                              origin: .zero, u: SIMD3(1, 0, 0), v: SIMD3(0, 0, 1))
        let rgba = try XCTUnwrap(try chunks(of: translucent).first { $0.entityPath == "/world/planes/7" }).ipc
        XCTAssertNotNil(rgba.range(of: Data(texels)), "the texels, unpremultiplied, top row first")
        XCTAssertNotNil(rgba.range(of: Data(Self.imageFormat(width: 2, height: 2, colorModel: 3))))
        XCTAssertNil(rgba.range(of: Data(Self.imageFormat(width: 2, height: 2, colorModel: 2))))

        let rgb = try XCTUnwrap(try chunks(of: scene()).first { $0.entityPath == "/world/planes/7" }).ipc
        XCTAssertNotNil(rgb.range(of: Data(Self.imageFormat(width: 12, height: 16, colorModel: 2))))
        XCTAssertNil(rgb.range(of: Data(Self.imageFormat(width: 12, height: 16, colorModel: 3))))
    }

    /// The body buffers of one `ImageFormat` struct row, each padded to 8 bytes: `width`,
    /// `height`, the null `pixel_format` (validity, value), `color_model`, `channel_datatype`.
    private static func imageFormat(width: UInt32, height: UInt32, colorModel: UInt8) -> [UInt8] {
        func padded(_ bytes: [UInt8]) -> [UInt8] { bytes + [UInt8](repeating: 0, count: 8 - bytes.count) }
        return padded(le32(width)) + padded(le32(height)) + padded([0]) + padded([0])
            + padded([colorModel]) + padded([6])
    }

    private static func le64(_ value: UInt64) -> [UInt8] { (0..<8).map { UInt8(truncatingIfNeeded: value >> (8 * $0)) } }
    private static func le32(_ value: UInt32) -> [UInt8] { (0..<4).map { UInt8(truncatingIfNeeded: value >> (8 * $0)) } }

    private static func varint(_ value: Int) -> [UInt8] {
        var rest = UInt64(value), out: [UInt8] = []
        while rest >= 0x80 {
            out.append(UInt8(rest & 0x7F) | 0x80)
            rest >>= 7
        }
        return out + [UInt8(rest)]
    }

    private static func png(rgba: [UInt8], width: Int, height: Int) throws -> Data {
        let provider = try XCTUnwrap(CGDataProvider(data: Data(rgba) as CFData))
        let space = try XCTUnwrap(CGColorSpace(name: CGColorSpace.sRGB))
        let image = try XCTUnwrap(CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
                                          bytesPerRow: width * 4, space: space,
                                          bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue),
                                          provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent))
        let output = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(output as CFMutableData, "public.png" as CFString, 1, nil))
        CGImageDestinationAddImage(destination, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return output as Data
    }

    func testRowAndChunkIdsIncreaseAcrossTheFile() throws {
        let ids = try chunks(of: scene()).map(\.chunkId)
        XCTAssertEqual(ids, ids.sorted())
        XCTAssertEqual(Set(ids).count, ids.count)
    }

    func testSceneWithoutPhotosOrLensStillWrites() throws {
        var bare = scene()
        bare.lens = nil
        bare.images = [:]
        bare.planes = bare.planes.map { var plane = $0; plane.texture = nil; return plane }
        let chunks = try chunks(of: bare)
        XCTAssertFalse(chunks.flatMap(\.fieldNames).contains("EncodedImage:blob"))
        XCTAssertFalse(chunks.flatMap(\.fieldNames).contains("Pinhole:resolution"))
        XCTAssertFalse(chunks.flatMap(\.fieldNames).contains("Mesh3D:vertex_positions"))
    }

    func testMismatchedPointColoursThrow() {
        var broken = scene()
        broken.pointColors.removeLast()
        XCTAssertThrowsError(try RerunRRDWriter.data(for: broken, recordingId: recordingId))
    }

    // MARK: - Encoders

    func testColoursPackAsRGBA() {
        XCTAssertEqual(RerunRRDWriter.packedColor(SIMD3(255, 0, 0)), 0xFF00_00FF)
        XCTAssertEqual(RerunRRDWriter.packedColor(SIMD3(1, 2, 3), alpha: 4), 0x0102_0304)
    }

    func testTuidTextFormMatchesRerun() {
        let tuid = RerunTuid(timeNanos: 0xABC, inc: 0xDEF)
        XCTAssertEqual(tuid.description, "0000000000000ABC0000000000000def")
        XCTAssertEqual(Array(tuid.bigEndianBytes), [0, 0, 0, 0, 0, 0, 0x0A, 0xBC, 0, 0, 0, 0, 0, 0, 0x0D, 0xEF])
    }

    func testProtobufVarintsAndNegativeInt32() throws {
        var proto = RerunProtobufWriter()
        proto.uint64(1, 300)
        proto.int32(2, -1)
        XCTAssertEqual(Array(proto.data), [0x08, 0xAC, 0x02, 0x10] + [UInt8](repeating: 0xFF, count: 9) + [0x01])
    }

    func testWebPAndJPEGPhotosEndUpAsJPEGOrPNG() throws {
        let png = try Self.encodedImage(type: "public.png", width: 8, height: 6)
        let photo = try XCTUnwrap(RerunImageCodec.photo(from: png))
        XCTAssertEqual(photo.mediaType, "image/png")
        XCTAssertEqual(photo.data, png, "PNG passes through")
        XCTAssertEqual([photo.width, photo.height], [8, 6])
        let pixels = try XCTUnwrap(RerunImageCodec.rgbPixels(from: png, maxDimension: 4))
        XCTAssertEqual(pixels.channels, 3)
        XCTAssertEqual(pixels.data.count, pixels.width * pixels.height * 3)
        XCTAssertEqual(max(pixels.width, pixels.height), 4)
    }

    // MARK: - Fixture

    private func scene() -> RerunExportScene {
        let jpeg = try! Self.encodedImage(type: "public.jpeg", width: 12, height: 16)
        let pose = { (t: Double) in
            RerunExportScene.CameraSample(time: t, position: SIMD3(Float(t), 1.5, 0), orientation: simd_quatf(angle: 0, axis: SIMD3(0, 1, 0)))
        }
        return RerunExportScene(
            title: "Test room",
            lens: .init(width: 480, height: 640, fx: 463, fy: 463, cx: 240, cy: 320),
            points: [SIMD3(0, 0, 0), SIMD3(1, 0, 0), SIMD3(0, 1, 0)],
            pointColors: [SIMD3(255, 0, 0), SIMD3(0, 255, 0), SIMD3(0, 0, 255)],
            cameraPath: [pose(0), pose(0.5), pose(1)],
            keyframes: [.init(time: 0.5, imagePath: "frames/000.jpg", pose: pose(0.5))],
            images: ["frames/000.jpg": jpeg],
            planes: [.init(
                id: 7, kind: "horizontal_upward",
                polygon: [SIMD3(0, 0, 0), SIMD3(1, 0, 0), SIMD3(1, 0, 1), SIMD3(0, 0, 1)],
                texture: .init(imageData: jpeg, origin: .zero, u: SIMD3(1, 0, 0), v: SIMD3(0, 0, 1))
            )],
            anchors: [.init(id: 3, position: SIMD3(0.5, 0, 0.5), orientation: simd_quatf(ix: 0, iy: 0, iz: 0, r: 1), modelName: "shiba")]
        )
    }

    private static func encodedImage(type: String, width: Int, height: Int) throws -> Data {
        let context = try XCTUnwrap(CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ))
        context.setFillColor(red: 0.2, green: 0.6, blue: 0.9, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let image = try XCTUnwrap(context.makeImage())
        let output = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(output as CFMutableData, type as CFString, 1, nil))
        CGImageDestinationAddImage(destination, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return output as Data
    }

    // MARK: - Reader

    struct Message {
        var kind: UInt64
        var payload: Data
        /// Where the payload starts in the file, after its 16-byte header.
        var offset: Int = 0
    }

    struct ParsedChunk {
        var entityPath: String
        var chunkId: String
        var isStatic: Bool
        var compression: UInt64
        var encoding: UInt64
        var uncompressedSize: Int
        var ipcLength: Int
        var ipc: Data
        var messageTypes: [UInt8]
        var schema: [String: String]
        var fieldNames: [String]
        var rowCount: Int
    }

    struct ReaderError: Error {}

    /// The recording's chunks, without the layout's.
    private func chunks(of scene: RerunExportScene) throws -> [ParsedChunk] {
        try Self.messages(in: RerunRRDWriter.data(for: scene, recordingId: recordingId))
            .filter { try $0.kind == 2 && Self.storeKind(ofArrowMessage: $0.payload) == 1 }
            .map { try Self.chunk(from: $0.payload) }
    }

    /// Every message, `End` included; after `End` exactly the 32-byte `StreamFooter` remains.
    static func messages(in data: Data) throws -> [Message] {
        let bytes = [UInt8](data)
        var position = 12
        var messages: [Message] = []
        while position < bytes.count {
            guard position + 16 <= bytes.count else { throw ReaderError() }
            let kind = le(bytes, position, 8), length = Int(le(bytes, position + 8, 8))
            position += 16
            guard position + length <= bytes.count else { throw ReaderError() }
            messages.append(Message(kind: kind, payload: Data(bytes[position..<position + length]), offset: position))
            position += length
            if kind == 0 {
                guard bytes.count - position == 32 else { throw ReaderError() }
                break
            }
        }
        return messages
    }

    /// `StoreKind` of an `ArrowMsg`'s `store_id`.
    static func storeKind(ofArrowMessage payload: Data) throws -> UInt64 {
        let storeId = try fields(XCTUnwrap(fields(payload)[1]?.first?.bytes))
        return storeId[1]?.first?.varint ?? 0
    }

    /// `StoreKind` of a `SetStoreInfo`'s `info.store_id`.
    static func storeKind(ofStoreInfo payload: Data) throws -> UInt64 {
        let info = try fields(XCTUnwrap(fields(payload)[2]?.first?.bytes))
        let storeId = try fields(XCTUnwrap(info[2]?.first?.bytes))
        return storeId[1]?.first?.varint ?? 0
    }

    static func le(_ bytes: [UInt8], _ at: Int, _ count: Int) -> UInt64 {
        (0..<count).reduce(UInt64(0)) { $0 | UInt64(bytes[at + $1]) << (8 * UInt64($1)) }
    }

    struct Value {
        var varint: UInt64?
        var bytes: Data?
        var string: String? { bytes.flatMap { String(data: $0, encoding: .utf8) } }
    }

    /// Protobuf fields by number (varint, fixed64 as varint, length-delimited as bytes).
    static func fields(_ data: Data) throws -> [Int: [Value]] {
        let bytes = [UInt8](data)
        var position = 0
        func varint() throws -> UInt64 {
            var value: UInt64 = 0, shift: UInt64 = 0
            while true {
                guard position < bytes.count else { throw ReaderError() }
                let byte = bytes[position]
                position += 1
                value |= UInt64(byte & 0x7F) << shift
                if byte < 0x80 { return value }
                shift += 7
            }
        }
        var result: [Int: [Value]] = [:]
        while position < bytes.count {
            let key = try varint()
            let value: Value
            switch key & 7 {
            case 0: value = Value(varint: try varint())
            case 1:
                value = Value(varint: le(bytes, position, 8))
                position += 8
            case 2:
                let length = Int(try varint())
                value = Value(bytes: Data(bytes[position..<position + length]))
                position += length
            default: throw ReaderError()
            }
            result[Int(key >> 3), default: []].append(value)
        }
        return result
    }

    static func chunk(from payload: Data) throws -> ParsedChunk {
        let message = try fields(payload)
        let ipc = [UInt8](try message[5]?.first?.bytes ?? { throw ReaderError() }())
        let chunkId = try fields(message[6]?.first?.bytes ?? Data())
        let tuid = RerunTuid(timeNanos: chunkId[1]?.first?.varint ?? 0, inc: chunkId[2]?.first?.varint ?? 0)

        var position = 0
        var types: [UInt8] = []
        var schema: [String: String] = [:]
        var names: [String] = []
        var rows = 0
        while true {
            guard le(ipc, position, 4) == 0xFFFF_FFFF else { throw ReaderError() }
            let length = Int(le(ipc, position + 4, 4))
            position += 8
            if length == 0 { break } // end of stream
            XCTAssertEqual(position % 8, 0)
            let flat = FlatReader(bytes: Array(ipc[position..<position + length]))
            let root = flat.root
            let type = UInt8(flat.scalar(root, slot: 1, size: 1))
            let bodyLength = Int(flat.scalar(root, slot: 3, size: 8))
            let header = try XCTUnwrap(flat.table(root, slot: 2))
            types.append(type)
            if type == 1 {
                schema = flat.keyValues(header, slot: 2)
                names = flat.tables(header, slot: 1).map { flat.string($0, slot: 0) ?? "" }
            } else if type == 3 {
                rows = Int(flat.scalar(header, slot: 0, size: 8))
            }
            position += length + bodyLength
        }
        XCTAssertEqual(position, ipc.count, "nothing after the end-of-stream marker")
        return ParsedChunk(
            entityPath: schema["rerun:entity_path"] ?? "",
            chunkId: tuid.description,
            isStatic: message[7]?.first?.varint == 1,
            compression: message[2]?.first?.varint ?? 0,
            encoding: message[4]?.first?.varint ?? 0,
            uncompressedSize: Int(message[3]?.first?.varint ?? 0),
            ipcLength: ipc.count,
            ipc: Data(ipc),
            messageTypes: types,
            schema: schema,
            fieldNames: names,
            rowCount: rows
        )
    }

    /// Reads flatbuffers the standard way (vtables, backward soffsets, forward uoffsets),
    /// independently of the writer.
    struct FlatReader {
        var bytes: [UInt8]

        var root: Int { Int(le(bytes, 0, 4)) }

        func fieldOffset(_ table: Int, slot: Int) -> Int? {
            let vtable = table - Int(Int32(bitPattern: UInt32(le(bytes, table, 4))))
            let vtableSize = Int(le(bytes, vtable, 2))
            let entry = 4 + 2 * slot
            guard entry < vtableSize else { return nil }
            let offset = Int(le(bytes, vtable + entry, 2))
            return offset == 0 ? nil : table + offset
        }

        func scalar(_ table: Int, slot: Int, size: Int) -> UInt64 {
            fieldOffset(table, slot: slot).map { le(bytes, $0, size) } ?? 0
        }

        func deref(_ position: Int) -> Int { position + Int(le(bytes, position, 4)) }

        func table(_ table: Int, slot: Int) -> Int? { fieldOffset(table, slot: slot).map(deref) }

        func string(_ table: Int, slot: Int) -> String? {
            self.table(table, slot: slot).map { start in
                let length = Int(le(bytes, start, 4))
                return String(decoding: bytes[start + 4..<start + 4 + length], as: UTF8.self)
            }
        }

        func tables(_ table: Int, slot: Int) -> [Int] {
            guard let vector = self.table(table, slot: slot) else { return [] }
            return (0..<Int(le(bytes, vector, 4))).map { deref(vector + 4 + 4 * $0) }
        }

        func keyValues(_ table: Int, slot: Int) -> [String: String] {
            Dictionary(uniqueKeysWithValues: tables(table, slot: slot).map {
                (string($0, slot: 0) ?? "", string($0, slot: 1) ?? "")
            })
        }
    }
}
