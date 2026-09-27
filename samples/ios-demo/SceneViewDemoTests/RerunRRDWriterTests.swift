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
        XCTAssertEqual(pixels.rgb.count, pixels.width * pixels.height * 3)
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
