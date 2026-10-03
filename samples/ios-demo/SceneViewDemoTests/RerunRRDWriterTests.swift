import CoreGraphics
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

    func testMessagesTileTheFileAndStartWithStoreInfo() throws {
        let messages = try Self.messages(in: RerunRRDWriter.data(for: scene(), recordingId: recordingId))
        XCTAssertEqual(messages.first?.kind, 1, "SetStoreInfo first")
        XCTAssertEqual(messages.filter { $0.kind == 1 }.count, 1)
        XCTAssertTrue(messages.dropFirst().allSatisfy { $0.kind == 2 }, "then only ArrowMsg")
    }

    func testStoreInfoNamesTheApplicationAndRecording() throws {
        let messages = try Self.messages(in: RerunRRDWriter.data(
            for: scene(), applicationId: "my_app", recordingId: recordingId
        ))
        let info = try Self.fields(messages[0].payload)
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

    // MARK: - Bytes the Android writer emits

    /// `RerunRrdWriter.kt`'s `TUID_EPOCH_NANOS`, and its `RerunTuidSequence` seed for
    /// ``recordingId``: the UUID's high 64 bits masked to 48.
    private static let tuidEpoch: UInt64 = 1_767_225_600_000_000_000
    private static let tuidSeed: UInt64 = 0x04E0_4F89_11D3

    /// `rerun.common.v1alpha1.StoreId` as Android's `storeId` writes it: kind 1 (recording),
    /// the lowercase recording id, then the application id in its own message.
    private static let storeId: [UInt8] = [0x08, 0x01, 0x12, 0x24]
        + Array("3f2504e0-4f89-11d3-9a0c-0305e82c3301".utf8)
        + [0x1A, 0x15, 0x0A, 0x13] + Array("sceneview_ar_replay".utf8)

    /// The stream header, the first `MessageHeader` and the whole `SetStoreInfo`, assembled
    /// here field by field from `RerunRrdWriter.kt` (`streamHeader`, `appendMessage`,
    /// `setStoreInfo`). The store source label is the only byte run that differs between the
    /// platforms: `SceneView Android` there.
    func testStreamAndStoreInfoAreByteForByteWhatTheAndroidWriterEmits() throws {
        let data = try RerunRRDWriter.data(for: scene(), recordingId: recordingId)
        let source = Array("SceneView iOS".utf8)
        var expected: [UInt8] = Array("RRF2".utf8) + [0, 38, 1, 0] + [0, 2, 0, 0]
        expected += Self.le64(1) + Self.le64(UInt64(101 + source.count)) // MessageHeader: kind, length.
        expected += [0x0A, 0x12, 0x09] + Self.le64(Self.tuidEpoch) + [0x11] + Self.le64(Self.tuidSeed + 1) // row_id
        expected += [0x12, UInt8(79 + source.count)] // info
        expected += [0x12, 0x3F] + Self.storeId
        expected += [0x2A, UInt8(6 + source.count), 0x08, 0x06, 0x12, UInt8(2 + source.count), 0x0A, UInt8(source.count)]
        expected += source
        expected += [0x32, 0x04, 0x08, 0x80, 0xCC, 0x04] // crate_version_bits of 0.38.1
        XCTAssertEqual(Self.storeId.count, 0x3F)
        XCTAssertEqual(Array(data.prefix(expected.count)), expected)
        XCTAssertEqual(Array(data.dropFirst(expected.count).prefix(8)), Self.le64(2), "an ArrowMsg follows")
    }

    /// The `world/points/live` message as Android's `arrowMessage` and `liveChunk` write it:
    /// the envelope bytes around the IPC stream, the chunk and row ids in sequence, and each
    /// column's values as contiguous little-endian runs.
    func testLivePointsMessageCarriesTheBytesTheAndroidWriterEmits() throws {
        var recorded = scene()
        recorded.pointObservations = [.init(time: 1.25, points: [1, 2]), .init(time: 0.5, points: [0])]
        let messages = try Self.messages(in: RerunRRDWriter.data(for: recorded, recordingId: recordingId))
        let parsed = try messages.dropFirst().map { try Self.chunk(from: $0.payload) }
        let index = try XCTUnwrap(parsed.firstIndex { $0.entityPath == "/world/points/live" })
        XCTAssertEqual(parsed[index - 1].entityPath, "/world/points", "right after the static map, as on Android")

        // One id for SetStoreInfo, then a chunk id and one row id per row for every chunk.
        let chunkInc = Self.tuidSeed + 1 + UInt64(parsed[..<index].reduce(0) { $0 + 1 + $1.rowCount }) + 1
        let payload = [UInt8](messages[index + 1].payload)
        let ipc = parsed[index].ipc
        var head: [UInt8] = [0x0A, 0x3F] + Self.storeId
        head += [0x10, 0x01, 0x18] + Self.varint(ipc.count) + [0x20, 0x01, 0x2A] + Self.varint(ipc.count)
        head += [0xFF, 0xFF, 0xFF, 0xFF]
        XCTAssertEqual(Array(payload.prefix(head.count)), head, "store id, no compression, size, Arrow IPC, payload")
        var tail: [UInt8] = [0x32, 0x12, 0x09] + Self.le64(Self.tuidEpoch)
        tail += [0x11] + Self.le64(chunkInc) + [0x38, 0x00]
        XCTAssertEqual(Array(payload.suffix(tail.count)), tail, "chunk id, then is_static = false")

        func tuid(_ inc: UInt64) -> [UInt8] { Array(Self.le64(Self.tuidEpoch).reversed()) + Array(Self.le64(inc).reversed()) }
        XCTAssertNotNil(ipc.range(of: Data(tuid(chunkInc + 1) + tuid(chunkInc + 2))), "row ids, big-endian")
        XCTAssertNotNil(ipc.range(of: Data(Self.le64(500_000_000) + Self.le64(1_250_000_000))), "nanoseconds, sorted")
        let positions: [Float] = [0, 0, 0, 1, 0, 0, 0, 1, 0]
        XCTAssertNotNil(ipc.range(of: Data(positions.flatMap { Self.le32($0.bitPattern) })), "the sighted map points, row after row")
        let radius = Self.le32(Float(0.008).bitPattern)
        XCTAssertNotNil(ipc.range(of: Data(radius + radius)), "radius 8 mm")
        XCTAssertNotNil(ipc.range(of: Data([0xFF, 0x0B, 0x9E, 0xF5, 0xFF, 0x0B, 0x9E, 0xF5])), "#F59E0B, opaque")
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

    private func chunks(of scene: RerunExportScene) throws -> [ParsedChunk] {
        try Self.messages(in: RerunRRDWriter.data(for: scene, recordingId: recordingId))
            .filter { $0.kind == 2 }
            .map { try Self.chunk(from: $0.payload) }
    }

    static func messages(in data: Data) throws -> [Message] {
        let bytes = [UInt8](data)
        var position = 12
        var messages: [Message] = []
        while position < bytes.count {
            guard position + 16 <= bytes.count else { throw ReaderError() }
            let kind = le(bytes, position, 8), length = Int(le(bytes, position + 8, 8))
            position += 16
            guard position + length <= bytes.count else { throw ReaderError() }
            messages.append(Message(kind: kind, payload: Data(bytes[position..<position + length])))
            position += length
        }
        return messages
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
